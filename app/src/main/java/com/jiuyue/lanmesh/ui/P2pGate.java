package com.jiuyue.lanmesh.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.Const;

/**
 * P2P 入口门禁（点击时执行，不在启动时拦截整个 App）。
 *
 * 顺序：
 *   1) SDK_INT < MIN_P2P_API → 弹"系统版本过低"（唯一按钮"知道了"），
 *      调用方不切换页面，局域网功能完全不受影响；
 *   2) >= 21 → 运行时探测 WebRTC（类加载 + initialize）；
 *   3) 探测通过 → P2P 页显示"能力探测通过"；失败 → 明确提示，不崩溃、不静默。
 *
 * MIN_P2P_API 只在此处与 Const 中出现，文案通过参数渲染，保证版本判断与
 * 提示文案永远一致。
 */
public final class P2pGate {

    private P2pGate() {}

    public interface ProbeCallback {
        /** failureReason == null 表示通过。 */
        void onResult(String failureReason);
    }

    /** 版本门禁。返回 false 表示调用方应停留在当前页面。 */
    public static boolean versionAllowed(Activity activity) {
        if (Build.VERSION.SDK_INT >= Const.MIN_P2P_API) return true;
        Ui.alert(activity,
                activity.getString(R.string.p2p_low_version_title),
                activity.getString(R.string.p2p_low_version_msg, com.jiuyue.lanmesh.core.Util.systemVersion()),
                activity.getString(R.string.know),
                null);
        return false;
    }

    /** 后台探测，回调在主线程。 */
    public static void probeAsync(final android.app.Activity activity, final ProbeCallback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String failure = probe(activity);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onResult(failure);
                    }
                });
            }
        }, "lanmesh-p2p-probe").start();
    }

    /**
     * 真正的能力探测。阶段一没有打包 org.webrtc，因此必然返回失败原因；
     * 阶段二接入 WebRTC 后同一段代码会走到 initialize() 成功分支。
     */
    public static String probe(android.content.Context context) {
        try {
            Class<?> factory = Class.forName("org.webrtc.PeerConnectionFactory");
            try {
                Class<?> options = Class.forName("org.webrtc.PeerConnectionFactory$InitializationOptions");
                Class<?> builder = Class.forName("org.webrtc.PeerConnectionFactory$InitializationOptions$Builder");
                // WebRTC 125: InitializationOptions.builder(Context) 是必需入口
                Object b = options.getMethod("builder", android.content.Context.class)
                        .invoke(null, context.getApplicationContext());
                Object opts = builder.getMethod("createInitializationOptions").invoke(b);
                factory.getMethod("initialize", options).invoke(null, opts);
                return null;
            } catch (Throwable initError) {
                return "WebRTC 初始化失败：" + initError.getClass().getSimpleName()
                        + (initError.getMessage() == null ? "" : " " + initError.getMessage());
            }
        } catch (ClassNotFoundException e) {
            return "当前安装包未集成 WebRTC 运行库（阶段一仅局域网）";
        } catch (Throwable t) {
            return "WebRTC 不可用：" + t.getClass().getSimpleName();
        }
    }
}
