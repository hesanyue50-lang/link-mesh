package com.jiuyue.lanmesh.net.ls;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 以 LocalSend 接收方身份监听 53317，处理官方客户端的推送。
 *
 * 只实现官方 v2 的最小集合：register / info / prepare-upload / upload / cancel。
 * 官方客户端默认 HTTPS + mTLS，我们只提供 HTTP，对方看到 announce 里的
 * protocol=http 会主动用 http 连过来。
 *
 * 协议实现上的几个硬约束（都踩过）：
 *  - Content-Length: 0 与"没有 Content-Length"必须区分，否则 0 字节文件会等到读超时
 *  - 不支持 Transfer-Encoding: chunked，遇到必须拒绝，不能把 chunk 头当正文写进文件
 *  - 落盘先写 .part，成功后 rename；任何失败出口都要删掉半截文件
 *  - token 用完即焚，否则同一会话可以无限往磁盘写
 */
public class LsServer {

    private static final String TAG = "LanMeshLs";
    private static final int HANDLER_TIMEOUT = 60000;
    /** 单个请求头总字节上限，防止慢速灌头把线程拖死 */
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_JSON_BODY = 512 * 1024;

    private final Context ctx;
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile int retryCount;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    /** sessionId -> 会话。所有访问都在 synchronized(sessions) 内。 */
    private final Map<String, Session> sessions = new HashMap<String, Session>();

    private static class Session {
        String sessionId;
        String remoteIp;
        String alias;
        final Map<String, String> tokens = new HashMap<String, String>();
        final Map<String, Long> sizes = new HashMap<String, Long>();
        final Map<String, String> names = new HashMap<String, String>();
        long createdAt = System.currentTimeMillis();
    }

    public interface Callback {
        void onFileReceived(String alias, String remoteIp, String fileName, long size,
                            File saved, String error);
    }

    private final Callback callback;

