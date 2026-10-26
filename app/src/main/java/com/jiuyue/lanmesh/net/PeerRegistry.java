package com.jiuyue.lanmesh.net;

import com.jiuyue.lanmesh.core.Prefs;
import com.jiuyue.lanmesh.core.Util;
import com.jiuyue.lanmesh.model.Peer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邻居注册表：三条发现路径（NSD / 组播 / 手动）的统一收敛点。
 *
 * 去重规则：以 deviceId 为唯一键。同一设备通过 NSD 和组播各出现一次时，
 * 只保留一条记录，把来源记为最近一次命中的路径，并刷新 lastSeen。
 * 当 IP 变化（DHCP 续租、切换热点）时以最新上报的地址为准。
 */
public class PeerRegistry {

    private static final String PEER_SHORT_PREFIX = "short-";

    private final Map<String, Peer> peers = new LinkedHashMap<String, Peer>();
    private final Prefs prefs;
    private final String selfId;

    public PeerRegistry(Prefs prefs) {
        this.prefs = prefs;
        this.selfId = prefs.deviceId();
    }

    public synchronized boolean upsert(String deviceId, String nickname, String host, int port, String source) {
        if (deviceId == null || host == null || port <= 0) return false;
        if (deviceId.equals(selfId)) return false; // 忽略自己（组播会收到自己的包）

        // 短 ID 归一：API 19 上 NSD 拿不到 deviceId，会先用 "short-XXXX" 占位；
        // 握手拿到真实 deviceId 后如果不清掉，列表里会永久留一条连不上的"幽灵设备"
        if (deviceId.startsWith(PEER_SHORT_PREFIX)) {
            String sid = deviceId.substring(PEER_SHORT_PREFIX.length());
            for (Peer exist : peers.values()) {
                if (!exist.deviceId.startsWith(PEER_SHORT_PREFIX)
                        && Util.shortId(exist.deviceId).equalsIgnoreCase(sid)) {
                    return false; // 已经有真实条目，丢弃这条临时记录
                }
            }
        } else {
            peers.remove(PEER_SHORT_PREFIX + Util.shortId(deviceId));
        }

        Peer p = peers.get(deviceId);
        boolean changed = false;
        if (p == null) {
            p = new Peer(deviceId, nickname, host, port, source);
            p.trusted = prefs.isTrusted(deviceId);
            peers.put(deviceId, p);
            changed = true;
        } else {
            if (nickname != null && nickname.length() > 0 && !nickname.equals(p.nickname)) {
                p.nickname = nickname;
                changed = true;
            }
            if (!host.equals(p.host) || port != p.port) {
                p.host = host;
                p.port = port;
                changed = true;
            }
            p.lastSeen = System.currentTimeMillis();
            p.source = source;
            boolean trusted = prefs.isTrusted(deviceId);
            if (trusted != p.trusted) {
                p.trusted = trusted;
                changed = true;
            }
        }
        return changed;
    }

    public synchronized Peer get(String deviceId) {
        return peers.get(deviceId);
    }

    public synchronized void touch(String deviceId, String host, int port) {
        Peer p = peers.get(deviceId);
        if (p == null) {
            if (host != null && port > 0) {
                upsert(deviceId, null, host, port, Peer.SRC_MANUAL);
            }
            return;
        }
        p.lastSeen = System.currentTimeMillis();
        if (host != null) p.host = host;
        if (port > 0) p.port = port;
    }

    /** 移除超过 30 秒没有任何迹象的设备，返回是否发生变化。 */
    public synchronized boolean removeExpired() {
        long now = System.currentTimeMillis();
        List<String> dead = new ArrayList<String>();
        for (Peer p : peers.values()) {
            if (p.isExpired(now)) dead.add(p.deviceId);
        }
        for (String id : dead) peers.remove(id);
        return !dead.isEmpty();
    }

    public synchronized void clear() {
        peers.clear();
    }

    public synchronized void remove(String deviceId) {
        peers.remove(deviceId);
    }

    public synchronized List<Peer> list() {
        List<Peer> list = new ArrayList<Peer>(peers.values());
        final long now = System.currentTimeMillis();
        Collections.sort(list, new Comparator<Peer>() {
            @Override
            public int compare(Peer a, Peer b) {
                // 在线 > 离线；信任 > 未信任；再按最近活跃
                boolean ao = a.isOnline(now), bo = b.isOnline(now);
                if (ao != bo) return ao ? -1 : 1;
                if (a.trusted != b.trusted) return a.trusted ? -1 : 1;
                if (a.lastSeen == b.lastSeen) return 0;
                return a.lastSeen > b.lastSeen ? -1 : 1;
            }
        });
        return list;
    }

    public synchronized int size() {
        return peers.size();
    }

    /** 是否至少有一个"在线"的邻居（NSD 5 秒降级判定用）。 */
    public synchronized boolean hasOnlinePeer() {
        long now = System.currentTimeMillis();
        for (Peer p : peers.values()) {
            if (p.isOnline(now)) return true;
        }
        return false;
    }

    public String selfId() {
        return selfId;
    }

    public String selfShortId() {
        return Util.shortId(selfId);
    }
}
