package com.jiuyue.lanmesh.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 传输历史：最近的文件收发记录。
 *
 * 只存元数据，不存文件内容；文件本体在下载目录里，localPath 指向它。
 * 用单独的 SharedPreferences 文件而不是复用 Prefs：历史是纯展示数据，
 * 随时可以整块清空，和身份/信任列表的生命周期不同，混在一起反而容易误删。
 *
 * 上限 60 条：再多也没人翻，还会拖慢每次启动的解析。
 * 所有写操作都在静态锁里做读改写，避免多线程（传输线程 + UI 线程）互相覆盖。
 *
 * 实际读改写逻辑收在 *From / addTo 这类接收 SharedPreferences 的方法里，
 * 公开方法只负责取 sp，好处是存储行为可以脱离 Android 运行时单测。
 */
public class History {

    public static final int DIR_SEND = 0;
    public static final int DIR_RECV = 1;

    private static final String TAG = "LanMeshHistory";

    /** SharedPreferences 文件名。 */
    private static final String FILE = "lanmesh_history";
    /** 存放整个 JSON 数组的键名。 */
    private static final String K_ENTRIES = "entries";

    private static final int MAX = 60;

    private static final Object LOCK = new Object();

    private static final String F_PEER_NAME = "peerName";
    private static final String F_FILE_NAME = "fileName";
    private static final String F_SIZE = "size";
    private static final String F_TS = "ts";
    private static final String F_DIR = "dir";
    private static final String F_OK = "ok";
    private static final String F_ERROR = "error";
    private static final String F_LOCAL_PATH = "localPath";
    private static final String F_PEER_ID = "peerId";
    private static final String F_BATCH_ID = "batchId";

    private History() {
    }

    public static class Entry {
        /** 对方名称（昵称，可能为空）。 */
        public String peerName;
        /** 文件名（原始名，展示用）。 */
        public String fileName;
        /** 字节数。 */
        public long size;
        /** 完成时间 millis。 */
        public long ts;
        /** DIR_SEND / DIR_RECV。 */
        public int dir;
        /** 成功与否。 */
        public boolean ok;
        /** 失败原因，成功时为 null。 */
        public String error;
        /** 接收到的本地绝对路径，发送时可为 null。 */
        public String localPath;
        /** 对端 id，用于重发时定位设备。 */
        public String peerId;
        /** 同一批传输共用一个 id（一次多选/多台的多个文件）。 */
        public String batchId;

        public Entry() {
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put(F_PEER_NAME, peerName == null ? "" : peerName);
                o.put(F_FILE_NAME, fileName == null ? "" : fileName);
                o.put(F_SIZE, size);
                o.put(F_TS, ts);
                o.put(F_DIR, dir);
                o.put(F_OK, ok);
                if (error != null) o.put(F_ERROR, error);
                if (localPath != null) o.put(F_LOCAL_PATH, localPath);
                if (peerId != null) o.put(F_PEER_ID, peerId);
                if (batchId != null) o.put(F_BATCH_ID, batchId);
            } catch (Throwable t) {
                // put 在 key 非空时不会失败，这里只是兜底，不让一条坏记录带走整次写入
                Log.w(TAG, "entry toJson failed", t);
            }
            return o;
        }

        /** 解析单条；缺字段用默认值，不抛异常。 */
        public static Entry fromJson(JSONObject o) {
            if (o == null) return null;
            Entry e = new Entry();
            e.peerName = o.optString(F_PEER_NAME, "");
            e.fileName = o.optString(F_FILE_NAME, "");
            e.size = o.optLong(F_SIZE, 0L);
            e.ts = o.optLong(F_TS, 0L);
            e.dir = o.optInt(F_DIR, DIR_SEND);
            e.ok = o.optBoolean(F_OK, true);
            e.error = o.isNull(F_ERROR) ? null : o.optString(F_ERROR, null);
            e.localPath = o.isNull(F_LOCAL_PATH) ? null : o.optString(F_LOCAL_PATH, null);
            e.peerId = o.isNull(F_PEER_ID) ? null : o.optString(F_PEER_ID, null);
            e.batchId = o.isNull(F_BATCH_ID) ? null : o.optString(F_BATCH_ID, null);
            return e;
        }
    }

    /** 插入到最前，保留最近 MAX 条。 */
    public static void add(Context ctx, Entry e) {
        if (ctx == null || e == null) return;
        SharedPreferences p = sp(ctx);
        if (p == null) return;
        synchronized (LOCK) {
            addTo(p, e);
        }
    }

    /** 最新的在前；解析失败返回空列表。 */
    public static List<Entry> list(Context ctx) {
        if (ctx == null) return new ArrayList<Entry>();
        SharedPreferences p = sp(ctx);
        if (p == null) return new ArrayList<Entry>();
        synchronized (LOCK) {
            return listFrom(p);
        }
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        SharedPreferences p = sp(ctx);
        if (p == null) return;
        synchronized (LOCK) {
            clearFrom(p);
        }
    }

    /** 按时间戳删一条（命中第一条即停，同 ts 的其余记录保留）。 */
    public static void remove(Context ctx, long ts) {
        if (ctx == null) return;
        SharedPreferences p = sp(ctx);
        if (p == null) return;
        synchronized (LOCK) {
            removeFrom(p, ts);
        }
    }

    /** 一次多选/多台传输共用的批次 id。 */
    public static String newBatchId() {
        return UUID.randomUUID().toString();
    }

    private static SharedPreferences sp(Context ctx) {
        try {
            return ctx.getApplicationContext()
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            Log.w(TAG, "getSharedPreferences failed", t);
            return null;
        }
    }

    // 以下方法必须在 LOCK 内调用。接收 SharedPreferences 而不是 Context，便于单测。

    static void addTo(SharedPreferences p, Entry e) {
        List<Entry> list = listFrom(p);
        list.add(0, e);
        while (list.size() > MAX) {
            list.remove(list.size() - 1);
        }
        writeTo(p, list);
    }

    static List<Entry> listFrom(SharedPreferences p) {
        List<Entry> out = new ArrayList<Entry>();
        String raw;
        try {
            raw = p.getString(K_ENTRIES, null);
        } catch (Throwable t) {
            Log.w(TAG, "read prefs failed", t);
            return out;
        }
        if (raw == null || raw.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Entry e = Entry.fromJson(arr.optJSONObject(i));
                if (e != null && (e.fileName != null || e.ts > 0)) out.add(e);
            }
        } catch (Throwable t) {
            // 存储被写坏（断电、降级安装）时不能让"工具"页打不开
            Log.w(TAG, "history json broken, reset", t);
            out.clear();
        }
        return out;
    }

    static void clearFrom(SharedPreferences p) {
        try {
            p.edit().remove(K_ENTRIES).apply();
        } catch (Throwable t) {
            Log.w(TAG, "clear failed", t);
        }
    }

    static void removeFrom(SharedPreferences p, long ts) {
        List<Entry> list = listFrom(p);
        boolean hit = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).ts == ts) {
                list.remove(i);
                hit = true;
                break;
            }
        }
        if (hit) writeTo(p, list);
    }

    static void writeTo(SharedPreferences p, List<Entry> list) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            arr.put(list.get(i).toJson());
        }
        try {
            p.edit().putString(K_ENTRIES, arr.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "write failed", t);
        }
    }
}
