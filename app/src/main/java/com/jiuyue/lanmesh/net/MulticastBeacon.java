package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.jiuyue.lanmesh.core.Const;
import com.jiuyue.lanmesh.core.Prefs;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Random;

/**
 * UDP 组播兜底（第二层）。
 *
 * 触发条件：NSD 启动后 5 秒仍无在线邻居（见 NetService 的 fallback 定时器）。
 * 一旦启动就持续运行，因为用户可能在 NSD 不可用的网络里来回切换。
 *
 * 为什么需要 MulticastLock：
 *  Android 默认过滤非本机单播包以省电，不拿 MulticastLock 时 MulticastSocket
 *  收不到任何组播包（这是"代码看起来对但收不到"的第一大坑）。
 *
 * 广播内容只含最小信息（deviceId/昵称/TCP 端口/协议版本），不含文件、消息、
 * 历史记录；首次连接仍需用户在信任卡片上确认。
 */
public class MulticastBeacon {

    private static final String TAG = "LanMeshMcast";

    public interface Listener {
        void onPeer(String deviceId, String nickname, String host, int tcpPort);
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Listener listener;
    private final int tcpPort;
    private final Random random = new Random();

    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;
    private Thread receiver;
    private HandlerThread timerThread;
    private Handler timer;
    private volatile boolean running = false;
    private InetAddress group;

    public MulticastBeacon(Context ctx, Prefs prefs, int tcpPort, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.tcpPort = tcpPort;
        this.listener = l;
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("lanmesh-mcast");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "acquire multicast lock failed", t);
        }

        try {
            group = InetAddress.getByName(Const.MULTICAST_GROUP);
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(Const.MULTICAST_PORT));
            socket.setTimeToLive(4);
            socket.setSoTimeout(1000);
            NetworkInterface nif = findWifiInterface();
            if (nif != null) {
                socket.joinGroup(new InetSocketAddress(group, Const.MULTICAST_PORT), nif);
                Log.i(TAG, "joined multicast on " + nif.getName());
            } else {
                socket.joinGroup(group);
                Log.i(TAG, "joined multicast on default interface");
            }
        } catch (Throwable t) {
            Log.w(TAG, "multicast init failed", t);
            running = false;
            release();
            return;
        }

        receiver = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "lanmesh-mcast-rx");
        receiver.setDaemon(true);
        receiver.start();

        timerThread = new HandlerThread("lanmesh-mcast-tx");
        timerThread.start();
        timer = new Handler(timerThread.getLooper());
        scheduleNext(200); // 启动后尽快发一次
    }

    private void scheduleNext(long delay) {
        if (!running || timer == null) return;
        timer.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendBeacon();
                // 5 秒周期 + 0-1500ms 抖动：多台设备同时开机时避免撞包
                scheduleNext(Const.MULTICAST_INTERVAL_MS + random.nextInt(Const.MULTICAST_JITTER_MS + 1));
            }
        }, delay);
    }

    private void sendBeacon() {
        if (!running) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "hello");
            o.put("deviceId", prefs.deviceId());
            o.put("nickname", prefs.nickname());
            o.put("port", tcpPort);
            o.put("protoVersion", Const.PROTO_VERSION);
            o.put("ts", System.currentTimeMillis());
            byte[] data = o.toString().getBytes("UTF-8");
            DatagramPacket p = new DatagramPacket(data, data.length, group, Const.MULTICAST_PORT);
            socket.send(p);
        } catch (Throwable t) {
            Log.w(TAG, "send beacon failed", t);
        }
    }

    private void sendAck(InetAddress to) {
        if (!running) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "ack");
            o.put("deviceId", prefs.deviceId());
            o.put("nickname", prefs.nickname());
            o.put("port", tcpPort);
            o.put("protoVersion", Const.PROTO_VERSION);
            byte[] data = o.toString().getBytes("UTF-8");
            DatagramPacket p = new DatagramPacket(data, data.length, to, Const.MULTICAST_PORT);
            socket.send(p);
        } catch (Throwable t) {
            Log.w(TAG, "send ack failed", t);
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                int len = p.getLength();
                if (len <= 0 || len > 1024) continue;
                String json = new String(p.getData(), p.getOffset(), len, "UTF-8");
                JSONObject o = new JSONObject(json);
                String type = o.optString("t", "");
                String deviceId = o.optString("deviceId", "");
                if (deviceId.length() == 0) continue;
                if (deviceId.equals(prefs.deviceId())) continue; // 自己的包
                int port = o.optInt("port", 0);
                if (port <= 0 || port > 65535) continue;
                String nickname = o.optString("nickname", "");
                String host = p.getAddress().getHostAddress();
                listener.onPeer(deviceId, nickname, host, port);

                // 收到广播后回单播确认，让双方都能立刻建表（双向发现更快）
                if ("hello".equals(type)) {
                    sendAck(p.getAddress());
                }
            } catch (java.net.SocketTimeoutException e) {
                // 正常：1 秒超时用来检查 running
            } catch (Throwable t) {
                if (running) Log.w(TAG, "receive failed", t);
            }
        }
    }

    private NetworkInterface findWifiInterface() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                if (name != null && name.startsWith("wlan")) return nif;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public void stop() {
        running = false;
        if (timerThread != null) {
            timerThread.quit();
            timerThread = null;
            timer = null;
        }
        release();
        if (receiver != null) {
            try {
                receiver.join(500);
            } catch (Throwable ignored) {
            }
            receiver = null;
        }
    }

    private void release() {
        if (socket != null) {
            try {
                if (group != null) socket.leaveGroup(group);
            } catch (Throwable ignored) {
            }
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
            socket = null;
        }
        if (lock != null) {
            try {
                if (lock.isHeld()) lock.release();
            } catch (Throwable ignored) {
            }
            lock = null;
        }
    }
}
