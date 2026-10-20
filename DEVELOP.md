# Link Mesh 开发文档

> 面向想读懂、修改、二次开发这个项目的人。构建可复现、架构有图、坑有注释。

## 构建

环境：JDK 17 + Android SDK（compileSdk 36）+ Gradle 8.13。

```bash
git clone https://github.com/hesanyue50-lang/link-mesh.git
cd link-mesh
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 依赖

| 依赖 | 版本 | 说明 |
|---|---|---|
| androidx.appcompat | **1.6.1** | 必须钉住——1.7+ 的 minSdk 是 21，会破坏 Android 4.4 兼容 |
| androidx.core / annotation | 1.9.0 / 1.7.1 | |
| io.github.webrtc-sdk:android | 125.6422.07 | P2P（BSD-3），AAR 放在 `app/libs/` |
| com.google.zxing:core | 3.5.3 | 二维码生成 |
| zxing-android-embedded | 4.3.0 | 扫码 |

WebRTC AAR 的 minSdk=21，manifest 里用 `tools:overrideLibrary` 放行合并；
API 19/20 上不加载它的类，由 `P2pGate` 在运行时拦截入口。

## 架构

```
App (Application)
 └─ AppCore（单例：Prefs / MessageStore / PeerRegistry）
 └─ NetService（前台服务，START_STICKY）
     ├─ TCP ServerSocket :45900+    ← 自定义帧协议，断点续传
     ├─ NsdHelper                    ← _lanmesh._tcp 发现
     ├─ MulticastBeacon              ← 239.255.42.99:45901 组播兜底
     ├─ WebShareServer :45902+       ← 手写 HTTP 网页端
     ├─ WebRtcSession / P2pManager   ← DataChannel + 二维码信令
     ├─ PortMapper                   ← UPnP-IGD / NAT-PMP
     └─ TransferManager              ← 传输任务 / 通知 / 取消
UI：MainActivity（PanelPager 三页）+ ChatActivity + 各自绘控件
```

### 传输协议

帧格式：`int32 payloadLength | uint8 frameType | payload`

- 控制帧 payload 是 UTF-8 JSON；CHUNK 帧 = `int32 chunkIndex + 原始字节`
- 128KB 分块；接收端 `RandomAccessFile.seek` 随机写，`BitSet` 记录已收块（1GB ≈ 1KB 位图），每 1.5 秒落盘
- 断点续传：重连时接收端在 `FILE_ACCEPT` 里回传位图，发送端跳过已收块
- 完整性：发送前流式算 SHA-256，接收后重算比对；内存占用与文件大小无关

### 发现：三层降级

1. NSD（mDNS）：API 21+ 用 attributes 带 deviceId/昵称；**API 19 没有 setAttribute**，降级为服务名 `lm-<短ID>` + TCP 握手补全
2. 组播：NSD 5 秒无邻居自动叠加；**必须持 MulticastLock**，否则收不到包
3. 手动 IP：UI 常驻入口

同 deviceId 三路去重，30 秒无迹象判离线。

### WebRTC

- `InitializationOptions.builder(Context)` → `initialize` → `createPeerConnectionFactory`
- **IceCandidate 必须带 sdpMid + sdpMLineIndex**：只传字符串会被对端静默丢弃（表现为"对称 NAT 打洞失败"，实际是候选没加上）
- 信令压缩：只留 host/srflx、IPv4≤4 + IPv6≤2、raw deflate + Base64 URL-safe ≈ 600 字节 → 二维码
- ICE gathering 10 秒兜底 emit；建连 60 秒超时（日志逐秒倒计时）

### 网页端

手写 HTTP（5 路由），不引框架。上传用 PUT 流式写入；`newCachedThreadPool`
（固定 2 线程会被浏览器轮询占满）。访客身份存 localStorage，会话按 `web-<guest>` 分桶。

## 版本规范

`MAJOR.MINOR.PATCH[-STAGE]`；versionCode 每次发版 +1。

- 1.0.0 = 阶段一（局域网）
- 1.1.0 = 阶段二（P2P + 网页端）

## 已知限制

- 双层 NAT 下 UPnP 只能穿一层（外部 IP 是私网地址）
- 对称型 NAT 双方均无 IPv6 时，P2P 无法成功（无 TURN 是产品红线）
- Android 10+ 不能直写公共 Download，接收目录走 SAF 授权
- 网页端无鉴权（局域网内任何设备可访问），公共网络勿开
