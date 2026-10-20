package com.jiuyue.lanmesh;

import androidx.multidex.MultiDexApplication;
import android.content.Intent;

import androidx.appcompat.app.AppCompatDelegate;

import com.jiuyue.lanmesh.core.AppCore;
import com.jiuyue.lanmesh.net.NetService;

/**
 * 入口：初始化单例、应用主题偏好、拉起发现服务。
 *
 * 主题在 Application 里应用，保证冷启动第一帧就是正确明度，
 * 不会出现"先亮一下再变暗"的闪烁（API 19 低端机上尤其明显）。
 */
public class App extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        AppCore core = AppCore.get(this);

        int mode = core.prefs.themeMode();
        int night = mode == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        try {
            AppCompatDelegate.setDefaultNightMode(night);
        } catch (Throwable ignored) {
        }

        try {
            startService(new Intent(this, NetService.class));
        } catch (Throwable ignored) {
            // 极端情况下（系统限制后台服务）主界面仍可打开，
            // 由 MainActivity 在 onResume 再尝试一次。
        }
    }
}
