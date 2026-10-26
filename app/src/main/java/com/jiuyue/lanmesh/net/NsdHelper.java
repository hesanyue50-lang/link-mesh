package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;

import com.jiuyue.lanmesh.core.Const;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/**
 * 原生 NSD（DNS-SD/mDNS）发现，主路径。
 *
 * API 19 限制与降级：
 *  - NsdServiceInfo.setAttribute/getAttributes 是 API 21 才有的。API 21+ 用 attributes
 *    携带 deviceId/nickname/protoVersion；API 19 只能把短 ID 塞进服务名
 *    （"lm-XXXXXXXX"），端口用 setPort，昵称靠随后的 TCP HELLO 握手补齐。
 *  - API 19 的 NsdManager 不允许并发 resolve（会回调 onResolveFailed，错误码
 *    FAILURE_ALREADY_ACTIVE），因此这里用单线程队列串行 resolve。
 *  - 部分国产 ROM 的 mDNS 实现会漏回调，因此 NSD 只作为主路径，5 秒无结果
 *    自动叠加组播兜底，不把发现能力押在单一通道上。
 */
public class NsdHelper {

    private static final String TAG = "LanMeshNsd";

    public interface Listener {
        void onPeerFound(String host, int port, Map<String, String> attrs);

        void onError(String message);
    }

    private final Context ctx;
    private final Listener listener;
    private final String serviceName;
    private final int port;
    private final String deviceId;
    private final String nickname;

    private NsdManager nsd;
    private NsdManager.RegistrationListener regListener;
    private NsdManager.DiscoveryListener discListener;

    private final Queue<NsdServiceInfo> resolveQueue = new LinkedList<NsdServiceInfo>();
    private boolean resolving = false;
    private volatile boolean running = false;

    public NsdHelper(Context ctx, String deviceId, String nickname, int port, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.deviceId = deviceId;
        this.nickname = nickname;
        this.port = port;
        this.listener = l;
        // 服务名必须符合 DNS label（只允许字母数字和连字符），因此不能放中文昵称
        this.serviceName = "lm-" + com.jiuyue.lanmesh.core.Util.shortId(deviceId);
    }

