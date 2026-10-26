package com.jiuyue.lanmesh.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.Bus;
import com.jiuyue.lanmesh.core.Const;
import com.jiuyue.lanmesh.core.Prefs;
import com.jiuyue.lanmesh.core.Util;
import com.jiuyue.lanmesh.model.ChatMessage;
import com.jiuyue.lanmesh.model.MessageStore;
import com.jiuyue.lanmesh.model.Peer;
import com.jiuyue.lanmesh.ui.ChatActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 传输调度：负责"发送文件"与"通知/取消"两条横切逻辑。
 *
 * 接收入口在 Session（因为接收要按帧顺序读同一个 socket），但接收任务同样
 * 在 here 注册，取消与进度展示才能统一。
 */
public class TransferManager {

    private static final String TAG = "LanMeshTransfer";

    /** 一个进行中的传输（发送或接收）。 */
    public static class Job {
        public final String fileId;
        public final ChatMessage msg;
        public volatile boolean cancelled = false;
        public volatile boolean paused = false;
        public volatile Socket socket;
        public long total;
        public long bytes;
        public long startedAt = System.currentTimeMillis();
        public long lastSampleAt = startedAt;
        public long lastSampleBytes = 0;
        public long speed; // B/s

        public Job(String fileId, ChatMessage msg, long total) {
            this.fileId = fileId;
            this.msg = msg;
            this.total = total;
        }

        public int percent() {
            if (total <= 0) return 0;
            int p = (int) (bytes * 100 / total);
            if (p > 100) p = 100;
            if (p < 0) p = 0;
            return p;
        }

        public void pause() { paused = true; }

        public void resume() { paused = false; }

        public void cancel() {
            cancelled = true;
            Socket s = socket;
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final MessageStore store;
    private final PeerRegistry peers;
    private final Map<String, Job> jobs = new HashMap<String, Job>();
    private NotificationManager nm;
    private long lastNotifyAt = 0;

    public TransferManager(Context ctx, Prefs prefs, MessageStore store, PeerRegistry peers) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.store = store;
        this.peers = peers;
        this.nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel();
    }

    private static final String CH_DONE = "lanmesh_done";

    private void ensureDoneChannel() {
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CH_DONE,
                    "传输完成提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }
    }

    /** 传输完成的强提醒：高优先级通知 + 震动。 */
    public void notifyDone(String text) {
        try {
            ensureDoneChannel();
            if (nm == null) return;
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle("传输完成")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_SOUND);
            nm.notify(2100 + (int) (System.currentTimeMillis() % 1000), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "notify done failed", t);
        }
    }

