package com.jiuyue.lanmesh.core;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把待发送的文件复制一份到用户选的公共文件夹（SAF），
 * 再从公共文件夹发送——这样发送方也能在文件管理器里看到同一份文件。
 */
public final class PublicCopy {

    private PublicCopy() {}

    /** 返回复制后的 content:// URI；失败返回 null。 */
    public static String copyToTree(Context ctx, String treeUri, Uri source, String name) {
        InputStream in = null;
        OutputStream out = null;
        try {
            Uri tree = Uri.parse(treeUri);
            DocumentFile dir = DocumentFile.fromTreeUri(ctx, tree);
            if (dir == null || !dir.canWrite()) return null;
            DocumentFile doc = dir.createFile(mimeOf(name), name == null ? "file" : name);
            if (doc == null) return null;
            in = ctx.getContentResolver().openInputStream(source);
            if (in == null) return null;
            out = ctx.getContentResolver().openOutputStream(doc.getUri());
            if (out == null) return null;
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return doc.getUri().toString();
        } catch (Throwable t) {
            Log.w("LanMeshCopy", "copy to tree failed: " + t.getClass().getSimpleName()
                    + " " + t.getMessage(), t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    private static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
