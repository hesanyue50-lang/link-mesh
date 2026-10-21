package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.util.Log;

import com.jiuyue.lanmesh.core.Bus;
import com.jiuyue.lanmesh.core.Const;
import com.jiuyue.lanmesh.core.Prefs;
import com.jiuyue.lanmesh.core.Util;
import com.jiuyue.lanmesh.model.ChatMessage;
import com.jiuyue.lanmesh.model.MessageStore;
import com.jiuyue.lanmesh.model.Peer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.util.BitSet;

/**
 * 一条入站 TCP 连接的处理器（服务端侧）。
 *
 * 每条连接都是"短连接 + 多帧"：握手一次后可连续发文本或完成一个文件，
 * 完成后关闭。这样不需要维护长连接心跳，切后台被系统冻结也不会留下
 * 半死不活的 socket。
 */
public class Session implements Runnable {

    private static final String TAG = "LanMeshSession";

    public interface FileGate {
        boolean askAccept(String deviceId, String fileName, long size);
    }

    public interface TrustGate {
        /**
         * 询问用户是否信任该设备。实现方需要阻塞等待用户点击（带 60 秒超时）。
         */
        boolean askTrust(String deviceId, String nickname, String host);
    }

    private final Context ctx;
    private final Socket socket;
    private final Prefs prefs;
    private final MessageStore store;
    private final PeerRegistry peers;
    private final TransferManager transfer;
    private final TrustGate trustGate;
    private final FileGate fileGate;

    public Session(Context ctx, Socket socket, Prefs prefs, MessageStore store,
                   PeerRegistry peers, TransferManager transfer, TrustGate gate, FileGate fileGate) {
        this.ctx = ctx.getApplicationContext();
        this.socket = socket;
        this.prefs = prefs;
        this.store = store;
        this.peers = peers;
        this.transfer = transfer;
        this.trustGate = gate;
        this.fileGate = fileGate;
    }

