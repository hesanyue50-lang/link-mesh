package com.jiuyue.lanmesh.net;

import android.app.Notification;
import android.widget.RemoteViews;
import android.graphics.BitmapFactory;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.content.pm.ServiceInfo;
import android.util.Log;

import com.jiuyue.lanmesh.App;
import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.AppCore;
import com.jiuyue.lanmesh.core.Bus;
import com.jiuyue.lanmesh.core.Const;
import com.jiuyue.lanmesh.core.Prefs;
import com.jiuyue.lanmesh.core.Util;
import com.jiuyue.lanmesh.model.ChatMessage;
import com.jiuyue.lanmesh.model.MessageStore;
import com.jiuyue.lanmesh.model.Peer;
import com.jiuyue.lanmesh.ui.MainActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 常驻前台服务：局域网发现 + TCP 服务端 + 传输调度。
 *
 * 为什么必须是前台服务：需求要求"传输 1GB 文件切后台 30 秒后继续"。
 * 普通后台 Service 在 API 26+ 会被冻结，Socket 读写可能被挂起甚至杀死进程；
 * 前台服务 + 常驻通知是唯一不需要云推送的保活方式。
 *
 * 兼容性：intent 里不携带任何 API 26+ 专属参数，通知使用 NotificationCompat，
 * 因此在 API 19 上等价于普通 Service + 通知。
 */
public class NetService extends Service {

    private static final String TAG = "LanMeshService";
    private static final String CH_SERVICE = "lanmesh_service";

    /** 通知副标题状态：null = 空闲（等待连接），否则是「正在发送 xxx · 45%」。 */
    private volatile String progressText;
    private volatile int progressPercent = -1;

    private static NetService instance;

    public static NetService get() {
        return instance;
    }

    private Prefs prefs;
    private MessageStore store;
    private PeerRegistry peers;
    private TransferManager transfer;

    private volatile ServerSocket server;
    private volatile int port = Const.DEFAULT_TCP_PORT;

    private NsdHelper nsd;
    private WebShareServer webShare;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private android.os.PowerManager.WakeLock wakeLock;
    private MulticastBeacon beacon;
    private volatile boolean multicastStarted = false;

    private ExecutorService connPool;
    private HandlerThread workerThread;
    private Handler worker;
    private Handler mainHandler;

    private final Map<String, TrustRequest> pendingTrust = new HashMap<String, TrustRequest>();
    private final Map<String, FileRequest> pendingFiles = new HashMap<String, FileRequest>();

    /** 手动接收文件时的确认请求。 */
    public static class FileRequest {
        public final String deviceId;
        public final String fileName;
        public final long size;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int answer = 2; // 0=接受 1=拒绝 2=超时

        FileRequest(String deviceId, String fileName, long size) {
            this.deviceId = deviceId;
            this.fileName = fileName;
            this.size = size;
        }
    }

    public static class TrustRequest {
        public final String deviceId;
        public final String nickname;
        public final String host;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int answer = 2; // 0=允许 1=拒绝 2=超时

