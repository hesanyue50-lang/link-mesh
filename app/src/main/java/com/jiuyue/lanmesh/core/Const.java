package com.jiuyue.lanmesh.core;

/**
 * 全局常量与超时参数。
 *
 * 所有"为什么是这个数"的理由集中写在这里，避免散落在各模块无法追溯。
 */
public final class Const {

    private Const() {}

    /**
     * P2P（WebRTC）最低系统版本：Android 5.0 / API 21。
     *
     * 理由：WebRTC 官方 Android SDK 的 minSdk 是 21，API 19/20 上既没有
     * 可用的 PeerConnectionFactory，也缺少其依赖的 java.nio/SSL 行为。
     * 该常量同时被 P2P 门禁的版本判断与提示文案引用，保证"文案说的门槛"
     * 与"代码判断的门槛"永远一致，不会出现改了一处忘记另一处。
     */
    public static final int MIN_P2P_API = 21;

    /** 协议版本。发现广播与 HELLO 都携带，便于以后灰度升级。 */
    public static final int PROTO_VERSION = 1;

    /** NSD 服务类型：固定使用私有类型，避免与其它 App 撞车。 */
    public static final String SERVICE_TYPE = "_lanmesh._tcp";

    /** 默认 TCP 服务端口。被占用时向后顺延（见 NetService.bindServer）。 */
    public static final int DEFAULT_TCP_PORT = 45900;
    /** 端口顺延的最大尝试次数（45900-45939）。 */
    public static final int PORT_SCAN_RANGE = 40;

    /** 组播兜底：固定组播组与端口（管理范围 239.x，无需 IANA 分配）。 */
    public static final String MULTICAST_GROUP = "239.255.42.99";
    public static final int MULTICAST_PORT = 45901;

    /**
     * NSD 无邻居后启用组播的等待时间：5 秒。
     *
     * 理由：NsdManager 在多数设备上 1-3 秒内即可回调 onServiceFound，
     * 5 秒足以覆盖慢设备；再久会让用户以为"搜不到"。组播启动后并不会
     * 关闭 NSD，两条路径并行且结果去重。
     */
    public static final long DISCOVERY_FALLBACK_MS = 5000L;

    /** 组播广播周期：5 秒，另加 0-1500ms 随机抖动防止同时开机撞包。 */
    public static final long MULTICAST_INTERVAL_MS = 5000L;
    public static final int MULTICAST_JITTER_MS = 1500;

    /**
     * 邻居过期时间：30 秒。
     *
     * 理由：组播/NSD 广播周期 5 秒，容忍 6 个周期丢失（弱信号、Doze 抖动）；
     * 小于 30 秒会让列表频繁闪烁，大于 45 秒则离线设备长期占位。
     */
    public static final long PEER_EXPIRE_MS = 30000L;

    /** 主动心跳/状态刷新周期：10 秒（只更新 lastSeen，不做长连接 ping）。 */
    public static final long HEARTBEAT_MS = 10000L;

    /** 连接与读写超时：15 秒。局域网内超过 15 秒无响应基本可判定链路不可用。 */
    public static final int SOCKET_TIMEOUT_MS = 15000;

    /**
     * 文件分块：128KB。
     *
     * 理由：太小（4-16KB）会让 1GB 文件产生数万次 syscall/帧开销；
     * 太大（1MB+）在 API 19 低内存设备上容易触发 GC 抖动。
     * 128KB 下单流内存占用恒定约 256KB（读+写缓冲），与文件大小无关。
     */
    public static final int CHUNK_SIZE = 128 * 1024;

    /** 单帧 payload 上限，防止恶意/错乱连接把内存打爆。 */
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;

    /**
     * P2P 建连超时：60 秒。
     *
     * 实测 ICE + DTLS 通常 10-20 秒完成，但移动网络下偶发 STUN 重试会到 40 秒；
     * 30 秒偏短容易误杀，60 秒兼顾"不无限等待"和慢链路。
     */
    public static final long P2P_CONNECT_TIMEOUT_MS = 60000L;

    /** 信任确认等待上限：60 秒（用户可能不在屏幕前，超时按拒绝处理）。 */
    public static final long TRUST_WAIT_MS = 60000L;

    /** 文本消息最大长度（字符），避免超长帧。 */
    public static final int MAX_TEXT_CHARS = 4000;

    /** 每个对端的本地聊天记录上限，防止 SharedPreferences/文件无限增长。 */
    public static final int MAX_MESSAGES_PER_PEER = 500;

    /** 并发接收连接数上限：4（每连接约 256KB 缓冲，总计约 1MB）。 */
    public static final int MAX_CONCURRENT_TRANSFERS = 4;

    /** 前台服务通知 ID（固定，避免重复通知）。 */
    /**
     * 前台服务通知 ID。
     *
     * 从 1001 改成 1003 是有意的：系统会按 ID 保留通知的图标/布局缓存，
     * 升级后如果沿用旧 ID，部分 ROM 会继续显示旧图标。换 ID 并主动 cancel
     * 旧 ID，等于强制系统丢弃旧通知记录。
     */
    public static final int NOTIF_ID_SERVICE = 1003;
    public static final int NOTIF_ID_SERVICE_LEGACY = 1001;
    public static final int NOTIF_ID_TRANSFER = 1002;
}