    /** 收到文本消息的强提醒（高优先级 + 震动）。 */
    public void notifyMessage(String from, String text) {
        try {
            ensureDoneChannel();
            if (nm == null) return;
            String body = (from == null ? "" : from + "：") + (text == null ? "" : text);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle("新消息")
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_SOUND);
            nm.notify(3100 + (int) (System.currentTimeMillis() % 1000), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "notify message failed", t);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel("lanmesh_transfer",
                    ctx.getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(ctx.getString(R.string.service_channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    // ---------------- 发送 ----------------

    /**
     * 发送文件。在后台线程执行，调用方（UI）只负责先落一条 sending 消息。
     *
     * @param uri  通过 ACTION_GET_CONTENT 拿到的 Uri（可为 null，若 path 有效）
     * @param path 本地真实路径（可为 null，若 uri 有效）
     */
    public void sendFile(final Peer peer, final ChatMessage msg, final Uri uri, final String path) {
        final Job job = new Job(msg.fileId, msg, msg.fileSize);
        register(job);
        new Thread(new Runnable() {
            @Override
            public void run() {
                doSendFile(peer, msg, uri, path, job);
            }
        }, "lanmesh-send-" + msg.fileId).start();
    }

    private InputStream openInput(Uri uri, String path) throws Exception {
        if (path != null) {
            File f = new File(path);
            if (f.isFile()) return new FileInputStream(f);
        }
        if (uri == null) throw new IllegalStateException("没有可读取的文件来源");
        ContentResolver cr = ctx.getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法打开文件流");
        return in;
    }

    private void doSendFile(Peer peer, ChatMessage msg, Uri uri, String path, Job job) {
        Socket socket = null;
        InputStream in = null;
        String failReason = null;
        try {
            // 1) 先整文件流式算 SHA-256（内存恒定 8KB）。1GB 文件在 eMMC 上约 5-10 秒，
            //    换来的是接收端可以无条件校验完整性。
            msg.status = ChatMessage.ST_SENDING;
            msg.progress = 0;
            Bus.post(Bus.MESSAGE_UPDATED, msg);

            in = openInput(uri, path);
            String digest = Util.sha256(in);
            Util.closeQuietly(in);
            in = null;

            if (job.cancelled) throw new InterruptedException("已取消");

            // 2) 建立连接并握手
            socket = new Socket();
            job.socket = socket;
            socket.connect(new InetSocketAddress(peer.host, peer.port), Const.SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(Const.SOCKET_TIMEOUT_MS);
            socket.setSendBufferSize(256 * 1024);
            Protocol.Reader reader = new Protocol.Reader(socket.getInputStream());
            Protocol.Writer writer = new Protocol.Writer(socket.getOutputStream());

            JSONObject hello = new JSONObject();
            hello.put("magic", Protocol.MAGIC);
            hello.put("proto", Const.PROTO_VERSION);
            hello.put("deviceId", prefs.deviceId());
            hello.put("nickname", prefs.nickname());
            hello.put("port", 0); // 发送方不需要对方回连
            writer.writeJson(Protocol.T_HELLO, hello);

            if (!handshake(reader, writer, peer)) {
                throw new IllegalStateException("对方未确认连接");
            }

            JSONObject offer = new JSONObject();
            offer.put("fileId", msg.fileId);
            offer.put("fileName", msg.fileName);
            offer.put("fileSize", msg.fileSize);
            offer.put("chunkSize", Const.CHUNK_SIZE);
            offer.put("transferMode", "local");
            offer.put("sha256", digest);
            writer.writeJson(Protocol.T_FILE_OFFER, offer);

            // 3) 等接收方给出已收到的块位图（断点续传）
            java.util.BitSet received = new java.util.BitSet();
            long deadline = System.currentTimeMillis() + Const.SOCKET_TIMEOUT_MS;
            while (true) {
                Protocol.Frame f = reader.read();
                if (f == null) throw new IllegalStateException("连接被中断");
                if (f.type == Protocol.T_FILE_ACCEPT) {
                    received = Protocol.bitsetFromBase64(f.json().optString("bitmap", ""));
                    break;
                } else if (f.type == Protocol.T_FILE_REJECT) {
                    throw new IllegalStateException("对方拒绝接收: " + f.json().optString("reason", "未知"));
                } else if (f.type == Protocol.T_CANCEL) {
                    throw new InterruptedException("对方取消");
                } else if (f.type == Protocol.T_BYE) {
                    throw new IllegalStateException("对方断开连接");
                }
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("等待接收确认超时");
            }

            // 4) 流式发送。断点续传时先跳过已确认的整块。
            in = openInput(uri, path);
            int chunk = Const.CHUNK_SIZE;
            byte[] buf = new byte[chunk];
            long skipped = 0;
            int totalChunks = (int) ((msg.fileSize + chunk - 1) / chunk);
            int startChunk = 0;
            if (!received.isEmpty()) {
                // 找到第一个未收到的块；它之前的都要跳过
                while (startChunk < totalChunks && received.get(startChunk)) startChunk++;
                skipped = (long) startChunk * chunk;
                long remain = skipped;
                while (remain > 0) {
                    long n = in.skip(remain);
                    if (n <= 0) {
                        if (in.read() < 0) break;
                        n = 1;
                    }
                    remain -= n;
                }
            }
            job.bytes = skipped;
            long sent = skipped;
            int index = startChunk;
            long lastUi = 0;
            long lastPing = 0;
            boolean pausedPosted = false;
            while (!job.cancelled) {
                if (job.paused) {
                    if (!pausedPosted) {
                        pausedPosted = true;
                        msg.status = ChatMessage.ST_PAUSED;
                        msg.speed = 0;
                        store.update(msg);
                        Bus.post(Bus.MESSAGE_UPDATED, msg);
                        updateNotification(job, "已暂停", msg.fileName);
                        try {
                            JSONObject o = new JSONObject();
                            o.put("fileId", msg.fileId);
                            writer.writeJson(Protocol.T_PAUSE, o);
                        } catch (Throwable ignored) {
                        }
                    }
                    long nowP = System.currentTimeMillis();
                    if (nowP - lastPing > 5000) {
                        lastPing = nowP;
                        try {
                            writer.writeJson(Protocol.T_PING, new JSONObject());
                        } catch (Throwable pingErr) {
                            // 对端已断开：暂停状态不能永远挂着，直接结束
                            throw new IllegalStateException("连接已断开");
                        }
                    }
                    Thread.sleep(150);
                    continue;
                }
                if (pausedPosted) {
                    pausedPosted = false;
                    msg.status = ChatMessage.ST_SENDING;
                    store.update(msg);
                    Bus.post(Bus.MESSAGE_UPDATED, msg);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("fileId", msg.fileId);
                        writer.writeJson(Protocol.T_RESUME, o);
                    } catch (Throwable ignored) {
                    }
                }
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                writer.writeChunk(index, buf, 0, n);
                sent += n;
                index++;
                job.bytes = sent;
                sampleSpeed(job);
                long now = System.currentTimeMillis();
                if (now - lastUi > 200) {
                    lastUi = now;
                    msg.progress = job.percent();
                    msg.speed = job.speed;
                    Bus.post(Bus.MESSAGE_UPDATED, msg);
                    updateNotification(job, "发送", msg.fileName);
                }
            }
            if (job.cancelled) throw new InterruptedException("已取消");

            // 5) 通知对端并等待校验结果
            JSONObject done = new JSONObject();
            done.put("fileId", msg.fileId);
            done.put("sha256", digest);
            done.put("size", msg.fileSize);
            writer.writeJson(Protocol.T_FILE_DONE, done);

            // 校验 1GB 文件需要时间，这里放宽到 120 秒
            socket.setSoTimeout(120000);
            boolean ok = false;
            String resultSha = digest;
            String err = "";
            while (true) {
                Protocol.Frame f = reader.read();
                if (f == null) break;
                if (f.type == Protocol.T_FILE_RESULT) {
                    ok = f.json().optBoolean("ok", false);
                    resultSha = f.json().optString("sha256", digest);
                    err = f.json().optString("error", "");
                    break;
                }
                if (f.type == Protocol.T_BYE) break;
            }
            if (!ok) throw new IllegalStateException("接收方校验失败: " + err);

            msg.status = ChatMessage.ST_DONE;
            msg.progress = 100;
            notifyDone("已发送 " + msg.fileName);
            msg.sha256 = resultSha;
            msg.speed = 0;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            Bus.toast("已发送 " + msg.fileName);
        } catch (InterruptedException e) {
            msg.status = ChatMessage.ST_CANCELLED;
            msg.error = "已取消";
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
        } catch (Throwable t) {
            Log.w(TAG, "send file failed", t);
            failReason = describe(t);
            msg.status = ChatMessage.ST_FAILED;
            msg.error = failReason;
            msg.speed = 0;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            Bus.toast("发送失败：" + failReason);
        } finally {
            Util.closeQuietly(in);
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
            unregister(job);
            updateNotification(job, "发送", msg.fileName);
        }
    }

    private String describe(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m;
    }

    /**
     * 客户端握手：HELLO 之后可能收到 TRUST_REQUEST（对方在等自己的用户确认），
     * 这时不是失败，需要继续等对方的最终答复。
     */
    public boolean handshake(Protocol.Reader reader, Protocol.Writer writer, Peer peer) throws Exception {
        long deadline = System.currentTimeMillis() + Const.TRUST_WAIT_MS + 5000;
        while (System.currentTimeMillis() < deadline) {
            Protocol.Frame f = reader.read();
            if (f == null) return false;
            if (f.type == Protocol.T_HELLO_ACK) {
                JSONObject o = f.json();
                peer.nickname = o.optString("nickname", peer.nickname);
                peer.trusted = true;
                peer.lastSeen = System.currentTimeMillis();
                peers.touch(peer.deviceId, peer.host, peer.port);
                return true;
            }
            if (f.type == Protocol.T_TRUST_REQUEST) {
                // 对方正在询问它的用户；继续等待，但给 UI 一个提示
                Bus.post(Bus.TOAST, "等待对方确认连接…");
                continue;
            }
            if (f.type == Protocol.T_TRUST_DENIED) {
                return false;
            }
            if (f.type == Protocol.T_BYE) {
                return false;
            }
        }
        return false;
    }

    private void sampleSpeed(Job job) {
        long now = System.currentTimeMillis();
        long dt = now - job.lastSampleAt;
        if (dt >= 1000) {
            long db = job.bytes - job.lastSampleBytes;
            job.speed = db * 1000 / Math.max(1, dt);
            job.lastSampleAt = now;
            job.lastSampleBytes = job.bytes;
        }
    }

    // ---------------- 任务表 ----------------

    public void register(Job job) {
        synchronized (jobs) {
            jobs.put(job.fileId, job);
        }
    }

    public void unregister(Job job) {
        synchronized (jobs) {
            jobs.remove(job.fileId);
        }
        updateNotification(job, "传输", job.msg == null ? "" : job.msg.fileName);
    }

    /** 暂停：保留连接与任务，发送端每 5 秒发 PING 保活。 */
    public boolean pause(String fileId) {
        Job job = findJob(fileId);
        if (job == null || job.cancelled) return false;
        job.paused = true;
        job.msg.status = ChatMessage.ST_PAUSED;
        job.msg.speed = 0;
        if (store != null) store.update(job.msg);
        Bus.post(Bus.MESSAGE_UPDATED, job.msg);
        return true;
    }

    /** 继续：恢复发送/接收循环。 */
    public boolean resume(String fileId) {
        Job job = findJob(fileId);
        if (job == null || job.cancelled) return false;
        job.paused = false;
        job.msg.status = job.msg.isOutgoing()
                ? ChatMessage.ST_SENDING : ChatMessage.ST_RECEIVING;
        if (store != null) store.update(job.msg);
        Bus.post(Bus.MESSAGE_UPDATED, job.msg);
        return true;
    }

    public boolean cancel(String fileId) {
        Job job;
        synchronized (jobs) {
            job = jobs.get(fileId);
        }
        if (job == null) return false;
        // 允许"暂停中直接取消"：先解除暂停，状态立即落库，不等传输线程
        job.paused = false;
        job.cancelled = true;
        job.msg.status = ChatMessage.ST_CANCELLED;
        job.msg.speed = 0;
        if (job.msg.error == null || job.msg.error.length() == 0) job.msg.error = "已取消";
        if (store != null) store.update(job.msg);
        Bus.post(Bus.MESSAGE_UPDATED, job.msg);
        Socket s = job.socket;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
        return true;
    }

    public List<Job> activeJobs() {
        synchronized (jobs) {
            return new ArrayList<Job>(jobs.values());
        }
    }

    public Job findJob(String fileId) {
        synchronized (jobs) {
            return jobs.get(fileId);
        }
    }

    // ---------------- 通知 ----------------

    private void updateNotification(Job job, String action, String name) {
        long now = System.currentTimeMillis();
        boolean finished = job.total > 0 && job.bytes >= job.total;
        // 节流：进度通知最多 2 秒一次，低端机上避免 NotificationManager 抖动
        if (!finished && now - lastNotifyAt < 2000) return;
        lastNotifyAt = now;
        if (nm == null) return;
        try {
            Intent i = new Intent(ctx, ChatActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "lanmesh_transfer")
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(ctx.getString(R.string.transfer_notif_title))
                    .setContentText((name == null ? "" : name) + " · " + job.percent() + "%")
                    .setOngoing(!finished)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pi);
            if (job.total > 0) {
                b.setProgress(100, job.percent(), false);
            }
            if (job.cancelled) {
                b.setContentTitle(ctx.getString(R.string.file_cancelled)).setProgress(0, 0, false);
            }
            Notification n = b.build();
            nm.notify(Const.NOTIF_ID_TRANSFER, n);
            if (finished || job.cancelled) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            nm.cancel(Const.NOTIF_ID_TRANSFER);
                        } catch (Throwable ignored) {
                        }
                    }
                }, 2500);
            }
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
        }
    }

    public void updateProgress(Job job) {
        updateNotification(job, "传输", job.msg == null ? "" : job.msg.fileName);
    }
}
