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
import java.io.InputStreamReader;
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
                Log.w(TAG, "load messages failed: " + f, t);
            } finally {
                close(r);
            }
        }
        cache.put(peerId, list);
        return new ArrayList<ChatMessage>(list);
    }

    public synchronized void append(ChatMessage msg) {
        if (msg == null || msg.peerDeviceId == null) return;
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
            w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            w.write(arr.toString());
            w.flush();
            w.close();
            w = null;
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "cannot delete old message file");
            }
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "rename message file failed");
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
