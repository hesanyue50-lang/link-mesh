package com.jiuyue.lanmesh.net;

import android.util.Base64;
import android.util.Log;

import com.jiuyue.lanmesh.core.Prefs;

import org.json.JSONObject;

/**
 * 可选的传输加密协商。
 *
 * 设计取舍：
 *  - 用"双方同一个口令"而不是配对码/证书：局域网里用户能理解"两台都填一样的口令"，
 *    不需要 CA、不需要处理自签证书，minSdk 19 也不用额外库。
 *  - 设备发现层（NSD / 组播 / UDP）一律不加密，只加密 TCP 会话的 payload；
 *    设备名和 IP 在局域网里本来就是公开信息。
 *  - 双方各自生成 16 字节 nonce，PBKDF2 派生 AES-128 密钥；
 *    nonce 排序后再拼盐，保证两端的派生结果一致。
 *
 * 兼容性：老版本（1.5.3 之前）的 HELLO 里没有 enc 字段。
 * 本机开启加密时会明确拒绝这类对端，而不是悄悄降级成明文。
 */
public final class EncHandshake {

    private static final String TAG = "LanMeshEnc";

    static final String K_ENC = "enc";       // 协议支持标记
    static final String K_ON = "encOn";      // 本端是否要求加密
    static final String K_NONCE = "encNonce"; // base64

    private EncHandshake() {
    }

    /** 本机是否真正会加密：开关打开且口令有效。 */
    public static boolean enabled(Prefs prefs) {
        if (prefs == null || !prefs.encEnabled()) return false;
        String p = prefs.encPass();
        return p != null && p.trim().length() >= 4;
    }

    /** 往 HELLO 里写入协商字段，返回本端 nonce（base64）。 */
    public static String attach(JSONObject hello, Prefs prefs) {
        String nonce = Base64.encodeToString(Protocol.newNonce(), Base64.NO_WRAP);
        try {
            hello.put(K_ENC, 1);
            hello.put(K_ON, enabled(prefs));
            hello.put(K_NONCE, nonce);
        } catch (Throwable ignored) {
        }
        return nonce;
    }

    /** 在应答里带上本端 nonce（服务端用）。 */
    public static String attachAck(JSONObject ack, Prefs prefs) {
        return attach(ack, prefs);
    }

    /**
     * 根据对端握手报文协商出密钥。
     *
     * @return null 表示双方都没开加密，明文继续
     * @throws IllegalStateException 协商失败（一方要求加密而另一方不支持/口令没配）
     */
    public static Protocol.Secure build(JSONObject remote, String myNonce, Prefs prefs)
            throws Exception {
        if (remote == null) return null;
        boolean mineOn = enabled(prefs);
        boolean peerSupport = remote.optInt(K_ENC, 0) >= 1;
        boolean peerOn = remote.optBoolean(K_ON, false);

        if (!mineOn && !peerOn) {
            // 开关开着但口令不合法：宁可报错也不能悄悄按明文走
            if (prefs.encEnabled()) {
                throw new IllegalStateException("加密已开启但口令无效，请到设置里重新填写");
            }
            return null;
        }
        // 文案保持中性：这段文字可能在任意一端显示，说"对方"会指引用户改错机器
        if (!mineOn) {
            throw new IllegalStateException("两端加密设置不一致：对端已开启，本机未开启");
        }
        if (!peerSupport) {
            throw new IllegalStateException("两端加密设置不一致：对端版本不支持加密");
        }
        if (!peerOn) {
            throw new IllegalStateException("两端加密设置不一致：本机已开启，对端未开启");
        }
        String peerNonce = remote.optString(K_NONCE, "");
        if (peerNonce.length() == 0 || myNonce == null || myNonce.length() == 0) {
            throw new IllegalStateException("加密协商缺少随机数");
        }
        byte[] a = Base64.decode(myNonce, Base64.NO_WRAP);
        byte[] b = Base64.decode(peerNonce, Base64.NO_WRAP);
        String pass = prefs.encPass();
        if (pass == null || pass.length() < 4) {
            throw new IllegalStateException("本机口令太短（至少 4 位）");
        }
        Log.i(TAG, "negotiated AES-128 for session");
        return new Protocol.Secure(Protocol.deriveKey(pass, a, b));
    }

    /** 协商失败时给用户看的短句。 */
    public static String why(Throwable t) {
        String m = t == null ? null : t.getMessage();
        if (m == null || m.length() == 0) return "加密协商失败";
        return m;
    }
}
