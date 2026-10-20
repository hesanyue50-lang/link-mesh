package com.jiuyue.lanmesh.ui;

import android.Manifest;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.AppCore;
import com.jiuyue.lanmesh.core.Bus;
import com.jiuyue.lanmesh.core.Util;
import com.jiuyue.lanmesh.model.ChatMessage;
import com.jiuyue.lanmesh.model.Peer;
import com.jiuyue.lanmesh.net.NetService;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * 单设备聊天页：文本 + 文件 + 传输状态。
 *
 * 文件选择使用 ACTION_GET_CONTENT（不是 SAF 的 OPEN_DOCUMENT）：
 * API 19 上 ACTION_OPEN_DOCUMENT 的行为在部分 ROM 缺失或不可用，
 * GET_CONTENT 从 API 1 就有，覆盖最广。代价是 Uri 权限不能持久化，
 * 所以传输必须"选完立刻发"，失败时提示重新选择。
 */
public class ChatActivity extends AppCompatActivity implements Bus.Listener {

    public static final String EXTRA_PEER_ID = "peer_id";

    private static final int REQ_PICK_FILE = 4201;
    private static final int REQ_PICK_IMAGE = 4202;
    private static final int REQ_STORAGE = 4203;

    private AppCore core;
    private String peerId;
    private Peer peer;
    private MessageAdapter adapter;
    private ListView listView;
    private EditText input;
    private TextView status;

    private String pendingMime;
    private final Queue<NetService.TrustRequest> trustQueue = new LinkedList<NetService.TrustRequest>();
    private boolean trustShowing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        core = AppCore.get(this);
        Ui.applyEdgeToEdge(this, findViewById(R.id.chat_root));
        peerId = getIntent().getStringExtra(EXTRA_PEER_ID);
        peer = core.peers.get(peerId);
        if (peer == null) {
            // 进程被回收后从历史入口进来：至少保留可读的占位信息
            String addr = core.prefs.lastPeerAddress(peerId);
            String host = "";
            int port = com.jiuyue.lanmesh.core.Const.DEFAULT_TCP_PORT;
            if (addr != null && addr.contains(":")) {
                int i = addr.lastIndexOf(':');
                host = addr.substring(0, i);
                try {
                    port = Integer.parseInt(addr.substring(i + 1));
                } catch (Throwable ignored) {
                }
            }
            if (peerId != null) {
                peer = new Peer(peerId, null, host, port, Peer.SRC_MANUAL);
            }
        }