    @Override
    public void run() {
        Protocol.Reader reader = null;
        Protocol.Writer writer = null;
        try {
            socket.setSoTimeout(Const.SOCKET_TIMEOUT_MS);
            socket.setReceiveBufferSize(256 * 1024);
            reader = new Protocol.Reader(socket.getInputStream());
            writer = new Protocol.Writer(socket.getOutputStream());

            Protocol.Frame first = reader.read();
            if (first == null || first.type != Protocol.T_HELLO) {
                Log.w(TAG, "非 HELLO 首帧，关闭");
                return;
            }
            JSONObject hello = first.json();
            if (!Protocol.MAGIC.equals(hello.optString("magic", ""))) {
                Log.w(TAG, "magic 不匹配，关闭");
                return;
            }
            final String deviceId = hello.optString("deviceId", "");
            final String nickname = hello.optString("nickname", "");
            final int listenPort = hello.optInt("port", 0);
            if (deviceId.length() < 8) return;
            if (deviceId.equals(prefs.deviceId())) return;

            final String host = socket.getInetAddress().getHostAddress();
            Peer peer = peers.get(deviceId);
            if (peer == null) {
                peers.upsert(deviceId, nickname, host, listenPort > 0 ? listenPort : Const.DEFAULT_TCP_PORT, Peer.SRC_MANUAL);
                peer = peers.get(deviceId);
            }
            if (peer == null) return;
            peer.nickname = nickname;
            peer.lastSeen = System.currentTimeMillis();
            if (listenPort > 0) peer.port = listenPort;
            peer.host = host;
            prefs.rememberPeerAddress(deviceId, host, listenPort > 0 ? listenPort : peer.port);

            boolean trusted = prefs.isTrusted(deviceId);
            if (!trusted) {
                if (prefs.isDenied(deviceId)) {
                    JSONObject o = new JSONObject();
                    o.put("reason", "已被拒绝");
                    writer.writeJson(Protocol.T_TRUST_DENIED, o);
                    return;
                }
                JSONObject req = new JSONObject();
                req.put("deviceId", prefs.deviceId());
                req.put("nickname", prefs.nickname());
                writer.writeJson(Protocol.T_TRUST_REQUEST, req);

                boolean allow = trustGate != null && trustGate.askTrust(deviceId, nickname, host);
                if (!allow) {
                    // 注意：拒绝列表由 NetService 在"用户明确点拒绝"时写入；
                    // 用户不在场导致的超时不能永久拉黑，否则以后再也连不上。
                    JSONObject o = new JSONObject();
                    o.put("reason", "对方拒绝");
                    writer.writeJson(Protocol.T_TRUST_DENIED, o);
                    return;
                }
                prefs.trust(deviceId);
                peer.trusted = true;
                Bus.post(Bus.PEERS_CHANGED, null);
            }

            JSONObject ack = new JSONObject();
            ack.put("ok", true);
            ack.put("deviceId", prefs.deviceId());
            ack.put("nickname", prefs.nickname());
            ack.put("port", 0);
            writer.writeJson(Protocol.T_HELLO_ACK, ack);

            // 握手后可以连续多帧：文本 → ACK；文件 → 收完再继续
            while (true) {
                Protocol.Frame f = reader.read();
                if (f == null) break;
                if (f.type == Protocol.T_TEXT) {
                    handleText(f.json(), peer, writer);
                } else if (f.type == Protocol.T_FILE_OFFER) {
                    handleFileOffer(f.json(), peer, reader, writer);
                } else if (f.type == Protocol.T_PING) {
                    writer.writeJson(Protocol.T_PONG, new JSONObject());
                } else if (f.type == Protocol.T_CANCEL) {
                    String fid = f.json().optString("fileId", "");
                    transfer.cancel(fid);
                } else if (f.type == Protocol.T_BYE) {
                    break;
                }
            }
        } catch (Throwable t) {
            String m = t.getMessage();
            if (m != null && m.contains("Socket closed")) {
                Log.i(TAG, "socket closed");
            } else {
                Log.w(TAG, "session error", t);
            }
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleText(JSONObject o, Peer peer, Protocol.Writer writer) {
        try {
            String text = o.optString("text", "");
            if (text.length() == 0) return;
            ChatMessage msg = new ChatMessage();
            msg.msgId = o.optString("msgId", msg.msgId);
            msg.peerDeviceId = peer.deviceId;
            msg.direction = ChatMessage.DIR_IN;
            msg.type = ChatMessage.TYPE_TEXT;
            msg.text = text;
            msg.timestamp = o.optLong("timestamp", System.currentTimeMillis());
            msg.status = ChatMessage.ST_SENT;
            store.append(msg);
            peer.lastSeen = System.currentTimeMillis();
            peers.touch(peer.deviceId, peer.host, peer.port);
            Bus.post(Bus.MESSAGE_NEW, msg);
            Bus.post(Bus.PEERS_CHANGED, null);
            transfer.notifyMessage(peer.displayName(), text);

            JSONObject ack = new JSONObject();
            ack.put("msgId", msg.msgId);
            ack.put("ok", true);
            writer.writeJson(Protocol.T_TEXT_ACK, ack);
        } catch (Throwable t) {
            Log.w(TAG, "handleText failed", t);
        }
    }

    // ---------------- 文件接收 ----------------

    private void handleFileOffer(JSONObject offer, Peer peer, Protocol.Reader reader, Protocol.Writer writer) {
        String fileId = offer.optString("fileId", "");
        String fileName = Util.safeFileName(offer.optString("fileName", "file"));
        long fileSize = offer.optLong("fileSize", 0);
        int chunkSize = offer.optInt("chunkSize", Const.CHUNK_SIZE);
        String sha = offer.optString("sha256", "");
        if (fileId.length() == 0 || fileSize <= 0 || chunkSize <= 0) {
            reject(writer, fileId, "参数不合法");
            return;
        }
        if (chunkSize > Const.MAX_FRAME_BYTES - 8) {
            reject(writer, fileId, "分块过大");
            return;
        }

        // 关闭"自动接收"后，先问用户是否接收（60 秒超时按拒绝处理）
        if (!prefs.autoAcceptFiles()) {
            boolean accept = fileGate != null
                    && fileGate.askAccept(peer.deviceId, fileName, fileSize);
            if (!accept) {
                reject(writer, fileId, "对方未接受文件");
                return;
            }
        }

        File dir = getReceiveDir();
        if (dir == null) {
            reject(writer, fileId, "无法创建保存目录");
            return;
        }
        long free = dir.getUsableSpace();
        if (free > 0 && free < fileSize + 8L * 1024 * 1024) {
            reject(writer, fileId, "对方存储空间不足");
            return;
        }

        int totalChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
        File partFile = new File(dir, ".part-" + fileId);
        File stateFile = new File(dir, ".state-" + fileId);

        BitSet bits = new BitSet(totalChunks);
        boolean resumable = false;
        if (partFile.isFile() && stateFile.isFile()) {
            try {
                String s = readSmall(stateFile);
                JSONObject st = new JSONObject(s);
                if (st.optLong("size", -1) == fileSize
                        && st.optInt("chunkSize", -1) == chunkSize
                        && st.optInt("total", -1) == totalChunks
                        && partFile.length() == fileSize) {
                    bits = Protocol.bitsetFromBase64(st.optString("bits", ""));
                    resumable = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "resume state broken, restart", t);
            }
        }
        if (!resumable) {
            // 重新开始：清掉旧碎片，避免随机写残留脏数据
            if (partFile.exists()) partFile.delete();
            if (stateFile.exists()) stateFile.delete();
            bits.clear();
        }

        // 先把"已收到哪些块"告诉发送方，再开始收
        try {
            JSONObject acc = new JSONObject();
            acc.put("fileId", fileId);
            acc.put("bitmap", Protocol.bitsetToBase64(bits));
            acc.put("chunkSize", chunkSize);
            writer.writeJson(Protocol.T_FILE_ACCEPT, acc);
        } catch (Throwable t) {
            Log.w(TAG, "send accept failed", t);
            return;
        }

        ChatMessage msg;
        boolean isNew = false;
        ChatMessage existing = store.find(peer.deviceId, "file-" + fileId);
        if (existing != null) {
            // 同一 fileId 重传（断点续传/重试）：复用原消息，避免聊天记录出现两条
            msg = existing;
            msg.fileName = fileName;
            msg.fileSize = fileSize;
            msg.sha256 = sha;
            msg.status = ChatMessage.ST_RECEIVING;
            msg.error = null;
            msg.speed = 0;
            msg.progress = (int) (Protocol.countSet(bits, totalChunks) * 100L / Math.max(1, totalChunks));
            store.update(msg);
        } else {
            msg = new ChatMessage();
            msg.msgId = "file-" + fileId;
            msg.peerDeviceId = peer.deviceId;
            msg.direction = ChatMessage.DIR_IN;
            msg.type = ChatMessage.TYPE_FILE;
            msg.fileId = fileId;
            msg.fileName = fileName;
            msg.fileSize = fileSize;
            msg.sha256 = sha;
            msg.timestamp = System.currentTimeMillis();
            msg.status = ChatMessage.ST_RECEIVING;
            msg.transferMode = "local";
            msg.progress = (int) (Protocol.countSet(bits, totalChunks) * 100L / Math.max(1, totalChunks));
            store.append(msg);
            isNew = true;
        }
        Bus.post(isNew ? Bus.MESSAGE_NEW : Bus.MESSAGE_UPDATED, msg);

        TransferManager.Job job = new TransferManager.Job(fileId, msg, fileSize);
        job.bytes = (long) Protocol.countSet(bits, totalChunks) * chunkSize;
        if (job.bytes > fileSize) job.bytes = fileSize;
        job.socket = socket;
        transfer.register(job);

        RandomAccessFile raf = null;
        boolean ok = false;
        String error = "";
        try {
            raf = new RandomAccessFile(partFile, "rw");
            raf.setLength(fileSize);

            long lastStateAt = 0;
            long lastUiAt = 0;
            long lastSpeedAt = System.currentTimeMillis();
            long lastSpeedBytes = job.bytes;
            long deadline = System.currentTimeMillis() + 10 * 60 * 1000L; // 单文件 10 分钟硬超时

            boolean pausedPosted = false;
            while (!job.cancelled) {
                if (job.paused) {
                    if (!pausedPosted) {
                        pausedPosted = true;
                        msg.status = ChatMessage.ST_PAUSED;
                        msg.speed = 0;
                        store.update(msg);
                        Bus.post(Bus.MESSAGE_UPDATED, msg);
                    }
                    try { Thread.sleep(150); } catch (InterruptedException ie) { break; }
                    continue;
                }
                if (pausedPosted) {
                    pausedPosted = false;
                    msg.status = ChatMessage.ST_RECEIVING;
                    store.update(msg);
                    Bus.post(Bus.MESSAGE_UPDATED, msg);
                }
                if (System.currentTimeMillis() > deadline) {
                    error = "接收超时";
                    break;
                }
                Protocol.Frame f = reader.read();
                if (f == null) {
                    error = "连接中断";
                    break;
                }
                if (f.type == Protocol.T_CHUNK) {
                    if (f.payload.length < 5) continue;
                    int idx = ((f.payload[0] & 0xFF) << 24) | ((f.payload[1] & 0xFF) << 16)
                            | ((f.payload[2] & 0xFF) << 8) | (f.payload[3] & 0xFF);
                    int len = f.payload.length - 4;
                    if (idx < 0 || idx >= totalChunks) continue;
                    long off = (long) idx * chunkSize;
                    if (off + len > fileSize) len = (int) (fileSize - off);
                    if (len <= 0) continue;
                    raf.seek(off);
                    raf.write(f.payload, 4, len);
                    bits.set(idx);
                    job.bytes += len;
                    if (job.bytes > fileSize) job.bytes = fileSize;

                    long now = System.currentTimeMillis();
                    if (now - lastSpeedAt >= 1000) {
                        job.speed = (job.bytes - lastSpeedBytes) * 1000L
                                / Math.max(1L, now - lastSpeedAt);
                        lastSpeedAt = now;
                        lastSpeedBytes = job.bytes;
                    }
                    if (now - lastUiAt > 200) {
                        lastUiAt = now;
                        msg.progress = job.percent();
                        msg.speed = job.speed;
                        Bus.post(Bus.MESSAGE_UPDATED, msg);
                        transfer.updateProgress(job);
                    }
                    // 每 32 块（4MB）落一次位图，兼顾断电续传与 IO 开销
                    if (now - lastStateAt > 1500) {
                        lastStateAt = now;
                        writeState(stateFile, fileSize, chunkSize, totalChunks, bits);
                    }
                } else if (f.type == Protocol.T_FILE_DONE) {
                    String senderSha = f.json().optString("sha256", sha);
                    if (senderSha.length() > 0) sha = senderSha;
                    ok = true;
                    break;
                } else if (f.type == Protocol.T_PAUSE) {
                    msg.status = ChatMessage.ST_PAUSED;
                    msg.error = "对方已暂停";
                    msg.speed = 0;
                    store.update(msg);
                    Bus.post(Bus.MESSAGE_UPDATED, msg);
                } else if (f.type == Protocol.T_RESUME) {
                    msg.status = ChatMessage.ST_RECEIVING;
                    msg.error = null;
                    store.update(msg);
                    Bus.post(Bus.MESSAGE_UPDATED, msg);
                } else if (f.type == Protocol.T_CANCEL) {
                    error = "对方取消";
                    break;
                } else if (f.type == Protocol.T_BYE) {
                    error = "对方断开";
                    break;
                } else if (f.type == Protocol.T_PING) {
                    writer.writeJson(Protocol.T_PONG, new JSONObject());
                }
            }
            if (job.cancelled) error = "已取消";
        } catch (Throwable t) {
            Log.w(TAG, "receive failed", t);
            error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        } finally {
            Util.closeQuietly(raf);
            writeState(stateFile, fileSize, chunkSize, totalChunks, bits);
            transfer.unregister(job);
        }

        if (!ok) {
            boolean cancelled = job.cancelled;
            msg.status = cancelled ? ChatMessage.ST_CANCELLED : ChatMessage.ST_FAILED;
            msg.error = error;
            msg.speed = 0;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            try {
                JSONObject r = new JSONObject();
                r.put("fileId", fileId);
                r.put("ok", false);
                r.put("error", error);
                writer.writeJson(Protocol.T_FILE_RESULT, r);
            } catch (Throwable ignored) {
            }
            return;
        }

        // 校验 SHA-256（流式，内存恒定）
        msg.progress = 100;
        msg.speed = 0;
        msg.status = ChatMessage.ST_RECEIVING;
        Bus.post(Bus.MESSAGE_UPDATED, msg);

        String actual;
        try {
            actual = Util.sha256(partFile);
        } catch (Throwable t) {
            actual = "";
        }
        boolean hashOk = sha.length() == 0 || actual.equalsIgnoreCase(sha);
        if (!hashOk) {
            msg.status = ChatMessage.ST_FAILED;
            msg.error = "SHA-256 校验失败";
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            try {
                JSONObject r = new JSONObject();
                r.put("fileId", fileId);
                r.put("ok", false);
                r.put("error", "SHA-256 校验失败");
                r.put("sha256", actual);
                writer.writeJson(Protocol.T_FILE_RESULT, r);
            } catch (Throwable ignored) {
            }
            // 保留 .part 供下次续传，但清掉状态里的位图避免"看似完整实际错误"
            return;
        }

        File target = Util.uniqueFile(dir, fileName);
        if (partFile.renameTo(target)) {
            stateFile.delete();
            // 如果用户在设置里选了 SAF 目录（公共文件夹），把文件复制过去，
            // 否则 Android/data 下的文件第三方文件管理器没有权限打开。
            String tree = prefs.saveTreeUri();
            String moved = null;
            if (tree != null && tree.length() > 0) {
                moved = copyToTree(tree, target, fileName);
                if (moved != null) {
                    // 复制成功就删掉应用目录里的副本，避免占两份空间
                    target.delete();
                }
            }
            msg.status = ChatMessage.ST_DONE;
            msg.localPath = moved != null ? moved : target.getAbsolutePath();
            msg.sha256 = actual;
            msg.progress = 100;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            Bus.toast("已接收 " + target.getName());
            transfer.notifyDone("已接收 " + target.getName());
            try {
                JSONObject r = new JSONObject();
                r.put("fileId", fileId);
                r.put("ok", true);
                r.put("sha256", actual);
                writer.writeJson(Protocol.T_FILE_RESULT, r);
            } catch (Throwable ignored) {
            }
        } else {
            msg.status = ChatMessage.ST_FAILED;
            msg.error = "文件重命名失败";
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
        }
    }

    private void reject(Protocol.Writer writer, String fileId, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("fileId", fileId);
            o.put("reason", reason);
            writer.writeJson(Protocol.T_FILE_REJECT, o);
        } catch (Throwable ignored) {
        }
    }

    private void writeState(File f, long size, int chunkSize, int total, BitSet bits) {
        FileOutputStream out = null;
        try {
            JSONObject o = new JSONObject();
            o.put("size", size);
            o.put("chunkSize", chunkSize);
            o.put("total", total);
            o.put("bits", Protocol.bitsetToBase64(bits));
            out = new FileOutputStream(f);
            out.write(o.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "write resume state failed", t);
        } finally {
            Util.closeQuietly(out);
        }
    }

    private String readSmall(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            Util.closeQuietly(in);
        }
    }

    /** 把文件复制到 SAF 选定的目录，返回 content:// URI；失败返回 null。 */
    private String copyToTree(String treeUri, File src, String name) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            android.net.Uri tree = android.net.Uri.parse(treeUri);
            androidx.documentfile.provider.DocumentFile dir =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree);
            if (dir == null || !dir.canWrite()) return null;
            androidx.documentfile.provider.DocumentFile doc = dir.createFile(mimeOf(name), name);
            if (doc == null) return null;
            in = new java.io.FileInputStream(src);
            out = ctx.getContentResolver().openOutputStream(doc.getUri());
            if (out == null) return null;
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            Log.i(TAG, "copied to tree: " + doc.getUri());
            return doc.getUri().toString();
        } catch (Throwable t) {
            Log.w(TAG, "copy to tree failed", t);
            return null;
        } finally {
            Util.closeQuietly(in);
            Util.closeQuietly(out);
        }
    }

    private String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private File getReceiveDir() {
        File dir = com.jiuyue.lanmesh.core.StorageUtil.downloadDir(ctx);
        if (dir == null) return null;
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }
}
