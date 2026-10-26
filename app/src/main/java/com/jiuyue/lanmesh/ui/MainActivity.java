package com.jiuyue.lanmesh.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.AppCore;
import com.jiuyue.lanmesh.core.Bus;
import com.jiuyue.lanmesh.core.Const;
import com.jiuyue.lanmesh.core.Util;
import com.jiuyue.lanmesh.model.Peer;
import com.jiuyue.lanmesh.net.NetService;
import com.jiuyue.lanmesh.net.P2pManager;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * 主界面：底部导航在「局域网 / P2P / 设置」之间切换。
 *
 * 三个页面用同一 Activity 内的 View 切换，而不是 Fragment：
 *  - API 19 低端机上省掉 Fragment 事务与额外生命周期开销；
 *  - 三个页面都需要常驻监听 Bus 事件，同一 Activity 更简单。
 *
 * P2P 入口始终可见；点击时才做版本门禁与能力探测（见 P2pGate）。
 */
public class MainActivity extends AppCompatActivity implements Bus.Listener {

    private static final int REQ_NOTIFICATION = 4101;
    private static final int REQ_SCAN_JOIN = 4301;
    private static final int REQ_TREE = 4401;
    private static final int REQ_P2P_FILE = 4402;
    private static final int REQ_SCAN_ANSWER = 4302;
    private static final int REQ_CAMERA = 4303;
    private boolean pendingScanAnswer = false;
    private static final String STATE_TAB = "current_tab";

    private static final int TAB_LAN = 0;
    private static final int TAB_P2P = 1;
    private static final int TAB_SETTINGS = 2;

    private AppCore core;
    private PeerAdapter adapter;
    private ListView listView;
    private TextView tvEmpty;
    private TextView tvOwnId;
    private TextView tvDiag;
    private TextView tvWebUrl;
    private TextView btnOpenWeb;
    private ImageView btnWebToggle;
    private int webToggleColor;
    private TextView tvCount;

    private PanelPager pager;
    private View segWrap;
    private View segSlider;
    private View segTabs;
    private View navLan;
    private View navP2p;
    private View navSettings;
    private ImageView ivLan;
    private ImageView ivP2p;
    private ImageView ivSettings;
    private TextView tvNavLan;
    private TextView tvNavP2p;
    private TextView tvNavSettings;

    private TextView btnP2pOffer;
    private TextView btnP2pJoin;
    private TextView btnP2pAnswer;
    private TextView btnP2pClose;
    private TextView tvP2pLog;
    private TextView btnP2pClearLog;
    private TextView btnP2pShowCode;
    private TextView btnP2pSend;
    private android.widget.EditText etP2pText;
    private View p2pSendBar;
    private TextView tvP2pState;
    private TextView tvP2pRetry;
    private TextView tvP2pApi;
    private boolean probing = false;
    private boolean p2pProbed = false;

    private TextView segFollow;
    private TextView segLight;
    private TextView segDark;
    private View themeSeg;
    private View themeSlider;
    private TextView[] themeLabels;
    private float themeProgress;
    private boolean themeSliderInited = false;
    private int navStep = 0;
    private android.animation.ValueAnimator themeAnim;
    private TextView tvSettingsNickname;
    private TextView tvShortId;
    private TextView tvAddress;
    private TextView tvAbout;
    private com.jiuyue.lanmesh.ui.AnimSwitch swAutoAccept;
    private boolean updatingAutoAccept = false;
    private TextView tvDownloadHint;
    private TextView tvSaveTree;
    private View pageP2p;
    private View pageLan;
    private View pageSettings;
    private com.jiuyue.lanmesh.ui.AnimSwitch swLanMain;
    private TextView tvLanState;
    private boolean updatingLanMain = false;
    private Boolean lastWebOn = null;
    private View pageRow;
    private TextView tvBatteryState;

    private int currentTab = TAB_LAN;
    private final Queue<NetService.TrustRequest> trustQueue = new LinkedList<NetService.TrustRequest>();
    private boolean trustShowing = false;
    private String cachedIp;
    private String cachedTreeUriForName;
    private String cachedTreeName;
    private static boolean notifAsked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        core = AppCore.get(this);
        cachedIp = Util.localIpv4(this);

        bindViews();
        Ui.applyEdgeToEdge(this, findViewById(R.id.main_root));
        Ui.letterSpacing((TextView) findViewById(R.id.tv_own_id), 0.02f);