        status = (TextView) findViewById(R.id.tv_status);
        input = (EditText) findViewById(R.id.et_input);
        listView = (ListView) findViewById(R.id.list_messages);
        adapter = new MessageAdapter(this);
        adapter.setPeer(peer);
        listView.setAdapter(adapter);
        if (peer != null) {
            ((TextView) findViewById(R.id.tv_peer_name)).setText(peer.displayName());
            adapter.setData(core.messages.load(peer.deviceId));
        }

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_send).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendText();
            }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAttachSheet();
            }
        });
        findViewById(R.id.btn_chat_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChatMenu();
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = adapter.getItem(position);
                if (o instanceof ChatMessage) showMessageMenu((ChatMessage) o);
                return true;
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = adapter.getItem(position);
                if (o instanceof ChatMessage && ((ChatMessage) o).isFile()) {
                    openFile((ChatMessage) o);
                }
            }
        });

        updateStatus();
        scrollToBottom();

        // 键盘弹出时 root 高度会变（IME inset 参与 padding），跟随滚动到底部
        final View root = findViewById(R.id.chat_root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            private int lastHeight = -1;

            @Override
            public void onGlobalLayout() {
                int h = root.getHeight();
                if (h != lastHeight) {
                    lastHeight = h;
                    scrollToBottom();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bus.register(this);
        updateStatus();
    }

    @Override
    protected void onPause() {
        Bus.unregister(this);
        super.onPause();
    }

    private void updateStatus() {
        if (status == null) return;
        boolean online = peer != null && peer.isOnline(System.currentTimeMillis());
        String mode = online ? getString(R.string.status_direct) : getString(R.string.status_offline);
        String id = peer == null ? "--------" : peer.shortId();
        status.setText(mode + "  " + id + (peer != null && peer.trusted ? "" : "  ·  未信任"));
        status.setTextColor(getResources().getColor(online ? R.color.accent : R.color.text_faint));
        if (peer != null) {
            ((TextView) findViewById(R.id.tv_peer_name)).setText(peer.displayName());
        }
    }

    /** 新消息轻轻的"上浮 + 淡入"，避免列表硬跳。 */
    private void animateLastItem() {
        listView.postDelayed(new Runnable() {
            @Override
            public void run() {
                int last = listView.getChildCount() - 1;
                if (last < 0) return;
                View v = listView.getChildAt(last);
                if (v == null) return;
                v.setAlpha(0f);
                v.setTranslationY(Ui.dp(ChatActivity.this, 18));
                v.animate().alpha(1f).translationY(0f).setDuration(220).start();
            }
        }, 40);
    }

    private void scrollToBottom() {
        if (adapter.getCount() == 0) return;
        listView.setSelection(adapter.getCount() - 1);
    }

    // ---------------- 文本 ----------------

    private void sendText() {
        String text = input.getText().toString();
        if (TextUtils.isEmpty(text) || peer == null) return;
        if (text.length() > com.jiuyue.lanmesh.core.Const.MAX_TEXT_CHARS) {
            Ui.toast(this, "消息过长，最多 " + com.jiuyue.lanmesh.core.Const.MAX_TEXT_CHARS + " 字");
            return;
        }
        ChatMessage m = new ChatMessage();
        m.peerDeviceId = peer.deviceId;
        m.direction = ChatMessage.DIR_OUT;
        m.type = ChatMessage.TYPE_TEXT;
        m.text = text;
        m.status = ChatMessage.ST_SENDING;
        core.messages.append(m);
        adapter.add(m);
        input.setText("");
        scrollToBottom();

        NetService s = core.service();
        if (s == null) {
            m.status = ChatMessage.ST_FAILED;
            m.error = "服务未启动";
            core.messages.update(m);
            adapter.update(m);
            return;
        }
        s.sendText(peer, m);
    }

    // ---------------- 文件 ----------------

    private void showAttachSheet() {
        Ui.sheet(this, new String[]{
                getString(R.string.pick_file),
                getString(R.string.pick_image),
                "发送本应用安装包",
                getString(R.string.manual_add_short)
        }, new Ui.OnClick[]{
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        pickWithPermission("*/*", REQ_PICK_FILE);
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        pickWithPermission("image/*", REQ_PICK_IMAGE);
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        sendOwnApk();
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        manualAdd();
                    }
                }
        });
    }

    /**
     * 存储权限分支：
     *  - API 19-22：安装即授予，绝不弹运行时权限（需求明确要求）；
     *  - API 23-32：首次选文件时请求 READ_EXTERNAL_STORAGE；
     *  - API 33+：存储权限模型已拆分（READ_MEDIA_*），而 GET_CONTENT 本身不需要权限，
     *    因此不请求，避免"请求一个根本用不上的权限"。
     */
    private void pickWithPermission(String mime, int req) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingMime = mime;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        launchPicker(mime, req);
    }

    private void launchPicker(String mime, int req) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType(mime);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, getString(R.string.pick_file)), req);
        } catch (ActivityNotFoundException e) {
            Ui.alert(this, getString(R.string.pick_file), "系统没有文件选择器", getString(R.string.know), null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            // 即使用户拒绝，仍然允许通过 GET_CONTENT 选择（多数 ROM 的选择器不受该权限限制），
            // 只是某些旧 ROM 可能返回不可读的 Uri —— 由发送失败提示兜底。
            String mime = pendingMime == null ? "*/*" : pendingMime;
            int req = "image/*".equals(mime) ? REQ_PICK_IMAGE : REQ_PICK_FILE;
            pendingMime = null;
            if (!granted) Ui.toast(this, "未授予读取权限，仍可尝试通过系统选择器选择");
            launchPicker(mime, req);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode != REQ_PICK_FILE && requestCode != REQ_PICK_IMAGE) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String name = null;
        long size = 0;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (name == null) name = "file-" + System.currentTimeMillis();
        if (size <= 0) {
            // 少数 ContentProvider 不返回 SIZE：读取流长度兜底，无法取得则置 0 后由发送端报错
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    size = in.available();
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
        sendFile(uri, name, size);
    }

    private void sendFile(Uri uri, String name, long size) {
        sendFile(uri, name, size, null);
    }

    private void sendFile(Uri uri, String name, long size, String localPath) {
        if (peer == null) return;
        if (size <= 0) {
            Ui.alert(this, getString(R.string.pick_file),
                    "无法读取文件大小，请换一个文件或使用系统文件管理器分享到本应用",
                    getString(R.string.know), null);
            return;
        }
        ChatMessage m = new ChatMessage();
        m.peerDeviceId = peer.deviceId;
        m.direction = ChatMessage.DIR_OUT;
        m.type = ChatMessage.TYPE_FILE;
        m.fileId = UUID.randomUUID().toString();
        m.fileName = Util.safeFileName(name);
        m.fileSize = size;
        m.status = ChatMessage.ST_SENDING;
        m.sourceUri = uri == null ? null : uri.toString();
        m.sourcePath = localPath;
        core.messages.append(m);
        adapter.add(m);
        scrollToBottom();

        NetService s = core.service();
        if (s == null) {
            m.status = ChatMessage.ST_FAILED;
            m.error = "服务未启动";
            core.messages.update(m);
            adapter.update(m);
            return;
        }
        s.sendFile(peer, m, uri, localPath);
    }

    private void openFile(ChatMessage m) {
        // 发送方：文件还在本机，直接打开原文件/原始 Uri（之前会误报"尚未接收完成"）
        if (m.isOutgoing()) {
            if (m.sourcePath != null && new File(m.sourcePath).isFile()) {
                openLocalFile(new File(m.sourcePath), m.fileName);
                return;
            }
            if (m.sourceUri != null) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(m.sourceUri), guessMime(m.fileName));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        if (m.localPath == null || m.localPath.length() == 0) {
            Ui.toast(this, "文件尚未接收完成");
            return;
        }
        if (m.localPath.startsWith("content://")) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(m.localPath), guessMime(m.fileName));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
                return;
            } catch (Throwable t) {
                Ui.toast(this, "没有可打开该文件的应用");
                return;
            }
        }
        File f = new File(m.localPath);
        if (!f.isFile()) {
            Ui.toast(this, "文件已被移动或删除");
            return;
        }
        openLocalFile(f, m.fileName);
    }

    private void openLocalFile(File f, String name) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, guessMime(name));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            Ui.alert(this, "无法打开", "没有可处理该文件类型的应用\n" + f.getAbsolutePath(),
                    getString(R.string.know), null);
        }
    }

    /** 清空聊天记录时，把该会话里接收到的文件一起删掉（避免长期堆积）。 */
    private void clearChatWithFiles() {
        if (peer == null) return;
        for (ChatMessage m : core.messages.load(peer.deviceId)) {
            try {
                if (m.localPath == null || m.localPath.length() == 0) continue;
                if (m.localPath.startsWith("content://")) {
                    getContentResolver().delete(Uri.parse(m.localPath), null, null);
                } else {
                    File f = new File(m.localPath);
                    if (f.isFile()) f.delete();
                }
            } catch (Throwable ignored) {
            }
        }
        core.messages.clear(peer.deviceId);
        adapter.setData(null);
    }

    private String guessMime(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "*/*";
    }

    // ---------------- 菜单 ----------------

    private void sendOwnApk() {
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(getPackageName(), 0);
            File src = new File(ai.sourceDir);
            File dst = new File(getCacheDir(), "LanMesh-" + versionName() + ".apk");
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            sendFile(null, dst.getName(), dst.length(), dst.getAbsolutePath());
            Ui.toast(this, "正在发送安装包");
        } catch (Throwable t) {
            Ui.toast(this, "发送安装包失败: " + t.getMessage());
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.1.0";
        }
    }

    private void showMessageMenu(final ChatMessage m) {
        if (m.isFile()) {
            showFileSheet(m);
        } else {
            showTextSheet(m);
        }
    }

    /** 文件长按弹窗：文件名 / 路径 / 在文件管理中打开 / 删除文件和记录，点外部取消。 */
    private void showFileSheet(final ChatMessage m) {
        final Dialog d = new Dialog(this, R.style.DarkDialogTheme);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_file);
        d.setCanceledOnTouchOutside(true);
        ((TextView) d.findViewById(R.id.dlg_file_name)).setText(m.fileName);
        String path = (m.localPath != null && m.localPath.length() > 0)
                ? m.localPath : "文件尚未接收完成";
        ((TextView) d.findViewById(R.id.dlg_file_path)).setText(path);
        d.findViewById(R.id.dlg_file_open).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                openInFileManager(m);
            }
        });
        d.findViewById(R.id.dlg_file_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                confirmDeleteFile(m);
            }
        });
        d.findViewById(R.id.dlg_file_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        Ui.animateDialogIn(d, false);
    }

    private void showTextSheet(final ChatMessage m) {
        Ui.sheet(this, new String[]{"复制", "删除"}, new Ui.OnClick[]{
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", Util.safe(m.text)));
                            Ui.toast(ChatActivity.this, "已复制");
                        }
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        if (peer == null) return;
                        core.messages.remove(peer.deviceId, m.msgId);
                        adapter.setData(core.messages.load(peer.deviceId));
                    }
                }
        });
    }

    private void openInFileManager(ChatMessage m) {
        if (m.localPath == null || m.localPath.length() == 0) {
            Ui.toast(this, "文件尚未接收完成");
            return;
        }
        File f = new File(m.localPath);
        if (!f.isFile()) {
            Ui.toast(this, "文件已被移动或删除");
            return;
        }
        // 用系统 DocumentsUI 直接打开"所在目录"，这是真正的"在文件管理中打开"
        String abs = f.getAbsolutePath();
        String prefix = "/storage/emulated/0/";
        if (Build.VERSION.SDK_INT < 21) {
            openFile(m);
            return;
        }
        if (abs.startsWith(prefix)) {
            String rel = abs.substring(prefix.length());
            int slash = rel.lastIndexOf('/');
            if (slash > 0) {
                try {
                    android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:" + rel.substring(0, slash));
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "vnd.android.document/directory");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        // 回退：直接打开文件本身
        openFile(m);
    }

    private void confirmDeleteFile(final ChatMessage m) {
        Ui.confirm(this, "删除文件和记录",
                "将从手机中删除文件，并从聊天记录里移除：\n" + m.fileName,
                "删除", new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        try {
                            if (m.localPath != null && m.localPath.length() > 0) {
                                File f = new File(m.localPath);
                                if (f.isFile()) f.delete();
                            }
                        } catch (Throwable ignored) {
                        }
                        if (peer != null) {
                            core.messages.remove(peer.deviceId, m.msgId);
                            adapter.setData(core.messages.load(peer.deviceId));
                        }
                        Ui.toast(ChatActivity.this, "已删除");
                    }
                },
                getString(R.string.cancel), null);
    }

    private void showMessageMenuOld(final ChatMessage m) {
        if (m.isFile()) {
            boolean running = ChatMessage.ST_SENDING.equals(m.status)
                    || ChatMessage.ST_RECEIVING.equals(m.status);
            if (running) {
                Ui.confirm(this, "传输中", getString(R.string.stop_transfer) + "？",
                        getString(R.string.confirm), new Ui.OnClick() {
                            @Override
                            public void onClick() {
                                NetService s = core.service();
                                if (s != null) s.transfers().cancel(m.fileId);
                                m.status = ChatMessage.ST_CANCELLED;
                                core.messages.update(m);
                                adapter.update(m);
                            }
                        },
                        getString(R.string.cancel), null);
            } else if (ChatMessage.ST_FAILED.equals(m.status) && m.isOutgoing() && m.sourceUri != null) {
                Ui.confirm(this, "重试", "重新发送 " + m.fileName + "？",
                        getString(R.string.retry), new Ui.OnClick() {
                            @Override
                            public void onClick() {
                                NetService s = core.service();
                                if (s == null || peer == null) return;
                                m.status = ChatMessage.ST_SENDING;
                                m.error = null;
                                m.progress = 0;
                                core.messages.update(m);
                                adapter.update(m);
                                s.sendFile(peer, m, Uri.parse(m.sourceUri), m.sourcePath);
                            }
                        },
                        getString(R.string.cancel), null);
            } else if (ChatMessage.ST_DONE.equals(m.status) && !m.isOutgoing()) {
                Ui.confirm(this, m.fileName, "已保存到：\n" + m.localPath,
                        "打开", new Ui.OnClick() {
                            @Override
                            public void onClick() {
                                openFile(m);
                            }
                        },
                        "复制路径", new Ui.OnClick() {
                            @Override
                            public void onClick() {
                                android.content.ClipboardManager cm =
                                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", m.localPath));
                                    Ui.toast(ChatActivity.this, "已复制");
                                }
                            }
                        });
            }
        } else {
            Ui.confirm(this, "文本消息", m.text,
                    "复制", new Ui.OnClick() {
                        @Override
                        public void onClick() {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", Util.safe(m.text)));
                                Ui.toast(ChatActivity.this, "已复制");
                            }
                        }
                    },
                    getString(R.string.cancel), null);
        }
    }

    private void showChatMenu() {
        if (peer == null) return;
        final boolean trusted = core.prefs.isTrusted(peer.deviceId);
        Ui.confirm(this, getString(R.string.trust_title),
                "设备：" + peer.displayName() + "\n短 ID：" + peer.shortId() + "\n地址：" + peer.address()
                        + "\n信任状态：" + (trusted ? "已信任" : "未信任"),
                trusted ? "已信任该设备（点击取消信任）" : "信任该设备", new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        if (trusted) {
                            core.prefs.untrust(peer.deviceId);
                            peer.trusted = false;
                        } else {
                            core.prefs.trust(peer.deviceId);
                            peer.trusted = true;
                        }
                        updateStatus();
                    }
                },
                "清空聊天记录", new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        Ui.confirm(ChatActivity.this, "清空聊天记录",
                                "仅删除本机保存的记录，不影响对方。",
                                "清空", new Ui.OnClick() {
                                    @Override
                                    public void onClick() {
                                        clearChatWithFiles();
                                    }
                                },
                                getString(R.string.cancel), null);
                    }
                });
    }

    private void manualAdd() {
        Ui.input(this, getString(R.string.manual_title), getString(R.string.manual_hint), "",
                new Ui.OnText() {
                    @Override
                    public void onText(String text) {
                        if (text == null || text.trim().length() == 0) return;
                        String v = text.trim();
                        String host = v;
                        int port = com.jiuyue.lanmesh.core.Const.DEFAULT_TCP_PORT;
                        int idx = v.lastIndexOf(':');
                        if (idx > 0) {
                            host = v.substring(0, idx).trim();
                            try {
                                port = Integer.parseInt(v.substring(idx + 1).trim());
                            } catch (Throwable t) {
                                Ui.toast(ChatActivity.this, getString(R.string.manual_invalid));
                                return;
                            }
                        }
                        NetService s = core.service();
                        if (s != null) s.connectManual(host, port);
                    }
                });
    }

    // ---------------- Bus ----------------

    @Override
    public void onEvent(String type, Object payload) {
        if (Bus.MESSAGE_NEW.equals(type) && payload instanceof ChatMessage) {
            ChatMessage m = (ChatMessage) payload;
            if (peer != null && peer.deviceId.equals(m.peerDeviceId)) {
                adapter.add(m);
                scrollToBottom();
                animateLastItem();
            }
        } else if (Bus.MESSAGE_UPDATED.equals(type) && payload instanceof ChatMessage) {
            ChatMessage m = (ChatMessage) payload;
            if (peer != null && peer.deviceId.equals(m.peerDeviceId)) {
                adapter.update(m);
            }
        } else if (Bus.PEERS_CHANGED.equals(type) || Bus.PEER_UPDATED.equals(type)) {
            Peer fresh = core.peers.get(peerId);
            if (fresh != null) {
                peer = fresh;
                adapter.setPeer(peer);
            }
            updateStatus();
        } else if (Bus.TOAST.equals(type) && payload instanceof String) {
            Ui.toast(this, (String) payload);
        } else if (Bus.FILE_REQUEST.equals(type) && payload instanceof NetService.FileRequest) {
            final NetService.FileRequest req = (NetService.FileRequest) payload;
            Ui.confirm(this, "收到文件",
                    req.fileName + "\n" + Util.humanSize(req.size)
                            + "\n\n对方正在等待你的确认（60 秒后自动拒绝）。",
                    "接受", new Ui.OnClick() {
                        @Override
                        public void onClick() {
                            NetService s = core.service();
                            if (s != null) s.answerFileAccept(req.deviceId, true);
                        }
                    },
                    "拒绝", new Ui.OnClick() {
                        @Override
                        public void onClick() {
                            NetService s = core.service();
                            if (s != null) s.answerFileAccept(req.deviceId, false);
                        }
                    });
        } else if (Bus.TRUST_REQUEST.equals(type) && payload instanceof NetService.TrustRequest) {
            trustQueue.add((NetService.TrustRequest) payload);
            showNextTrust();
        }
    }

    private void showNextTrust() {
        if (trustShowing) return;
        final NetService.TrustRequest req = trustQueue.poll();
        if (req == null) return;
        trustShowing = true;
        String msg = getString(R.string.trust_msg,
                TextUtils.isEmpty(req.nickname) ? getString(R.string.unknown_peer) : req.nickname,
                Util.shortId(req.deviceId));
        Ui.confirm(this, getString(R.string.trust_title), msg,
                getString(R.string.trust_allow), new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        NetService s = core.service();
                        if (s != null) s.answerTrust(req.deviceId, true);
                        trustShowing = false;
                    }
                },
                getString(R.string.trust_deny), new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        NetService s = core.service();
                        if (s != null) s.answerTrust(req.deviceId, false);
                        trustShowing = false;
                    }
                });
    }
}
