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
