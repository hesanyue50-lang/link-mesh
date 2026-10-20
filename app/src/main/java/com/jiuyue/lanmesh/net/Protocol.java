package com.jiuyue.lanmesh.net;

import android.util.Base64;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.BitSet;

/**
 * LanMesh 二进制帧协议。
 *
 * 为什么不用现成框架（OkHttp / Netty）：
 *  - 阶段一必须支持 API 19，OkHttp 4.x 需要 Java 8+ 与 API 21；
 *  - 需求明确是"局域网 + 零云成本"，HTTP 头开销与连接复用在文件传输上是负担。
 *
 * 帧格式（大端）：
 *   int32 payloadLength  |  int8 frameType  |  payload[payloadLength]
 *
 * 控制帧 payload 为 UTF-8 JSON；CHUNK 帧 payload = int32 chunkIndex + 原始字节。
 * 每帧长度受 Const.MAX_FRAME_BYTES 限制，防止对端发送异常长度导致 OOM。
 */
public final class Protocol {

    private Protocol() {}

    public static final String MAGIC = "LMSH";
    public static final int T_HELLO = 1;
    public static final int T_HELLO_ACK = 2;
    public static final int T_TEXT = 3;
    public static final int T_TEXT_ACK = 4;
    public static final int T_FILE_OFFER = 5;
    public static final int T_FILE_ACCEPT = 6;
    public static final int T_FILE_REJECT = 7;
    public static final int T_CHUNK = 8;
    public static final int T_FILE_DONE = 9;
    public static final int T_FILE_RESULT = 10;
    public static final int T_CANCEL = 11;
    public static final int T_PING = 12;
    public static final int T_PONG = 13;
    public static final int T_TRUST_REQUEST = 14;
    public static final int T_TRUST_DENIED = 15;
    public static final int T_BYE = 16;

    public static String typeName(int t) {
        switch (t) {
            case T_HELLO: return "HELLO";
            case T_HELLO_ACK: return "HELLO_ACK";
            case T_TEXT: return "TEXT";
            case T_TEXT_ACK: return "TEXT_ACK";
            case T_FILE_OFFER: return "FILE_OFFER";
            case T_FILE_ACCEPT: return "FILE_ACCEPT";
            case T_FILE_REJECT: return "FILE_REJECT";
            case T_CHUNK: return "CHUNK";
            case T_FILE_DONE: return "FILE_DONE";
            case T_FILE_RESULT: return "FILE_RESULT";
            case T_CANCEL: return "CANCEL";
            case T_PING: return "PING";
            case T_PONG: return "PONG";
            case T_TRUST_REQUEST: return "TRUST_REQUEST";
            case T_TRUST_DENIED: return "TRUST_DENIED";
            case T_BYE: return "BYE";
            default: return "T?" + t;
        }
    }

    // ---------- BitSet（断点续传位图）编解码 ----------

    public static String bitsetToBase64(BitSet bits) {
        if (bits == null) bits = new BitSet();
        return Base64.encodeToString(bits.toByteArray(), Base64.NO_WRAP);
    }

    public static BitSet bitsetFromBase64(String s) {
        BitSet bits = new BitSet();
        if (s == null || s.length() == 0) return bits;
        try {
            byte[] data = Base64.decode(s, Base64.NO_WRAP);
            BitSet parsed = BitSet.valueOf(data);
            return parsed;
        } catch (Throwable t) {
            return bits;
        }
    }

    /** 已收到的块数（用于进度与日志）。 */
    public static int countSet(BitSet bits, int totalChunks) {
        if (bits == null) return 0;
        int count = 0;
        for (int i = bits.nextSetBit(0); i >= 0 && i < totalChunks; i = bits.nextSetBit(i + 1)) {
            count++;
        }
        return count;
    }

    // ---------- 帧读写 ----------

    public static final class Frame {
        public final int type;
        public final byte[] payload;

        public Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        public JSONObject json() {
            try {
                return new JSONObject(new String(payload, "UTF-8"));
            } catch (Throwable t) {
                return new JSONObject();
            }
        }
    }

    public static final class Reader {
        private final DataInputStream in;

        public Reader(InputStream in) {
            this.in = new DataInputStream(in);
        }

        public Frame read() throws IOException {
            int len;
            try {
                len = in.readInt();
            } catch (EOFException e) {
                return null; // 对端正常关闭
            }
            if (len < 0 || len > com.jiuyue.lanmesh.core.Const.MAX_FRAME_BYTES) {
                throw new IOException("非法帧长度: " + len);
            }
            int type = in.readUnsignedByte();
            byte[] buf = new byte[len];
            in.readFully(buf);
            return new Frame(type, buf);
        }
    }

    public static final class Writer {
        private final DataOutputStream out;

        public Writer(OutputStream out) {
            this.out = new DataOutputStream(out);
        }

        public synchronized void write(int type, byte[] payload) throws IOException {
            if (payload == null) payload = new byte[0];
            out.writeInt(payload.length);
            out.writeByte(type);
            out.write(payload);
            out.flush();
        }

        public void writeJson(int type, JSONObject obj) throws IOException {
            write(type, obj == null ? new byte[0] : obj.toString().getBytes("UTF-8"));
        }

        public void writeChunk(int index, byte[] data, int offset, int count) throws IOException {
            out.writeInt(4 + count);
            out.writeByte(T_CHUNK);
            out.writeInt(index);
            out.write(data, offset, count);
            out.flush();
        }
    }
}
