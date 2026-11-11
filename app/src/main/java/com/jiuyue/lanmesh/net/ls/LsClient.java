package com.jiuyue.lanmesh.net.ls;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 以 LocalSend 客户端身份把文件推给对方（官方 App 或同协议的其他实现）。
 *
 * 流程严格按官方 v2 API：
 *   POST /api/localsend/v2/prepare-upload            -> {sessionId, files:{fileId: token}}
 *   POST /api/localsend/v2/upload?sessionId=&fileId=&token=   body = 原始字节
 *
 * 官方默认 HTTPS + 自签证书 + 强制客户端证书，所以这里：
 *  - 客户端证书用 LsIdentity 生成的那张
 *  - 信任链一律接受（本地网对端自签，没有 CA 可校验）
 *  - 主机关名校验关掉（官方证书只有 CN，没有 SAN）
 */
public final class LsClient {

    private static final String TAG = "LanMeshLs";
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 60000;

    public interface Progress {
        void onProgress(long sent, long total);
    }

    public static class Result {
        public boolean ok;
        public String error;

        static Result fail(String why) {
            Result r = new Result();
            r.ok = false;
            r.error = why;
            return r;
        }
    }

    private LsClient() {
    }

    /** 发一个文件：先报元数据，对方同意后再传字节。 */
    public static Result sendFile(Context ctx, LsDevice dev, String fileName, String mime,
                                  long size, InputStream in, Progress cb) {
        String fileId = "file1";
        if (size <= 0) {
            // prepare-upload 必须报准确大小，官方服务端按它校验总字节数
            return Result.fail("文件大小为 0 或未知，无法用 LocalSend 发送");
        }
        HttpURLConnection cur = null;
        try {
            JSONObject info = LsIdentity.infoJson(ctx);
            JSONObject f = new JSONObject();
            f.put("id", fileId);
            f.put("fileName", fileName);
            f.put("size", size);
            f.put("fileType", mime == null || mime.length() == 0
                    ? "application/octet-stream" : mime);

            JSONObject files = new JSONObject();
            files.put(fileId, f);

            JSONObject body = new JSONObject();
            body.put("info", info);
            body.put("files", files);

            HttpURLConnection c = open(ctx, dev, LsConst.API + "/prepare-upload", "POST");
            cur = c;
            c.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes("UTF-8");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code == 204) return Result.fail("对方没有接收这个文件");
            if (code == 401) return Result.fail("对方设置了 PIN，需要配对码");
            if (code == 403) return Result.fail("对方拒绝接收");
            if (code == 409) return Result.fail("对方正忙（已有进行中的会话）");
            if (code != 200) return Result.fail("准备上传失败 HTTP " + code + readBrief(c));

            JSONObject r = new JSONObject(readAll(c.getInputStream()));
            String sessionId = r.optString("sessionId", "");
            JSONObject tokens = r.optJSONObject("files");
            String token = tokens == null ? "" : tokens.optString(fileId, "");
            if (sessionId.length() == 0 || token.length() == 0) {
                return Result.fail("对方应答缺少会话或令牌");
            }

            String q = LsConst.API + "/upload?sessionId=" + enc(sessionId)
                    + "&fileId=" + enc(fileId) + "&token=" + enc(token);
            HttpURLConnection up = open(ctx, dev, q, "POST");
            up.setDoOutput(true);
            up.setFixedLengthStreamingMode(size);
            up.setRequestProperty("Content-Type", "application/octet-stream");

            cur = up;
            OutputStream os2 = up.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            long sent = 0;
            long lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os2.write(buf, 0, n);
                sent += n;
                long now = System.currentTimeMillis();
                if (cb != null && (now - lastReport > 120 || sent >= size)) {
                    lastReport = now;
                    cb.onProgress(sent, size);
                }
            }
            os2.flush();
            os2.close();
            // upload 不要求 Content-Length，但我们给了；对方按流读到 EOF 校验总长度
            if (sent != size) {
                return Result.fail("文件读取不完整（" + sent + "/" + size + " 字节）");
            }

            int uc = up.getResponseCode();
            if (uc == 200) {
                Result ok = new Result();
                ok.ok = true;
                return ok;
            }
            if (uc == 422) return Result.fail("对方校验和不匹配");
            if (uc == 403) return Result.fail("令牌或来源地址不符");
            return Result.fail("上传失败 HTTP " + uc + " " + readBrief(up));
        } catch (Throwable t) {
            Log.w(TAG, "send failed", t);
            String m = t.getMessage();
            return Result.fail(m == null ? t.getClass().getSimpleName() : m);
        } finally {
            // 不主动断开的话，失败分支的 socket 只能等连接池回收
            try {
                if (cur != null) cur.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- HTTP / TLS ----------------

    private static HttpURLConnection open(Context ctx, LsDevice dev, String pathAndQuery, String method)
            throws Exception {
        URL url = new URL(dev.baseUrl() + pathAndQuery);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setUseCaches(false);
        if ("https".equals(url.getProtocol()) && c instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) c;
            SSLSocketFactory sf = socketFactory(ctx);
            if (sf != null) https.setSSLSocketFactory(sf);
            https.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        }
        return c;
    }

    private static SSLSocketFactory socketFactory(Context ctx) {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};

            javax.net.ssl.KeyManager[] kms = null;
            KeyStore ks = LsIdentity.clientKeyStore(ctx);
            if (ks != null) {
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, "localsend".toCharArray());
                kms = kmf.getKeyManagers();
            }
            // Android 4.4（API 19）上 "TLS" 默认只协商 SSLv3/TLSv1，
            // 而官方 LocalSend 最低要求 TLS 1.2，结果是握手直接失败。
            SSLContext sc = android.os.Build.VERSION.SDK_INT >= 20
                    ? SSLContext.getInstance("TLS")
                    : SSLContext.getInstance("TLSv1.2");
            sc.init(kms, trustAll, new SecureRandom());
            return sc.getSocketFactory();
        } catch (Throwable t) {
            Log.w(TAG, "ssl init failed", t);
            return null;
        }
    }

    private static String enc(String s) throws Exception {
        return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() > 256 * 1024) break;
        }
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String readBrief(HttpURLConnection c) {
        try {
            InputStream es = c.getErrorStream();
            if (es == null) return "";
            String s = readAll(es);
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (Throwable t) {
            return "";
        }
    }
}
