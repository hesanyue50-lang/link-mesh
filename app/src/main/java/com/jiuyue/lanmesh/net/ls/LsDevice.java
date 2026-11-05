package com.jiuyue.lanmesh.net.ls;

import org.json.JSONObject;

/**
 * 一台通过 LocalSend 协议发现的设备。
 *
 * 字段命名对齐 LocalSend v2 announce（camelCase），见官方
 * packages/core/src/model/discovery.rs。我们只保留实际用得到的那几个。
 */
public class LsDevice {

    public final String fingerprint; // 设备唯一标识（HTTP 模式下是随机串，HTTPS 下是证书指纹）
    public volatile String alias;    // 设备名
    public volatile String host;     // 来源 IP（UDP 包的 source address）
    public volatile int port;        // 对方 HTTP(S) 端口
    public volatile String protocol; // "http" | "https"
    public volatile String deviceType;
    public volatile String version;
    public volatile boolean download; // 对方是否开放 download API
    public volatile long lastSeen;

    public LsDevice(String fingerprint, String host) {
        this.fingerprint = fingerprint;
        this.host = host;
        this.lastSeen = System.currentTimeMillis();
    }

    public static LsDevice parse(JSONObject o, String sourceIp) {
        if (o == null) return null;
        String fp = o.optString("fingerprint", "");
        if (fp.length() == 0) return null;
        LsDevice d = new LsDevice(fp, sourceIp);
        d.apply(o);
        return d;
    }

    /** announce 与 /register 响应的字段一致，统一在这里更新。 */
    public void apply(JSONObject o) {
        if (o == null) return;
        alias = o.optString("alias", alias);
        int p = o.optInt("port", port);
        if (p > 0) port = p;
        String proto = o.optString("protocol", protocol);
        if (proto != null && proto.length() > 0) protocol = proto.toLowerCase();
        deviceType = o.optString("deviceType", deviceType);
        version = o.optString("version", version);
        if (o.has("download")) download = o.optBoolean("download", false);
        lastSeen = System.currentTimeMillis();
    }

    /** 用另一份解析结果覆盖可变字段（合并同一设备的多条 announce）。 */
    public void applyFrom(LsDevice o) {
        if (o == null) return;
        if (o.alias != null && o.alias.length() > 0) alias = o.alias;
        if (o.port > 0) port = o.port;
        if (o.protocol != null && o.protocol.length() > 0) protocol = o.protocol;
        if (o.deviceType != null) deviceType = o.deviceType;
        if (o.version != null) version = o.version;
        if (o.host != null) host = o.host;
        download = o.download;
        lastSeen = System.currentTimeMillis();
    }

    public boolean https() {
        return "https".equals(protocol);
    }

    public String baseUrl() {
        return (https() ? "https" : "http") + "://" + host + ":" + port;
    }

    public String displayName() {
        if (alias == null || alias.trim().length() == 0) return host;
        return alias;
    }

    public boolean expired(long now, long ttlMs) {
        return now - lastSeen > ttlMs;
    }
}
