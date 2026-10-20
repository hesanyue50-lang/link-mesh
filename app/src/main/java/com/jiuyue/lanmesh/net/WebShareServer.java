package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.util.Log;

import com.jiuyue.lanmesh.core.Bus;
import com.jiuyue.lanmesh.core.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 阶段三：浏览器访客（零成本，不需要装任何客户端）。
 *
 * 电脑/平板在同一 Wi-Fi 下打开 http://手机IP:45902 就能：
 *  - 看设备信息与最近文本
 *  - 上传文件到手机（PUT 流式写入，不占内存）
 *  - 下载手机收到的文件
 *  - 发一条文本到手机
 *
 * 为什么自己写 HTTP 而不是引 OkHttp/NanoHTTPD：
 *  - NanoHTTPD 会把 POST body 读进内存，大文件会 OOM；
 *  - 我们只需要 5 个路由，ServerSocket 手写更可控，也不增加依赖体积。
 *
 * 安全：只在局域网监听；不做账号体系（需求明确无账号）。页面会显示
 * "同一网络内任何人都可访问"的提示，避免用户误以为有鉴权。
 */
public class WebShareServer {

    private static final String TAG = "LanMeshWeb";
    public static final int DEFAULT_PORT = 45902;

    private final Context ctx;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;
    private int port = DEFAULT_PORT;

    public WebShareServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public int getPort() {
        return port;
    }

