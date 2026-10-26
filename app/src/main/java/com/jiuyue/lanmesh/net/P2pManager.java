package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.util.Log;

import com.jiuyue.lanmesh.core.Bus;
import com.jiuyue.lanmesh.core.Prefs;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段二的 P2P 会话管理器（进程内单例）。
 *
 * 状态机：IDLE → OFFER_READY → CONNECTING → CONNECTED / FAILED
 *          IDLE → ANSWER_READY → CONNECTING → CONNECTED / FAILED
 *
 * 信令完全手动：本端生成邀请码/应答码由用户复制给对方，不经过任何服务器。
 */
public class P2pManager {

    private static final String TAG = "LanMeshP2p";

    public static final String ST_IDLE = "idle";
    public static final String ST_OFFER_READY = "offer_ready";
    public static final String ST_ANSWER_READY = "answer_ready";
    public static final String ST_CONNECTING = "connecting";
    public static final String ST_CONNECTED = "connected";
    public static final String ST_FAILED = "failed";

    private static P2pManager instance;

    private final Context ctx;
    private final Prefs prefs;
    private WebRtcSession session;
    private String state = ST_IDLE;
    private String detail = "";
    private String localCode = "";
    private final List<String> logs = new ArrayList<String>();

    // ---- P2P 文件传输状态 ----
    private static final int P2P_CHUNK = 16 * 1024;
    private final java.util.concurrent.LinkedBlockingQueue<JSONObject> fileCtl =
            new java.util.concurrent.LinkedBlockingQueue<JSONObject>();
    private java.io.RandomAccessFile recvRaf;
    private java.io.File recvPart;
    private java.io.File recvTarget;
    private String recvFileId = "";
    private String recvName = "";
    private String recvSha = "";
    private int recvChunk = P2P_CHUNK;
    private long recvSize;
    private long recvBytes;
    private long recvLastUi;

    public static synchronized P2pManager get(Context c) {
        if (instance == null) instance = new P2pManager(c.getApplicationContext());
        return instance;
    }

    private P2pManager(Context c) {
        this.ctx = c;
        this.prefs = com.jiuyue.lanmesh.core.AppCore.get(c).prefs;
    }

    public String state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public String localCode() {
        return localCode;
    }

    private void setState(String s, String d) {
        state = s;
        detail = d == null ? "" : d;
        addLog("[" + state + "] " + detail);
        Bus.post(Bus.P2P_STATE, s);
        Bus.post(Bus.P2P_DETAIL, this.detail);
    }

    private void addLog(String line) {
        if (line == null) return;
        logs.add(line);
        while (logs.size() > 60) logs.remove(0);
        Bus.post(Bus.P2P_LOG, line);
    }

    /** UI 回到前台时用它恢复状态与日志（Bus 事件在后台会丢）。 */
    public List<String> logs() {
        return new ArrayList<String>(logs);
    }

    public void clearLogs() {
        logs.clear();
    }

    public void startOffer() {
        close();
        setState(ST_CONNECTING, "正在生成邀请码…");
        session = new WebRtcSession();
        session.startOffer(ctx, new SessionCallback());
    }

    public void acceptOffer(String code) {
        Log.i(TAG, "acceptOffer codeLen=" + (code == null ? 0 : code.length()));
        close();
        setState(ST_CONNECTING, "正在解析邀请码并生成应答码…");
        session = new WebRtcSession();
        session.acceptOffer(ctx, code, new SessionCallback());
    }

    public void applyAnswer(String code) {
        if (session == null) {
            setState(ST_FAILED, "请先发起连接");
            return;
        }
        setState(ST_CONNECTING, "正在应用应答码…");
        session.applyAnswer(code);
    }

    public boolean sendText(String text) {
        if (session == null || !session.isConnected()) {
            Bus.toast("P2P 尚未连接");
            return false;
        }
        boolean ok = session.sendText(text);
        if (ok) {
            addLog("我: " + text);
            Bus.post(Bus.P2P_TEXT, "我: " + text);
        }
        return ok;
    }

