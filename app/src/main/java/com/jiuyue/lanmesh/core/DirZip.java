package com.jiuyue.lanmesh.core;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把 SAF 选中的整个目录打包成 zip。
 *
 * 为什么用 zip 而不是自己定一套目录清单协议：接收端拿到的是普通文件，
 * 断点续传、校验、历史记录、转发全都能直接复用，不需要在帧协议里
 * 增加新类型，也不会让老版本客户端解析失败。
 *
 * 中文文件名靠 ZipOutputStream 的 UTF-8 条目名（Java 7 起默认），
 * 接收端用系统解压工具打开即可，不会乱码。
 */
public class DirZip {

    public interface Progress {
        /** 已写入字节数、当前处理的文件名 */
        void onProgress(long bytes, String name);
    }

    /**
     * @param treeUri ACTION_OPEN_DOCUMENT_TREE 拿到的目录 URI
     * @param out     目标 zip 文件
     * @return 成功返回文件大小，失败抛异常
     */
    public static long pack(Context ctx, Uri treeUri, File out, Progress cb) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        String rootName = queryName(cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId));
        if (rootName == null || rootName.length() == 0) rootName = "folder";
        rootName = safe(rootName);

        ZipOutputStream zos = null;
        long[] written = new long[]{0};
        try {
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 64 * 1024));
            zos.setLevel(6);
            walk(cr, treeUri, rootId, rootName + "/", zos, cb, written);
            zos.finish();
        } finally {
            try {
                if (zos != null) zos.close();
            } catch (Throwable ignored) {
            }
        }
        return out.length();
    }

    private static void walk(ContentResolver cr, Uri treeUri, String docId, String prefix,
                             ZipOutputStream zos, Progress cb, long[] written) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        String[] cols = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        Cursor c = null;
        try {
            c = cr.query(children, cols, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (id == null) continue;
                if (name == null || name.length() == 0) name = "unnamed";
                name = safe(name);
                String path = prefix + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    zos.putNextEntry(new ZipEntry(path + "/"));
                    zos.closeEntry();
                    walk(cr, treeUri, id, path + "/", zos, cb, written);
                } else {
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    InputStream in = null;
                    try {
                        in = cr.openInputStream(fileUri);
                        if (in == null) continue;
                        zos.putNextEntry(new ZipEntry(path));
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            zos.write(buf, 0, n);
                            written[0] += n;
                            if (cb != null) cb.onProgress(written[0], path);
                        }
                        zos.closeEntry();
                    } catch (Throwable t) {
                        // 单个文件读不出来不该让整包失败，跳过继续
                        try {
                            zos.closeEntry();
                        } catch (Throwable ignored) {
                        }
                    } finally {
                        try {
                            if (in != null) in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } finally {
            try {
                if (c != null) c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String queryName(ContentResolver cr, Uri docUri) {
        Cursor c = null;
        try {
            c = cr.query(docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {
        } finally {
            try {
                if (c != null) c.close();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** zip 条目里不能出现路径分隔符穿越，也不能有控制字符 */
    private static String safe(String n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch == '/' || ch == '\\' || ch < 0x20) sb.append('_');
            else sb.append(ch);
        }
        String out = sb.toString().trim();
        if (out.length() == 0) out = "unnamed";
        if (out.length() > 100) {
            // 直接砍尾部会让"前缀相同、后缀不同"的长文件名撞成同名，
            // putNextEntry 抛 duplicate entry 后被上层 catch 吞掉 → 文件静默丢失。
            // 保留尾部（后缀往往才是区分点），中间用 ~ 标记省略。
            out = out.substring(0, 60) + "~" + out.substring(out.length() - 39);
        }
        return out;
    }
}
