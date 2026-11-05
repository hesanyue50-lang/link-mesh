package com.jiuyue.lanmesh.model;

/**
 * 一个局域网邻居。
 *
 * 同一 deviceId 可能同时被 NSD、组播、手动添加三条路径发现，
 * 统一收敛到 PeerRegistry 后只保留一个对象，只更新来源与最近时间。
 */
public class Peer {

    public static final String SRC_NSD = "nsd";
    public static final String SRC_MULTICAST = "multicast";
    public static final String SRC_MANUAL = "manual";
    /** 来自本地历史记录（本次未发现到），仅用于"上次连过的设备"列表项。 */
    public static final String SRC_MEMORY = "memory";
    /** 通过 LocalSend 协议发现的对端（官方客户端，不跑我们的二进制协议）。 */
    public static final String SRC_LOCALSEND = "localsend";
    public static final String LS_PREFIX = "ls-";

    public final String deviceId;
    public volatile String nickname;
    public volatile String host;
    public volatile int port;
    /** 最近一次被听到/通信成功的时间戳（ms）。 */
    public volatile long lastSeen;
    /** 最近一次发现来源，仅用于诊断展示。 */
    public volatile String source;
    /** 是否在本地信任列表中。 */
    public volatile boolean trusted;
    /** 由发现层提供的协议版本，握手时会以真实值为准。 */
    public volatile int protoVersion = 1;
    /** 是否从本地历史恢复的条目。记住的设备即使过期也不从列表移除。 */
    public volatile boolean remembered;

    /** 是否是 LocalSend 对端（走 HTTP API，不走我们的 TCP 帧协议）。 */
    public boolean isLocalSend() {
        return deviceId != null && deviceId.startsWith(LS_PREFIX);
    }

    public String lsFingerprint() {
        return isLocalSend() ? deviceId.substring(LS_PREFIX.length()) : null;
    }

    public Peer(String deviceId, String nickname, String host, int port, String source) {
        this.deviceId = deviceId;
        this.nickname = nickname;
        this.host = host;
        this.port = port;
        this.source = source;
        this.lastSeen = System.currentTimeMillis();
    }

    public String shortId() {
        return com.jiuyue.lanmesh.core.Util.shortId(deviceId);
    }

    public String displayName() {
        if (nickname == null || nickname.trim().length() == 0) return shortId();
        return nickname;
    }

    public boolean isExpired(long now) {
        return now - lastSeen > com.jiuyue.lanmesh.core.Const.PEER_EXPIRE_MS;
    }

    /** 30 秒内有任何发现/通信迹象即视为"直连可用"。 */
    public boolean isOnline(long now) {
        return now - lastSeen <= com.jiuyue.lanmesh.core.Const.PEER_EXPIRE_MS;
    }

    public String address() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Peer)) return false;
        return deviceId.equals(((Peer) o).deviceId);
    }

    @Override
    public int hashCode() {
        return deviceId.hashCode();
    }
}
