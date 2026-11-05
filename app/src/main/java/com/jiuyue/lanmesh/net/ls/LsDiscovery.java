package com.jiuyue.lanmesh.net.ls;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * LocalSend 发现层：加入 224.0.0.167:53317 多播组，收别人的 announce。
 *
 * 官方实现「只发不应答」，收到 announce 后用 HTTP POST /api/localsend/v2/register
 * 回给对方（那步在 LsClient 侧，这里只收包与维护设备表）。
 *
 * 端口复用的关键：MulticastSocket 构造时就 bind 了，事后再 setReuseAddress 无效，
 * 必须 new MulticastSocket(null) → setReuseAddress(true) → bind()。否则本机装了
 * 官方 LocalSend 时直接 Address already in use。
 */
public class LsDiscovery {

    private static final String TAG = "LanMeshLs";

    public static final String GROUP = "224.0.0.167";
    public static final int PORT = 53317;
    /** 官方 Flutter 侧没有固定 TTL，这里取 90 秒 */
    public static final long TTL_MS = 90_000L;

    public interface Listener {
        void onDevicesChanged();

        /** 有人在问"谁在线"，需要回一个 announce。 */
        void onAnnounceWanted();
    }

    private final Context ctx;
    private final Listener listener;
    private volatile MulticastSocket socket;
    private volatile WifiManager.MulticastLock lock;
    private volatile Thread thread;
    private volatile boolean running;
    private volatile int retryCount;
    /** 每次 open 递增，防止 stop/start 与重试线程交叠时开出两个 socket */
    private volatile int generation;
    private final List<LsDevice> devices = new ArrayList<LsDevice>();

    public LsDiscovery(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        retryCount = 0;
        startRecvThread();
    }

