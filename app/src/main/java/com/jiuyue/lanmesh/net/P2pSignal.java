package com.jiuyue.lanmesh.net;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 手动信令编解码。
 *
 * 为什么手动：需求明确不使用服务器信令（零云成本），因此 offer/answer 需要
 * 用户自己搬运。SDP + ICE 原始文本通常 3-8KB，直接复制体验很差，这里做
 * gzip + Base64(URL-safe) 压缩，一般能压到 1-2KB，方便复制粘贴，
 * 后续接二维码也只需要把同一字符串编码成 QR。
 */
public class P2pSignal {

    public static final String TYPE_OFFER = "offer";
    public static final String TYPE_ANSWER = "answer";

    /** 一条 ICE 候选，必须带 sdpMid / sdpMLineIndex，否则对端无法把它挂到正确的 m-line 上。 */
    public static class IceEntry {
        public final String candidate;
        public final String mid;
        public final int line;

        public IceEntry(String candidate, String mid, int line) {
            this.candidate = candidate;
            this.mid = mid == null ? "0" : mid;
            this.line = line;
        }
    }

    public String type;
    public String sdp;
    public List<IceEntry> ice = new ArrayList<IceEntry>();

    public P2pSignal() {
    }

    public P2pSignal(String type, String sdp) {
        this.type = type;
        this.sdp = sdp;
    }

    /**
     * 只保留真正有用的 ICE 候选：
     *  - 只留 host / srflx（relay 是 TURN，按需求不引入）；
     *  - 跳过 IPv6（体积大且多数移动网络不可用）；
     *  - 按 ip:port 去重；
     *  - 最多 4 个，srflx 优先（跨网打洞主要靠它）。
     *
     * 9 个候选会让二维码密度翻倍，而真正被用到的通常只有 2-3 个。
     */
    private List<IceEntry> compactIce() {
        // 只留 host / srflx；IPv4 最多 4 个（srflx 优先），IPv6 最多 2 个。
        // IPv6 必须保留：国内移动网络大量是 IPv6-only 或双栈，IPv6 直连可以绕过 NAT。
        List<IceEntry> srflx4 = new ArrayList<IceEntry>();
        List<IceEntry> host4 = new ArrayList<IceEntry>();
        List<IceEntry> v6 = new ArrayList<IceEntry>();
        Set<String> seen = new HashSet<String>();
        for (IceEntry e : ice) {
            if (e == null || e.candidate == null) continue;
            boolean isSrflx = e.candidate.contains(" typ srflx");
            boolean isHost = e.candidate.contains(" typ host");
            if (!isSrflx && !isHost) continue;
            String[] parts = e.candidate.trim().split(" ");
            if (parts.length < 6) continue;
            String ip = parts[4];
            String port = parts[5];
            String key = ip + ":" + port;
            if (seen.contains(key)) continue;
            seen.add(key);
            if (ip != null && ip.indexOf(':') >= 0) {
                if (v6.size() < 2) v6.add(e);
            } else if (isSrflx) {
                srflx4.add(e);
            } else {
                host4.add(e);
            }
        }
        List<IceEntry> out = new ArrayList<IceEntry>();
        for (IceEntry e : srflx4) {
            if (out.size() >= 4) break;
            out.add(e);
        }
        for (IceEntry e : host4) {
            if (out.size() >= 4) break;
            out.add(e);
        }
        for (IceEntry e : v6) {
            if (out.size() >= 6) break;
            out.add(e);
        }
        return out;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("t", type);
            o.put("sdp", sdp == null ? "" : sdp);
            JSONArray arr = new JSONArray();
            for (IceEntry e : compactIce()) {
                JSONObject item = new JSONObject();
                item.put("c", e.candidate);
                item.put("m", e.mid);
                item.put("i", e.line);
                arr.put(item);
            }
            o.put("ice", arr);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public String encode() {
        try {
            byte[] raw = toJson().getBytes("UTF-8");
            // raw deflate 比 gzip 少 18 字节头，二维码容量寸土寸金
            Deflater def = new Deflater(Deflater.BEST_COMPRESSION, true);
            def.setInput(raw);
            def.finish();
            byte[] buf = new byte[8192];
            int n = def.deflate(buf);
            def.end();
            if (n <= 0) return "";
            return Base64.encodeToString(buf, 0, n,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable t) {
            return "";
        }
    }

    public static P2pSignal decode(String code) {
        if (code == null) return null;
        String s = code.trim().replaceAll("\\s", "");
        try {
            byte[] raw = Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            Inflater inf = new Inflater(true);
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try {
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) break;
                    } else {
                        out.write(buf, 0, n);
                    }
                }
            } catch (DataFormatException dfe) {
                return null;
            } finally {
                inf.end();
            }
            JSONObject o = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            P2pSignal sig = new P2pSignal();
            sig.type = o.optString("t", "");
            sig.sdp = o.optString("sdp", "");
            JSONArray arr = o.optJSONArray("ice");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject) {
                        JSONObject j = (JSONObject) item;
                        String c = j.optString("c", "");
                        if (c.length() > 0) {
                            sig.ice.add(new IceEntry(c, j.optString("m", "0"), j.optInt("i", 0)));
                        }
                    } else if (item instanceof String) {
                        // 兼容旧格式（只有候选串）
                        String c = (String) item;
                        if (c.length() > 0) sig.ice.add(new IceEntry(c, "0", 0));
                    }
                }
            }
            return sig;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isValid() {
        return sdp != null && sdp.length() > 50 && (TYPE_OFFER.equals(type) || TYPE_ANSWER.equals(type));
    }
}
