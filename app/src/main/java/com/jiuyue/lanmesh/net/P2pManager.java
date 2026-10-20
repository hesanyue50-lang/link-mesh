package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.util.Log;

import com.jiuyue.lanmesh.core.Bus;
import com.jiuyue.lanmesh.core.Prefs;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段二的 P2P 会话管理器（进程内单例）。
 *
 * 状态机：IDLE → OFFER_READY → CONNECTING → CONNECTED / FAILED
 *          IDLE → ANSWER_READY → CONNECTING → CONNECTED / FAILED
 *
 * 信令完全手动：本端生成邀请码/应答码由用户复制给对方，不经过任何服务器。
 */
public class P2pManager {

    private static final String TAG = "LanMeshP2p";

    public static final String ST_IDLE = "idle";
    public static final String ST_OFFER_READY = "offer_ready";
    public static final String ST_ANSWER_READY = "answer_ready";
    public static final String ST_CONNECTING = "connecting";
    public static final String ST_CONNECTED = "connected";
    public static final String ST_FAILED = "failed";

    private static P2pManager instance;

    private final Context ctx;
    private final Prefs prefs;
    private WebRtcSession session;
    private String state = ST_IDLE;
    private String detail = "";
    private String localCode = "";
    private final List<String> logs = new ArrayList<String>();

    public static synchronized P2pManager get(Context c) {
        if (instance == null) instance = new P2pManager(c.getApplicationContext());
        return instance;
    }

    private P2pManager(Context c) {
        this.ctx = c;
        this.prefs = com.jiuyue.lanmesh.core.AppCore.get(c).prefs;
    }

    public String state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public String localCode() {
        return localCode;
    }

    private void setState(String s, String d) {
        state = s;
        detail = d == null ? "" : d;
        addLog("[" + state + "] " + detail);
        Bus.post(Bus.P2P_STATE, s);
        Bus.post(Bus.P2P_DETAIL, this.detail);
    }

    private void addLog(String line) {
        if (line == null) return;
        logs.add(line);
        while (logs.size() > 60) logs.remove(0);
        Bus.post(Bus.P2P_LOG, line);
    }

    /** UI 回到前台时用它恢复状态与日志（Bus 事件在后台会丢）。 */
    public List<String> logs() {
        return new ArrayList<String>(logs);
    }

    public void clearLogs() {
        logs.clear();
    }

    public void startOffer() {
        close();
        setState(ST_CONNECTING, "正在生成邀请码…");
        session = new WebRtcSession();
        session.startOffer(ctx, new SessionCallback());
    }

    public void acceptOffer(String code) {
        Log.i(TAG, "acceptOffer codeLen=" + (code == null ? 0 : code.length()));
        close();
        setState(ST_CONNECTING, "正在解析邀请码并生成应答码…");
        session = new WebRtcSession();
        session.acceptOffer(ctx, code, new SessionCallback());
    }

    public void applyAnswer(String code) {
        if (session == null) {
            setState(ST_FAILED, "请先发起连接");
            return;
        }
        setState(ST_CONNECTING, "正在应用应答码…");
        session.applyAnswer(code);
    }

    public boolean sendText(String text) {
        if (session == null || !session.isConnected()) {
            Bus.toast("P2P 尚未连接");
            return false;
        }
        boolean ok = session.sendText(text);
        if (ok) {
            addLog("我: " + text);
            Bus.post(Bus.P2P_TEXT, "我: " + text);
        }
        return ok;
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (!ST_FAILED.equals(state)) {
            setState(ST_IDLE, "");
        }
    }

    private class SessionCallback implements WebRtcSession.Callback {
        @Override
        public void onLocalSignal(P2pSignal signal) {
            localCode = signal.encode();
            Log.i(TAG, "onLocalSignal type=" + signal.type + " codeLen=" + localCode.length());
            if (LOCAL_TYPE_OFFER.equals(signal.type)) {
                setState(ST_OFFER_READY, "把邀请码发给对方，然后粘贴对方的应答码");
            } else {
                setState(ST_ANSWER_READY, "把应答码发给对方");
            }
        }

        @Override
        public void onConnected() {
            setState(ST_CONNECTED, "P2P 直连已建立");
        }

        @Override
        public void onFrame(int type, byte[] payload) {
            try {
                if (type == Protocol.T_TEXT) {
                    JSONObject o = new JSONObject(new String(payload, "UTF-8"));
                    String msg = "对方: " + o.optString("text", "");
                    addLog(msg);
                    Bus.post(Bus.P2P_TEXT, msg);
                }
            } catch (Throwable t) {
                Log.w(TAG, "frame parse failed", t);
            }
        }

        @Override
        public void onCountdown(int seconds) {
            if (seconds > 0) addLog("[倒计时] 剩余 " + seconds + " 秒");
        }

        @Override
        public void onClosed(String reason) {
            setState(ST_IDLE, reason);
        }

        @Override
        public void onError(String message) {
            setState(ST_FAILED, message);
        }
    }

    private static final String LOCAL_TYPE_OFFER = P2pSignal.TYPE_OFFER;
}