    private void startRecvThread() {
        final int gen = ++generation;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                open(gen);
            }
        }, "lanmesh-ls-open");
        t.setDaemon(true);
        t.start();
    }

    private void open(int gen) {
        if (gen != generation || !running) return;
        MulticastSocket s = null;
        try {
            s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            s.setTimeToLive(1);
            s.setLoopbackMode(false); // false = 允许本机收到自己的包
            InetAddress group = InetAddress.getByName(GROUP);
            NetworkInterface nif = findInterface();
            if (nif != null) {
                try {
                    s.setNetworkInterface(nif);
                } catch (Throwable t) {
                    Log.w(TAG, "setNetworkInterface failed", t);
                }
                s.joinGroup(new InetSocketAddress(group, PORT), nif);
            } else {
                s.joinGroup(group);
            }
            if (gen != generation || !running) {
                closeQuietly(s);
                return;
            }
            socket = s;
            acquireLock();
            retryCount = 0;
            Log.i(TAG, "localSend discovery on " + GROUP + ":" + PORT);

            Thread rt = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "lanmesh-ls-recv");
            rt.setDaemon(true);
            thread = rt;
            rt.start();
        } catch (Throwable t) {
            // 失败时必须关掉已经建好的 socket 并放掉锁，否则端口和 WifiLock 一起泄漏
            closeQuietly(s);
            releaseLock();
            if (gen != generation || !running) return;
            Log.w(TAG, "cannot open multicast", t);
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (retryCount >= 3) {
            Log.w(TAG, "give up joining multicast after 3 attempts");
            running = false;
            return;
        }
        retryCount++;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (Throwable ignored) {
                }
                if (running) startRecvThread();
            }
        }, "lanmesh-ls-retry").start();
    }

    private void loop() {
        byte[] buf = new byte[8192];
        while (running) {
            try {
                MulticastSocket s = socket;
                if (s == null) break;
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                if (p.getLength() <= 0) continue;
                String json = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                if (!json.startsWith("{")) continue;
                JSONObject o = new JSONObject(json);
                String ip = p.getAddress() == null ? null : p.getAddress().getHostAddress();
                if (ip == null) continue;

                if (!o.has("fingerprint")) {
                    if (listener != null) listener.onAnnounceWanted();
                    continue;
                }
                LsDevice d = LsDevice.parse(o, ip);
                if (d == null) continue;
                if (isSelf(d)) continue;
                merge(d);
            } catch (Throwable t) {
                if (!running) break;
                String m = t.getMessage();
                if (m != null && m.toLowerCase(Locale.US).contains("socket closed")) break;
                Log.w(TAG, "receive failed", t);
            }
        }
    }

    private void merge(LsDevice d) {
        boolean changed = false;
        synchronized (devices) {
            LsDevice exist = null;
            for (LsDevice e : devices) {
                if (e.fingerprint.equals(d.fingerprint)) {
                    exist = e;
                    break;
                }
            }
            if (exist == null) {
                devices.add(d);
                changed = true;
            } else {
                // 逐字段比较必须空安全：announce 里 protocol/alias 可能缺省
                if (!eq(d.host, exist.host) || d.port != exist.port
                        || !eq(d.protocol, exist.protocol) || !eq(d.alias, exist.alias)) {
                    changed = true;
                }
                exist.applyFrom(d);
            }
            // 顺手清掉长期不出现的设备，避免按见过的 fingerprint 无界增长
            long now = System.currentTimeMillis();
            Iterator<LsDevice> it = devices.iterator();
            while (it.hasNext()) {
                LsDevice e = it.next();
                if (e.expired(now, TTL_MS * 4) && !e.fingerprint.equals(d.fingerprint)) {
                    it.remove();
                }
            }
        }
        if (changed && listener != null) listener.onDevicesChanged();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean isSelf(LsDevice d) {
        return LsIdentity.fingerprint(ctx).equalsIgnoreCase(d.fingerprint);
    }

    public LsDevice byFingerprint(String fp) {
        if (fp == null) return null;
        long now = System.currentTimeMillis();
        synchronized (devices) {
            for (LsDevice d : devices) {
                if (d.fingerprint.equalsIgnoreCase(fp)) {
                    // 过期设备不返回：IP 可能已经变了，发过去只会超时
                    return d.expired(now, TTL_MS) ? null : d;
                }
            }
        }
        return null;
    }

    public List<LsDevice> snapshot() {
        long now = System.currentTimeMillis();
        List<LsDevice> out = new ArrayList<LsDevice>();
        synchronized (devices) {
            for (LsDevice d : devices) {
                if (!d.expired(now, TTL_MS)) out.add(d);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** 广播一次"我在这儿"：官方节奏是 3 个包，间隔 100/500/2000ms。 */
    public void announce() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long[] delays = new long[]{0, 100, 500, 2000};
                byte[] payload;
                try {
                    payload = LsIdentity.announceJson(ctx).toString().getBytes("UTF-8");
                } catch (Throwable t) {
                    return;
                }
                for (int i = 0; i < delays.length && running; i++) {
                    if (delays[i] > 0) {
                        try {
                            Thread.sleep(delays[i]);
                        } catch (Throwable ignored) {
                        }
                    }
                    sendPacket(payload);
                }
            }
        }, "lanmesh-ls-announce").start();
    }

    private void sendPacket(byte[] payload) {
        MulticastSocket s = socket;
        if (s == null) return;
        try {
            InetAddress group = InetAddress.getByName(GROUP);
            DatagramPacket p = new DatagramPacket(payload, payload.length, group, PORT);
            s.send(p);
        } catch (Throwable t) {
            Log.w(TAG, "announce failed", t);
        }
    }

    public synchronized void stop() {
        running = false;
        generation++;
        closeQuietly(socket);
        socket = null;
        releaseLock();
        synchronized (devices) {
            devices.clear();
        }
    }

    // ---------------- 资源 ----------------

    private void acquireLock() {
        try {
            if (lock != null && lock.isHeld()) return;
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("lanmesh-ls");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseLock() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(MulticastSocket s) {
        try {
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }

    private NetworkInterface findInterface() {
        try {
            java.util.Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface nif = all.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                java.util.Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String name = nif.getName();
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                            return nif;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
