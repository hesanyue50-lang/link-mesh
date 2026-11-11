package com.jiuyue.lanmesh.model;

import android.content.Context;
import android.util.Log;

import com.jiuyue.lanmesh.core.Const;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地聊天记录：每个对端一个 JSON 文件。
 *
 * 明确不做云端同步 —— 需求要求"无账号、无云端历史"。文件上限 500 条，
 * 超出后丢弃最旧记录，防止长期使用后 IO 越来越慢。
 */
public class MessageStore {

    private static final String TAG = "LanMeshMsgStore";
    private final File dir;
    private final Map<String, List<ChatMessage>> cache = new HashMap<String, List<ChatMessage>>();

    public MessageStore(Context ctx) {
        this.dir = new File(ctx.getFilesDir(), "messages");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create message dir: " + dir);
        }
    }

    private File fileFor(String peerId) {
        String safe = peerId == null ? "unknown" : peerId.replaceAll("[^a-zA-Z0-9-]", "_");
        return new File(dir, safe + ".json");
    }

    public synchronized List<ChatMessage> load(String peerId) {
        List<ChatMessage> cached = cache.get(peerId);
        if (cached != null) return new ArrayList<ChatMessage>(cached);

        List<ChatMessage> list = new ArrayList<ChatMessage>();
        File f = fileFor(peerId);
        if (f.isFile()) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                JSONArray arr = new JSONArray(sb.toString());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) list.add(ChatMessage.fromJson(o));
                }
            } catch (Throwable t) {
                // 文件损坏：先留一份原样副本再继续，绝不把这个空列表当成权威数据缓存起来。
                // 原来直接 cache.put(空list)，用户随手发一条消息就把整份记录覆盖没了。
                Log.w(TAG, "load messages failed: " + f, t);
                try {
                    File bad = new File(dir, f.getName() + ".corrupt-"
                            + System.currentTimeMillis());
                    copyFile(f, bad);
                } catch (Throwable ignored) {
                }
                corrupt.put(peerId, Boolean.TRUE);
                return new ArrayList<ChatMessage>(list);
            } finally {
                close(r);
            }
        }
        cache.put(peerId, list);
        return new ArrayList<ChatMessage>(list);
    }

    /** 文件读坏过的会话：下次写之前再试一次，别用空列表覆盖别人 */
    private final java.util.Map<String, Boolean> corrupt =
            new java.util.HashMap<String, Boolean>();

    /** 重新解析一次；成功则清掉损坏标记并写进缓存，仍失败返回 null。 */
    private List<ChatMessage> tryReload(String peerId) {
        File f = fileFor(peerId);
        if (!f.isFile()) {
            corrupt.remove(peerId);
            return new ArrayList<ChatMessage>();
        }
        List<ChatMessage> list = new ArrayList<ChatMessage>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(ChatMessage.fromJson(o));
            }
        } catch (Throwable t) {
            return null;
        } finally {
            close(r);
        }
        corrupt.remove(peerId);
        cache.put(peerId, list);
        return list;
    }

    private void copyFile(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            close(in);
            close(out);
        }
    }

    public synchronized void append(ChatMessage msg) {
        if (msg == null || msg.peerDeviceId == null) return;
        // 文件读坏过的会话：写之前再试着解析一次。仍失败就保留 .corrupt 副本，
        // 宁可这次写不进去，也不能拿半截列表把原记录整份覆盖掉。
        if (corrupt.containsKey(msg.peerDeviceId)) {
            List<ChatMessage> recovered = tryReload(msg.peerDeviceId);
            if (recovered == null) {
                Log.w(TAG, "refuse to overwrite corrupt store: " + msg.peerDeviceId);
                return;
            }
        }
        List<ChatMessage> list = cache.get(msg.peerDeviceId);
        if (list == null) {
            list = load(msg.peerDeviceId);
        }
        list.add(msg);
        while (list.size() > Const.MAX_MESSAGES_PER_PEER) {
            list.remove(0);
        }
        cache.put(msg.peerDeviceId, list);
        persist(msg.peerDeviceId, list);
    }

    /** 状态变化（发送成功/失败/完成）落盘；进度更新请勿调用，避免高频 IO。 */
    public synchronized void update(ChatMessage msg) {
        if (msg == null || msg.peerDeviceId == null) return;
        List<ChatMessage> list = cache.get(msg.peerDeviceId);
        if (list == null) list = load(msg.peerDeviceId);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).msgId.equals(msg.msgId)) {
                list.set(i, msg);
                break;
            }
        }
        cache.put(msg.peerDeviceId, list);
        persist(msg.peerDeviceId, list);
    }

    public synchronized ChatMessage find(String peerId, String msgId) {
        List<ChatMessage> list = load(peerId);
        for (ChatMessage m : list) {
            if (m.msgId.equals(msgId)) return m;
        }
        return null;
    }

    /** 所有本地会话的 peerId（用于网页端聚合展示）。 */
    public synchronized java.util.List<String> peerIds() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".json") || n.endsWith(".tmp")) continue;
            out.add(n.substring(0, n.length() - 5));
        }
        return out;
    }

    public synchronized void remove(String peerId, String msgId) {
        if (peerId == null || msgId == null) return;
        List<ChatMessage> list = cache.get(peerId);
        if (list == null) list = load(peerId);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).msgId.equals(msgId)) {
                list.remove(i);
                break;
            }
        }
        cache.put(peerId, list);
        persist(peerId, list);
    }

    public synchronized void clear(String peerId) {
        cache.remove(peerId);
        File f = fileFor(peerId);
        if (f.exists()) f.delete();
    }

    private void persist(String peerId, List<ChatMessage> list) {
        File target = fileFor(peerId);
        File tmp = new File(dir, target.getName() + ".tmp");
        OutputStreamWriter w = null;
        try {
            JSONArray arr = new JSONArray();
            // 写入时保持时间顺序（load 也是顺序读）
            List<ChatMessage> ordered = new ArrayList<ChatMessage>(list);
            Collections.sort(ordered, new java.util.Comparator<ChatMessage>() {
                @Override
                public int compare(ChatMessage a, ChatMessage b) {
                    if (a.timestamp == b.timestamp) return 0;
                    return a.timestamp < b.timestamp ? -1 : 1;
                }
            });
            for (ChatMessage m : ordered) arr.put(m.toJson());
            FileOutputStream fos = new FileOutputStream(tmp);
            w = new OutputStreamWriter(fos, "UTF-8");
            w.write(arr.toString());
            w.flush();
            try {
                fos.getFD().sync();   // 不 sync 的话断电后可能只剩 .tmp
            } catch (Throwable ignored) {
            }
            w.close();
            w = null;
            // 同目录 rename 是原子的，可以直接覆盖。"先删后改名"只要在两步之间被杀，
            // 就会连原文件一起消失（只剩 .tmp，而 load 只认 .json）。
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "rename message file failed");
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) Log.w(TAG, "rename retry failed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "persist messages failed", t);
        } finally {
            close(w);
        }
    }

    private void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
