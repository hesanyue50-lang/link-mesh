package com.jiuyue.lanmesh.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * 接系统的「分享」入口：从相册、文件管理器、其它 App 把文件分享到 Link Mesh。
 *
 * 职责只有一件事——把 content:// 流落成我们自己的临时文件，然后拉主界面。
 * 为什么要落盘：分享给的 URI 权限是跟着 Intent 授权走的，对方开始接收时
 * 授权大概率已经失效（尤其是相册的 content://），直接拿 URI 发会中途失败。
 * 落盘的代价是多写一份数据，但换来的是发送过程完全可靠。
 */
public class ShareReceiverActivity extends Activity {

    /** 主界面启动时扫描这个目录里的待发文件 */
    public static final String SHARE_DIR = "share_in";

    public static File shareDir(android.content.Context ctx) {
        File d = new File(ctx.getCacheDir(), SHARE_DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent it = getIntent();
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        if (it != null) {
            if (Intent.ACTION_SEND.equals(it.getAction())) {
                Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(it.getAction())) {
                ArrayList<Uri> list = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) uris.addAll(list);
            }
        }
        if (uris.isEmpty()) {
            // 纯文字分享不走这条路径
            toast("没有可发送的文件");
            finish();
            return;
        }
        toast(uris.size() > 1 ? ("正在准备 " + uris.size() + " 个文件…") : "正在准备文件…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int ok = copyAll(uris);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok == 0) {
                            toast("读取分享的文件失败");
                            finish();
                            return;
                        }
                        Intent m = new Intent(ShareReceiverActivity.this, MainActivity.class);
                        m.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        m.putExtra("from_share", true);
                        try {
                            startActivity(m);
                        } catch (Throwable ignored) {
                        }
                        finish();
                    }
                });
            }
        }, "lanmesh-share-in").start();
    }

    private int copyAll(ArrayList<Uri> uris) {
        File dir = shareDir(this);
        // 上一批没发出去的残留先清掉，避免新旧混在一起
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        }
        int ok = 0;
        for (int i = 0; i < uris.size(); i++) {
            Uri u = uris.get(i);
            String name = guessName(u, i);
            File dst = new File(dir, prefix(i) + "_" + name);
            InputStream in = null;
            OutputStream out = null;
            try {
                in = getContentResolver().openInputStream(u);
                if (in == null) continue;
                out = new FileOutputStream(dst);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
                ok++;
            } catch (Throwable t) {
                try {
                    dst.delete();
                } catch (Throwable ignored) {
                }
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignored) {
                }
                try {
                    if (out != null) out.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return ok;
    }

    /** 序号前缀保证多选时顺序稳定（文件系统不保证 listFiles 的顺序） */
    private static String prefix(int i) {
        String s = String.valueOf(i);
        while (s.length() < 3) s = "0" + s;
        return s;
    }

    private String guessName(Uri u, int index) {
        try {
            android.database.Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && n.length() > 0) {
                        c.close();
                        return sanitize(n);
                    }
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }
        String last = u == null ? null : u.getLastPathSegment();
        if (last == null || last.length() == 0) last = "shared_" + index;
        int slash = last.lastIndexOf('/');
        if (slash >= 0) last = last.substring(slash + 1);
        return sanitize(last);
    }

    private static String sanitize(String n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?'
                    || ch == '"' || ch == '<' || ch == '>' || ch == '|' || ch < 0x20) {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        String out = sb.toString().trim();
        if (out.length() == 0) out = "shared";
        if (out.length() > 120) {
            int dot = out.lastIndexOf('.');
            if (dot > 0 && out.length() - dot <= 10) {
                out = out.substring(0, 110) + out.substring(dot);
            } else {
                out = out.substring(0, 120);
            }
        }
        return out;
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