    public String serviceName() {
        return serviceName;
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) {
                listener.onError("系统没有 NSD 服务");
                return;
            }
            register();
            discover();
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
            listener.onError("NSD 启动失败: " + t.getClass().getSimpleName());
        }
    }

    private NsdServiceInfo buildInfo() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(Const.SERVICE_TYPE);
        info.setPort(port);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                info.setAttribute("deviceId", deviceId);
                info.setAttribute("nickname", nickname == null ? "" : nickname);
                info.setAttribute("protoVersion", String.valueOf(Const.PROTO_VERSION));
                info.setAttribute("port", String.valueOf(port));
            } catch (Throwable t) {
                Log.w(TAG, "setAttribute failed", t);
            }
        }
        return info;
    }

    private void register() {
        NsdServiceInfo info = buildInfo();
        regListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "registered as " + serviceInfo.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "register failed: " + errorCode);
                // 3 = FAILURE_ALREADY_ACTIVE：刚 unregister 完立刻 register 会撞上，
                // 延迟 500ms 重试一次，否则刷新后别人永远搜不到本机
                if (errorCode == 3 || errorCode == 1) {
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (!running) return;
                                    try {
                                        nsd.registerService(buildInfo(), NsdManager.PROTOCOL_DNS_SD, regListener);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }, 600);
                    return;
                }
                listener.onError("NSD 注册失败(" + errorCode + ")，使用组播兜底");
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener);
        } catch (Throwable t) {
            Log.w(TAG, "registerService throw", t);
        }
    }

    private void discover() {
        discListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "startDiscovery failed: " + errorCode);
                listener.onError("NSD 发现启动失败(" + errorCode + ")");
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (!running) return; // stop() 之后回调仍会到达，别把已清空的列表又填回去
                String t = info == null ? null : info.getServiceType();
                if (t == null) return;
                t = t.toLowerCase(java.util.Locale.US);
                if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
                // 部分 ROM 的 serviceType 带尾点或大小写差异，严格 equals 会把邻居全过滤掉
                if (!t.startsWith(Const.SERVICE_TYPE.toLowerCase(java.util.Locale.US))) return;
                if (serviceName.equals(info.getServiceName())) return; // 自己
                enqueueResolve(info);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                // 不在这里直接删除：PeerRegistry 统一按 30 秒过期处理，
                // 避免 mDNS 抖动导致列表闪烁。
            }
        };
        try {
            nsd.discoverServices(Const.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener);
        } catch (Throwable t) {
            Log.w(TAG, "discoverServices throw", t);
        }
    }

    private String resolvingName;

    private void enqueueResolve(NsdServiceInfo info) {
        synchronized (resolveQueue) {
            // 正在解析的那台也要去重：mDNS 会周期性重复上报，
            // 否则同一个服务会被反复入队，把新设备挤到队尾
            if (info.getServiceName() != null
                    && info.getServiceName().equals(resolvingName)) return;
            // 同一个服务名（同一设备）可能被多次上报，队列内去重
            for (NsdServiceInfo q : resolveQueue) {
                if (q.getServiceName().equals(info.getServiceName())) return;
            }
            resolveQueue.add(info);
            if (resolving) return;
            resolving = true;
        }
        resolveNext();
    }

    private void resolveNext() {
        final NsdServiceInfo info;
        synchronized (resolveQueue) {
            info = resolveQueue.poll();
            resolvingName = info == null ? null : info.getServiceName();
            if (info == null) {
                resolving = false;
                return;
            }
        }
        NsdManager.ResolveListener rl = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "resolve failed " + serviceInfo.getServiceName() + ": " + errorCode);
                synchronized (resolveQueue) {
                    resolving = false;
                }
                // 间隔 300ms 继续下一个，避免 API19 上 FAILURE_ALREADY_ACTIVE
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        resolveNext();
                    }
                }, 300);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                try {
                    if (!running) return; // 已 stop：不再投递 peer（finally 里仍会推进队列）
                    String host = null;
                    // 注意：不要用 getHostAddresses()。它在 API 21 返回 InetAddress[]，
                    // API 34 改成 List<InetAddress>，compileSdk 36 编译出的字节码在
                    // API 21-33 设备上会 checkcast 失败。getHost() 从 API 16 起稳定可用。
                    InetAddress a = serviceInfo.getHost();
                    if (a != null) host = a.getHostAddress();

                    Map<String, String> attrs = new HashMap<String, String>();
                    if (Build.VERSION.SDK_INT >= 21) {
                        try {
                            Map<String, byte[]> raw = serviceInfo.getAttributes();
                            if (raw != null) {
                                for (Map.Entry<String, byte[]> e : raw.entrySet()) {
                                    try {
                                        attrs.put(e.getKey(), new String(e.getValue(), "UTF-8"));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!attrs.containsKey("deviceId")) {
                        // API 19 降级：服务名 lm-XXXXXXXX 只带短 ID；
                        // 真正 deviceId 由 TCP 握手给出，这里先挂一个临时键。
                        String sn = serviceInfo.getServiceName();
                        if (sn != null && sn.startsWith("lm-")) {
                            attrs.put("shortId", sn.substring(3));
                        }
                    }
                    int p = serviceInfo.getPort();
                    if (p <= 0) {
                        try {
                            p = Integer.parseInt(attrs.get("port"));
                        } catch (Throwable ignored) {
                        }
                    }
                    if (host != null && p > 0) {
                        listener.onPeerFound(host, p, attrs);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "handle resolved failed", t);
                } finally {
                    synchronized (resolveQueue) {
                        resolving = false;
                        resolvingName = null;
                    }
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            resolveNext();
                        }
                    }, 120);
                }
            }
        };
        try {
            nsd.resolveService(info, rl);
        } catch (Throwable t) {
            Log.w(TAG, "resolveService throw", t);
            synchronized (resolveQueue) {
                resolving = false;
            }
        }
    }

    public void stop() {
        running = false;
        if (nsd == null) return;
        if (discListener != null) {
            try {
                nsd.stopServiceDiscovery(discListener);
            } catch (Throwable ignored) {
            }
            discListener = null;
        }
        if (regListener != null) {
            try {
                nsd.unregisterService(regListener);
            } catch (Throwable ignored) {
            }
            regListener = null;
        }
        synchronized (resolveQueue) {
            resolveQueue.clear();
        }
    }
}