        // 冷启动一律回局域网；只有同一进程内的 recreate（主题切换）才沿用原页。
        // lastTabMemory 是静态字段，进程重启后自然为 -1。
        if (AppCore.lastTabMemory >= 0) {
            currentTab = AppCore.lastTabMemory;
            AppCore.lastTabMemory = -1; // 只用于本次 recreate，用完即清
        } else if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, TAB_LAN);
        } else {
            int home = core.prefs.homePage();
            if (core.prefs.pageHidden().contains(home)) {
                home = TAB_LAN;
                for (int k : core.prefs.pageOrder()) {
                    if (!core.prefs.pageHidden().contains(k)) {
                        home = k;
                        break;
                    }
                }
            }
            currentTab = home;
        }

        setupLanPage();
        setupP2pPage();
        setupSettingsPage();
        setupPager();

        if (currentTab == TAB_P2P && Build.VERSION.SDK_INT < Const.MIN_P2P_API) currentTab = TAB_LAN;
        ensureService();
        refresh();
        refreshSettings();

        if (AppCore.themeFadeIn) {
            AppCore.themeFadeIn = false;
            final android.view.ViewGroup decor =
                    (android.view.ViewGroup) getWindow().getDecorView();
            final View veil = new View(this);
            veil.setBackgroundColor(getResources().getColor(R.color.bg));
            veil.setAlpha(1f);
            decor.addView(veil, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            final Runnable remove = new Runnable() {
                @Override
                public void run() {
                    try {
                        decor.removeView(veil);
                    } catch (Throwable ignored) {
                    }
                }
            };
            veil.animate().alpha(0f).setDuration(240).withEndAction(remove).start();
            // 兜底：动画被打断时也必须移除遮罩，否则会一直盖着
            veil.postDelayed(remove, 900);
        }
    }

    private void bindViews() {
        tvOwnId = (TextView) findViewById(R.id.tv_own_id);
        tvEmpty = (TextView) findViewById(R.id.tv_empty);
        tvDiag = (TextView) findViewById(R.id.tv_diag);
        tvWebUrl = (TextView) findViewById(R.id.tv_web_url);
        btnOpenWeb = (TextView) findViewById(R.id.btn_open_web);
        btnWebToggle = (ImageView) findViewById(R.id.btn_web_toggle);
        webToggleColor = getResources().getColor(R.color.accent);
        tvCount = (TextView) findViewById(R.id.tv_count);
        listView = (ListView) findViewById(R.id.list_peers);

        pager = (PanelPager) findViewById(R.id.panel_pager);
        segWrap = findViewById(R.id.seg_wrap);
        segSlider = findViewById(R.id.nav_slider);
        segTabs = findViewById(R.id.seg_tabs);

        navLan = findViewById(R.id.nav_lan);
        navP2p = findViewById(R.id.nav_p2p);
        navSettings = findViewById(R.id.nav_settings);
        ivLan = (ImageView) findViewById(R.id.iv_nav_lan);
        ivP2p = (ImageView) findViewById(R.id.iv_nav_p2p);
        ivSettings = (ImageView) findViewById(R.id.iv_nav_settings);
        tvNavLan = (TextView) findViewById(R.id.tv_nav_lan);
        tvNavP2p = (TextView) findViewById(R.id.tv_nav_p2p);
        tvNavSettings = (TextView) findViewById(R.id.tv_nav_settings);

        btnP2pOffer = (TextView) findViewById(R.id.btn_p2p_offer);
        btnP2pJoin = (TextView) findViewById(R.id.btn_p2p_join);
        btnP2pAnswer = (TextView) findViewById(R.id.btn_p2p_answer);
        btnP2pClose = (TextView) findViewById(R.id.btn_p2p_close);
        tvP2pLog = (TextView) findViewById(R.id.tv_p2p_log);
        btnP2pClearLog = (TextView) findViewById(R.id.btn_p2p_clear_log);
        btnP2pShowCode = (TextView) findViewById(R.id.btn_p2p_show_code);
        btnP2pSend = (TextView) findViewById(R.id.btn_p2p_send);
        etP2pText = (android.widget.EditText) findViewById(R.id.et_p2p_text);
        p2pSendBar = findViewById(R.id.p2p_send_bar);
        tvP2pState = (TextView) findViewById(R.id.tv_p2p_state);
        tvP2pRetry = (TextView) findViewById(R.id.btn_p2p_retry);
        tvP2pApi = (TextView) findViewById(R.id.tv_api);

        themeSeg = findViewById(R.id.seg_container);
        themeSlider = findViewById(R.id.theme_slider);
        segFollow = (TextView) findViewById(R.id.seg_follow);
        segLight = (TextView) findViewById(R.id.seg_light);
        segDark = (TextView) findViewById(R.id.seg_dark);
        tvSettingsNickname = (TextView) findViewById(R.id.tv_settings_nickname);
        tvShortId = (TextView) findViewById(R.id.tv_short_id);
        tvAddress = (TextView) findViewById(R.id.tv_address);
        tvAbout = (TextView) findViewById(R.id.tv_about);
        swAutoAccept = (com.jiuyue.lanmesh.ui.AnimSwitch) findViewById(R.id.sw_auto_accept);
        tvDownloadHint = (TextView) findViewById(R.id.tv_download_hint);
        tvSaveTree = (TextView) findViewById(R.id.tv_save_tree);
        pageP2p = findViewById(R.id.page_p2p);
        pageLan = findViewById(R.id.page_lan);
        pageSettings = findViewById(R.id.page_settings);
        pageRow = findViewById(R.id.page_row);
        swLanMain = (com.jiuyue.lanmesh.ui.AnimSwitch) findViewById(R.id.sw_lan_main);
        tvLanState = (TextView) findViewById(R.id.tv_lan_state);
        tvBatteryState = (TextView) findViewById(R.id.tv_battery_state);
    }

    // ---------------- 局域网页 ----------------

    private void setupLanPage() {
        adapter = new PeerAdapter(this);
        listView.setAdapter(adapter);
        listView.setEmptyView(tvEmpty);

        findViewById(R.id.btn_nickname).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editNickname();
            }
        });
        Ui.pressable(findViewById(R.id.btn_nickname));
        findViewById(R.id.row_lan_main).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (swLanMain != null) swLanMain.toggle();
            }
        });
        if (swLanMain != null) {
            swLanMain.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    if (updatingLanMain) return;
                    core.prefs.setLanEnabled(isChecked);
                    NetService s = core.service();
                    if (s != null) s.setLanEnabled(isChecked);
                    updateLanState();
                    refresh();
                    Ui.toast(MainActivity.this, isChecked ? "局域网传输已开启" : "局域网传输已关闭");
                }
            });
        }
        findViewById(R.id.btn_manual).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualAdd();
            }
        });
        Ui.pressable(findViewById(R.id.btn_manual));

        final TextView btnRefresh = (TextView) findViewById(R.id.btn_refresh_peers);
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 不清空内存表：已连接/正在聊天的设备会从列表消失，聊天入口也没了。
                // 让发现结果自己刷新，过期的由 removeExpired 统一收敛。
                NetService s = core.service();
                if (s != null) s.restartDiscovery();
                refresh();
                android.util.Log.i("LanMeshUI", "refresh peers clicked");
                btnRefresh.setText("搜索中");
                btnRefresh.setBackgroundResource(R.drawable.bg_btn_primary);
                btnRefresh.setTextColor(getResources().getColor(R.color.on_accent));
                btnRefresh.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        btnRefresh.setText("刷新");
                        btnRefresh.setBackgroundResource(R.drawable.bg_btn_ghost);
                        btnRefresh.setTextColor(getResources().getColor(R.color.text));
                    }
                }, 1600);
                Ui.toast(MainActivity.this, "正在重新搜索附近设备…");
            }
        });
        Ui.pressable(btnRefresh);

        btnWebToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!core.prefs.lanEnabled()) {
                    Ui.toast(MainActivity.this, "请先开启局域网传输");
                    return;
                }
                boolean enable = !isWebRunning();
                core.prefs.setWebShareEnabled(enable);
                NetService s = core.service();
                if (s != null) s.setWebShareEnabled(enable);
                updateWebToggle(enable, true);
                refresh();
                Ui.toast(MainActivity.this, enable ? "网页端已开启" : "网页端已关闭，中转文件已清理");
            }
        });
        btnOpenWeb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWebPage();
            }
        });
        tvWebUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWebPage();
            }
        });
        tvWebUrl.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copy(tvWebUrl.getText().toString());
                return true;
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Peer p = adapter.getItemAt(position);
                if (p == null) return;
                Intent i = new Intent(MainActivity.this, ChatActivity.class);
                i.putExtra(ChatActivity.EXTRA_PEER_ID, p.deviceId);
                startActivity(i);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Peer p = adapter.getItemAt(position);
                if (p != null) showPeerInfo(p);
                return true;
            }
        });
        tvDiag.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copyDiagnostics();
                return true;
            }
        });
    }

    // ---------------- P2P 页 ----------------

    private void setupP2pPage() {
        tvP2pApi.setText("API " + Build.VERSION.SDK_INT
                + " · MIN_P2P_API " + Const.MIN_P2P_API
                + "\n系统 " + Util.systemVersion()
                + "\nIPv6: " + (Util.hasGlobalIpv6() ? "可用（可绕过 NAT 直连）" : "不可用")
                + "\nSTUN 可配置 · 不使用 TURN");
        tvP2pRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p2pProbed = false;
                runP2pProbe();
            }
        });
        btnP2pOffer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!P2pGate.versionAllowed(MainActivity.this)) return;
                P2pManager.get(MainActivity.this).startOffer();
            }
        });
        btnP2pJoin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!P2pGate.versionAllowed(MainActivity.this)) return;
                showScanChoice(false);
            }
        });
        btnP2pAnswer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showScanChoice(true);
            }
        });
        btnP2pClearLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                P2pManager.get(MainActivity.this).clearLogs();
                tvP2pLog.setText("尚未连接");
            }
        });
        btnP2pShowCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                P2pManager mgr = P2pManager.get(MainActivity.this);
                if (mgr.localCode() == null || mgr.localCode().length() == 0) {
                    Ui.toast(MainActivity.this, "当前没有可用的二维码");
                    return;
                }
                boolean isAnswer = P2pManager.ST_ANSWER_READY.equals(mgr.state());
                showSignalDialog(isAnswer ? "应答码" : "邀请码",
                        isAnswer ? "把这段应答码回给对方" : "把这段邀请码发给对方", mgr.localCode());
            }
        });
        findViewById(R.id.btn_p2p_file).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!P2pManager.get(MainActivity.this).isConnected()) {
                    Ui.toast(MainActivity.this, "P2P 尚未连接");
                    return;
                }
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(i, "选择要发送的文件"), REQ_P2P_FILE);
                } catch (Throwable t) {
                    Ui.toast(MainActivity.this, "没有可用的文件选择器");
                }
            }
        });
        btnP2pSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = etP2pText.getText().toString().trim();
                if (text.length() == 0) return;
                if (P2pManager.get(MainActivity.this).sendText(text)) {
                    etP2pText.setText("");
                }
            }
        });
        btnP2pClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                P2pManager.get(MainActivity.this).close();
                tvP2pLog.setText("尚未连接");
            }
        });
        Ui.pressable(tvP2pRetry);
        updateP2pHint();
    }

    private void updateP2pHint() {
        if (Build.VERSION.SDK_INT < Const.MIN_P2P_API) {
            tvP2pState.setText(getString(R.string.p2p_low_version_title) + "\n\n"
                    + getString(R.string.p2p_low_version_msg, Util.systemVersion()));
            tvP2pRetry.setVisibility(View.GONE);
        } else {
            tvP2pState.setText(getString(R.string.p2p_checking));
            tvP2pRetry.setVisibility(View.GONE);
        }
    }

    private void runP2pProbe() {
        if (probing) return;
        p2pProbed = true;
        if (Build.VERSION.SDK_INT < Const.MIN_P2P_API) {
            // 理论上到不了这里（tab 点击已拦截），双保险
            updateP2pHint();
            return;
        }
        probing = true;
        tvP2pState.setText(getString(R.string.p2p_checking));
        tvP2pRetry.setVisibility(View.GONE);
        P2pGate.probeAsync(MainActivity.this, new P2pGate.ProbeCallback() {
            @Override
            public void onResult(String failureReason) {
                probing = false;
                if (failureReason == null) {
                    tvP2pState.setText(getString(R.string.p2p_ok) + "\n\n" + "DataChannel 已就绪，可发起或加入连接");
                } else {
                    tvP2pState.setText(getString(R.string.p2p_unsupported) + "\n\n"
                            + failureReason);
                }
                tvP2pRetry.setVisibility(View.VISIBLE);
            }
        });
    }

    // ---------------- 设置页 ----------------

    private void setupSettingsPage() {
        themeLabels = new TextView[]{segFollow, segLight, segDark};
        View.OnClickListener themeClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int mode = v.getId() == R.id.seg_light ? 1 : v.getId() == R.id.seg_dark ? 2 : 0;
                applyTheme(mode);
            }
        };
        segFollow.setOnClickListener(themeClick);
        segLight.setOnClickListener(themeClick);
        segDark.setOnClickListener(themeClick);
        if (themeSeg != null) {
            themeSeg.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or_, int ob) {
                    layoutThemeSlider();
                }
            });
        }

        View.OnClickListener nick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editNickname();
            }
        };
        findViewById(R.id.row_nickname).setOnClickListener(nick);
        Ui.pressable(findViewById(R.id.row_nickname));

        findViewById(R.id.row_clear_web).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.confirm(MainActivity.this, "清除全部聊天记录",
                        "将删除本机保存的所有会话（包括与设备的聊天记录）和网页上传的文件。此操作不可恢复。",
                        "全部清除", new Ui.OnClick() {
                            @Override
                            public void onClick() {
                                runCleanTask("正在清除…", new java.util.concurrent.Callable<Integer>() {
                                    @Override
                                    public Integer call() {
                                        return com.jiuyue.lanmesh.net.WebShareServer
                                                .cleanupEverything(MainActivity.this);
                                    }
                                }, "已清除 %d 个会话");
                            }
                        },
                        getString(R.string.cancel), null);
            }
        });
        if (tvAbout != null) {
            tvAbout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/hesanyue50-lang/link-mesh")));
                    } catch (Throwable t) {
                        Ui.toast(MainActivity.this, "没有可用的浏览器");
                    }
                }
            });
        }
        findViewById(R.id.btn_copy_diag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyDiagnostics();
            }
        });
        Ui.pressable(findViewById(R.id.btn_copy_diag));

        findViewById(R.id.row_nav_manager).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NavManager.show(MainActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        applyNavConfig();
                    }
                });
            }
        });
        findViewById(R.id.row_save_tree).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSaveTree();
            }
        });
        findViewById(R.id.row_auto_accept).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (swAutoAccept != null) swAutoAccept.toggle();
            }
        });
        if (swAutoAccept != null) {
            swAutoAccept.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    if (updatingAutoAccept) return;
                    core.prefs.setAutoAcceptFiles(isChecked);
                    Ui.toast(MainActivity.this, isChecked
                            ? "已开启自动接收" : "已关闭自动接收，收到文件需手动确认");
                }
            });
        }
        findViewById(R.id.row_clean_transfers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.confirm(MainActivity.this, "清理传输文件",
                        "将删除下载目录里的所有已接收文件（不影响聊天记录）。此操作不可恢复。",
                        "清理", new Ui.OnClick() {
                            @Override
                            public void onClick() {
                                runCleanTask("正在清理…", new java.util.concurrent.Callable<Integer>() {
                                    @Override
                                    public Integer call() {
                                        return cleanTransferFiles();
                                    }
                                }, "已删除 %d 个文件");
                            }
                        },
                        getString(R.string.cancel), null);
            }
        });
        findViewById(R.id.row_clear_download).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.confirm(MainActivity.this, "清空保存目录",
                        "将删除保存目录（含公共文件夹中已保存的副本）里的全部文件，不影响聊天记录。此操作不可恢复。",
                        "清空", new Ui.OnClick() {
                            @Override
                            public void onClick() {
                                runCleanTask("正在清空…", new java.util.concurrent.Callable<Integer>() {
                                    @Override
                                    public Integer call() {
                                        return clearSavedDirFiles();
                                    }
                                }, "已删除 %d 个文件");
                            }
                        },
                        getString(R.string.cancel), null);
            }
        });
        findViewById(R.id.row_open_download).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDownloadDir();
            }
        });
        findViewById(R.id.row_autostart_perm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAutostartSettings();
            }
        });
        findViewById(R.id.row_battery_opt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBattery();
            }
        });
        findViewById(R.id.row_app_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    Ui.toast(MainActivity.this, "无法打开应用设置");
                }
            }
        });
    }

    private void applyTheme(final int mode) {
        if (mode == core.prefs.themeMode() && !willRecreate(mode)) {
            animateThemeTo(mode, null);
            return;
        }
        core.prefs.setThemeMode(mode);
        if (!willRecreate(mode)) {
            // 目标明度与当前一致：不重建、不遮罩，但必须同步 delegate，
            // 否则"跟随系统 → 浅色"锁定后，系统入夜仍会变深色
            AppCompatDelegate.setDefaultNightMode(nightOf(mode));
            animateThemeTo(mode, null);
            return;
        }
        // 先让滑块走完，再触发 recreate，避免用户只看到"页面突然重开"
        animateThemeTo(mode, new Runnable() {
            @Override
            public void run() {
                applyNightMode(mode);
            }
        });
    }

    /**
     * 主题切换的过渡：
     * ① 旧界面 160ms 淡出（而不是瞬间硬切）；
     * ② 触发 recreate；
     * ③ 新 Activity 从 alpha=0 淡入 220ms。
     * 因为 recreate 无法做跨主题的逐属性动画，用两层 alpha 过渡是最稳的做法，
     * API 19 上同样有效。
     */
    /** 目标明度是否与当前不同（不同才需要 recreate + 遮罩）。 */
    private boolean willRecreate(int mode) {
        int nightMask = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean nowNight = nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean target;
        if (mode == 1) {
            target = false;
        } else if (mode == 2) {
            target = true;
        } else {
            int sys = getApplicationContext().getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            target = sys == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        return target != nowNight;
    }

    private int nightOf(int mode) {
        return mode == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    private void applyNightMode(final int mode) {
        final int night = nightOf(mode);
        final android.view.ViewGroup decor =
                (android.view.ViewGroup) getWindow().getDecorView();
        final View veil = new View(this);
        veil.setBackgroundColor(getResources().getColor(R.color.bg));
        veil.setAlpha(0f);
        veil.setClickable(true); // 拦截过渡期间的重复点击
        decor.addView(veil, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        final Runnable abort = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                // 极端情况没有触发重建：必须清掉遮罩与标记，否则界面会被盖住
                AppCore.themeFadeIn = false;
                try {
                    decor.removeView(veil);
                } catch (Throwable ignored) {
                }
            }
        };
        veil.animate().alpha(1f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                AppCore.themeFadeIn = true;
                AppCompatDelegate.setDefaultNightMode(night);
            }
        }).start();
        veil.postDelayed(abort, 1600);
    }

    private void layoutThemeSlider() {
        if (themeSeg == null || themeSlider == null) return;
        int inner = themeSeg.getWidth() - themeSeg.getPaddingLeft() - themeSeg.getPaddingRight();
        int h = themeSeg.getHeight() - themeSeg.getPaddingTop() - themeSeg.getPaddingBottom();
        if (inner <= 0 || h <= 0) return;
        int w = inner / 3;
        android.view.ViewGroup.LayoutParams lp = themeSlider.getLayoutParams();
        boolean need = false;
        if (w > 0 && lp.width != w) {
            lp.width = w;
            need = true;
        }
        if (lp.height != h) {
            lp.height = h;
            need = true;
        }
        if (need) themeSlider.setLayoutParams(lp);
        if (!themeSliderInited) {
            themeSliderInited = true;
            themeProgress = core.prefs.themeMode();
        }
        applyThemeProgress(themeProgress);
    }

    /** 与底部导航同一套：滑块位置 + 标签颜色插值，全部由进度 p 驱动。 */
    private void applyThemeProgress(float p) {
        if (themeSeg == null || themeSlider == null || themeLabels == null) return;
        int inner = themeSeg.getWidth() - themeSeg.getPaddingLeft() - themeSeg.getPaddingRight();
        if (inner <= 0) return;
        float step = inner / 3f;
        float tx = p * step;
        // 过冲时夹住，避免滑块被父容器裁出锯齿
        if (tx < 0) tx = 0;
        if (tx > step * 2) tx = step * 2;
        themeSlider.setTranslationX(tx);

        int on = getResources().getColor(R.color.on_accent);
        int off = getResources().getColor(R.color.text_dim);
        for (int i = 0; i < themeLabels.length; i++) {
            float t = Math.max(0f, 1f - Math.abs(p - i));
            themeLabels[i].setTextColor(blendColor(off, on, t));
            themeLabels[i].setTypeface(null, t > 0.5f ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /**
     * 分段控件动画：260ms + OvershootInterpolator(0.9) ≈ 弹簧 spatial-fast 的 +9% 过冲。
     * 用单一进度值同时驱动位移与颜色，和底部导航的手感保持一致。
     */
    private void animateThemeTo(final int target, final Runnable after) {
        if (themeSeg == null || themeSlider == null) {
            themeProgress = target;
            if (after != null) after.run();
            return;
        }
        if (themeAnim != null) themeAnim.cancel();
        themeAnim = android.animation.ValueAnimator.ofFloat(themeProgress, target);
        themeAnim.setDuration(260);
        themeAnim.setInterpolator(new android.view.animation.OvershootInterpolator(0.18f));
        themeAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                themeProgress = (Float) a.getAnimatedValue();
                applyThemeProgress(themeProgress);
            }
        });
        themeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                themeProgress = target;
                applyThemeProgress(target);
                if (after != null) after.run();
            }
        });
        themeAnim.start();
    }

    private void updateThemeSegments() {
        layoutThemeSlider();
    }

    private void requestIgnoreBattery() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Ui.toast(this, "已经忽略电池优化");
                    updateBatteryState();
                    return;
                }
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                Ui.toast(this, "当前系统版本无需单独设置");
            }
        } catch (Throwable t) {
            Ui.toast(this, "无法打开电池优化设置，请手动在系统设置中允许");
        }
    }

    /**
     * 各品牌的自启动/后台管理页面没有公开 API，只能按包名 + Activity 逐个尝试。
     * 顺序覆盖国内主流 ROM：小米 / 华为 / OPPO·realme·一加 / vivo·iQOO /
     * 三星 / 魅族 / 努比亚 / 联想，最后统一回退到系统应用详情页。
     */
    private static final String[][] AUTOSTART_PAGES = new String[][]{
            // 小米 / Redmi（MIUI / HyperOS）
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            // 华为 / 荣耀（EMUI / HarmonyOS）
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            // OPPO / realme / 一加（ColorOS / OxygenOS）
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
            {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
            // vivo / iQOO
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            {"com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"},
            // 三星（One UI）
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.ram.AutoRunActivity"},
            // 魅族（Flyme）
            {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
            {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
            // 努比亚 / 中兴
            {"cn.nubia.security2", "cn.nubia.security2.activity.PermissionManageActivity"},
            // 联想 / Moto
            {"com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"},
            {"com.motorola.moto", "com.motorola.moto.notification.NotificationSettingsActivity"}
    };

    private void openAutostartSettings() {
        // 先按当前品牌过滤，命中就直接跳，避免在别的品牌的组件上空转
        String maker = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase());
        String brand = (Build.BRAND == null ? "" : Build.BRAND.toLowerCase());
        java.util.List<String[]> ordered = new java.util.ArrayList<String[]>();
        for (String[] t : AUTOSTART_PAGES) {
            String pkg = t[0].toLowerCase();
            boolean sameBrand = false;
            if (maker.contains("xiaomi") || brand.contains("redmi")) sameBrand = pkg.contains("miui");
            else if (maker.contains("huawei") || brand.contains("honor")) sameBrand = pkg.contains("huawei") || pkg.contains("hihonor");
            else if (maker.contains("oppo") || maker.contains("realme") || brand.contains("oneplus")) {
                sameBrand = pkg.contains("coloros") || pkg.contains("oppo") || pkg.contains("oneplus");
            } else if (maker.contains("vivo") || brand.contains("iqoo")) {
                sameBrand = pkg.contains("vivo") || pkg.contains("iqoo");
            } else if (maker.contains("samsung")) sameBrand = pkg.contains("samsung");
            else if (maker.contains("meizu")) sameBrand = pkg.contains("meizu");
            else if (maker.contains("nubia") || maker.contains("zte")) sameBrand = pkg.contains("nubia");
            else if (maker.contains("lenovo") || maker.contains("motorola")) sameBrand = pkg.contains("lenovo") || pkg.contains("motorola");
            if (sameBrand) ordered.add(t);
        }
        for (String[] t : AUTOSTART_PAGES) {
            if (!ordered.contains(t)) ordered.add(t);
        }
        for (String[] t : ordered) {
            try {
                Intent i = new Intent();
                i.setComponent(new android.content.ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Ui.toast(this, "请手动在系统设置中开启自启动");
        }
    }

    private void updateBatteryState() {
        if (tvBatteryState == null) return;
        boolean ok = false;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                ok = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        } catch (Throwable ignored) {
        }
        tvBatteryState.setText(ok ? "已允许" : "未允许");
        tvBatteryState.setTextColor(getResources().getColor(ok ? R.color.accent : R.color.warn));
    }

    private void chooseSaveTree() {
        Ui.sheet(this, new String[]{"选择系统文件夹", "使用应用专属目录"}, new Ui.OnClick[]{
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        try {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                            startActivityForResult(i, REQ_TREE);
                        } catch (Throwable t) {
                            Ui.toast(MainActivity.this, "无法打开目录选择器");
                        }
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        core.prefs.setSaveTreeUri("");
                        updateDownloadSettings();
                        Ui.toast(MainActivity.this, "已恢复为应用专属目录");
                    }
                }
        });
    }

    /**
     * 清空「保存目录」。
     *
     * 公共 SAF 目录里只删"聊天记录引用到的那些副本"——用户可能把保存目录选成
     * Download 甚至存储根，整目录 listFiles+delete 会连别人的文件一起删
     *（DocumentFile.delete 对目录还是递归删除）。
     */
    private int clearSavedDirFiles() {
        int n = 0;
        String tree = core.prefs.saveTreeUri();
        if (tree != null && tree.length() > 0 && Build.VERSION.SDK_INT >= 21) {
            java.util.HashSet<String> ours = new java.util.HashSet<String>();
            try {
                for (String pid : core.messages.peerIds()) {
                    for (com.jiuyue.lanmesh.model.ChatMessage m : core.messages.load(pid)) {
                        if (m.localPath != null && m.localPath.startsWith("content://")) {
                            ours.add(m.localPath);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            for (String path : ours) {
                try {
                    if (getContentResolver().delete(android.net.Uri.parse(path), null, null) > 0) n++;
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            java.io.File dir = com.jiuyue.lanmesh.core.StorageUtil.downloadDir(this);
            java.io.File[] list = dir.listFiles();
            if (list != null) {
                for (java.io.File f : list) {
                    if (f.isFile() && f.delete()) n++;
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 删文件/清会话放到后台：文件多时主线程直接删会 ANR。 */
    private void runCleanTask(final String hint, final java.util.concurrent.Callable<Integer> task,
                              final String doneFmt) {
        Ui.toast(this, hint);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                try {
                    Integer r = task.call();
                    if (r != null) n = r;
                } catch (Throwable ignored) {
                }
                final int count = n;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Ui.toast(MainActivity.this,
                                count > 0 ? doneFmt.replace("%d", String.valueOf(count)) : "没有可清理的内容");
                        refresh();
                        refreshSettings();
                    }
                });
            }
        }, "lanmesh-clean").start();
    }

    private int cleanTransferFiles() {
        int n = 0;
        try {
            java.io.File dir = com.jiuyue.lanmesh.core.StorageUtil.downloadDir(this);
            java.io.File[] list = dir.listFiles();
            if (list != null) {
                for (java.io.File f : list) {
                    if (f.isFile() && !f.getName().startsWith(".")) {
                        if (f.delete()) n++;
                    } else if (f.isDirectory() && "web".equals(f.getName())) {
                        java.io.File[] subs = f.listFiles();
                        if (subs != null) {
                            for (java.io.File sub : subs) {
                                if (sub.isFile() && sub.delete()) n++;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private void openDownloadDir() {
        String tree = core.prefs.saveTreeUri();
        if (tree != null && tree.length() > 0 && Build.VERSION.SDK_INT >= 21) {
            try {
                android.net.Uri treeUri = android.net.Uri.parse(tree);
                String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                android.net.Uri docUri = android.provider.DocumentsContract
                        .buildDocumentUriUsingTree(treeUri, docId);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(docUri, "vnd.android.document/directory");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        java.io.File dir = com.jiuyue.lanmesh.core.StorageUtil.downloadDir(this);
        String abs = dir.getAbsolutePath();
        String prefix = "/storage/emulated/0/";
        if (abs.startsWith(prefix)) {
            try {
                android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:" + abs.substring(prefix.length()));
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "vnd.android.document/directory");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        Ui.toast(this, "无法打开文件管理器：" + abs);
    }

    private void updateDownloadSettings() {
        if (swAutoAccept != null) {
            updatingAutoAccept = true;
            swAutoAccept.setChecked(core.prefs.autoAcceptFiles());
            updatingAutoAccept = false;
        }
        if (tvSaveTree != null) {
            String tree = core.prefs.saveTreeUri();
            if (tree == null || tree.length() == 0) {
                tvSaveTree.setText("应用专属");
                cachedTreeUriForName = "";
                cachedTreeName = null;
            } else if (tree.equals(cachedTreeUriForName) && cachedTreeName != null) {
                tvSaveTree.setText(cachedTreeName); // 同一个目录不重复跨进程查询
            } else {
                String name = "已选择";
                try {
                    androidx.documentfile.provider.DocumentFile d =
                            androidx.documentfile.provider.DocumentFile.fromTreeUri(this,
                                    android.net.Uri.parse(tree));
                    if (d != null && d.getName() != null) name = d.getName();
                } catch (Throwable ignored) {
                }
                cachedTreeUriForName = tree;
                cachedTreeName = name;
                tvSaveTree.setText(name);
            }
        }
        if (tvDownloadHint != null) {
            tvDownloadHint.setText(com.jiuyue.lanmesh.core.StorageUtil.publicDirAvailable(this)
                    ? "选择系统文件夹后，接收的文件会复制到该目录（公共文件夹），文件管理器可直接访问。"
                    : "Android 10 及以上无法直接写公共下载目录，请用「保存目录」选择一个系统文件夹，接收的文件会复制到那里。");
        }
    }

    private void refreshSettings() {
        // 注意：这里不要再调用 updateThemeSegments()。
        // 每次切到设置页都按 prefs 重置滑块位置，会在页面滑动/布局变化时
        // 表现为"滑块抽搐"；滑块只由点击动画与首次初始化驱动。
        tvSettingsNickname.setText(core.prefs.nickname());
        tvShortId.setText(core.prefs.shortId());
        NetService s = core.service();
        int port = s == null ? 0 : s.getPort();
        NetService svcForPort = core.service();
        int webPort = svcForPort == null ? 0 : svcForPort.getWebPort();
        boolean webOn = svcForPort != null && svcForPort.isWebShareRunning();
        tvAddress.setText((cachedIp == null ? "无 Wi-Fi" : cachedIp) + ":" + port
                + ((cachedIp == null || !webOn || webPort <= 0) ? "" : " · 网页 " + cachedIp + ":" + webPort));
        updateBatteryState();
        updateDownloadSettings();
        tvAbout.setText("Link Mesh " + versionName() + "\n"
                + "github.com/hesanyue50-lang/link-mesh");
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    // ---------------- 底部导航 ----------------

    private void setupPager() {
        navLan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(TAB_LAN);
            }
        });
        navSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(TAB_SETTINGS);
            }
        });
        navP2p.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 门禁在点击时执行：版本不够就停在当前页，不改变用户所在位置
                if (!P2pGate.versionAllowed(MainActivity.this)) return;
                showTab(TAB_P2P);
            }
        });

        if (segWrap != null) {
            segWrap.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or_, int ob) {
                    layoutSlider();
                }
            });
        }
        if (pager != null) {
            pager.setListener(new PanelPager.Listener() {
                @Override
                public void onPagerScroll(float progress) {
                    applyNavProgress(progress);
                }

                @Override
                public void onPageSettled(int page) {
                    // 必须显式限定外部类：匿名类自身也有同名方法，
                    // 直接写 onPageSettled(page) 会无限递归（曾导致一打开就闪退）
                    MainActivity.this.onPageSettled(page);
                }
            });
            // 关键顺序：必须在 View 布局前就把目标页交给 PanelPager
            // （pageWidth==0 时它会记为 pendingPage）。放到 post 里会与
            // onSizeChanged 的 scrollTo(0) 竞争，表现为重建后跳回第一页。
            pager.setPage(currentTab, false);
            pager.post(new Runnable() {
                @Override
                public void run() {
                    applyNavConfig();
                }
            });
        }
    }

    /**
     * 滑块尺寸 = 导航条内宽 / 3，高度 = 标签行高。
     *
     * 必须等布局完成后再量：滑块是无内容的 View，XML 里写 match_parent
     * 在 AT_MOST 约束下会撑到上限，把导航条顶变形。
     */
    private void layoutSlider() {
        if (segWrap == null || segSlider == null || segTabs == null) return;
        int inner = segWrap.getWidth() - segWrap.getPaddingLeft() - segWrap.getPaddingRight();
        int h = segTabs.getHeight();
        if (inner <= 0 || h <= 0) return;
        int n = visibleTabs();
        int w = inner / n;
        android.view.ViewGroup.LayoutParams lp = segSlider.getLayoutParams();
        boolean need = false;
        if (w > 0 && lp.width != w) {
            lp.width = w;
            need = true;
        }
        if (lp.height != h) {
            lp.height = h;
            need = true;
        }
        if (need) segSlider.setLayoutParams(lp);
        navStep = w;
        applyNavProgress(pageProgress());
    }

    private int visibleTabs() {
        if (segTabs == null) return 1;
        int n = 0;
        android.view.ViewGroup g = (android.view.ViewGroup) segTabs;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i).getVisibility() != View.GONE) n++;
        }
        return Math.max(1, n);
    }

    private int keyOfNav(View v) {
        if (v.getId() == R.id.nav_lan) return TAB_LAN;
        if (v.getId() == R.id.nav_p2p) return TAB_P2P;
        return TAB_SETTINGS;
    }

    private int indexOfKey(int key) {
        if (segTabs == null) return 0;
        android.view.ViewGroup g = (android.view.ViewGroup) segTabs;
        int idx = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() == View.GONE) continue;
            if (keyOfNav(v) == key) return idx;
            idx++;
        }
        return 0;
    }

    private int keyOfIndex(int idx) {
        if (segTabs == null) return TAB_LAN;
        android.view.ViewGroup g = (android.view.ViewGroup) segTabs;
        int cur = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() == View.GONE) continue;
            if (cur == idx) return keyOfNav(v);
            cur++;
        }
        return TAB_LAN;
    }

    private View pageOf(int key) {
        if (key == TAB_LAN) return pageLan;
        if (key == TAB_P2P) return pageP2p;
        return pageSettings;
    }

    private View navOf(int key) {
        if (key == TAB_LAN) return navLan;
        if (key == TAB_P2P) return navP2p;
        return navSettings;
    }

    /** 按「顺序 + 隐藏」重建页面容器和底部导航；滑块按可见数重新等分。 */
    private void applyNavConfig() {
        if (segTabs == null || pageRow == null) return;
        android.widget.LinearLayout row = (android.widget.LinearLayout) pageRow;
        android.widget.LinearLayout tabs = (android.widget.LinearLayout) segTabs;
        row.removeAllViews();
        tabs.removeAllViews();
        java.util.Set<Integer> hidden = core.prefs.pageHidden();
        for (int key : core.prefs.pageOrder()) {
            if (hidden.contains(key)) continue;
            row.addView(pageOf(key));
            tabs.addView(navOf(key));
        }
        if (pager != null) pager.setPage(indexOfKey(currentTab), false);
        layoutSlider(); // 先滚到目标页再算滑块，否则会先用旧偏移算一帧
        if (segWrap != null) {
            segWrap.animate().cancel();
            segWrap.setAlpha(0.55f);
            segWrap.setTranslationY(Ui.dp(this, 6));
            segWrap.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    private void updateLanState() {
        if (swLanMain != null) {
            updatingLanMain = true;
            swLanMain.setChecked(core.prefs.lanEnabled());
            updatingLanMain = false;
        }
        if (tvLanState == null) return;
        boolean on = core.prefs.lanEnabled();
        NetService s = core.service();
        int port = s == null ? 0 : s.getPort();
        tvLanState.setText(on
                ? ("已开启 · " + (cachedIp == null ? "无 Wi-Fi" : cachedIp) + ":" + port)
                : "已关闭 · 不参与发现与传输");
    }

    private float pageProgress() {
        if (pager == null || pager.getWidth() == 0) return indexOfKey(currentTab);
        return pager.getScrollX() / (float) pager.getWidth();
    }

    /**
     * 导航条完全由滚动进度驱动：滑块位置线性跟随，标签颜色按距离插值。
     * 不播放动画器 —— 手指拖到哪滑块就在哪，比"松手再播一段动画"跟手。
     */
    private void applyNavProgress(float progress) {
        if (segWrap == null || segSlider == null || segTabs == null) return;
        int inner = segWrap.getWidth() - segWrap.getPaddingLeft() - segWrap.getPaddingRight();
        if (inner <= 0) return;
        int n = visibleTabs();
        float step = inner / (float) n;
        float max = step * (n - 1);
        float tx = progress * step;
        if (tx < 0) tx = 0;
        if (tx > max) tx = max;
        segSlider.setTranslationX(tx);

        int on = getResources().getColor(R.color.accent);
        int off = getResources().getColor(R.color.text_faint);
        android.view.ViewGroup g = (android.view.ViewGroup) segTabs;
        int idx = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View tab = g.getChildAt(i);
            if (tab.getVisibility() == View.GONE) continue;
            float t = Math.max(0f, 1f - Math.abs(progress - idx));
            // 量化到 0.1：拖动时每像素都建 ColorFilter/Typeface 会让低端机明显掉帧
            t = Math.round(t * 10f) / 10f;
            int c = blendColor(off, on, t);
            try {
                android.view.ViewGroup tg = (android.view.ViewGroup) tab;
                ImageView icon = (ImageView) tg.getChildAt(0);
                TextView label = (TextView) tg.getChildAt(1);
                label.setTextColor(c);
                icon.setColorFilter(c);
                Boolean bold = (Boolean) label.getTag();
                boolean wantBold = t > 0.5f;
                if (bold == null || bold.booleanValue() != wantBold) {
                    label.setTypeface(null, wantBold
                            ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    label.setTag(Boolean.valueOf(wantBold));
                }
            } catch (Throwable ignored) {
            }
            idx++;
        }
    }

    private static int blendColor(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (android.graphics.Color.alpha(from)
                + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t);
        int r = (int) (android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t);
        int g = (int) (android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t);
        int b = (int) (android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t);
        return android.graphics.Color.argb(a, r, g, b);
    }

    private void showTab(int tab) {
        if (tab == TAB_P2P && !P2pGate.versionAllowed(this)) return;
        core.prefs.setLastTab(tab);
        AppCore.lastTabMemory = tab;
        currentTab = tab;
        if (pager != null) {
            pager.setPage(indexOfKey(tab), true);
        } else {
            currentTab = tab;
        }
    }

    /** 某页停稳后：触发该页的数据刷新与 P2P 探测。 */
    private void onPageSettled(int page) {
        int key = keyOfIndex(page);
        currentTab = key;
        core.prefs.setLastTab(key);
        AppCore.lastTabMemory = key;
        if (key == TAB_P2P) {
            if (Build.VERSION.SDK_INT < Const.MIN_P2P_API) {
                // 滑动也能到 P2P 页：同样拦截并回退，绝不隐藏入口。
                // 注意 setPage 的入参是"索引"不是 key：用户隐藏/重排后索引 0 可能正是 P2P，
                // 直接 setPage(TAB_LAN) 会同步递归到 StackOverflow
                P2pGate.versionAllowed(this);
                int fallback = keyOfIndex(0);
                if (fallback == TAB_P2P) fallback = TAB_SETTINGS;
                currentTab = fallback;
                AppCore.lastTabMemory = fallback;
                if (pager != null) {
                    int idx = indexOfKey(fallback);
                    if (pager.getPage() != idx) pager.setPage(idx, true);
                }
                return;
            }
            if (!p2pProbed) runP2pProbe();
        } else if (key == TAB_SETTINGS) {
            refreshSettings();
        } else {
            refresh();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            layoutSlider();
            layoutThemeSlider();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bus.register(this);
        layoutSlider();
        ensureService();
        refresh();
        refreshSettings();
        updateWebToggle(isWebRunning(), false);
        lastWebOn = isWebRunning();
        // IP 只在 onCreate 取一次的话，先开后连 Wi-Fi / 换网段后地址永远是旧的
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String ip = Util.localIpv4(MainActivity.this);
                if (ip == null || ip.equals(cachedIp)) return;
                cachedIp = ip;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                        refreshSettings();
                    }
                });
            }
        }, "lanmesh-ip").start();
        syncP2pUi();
        requestNotificationPermission();
    }

    @Override
    protected void onPause() {
        Bus.unregister(this);
        dismissSignalDialog(); // 旋转/切后台：不让 Dialog 挂在已销毁的 Activity 上
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // 主题动画回调可能持有已销毁的 Activity：不取消会在死实例上叠遮罩
        if (themeAnim != null) {
            try { themeAnim.cancel(); } catch (Throwable ignored) {}
            themeAnim = null;
        }
        super.onDestroy();
    }

    private void ensureService() {
        if (core.service() == null) {
            try {
                // API 26+ 后台不允许 startService，必须走前台服务入口，否则抛异常被吞、服务静默不启动
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(new Intent(this, NetService.class));
                } else {
                    startService(new Intent(this, NetService.class));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void requestNotificationPermission() {
        if (notifAsked) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifAsked = true;
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 网页端开关：
     *  - 图标用 Lucide 的 play/pause（矢量，辨识度高）
     *  - 动画只做「颜色渐变 + 图标旋转淡入」，不做放大缩小
     *    （放大容易顶到卡片边缘，看起来廉价）
     */
    private void updateWebToggle(boolean enabled, boolean animate) {
        if (btnWebToggle == null) return;
        final int target = getResources().getColor(enabled ? R.color.accent : R.color.panel_hi);
        btnWebToggle.setImageResource(enabled ? R.drawable.ic_pause : R.drawable.ic_play);
        btnWebToggle.setColorFilter(getResources().getColor(enabled ? R.color.on_accent : R.color.text));
        if (!animate) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(target);
            btnWebToggle.setBackground(g);
            btnWebToggle.setRotation(0f);
            btnWebToggle.setAlpha(1f);
            webToggleColor = target;
            return;
        }
        final android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(webToggleColor);
        btnWebToggle.setBackground(g);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofObject(
                new android.animation.ArgbEvaluator(), webToggleColor, target);
        va.setDuration(300);
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                g.setColor((Integer) a.getAnimatedValue());
            }
        });
        va.start();
        webToggleColor = target;

        // 只做淡入 + 背景色渐变：之前给图标加了 rotation(180)，
        // 动画结束后角度不会复位，播放三角形的尖端就被转到了左边。
        btnWebToggle.animate().cancel();
        // 转满 360°（结束角度与 0 等价，不会像 180° 那样把三角形转反）
        btnWebToggle.setRotation(0f);
        android.animation.ObjectAnimator spin =
                android.animation.ObjectAnimator.ofFloat(btnWebToggle, "rotation", 0f, 360f);
        spin.setDuration(380);
        spin.setInterpolator(new android.view.animation.DecelerateInterpolator());
        spin.start();
        btnWebToggle.setAlpha(0.2f);
        btnWebToggle.animate().alpha(1f).setDuration(260).start();
    }

    private boolean isWebRunning() {
        NetService s = core.service();
        return s != null && s.isWebShareRunning();
    }

    private void openWebPage() {
        String url = webUrl();
        if (url == null) {
            Ui.toast(this, "当前没有 Wi-Fi 地址");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(i);
        } catch (Throwable t) {
            Ui.toast(this, "没有可用的浏览器，地址已复制");
            copy(url);
        }
    }

    private String webUrl() {
        if (cachedIp == null) return null;
        NetService s = core.service();
        if (s == null || !s.isWebShareRunning()) return null; // 没在监听就别给死链
        int webPort = s.getWebPort();
        if (webPort <= 0) return null;
        return "http://" + cachedIp + ":" + webPort;
    }

    private void refresh() {
        List<Peer> peers = core.peers.list();
        adapter.setData(peers);
        tvOwnId.setText(core.prefs.shortId() + "  " + core.prefs.nickname());
        tvCount.setText(getString(R.string.nearby_count, peers.size()));
        NetService s = core.service();
        int port = s == null ? 0 : s.getPort();
        String mode = s == null ? "服务未启动" : "发现中";
        tvDiag.setText((cachedIp == null ? "无 Wi-Fi 地址" : cachedIp + ":" + port)
                + "  ·  " + peers.size() + " 台  ·  " + mode + "  ·  长按复制诊断");
        boolean webOn = isWebRunning();
        if (lastWebOn == null || lastWebOn.booleanValue() != webOn) {
            updateWebToggle(webOn, lastWebOn != null);
            lastWebOn = webOn;
        }
        updateLanState();
        if (tvWebUrl != null) {
            String url = webUrl();
            if (!webOn) {
                tvWebUrl.setText("网页端已关闭");
            } else {
                tvWebUrl.setText(url == null ? "需要连接 Wi-Fi" : url);
            }
            boolean canOpen = url != null && webOn;
            btnOpenWeb.setEnabled(canOpen);
            btnOpenWeb.setAlpha(canOpen ? 1f : 0.4f);
        }
    }

    // ---------------- 交互 ----------------

    private void editNickname() {
        Ui.input(this, getString(R.string.nickname_title), getString(R.string.nickname_hint),
                core.prefs.nickname(), new Ui.OnText() {
                    @Override
                    public void onText(String text) {
                        if (text == null || text.trim().length() == 0) return;
                        core.prefs.setNickname(text.trim());
                        NetService s = core.service();
                        if (s != null) s.refreshDiscovery();
                        refresh();
                        refreshSettings();
                    }
                });
    }

    private void manualAdd() {
        Ui.input(this, getString(R.string.manual_title), getString(R.string.manual_hint), "",
                new Ui.OnText() {
                    @Override
                    public void onText(String text) {
                        connectManual(text);
                    }
                });
    }

    private void connectManual(String text) {
        if (text == null) return;
        String v = text.trim();
        if (v.length() == 0) return;
        String host;
        int port = Const.DEFAULT_TCP_PORT;
        int idx = v.lastIndexOf(':');
        if (idx > 0) {
            host = v.substring(0, idx).trim();
            try {
                port = Integer.parseInt(v.substring(idx + 1).trim());
            } catch (Throwable t) {
                Ui.alert(this, getString(R.string.manual_title), getString(R.string.manual_invalid),
                        getString(R.string.know), null);
                return;
            }
        } else {
            host = v;
        }
        if (host.length() == 0 || port <= 0 || port > 65535) {
            Ui.alert(this, getString(R.string.manual_title), getString(R.string.manual_invalid),
                    getString(R.string.know), null);
            return;
        }
        NetService s = core.service();
        if (s == null) {
            Ui.alert(this, getString(R.string.manual_title), "网络服务尚未就绪，请稍后重试",
                    getString(R.string.know), null);
            return;
        }
        Ui.toast(this, getString(R.string.connecting_to, host + ":" + port));
        s.connectManual(host, port);
    }

    private void showPeerInfo(final Peer p) {
        String msg = p.displayName() + "\n短 ID：" + p.shortId()
                + "\n地址：" + p.address()
                + "\n来源：" + p.source
                + "\n状态：" + (p.isOnline(System.currentTimeMillis()) ? "在线" : "离线")
                + "\n信任：" + (p.trusted ? "已信任" : "未信任");
        Ui.confirm(this, "设备信息", msg,
                "复制地址", new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        copy(p.address());
                    }
                },
                "从列表移除", new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        core.peers.remove(p.deviceId);
                        refresh();
                    }
                });
    }

    private void copyDiagnostics() {
        NetService s = core.service();
        String info = "LanMesh 诊断\n"
                + "本机短ID: " + core.prefs.shortId() + "\n"
                + "昵称: " + core.prefs.nickname() + "\n"
                + "系统: " + Util.systemVersion() + "\n"
                + "IP: " + Util.safe(cachedIp) + "\n"
                + "TCP端口: " + (s == null ? "未启动" : String.valueOf(s.getPort())) + "\n"
                + "MIN_P2P_API: " + Const.MIN_P2P_API + "\n"
                + "设备数: " + core.peers.list().size() + "\n"
                + "发现: NSD + UDP组播(5s降级) + 手动";
        copy(info);
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("lanmesh", text));
                Ui.toast(this, "已复制");
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- Bus ----------------

    @Override
    public void onEvent(String type, Object payload) {
        if (Bus.PEERS_CHANGED.equals(type) || Bus.PEER_UPDATED.equals(type)) {
            refresh();
        } else if (Bus.TOAST.equals(type) && payload instanceof String) {
            Ui.toast(this, (String) payload);
        } else if (Bus.MANUAL_RESULT.equals(type) && payload instanceof String) {
            String r = (String) payload;
            if (r.startsWith("fail:")) {
                String err = r.substring(5);
                if (TextUtils.isEmpty(err)) err = "未知错误";
                Ui.confirm(this, getString(R.string.manual_title),
                        getString(R.string.connect_failed, err),
                        getString(R.string.retry), new Ui.OnClick() {
                            @Override
                            public void onClick() {
                                manualAdd();
                            }
                        },
                        getString(R.string.know), null);
            }
        } else if (Bus.P2P_STATE.equals(type) && payload instanceof String) {
            onP2pState((String) payload);
        } else if (Bus.P2P_DETAIL.equals(type) && payload instanceof String) {
            tvP2pLog.append("\n" + payload);
        } else if (Bus.PORT_MAP.equals(type) && payload instanceof String) {
            String v = (String) payload;
            if (v.startsWith("fail:")) {
                tvP2pLog.append("\n端口映射: 不可用（" + v.substring(5) + "）");
            } else {
                tvP2pLog.append("\n端口映射: " + v);
            }
        } else if (Bus.P2P_LOG.equals(type) && payload instanceof String) {
            // 状态变化时 syncP2pUi 会整体重建，这里只处理纯日志（文本消息）
            // 为避免重复，仅在当前不是状态切换瞬间追加
            tvP2pLog.append("\n" + payload);
        } else if (Bus.P2P_TEXT.equals(type) && payload instanceof String) {
            // 文本消息已由 P2P_LOG 统一处理
        } else if (Bus.FILE_REQUEST.equals(type) && payload instanceof NetService.FileRequest) {
            fileQueue.add((NetService.FileRequest) payload);
            showNextFileRequest();
        } else if (Bus.TRUST_REQUEST.equals(type) && payload instanceof NetService.TrustRequest) {
            enqueueTrust((NetService.TrustRequest) payload);
        }
    }

    private void showScanChoice(final boolean isAnswer) {
        Ui.sheet(this, new String[]{"扫描二维码", "粘贴文本"}, new Ui.OnClick[]{
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        startQrScan(isAnswer);
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        promptSignalText(isAnswer);
                    }
                }
        });
    }

    private void promptSignalText(final boolean isAnswer) {
        Ui.input(this,
                isAnswer ? "粘贴应答码" : "加入连接",
                isAnswer ? "粘贴对方发来的应答码" : "粘贴对方发来的邀请码", "",
                new Ui.OnText() {
                    @Override
                    public void onText(String text) {
                        if (text == null || text.trim().length() == 0) return;
                        if (isAnswer) P2pManager.get(MainActivity.this).applyAnswer(text.trim());
                        else P2pManager.get(MainActivity.this).acceptOffer(text.trim());
                    }
                });
    }

    private void startQrScan(boolean isAnswer) {
        pendingScanAnswer = isAnswer;
        // API 23+ 扫码前要拿相机权限；库本身不负责申请
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        launchScanner(isAnswer);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchScanner(pendingScanAnswer);
            } else {
                Ui.toast(this, "未授予相机权限，可改用粘贴文本");
            }
        }
    }

    private void launchScanner(boolean isAnswer) {
        try {
            com.google.zxing.integration.android.IntentIntegrator integrator =
                    new com.google.zxing.integration.android.IntentIntegrator(this);
            integrator.setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE);
            integrator.setPrompt("");
            integrator.setBeepEnabled(false);
            integrator.setOrientationLocked(false);
            integrator.setCaptureActivity(QrScanActivity.class);
            // 库没有 initiateScan(requestCode) 重载：自己拿 Intent 再 startActivityForResult
            Intent scanIntent = integrator.createScanIntent();
            android.util.Log.i("LanMeshQr", "launch scan -> " + scanIntent.getComponent()
                    + " action=" + scanIntent.getAction());
            startActivityForResult(scanIntent,
                    isAnswer ? REQ_SCAN_ANSWER : REQ_SCAN_JOIN);
        } catch (Throwable t) {
            Ui.toast(this, "无法启动扫码: " + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        android.util.Log.i("LanMeshQr", "onActivityResult req=" + requestCode
                + " res=" + resultCode + " hasData=" + (data != null));
        if (requestCode == REQ_P2P_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                android.net.Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                String name = "file";
                long size = 0;
                try {
                    androidx.documentfile.provider.DocumentFile doc =
                            androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri);
                    if (doc != null) {
                        name = doc.getName() == null ? "file" : doc.getName();
                        size = doc.length();
                    }
                } catch (Throwable ignored) {
                }
                if (size <= 0) {
                    Ui.toast(this, "无法读取文件大小");
                    return;
                }
                P2pManager.get(this).sendFile(uri, name, size);
            }
            return;
        }
        if (requestCode == REQ_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                android.net.Uri uri = data.getData();
                int flags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (flags == 0) {
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                }
                try {
                    getContentResolver().takePersistableUriPermission(uri, flags);
                    android.util.Log.i("LanMeshQr", "tree permission taken: " + uri);
                } catch (Throwable t) {
                    android.util.Log.w("LanMeshQr", "takePersistableUriPermission failed", t);
                }
                core.prefs.setSaveTreeUri(uri.toString());
                updateDownloadSettings();
                Ui.toast(this, "保存目录已设置");
            } else {
                Ui.toast(this, "未选择目录");
            }
            return;
        }
        String scanned = null;
        if (data != null) {
            // 先手动读 ZXing 的标准 extra，避免库版本差异导致 parse 返回 null
            if (data.hasExtra("SCAN_RESULT")) {
                scanned = data.getStringExtra("SCAN_RESULT");
            }
            if (scanned == null) {
                com.google.zxing.integration.android.IntentResult r =
                        com.google.zxing.integration.android.IntentIntegrator
                                .parseActivityResult(requestCode, resultCode, data);
                if (r != null) scanned = r.getContents();
            }
        }
        if (scanned != null) {
            scanned = scanned.trim();
            android.util.Log.i("LanMeshQr", "scan ok len=" + scanned.length()
                    + " req=" + requestCode);
            if (requestCode == REQ_SCAN_JOIN) {
                P2pManager.get(this).acceptOffer(scanned);
                return;
            }
            if (requestCode == REQ_SCAN_ANSWER) {
                P2pManager.get(this).applyAnswer(scanned);
                return;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private static String p2pStateTitle(String st) {
        if (P2pManager.ST_OFFER_READY.equals(st)) return "邀请码已生成";
        if (P2pManager.ST_ANSWER_READY.equals(st)) return "应答码已生成";
        if (P2pManager.ST_CONNECTING.equals(st)) return "正在建立连接…";
        if (P2pManager.ST_CONNECTED.equals(st)) return "P2P 已连接";
        if (P2pManager.ST_FAILED.equals(st)) return "P2P 连接失败";
        return "P2P 未连接";
    }

    /** 把 P2pManager 的状态/日志/按钮可见性一次性同步到 UI。 */
    private void syncP2pUi() {
        if (tvP2pState == null) return;
        P2pManager mgr = P2pManager.get(this);
        String st = mgr.state();
        StringBuilder sb = new StringBuilder();
        for (String l : mgr.logs()) sb.append(l).append('\n');
        tvP2pLog.setText(sb.length() == 0 ? "尚未连接" : sb.toString().trim());
        tvP2pState.setText(p2pStateTitle(st) + (mgr.detail().length() > 0 ? "\n\n" + mgr.detail() : ""));

        boolean offerReady = P2pManager.ST_OFFER_READY.equals(st);
        boolean answerReady = P2pManager.ST_ANSWER_READY.equals(st);
        boolean connected = P2pManager.ST_CONNECTED.equals(st);
        boolean connecting = P2pManager.ST_CONNECTING.equals(st);

        btnP2pAnswer.setVisibility(offerReady ? View.VISIBLE : View.GONE);
        btnP2pShowCode.setVisibility(offerReady || answerReady ? View.VISIBLE : View.GONE);
        p2pSendBar.setVisibility(connected ? View.VISIBLE : View.GONE);
        btnP2pOffer.setEnabled(!connecting);
        btnP2pJoin.setEnabled(!connecting);
        btnP2pOffer.setAlpha(connecting ? 0.5f : 1f);
        btnP2pJoin.setAlpha(connecting ? 0.5f : 1f);
    }

    private void onP2pState(String st) {
        P2pManager mgr = P2pManager.get(this);
        boolean onP2pPage = currentTab == TAB_P2P;
        if (P2pManager.ST_OFFER_READY.equals(st)) {
            btnP2pAnswer.setVisibility(View.VISIBLE);
            if (onP2pPage) {
                showSignalDialog("邀请码", "把这段邀请码发给对方；收到对方的应答码后，点「粘贴对方的应答码」。",
                        mgr.localCode());
            } else {
                Ui.toast(this, "邀请码已生成，去 P2P 页查看");
            }
        } else if (P2pManager.ST_ANSWER_READY.equals(st)) {
            btnP2pAnswer.setVisibility(View.GONE);
            if (onP2pPage) {
                showSignalDialog("应答码", "把这段应答码回给对方，等待连接建立。", mgr.localCode());
            } else {
                Ui.toast(this, "应答码已生成，去 P2P 页查看");
            }
        } else if (P2pManager.ST_CONNECTED.equals(st)) {
            btnP2pAnswer.setVisibility(View.GONE);
            Ui.toast(this, "P2P 已连接");
            tvP2pLog.append("\n已连接，可以发送文本");
        } else if (P2pManager.ST_FAILED.equals(st)) {
            btnP2pAnswer.setVisibility(View.GONE);
            if (!onP2pPage) {
                Ui.toast(this, "P2P 连接失败：" + mgr.detail());
                syncP2pUi();
                return;
            }
            Ui.confirm(this, "P2P 连接失败",
                    mgr.detail() + "\n\n可以改用局域网直连，或让对端用浏览器打开本机网页端。",
                    "打开局域网", new Ui.OnClick() {
                        @Override
                        public void onClick() {
                            showTab(TAB_LAN);
                        }
                    },
                    getString(R.string.know), null);
        } else if (P2pManager.ST_IDLE.equals(st)) {
            btnP2pAnswer.setVisibility(View.GONE);
        }
        if (P2pManager.ST_CONNECTING.equals(st)) {
            btnP2pAnswer.setVisibility(View.GONE);
        }
        syncP2pUi();
    }

    private android.app.Dialog dlgSignal;
    private android.graphics.Bitmap dlgSignalBmp;

    private void dismissSignalDialog() {
        if (dlgSignal != null) {
            try { dlgSignal.dismiss(); } catch (Throwable ignored) {}
            dlgSignal = null;
        }
    }

    private void showSignalDialog(String title, String hint, String code) {
        if (code == null || code.length() == 0) return;
        if (isFinishing() || isDestroyed()) return;
        dismissSignalDialog(); // 连点两次/邀请码→应答码：先关旧的，别叠窗
        final android.app.Dialog d = new android.app.Dialog(this, R.style.DarkDialogTheme);
        dlgSignal = d;
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_signal);
        ((TextView) d.findViewById(R.id.dlg_sig_title)).setText(title);
        ((TextView) d.findViewById(R.id.dlg_sig_hint)).setText(hint);
        ((TextView) d.findViewById(R.id.dlg_sig_code)).setText(code);
        final ImageView qr = (ImageView) d.findViewById(R.id.dlg_sig_qr);
        // 二维码在后台生成：1-2KB 信令串在低端机上要几百毫秒，主线程会直接卡住
        final int px = (int) (220 * getResources().getDisplayMetrics().density);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = QrUtil.encode(code, px);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed() || dlgSignal != d) {
                            if (bmp != null) bmp.recycle();
                            return;
                        }
                        if (bmp != null) {
                            dlgSignalBmp = bmp;
                            qr.setImageBitmap(bmp);
                            qr.setVisibility(View.VISIBLE);
                        } else {
                            qr.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }, "lanmesh-qr").start();
        d.findViewById(R.id.dlg_sig_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(((TextView) d.findViewById(R.id.dlg_sig_code)).getText().toString());
            }
        });
        d.findViewById(R.id.dlg_sig_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                if (dlgSignal == d) dlgSignal = null;
                if (dlgSignalBmp != null) {
                    try { dlgSignalBmp.recycle(); } catch (Throwable ignored) {}
                    dlgSignalBmp = null;
                }
            }
        });
        d.show();
        Ui.animateDialogIn(d, false);
    }

    private final java.util.ArrayDeque<NetService.FileRequest> fileQueue =
            new java.util.ArrayDeque<NetService.FileRequest>();
    private boolean fileShowing = false;

    private void showNextFileRequest() {
        if (fileShowing) return;
        final NetService.FileRequest req = fileQueue.poll();
        if (req == null) return;
        if (isFinishing() || isDestroyed()) return;
        fileShowing = true;
        android.app.Dialog dlg = Ui.confirm(this, "收到文件",
                req.fileName + "\n" + Util.humanSize(req.size)
                        + "\n\n对方正在等待你的确认（60 秒后自动拒绝）。",
                "接受", new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        answerFile(req, true);
                    }
                },
                "拒绝", new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        answerFile(req, false);
                    }
                });
        if (dlg != null) {
            // 点外部/返回键关掉 = 拒绝，并且必须推进队列
            dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface dialog) {
                    if (fileShowing) answerFile(req, false);
                }
            });
        }
    }

    private void answerFile(NetService.FileRequest req, boolean accept) {
        NetService s = core.service();
        if (s != null) s.answerFileAccept(req.deviceId, accept);
        fileShowing = false;
        tvDiag.post(new Runnable() {
            @Override
            public void run() {
                showNextFileRequest();
            }
        });
    }

    private void enqueueTrust(NetService.TrustRequest req) {
        trustQueue.add(req);
        showNextTrust();
    }

    private void showNextTrust() {
        if (trustShowing) return;
        final NetService.TrustRequest req = trustQueue.poll();
        if (req == null) return;
        trustShowing = true;
        String msg = getString(R.string.trust_msg,
                TextUtils.isEmpty(req.nickname) ? getString(R.string.unknown_peer) : req.nickname,
                Util.shortId(req.deviceId));
        android.app.Dialog dlg = Ui.confirm(this, getString(R.string.trust_title), msg,
                getString(R.string.trust_allow), new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        answer(req, true);
                    }
                },
                getString(R.string.trust_deny), new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        answer(req, false);
                    }
                });
        // 点外部/返回键也能关掉：不复位标志的话 trustShowing 永久为 true，
        // 之后所有信任请求都只入队不弹窗，对端一直等到超时
        if (dlg != null) {
            dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface dialog) {
                    if (trustShowing) answer(req, false);
                }
            });
        }
    }

    private void answer(NetService.TrustRequest req, boolean allow) {
        NetService s = core.service();
        if (s != null) s.answerTrust(req.deviceId, allow);
        trustShowing = false;
        refresh();
        tvDiag.post(new Runnable() {
            @Override
            public void run() {
                showNextTrust();
            }
        });
    }
}
