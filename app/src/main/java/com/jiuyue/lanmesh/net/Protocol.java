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
    public static final int T_PAUSE = 17;
    public static final int T_RESUME = 18;

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
            case T_PAUSE: return "PAUSE";
            case T_RESUME: return "RESUME";
            default: return "T?" + t;
        }
    }

    /** 解密失败（口令不一致或密文被改）。单独成类，方便上层给出"口令不一致"的提示。 */
    public static class DecryptException extends IOException {
        public DecryptException(String m, Throwable cause) {
            super(m, cause);
        }
    }

    // ---------- 可选传输加密（AES-GCM，口令派生） ----------

    /**
     * 只加密 payload，帧头仍是明文的 长度+类型。
     *
     * 选口令而不是证书交换：局域网场景下用户能理解"两边填同一个口令"，
     * 且不需要 CA、不需要处理自签证书校验，minSdk 19 也不用引 BouncyCastle。
     * 代价是安全性取决于口令强度——弱口令可被离线暴力破解，界面上要提示这一点。
     */
    public static final class Secure {
        private final javax.crypto.SecretKey key;
        private final java.security.SecureRandom rnd = new java.security.SecureRandom();
        private final javax.crypto.Cipher cipher;

        public Secure(javax.crypto.SecretKey key) throws Exception {
            this.key = key;
            this.cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        }

        public synchronized byte[] seal(byte[] plain) throws Exception {
            byte[] iv = new byte[12];
            rnd.nextBytes(iv);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        }

        public synchronized byte[] open(byte[] data) throws Exception {
            if (data.length < 28) throw new IOException("加密帧太短: " + data.length);
            byte[] iv = new byte[12];
            System.arraycopy(data, 0, iv, 0, 12);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            return cipher.doFinal(data, 12, data.length - 12);
        }
    }

    public static byte[] newNonce() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * 双方用同一个口令 + 两个 nonce 派生同一把密钥。
     * nonce 必须先排序再拼接，否则两端顺序不同会得到不同密钥。
     */
    public static javax.crypto.SecretKey deriveKey(String pass, byte[] nonceA, byte[] nonceB)
            throws Exception {
        if (pass == null || pass.length() == 0) throw new IllegalArgumentException("口令为空");
        byte[] first = nonceA, second = nonceB;
        if (compareBytes(nonceA, nonceB) > 0) {
            first = nonceB;
            second = nonceA;
        }
        byte[] salt = new byte[first.length + second.length];
        System.arraycopy(first, 0, salt, 0, first.length);
        System.arraycopy(second, 0, salt, first.length, second.length);
        javax.crypto.spec.PBEKeySpec spec =
                new javax.crypto.spec.PBEKeySpec(pass.toCharArray(), salt, 12000, 128);
        javax.crypto.SecretKeyFactory f =
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        return new javax.crypto.spec.SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xFF, y = b[i] & 0xFF;
            if (x != y) return x - y;
        }
        return a.length - b.length;
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
        private volatile Secure secure;

        public Reader(InputStream in) {
            this.in = new DataInputStream(in);
        }

        /** 握手完成后调用：之后收到的 payload 一律按加密帧解析。 */
        public void setSecure(Secure s) {
            this.secure = s;
        }

        public boolean encrypted() {
            return secure != null;
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
            Secure s = secure;
            if (s != null) {
                try {
                    buf = s.open(buf);
                } catch (Exception e) {
                    throw new DecryptException("解密失败（口令不一致？）", e);
                }
            }
            return new Frame(type, buf);
        }
    }

    public static final class Writer {
        private final DataOutputStream out;
        private volatile Secure secure;

        public Writer(OutputStream out) {
            this.out = new DataOutputStream(out);
        }

        public void setSecure(Secure s) {
            this.secure = s;
        }

        public boolean encrypted() {
            return secure != null;
        }

        public synchronized void write(int type, byte[] payload) throws IOException {
            if (payload == null) payload = new byte[0];
            Secure s = secure;
            if (s != null) {
                try {
                    payload = s.seal(payload);
                } catch (Exception e) {
                    throw new IOException("加密失败", e);
                }
            }
            out.writeInt(payload.length);
            out.writeByte(type);
            out.write(payload);
            out.flush();
        }

        public void writeJson(int type, JSONObject obj) throws IOException {
            write(type, obj == null ? new byte[0] : obj.toString().getBytes("UTF-8"));
        }

        public synchronized void writeChunk(int index, byte[] data, int offset, int count)
                throws IOException {
            if (secure == null) {
                // 明文快路径：一次写出，不做多余拷贝
                out.writeInt(4 + count);
                out.writeByte(T_CHUNK);
                out.writeInt(index);
                out.write(data, offset, count);
                out.flush();
                return;
            }
            byte[] raw = new byte[4 + count];
            raw[0] = (byte) (index >>> 24);
            raw[1] = (byte) (index >>> 16);
            raw[2] = (byte) (index >>> 8);
            raw[3] = (byte) index;
            System.arraycopy(data, offset, raw, 4, count);
            write(T_CHUNK, raw);
        }
    }
}
