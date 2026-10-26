package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段二：WebRTC 点对点会话（只传数据，不用音视频）。
 *
 * 设计取舍：
 *  - 用 DataChannel 而不是 MediaStream：文件/文本是可靠有序的数据，SCTP 天然合适；
 *  - 只配 STUN，不配 TURN：需求明确禁止 TURN，打洞失败就明确提示；
 *  - 手动信令：SDP + ICE 压缩成一段邀请码/应答码，用户复制粘贴，不依赖任何服务器；
 *  - 15 秒硬超时：从 createPeerConnection 开始计时，未 OPEN 立即失败并释放资源。
 */
public class WebRtcSession {

    private static final String TAG = "LanMeshWebRtc";

    /** DataChannel 背压水位：超过高水位暂停写入，降到低水位恢复。 */
    private static final long HIGH_WATER = 1024 * 1024;
    private static final long LOW_WATER = 256 * 1024;
    private final Object sendLock = new Object();

    public interface Callback {
        /** 本地 SDP（含已收集的 ICE）就绪，交给用户复制给对方。 */
        void onLocalSignal(P2pSignal signal);

        /** DataChannel 已打开。 */
        void onConnected();

        /** 收到一帧（协议帧，见 Protocol）。 */
        void onFrame(int type, byte[] payload);

        /** 建连倒计时（秒）。 */
        void onCountdown(int seconds);

        void onClosed(String reason);

        void onError(String message);
    }

    private static PeerConnectionFactory factory;
    private static final Object FACTORY_LOCK = new Object();

