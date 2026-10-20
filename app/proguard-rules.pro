# 阶段一未开启混淆。保留文件以便阶段二接入 WebRTC 时补充 keep 规则。
-keep class org.webrtc.** { *; }