    /** 通过 DataChannel 发送文件：16KB 分块 + SHA-256 校验 + 背压。 */
    public void sendFile(final android.net.Uri uri, final String name, final long size) {
        if (!isConnected()) {
            Bus.toast("P2P 尚未连接");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.InputStream in = null;
                final WebRtcSession s = session;
                if (s == null) {
                    Bus.post(Bus.P2P_LOG, "发送失败：连接已关闭");
                    return;
                }
                try {
                    final String fileId = java.util.UUID.randomUUID().toString();
                    in = ctx.getContentResolver().openInputStream(uri);
                    if (in == null) throw new IllegalStateException("无法打开文件");
                    String sha = com.jiuyue.lanmesh.core.Util.sha256(in);
                    in.close();
                    in = null;

                    JSONObject offer = new JSONObject();
                    offer.put("fileId", fileId);
                    offer.put("fileName", name);
                    offer.put("fileSize", size);
                    offer.put("chunkSize", P2P_CHUNK);
                    offer.put("sha256", sha);
                    if (!s.sendFrame(Protocol.T_FILE_OFFER,
                            offer.toString().getBytes("UTF-8"))) {
                        throw new IllegalStateException("发送文件请求失败");
                    }

                    JSONObject acc = null;
                    long until = System.currentTimeMillis() + 60000;
                    while (System.currentTimeMillis() < until) {
                        JSONObject o = fileCtl.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (o == null) continue;
                        int t = o.optInt("__t", -1);
                        if (t == Protocol.T_FILE_ACCEPT) { acc = o; break; }
                        if (t == Protocol.T_FILE_REJECT) {
                            throw new IllegalStateException("对方拒绝接收: "
                                    + o.optString("reason", ""));
                        }
                    }
                    if (acc == null) throw new IllegalStateException("对方未确认接收（超时）");

                    in = ctx.getContentResolver().openInputStream(uri);
                    byte[] buf = new byte[P2P_CHUNK];
                    int idx = 0;
                    long sent = 0;
                    long lastUi = 0;
                    while (in != null) {
                        int n = in.read(buf);
                        if (n <= 0) break;
                        byte[] payload = new byte[4 + n];
                        payload[0] = (byte) (idx >>> 24);
                        payload[1] = (byte) (idx >>> 16);
                        payload[2] = (byte) (idx >>> 8);
                        payload[3] = (byte) idx;
                        System.arraycopy(buf, 0, payload, 4, n);
                        if (!s.sendFrame(Protocol.T_CHUNK, payload)) {
                            throw new IllegalStateException("发送中断（连接已断开）");
                        }
                        sent += n;
                        idx++;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 500) {
                            lastUi = now;
                            int pc = size <= 0 ? 0 : (int) Math.min(100, sent * 100 / size);
                            Bus.post(Bus.P2P_LOG, "发送 " + name + "  " + pc + "%  "
                                    + com.jiuyue.lanmesh.core.Util.humanSize(sent) + " / "
                                    + com.jiuyue.lanmesh.core.Util.humanSize(size));
                        }
                    }
                    in.close();
                    in = null;

                    JSONObject done = new JSONObject();
                    done.put("fileId", fileId);
                    done.put("sha256", sha);
                    done.put("size", size);
                    s.sendFrame(Protocol.T_FILE_DONE, done.toString().getBytes("UTF-8"));

                    JSONObject res = null;
                    long waitRes = System.currentTimeMillis() + 120000;
                    while (System.currentTimeMillis() < waitRes) {
                        JSONObject o = fileCtl.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (o == null) continue;
                        if (o.optInt("__t", -1) == Protocol.T_FILE_RESULT) { res = o; break; }
                    }
                    if (res == null) {
                        Bus.post(Bus.P2P_LOG, "文件已发出，但没等到对方校验结果");
                    } else if (!res.optBoolean("ok", false)) {
                        Bus.post(Bus.P2P_LOG, "对方校验失败：" + res.optString("error", ""));
                    } else {
                        Bus.post(Bus.P2P_LOG, "已发送 " + name + "（SHA-256 校验通过）");
                    }
                } catch (Throwable t) {
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    Bus.post(Bus.P2P_LOG, "发送失败：" + m);
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) {}
                }
            }
        }, "p2p-send").start();
    }

    /** 把当前链路信息（直连/P2P、RTT、收发字节）写进日志。 */
    public void requestStats() {
        if (session == null) return;
        session.requestStats(new WebRtcSession.StatsCallback() {
            @Override
            public void onStats(String text) {
                addLog("链路: " + text);
            }
        });
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (!ST_FAILED.equals(state)) {
            setState(ST_IDLE, "");
        }
    }

    private class SessionCallback implements WebRtcSession.Callback {
        @Override
        public void onLocalSignal(P2pSignal signal) {
            localCode = signal.encode();
            Log.i(TAG, "onLocalSignal type=" + signal.type + " codeLen=" + localCode.length());
            if (LOCAL_TYPE_OFFER.equals(signal.type)) {
                setState(ST_OFFER_READY, "把邀请码发给对方，然后粘贴对方的应答码");
            } else {
                setState(ST_ANSWER_READY, "把应答码发给对方");
            }
        }

        @Override
        public void onConnected() {
            setState(ST_CONNECTED, "P2P 直连已建立");
            // 1.5 秒后读一次链路统计（此时 candidate-pair 已选中）
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            requestStats();
                        }
                    }, 1500);
        }

        @Override
        public void onFrame(int type, byte[] payload) {
            try {
                if (type == Protocol.T_TEXT) {
                    JSONObject o = new JSONObject(new String(payload, "UTF-8"));
                    String msg = "对方: " + o.optString("text", "");
                    addLog(msg);
                    Bus.post(Bus.P2P_TEXT, msg);
                } else if (type == Protocol.T_FILE_OFFER) {
                    handleFileOffer(payload);
                } else if (type == Protocol.T_CHUNK) {
                    handleFileChunk(payload);
                } else if (type == Protocol.T_FILE_DONE) {
                    handleFileDone(payload);
                } else if (type == Protocol.T_FILE_ACCEPT || type == Protocol.T_FILE_REJECT
                        || type == Protocol.T_FILE_RESULT) {
                    JSONObject o = new JSONObject(new String(payload, "UTF-8"));
                    o.put("__t", type);
                    fileCtl.offer(o);
                }
            } catch (Throwable t) {
                Log.w(TAG, "frame parse failed", t);
            }
        }

        @Override
        public void onCountdown(int seconds) {
            if (seconds > 0) addLog("[倒计时] 剩余 " + seconds + " 秒");
        }

        @Override
        public void onClosed(String reason) {
            setState(ST_IDLE, reason);
        }

        @Override
        public void onError(String message) {
            setState(ST_FAILED, message);
        }
    }

    // ---------------- 文件接收 ----------------

    private void handleFileOffer(byte[] payload) {
        try {
            JSONObject o = new JSONObject(new String(payload, "UTF-8"));
            String fileId = o.optString("fileId", "");
            String name = com.jiuyue.lanmesh.core.Util.safeFileName(o.optString("fileName", "file"));
            long size = o.optLong("fileSize", 0);
            int chunk = o.optInt("chunkSize", P2P_CHUNK);
            if (fileId.length() == 0 || size <= 0) return;
            closeReceive();
            java.io.File dir = com.jiuyue.lanmesh.core.StorageUtil.downloadDir(ctx);
            long free = dir.getUsableSpace();
            if (free > 0 && free < size + 16L * 1024 * 1024) {
                JSONObject rej = new JSONObject();
                rej.put("fileId", fileId);
                rej.put("reason", "存储空间不足");
                session.sendFrame(Protocol.T_FILE_REJECT, rej.toString().getBytes("UTF-8"));
                Bus.post(Bus.P2P_LOG, "拒绝接收 " + name + "：存储空间不足");
                return;
            }
            recvPart = new java.io.File(dir, ".p2p-" + fileId);
            recvTarget = com.jiuyue.lanmesh.core.Util.uniqueFile(dir, name);
            recvRaf = new java.io.RandomAccessFile(recvPart, "rw");
            recvRaf.setLength(size);
            recvFileId = fileId;
            recvName = name;
            recvSize = size;
            recvChunk = chunk;
            recvSha = o.optString("sha256", "");
            recvBytes = 0;
            recvLastUi = 0;
            JSONObject acc = new JSONObject();
            acc.put("fileId", fileId);
            acc.put("bitmap", "");
            acc.put("chunkSize", chunk);
            session.sendFrame(Protocol.T_FILE_ACCEPT, acc.toString().getBytes("UTF-8"));
            Bus.post(Bus.P2P_LOG, "接收 " + name + "（"
                    + com.jiuyue.lanmesh.core.Util.humanSize(size) + "）…");
        } catch (Throwable t) {
            addLog("接收失败：" + t.getMessage());
        }
    }

    private void handleFileChunk(byte[] payload) {
        if (recvRaf == null || payload.length < 5) return;
        try {
            int idx = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                    | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
            int len = payload.length - 4;
            if (idx < 0) return;
            long off = (long) idx * recvChunk;
            if (off + len > recvSize) len = (int) (recvSize - off);
            if (len <= 0) return;
            recvRaf.seek(off);
            recvRaf.write(payload, 4, len);
            recvBytes += len;
            long now = System.currentTimeMillis();
            if (now - recvLastUi > 500) {
                recvLastUi = now;
                int pc = recvSize <= 0 ? 0 : (int) Math.min(100, recvBytes * 100 / recvSize);
                Bus.post(Bus.P2P_LOG, "接收 " + recvName + "  " + pc + "%  "
                        + com.jiuyue.lanmesh.core.Util.humanSize(recvBytes) + " / "
                        + com.jiuyue.lanmesh.core.Util.humanSize(recvSize));
            }
        } catch (Throwable t) {
            addLog("写入失败：" + t.getMessage());
        }
    }

    private void handleFileDone(byte[] payload) {
        String okSha = "";
        String err = "";
        boolean ok = false;
        try {
            JSONObject o = new JSONObject(new String(payload, "UTF-8"));
            String fileId = o.optString("fileId", "");
            if (!fileId.equals(recvFileId)) throw new IllegalStateException("文件标识不匹配");
            if (recvRaf != null) { recvRaf.close(); recvRaf = null; }
            java.io.InputStream in = new java.io.FileInputStream(recvPart);
            String actual = com.jiuyue.lanmesh.core.Util.sha256(in);
            in.close();
            okSha = actual;
            if (recvSha.length() > 0 && !recvSha.equalsIgnoreCase(actual)) {
                err = "SHA-256 不一致";
            } else if (recvPart.renameTo(recvTarget)) {
                ok = true;
            } else {
                err = "无法保存文件";
            }
        } catch (Throwable t) {
            err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
        try {
            JSONObject res = new JSONObject();
            res.put("fileId", recvFileId);
            res.put("ok", ok);
            res.put("sha256", okSha);
            res.put("error", err);
            if (session != null) {
                session.sendFrame(Protocol.T_FILE_RESULT, res.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        Bus.post(Bus.P2P_LOG, ok
                ? "已接收 " + recvName + "（SHA-256 校验通过）→ " + recvTarget.getName()
                : "接收失败：" + err);
        closeReceive();
    }

    private void closeReceive() {
        try { if (recvRaf != null) recvRaf.close(); } catch (Throwable ignored) {}
        recvRaf = null;
        if (recvPart != null && recvPart.exists()) recvPart.delete();
        recvPart = null;
        recvTarget = null;
        recvFileId = "";
        recvName = "";
        recvSha = "";
        recvBytes = 0;
    }

    private static final String LOCAL_TYPE_OFFER = P2pSignal.TYPE_OFFER;
}