    private final Handler main = new Handler(Looper.getMainLooper());
    private PeerConnection pc;
    private DataChannel dc;
    private P2pSignal localSignal;
    private Callback callback;
    private volatile boolean closed;
    private volatile boolean connected;
    private boolean isOfferer;
    private final List<IceCandidate> pendingRemoteIce = new ArrayList<IceCandidate>();
    private boolean remoteDescriptionSet;
    private boolean signalEmitted;
    private long deadline;
    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            if (closed || connected) return;
            int remain = (int) Math.max(0, (deadline - System.currentTimeMillis()) / 1000);
            if (callback != null) callback.onCountdown(remain);
            if (remain > 0) main.postDelayed(this, 1000);
        }
    };
    private final Runnable signalFallback = new Runnable() {
        @Override
        public void run() {
            // 少数网络下 ICE gathering 迟迟不 COMPLETE：10 秒兜底先给出一版信令，
            // 否则 UI 会一直停在"正在生成…"。
            if (!closed) emitLocalSignal();
        }
    };

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (!connected && !closed) {
                Log.w(TAG, "p2p connect timeout");
                int srflx = 0;
                int host = 0;
                if (localSignal != null) {
                    for (P2pSignal.IceEntry e : localSignal.ice) {
                        if (e.candidate == null) continue;
                        if (e.candidate.contains(" typ srflx")) srflx++;
                        else if (e.candidate.contains(" typ host")) host++;
                    }
                }
                String why = srflx == 0
                        ? "本机没有拿到公网候选（STUN 全超时），当前网络可能禁止 UDP"
                        : (host == 0 ? "没有局域网候选，双方可能不在同一网段" : "可能是对称型 NAT 或防火墙拦截");
                fail("60 秒内未能建立 P2P 连接：" + why + "。局域网传输仍可用");
            }
        }
    };

    // ---------------- 工厂 ----------------

    public static boolean ensureFactory(Context ctx) {
        synchronized (FACTORY_LOCK) {
            if (factory != null) return true;
            try {
                PeerConnectionFactory.InitializationOptions opts =
                        PeerConnectionFactory.InitializationOptions.builder(ctx.getApplicationContext())
                                .setEnableInternalTracer(false)
                                .createInitializationOptions();
                PeerConnectionFactory.initialize(opts);
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
                Log.i(TAG, "PeerConnectionFactory initialized");
                return factory != null;
            } catch (Throwable t) {
                Log.e(TAG, "factory init failed", t);
                return false;
            }
        }
    }

    private static List<PeerConnection.IceServer> iceServers() {
        List<PeerConnection.IceServer> list = new ArrayList<PeerConnection.IceServer>();
        // 只使用公共 STUN；不配置 TURN（需求红线）
        // 国内优先：Google 的 STUN 在部分网络不可达，会导致没有 srflx 候选
        list.add(PeerConnection.IceServer.builder("stun:stun.miwifi.com:3478").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.chat.bilibili.com:3478").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.nextcloud.com:443").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.syncthing.net:3478").createIceServer());
        return list;
    }

    // ---------------- 发起 / 接受 ----------------

    public void startOffer(Context ctx, Callback cb) {
        this.callback = cb;
        this.isOfferer = true;
        if (!ensureFactory(ctx)) {
            fail("WebRTC 初始化失败");
            return;
        }
        try {
            createPeerConnection(ctx);
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            dc = pc.createDataChannel("lanmesh", init);
            registerDataChannel(dc);

            localSignal = new P2pSignal(P2pSignal.TYPE_OFFER, "");
            pc.createOffer(new SimpleSdpObserver() {
                @Override
                public void onCreateSuccess(final SessionDescription sdp) {
                    Log.i(TAG, "offer created");
                    pc.setLocalDescription(new SimpleSdpObserver() {
                        @Override
                        public void onSetSuccess() {
                            localSignal.sdp = sdp.description;
                            Log.i(TAG, "offer localDescription set");
                        }
                    }, sdp);
                }

                @Override
                public void onCreateFailure(String s) {
                    fail("创建 offer 失败: " + s);
                }
            }, new MediaConstraints());
            deadline = System.currentTimeMillis() + com.jiuyue.lanmesh.core.Const.P2P_CONNECT_TIMEOUT_MS;
            main.postDelayed(timeout, com.jiuyue.lanmesh.core.Const.P2P_CONNECT_TIMEOUT_MS);
            main.postDelayed(signalFallback, 10000);
            main.post(countdown);
        } catch (Throwable t) {
            fail("发起连接失败: " + t.getMessage());
        }
    }

    public void acceptOffer(Context ctx, String code, Callback cb) {
        this.callback = cb;
        this.isOfferer = false;
        if (!ensureFactory(ctx)) {
            fail("WebRTC 初始化失败");
            return;
        }
        final P2pSignal remote = P2pSignal.decode(code);
        if (remote == null || !remote.isValid() || !P2pSignal.TYPE_OFFER.equals(remote.type)) {
            fail("邀请码无效或已损坏");
            return;
        }
        try {
            createPeerConnection(ctx);
            localSignal = new P2pSignal(P2pSignal.TYPE_ANSWER, "");
            // 先把远端 ICE 放进队列：部分设备 onSetSuccess 是同步回调的，
            // 放后面会导致 flush 时队列还是空的（候选永久丢失）
            for (P2pSignal.IceEntry e : remote.ice) {
                pendingRemoteIce.add(new IceCandidate(e.mid, e.line, e.candidate));
            }
            Log.i(TAG, "acceptOffer sdpLen=" + remote.sdp.length() + " ice=" + remote.ice.size());
            pc.setRemoteDescription(new SimpleSdpObserver() {
                @Override
                public void onSetSuccess() {
                    remoteDescriptionSet = true;
                    flushRemoteIce();
                    pc.createAnswer(new SimpleSdpObserver() {
                        @Override
                        public void onCreateSuccess(final SessionDescription sdp) {
                            Log.i(TAG, "answer created");
                            pc.setLocalDescription(new SimpleSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    localSignal.sdp = sdp.description;
                                    Log.i(TAG, "answer localDescription set");
                                }
                            }, sdp);
                        }

                        @Override
                        public void onCreateFailure(String s) {
                            fail("创建 answer 失败: " + s);
                        }
                    }, new MediaConstraints());
                }
            }, new SessionDescription(SessionDescription.Type.OFFER, remote.sdp));

            deadline = System.currentTimeMillis() + com.jiuyue.lanmesh.core.Const.P2P_CONNECT_TIMEOUT_MS;
            main.postDelayed(timeout, com.jiuyue.lanmesh.core.Const.P2P_CONNECT_TIMEOUT_MS);
            main.postDelayed(signalFallback, 10000);
            main.post(countdown);
        } catch (Throwable t) {
            fail("接受邀请失败: " + t.getMessage());
        }
    }

    public void applyAnswer(String code) {
        P2pSignal remote = P2pSignal.decode(code);
        if (remote == null || !remote.isValid() || !P2pSignal.TYPE_ANSWER.equals(remote.type)) {
            fail("应答码无效或已损坏");
            return;
        }
        if (pc == null) {
            fail("会话已关闭");
            return;
        }
        pc.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                remoteDescriptionSet = true;
                flushRemoteIce();
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, remote.sdp));
        for (P2pSignal.IceEntry e : remote.ice) {
            pendingRemoteIce.add(new IceCandidate(e.mid, e.line, e.candidate));
        }
    }

    private void flushRemoteIce() {
        if (!remoteDescriptionSet || pc == null) return;
        for (IceCandidate c : pendingRemoteIce) {
            try {
                pc.addIceCandidate(c);
            } catch (Throwable ignored) {
            }
        }
        pendingRemoteIce.clear();
    }

    private void createPeerConnection(Context ctx) {
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers());
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        pc = factory.createPeerConnection(cfg, new SimpleObserver());
        if (pc == null) throw new IllegalStateException("createPeerConnection 返回 null");
    }

    private void registerDataChannel(final DataChannel channel) {
        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
                synchronized (sendLock) {
                    sendLock.notifyAll();
                }
            }

            @Override
            public void onStateChange() {
                DataChannel.State st = channel.state();
                if (st == DataChannel.State.OPEN) {
                    connected = true;
                    main.removeCallbacks(timeout);
                    main.removeCallbacks(countdown);
                    if (callback != null) callback.onConnected();
                } else if (st == DataChannel.State.CLOSED && !closed) {
                    if (callback != null) callback.onClosed("连接已关闭");
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                try {
                    ByteBuffer data = buffer.data;
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    if (bytes.length < 5) return;
                    int len = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                            | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
                    if (len < 0 || len > bytes.length - 5) return;
                    int type = bytes[4] & 0xFF;
                    byte[] payload = new byte[len];
                    System.arraycopy(bytes, 5, payload, 0, len);
                    if (callback != null) callback.onFrame(type, payload);
                } catch (Throwable t) {
                    Log.w(TAG, "onMessage parse failed", t);
                }
            }
        });
    }

    // ---------------- 发送 ----------------

    public boolean isConnected() {
        return connected && dc != null && dc.state() == DataChannel.State.OPEN;
    }

    public boolean sendFrame(int type, byte[] payload) {
        if (!isConnected()) return false;
        try {
            // 背压：SCTP 发送缓冲超过 1MB 时等待，避免内存暴涨/延迟飙升
            long waited = 0;
            synchronized (sendLock) {
                while (!closed && dc != null && dc.bufferedAmount() > HIGH_WATER) {
                    try {
                        sendLock.wait(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    waited += 200;
                    if (waited > 60000) {
                        Log.w(TAG, "send backpressure timeout");
                        return false;
                    }
                }
            }
            if (payload == null) payload = new byte[0];
            ByteBuffer buf = ByteBuffer.allocateDirect(5 + payload.length);
            buf.putInt(payload.length);
            buf.put((byte) type);
            buf.put(payload);
            buf.flip();
            return dc.send(new DataChannel.Buffer(buf, true));
        } catch (Throwable t) {
            Log.w(TAG, "send failed", t);
            return false;
        }
    }

    public boolean sendText(String text) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("text", text);
            o.put("ts", System.currentTimeMillis());
            return sendFrame(Protocol.T_TEXT, o.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 关闭 ----------------

    public void close() {
        closed = true;
        main.removeCallbacks(timeout);
        main.removeCallbacks(signalFallback);
        main.removeCallbacks(countdown);
        if (dc != null) {
            try {
                dc.unregisterObserver();
                dc.close();
                dc.dispose();
            } catch (Throwable ignored) {
            }
            dc = null;
        }
        if (pc != null) {
            try {
                pc.close();
                pc.dispose();
            } catch (Throwable ignored) {
            }
            pc = null;
        }
        connected = false;
    }

    public interface StatsCallback {
        void onStats(String text);
    }

    /** 读取当前选中的 candidate-pair：链路类型、RTT、收发字节。 */
    public void requestStats(final StatsCallback cb) {
        if (pc == null || !connected) {
            cb.onStats("未连接");
            return;
        }
        try {
            pc.getStats(new org.webrtc.RTCStatsCollectorCallback() {
                @Override
                public void onStatsDelivered(org.webrtc.RTCStatsReport report) {
                    String text = "暂无链路统计";
                    try {
                        java.util.Map<String, org.webrtc.RTCStats> all = report.getStatsMap();
                        for (org.webrtc.RTCStats st : all.values()) {
                            if (!"candidate-pair".equals(st.getType())) continue;
                            java.util.Map<String, Object> m = st.getMembers();
                            Object state = m.get("state");
                            if (!"succeeded".equals(String.valueOf(state))) continue;
                            String lt = candidateType(all, String.valueOf(m.get("localCandidateId")));
                            String rt = candidateType(all, String.valueOf(m.get("remoteCandidateId")));
                            Object rtt = m.get("currentRoundTripTime");
                            double ms = rtt instanceof Number ? ((Number) rtt).doubleValue() * 1000 : 0;
                            Object bs = m.get("bytesSent");
                            Object br = m.get("bytesReceived");
                            text = lt + " → " + rt + "  RTT " + Math.round(ms) + "ms"
                                    + "  发 " + com.jiuyue.lanmesh.core.Util.humanSize(
                                    bs instanceof Number ? ((Number) bs).longValue() : 0)
                                    + "  收 " + com.jiuyue.lanmesh.core.Util.humanSize(
                                    br instanceof Number ? ((Number) br).longValue() : 0);
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                    cb.onStats(text);
                }
            });
        } catch (Throwable t) {
            cb.onStats("统计不可用");
        }
    }

    private static String candidateType(java.util.Map<String, org.webrtc.RTCStats> all, String id) {
        try {
            org.webrtc.RTCStats st = all.get(id);
            if (st == null) return "?";
            Object t = st.getMembers().get("candidateType");
            String s = t == null ? "?" : String.valueOf(t);
            if ("host".equals(s)) return "直连";
            if ("srflx".equals(s)) return "P2P";
            if ("relay".equals(s)) return "中继";
            return s;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void fail(String msg) {
        if (closed) return;
        close();
        if (callback != null) callback.onError(msg);
    }

    private void emitLocalSignal() {
        if (signalEmitted || closed) return;
        if (localSignal == null || localSignal.sdp == null || localSignal.sdp.length() == 0) return;
        signalEmitted = true;
        main.removeCallbacks(signalFallback);
        Log.i(TAG, "emit signal type=" + localSignal.type + " sdpLen=" + localSignal.sdp.length()
                + " ice=" + localSignal.ice.size());
        if (callback != null) callback.onLocalSignal(localSignal);
    }

    // ---------------- 回调适配 ----------------

    private class SimpleObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                fail("打洞失败：双方可能处于对称型 NAT 或被防火墙拦截。建议 ①同一 Wi-Fi 下改用局域网传输 ②换到有 IPv6 的网络 ③切换网络后重试");
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                emitLocalSignal();
            }
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            if (localSignal != null && iceCandidate.sdp != null) {
                localSignal.ice.add(new P2pSignal.IceEntry(
                        iceCandidate.sdp, iceCandidate.sdpMid, iceCandidate.sdpMLineIndex));
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            // 被叫方：由发起方创建的数据通道
            if (dc == null) {
                dc = dataChannel;
                registerDataChannel(dc);
            }
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                Log.i(TAG, "peer connection connected");
            } else if (state == PeerConnection.PeerConnectionState.FAILED && !closed) {
                fail("连接失败（可能是网络切换或对端已关闭）");
            }
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        }
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
        }

        @Override
        public void onSetFailure(String s) {
        }
    }
}
