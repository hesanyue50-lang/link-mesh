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
        loadRemembered();
    }

    // ---------------- 历史设备（内存里记住上一台，重启后立刻可见） ----------------

    private static final int MAX_REMEMBERED = 30;
    private long lastPersistAt;

    private void loadRemembered() {
        String json = prefs.rememberedPeers();
        if (json == null || json.length() == 0) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", "");
                String host = o.optString("host", "");
                int port = o.optInt("port", 0);
                if (id.length() == 0 || host.length() == 0 || port <= 0) continue;
                if (id.equals(selfId)) continue;
                Peer p = new Peer(id, o.optString("name", ""), host, port, Peer.SRC_MEMORY);
                p.remembered = true;
                p.lastSeen = o.optLong("seen", 0);
                p.trusted = prefs.isTrusted(id);
                peers.put(id, p);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 落盘节流：发现层每秒可能 upsert 好几次，只在地址变化时写，且 5 秒最多一次。 */
    private void persistLater() {
        long now = System.currentTimeMillis();
        if (now - lastPersistAt < 5000) return;
        lastPersistAt = now;
        persistNow();
    }

    public synchronized void persistNow() {
        try {
            List<Peer> all = new ArrayList<Peer>(peers.values());
            // 只留最近活跃的 MAX_REMEMBERED 台，避免长期使用后无限增长
            Collections.sort(all, new Comparator<Peer>() {
                @Override
                public int compare(Peer a, Peer b) {
                    if (a.lastSeen == b.lastSeen) return 0;
                    return a.lastSeen > b.lastSeen ? -1 : 1;
                }
            });
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int i = 0; i < all.size() && i < MAX_REMEMBERED; i++) {
                Peer p = all.get(i);
                if (p.deviceId == null || p.host == null || p.port <= 0) continue;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", p.deviceId);
                o.put("name", p.nickname == null ? "" : p.nickname);
                o.put("host", p.host);
                o.put("port", p.port);
                o.put("seen", p.lastSeen);
                arr.put(o);
            }
            prefs.setRememberedPeers(arr.toString());
        } catch (Throwable ignored) {
        }
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
            p.remembered = true;
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
                p.remembered = true;
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
        if (changed) persistLater();
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
            // 记住的设备过期后保留为"离线"，直接从列表消失会让人以为丢数据
            if (p.isExpired(now) && !p.remembered) dead.add(p.deviceId);
        }
        for (String id : dead) peers.remove(id);
        return !dead.isEmpty();
    }

    public synchronized void clear() {
        peers.clear();
    }

    public synchronized void remove(String deviceId) {
        peers.remove(deviceId);
        persistNow();
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
