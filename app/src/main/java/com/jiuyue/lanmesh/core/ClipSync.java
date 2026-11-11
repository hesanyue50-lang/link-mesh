package com.jiuyue.lanmesh.core;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 系统剪贴板监听：只做「监听 + 防抖 + 去重 + 回调」，不负责发送。
 *
 * 设计要点：
 *  - 全部状态都是静态的，start/stop 不依赖 Service 或 Activity 的生命周期，
 *    谁先谁后、谁先死都不影响另一侧（用 application context，不持有 Activity）。
 *  - ClipboardManager 的注册/移除/读取都走主线程：部分 ROM（含 vivo）对
 *    非 UI 线程访问剪贴板会抛异常或直接返回 null。
 *  - 收到变化后延迟 {@link #DEBOUNCE_MS} 再读：输入法"先 setPrimaryClip(null)
 *    再写新内容"会产生两次回调，立即读只能拿到半截数据。
 *  - 同一内容 {@link #MIN_INTERVAL_MS} 内只回调一次，避免监听到自己写进去的内容
 *    形成回环。真正"自己写"的场景用 {@link #suppressOnce} 精确屏蔽一次。
 */
public class ClipSync {

    /** 回调接口：只在主线程触发。 */
    public interface Listener {
        void onClipboardText(String text);
    }

    private static final String TAG = "LanMeshClip";

    /** 变化后延迟读取的时长。 */
    private static final long DEBOUNCE_MS = 600L;
    /** 同一内容的最短回调间隔。 */
    private static final long MIN_INTERVAL_MS = 2000L;
    /** 超过这个长度不参与同步：大段文本应该走文件通道，而不是悄悄复制出去。 */
    private static final int MAX_LEN = 4000;
    /** 等待主线程完成注册/注销的上限，防止调用方卡住。 */
    private static final long MAIN_WAIT_MS = 2000L;

    private static final Object LOCK = new Object();

    private static ClipboardManager clipboard;
    private static ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private static Handler handler;
    private static Listener listener;
    private static Context appContext;

    /** 自己写剪贴板时登记的内容；下一次监听到相同内容直接丢弃（防回环）。 */
    private static String suppressed;
    private static String lastText;
    private static long lastDeliverAt;

    /** 供 isRunning 无锁读取。 */
    private static volatile boolean running;

    private ClipSync() {
    }

    /**
     * 开始监听。幂等：已在运行时只替换监听器，不重复注册。
     *
     * @return true=已处于监听状态；false=失败（无剪贴板服务 / 注册被拒）
     */
    public static boolean start(Context ctx, Listener l) {
        if (ctx == null || l == null) return false;
        if (running) {
            // 幂等分支：不重复注册，但允许换监听器
            synchronized (LOCK) {
                if (running) {
                    listener = l;
                    return true;
                }
            }
        }
        final Context app = appContextOf(ctx);
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] result = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = doStart(app, l, h);
                } catch (Throwable t) {
                    Log.w(TAG, "start failed", t);
                } finally {
                    latch.countDown();
                }
            }
        };
        if (isMainThread()) {
            task.run();
        } else {
            h.post(task);
            try {
                if (!latch.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "start timeout waiting for main thread");
                    return running;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return running;
            }
        }
        return result[0];
    }

    /** 停止监听并清空待处理的延迟任务。重复调用安全。 */
    public static void stop(Context ctx) {
        final ClipboardManager cm;
        final ClipboardManager.OnPrimaryClipChangedListener cb;
        final Handler h;
        synchronized (LOCK) {
            cm = clipboard;
            cb = clipListener;
            h = handler;
            resetLocked();
        }
        if (cm == null && cb == null && h == null) return;

        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (h != null) h.removeCallbacksAndMessages(null);
                } catch (Throwable ignored) {
                }
                try {
                    if (cm != null && cb != null) cm.removePrimaryClipChangedListener(cb);
                } catch (Throwable t) {
                    Log.w(TAG, "remove listener failed", t);
                }
            }
        };
        if (isMainThread()) {
            task.run();
        } else if (h != null) {
            h.post(task);
        } else {
            task.run();
        }
        Log.i(TAG, "clipboard listener stopped");
    }

    /** 是否正在监听。 */
    public static boolean isRunning(Context ctx) {
        return running;
    }

    /**
     * 我们自己往剪贴板写内容时调用：下一次监听到相同文本直接丢弃，不计入去重窗口。
     * 只屏蔽一次，避免把用户随后真正的复制也一起吞掉。
     */
    public static void suppressOnce(Context ctx, String text) {
        if (text == null) return;
        synchronized (LOCK) {
            suppressed = text;
            lastText = text;
            lastDeliverAt = SystemClock.uptimeMillis();
        }
    }

    // ---------------- 内部实现（以下方法都在主线程执行） ----------------

    private static boolean doStart(Context app, Listener l, Handler h) {
        synchronized (LOCK) {
            if (running) {
                listener = l;
                return true;
            }
            ClipboardManager cm = null;
            try {
                Object svc = app.getSystemService(Context.CLIPBOARD_SERVICE);
                if (svc instanceof ClipboardManager) cm = (ClipboardManager) svc;
            } catch (Throwable t) {
                Log.w(TAG, "clipboard service unavailable", t);
            }
            if (cm == null) {
                Log.w(TAG, "no ClipboardManager, clip sync disabled");
                return false;
            }
            ClipboardManager.OnPrimaryClipChangedListener cb =
                    new ClipboardManager.OnPrimaryClipChangedListener() {
                        @Override
                        public void onPrimaryClipChanged() {
                            onClipChanged();
                        }
                    };
            try {
                cm.addPrimaryClipChangedListener(cb);
            } catch (Throwable t) {
                Log.w(TAG, "add listener failed", t);
                return false;
            }
            appContext = app;
            clipboard = cm;
            handler = h;
            listener = l;
            clipListener = cb;
            running = true;
            Log.i(TAG, "clipboard listener started");
            return true;
        }
    }

    private static final Runnable PENDING_READ = new Runnable() {
        @Override
        public void run() {
            deliverLatest();
        }
    };

    /** 剪贴板变化回调：合并 600ms 内的多次变化，只读一次最终内容。 */
    private static void onClipChanged() {
        Handler h;
        synchronized (LOCK) {
            h = handler;
        }
        if (h == null) return;
        h.removeCallbacks(PENDING_READ);
        h.postDelayed(PENDING_READ, DEBOUNCE_MS);
    }

    /**
     * 立刻读一次剪贴板。
     * Android 10 起，没有焦点窗口的应用调 getPrimaryClip() 只会拿到 null，
     * 所以"复制了但没同步"在后台是常态。回到前台时补读一次能补上大部分场景。
     */
    public static void pollNow() {
        boolean on;
        synchronized (LOCK) {
            on = listener != null && clipboard != null;
        }
        if (on) deliverLatest();
    }

    /** 防抖结束后真正读剪贴板，过滤 + 去重后回调。 */
    private static void deliverLatest() {
        ClipboardManager cm;
        Listener l;
        Context app;
        synchronized (LOCK) {
            cm = clipboard;
            l = listener;
            app = appContext;
        }
        if (cm == null || l == null) return;

        String text;
        try {
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() <= 0) return;
            ClipDescription desc = clip.getDescription();
            if (desc != null) {
                boolean plain;
                try {
                    plain = desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
                } catch (Throwable t) {
                    plain = true; // 拿不到 MIME 就当纯文本处理
                }
                if (!plain) return; // 图片 / URI / HTML 等不参与文本同步
            }
            ClipData.Item item = clip.getItemAt(0);
            if (item == null) return;
            CharSequence cs = null;
            try {
                cs = item.getText();
            } catch (Throwable ignored) {
            }
            if (cs == null && app != null) {
                try {
                    cs = item.coerceToText(app);
                } catch (Throwable ignored) {
                }
            }
            if (cs == null) return;
            text = cs.toString();
        } catch (Throwable t) {
            Log.w(TAG, "read clipboard failed", t);
            return;
        }

        if (text.length() == 0 || text.length() > MAX_LEN) return;

        long now = SystemClock.uptimeMillis();
        synchronized (LOCK) {
            if (suppressed != null) {
                if (suppressed.equals(text)) {
                    // 自己写进去的内容：清标记，且不再回调，避免回环
                    suppressed = null;
                    lastText = text;
                    lastDeliverAt = now;
                    Log.d(TAG, "suppressed self-written text");
                    return;
                }
                // 标记没命中，说明中间已被别的复制覆盖，清掉别一直挂着
                suppressed = null;
            }
            if (lastText != null && lastText.equals(text) && (now - lastDeliverAt) < MIN_INTERVAL_MS) {
                return;
            }
            lastText = text;
            lastDeliverAt = now;
            l = listener;
        }
        if (l != null) {
            try {
                l.onClipboardText(text);
            } catch (Throwable t) {
                Log.w(TAG, "listener threw", t);
            }
        }
    }

    private static void resetLocked() {
        running = false;
        clipboard = null;
        clipListener = null;
        handler = null;
        listener = null;
        appContext = null;
        // suppressed / lastText 故意保留：刚写进剪贴板的内容在 stop→start
        // 之后仍可能是同一段，保留才能继续拦住回环。
    }

    private static Context appContextOf(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            return app != null ? app : ctx;
        } catch (Throwable t) {
            return ctx;
        }
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
