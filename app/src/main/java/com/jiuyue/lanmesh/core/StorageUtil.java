package com.jiuyue.lanmesh.core;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * 下载目录策略。
 *
 * Android 10（API 29）起，应用不能直接写公共 Download 目录（Scoped Storage），
 * 所以：
 *  - API 19-28：可以选「系统下载」（Download/LanMesh），文件管理器直接可见；
 *  - API 29+：仍然写应用专属目录（getExternalFilesDir），系统下载选项不生效，
 *    设置页会明确提示，避免用户以为文件丢了。
 */
public final class StorageUtil {

    private StorageUtil() {}

    public static File downloadDir(Context ctx) {
        Prefs prefs = AppCore.get(ctx).prefs;
        if ("public".equals(prefs.downloadDir()) && Build.VERSION.SDK_INT <= 28) {
            File d = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "LanMesh");
            if (!d.exists()) d.mkdirs();
            if (d.isDirectory()) return d;
        }
        File d = ctx.getExternalFilesDir("LanMesh");
        if (d == null) d = new File(ctx.getFilesDir(), "LanMesh");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static boolean publicDirAvailable(Context ctx) {
        return Build.VERSION.SDK_INT <= 28;
    }
}