        TrustRequest(String deviceId, String nickname, String host) {
            this.deviceId = deviceId;
            this.nickname = nickname;
            this.host = host;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppCore core = AppCore.get(this);
        prefs = core.prefs;
        store = core.messages;
        peers = core.peers;
        transfer = new TransferManager(this, prefs, store, peers);
        core.attachService(this);

        // 等待信任确认的握手会阻塞线程最多 60 秒：固定 4 线程会被几个未确认连接占满，
        // 但换成无上限的缓存池同样不行 —— 同网段随便建几百个连接就能把线程数打爆。
        // 这里用 8~48 的弹性池 + 有界队列，超出直接拒绝。
        connPool = new java.util.concurrent.ThreadPoolExecutor(8, MAX_INCOMING, 60L,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(MAX_INCOMING * 2),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        workerThread = new HandlerThread("lanmesh-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        mainHandler = new Handler(getMainLooper());

        createServiceChannel();
        startForegroundCompat();
        scheduleHousekeeping();
        if (prefs.lanEnabled()) {
            acquireKeepAlive();
            startServer();
            startDiscovery();
            startPortMapping();
            if (prefs.webShareEnabled()) startWebShare();
        }
        Log.i(TAG, "service started, tcp port=" + port);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次启动都重申前台状态：API 26+ 用 startForegroundService() 拉起时，
        // 系统要求 5 秒内必须 startForeground()，漏调会直接 ANR/崩溃。
        startForegroundCompat();
        // START_STICKY：被系统回收后重建，重新开始监听（不恢复传输，传输由 UI 重试）
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroying");
        try {
            // 前台状态必须先摘掉，否则通知会残留到服务销毁之后
            stopForeground(true);
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground failed", t);
        }
        releaseKeepAlive();
        if (webShare != null) webShare.stop();
        if (nsd != null) nsd.stop();
        if (beacon != null) beacon.stop();
        if (server != null) {
            try {
                server.close();
            } catch (Throwable ignored) {
            }
        }
        if (connPool != null) connPool.shutdownNow();
        if (workerThread != null) workerThread.quit();
        synchronized (pendingTrust) {
            for (TrustRequest t : pendingTrust.values()) t.latch.countDown();
            pendingTrust.clear();
        }
        synchronized (pendingFiles) {
            for (FileRequest f : pendingFiles.values()) f.latch.countDown();
            pendingFiles.clear();
        }
        // 心跳循环是无限自投的，不清掉的话服务销毁后主线程还每 5 秒跑一次，
        // 而且持有已销毁的 Service 实例
        try {
            mainHandler.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        if (instance == this) instance = null;
        AppCore.get(this).attachService(null);
        super.onDestroy();
    }

    // ---------------- 前台通知 ----------------

    /** 创建（或校正）前台服务通知渠道：API 26 以下没有渠道概念，直接跳过。 */
    private void createServiceChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel old = nm.getNotificationChannel(CH_SERVICE);
            if (old != null && old.getImportance() == NotificationManager.IMPORTANCE_MIN) {
                // 早期版本用过 IMPORTANCE_MIN，渠道重要性创建后不能再改，只能删掉重建；
                // 否则传输进度那行文本在通知栏里根本展不开。
                nm.deleteNotificationChannel(CH_SERVICE);
                old = null;
                Log.i(TAG, "recreate service channel (MIN -> LOW)");
            }
            if (old == null) {
                NotificationChannel ch = new NotificationChannel(CH_SERVICE,
                        getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.service_channel_desc));
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {
            Log.w(TAG, "create service channel failed", t);
        }
    }

    private void startForegroundCompat() {
        try {
            // 清掉旧 ID 的通知（升级前的缓存），避免继续显示旧图标
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try {
                    nm.cancel(Const.NOTIF_ID_SERVICE_LEGACY);
                } catch (Throwable ignored) {
                }
            }
            Notification n = buildServiceNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 必须显式带类型（Manifest 里已声明 dataSync）
                startForeground(Const.NOTIF_ID_SERVICE, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Const.NOTIF_ID_SERVICE, n);
            }
        } catch (SecurityException se) {
            // API 34+ 缺权限 / 通知被禁用时会被拒：降级为后台服务继续跑，不能崩
            Log.w(TAG, "startForeground denied, run as background service", se);
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
    }

    private Notification buildServiceNotification() {
        createServiceChannel();
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_SERVICE);
        } else {
            // API 19-25 没有渠道，重要性只能靠 builder 上的 priority 表达
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            b.setColor(getResources().getColor(R.color.accent));
            b.setCategory(Notification.CATEGORY_SERVICE);
        }
        // 系统标准样式：只保留 smallIcon（通知栏单色是系统硬性要求），不自绘 RemoteViews，
        // 否则会和 vivo 自己画的图标槽位叠成两个图标（见 notification_service.xml 的历史问题）。
        b.setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.service_title))
                .setContentText(statusText())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pi);
        if (progressPercent >= 0) {
            b.setProgress(100, progressPercent, false);
        }
        return b.build();
    }

    /** 通知副标题：空闲时固定「等待连接」，传输中由 updateProgress 覆盖。 */
    private String statusText() {
        String t = progressText;
        return (t == null) ? "等待连接" : t;
    }

    /**
     * 传输进度刷新：只更新同一条前台服务通知（Const.NOTIF_ID_SERVICE），不会多出一条。
     *
     * @param name    文件名，空则用「文件」兜底
     * @param percent 0-100，越界自动夹紧
     * @param sending true=正在发送，false=正在接收
     */
    public void updateProgress(String name, int percent, boolean sending) {
        int p = percent;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        String n = (name == null) ? "" : name.trim();
        if (n.length() == 0) n = "文件";
        progressText = (sending ? "正在发送 " : "正在接收 ") + n + " · " + p + "%";
        progressPercent = p;
        notifyStatus();
    }

    /** 传输结束或取消后回到「等待连接」。 */
    public void updateIdle() {
        progressText = null;
        progressPercent = -1;
        notifyStatus();
    }

    private void notifyStatus() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(Const.NOTIF_ID_SERVICE, buildServiceNotification());
        } catch (Throwable t) {
            // 通知被禁用（POST_NOTIFICATIONS 被拒）时 notify 会失败，不能影响传输本身
            Log.w(TAG, "notify status failed", t);
        }
    }

    /**
     * Android 14 起允许用户划掉前台服务通知。划掉后服务仍在跑，但状态栏没有
     * 任何提示，用户会以为应用挂了。这里在心跳里检查一次：
     *  - API 23+ 用 getActiveNotifications() 精确判断；
     *  - 更低版本无法查询，按 60 秒周期静默重发（onlyAlertOnce，不会响铃）。
     */
    private void ensureServiceNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            boolean present = false;
            if (Build.VERSION.SDK_INT >= 23) {
                android.service.notification.StatusBarNotification[] list = nm.getActiveNotifications();
                if (list != null) {
                    for (android.service.notification.StatusBarNotification sbn : list) {
                        if (sbn.getId() == Const.NOTIF_ID_SERVICE) {
                            present = true;
                            break;
                        }
                    }
                }
                if (!present) {
                    Log.i(TAG, "service notification dismissed, restoring");
                    startForegroundCompat();
                }
            } else {
                long now = System.currentTimeMillis();
                if (now - lastNotifyRefresh > 60000L) {
                    lastNotifyRefresh = now;
                    nm.notify(Const.NOTIF_ID_SERVICE, buildServiceNotification());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensure notification failed", t);
        }
    }

    private long lastNotifyRefresh = 0;

    // ---------------- TCP 服务端 ----------------

    private synchronized void startServer() {
        if (server != null && !server.isClosed()) return;
        for (int i = 0; i < Const.PORT_SCAN_RANGE; i++) {
            int candidate = Const.DEFAULT_TCP_PORT + i;
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(candidate));
                server = ss;
                port = candidate;
                break;
            } catch (IOException e) {
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (server == null) {
            Log.e(TAG, "no free port, LAN server disabled");
            Bus.toast("无法监听局域网端口，请重启 App");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "lanmesh-accept");
        t.setDaemon(true);
        t.start();
    }

    /** 同时处理的入站连接上限 */
    private static final int MAX_INCOMING = 48;
    private final java.util.concurrent.Semaphore connSlots =
            new java.util.concurrent.Semaphore(MAX_INCOMING);

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            Socket s = null;
            try {
                s = server.accept();
                s.setTcpNoDelay(true);
                // 读超时由 Session 构造函数统一设置（Const.SOCKET_TIMEOUT_MS）
                final Socket incoming = s;
                if (!connSlots.tryAcquire()) {
                    Util.closeQuietly(incoming); // 并发已满：别让它占着 fd 排队
                    continue;
                }
                final Session session = new Session(NetService.this, incoming, prefs, store, peers, transfer,
                        new Session.TrustGate() {
                            @Override
                            public boolean askTrust(String deviceId, String nickname, String host) {
                                return NetService.this.askTrustBlocking(deviceId, nickname, host);
                            }
                        },
                        new Session.FileGate() {
                            @Override
                            public boolean askAccept(String deviceId, String fileName, long size) {
                                return NetService.this.askFileAccept(deviceId, fileName, size);
                            }
                        });
                try {
                    connPool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                session.run();
                            } finally {
                                connSlots.release();
                            }
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException rej) {
                    connSlots.release();
                    Util.closeQuietly(incoming);
                }
            } catch (Throwable t) {
                if (server != null && !server.isClosed()) {
                    Log.w(TAG, "accept failed", t);
                }
                Util.closeQuietly(s);
            }
        }
    }

    public int getPort() {
        return port;
    }

    /**
     * 昵称等广播元数据变化后重新注册 NSD。
     *
     * NSD 的 attributes 只在注册时上报，改昵称不重注册的话，对端会一直看到旧名字；
     * 组播每次广播都现读 prefs，因此不需要重启。
     */
    /** 手动刷新：停掉 NSD/组播，清空列表后重新开始搜索。 */
    public void restartDiscovery() {
        if (!prefs.lanEnabled()) return;
        worker.post(new Runnable() {
            @Override
            public void run() {
                if (nsd != null) {
                    nsd.stop();
                    nsd = null;
                }
                if (beacon != null) {
                    beacon.stop();
                    beacon = null;
                }
                multicastStarted = false;
                startDiscovery();
            }
        });
    }

    public void refreshDiscovery() {
        worker.post(new Runnable() {
            @Override
            public void run() {
                if (nsd != null) {
                    nsd.stop();
                }
                startDiscovery();
            }
        });
    }

    // ---------------- 发现 ----------------

    private void startDiscovery() {
        try {
            nsd = new NsdHelper(this, prefs.deviceId(), prefs.nickname(), port, new NsdHelper.Listener() {
                @Override
                public void onPeerFound(String host, int p, Map<String, String> attrs) {
                    String deviceId = attrs.get("deviceId");
                    if (deviceId == null || deviceId.length() < 8) {
                        // API 19 只有短 ID：先用短 ID 占位，等 TCP 握手拿到真实 deviceId
                        String shortId = attrs.get("shortId");
                        if (shortId == null) return;
                        // 用短 ID 作为临时键（前缀标注），握手成功后会被真实记录覆盖
                        deviceId = "short-" + shortId;
                    }
                    String nickname = attrs.get("nickname");
                    if (peers.upsert(deviceId, nickname, host, p, Peer.SRC_NSD)) {
                        Bus.post(Bus.PEERS_CHANGED, null);
                    } else {
                        Bus.post(Bus.PEER_UPDATED, peers.get(deviceId));
                    }
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "nsd: " + message);
                }
            });
            nsd.start();
        } catch (Throwable t) {
            Log.w(TAG, "nsd start failed", t);
        }

        // 5 秒降级：没有 NSD 结果就叠加组播（NSD 不关闭，继续并行工作）
        worker.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 用户可能在这 5 秒里关掉了开关：关了就绝不能再把组播拉起来
                if (!prefs.lanEnabled() || instance != NetService.this) return;
                if (!multicastStarted && !peers.hasOnlinePeer()) {
                    startMulticast("NSD 5 秒无邻居");
                } else if (!multicastStarted) {
                    // 已有邻居也启动组播，保证心跳持续（对端切换网络后可被重新发现）
                    startMulticast("NSD 已有邻居，组播并行");
                }
            }
        }, Const.DISCOVERY_FALLBACK_MS);
    }

    private int multicastRetry = 0;

    private void startMulticast(final String reason) {
        if (multicastStarted) return;
        multicastStarted = true;
        Log.i(TAG, "start multicast: " + reason);
        try {
            beacon = new MulticastBeacon(this, prefs, port, new MulticastBeacon.Listener() {
                @Override
                public void onPeer(String deviceId, String nickname, String host, int tcpPort) {
                    if (peers.upsert(deviceId, nickname, host, tcpPort, Peer.SRC_MULTICAST)) {
                        Bus.post(Bus.PEERS_CHANGED, null);
                    }
                }
            });
            if (!beacon.start()) {
                // 端口被占 / MulticastLock 无权限：复位标志，10 秒后重试，别让降级通道永久瘫痪
                multicastStarted = false;
                beacon = null;
                if (multicastRetry < 3) {
                    multicastRetry++;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!prefs.lanEnabled() || instance != NetService.this) return;
                            startMulticast("retry " + multicastRetry);
                        }
                    }, 10000);
                }
                return;
            }
            multicastRetry = 0;
        } catch (Throwable t) {
            Log.w(TAG, "multicast start failed", t);
            multicastStarted = false;
        }
    }

    /**
     * 后台保活：
     *  - WifiLock(HIGH_PERF)：屏幕关闭后 Wi-Fi 不会被切到省电模式，
     *    否则网页端和 P2P 在锁屏几分钟后就会"掉线"；
     *  - PARTIAL_WAKE_LOCK：允许后台处理 Socket 数据（不点屏）。
     * 这两个锁都常驻，代价是耗电；如果用户不需要后台接收，可以在设置里关掉
     * （后续可以加开关，目前先默认开）。
     */
    private void acquireKeepAlive() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanmesh:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wifi lock failed", t);
        }
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "lanmesh:cpu");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wake lock failed", t);
        }
    }

    private void releaseKeepAlive() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        wifiLock = null;
        wakeLock = null;
    }

        /**
     * Android 15 起 dataSync 类型的前台服务每天有累计时长上限，到点必须自己停，
     * 否则系统按"未在超时内停止前台服务"判 ANR。
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "foreground service timeout, stopping");
        try {
            Bus.toast("后台保活已达系统上限，重新打开应用即可恢复");
        } catch (Throwable ignored) {
        }
        try {
            updateIdle();
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    /** 任务被划掉后 1 秒重新拉起服务，避免用户"清理后台"就彻底失联。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(this, NetService.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            // API 26+ 后台不允许 startService，必须走前台服务入口；
            // RTC 在灭屏时不触发，改用 RTC_WAKEUP
            PendingIntent pi = Build.VERSION.SDK_INT >= 26
                    ? PendingIntent.getForegroundService(this, 101, i, flags)
                    : PendingIntent.getService(this, 101, i, flags);
            long at = System.currentTimeMillis() + 1000;
            // 不能用 setExactAndAllowWhileIdle：它需要 SCHEDULE_EXACT_ALARM，
            // Android 12+ 上必抛 SecurityException，被下面的 catch 吞掉后服务再也拉不起来。
            // 这里本来也不需要精确时间。
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "restart on task removed failed", t);
        }
        super.onTaskRemoved(rootIntent);
    }

    /** 阶段三：浏览器访客入口。 */
    private void startWebShare() {
        try {
            final WebShareServer ws = new WebShareServer(this);
            ws.start();
            if (!ws.isRunning()) {
                // 10 个端口全被占：别让 UI 显示"已开启"然后给个死链
                Log.w(TAG, "web share failed to bind any port");
                prefs.setWebShareEnabled(false);
                webShare = null;
                return;
            }
            webShare = ws;
        } catch (Throwable t) {
            Log.w(TAG, "web share start failed", t);
        }
    }

    public int getWebPort() {
        return webShare == null ? WebShareServer.DEFAULT_PORT : webShare.getPort();
    }

    /** 局域网传输总开关：关闭后停止发现、TCP、网页与保活锁。 */
    public void setLanEnabled(boolean enable) {
        if (enable) {
            if (server == null) startServer();
            if (nsd == null) startDiscovery();
            if (prefs.webShareEnabled() && webShare == null) startWebShare();
            acquireKeepAlive();
            startPortMapping();
            // LocalSend 兼容层同样属于"局域网传输"，开关打开时也要拉起来
            try {
                com.jiuyue.lanmesh.core.AppCore c = com.jiuyue.lanmesh.core.AppCore.get(this);
                com.jiuyue.lanmesh.net.ls.LsServer ls = c.lsServer();
                if (ls != null && !ls.isRunning()) ls.start();
                com.jiuyue.lanmesh.net.ls.LsDiscovery lsd = c.lsDiscovery();
                if (lsd != null) lsd.start();
            } catch (Throwable ignored) {
            }
        } else {
            if (nsd != null) {
                nsd.stop();
                nsd = null;
            }
            if (beacon != null) {
                beacon.stop();
                beacon = null;
            }
            multicastStarted = false; // 否则下次打开局域网时 startMulticast 直接 return
            multicastRetry = 0;
            PortMapper.stopRenew();   // 别让续约线程在关闭后继续打路由器
            // LocalSend 层原来完全不受这个开关控制：关掉之后 53317 还在收文件、
            // 还在对外宣告、还持有 MulticastLock。用户以为关了，其实没关。
            try {
                com.jiuyue.lanmesh.core.AppCore c = com.jiuyue.lanmesh.core.AppCore.get(this);
                com.jiuyue.lanmesh.net.ls.LsServer ls = c.lsServer();
                if (ls != null) ls.stop();
                com.jiuyue.lanmesh.net.ls.LsDiscovery lsd = c.lsDiscovery();
                if (lsd != null) lsd.stop();
            } catch (Throwable ignored) {
            }
            if (webShare != null) {
                webShare.stop();
                webShare = null;
            }
            try {
                if (server != null) server.close();
            } catch (Throwable ignored) {
            }
            server = null;
            releaseKeepAlive();
        }
    }

    public boolean isWebShareRunning() {
        return webShare != null && webShare.isRunning();
    }

    /** 网页端开关：关闭时清理中转产生的文件。 */
    public void setWebShareEnabled(boolean enable) {
        if (!prefs.lanEnabled() && enable) return;
        if (enable == isWebShareRunning()) return;
        if (enable) {
            startWebShare();
        } else {
            WebShareServer old = webShare;
            webShare = null;
            if (old != null) {
                old.cleanupRelayFiles();
                old.stop();
            }
        }
    }

    /**
     * 启动 5 秒后尝试用路由器做端口映射（UPnP / NAT-PMP）。
     * 失败是常态（移动网络、关闭 UPnP 的路由器），只记录不打扰。
     */
    private void startPortMapping() {
        worker.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    PortMapper.map(NetService.this, port, new PortMapper.Callback() {
                        @Override
                        public void onResult(String method, String externalIp, int externalPort, String error) {
                            if (method != null) {
                                Log.i(TAG, "port mapped via " + method + " -> " + externalIp + ":" + externalPort);
                                Bus.post(Bus.PORT_MAP, method + " " + externalIp + ":" + externalPort);
                            } else {
                                Log.i(TAG, "port mapping unavailable: " + error);
                                Bus.post(Bus.PORT_MAP, "fail:" + Util.safe(error));
                            }
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "port mapping crashed", t);
                }
            }
        }, 5000);
    }

    private void scheduleHousekeeping() {
        // 放在主线程 Handler：worker 上可能正卡着 60 秒的握手，
        // 过期清理和通知恢复不该跟着停摆
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ensureServiceNotification();
                boolean changed = peers.removeExpired();
                if (changed) Bus.post(Bus.PEERS_CHANGED, null);
                else if (peers.size() > 0) Bus.post(Bus.PEER_UPDATED, null);
                mainHandler.postDelayed(this, Const.HEARTBEAT_MS / 2);
            }
        });
    }

    // ---------------- 信任确认 ----------------

    private boolean askTrustBlocking(String deviceId, String nickname, String host) {
        TrustRequest req = new TrustRequest(deviceId, nickname, host);
        synchronized (pendingTrust) {
            TrustRequest old = pendingTrust.put(deviceId, req);
            if (old != null) {
                // 同一设备并发发起第二次连接：放走第一个等待者，否则它的 latch 永远没人 countDown
                old.answer = 2;
                old.latch.countDown();
            }
        }
        Bus.post(Bus.TRUST_REQUEST, req);
        try {
            req.latch.await(Const.TRUST_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        synchronized (pendingTrust) {
            if (pendingTrust.get(deviceId) == req) pendingTrust.remove(deviceId);
        }
        if (req.answer == 1) {
            prefs.deny(deviceId);
            return false;
        }
        if (req.answer == 2) {
            // 超时：不写入拒绝列表，用户下次在线时仍可被询问
            Bus.toast("信任确认超时，已断开 " + (nickname == null ? Util.shortId(deviceId) : nickname));
            return false;
        }
        prefs.trust(deviceId);
        Peer p = peers.get(deviceId);
        if (p != null) p.trusted = true;
        Bus.post(Bus.PEERS_CHANGED, null);
        return true;
    }

    /** UI 调用：回答信任卡片。 */
    /** Session 在 autoAcceptFiles=false 时调用，阻塞等待用户点"接受/拒绝"。 */
    public boolean askFileAccept(String deviceId, String fileName, long size) {
        FileRequest req = new FileRequest(deviceId, fileName, size);
        synchronized (pendingFiles) {
            FileRequest old = pendingFiles.put(deviceId, req);
            if (old != null) {
                old.answer = 2; // 同设备并发：让旧的直接超时返回
                old.latch.countDown();
            }
        }
        Bus.post(Bus.FILE_REQUEST, req);
        try {
            req.latch.await(Const.TRUST_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        synchronized (pendingFiles) {
            if (pendingFiles.get(deviceId) == req) pendingFiles.remove(deviceId);
        }
        return req.answer == 0;
    }

    public void answerFileAccept(String deviceId, boolean accept) {
        FileRequest req;
        synchronized (pendingFiles) {
            req = pendingFiles.get(deviceId);
        }
        if (req == null) return;
        req.answer = accept ? 0 : 1;
        req.latch.countDown();
    }

    public void answerTrust(String deviceId, boolean allow) {
        TrustRequest req;
        synchronized (pendingTrust) {
            req = pendingTrust.get(deviceId);
        }
        if (req == null) return;
        req.answer = allow ? 0 : 1;
        req.latch.countDown();
    }

    public TrustRequest pendingTrustFor(String deviceId) {
        synchronized (pendingTrust) {
            return pendingTrust.get(deviceId);
        }
    }

    // ---------------- 文本发送 ----------------

    public void sendText(final Peer peer, final ChatMessage msg) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                doSendText(peer, msg);
            }
        });
    }

    private void doSendText(Peer peer, ChatMessage msg) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(peer.host, peer.port), Const.SOCKET_TIMEOUT_MS);
            s.setSoTimeout((int) (Const.TRUST_WAIT_MS + 5000));
            s.setTcpNoDelay(true);
            Protocol.Reader reader = new Protocol.Reader(s.getInputStream());
            Protocol.Writer writer = new Protocol.Writer(s.getOutputStream());

            JSONObject hello = new JSONObject();
            hello.put("magic", Protocol.MAGIC);
            hello.put("proto", Const.PROTO_VERSION);
            hello.put("deviceId", prefs.deviceId());
            hello.put("nickname", prefs.nickname());
            hello.put("port", port);
            String myNonce = EncHandshake.attach(hello, prefs);
            writer.writeJson(Protocol.T_HELLO, hello);

            if (!transfer.handshake(reader, writer, peer, myNonce)) {
                throw new IllegalStateException("对方未确认连接");
            }

            JSONObject t = new JSONObject();
            t.put("msgId", msg.msgId);
            t.put("fromDeviceId", prefs.deviceId());
            t.put("fromNickname", prefs.nickname());
            t.put("timestamp", msg.timestamp);
            t.put("text", msg.text);
            writer.writeJson(Protocol.T_TEXT, t);

            s.setSoTimeout(Const.SOCKET_TIMEOUT_MS);
            boolean acked = false;
            while (true) {
                Protocol.Frame f = reader.read();
                if (f == null) break;
                if (f.type == Protocol.T_TEXT_ACK) {
                    acked = f.json().optBoolean("ok", false);
                    break;
                }
                if (f.type == Protocol.T_BYE) break;
            }
            if (!acked) throw new IllegalStateException("对方未确认收到");

            msg.status = ChatMessage.ST_SENT;
            msg.error = null;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
        } catch (Throwable t) {
            msg.status = ChatMessage.ST_FAILED;
            msg.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            Bus.toast("发送失败：" + msg.error);
        } finally {
            Util.closeQuietly(s);
        }
    }

    // ---------------- 文件发送 ----------------

    public void sendFile(Peer peer, ChatMessage msg, Uri uri, String path) {
        transfer.sendFile(peer, msg, uri, path);
    }

    public TransferManager transfers() {
        return transfer;
    }

    // ---------------- 手动添加 ----------------

    public void connectManual(final String host, final int p) {
        Bus.post(Bus.SERVICE_STATE, "connecting:" + host + ":" + p);
        worker.post(new Runnable() {
            @Override
            public void run() {
                Socket s = null;
                String error = null;
                Peer found = null;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(host, p), Const.SOCKET_TIMEOUT_MS);
                    s.setSoTimeout((int) (Const.TRUST_WAIT_MS + 8000));
                    Protocol.Reader reader = new Protocol.Reader(s.getInputStream());
                    Protocol.Writer writer = new Protocol.Writer(s.getOutputStream());

                    JSONObject hello = new JSONObject();
                    hello.put("magic", Protocol.MAGIC);
                    hello.put("proto", Const.PROTO_VERSION);
                    hello.put("deviceId", prefs.deviceId());
                    hello.put("nickname", prefs.nickname());
                    hello.put("port", port);
                    final String myNonce = EncHandshake.attach(hello, prefs);
                    writer.writeJson(Protocol.T_HELLO, hello);

                    long deadline = System.currentTimeMillis() + Const.TRUST_WAIT_MS + 5000;
                    while (System.currentTimeMillis() < deadline) {
                        Protocol.Frame f = reader.read();
                        if (f == null) {
                            error = "对方关闭了连接";
                            break;
                        }
                        if (f.type == Protocol.T_HELLO_ACK) {
                            JSONObject o = f.json();
                            if (!o.optBoolean("ok", true)) {
                                error = o.optString("reason", "对方拒绝连接");
                                break;
                            }
                            try {
                                Protocol.Secure sec = EncHandshake.build(o, myNonce, prefs);
                                if (sec != null) {
                                    writer.setSecure(sec);
                                    reader.setSecure(sec);
                                }
                            } catch (Exception enc) {
                                error = EncHandshake.why(enc);
                                break;
                            }
                            String did = o.optString("deviceId", "");
                            if (did.length() >= 8) {
                                peers.upsert(did, o.optString("nickname", ""), host, p, Peer.SRC_MANUAL);
                                Peer peer = peers.get(did);
                                if (peer != null) {
                                    peer.trusted = prefs.isTrusted(did);
                                    prefs.rememberPeerAddress(did, host, p);
                                    found = peer;
                                }
                            }
                            break;
                        }
                        if (f.type == Protocol.T_TRUST_REQUEST) {
                            Bus.post(Bus.TOAST, "已连接，等待对方在设备上确认…");
                            continue;
                        }
                        if (f.type == Protocol.T_TRUST_DENIED) {
                            error = "对方拒绝连接";
                            break;
                        }
                    }
                    if (found == null && error == null) error = "等待对方确认超时";
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                } finally {
                    Util.closeQuietly(s);
                }
                if (found != null) {
                    Bus.post(Bus.PEERS_CHANGED, null);
                    Bus.post(Bus.MANUAL_RESULT, "ok");
                    Bus.toast("已添加 " + found.displayName());
                } else {
                    Bus.post(Bus.MANUAL_RESULT, "fail:" + Util.safe(error));
                }
            }
        });
    }
}
