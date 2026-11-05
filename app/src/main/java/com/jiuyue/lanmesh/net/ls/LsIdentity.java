package com.jiuyue.lanmesh.net.ls;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 本机在 LocalSend 网络里的身份：指纹、announce 报文、自签证书。
 *
 * LocalSend 官方客户端默认用 HTTPS + mTLS（要求客户端证书），
 * Android 没有签发证书的 API，也没有 bcpkix，所以这里用纯 DER 拼一个自签证书。
 * 证书只在本地生成、本地用，不会外发。
 */
public final class LsIdentity {

    private static final String TAG = "LanMeshLs";
    private static final String SP = "lanmesh_ls";
    private static final String K_FP = "fingerprint";
    private static final String CERT_FILE = "ls_client.p12";
    private static final String CERT_PASS = "localsend";

    private static KeyStore clientStore;
    private static String fingerprintHttps;

    private LsIdentity() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** HTTP 模式下的设备指纹：随机 32 字节，持久化，变了会导致对方当成新设备。 */
    public static String fingerprint(Context ctx) {
        SharedPreferences p = sp(ctx);
        String v = p.getString(K_FP, null);
        if (v != null && v.length() >= 32) return v;
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        v = sb.toString();
        p.edit().putString(K_FP, v).apply();
        return v;
    }

    public static JSONObject announceJson(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        o.put("alias", com.jiuyue.lanmesh.core.AppCore.get(ctx).prefs.nickname());
        o.put("version", LsConst.VERSION);
        o.put("deviceModel", android.os.Build.MODEL == null ? "" : android.os.Build.MODEL);
        o.put("deviceType", "mobile");
        o.put("fingerprint", fingerprint(ctx));
        o.put("port", LsConst.HTTP_PORT);
        o.put("protocol", "http");
        o.put("download", false);
        o.put("announce", true);
        return o;
    }

    /** prepare-upload 里要带的 info（不含 announce 字段）。 */
    public static JSONObject infoJson(Context ctx) throws Exception {
        JSONObject o = announceJson(ctx);
        o.remove("announce");
        return o;
    }

    /**
     * HTTPS 客户端要用的密钥库（LocalSend 服务端强制 mTLS）。
     * 首次调用生成 RSA-2048 + 自签证书，之后从文件读。
     */
    public static synchronized KeyStore clientKeyStore(Context ctx) {
        if (clientStore != null) return clientStore;
        File f = new File(ctx.getFilesDir(), CERT_FILE);
        if (f.isFile()) {
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                FileInputStream in = new FileInputStream(f);
                ks.load(in, CERT_PASS.toCharArray());
                in.close();
                clientStore = ks;
                return ks;
            } catch (Throwable t) {
                Log.w(TAG, "load cert failed, regenerating", t);
            }
        }
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            byte[] der = buildCert(kp, "LocalSend User", 3650);
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(der)) sb.append(String.format("%02X", b));
            fingerprintHttps = sb.toString();

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("ls", kp.getPrivate(), CERT_PASS.toCharArray(),
                    new java.security.cert.Certificate[]{cert});
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ks.store(bo, CERT_PASS.toCharArray());
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f);
                out.write(bo.toByteArray());
                out.flush();
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            clientStore = ks;
            Log.i(TAG, "client cert generated " + der.length + "B");
            return ks;
        } catch (Throwable t) {
            Log.w(TAG, "generate cert failed", t);
            return null;
        }
    }

    // ---------------- DER 编码（只要能用，不求通用） ----------------

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = v.length;
        if (n < 0x80) {
            o.write(n);
        } else if (n < 0x100) {
            o.write(0x81);
            o.write(n);
        } else if (n < 0x10000) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write(n >> 8);
            o.write(n);
        }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) if (p != null) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... p) {
        return tlv(0x30, cat(p));
    }

    private static byte[] set(byte[]... p) {
        return tlv(0x31, cat(p));
    }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[8];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>= 7;
            } while (v > 0);
            for (int k = n - 1; k >= 0; k--) {
                o.write(tmp[k] | (k == 0 ? 0 : 0x80));
            }
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] utc(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes());
    }

    private static byte[] buildCert(KeyPair kp, String cn, int days) throws Exception {
        byte[] sigAlg = seq(oid("1.2.840.113549.1.1.11"), tlv(0x05, new byte[0]));
        byte[] name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, cn.getBytes("UTF-8")))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utc(new Date(now - 86400000L)),
                utc(new Date(now + (long) days * 86400000L)));
        byte[] spki = kp.getPublic().getEncoded();

        byte[] tbs = seq(
                tlv(0xA0, tlv(0x02, BigInteger.valueOf(2).toByteArray())),
                tlv(0x02, new BigInteger(64, new SecureRandom()).abs().toByteArray()),
                sigAlg,
                name,
                validity,
                name,
                spki);

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bit = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bit, 1, sig.length);
        return seq(tbs, sigAlg, tlv(0x03, bit));
    }
}