    public LsServer(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.callback = cb;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        retryCount = 0;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                bindAndAccept();
            }
        });
    }

    private void bindAndAccept() {
        try {
            ServerSocket s = new ServerSocket(LsConst.HTTP_PORT);
            s.setReuseAddress(true);
            server = s;
            retryCount = 0;
            Log.i(TAG, "localSend server on " + LsConst.HTTP_PORT);
            if (!running) {
                // start() 与 bind 之间被 stop()：立刻释放端口，否则永远占着
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
                server = null;
                return;
            }
            while (running) {
                final Socket c = s.accept();
                c.setSoTimeout(HANDLER_TIMEOUT);
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(c);
                    }
                });
            }
        } catch (Throwable t) {
            if (running) {
                Log.w(TAG, "cannot listen on " + LsConst.HTTP_PORT, t);
                scheduleRetry();
            }
        }
    }

    private void scheduleRetry() {
        if (retryCount >= 3) {
            Log.w(TAG, "give up listening after 3 attempts");
            running = false;
            return;
        }
        retryCount++;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (Throwable ignored) {
                }
                if (running) bindAndAccept();
            }
        }, "lanmesh-ls-bind-retry").start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        server = null;
        synchronized (sessions) {
            sessions.clear();
        }
    }

    public boolean isRunning() {
        return running && server != null && !server.isClosed();
    }

    // ---------------- 请求处理 ----------------

    private void handle(Socket c) {
        try {
            InputStream raw = new BufferedInputStream(c.getInputStream(), 16 * 1024);
            String requestLine = readLine(raw, 4 * 1024);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return;
            String method = parts[0];
            String path = parts[1];

            // contentLength 用 -1 表示"没有这个头"，与显式的 0 区分开
            long contentLength = -1;
            boolean chunked = false;
            int headerBytes = requestLine.length();
            String line;
            while ((line = readLine(raw, MAX_HEADER_BYTES)) != null && line.length() > 0) {
                headerBytes += line.length();
                if (headerBytes > MAX_HEADER_BYTES) {
                    json(c, 431, "{\"message\":\"header too large\"}");
                    return;
                }
                int i = line.indexOf(':');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim().toLowerCase(Locale.US);
                String v = line.substring(i + 1).trim();
                if ("content-length".equals(k)) {
                    try {
                        contentLength = Long.parseLong(v);
                    } catch (Throwable ignored) {
                    }
                } else if ("transfer-encoding".equals(k)) {
                    chunked = v.toLowerCase(Locale.US).contains("chunked");
                }
            }

            String remoteIp = c.getInetAddress() == null
                    ? "?" : c.getInetAddress().getHostAddress();

            // chunked 正文不解析长度直接写文件会得到损坏的文件，宁可明确拒绝
            if (chunked) {
                json(c, 400, "{\"message\":\"chunked not supported\"}");
                return;
            }

            if (path.startsWith(LsConst.API + "/register")) {
                json(c, 200, LsIdentity.infoJson(ctx).toString());
            } else if (path.startsWith(LsConst.API + "/info")) {
                json(c, 200, LsIdentity.infoJson(ctx).toString());
            } else if (path.startsWith(LsConst.API + "/prepare-upload")) {
                byte[] body = readBody(raw, contentLength, MAX_JSON_BODY);
                if (body == null) {
                    json(c, 413, "{\"message\":\"body too large\"}");
                    return;
                }
                prepareUpload(c, remoteIp, new String(body, "UTF-8"));
            } else if (path.startsWith(LsConst.API + "/upload")) {
                upload(c, raw, remoteIp, path, contentLength);
            } else if (path.startsWith(LsConst.API + "/cancel")) {
                readBody(raw, contentLength, MAX_JSON_BODY);
                cancel(path);
                json(c, 200, "{}");
            } else {
                json(c, 404, "{\"message\":\"not found\"}");
            }
        } catch (Throwable t) {
            Log.w(TAG, "handle failed", t);
        } finally {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void prepareUpload(Socket c, String remoteIp, String body) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (Throwable t) {
            json(c, 400, "{\"message\":\"bad json\"}");
            return;
        }
        JSONObject info = req.optJSONObject("info");
        JSONObject files = req.optJSONObject("files");
        if (files == null || files.length() == 0) {
            json(c, 400, "{\"message\":\"no files\"}");
            return;
        }
        String alias = info == null ? remoteIp : info.optString("alias", remoteIp);
        if (alias == null || alias.length() == 0) alias = remoteIp;

        boolean auto = com.jiuyue.lanmesh.core.AppCore.get(ctx).prefs.autoAcceptFiles();
        if (!auto) {
            json(c, 403, "{\"message\":\"receive disabled\"}");
            if (callback != null) {
                callback.onFileReceived(alias, remoteIp, "(请求发送文件)", 0, null,
                        "对方请求发送文件，请在设置里打开「自动接收文件」");
            }
            return;
        }

        Session s = new Session();
        s.sessionId = UUID.randomUUID().toString();
        s.remoteIp = remoteIp;
        s.alias = alias;
        JSONObject tokens = new JSONObject();
        Iterator<String> it = files.keys();
        while (it.hasNext()) {
            String fid = it.next();
            JSONObject f = files.optJSONObject(fid);
            if (f == null) continue;
            String token = UUID.randomUUID().toString();
            s.tokens.put(fid, token);
            s.sizes.put(fid, f.optLong("size", -1));
            s.names.put(fid, f.optString("fileName", "file"));
            tokens.put(fid, token);
        }
        synchronized (sessions) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Session>> e = sessions.entrySet().iterator();
            while (e.hasNext()) {
                if (now - e.next().getValue().createdAt > 10 * 60 * 1000L) e.remove();
            }
            sessions.put(s.sessionId, s);
        }
        JSONObject ok = new JSONObject();
        ok.put("sessionId", s.sessionId);
        ok.put("files", tokens);
        json(c, 200, ok.toString());
    }

    private void upload(Socket c, InputStream in, String remoteIp, String path, long contentLength)
            throws Exception {
        Map<String, String> q = query(path);
        String sessionId = q.get("sessionId");
        String fileId = q.get("fileId");
        String token = q.get("token");

        Session s;
        synchronized (sessions) {
            s = sessions.get(sessionId);
        }
        if (s == null || fileId == null || token == null) {
            drain(c, in, contentLength);
            json(c, 403, "{\"message\":\"Invalid token or IP address\"}");
            return;
        }
        String expectToken;
        long expect;
        String name;
        synchronized (sessions) {
            expectToken = s.tokens.get(fileId);
            expect = s.sizes.containsKey(fileId) ? s.sizes.get(fileId) : -1;
            name = s.names.containsKey(fileId) ? s.names.get(fileId) : "file";
        }
        if (expectToken == null || !token.equals(expectToken)
                || (s.remoteIp != null && !s.remoteIp.equals(remoteIp))) {
            drain(c, in, contentLength);
            json(c, 403, "{\"message\":\"Invalid token or IP address\"}");
            return;
        }

        File dir = receiveDir();
        if (dir == null) {
            drain(c, in, contentLength);
            json(c, 500, "{\"message\":\"no storage\"}");
            return;
        }

        String safe = com.jiuyue.lanmesh.core.Util.safeFileName(name);
        // 先写 .part，成功才改名：半截文件绝不能以正式名字留在下载目录里
        Part part = newPart(dir, safe);
        if (part == null) {
            drain(c, in, contentLength);
            json(c, 500, "{\"message\":\"cannot create file\"}");
            return;
        }

        long written = 0;
        FileOutputStream fo = null;
        boolean ok = false;
        try {
            fo = new FileOutputStream(part.tmp);
            byte[] buf = new byte[64 * 1024];
            while (true) {
                // Content-Length: 0 是合法的空文件，不能落进下面的 read 阻塞
                if (contentLength == 0) break;
                int want = buf.length;
                if (contentLength > 0) {
                    long left = contentLength - written;
                    if (left <= 0) break;
                    want = (int) Math.min(buf.length, left);
                }
                int n = in.read(buf, 0, want);
                if (n <= 0) break;
                fo.write(buf, 0, n);
                written += n;
            }
            fo.flush();
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "write failed", t);
        } finally {
            try {
                if (fo != null) fo.close();
            } catch (Throwable ignored) {
            }
        }

        if (!ok) {
            part.tmp.delete();
            json(c, 500, "{\"message\":\"write failed\"}");
            return;
        }
        if (contentLength >= 0 && written != contentLength) {
            part.tmp.delete();
            json(c, 500, "{\"message\":\"length mismatch\"}");
            return;
        }
        if (expect >= 0 && written != expect) {
            part.tmp.delete();
            json(c, 500, "{\"message\":\"length mismatch\"}");
            return;
        }
        if (!part.tmp.renameTo(part.dst)) {
            part.tmp.delete();
            json(c, 500, "{\"message\":\"rename failed\"}");
            return;
        }

        // token 用完即焚，避免同一会话反复往磁盘写
        synchronized (sessions) {
            s.tokens.remove(fileId);
            if (s.tokens.isEmpty()) sessions.remove(s.sessionId);
        }

        json(c, 200, "{}");
        if (callback != null) {
            callback.onFileReceived(s.alias, remoteIp, part.dst.getName(), written, part.dst, null);
        }
    }

    private void cancel(String path) {
        Map<String, String> q = query(path);
        String sid = q.get("sessionId");
        if (sid == null) return;
        synchronized (sessions) {
            sessions.remove(sid);
        }
    }

    // ---------------- 落盘 ----------------

    private static class Part {
        File tmp;
        File dst;
    }

    /** 原子地占住一个文件名：createNewFile 返回 true 才算拿到。 */
    private static final Object NAME_LOCK = new Object();

    private Part newPart(File dir, String name) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        synchronized (NAME_LOCK) {
            for (int i = 0; i < 1000; i++) {
                String cand = i == 0 ? name : base + "(" + i + ")" + ext;
                // 最终名已存在就换名。原来只判 .part 是否存在，
                // 于是"下载目录已有 photo.jpg"时 renameTo 会原子替换掉它，旧文件直接消失。
                if (new File(dir, cand).exists()) continue;
                File tmp = new File(dir, cand + ".part");
                try {
                    if (tmp.createNewFile()) {
                        Part p = new Part();
                        p.tmp = tmp;
                        p.dst = new File(dir, cand);
                        return p;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 与 App 内其它接收路径保持一致，用户在一个目录里找得到所有文件。 */
    private File receiveDir() {
        try {
            return com.jiuyue.lanmesh.core.StorageUtil.downloadDir(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 工具 ----------------

    private static String readLine(InputStream in, int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > max) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    /** contentLength < 0 时读到 EOF；超过上限返回 null（调用方回 413）。 */
    private static byte[] readBody(InputStream in, long contentLength, int max) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        while (true) {
            if (contentLength >= 0 && total >= contentLength) break;
            int want = buf.length;
            if (contentLength >= 0) want = (int) Math.min(buf.length, contentLength - total);
            int n = in.read(buf, 0, want);
            if (n <= 0) break;
            total += n;
            if (total > max) return null;
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    /** 拒绝请求前把 body 读掉，否则 close 会发 RST，对端看不到我们的响应。 */
    private static void drain(Socket c, InputStream in, long contentLength) {
        try {
            if (contentLength > 0) {
                byte[] buf = new byte[8192];
                long left = contentLength;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) break;
                    left -= n;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Map<String, String> query(String path) {
        Map<String, String> m = new HashMap<String, String>();
        int q = path.indexOf('?');
        if (q < 0) return m;
        String s = path.substring(q + 1);
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                        URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Throwable ignored) {
            }
        }
        return m;
    }

    /**
     * 成功也返回 {}。官方客户端（dio）默认按 JSON 解析响应体，
     * 返回 200 + 空 body 会被它当成解析失败，把成功报成失败。
     */
    private static void json(Socket c, int code, String body) throws IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes("UTF-8");
        OutputStream o = c.getOutputStream();
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(" ").append(reason(code)).append("\r\n");
        h.append("Content-Type: application/json\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        o.write(h.toString().getBytes("UTF-8"));
        o.write(b);
        o.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 431: return "Request Header Fields Too Large";
            default: return "Error";
        }
    }
}
