package com.jiuyue.lanmesh.core;

import android.content.Context;

import com.jiuyue.lanmesh.model.MessageStore;
import com.jiuyue.lanmesh.net.NetService;
import com.jiuyue.lanmesh.net.PeerRegistry;

/**
 * 进程级单例：把"不依赖 Service 生命周期"的数据集中在一处。
 *
 * UI 可以在 Service 还没起来时就读到 deviceId / 历史消息 / 信任列表，
 * 避免出现空白页或 NPE；Service 只负责网络部分。
 */
public final class AppCore {

    private static AppCore instance;

    /** 进程内"上次所在页"：主题切换会 recreate，静态字段比异步写盘更可靠。 */
    public static int lastTabMemory = -1;

    /** 主题切换后新 Activity 做一次淡入，形成"颜色过渡"而不是硬切。 */
    public static boolean themeFadeIn = false;

    public final Prefs prefs;
    public final MessageStore messages;
    public final PeerRegistry peers;

    private NetService service;

    private AppCore(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.prefs = new Prefs(app);
        this.messages = new MessageStore(app);
        this.peers = new PeerRegistry(prefs);
        // 放后台：启动时可能有几十个会话文件，别卡主线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                recoverStaleTransfers();
            }
        }, "lanmesh-recover").start();
    }

    /**
     * 进程被杀后，处于 sending/receiving/paused 的消息已经没有任何传输任务了，
     * 界面会永远停在"已暂停 45%"。启动时统一标成失败，用户可以重新发送。
     */
    private void recoverStaleTransfers() {
        try {
            for (String pid : messages.peerIds()) {
                boolean dirty = false;
                java.util.List<com.jiuyue.lanmesh.model.ChatMessage> list = messages.load(pid);
                for (com.jiuyue.lanmesh.model.ChatMessage m : list) {
                    if (com.jiuyue.lanmesh.model.ChatMessage.TYPE_FILE.equals(m.type)
                            && (com.jiuyue.lanmesh.model.ChatMessage.ST_SENDING.equals(m.status)
                            || com.jiuyue.lanmesh.model.ChatMessage.ST_RECEIVING.equals(m.status)
                            || com.jiuyue.lanmesh.model.ChatMessage.ST_PAUSED.equals(m.status))) {
                        m.status = com.jiuyue.lanmesh.model.ChatMessage.ST_FAILED;
                        m.error = "传输中断（App 已重启）";
                        m.speed = 0;
                        messages.update(m);
                        dirty = true;
                    }
                }
                if (dirty) {
                    android.util.Log.i("LanMeshCore", "recovered stale transfers for " + pid);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("LanMeshCore", "recover stale transfers failed", t);
        }
    }

    public static synchronized AppCore get(Context ctx) {
        if (instance == null) instance = new AppCore(ctx);
        return instance;
    }

    public synchronized void attachService(NetService s) {
        this.service = s;
    }

    public synchronized NetService service() {
        return service;
    }
}
