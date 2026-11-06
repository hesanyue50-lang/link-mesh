package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.os.Environment;
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
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
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

    /** 是否真的在监听：start() 端口全被占时会静默失败，对象仍然非空。 */
    public boolean isRunning() {
        return running && server != null && !server.isClosed();
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
                s.setSoTimeout(30000);
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
            String rangeHeader = null;
            String authHeader = null;
            String depthHeader = null;
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
                    } else if ("range".equals(k)) {
                        rangeHeader = v;
                    } else if ("authorization".equals(k)) {
                        authHeader = v;
                    } else if ("depth".equals(k)) {
                        depthHeader = v;
                    }
                }
            }

            String bare = path;
            int qm = bare.indexOf('?');
            if (qm >= 0) bare = bare.substring(0, qm);

            if ("OPTIONS".equals(method)) {
                sendOptions(socket);
            } else if ("PROPFIND".equals(method)) {
                if (!davAuthorized(authHeader)) {
                    sendAuthRequired(socket);
                } else {
                    sendPropfind(socket, bare, depthHeader);
                }
            } else if (("GET".equals(method) || "HEAD".equals(method))
                    && authHeader != null && isDavPath(bare)) {
                // 带 Basic 凭据、又不是应用自己的路由：按 WebDAV 根解析路径
                if (!davAuthorized(authHeader)) {
                    sendAuthRequired(socket);
                } else {
                    sendDavFile(socket, bare, "HEAD".equals(method), rangeHeader);
                }
            } else if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendHtml(socket, page());
            } else if ("GET".equals(method) && (path.equals("/ls") || path.startsWith("/ls?"))) {
                sendDirListing(socket, paramRaw(path, "path"));
            } else if ("GET".equals(method) && path.startsWith("/api/state")) {
                sendJson(socket, stateJson(guest));
            } else if ("GET".equals(method) && path.startsWith("/app.apk")) {
                sendApk(socket);
            } else if (("GET".equals(method) || "HEAD".equals(method))
                    && (path.equals("/f") || path.startsWith("/f?"))) {
                String fp = paramRaw(path, "path");
                if (fp != null) {
                    boolean inline = "1".equals(param(path, "inline"));
                    sendBrowsed(socket, fp, "HEAD".equals(method), rangeHeader, inline);
                } else {
                    sendFile(socket, param(path, "name"), "HEAD".equals(method), rangeHeader);
                }
            } else if ("GET".equals(method) && path.startsWith("/upload/size")) {
                uploadSize(socket, param(path, "name"));
            } else if ("PUT".equals(method) && path.startsWith("/upload")) {
                String name = param(path, "name");
                long offset = 0;
                try {
                    String o = param(path, "offset");
                    if (o != null) offset = Long.parseLong(o);
                } catch (Throwable ignored) {
                }
                receiveFile(socket, in, name, contentLength, guest, offset);
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

    /** 取原始（未解码）查询参数。路径类参数必须自己解码，否则文件名里的 '+' 会变成空格。 */
    private String paramRaw(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        String[] pairs = path.substring(q + 1).split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(key)) {
                String v = p.substring(eq + 1);
                return v.length() == 0 ? null : v;
            }
        }
        return null;
    }

    /**
     * 路径解码：请求行和查询串里的 '+' 都表示字面加号（文件名常见 a+b.mp4），
     * 而 URLDecoder 会把 '+' 当空格，所以先把 '+' 转成 %2B 再解。
     */
    private static String decodePath(String s) {
        if (s == null) return "";
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    /** 相对路径归一：去掉首尾斜杠、反斜杠统一成 '/'。'..' 交给 canonical 校验去挡。 */
    private static String normalizeRel(String rel) {
        if (rel == null) return "";
        String r = rel.replace('\\', '/');
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private static String parentRel(String rel) {
        if (rel == null || rel.length() == 0) return "";
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
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

    // ---------------- 文件浏览 + WebDAV ----------------

    /** 单目录最多返回的条目数，超大目录不至于把手机和浏览器一起拖死。 */
    private static final int LS_LIMIT = 2000;

    /**
     * 浏览根目录：优先外部存储根（Android 11+ 需要「所有文件访问」权限），
     * 读不到就回退 App 外部私有目录，再退到内部私有目录。
     */
    private File browseRoot() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            // Android 11+ 没有「所有文件访问」时，/sdcard 能列出名字但读不了内容，
            // 表现是「目录看得见、点下载 0 字节」。所以必须按权限决定根目录。
            boolean allFiles = android.os.Build.VERSION.SDK_INT < 30
                    || Environment.isExternalStorageManager();
            if (allFiles && isReadableDir(ext)) return ext;
        } catch (Throwable ignored) {
        }
        try {
            File app = ctx.getExternalFilesDir(null);
            if (isReadableDir(app)) return app;
        } catch (Throwable ignored) {
        }
        return ctx.getFilesDir();
    }

    private static boolean isReadableDir(File d) {
        try {
            return d != null && d.isDirectory() && d.canRead() && d.listFiles() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private String browseRootLabel(File root) {
        try {
            if (root != null && root.getCanonicalPath()
                    .equals(Environment.getExternalStorageDirectory().getCanonicalPath())) {
                return "内部存储";
            }
        } catch (Throwable ignored) {
        }
        return root == null ? "文件" : root.getName();
    }

    /**
     * 相对路径 -> root 下的真实文件，带路径穿越校验。
     * 返回 null 表示越界（'..'、绝对路径、符号链接逃逸），调用方回 403。
     */
    private File resolveUnder(File root, String rel) {
        try {
            File rootCanon = root.getCanonicalFile();
            File canon = new File(rootCanon, normalizeRel(rel)).getCanonicalFile();
            String base = rootCanon.getPath();
            String p = canon.getPath();
            if (p.equals(base)) return canon;
            String prefix = base.endsWith(File.separator) ? base : base + File.separator;
            if (!p.startsWith(prefix)) return null;
            return canon;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String relOf(File root, File f) {
        try {
            String base = root.getCanonicalPath();
            String p = f.getCanonicalPath();
            if (p.equals(base)) return "";
            if (p.startsWith(base + File.separator)) {
                return p.substring(base.length() + 1).replace(File.separatorChar, '/');
            }
        } catch (Throwable ignored) {
        }
        return f.getName();
    }

    /** 目录在前、名字升序；返回 null 表示目录不可读。 */
    private static List<File> sortedChildren(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        List<File> out = new ArrayList<File>();
        for (File f : list) {
            if (f != null) out.add(f);
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                boolean da = a.isDirectory();
                boolean db = b.isDirectory();
                if (da != db) return da ? -1 : 1;
                int c = a.getName().compareToIgnoreCase(b.getName());
                return c != 0 ? c : a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    /** GET /ls?path= —— 目录列表 JSON，供网页端文件浏览。 */
    private void sendDirListing(Socket socket, String rawPath) throws Exception {
        File root = browseRoot();
        String rel = normalizeRel(decodePath(rawPath));
        File dir = resolveUnder(root, rel);
        if (dir == null) {
            sendStatus(socket, 403, "forbidden");
            return;
        }
        if (!dir.exists()) {
            sendStatus(socket, 404, "not found");
            return;
        }
        if (!dir.isDirectory()) {
            sendStatus(socket, 400, "not a directory");
            return;
        }
        List<File> all = sortedChildren(dir);
        if (all == null) {
            sendStatus(socket, 403, "cannot list");
            return;
        }
        boolean truncated = all.size() > LS_LIMIT;
        int n = Math.min(all.size(), LS_LIMIT);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < n; i++) {
            File f = all.get(i);
            JSONObject it = new JSONObject();
            it.put("name", f.getName());
            it.put("dir", f.isDirectory());
            it.put("size", f.isDirectory() ? 0 : f.length());
            it.put("mtime", f.lastModified());
            arr.put(it);
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("path", rel);
        o.put("parent", parentRel(rel));
        o.put("root", browseRootLabel(root));
        o.put("items", arr);
        if (truncated) o.put("truncated", true);
        sendJson(socket, o.toString());
    }

    /** GET/HEAD /f?path= —— 浏览根里的文件；inline=1 时让浏览器内联播放。 */
    private void sendBrowsed(Socket socket, String rawPath, boolean head, String range,
                             boolean inline) throws Exception {
        File root = browseRoot();
        String rel = normalizeRel(decodePath(rawPath));
        File f = resolveUnder(root, rel);
        if (f == null) {
            sendStatus(socket, 403, "forbidden");
            return;
        }
        if (!f.exists()) {
            sendStatus(socket, 404, "not found");
            return;
        }
        if (f.isDirectory()) {
            sendStatus(socket, 400, "is a directory");
            return;
        }
        sendFileBody(socket, f, head, range, mimeOf(f.getName()), inline, f.getName());
    }

    /** WebDAV 的 GET/HEAD：路径同样相对浏览根。 */
    private void sendDavFile(Socket socket, String bare, boolean head, String range)
            throws Exception {
        File root = browseRoot();
        String rel = normalizeRel(decodePath(bare));
        File f = resolveUnder(root, rel);
        if (f == null) {
            sendStatus(socket, 403, "forbidden");
            return;
        }
        if (!f.exists()) {
            sendStatus(socket, 404, "not found");
            return;
        }
        if (f.isDirectory()) {
            sendStatus(socket, 400, "is a directory");
            return;
        }
        sendFileBody(socket, f, head, range, mimeOf(f.getName()), false, f.getName());
    }

    /** 真正发文件的地方，Range/断点续传逻辑都在这里。 */
    private void sendFileBody(Socket socket, File f, boolean head, String range,
                              String contentType, boolean inline, String name) throws Exception {
        String safe = headerSafe(name);
        long total = f.length();
        long start = 0;
        long end = total - 1;
        if (range != null && range.startsWith("bytes=")) {
            try {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (a.length() > 0) start = Long.parseLong(a);
                    if (b.length() > 0) end = Long.parseLong(b);
                }
                if (start < 0) start = 0;
                if (end >= total) end = total - 1;
                if (start > end) {
                    sendStatus(socket, 416, "range");
                    return;
                }
            } catch (Throwable t) {
                start = 0;
                end = total - 1;
            }
        }
        if (total == 0) {
            OutputStream empty = socket.getOutputStream();
            empty.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\n"
                    + "Content-Length: 0\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n")
                    .getBytes("UTF-8"));
            empty.flush();
            return;
        }
        boolean partial = start > 0 || end < total - 1;
        long len = end - start + 1;
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + len + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n" : "")
                + "Content-Disposition: " + (inline ? "inline" : "attachment")
                + "; filename=\"" + safe + "\""
                + "; filename*=UTF-8''" + urlEncode(safe) + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        if (head) {
            out.flush();
            return;
        }
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long left = len;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
        } finally {
            try { raf.close(); } catch (Throwable ignored) {}
        }
    }

    private static String headerSafe(String name) {
        if (name == null || name.length() == 0) return "file";
        return name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        if ("mp4".equals(ext) || "m4v".equals(ext)) return "video/mp4";
        if ("webm".equals(ext)) return "video/webm";
        if ("mkv".equals(ext)) return "video/x-matroska";
        if ("mov".equals(ext)) return "video/quicktime";
        if ("3gp".equals(ext)) return "video/3gpp";
        if ("mp3".equals(ext)) return "audio/mpeg";
        if ("m4a".equals(ext)) return "audio/mp4";
        if ("aac".equals(ext)) return "audio/aac";
        if ("wav".equals(ext)) return "audio/wav";
        if ("ogg".equals(ext) || "oga".equals(ext)) return "audio/ogg";
        if ("flac".equals(ext)) return "audio/flac";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("png".equals(ext)) return "image/png";
        if ("gif".equals(ext)) return "image/gif";
        if ("webp".equals(ext)) return "image/webp";
        if ("bmp".equals(ext)) return "image/bmp";
        if ("svg".equals(ext)) return "image/svg+xml";
        if ("pdf".equals(ext)) return "application/pdf";
        if ("txt".equals(ext) || "log".equals(ext) || "md".equals(ext)) return "text/plain; charset=utf-8";
        if ("html".equals(ext) || "htm".equals(ext)) return "text/html; charset=utf-8";
        if ("json".equals(ext)) return "application/json";
        if ("apk".equals(ext)) return "application/vnd.android.package-archive";
        if ("zip".equals(ext)) return "application/zip";
        return "application/octet-stream";
    }

    private void receiveFile(Socket socket, InputStream in, String name, long length, String guest,
                             long offset) throws Exception {
        if (name == null || name.length() == 0) {
            sendStatus(socket, 400, "missing name");
            return;
        }
        String safe = Util.safeFileName(name);
        File relay = relayDir();
        long free = relay.getUsableSpace();
        if (length > 0 && free > 0 && free < length + 16L * 1024 * 1024) {
            sendStatus(socket, 507, "insufficient storage");
            return;
        }
        File target = new File(relay, safe);
        if (offset > 0 && (!target.isFile() || target.length() != offset)) {
            // 浏览器报告的续传点和服务端实际收到的字节不一致：从头来
            offset = 0;
        }
        if (offset <= 0) {
            // 固定文件名：否则同名上传会被 uniqueFile 改名，浏览器续传就找不到原文件
            target = new File(relayDir(), safe);
            if (target.isFile()) target.delete();
        }
        java.io.RandomAccessFile out = new java.io.RandomAccessFile(target, "rw");
        if (offset > 0) {
            out.seek(offset);
        } else {
            out.setLength(0);
        }
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
            out.close();
        }
        if (offset <= 0) addFileMessage(guest, target);
        String json = "{\"ok\":true,\"name\":\"" + target.getName().replace("\"", "")
                + "\",\"size\":" + target.length() + "}";
        sendJson(socket, json);
        if (offset <= 0) {
            Bus.post(Bus.TOAST, "浏览器上传: " + target.getName()
                    + " (" + Util.humanSize(target.length()) + ")");
        }
    }

    private void uploadSize(Socket socket, String name) throws Exception {
        long size = 0;
        if (name != null) {
            File f = new File(relayDir(), Util.safeFileName(name));
            if (f.isFile()) size = f.length();
        }
        sendJson(socket, "{\"size\":" + size + "}");
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

    private void sendFile(Socket socket, String name, boolean head, String range) throws Exception {
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
        sendFileBody(socket, f, head, range, "application/octet-stream", false, safe);
    }


    // ---------------- WebDAV 只读 ----------------

    /** OPTIONS：宣告 DAV 级别，Windows/资源管理器靠这个判断能不能挂载。 */
    private void sendOptions(Socket socket) throws Exception {
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\n"
                + "DAV: 1, 2\r\n"
                + "MS-Author-Via: DAV\r\n"
                + "Allow: OPTIONS,GET,HEAD,PROPFIND\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.flush();
    }

    /**
     * Basic Auth：用户名不校验，密码必须等于设置里的 WebDAV 密码。
     * Windows 资源管理器不接受空密码，所以空密码一律拒绝。
     */
    private boolean davAuthorized(String authHeader) {
        if (authHeader == null) return false;
        String h = authHeader.trim();
        if (h.length() < 6 || !h.substring(0, 6).equalsIgnoreCase("Basic ")) return false;
        String plain;
        try {
            byte[] raw = android.util.Base64.decode(h.substring(6).trim(),
                    android.util.Base64.DEFAULT);
            plain = new String(raw, "UTF-8");
        } catch (Throwable t) {
            return false;
        }
        int colon = plain.indexOf(':');
        if (colon < 0) return false;
        String pass = plain.substring(colon + 1);
        String expected;
        try {
            expected = com.jiuyue.lanmesh.core.AppCore.get(ctx).prefs.webdavPassword();
        } catch (Throwable t) {
            return false;
        }
        if (expected == null || expected.length() == 0) return false;
        return expected.equals(pass);
    }

    private void sendAuthRequired(Socket socket) throws Exception {
        sendStatusEx(socket, 401, "Unauthorized", "authentication required",
                "WWW-Authenticate: Basic realm=\"Link Mesh\"\r\nDAV: 1, 2\r\n");
    }

    /** PROPFIND：Depth 0 只回自身，Depth 1（默认）带上直接子项。 */
    private void sendPropfind(Socket socket, String bare, String depth) throws Exception {
        File root = browseRoot();
        String rel = normalizeRel(decodePath(bare));
        File target = resolveUnder(root, rel);
        if (target == null) {
            sendStatus(socket, 403, "forbidden");
            return;
        }
        if (!target.exists()) {
            sendStatus(socket, 404, "not found");
            return;
        }
        boolean deep = depth == null || !"0".equals(depth.trim());
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\r\n");
        appendDavEntry(sb, root, target);
        if (deep && target.isDirectory()) {
            List<File> kids = sortedChildren(target);
            if (kids != null) {
                int n = Math.min(kids.size(), LS_LIMIT);
                for (int i = 0; i < n; i++) {
                    appendDavEntry(sb, root, kids.get(i));
                }
            }
        }
        sb.append("</D:multistatus>\r\n");
        byte[] body = sb.toString().getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 207 Multi-Status\r\n"
                + "Content-Type: application/xml; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "DAV: 1, 2\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private void appendDavEntry(StringBuilder sb, File root, File f) {
        boolean dir = f.isDirectory();
        String rel = relOf(root, f);
        String name = rel.length() == 0 ? browseRootLabel(root) : f.getName();
        sb.append("<D:response><D:href>").append(encodeHref(rel, dir)).append("</D:href>");
        sb.append("<D:propstat><D:prop>");
        if (dir) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>");
        } else {
            sb.append("<D:resourcetype/>");
        }
        sb.append("<D:getcontentlength>").append(dir ? 0L : f.length()).append("</D:getcontentlength>");
        sb.append("<D:getlastmodified>").append(httpDate(f.lastModified()))
                .append("</D:getlastmodified>");
        sb.append("<D:getcontenttype>")
                .append(xmlEsc(dir ? "httpd/unix-directory" : mimeOf(f.getName())))
                .append("</D:getcontenttype>");
        sb.append("<D:displayname>").append(xmlEsc(name)).append("</D:displayname>");
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\r\n");
    }

    /** href 用绝对路径 + 逐段百分号编码，中文/空格/加号都不会被客户端误解。 */
    private static String encodeHref(String rel, boolean dir) {
        StringBuilder sb = new StringBuilder("/");
        if (rel != null && rel.length() > 0) {
            String[] seg = rel.split("/");
            for (int i = 0; i < seg.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(urlEncode(seg[i]));
            }
        }
        if (dir && sb.charAt(sb.length() - 1) != '/') sb.append('/');
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    /** RFC 1123 / HTTP-date，必须是 GMT。 */
    private static String httpDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms <= 0 ? 0 : ms));
    }

    private static String xmlEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c == '\'') sb.append("&apos;");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 只把「不属于应用自身路由」的路径交给 WebDAV，避免抢掉首页/接口。 */
    private static boolean isDavPath(String p) {
        if (p == null || p.length() == 0) return false;
        if ("/".equals(p) || "/index.html".equals(p) || "/favicon.ico".equals(p)) return false;
        if (p.startsWith("/api/") || p.startsWith("/upload") || p.startsWith("/text")
                || p.startsWith("/app.apk") || p.equals("/ls") || p.startsWith("/ls?")) {
            return false;
        }
        if (p.equals("/f") || p.startsWith("/f/")) return false;
        return true;
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
        sendStatusEx(socket, code, "ERROR", text, null);
    }

    /** 带额外响应头的状态码（401 需要 WWW-Authenticate）。 */
    private void sendStatusEx(Socket socket, int code, String reason, String text,
                              String extraHeaders) throws Exception {
        byte[] body = text == null ? new byte[0] : text.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + (extraHeaders == null ? "" : extraHeaders)
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
            // 可选：写进手机剪贴板。默认关 —— 同网段任何人都能访问网页端并调这个接口，
            // 默认自动写等于把剪贴板交给别人，可以拿来做粘贴劫持。
            try {
                if (com.jiuyue.lanmesh.core.AppCore.get(ctx).prefs.autoClipFromWeb()
                        && msg.length() <= 4000) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Link Mesh", msg));
                    }
                }
            } catch (Throwable ignored) {
            }
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
                // 只有"当前访客"自己发的才算 mine；手机主人发出的 DIR_OUT 对访客来说是对方
                boolean mine = ("web-" + guest).equals(m.peerDeviceId);
                item.put("mine", mine);
                String sender;
                if (com.jiuyue.lanmesh.model.ChatMessage.DIR_OUT.equals(m.direction)) {
                    sender = prefs.nickname();
                } else if (m.peerDeviceId != null && m.peerDeviceId.startsWith("web-")) {
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
                + ".fdl{display:inline-block;margin-top:8px;font-size:13px;font-weight:600;color:var(--accent);cursor:pointer}"
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
                + ".acts{margin-top:7px;display:flex;gap:14px}"
                + ".acts a{font-size:12px;color:var(--accent);cursor:pointer}"
                + ".acts a.cancel{color:#e06a6a}"
                + ".copy{cursor:pointer;word-break:break-word;transition:opacity .15s}"
                + ".tabs{flex:none;display:flex;gap:8px;padding:10px 16px 2px}"
                + ".tab{border:1px solid var(--stroke);background:var(--card);color:var(--dim);"
                + "border-radius:999px;padding:7px 15px;font-size:13px;cursor:pointer;font-family:inherit}"
                + ".tab.on{background:var(--accent);border-color:transparent;color:var(--on);font-weight:600}"
                + ".view{display:none;flex:1 1 auto;min-height:0;flex-direction:column}"
                + ".view.on{display:flex}"
                + ".fbar{flex:none;display:flex;align-items:center;gap:2px;padding:10px 16px;overflow-x:auto;"
                + "white-space:nowrap;border-bottom:1px solid var(--stroke)}"
                + ".fbar::-webkit-scrollbar{display:none}"
                + ".crumb{font-size:12px;color:var(--accent);cursor:pointer;padding:2px;flex:none}"
                + ".crumb.cur{color:var(--text);font-weight:600}"
                + ".csep{font-size:12px;color:var(--dim);flex:none}"
                + ".flist{flex:1 1 auto;min-height:0;overflow-y:auto;-webkit-overflow-scrolling:touch;"
                + "padding:4px 10px 28px}"
                + ".frow{display:flex;align-items:center;gap:10px;padding:10px 8px;"
                + "border-bottom:1px solid var(--stroke)}"
                + ".frow:active{background:var(--card)}"
                + ".fico{flex:none;width:18px;text-align:center;font-size:13px;color:var(--accent)}"
                + ".fmain{flex:1 1 auto;min-width:0}"
                + ".fnm{font-size:13.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
                + ".fsub{font-size:11px;color:var(--dim);font-family:ui-monospace,Menlo,Consolas,monospace}"
                + ".fact{flex:none;display:flex;gap:10px;align-items:center}"
                + ".fact a{font-size:12px;color:var(--accent);text-decoration:none;padding:3px 2px}"
                + ".fempty{color:var(--dim);text-align:center;margin-top:40px;font-size:13px;"
                + "line-height:1.8;padding:0 16px}"
                + "</style></head><body><div class='app'>"
                + "<header><div class='dot'></div><div class='who'><h1 id='name'>Link Mesh</h1>"
                + "<div class='sub' id='sub'>连接中…</div></div>"
                + "<a class='dl' href='/app.apk' download>下载 App</a></header>"
                + "<div class='tabs'><button type='button' class='tab on' id='tabChat'>传输</button>"
                + "<button type='button' class='tab' id='tabFiles'>文件</button></div>"
                + "<div class='view on' id='vChat'>"
                + "<div class='msgs' id='msgs'><div class='empty'>还没有消息<br>发一条试试，或点左下角 + 上传文件</div></div>"
                + "<div class='composer'>"
                + "<button class='icon' id='btnFile'>+</button>"
                + "<input type='file' id='f' style='display:none'>"
                + "<input type='text' id='msg' placeholder='输入消息…'>"
                + "<button class='send' id='btnSend'>发送</button>"
                + "</div></div>"
                + "<div class='view' id='vFiles'>"
                + "<div class='fbar' id='fbar'></div>"
                + "<div class='flist' id='flist'><div class='fempty'>正在读取…</div></div>"
                + "</div></div><script>"
                + "var G=(function(){var v=localStorage.getItem('lm_guest');"
                + "if(!v){v='g'+Math.random().toString(36).slice(2,10);localStorage.setItem('lm_guest',v);}return v;})();"
                + "var Q='?g='+encodeURIComponent(G);"
                + "function esc(s){s=(s===null||s===undefined)?'':''+s;return s.replace(/[&<>\"']/g,"
                + "function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}"
                + "function fmt(t){var d=new Date(t);function p(n){return n<10?'0'+n:''+n;}"
                + "return p(d.getMonth()+1)+'-'+p(d.getDate())+' '+p(d.getHours())+':'+p(d.getMinutes());}"
                + "function human(b){return b<1024?b+' B':(b<1048576?(b/1024).toFixed(1)+' KB':(b/1048576).toFixed(1)+' MB');}"
                + "var box=document.getElementById('msgs');var lastCount=-1;"
                + "function pick(el){try{var r=document.createRange();r.selectNodeContents(el);"
                + "var s2=window.getSelection();s2.removeAllRanges();s2.addRange(r);}catch(e){}}"
                + "function copyText(txt,el){var ok=false;try{var ta=document.createElement('textarea');"
                + "ta.value=txt;ta.style.position='fixed';ta.style.top='-999px';document.body.appendChild(ta);"
                + "ta.focus();ta.select();ok=document.execCommand('copy');document.body.removeChild(ta);}catch(e){}"
                + "if(!ok){pick(el);return;}var o=el.style.opacity;el.style.opacity='.4';"
                + "setTimeout(function(){el.style.opacity=o;},200);}"
                + "box.addEventListener('click',function(e){var t=e.target;"
                + "while(t&&t!==box&&!(t.classList&&t.classList.contains('copy')))t=t.parentNode;"
                + "if(!t||t===box)return;copyText(decodeURIComponent(t.getAttribute('data-c')||''),t);});"
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
                + "html+='<a class=fdl data-n=\"'+encodeURIComponent(m.name)+'\">下载</a>';}"
                + "else{html+='<div class=copy data-c=\"'+encodeURIComponent(m.text||'')+'\">'+esc(m.text)+'</div>';}"
                + "html+='<time>'+fmt(m.time)+'</time></div>';});"
                + "box.innerHTML=html||'<div class=empty>还没有消息<br>发一条试试，或点左下角 + 上传文件</div>';"
                + "if(keep)toBottom();}}).catch(function(){});}"
                + "function send(){var el=document.getElementById('msg');var v=el.value.trim();if(!v)return;"
                + "el.value='';fetch('/text'+Q,{method:'POST',body:v}).then(function(){lastCount=-1;refresh();});}"
                + "var up={file:null,xhr:null,sent:0,paused:false,cancel:false,wrap:null,bar:null,time:null};"
                + "function startUp(off){if(!up.file||up.cancel)return;var x=new XMLHttpRequest();up.xhr=x;"
                + "x.open('PUT','/upload?name='+encodeURIComponent(up.file.name)+'&offset='+off+'&g='+encodeURIComponent(G));"
                + "x.upload.onprogress=function(e){if(!e.lengthComputable)return;var d=off+e.loaded;up.sent=d;"
                + "up.bar.style.width=(d/up.file.size*100)+'%';up.time.textContent=(d/1048576).toFixed(1)+' / '+(up.file.size/1048576).toFixed(1)+' MB';};"
                + "x.onload=function(){if(x.status===200){up.wrap&&up.wrap.remove();up.xhr=null;lastCount=-1;refresh();}else{up.time.textContent='上传失败';}};"
                + "x.onerror=function(){if(!up.paused&&!up.cancel)up.time.textContent='上传失败';};"
                + "x.send(off>0?up.file.slice(off):up.file);}"
                + "function upload(){var f=document.getElementById('f').files[0];if(!f)return;"
                + "document.getElementById('f').value='';up.file=f;up.sent=0;up.paused=false;up.cancel=false;"
                + "var wrap=document.createElement('div');wrap.className='msg mine';"
                + "wrap.innerHTML='<div class=fname>'+esc(f.name)+'</div><div class=fmeta>'+human(f.size)+'</div>"
                + "<div class=progress><i></i></div><time>上传中…</time>"
                + "<div class=acts><a class=pause>暂停</a><a class=cancel>取消</a></div>';"
                + "box.appendChild(wrap);toBottom();up.wrap=wrap;up.bar=wrap.querySelector('.progress i');up.time=wrap.querySelector('time');"
                + "wrap.querySelector('.pause').onclick=function(){if(up.xhr){up.paused=true;up.xhr.abort();up.xhr=null;this.textContent='继续';up.time.textContent='已暂停';}"
                + "else{up.paused=false;this.textContent='暂停';up.time.textContent='继续上传…';"
                + "fetch('/upload/size?name='+encodeURIComponent(up.file.name)+'&g='+encodeURIComponent(G)).then(function(r){return r.json();})"
                + ".then(function(d){startUp(d.size||0);}).catch(function(){startUp(up.sent);});}};"
                + "wrap.querySelector('.cancel').onclick=function(){up.cancel=true;if(up.xhr){up.xhr.abort();up.xhr=null;}wrap.remove();};"
                + "startUp(0);}"
                + "function download(n){var name=decodeURIComponent(n||'');if(!name)return;"
                + "var st={name:name,total:0,off:0,chunks:[],paused:false,cancel:false,xhr:null};var CH=524288;"
                + "var wrap=document.createElement('div');wrap.className='msg other';"
                + "wrap.innerHTML='<div class=fname>'+esc(name)+'</div><div class=progress><i></i></div><time>连接中…</time>"
                + "<div class=acts><a class=pause>暂停</a><a class=cancel>取消</a></div>';"
                + "box.appendChild(wrap);toBottom();var bar=wrap.querySelector('.progress i'),tm=wrap.querySelector('time');"
                + "function ui(t,p){tm.textContent=t;if(p!=null)bar.style.width=p+'%';}"
                + "function next(){if(st.paused||st.cancel)return;var end=Math.min(st.total,st.off+CH)-1;"
                + "var x=new XMLHttpRequest();st.xhr=x;x.open('GET','/f?name='+encodeURIComponent(st.name));"
                + "if(st.off>0)x.setRequestHeader('Range','bytes='+st.off+'-'+end);x.responseType='arraybuffer';"
                + "x.onload=function(){if(st.cancel)return;if(x.status!==200&&x.status!==206){ui('下载失败');return;}"
                + "st.chunks.push(x.response);st.off+=x.response.byteLength;"
                + "ui((st.off/1048576).toFixed(1)+' / '+(st.total/1048576).toFixed(1)+' MB',st.off/st.total*100);"
                + "if(st.off<st.total){next();}else{var blob=new Blob(st.chunks);var a=document.createElement('a');"
                + "a.href=URL.createObjectURL(blob);a.download=st.name;a.click();setTimeout(function(){URL.revokeObjectURL(a.href);},4000);wrap.remove();}};"
                + "x.onerror=function(){if(!st.paused&&!st.cancel)ui('下载失败');};x.send();}"
                + "wrap.querySelector('.pause').onclick=function(){if(st.xhr){st.paused=true;st.xhr.abort();st.xhr=null;this.textContent='继续';ui('已暂停 '+(st.off/1048576).toFixed(1)+' MB');}"
                + "else{st.paused=false;this.textContent='暂停';ui('继续下载…');next();}};"
                + "wrap.querySelector('.cancel').onclick=function(){st.cancel=true;if(st.xhr){st.xhr.abort();st.xhr=null;}wrap.remove();};"
                + "fetch('/f?name='+encodeURIComponent(name),{method:'HEAD'}).then(function(r){st.total=parseInt(r.headers.get('Content-Length')||'0',10);"
                + "if(!st.total){ui('无法获取文件大小');return;}ui('0 / '+(st.total/1048576).toFixed(1)+' MB',0);next();}).catch(function(){ui('下载失败');});}"
                + "document.getElementById('msgs').addEventListener('click',function(e){var t=e.target;"
                + "if(t&&t.className&&t.className.indexOf('fdl')>=0){download(t.getAttribute('data-n'));}});"
                + "document.getElementById('f').onchange=upload;"
                + "document.getElementById('btnFile').onclick=function(){document.getElementById('f').click();};"
                + "document.getElementById('btnSend').onclick=send;"
                // ---- 文件浏览 ----
                + "var fb={path:'',root:'文件',items:[],truncated:false};"
                + "var MEDIA={'3gp':1,mp4:1,m4v:1,webm:1,mkv:1,mov:1,mp3:1,m4a:1,aac:1,ogg:1,oga:1,"
                + "wav:1,flac:1,jpg:1,jpeg:1,png:1,gif:1,webp:1,bmp:1,svg:1,pdf:1,txt:1,html:1};"
                + "function elm(id){return document.getElementById(id);}"
                + "function extOf(n){var i=n.lastIndexOf('.');return i>=0?n.substring(i+1).toLowerCase():'';}"
                + "function parentOf(p){if(!p)return '';var i=p.lastIndexOf('/');return i<0?'':p.substring(0,i);}"
                + "function tabTo(name){var chat=(name==='chat');"
                + "if(chat){elm('vChat').classList.add('on');elm('vFiles').classList.remove('on');"
                + "elm('tabChat').classList.add('on');elm('tabFiles').classList.remove('on');}"
                + "else{elm('vFiles').classList.add('on');elm('vChat').classList.remove('on');"
                + "elm('tabFiles').classList.add('on');elm('tabChat').classList.remove('on');"
                + "loadDir(fb.path);}}"
                + "function renderBar(){"
                + "var html='<a class=\"crumb'+(fb.path?'':' cur')+'\" data-p=\"\">'+esc(fb.root||'根目录')+'</a>';"
                + "var parts=fb.path?fb.path.split('/'):[];var acc='';"
                + "for(var i=0;i<parts.length;i++){acc=acc?acc+'/'+parts[i]:parts[i];"
                + "html+='<span class=\"csep\">/</span><a class=\"crumb'+(i===parts.length-1?' cur':'')+'"
                + "\" data-p=\"'+esc(acc)+'\">'+esc(parts[i])+'</a>';}"
                + "elm('fbar').innerHTML=html;}"
                + "function renderList(){var out='';"
                + "if(fb.path){out+='<div class=\"frow\" data-up=\"1\"><div class=\"fico\">↑</div>"
                + "<div class=\"fmain\"><div class=\"fnm\">返回上级</div></div></div>';}"
                + "for(var i=0;i<fb.items.length;i++){var it=fb.items[i];"
                + "var p=fb.path?fb.path+'/'+it.name:it.name;"
                + "if(it.dir){out+='<div class=\"frow\" data-dir=\"'+esc(p)+'\"><div class=\"fico\">▸</div>"
                + "<div class=\"fmain\"><div class=\"fnm\">'+esc(it.name)+'/</div>"
                + "<div class=\"fsub\">文件夹 · '+fmt(it.mtime)+'</div></div></div>';}"
                + "else{var href='/f?path='+encodeURIComponent(p);"
                + "var play=MEDIA[extOf(it.name)]?'<a href=\"'+href+'&amp;inline=1\" target=\"_blank\" "
                + "rel=\"noopener\">打开</a>':'';"
                + "out+='<div class=\"frow\"><div class=\"fico\">·</div><div class=\"fmain\">"
                + "<div class=\"fnm\">'+esc(it.name)+'</div><div class=\"fsub\">'+human(it.size)"
                + "+' · '+fmt(it.mtime)+'</div></div><div class=\"fact\"><a href=\"'+href+'\">下载</a>'"
                + "+play+'</div></div>';}}"
                + "if(!fb.items.length){out+='<div class=\"fempty\">这个文件夹是空的</div>';}"
                + "if(fb.truncated){out+='<div class=\"fempty\">只显示前 2000 项</div>';}"
                + "elm('flist').innerHTML=out;}"
                + "function loadDir(p){fb.path=p||'';elm('flist').innerHTML='<div class=\"fempty\">正在读取…</div>';"
                + "fetch('/ls?path='+encodeURIComponent(fb.path)).then(function(r){"
                + "if(r.status!==200)throw new Error(''+r.status);return r.json();}).then(function(d){"
                + "fb.path=d.path||'';fb.items=d.items||[];fb.root=d.root||'文件';"
                + "fb.truncated=!!d.truncated;renderBar();renderList();}).catch(function(){"
                + "elm('flist').innerHTML='<div class=\"fempty\">读不到这个目录<br>"
                + "如果是「内部存储」，请先在 App 里授予「所有文件访问」权限</div>';});}"
                + "function onListClick(e){var t=e.target;"
                + "while(t&&t!==elm('flist')){if(t.getAttribute){"
                + "var d=t.getAttribute('data-dir');"
                + "if(d!==null&&d!==undefined&&d!==''){loadDir(d);return;}"
                + "if(t.getAttribute('data-up')){loadDir(parentOf(fb.path));return;}}t=t.parentNode;}}"
                + "elm('tabChat').onclick=function(){tabTo('chat');};"
                + "elm('tabFiles').onclick=function(){tabTo('files');};"
                + "elm('flist').addEventListener('click',onListClick);"
                + "elm('fbar').addEventListener('click',function(e){var t=e.target;"
                + "while(t&&t!==elm('fbar')){if(t.getAttribute){var p=t.getAttribute('data-p');"
                + "if(p!==null&&p!==undefined){loadDir(p);return;}}t=t.parentNode;}});"
                + "refresh();setInterval(refresh,2500);"
                + "</script></body></html>";
    }
}
