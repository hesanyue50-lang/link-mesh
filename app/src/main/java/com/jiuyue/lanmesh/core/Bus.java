package com.jiuyue.lanmesh.core;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简进程内事件总线。
 *
 * 为什么不用第三方库：阶段一要求零依赖、零云成本，且 API 19 上
 * LiveData/Flow 会额外引入 lifecycle 依赖。这里只需要"Service ↔ Activity"
 * 同进程通信，一个 Handler 足够，且回调统一切主线程，UI 代码不必再 post。
 */
public final class Bus {

    public static final String PEERS_CHANGED = "peers_changed";
    public static final String PEER_UPDATED = "peer_updated";
    public static final String MESSAGE_NEW = "message_new";
    public static final String MESSAGE_UPDATED = "message_updated";
    public static final String TRUST_REQUEST = "trust_request";
    public static final String TRANSFER_STATE = "transfer_state";
    public static final String MANUAL_RESULT = "manual_result";
    public static final String SERVICE_STATE = "service_state";
    public static final String TOAST = "toast";
    public static final String P2P_STATE = "p2p_state";
    public static final String P2P_DETAIL = "p2p_detail";
    public static final String P2P_TEXT = "p2p_text";
    public static final String P2P_LOG = "p2p_log";
    public static final String PORT_MAP = "port_map";
    public static final String FILE_REQUEST = "file_request";

    public interface Listener {
        void onEvent(String type, Object payload);
    }

    private static final List<Listener> LISTENERS = new ArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bus() {}

    public static void register(Listener l) {
        if (l == null) return;
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(l)) LISTENERS.add(l);
        }
    }

    public static void unregister(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.remove(l);
        }
    }

    /** 可在任意线程调用；监听器一定在主线程被回调。 */
    public static void post(final String type, final Object payload) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                List<Listener> snapshot;
                synchronized (LISTENERS) {
                    snapshot = new ArrayList<Listener>(LISTENERS);
                }
                for (Listener l : snapshot) {
                    try {
                        l.onEvent(type, payload);
                    } catch (Throwable ignored) {
                        // 单个监听器异常不能影响其他监听器
                    }
                }
            }
        });
    }

    public static void toast(String msg) {
        post(TOAST, msg);
    }
}
