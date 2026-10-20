package com.jiuyue.lanmesh.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 一条聊天消息（文本 / 文件 / 系统提示）。
 *
 * 进度与瞬时速度不落盘（只存在于内存对象），避免传输时高频写文件。
 */
public class ChatMessage {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_FILE = "file";
    public static final String TYPE_SYSTEM = "system";

    public static final String DIR_IN = "in";
    public static final String DIR_OUT = "out";

    public static final String ST_SENDING = "sending";
    public static final String ST_SENT = "sent";
    public static final String ST_RECEIVING = "receiving";
    public static final String ST_DONE = "done";
    public static final String ST_FAILED = "failed";
    public static final String ST_CANCELLED = "cancelled";

    public String msgId;
    public String peerDeviceId;
    public String direction;
    public String type;
    public String text;
    public String fileName;
    public long fileSize;
    public String fileId;
    public long timestamp;
    public String status;
    public String sha256;
    public String localPath;
    public String error;
    public String transferMode = "local";

    /** 仅内存：0-100。 */
    public int progress;
    /** 仅内存：字节/秒。 */
    public long speed;
    /** 仅内存：发送失败可重试时保留原始 Uri 字符串。 */
    public String sourceUri;
    /** 仅内存：发送方本地文件路径（重试时优先用）。 */
    public String sourcePath;

    public ChatMessage() {
        this.msgId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isOutgoing() {
        return DIR_OUT.equals(direction);
    }

    public boolean isFile() {
        return TYPE_FILE.equals(type);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("msgId", msgId);
        o.put("peerDeviceId", peerDeviceId);
        o.put("direction", direction);
        o.put("type", type);
        o.put("text", text == null ? "" : text);
        o.put("fileName", fileName == null ? "" : fileName);
        o.put("fileSize", fileSize);
        o.put("fileId", fileId == null ? "" : fileId);
        o.put("timestamp", timestamp);
        o.put("status", status == null ? "" : status);
        o.put("sha256", sha256 == null ? "" : sha256);
        o.put("localPath", localPath == null ? "" : localPath);
        o.put("error", error == null ? "" : error);
        o.put("transferMode", transferMode == null ? "local" : transferMode);
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) {
        ChatMessage m = new ChatMessage();
        m.msgId = o.optString("msgId", m.msgId);
        m.peerDeviceId = o.optString("peerDeviceId", "");
        m.direction = o.optString("direction", DIR_IN);
        m.type = o.optString("type", TYPE_TEXT);
        m.text = o.optString("text", "");
        m.fileName = o.optString("fileName", "");
        m.fileSize = o.optLong("fileSize", 0);
        m.fileId = o.optString("fileId", "");
        m.timestamp = o.optLong("timestamp", System.currentTimeMillis());
        m.status = o.optString("status", ST_SENT);
        m.sha256 = o.optString("sha256", "");
        m.localPath = o.optString("localPath", "");
        m.error = o.optString("error", "");
        m.transferMode = o.optString("transferMode", "local");
        return m;
    }
}