    public void start() {
        if (running) return;
        for (int i = 0; i < 10; i++) {
            int candidate = DEFAULT_PORT + i;
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(candidate));
                server = ss;
                port = candidate;
                break;
            } catch (Throwable ignored) {
            }
        }
        if (server == null) {
            Log.w(TAG, "no free port for web share");
            return;
        }
        running = true;
        pool = Executors.newCachedThreadPool();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "lanmesh-web-accept");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "web share on " + port);
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                s.setSoTimeout(8000);
                s.setTcpNoDelay(true);
                s.setSendBufferSize(256 * 1024);
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(s);
                    }
                });
            } catch (Throwable t) {
                if (running) Log.w(TAG, "accept failed", t);
            }
        }
    }

    // ---------------- 请求解析 ----------------

    private void handle(Socket socket) {
        try {
            InputStream raw = socket.getInputStream();
            BufferedInputStream in = new BufferedInputStream(raw, 16 * 1024);
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return;
            String method = parts[0];
            String path = parts[1];
            String guest = param(path, "g");
            if (guest == null || guest.length() < 4) guest = "guest";

            long contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim().toLowerCase(Locale.US);
                    String v = line.substring(idx + 1).trim();
                    if ("content-length".equals(k)) {
                        try {
                            contentLength = Long.parseLong(v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendHtml(socket, page());
            } else if ("GET".equals(method) && path.startsWith("/api/state")) {
                sendJson(socket, stateJson(guest));
            } else if ("GET".equals(method) && path.startsWith("/app.apk")) {
                sendApk(socket);
            } else if ("GET".equals(method) && path.startsWith("/f?")) {
                String name = param(path, "name");
                sendFile(socket, name);
            } else if ("PUT".equals(method) && path.startsWith("/upload")) {
                String name = param(path, "name");
                receiveFile(socket, in, name, contentLength, guest);
            } else if ("POST".equals(method) && path.startsWith("/api/clear")) {
                int n = cleanupAll(ctx);
                sendJson(socket, "{\"ok\":true,\"removed\":" + n + "}");
            } else if ("POST".equals(method) && path.startsWith("/text")) {
                byte[] body = readBody(in, contentLength, 64 * 1024);
                String msg = new String(body, "UTF-8").trim();
                if (msg.length() > 0) appendText(guest, msg);
                sendJson(socket, "{\"ok\":true}");
            } else {
                sendStatus(socket, 404, "not found");
            }
        } catch (Throwable t) {
            Log.w(TAG, "handle failed", t);
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private byte[] readBody(InputStream in, long length, int limit) throws Exception {
        if (length <= 0) return new byte[0];
        int n = (int) Math.min(length, limit);
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return buf;
    }

    private String param(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        String[] pairs = path.substring(q + 1).split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(p.substring(eq + 1), "UTF-8");
                } catch (Throwable t) {
                    return p.substring(eq + 1);
                }
            }
        }
        return null;
    }

    // ---------------- 路由实现 ----------------

    /** 浏览器上传/中转的文件单独放 web/ 子目录，开关关闭时可以整目录清理。 */
    private File relayDir() {
        File d = new File(receiveDir(), "web");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 清掉网页上传的文件，同时清掉所有 web-* 访客会话的聊天记录。 */
    public void cleanupRelayFiles() {
        cleanupAll(ctx);
    }

    /** 清空所有会话（含软对软记录）+ 网页中转文件。危险操作，需二次确认。 */
    public static int cleanupEverything(Context ctx) {
        try {
            com.jiuyue.lanmesh.core.AppCore core = com.jiuyue.lanmesh.core.AppCore.get(ctx);
            int removed = 0;
            for (String pid : core.messages.peerIds()) {
                core.messages.clear(pid);
                removed++;
            }
            File dir = new File(ctx.getExternalFilesDir("LanMesh"), "web");
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isFile()) f.delete();
                }
            }
            Log.i(TAG, "everything cleaned: sessions=" + removed);
            return removed;
        } catch (Throwable t) {
            Log.w(TAG, "cleanup everything failed", t);
            return 0;
        }
    }

    /** 静态版本：即使网页服务没启动也能清理。返回清理的会话数。 */
    public static int cleanupAll(Context ctx) {
        try {
            com.jiuyue.lanmesh.core.AppCore core = com.jiuyue.lanmesh.core.AppCore.get(ctx);
            int removed = 0;
            for (String pid : core.messages.peerIds()) {
                if (pid != null && pid.startsWith("web-")) {
                    core.messages.clear(pid);
                    removed++;
                }
            }
            File dir = new File(ctx.getExternalFilesDir("LanMesh"), "web");
            File[] list = dir.listFiles();
            int files = 0;
            if (list != null) {
                for (File f : list) {
                    if (f.isFile() && f.delete()) files++;
                }
            }
            Log.i(TAG, "web records cleaned: sessions=" + removed + " files=" + files);
            return removed;
        } catch (Throwable t) {
            Log.w(TAG, "cleanup web records failed", t);
            return 0;
        }
    }

    private File receiveDir() {
        File dir = ctx.getExternalFilesDir("LanMesh");
        if (dir == null) dir = new File(ctx.getFilesDir(), "LanMesh");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void receiveFile(Socket socket, InputStream in, String name, long length, String guest) throws Exception {
        if (name == null || name.length() == 0) {
            sendStatus(socket, 400, "missing name");
            return;
        }
        String safe = Util.safeFileName(name);
        File target = Util.uniqueFile(relayDir(), safe);
        FileOutputStream out = new FileOutputStream(target);
        long left = length;
        byte[] buf = new byte[256 * 1024];
        try {
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) break;
                out.write(buf, 0, r);
                left -= r;
            }
        } finally {
            out.flush();
            out.close();
        }
        addFileMessage(guest, target);
        String json = "{\"ok\":true,\"name\":\"" + safe.replace("\"", "") + "\"}";
        sendJson(socket, json);
        Bus.post(Bus.TOAST, "浏览器上传: " + safe + " (" + Util.humanSize(target.length()) + ")");
    }

    /** 把当前安装包的 APK 直接发给浏览器（用于给未安装的用户下载）。 */
    private void sendApk(Socket socket) {
        java.io.InputStream in = null;
        try {
            android.content.pm.ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0);
            File apk = new File(ai.sourceDir);
            if (!apk.isFile()) {
                sendStatus(socket, 404, "apk not found");
                return;
            }
            OutputStream out = socket.getOutputStream();
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/vnd.android.package-archive\r\n"
                    + "Content-Length: " + apk.length() + "\r\n"
                    + "Content-Disposition: attachment; filename=\"LinkMesh.apk\"\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(header.getBytes("UTF-8"));
            in = new java.io.FileInputStream(apk);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "send apk failed", t);
        } finally {
            Util.closeQuietly(in);
        }
    }

    private void sendFile(Socket socket, String name) throws Exception {
        if (name == null) {
            sendStatus(socket, 400, "missing name");
            return;
        }
        String safe = Util.safeFileName(name);
        // 网页上传的文件在 web/ 子目录，软对软接收的文件在 LanMesh/ 根目录；
        // 先找 web/，再回退根目录，否则点下载永远 404。
        File f = new File(relayDir(), safe);
        if (!f.isFile()) f = new File(receiveDir(), safe);
        if (!f.isFile()) {
            sendStatus(socket, 404, "not found");
            return;
        }
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + f.length() + "\r\n"
                + "Content-Disposition: attachment; filename=\"" + safe + "\"\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        FileInputStream fin = new FileInputStream(f);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
        fin.close();
        out.flush();
    }

    private void sendHtml(Socket socket, String html) throws Exception {
        byte[] body = html.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private void sendJson(Socket socket, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private void sendStatus(Socket socket, int code, String text) throws Exception {
        byte[] body = text.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 " + code + " ERROR\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    // ---------------- 数据 ----------------

    private File textFile() {
        return new File(ctx.getFilesDir(), "web_texts.txt");
    }

    private static final String WEB_PEER = "web-guest";

    private void appendText(String guest, String msg) {
        try {
            com.jiuyue.lanmesh.model.ChatMessage m = new com.jiuyue.lanmesh.model.ChatMessage();
            m.peerDeviceId = "web-" + guest;
            m.direction = com.jiuyue.lanmesh.model.ChatMessage.DIR_IN;
            m.type = com.jiuyue.lanmesh.model.ChatMessage.TYPE_TEXT;
            m.text = msg.replace("\n", " ");
            m.status = com.jiuyue.lanmesh.model.ChatMessage.ST_SENT;
            com.jiuyue.lanmesh.core.AppCore.get(ctx).messages.append(m);
            Bus.post(Bus.TOAST, "浏览器消息: " + msg);
        } catch (Throwable t) {
            Log.w(TAG, "append text failed", t);
        }
    }

    private void addFileMessage(String guest, File f) {
        try {
            com.jiuyue.lanmesh.model.ChatMessage m = new com.jiuyue.lanmesh.model.ChatMessage();
            m.peerDeviceId = "web-" + guest;
            m.direction = com.jiuyue.lanmesh.model.ChatMessage.DIR_IN;
            m.type = com.jiuyue.lanmesh.model.ChatMessage.TYPE_FILE;
            m.fileName = f.getName();
            m.fileSize = f.length();
            m.localPath = f.getAbsolutePath();
            m.status = com.jiuyue.lanmesh.model.ChatMessage.ST_DONE;
            m.transferMode = "web";
            com.jiuyue.lanmesh.core.AppCore.get(ctx).messages.append(m);
        } catch (Throwable t) {
            Log.w(TAG, "add file message failed", t);
        }
    }

    private List<String> recentTexts() {
        List<String> out = new ArrayList<String>();
        try {
            File f = textFile();
            if (!f.isFile()) return out;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
                if (out.size() > 50) out.remove(0);
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return out;
    }

    private String stateJson(String guest) {
        try {
            JSONObject o = new JSONObject();
            com.jiuyue.lanmesh.core.AppCore core = com.jiuyue.lanmesh.core.AppCore.get(ctx);
            com.jiuyue.lanmesh.core.Prefs prefs = core.prefs;
            String guestName = "访客-" + (guest.length() > 4 ? guest.substring(guest.length() - 4) : guest);
            o.put("name", prefs.nickname());
            o.put("shortId", prefs.shortId());
            o.put("ip", Util.safe(Util.localIpv4(ctx)));
            o.put("port", port);
            o.put("me", guestName);
            o.put("phoneName", prefs.nickname());

            // 聚合所有会话（软对软聊天 + 所有网页访客），按时间排序取最近 120 条。
            // 之前只读 web-<当前 guest>，所以别的设备/别的访客发的消息网页端永远看不到。
            java.util.List<com.jiuyue.lanmesh.model.ChatMessage> all =
                    new java.util.ArrayList<com.jiuyue.lanmesh.model.ChatMessage>();
            for (String pid : core.messages.peerIds()) {
                // 只聚合网页访客的会话；软对软的聊天不映射到网页端
                if (pid == null || !pid.startsWith("web-")) continue;
                try {
                    all.addAll(core.messages.load(pid));
                } catch (Throwable ignored) {
                }
            }
            java.util.Collections.sort(all, new java.util.Comparator<com.jiuyue.lanmesh.model.ChatMessage>() {
                @Override
                public int compare(com.jiuyue.lanmesh.model.ChatMessage a, com.jiuyue.lanmesh.model.ChatMessage b) {
                    if (a.timestamp == b.timestamp) return 0;
                    return a.timestamp < b.timestamp ? -1 : 1;
                }
            });
            int from = Math.max(0, all.size() - 120);
            JSONArray msgs = new JSONArray();
            for (int i = from; i < all.size(); i++) {
                com.jiuyue.lanmesh.model.ChatMessage m = all.get(i);
                JSONObject item = new JSONObject();
                item.put("id", m.msgId);
                item.put("type", m.type);
                item.put("time", m.timestamp);
                item.put("dir", m.direction);
                boolean mine = ("web-" + guest).equals(m.peerDeviceId)
                        || com.jiuyue.lanmesh.model.ChatMessage.DIR_OUT.equals(m.direction);
                item.put("mine", mine);
                String sender;
                if (m.peerDeviceId != null && m.peerDeviceId.startsWith("web-")) {
                    String g = m.peerDeviceId.substring(4);
                    sender = "访客-" + (g.length() > 4 ? g.substring(g.length() - 4) : g);
                } else if (com.jiuyue.lanmesh.model.ChatMessage.DIR_IN.equals(m.direction)) {
                    com.jiuyue.lanmesh.model.Peer p = core.peers.get(m.peerDeviceId);
                    sender = p != null ? p.displayName() : Util.shortId(m.peerDeviceId);
                } else {
                    sender = prefs.nickname();
                }
                item.put("from", sender);
                if (com.jiuyue.lanmesh.model.ChatMessage.TYPE_FILE.equals(m.type)) {
                    item.put("name", m.fileName == null ? "file" : m.fileName);
                    item.put("size", m.fileSize);
                    item.put("human", Util.humanSize(m.fileSize));
                } else {
                    item.put("text", m.text == null ? "" : m.text);
                }
                msgs.put(item);
            }
            o.put("messages", msgs);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    // ---------------- 页面 ----------------

    private String page() {
        return "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<title>Link Mesh</title><style>"
                + ":root{--bg:#0b0c0e;--card:#141619;--stroke:#2a2e34;--text:#eceef2;--dim:#9ba2ac;"
                + "--accent:#35e0b0;--on:#04120e;--mine:#1f6f5a}"
                + "@media(prefers-color-scheme:light){:root{--bg:#f4f5f7;--card:#fff;--stroke:#e0e3e8;"
                + "--text:#14171b;--dim:#5c636d;--accent:#0e9e7a;--on:#fff;--mine:#d7f2ea}}"
                + "*{box-sizing:border-box;margin:0;padding:0}"
                + "html,body{height:100%;overscroll-behavior:none}"
                + "body{background:var(--bg);color:var(--text);"
                + "font:14px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC',sans-serif}"
                + ".app{display:flex;flex-direction:column;height:100vh;height:100dvh;max-width:820px;"
                + "margin:0 auto;overflow:hidden}"
                + "header{flex:none;padding:16px 18px 12px;border-bottom:1px solid var(--stroke);"
                + "display:flex;align-items:center;gap:10px}"
                + ".dot{width:8px;height:8px;border-radius:50%;background:var(--accent);flex:none}"
                + ".dl{flex:none;padding:8px 12px;border-radius:10px;background:var(--accent);"
                + "color:var(--on);font-size:12px;font-weight:600;text-decoration:none}"
                + ".who{flex:1;min-width:0}h1{font-size:15px;font-weight:600;white-space:nowrap;overflow:hidden;"
                + "text-overflow:ellipsis}"
                + ".sub{font-size:11px;color:var(--dim);font-family:ui-monospace,Menlo,Consolas,monospace;"
                + "white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
                + ".msgs{flex:1 1 auto;min-height:0;overflow-y:auto;-webkit-overflow-scrolling:touch;"
                + "padding:16px 16px 8px;display:flex;flex-direction:column;gap:10px}"
                + ".msg{max-width:78%;padding:10px 13px;border-radius:18px;border:1px solid var(--stroke);"
                + "word-break:break-word;white-space:pre-wrap;flex:none}"
                + ".msg.mine{align-self:flex-end;background:var(--mine);border-color:transparent}"
                + ".msg.other{align-self:flex-start;background:var(--card)}"
                + ".from{font-size:10px;color:var(--dim);margin-bottom:4px;font-family:ui-monospace,monospace}"
                + ".msg time{display:block;font-size:10px;color:var(--dim);margin-top:5px;"
                + "font-family:ui-monospace,monospace}"
                + ".fname{font-size:13px;margin-bottom:3px;word-break:break-all}"
                + ".fmeta{font-size:11px;color:var(--dim);font-family:ui-monospace,monospace}"
                + ".msg a{color:var(--accent);font-size:12px;text-decoration:none;display:inline-block;margin-top:6px}"
                + ".progress{height:4px;background:var(--stroke);border-radius:2px;margin-top:8px;overflow:hidden}"
                + ".progress i{display:block;height:100%;width:0;background:var(--accent);transition:width .12s linear}"
                + ".composer{flex:none;display:flex;gap:8px;align-items:center;padding:10px 12px "
                + "calc(10px + env(safe-area-inset-bottom));border-top:1px solid var(--stroke);background:var(--bg)}"
                + ".icon{width:48px;height:48px;border-radius:50%;border:1px solid var(--stroke);"
                + "background:var(--card);color:var(--accent);font-size:24px;line-height:1;cursor:pointer;flex:none}"
                + "input[type=text]{flex:1;min-width:0;background:var(--card);border:1px solid var(--stroke);"
                + "border-radius:14px;padding:11px 13px;color:var(--text);font-size:14px;outline:none}"
                + "input[type=text]:focus{border-color:var(--accent)}"
                + ".send{flex:none;background:var(--accent);color:var(--on);border:0;border-radius:14px;"
                + "padding:11px 16px;font-size:14px;font-weight:600;cursor:pointer}"
                + ".empty{color:var(--dim);text-align:center;margin-top:40px;font-size:13px;line-height:1.8}"
                + "</style></head><body><div class='app'>"
                + "<header><div class='dot'></div><div class='who'><h1 id='name'>Link Mesh</h1>"
                + "<div class='sub' id='sub'>连接中…</div></div>"
                + "<a class='dl' href='/app.apk' download>下载 App</a></header>"
                + "<div class='msgs' id='msgs'><div class='empty'>还没有消息<br>发一条试试，或点左下角 + 上传文件</div></div>"
                + "<div class='composer'>"
                + "<button class='icon' id='btnFile'>+</button>"
                + "<input type='file' id='f' style='display:none'>"
                + "<input type='text' id='msg' placeholder='输入消息…'>"
                + "<button class='send' id='btnSend'>发送</button>"
                + "</div></div><script>"
                + "var G=(function(){var v=localStorage.getItem('lm_guest');"
                + "if(!v){v='g'+Math.random().toString(36).slice(2,10);localStorage.setItem('lm_guest',v);}return v;})();"
                + "var Q='?g='+encodeURIComponent(G);"
                + "function esc(s){return (s||'').replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}"
                + "function fmt(t){var d=new Date(t);function p(n){return n<10?'0'+n:''+n;}"
                + "return p(d.getMonth()+1)+'-'+p(d.getDate())+' '+p(d.getHours())+':'+p(d.getMinutes());}"
                + "function human(b){return b<1024?b+' B':(b<1048576?(b/1024).toFixed(1)+' KB':(b/1048576).toFixed(1)+' MB');}"
                + "var box=document.getElementById('msgs');var lastCount=-1;"
                + "function nearBottom(){return box.scrollHeight-box.scrollTop-box.clientHeight<120;}"
                + "function toBottom(){requestAnimationFrame(function(){box.scrollTop=box.scrollHeight;});}"
                + "function refresh(){var keep=nearBottom();"
                + "fetch('/api/state'+Q).then(function(r){return r.json();}).then(function(d){"
                + "document.getElementById('name').textContent=d.name||'';"
                + "document.getElementById('sub').textContent=(d.me?d.me+' · ':'')+(d.shortId||'')+' · '+(d.ip||'')+':'+d.port;"
                + "var list=d.messages||[];"
                + "if(list.length!==lastCount){lastCount=list.length;var html='';"
                + "list.forEach(function(m){var cls=m.mine?'mine':'other';"
                + "html+='<div class=msg '+cls+'><div class=from>'+esc(m.from||'')+'</div>';"
                + "if(m.type==='file'){html+='<div class=fname>'+esc(m.name)+'</div>';"
                + "html+='<div class=fmeta>'+m.human+'</div>';"
                + "html+='<a href=\"/f?name='+encodeURIComponent(m.name)+'\" download>下载</a>';}"
                + "else{html+=esc(m.text);}"
                + "html+='<time>'+fmt(m.time)+'</time></div>';});"
                + "box.innerHTML=html||'<div class=empty>还没有消息<br>发一条试试，或点左下角 + 上传文件</div>';"
                + "if(keep)toBottom();}}).catch(function(){});}"
                + "function send(){var el=document.getElementById('msg');var v=el.value.trim();if(!v)return;"
                + "el.value='';fetch('/text'+Q,{method:'POST',body:v}).then(function(){lastCount=-1;refresh();});}"
                + "function upload(){var f=document.getElementById('f').files[0];if(!f)return;"
                + "document.getElementById('f').value='';"
                + "var wrap=document.createElement('div');wrap.className='msg mine';"
                + "wrap.innerHTML='<div class=fname>'+esc(f.name)+'</div><div class=fmeta>'+human(f.size)+'</div>"
                + "<div class=progress><i></i></div><time>上传中…</time>';"
                + "box.appendChild(wrap);toBottom();"
                + "var bar=wrap.querySelector('.progress i');"
                + "var xhr=new XMLHttpRequest();"
                + "xhr.open('PUT','/upload?name='+encodeURIComponent(f.name)+'&g='+encodeURIComponent(G));"
                + "xhr.upload.onprogress=function(e){if(e.lengthComputable){bar.style.width=(e.loaded/e.total*100)+'%';"
                + "wrap.querySelector('time').textContent=(e.loaded/1048576).toFixed(1)+' / '+(e.total/1048576).toFixed(1)+' MB';}};"
                + "xhr.onload=function(){if(xhr.status===200){wrap.remove();lastCount=-1;refresh();}"
                + "else{wrap.querySelector('time').textContent='上传失败';}};"
                + "xhr.onerror=function(){wrap.querySelector('time').textContent='上传失败';};"
                + "xhr.send(f);}"
                + "document.getElementById('btnFile').onclick=function(){document.getElementById('f').click();};"
                + "document.getElementById('btnSend').onclick=send;"
                + "document.getElementById('f').onchange=upload;"
                + "refresh();setInterval(refresh,2500);"
                + "</script></body></html>";
    }
}
