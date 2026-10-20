package com.jiuyue.lanmesh;

import com.jiuyue.lanmesh.core.Util;
import com.jiuyue.lanmesh.model.ChatMessage;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 纯逻辑单测：不依赖 Android 运行时（不触碰 Looper/Context/Socket）。
 *
 * 网络协议无法在 JVM 单测里真实跑（android.util.Base64 是 stub），
 * 因此把可测的部分（命名清洗、尺寸格式化、消息序列化）覆盖住。
 */
public class UtilTest {

    @Test
    public void shortId_takes8Hex() {
        assertEquals("ABCDEF12", Util.shortId("abcdef12-3456-7890-abcd-ef1234567890"));
    }

    @Test
    public void shortId_nullSafe() {
        assertEquals("--------", Util.shortId(null));
        assertEquals("--------", Util.shortId("short"));
    }

    @Test
    public void safeFileName_stripsPath() {
        assertEquals("a.txt", Util.safeFileName("/sdcard/dir/a.txt"));
        assertEquals("a.txt", Util.safeFileName("C:\\dir\\a.txt"));
    }

    @Test
    public void safeFileName_replacesIllegal() {
        assertEquals("a_b_c.txt", Util.safeFileName("a:b*c.txt"));
        assertEquals("file", Util.safeFileName("   "));
        assertEquals("file", Util.safeFileName(null));
    }

    @Test
    public void humanSize_units() {
        assertEquals("0 B", Util.humanSize(0));
        assertEquals("512 B", Util.humanSize(512));
        assertEquals("1.0 KB", Util.humanSize(1024));
        assertEquals("1.0 MB", Util.humanSize(1024 * 1024));
        assertEquals("1.00 GB", Util.humanSize(1024L * 1024 * 1024));
    }

    @Test
    public void messageJson_roundTripKeepsCriticalFields() throws Exception {
        ChatMessage m = new ChatMessage();
        m.peerDeviceId = "peer-1";
        m.direction = ChatMessage.DIR_OUT;
        m.type = ChatMessage.TYPE_FILE;
        m.fileName = "大文件.bin";
        m.fileSize = 1024L * 1024 * 1024;
        m.fileId = "f-1";
        m.status = ChatMessage.ST_SENDING;
        m.text = "带 emoji 🚀 的文本";
        m.sha256 = "abc123";
        m.transferMode = "local";

        JSONObject o = m.toJson();
        ChatMessage n = ChatMessage.fromJson(o);

        assertEquals(m.msgId, n.msgId);
        assertEquals(m.peerDeviceId, n.peerDeviceId);
        assertEquals(m.direction, n.direction);
        assertEquals(m.type, n.type);
        assertEquals(m.fileName, n.fileName);
        assertEquals(m.fileSize, n.fileSize);
        assertEquals(m.fileId, n.fileId);
        assertEquals(m.text, n.text);
        assertEquals(m.sha256, n.sha256);
        assertEquals(m.transferMode, n.transferMode);
        assertTrue(n.isFile());
        assertTrue(n.isOutgoing());
        assertFalse(ChatMessage.DIR_IN.equals(n.direction));
    }
}
