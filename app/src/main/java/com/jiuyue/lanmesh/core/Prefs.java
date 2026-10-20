package com.jiuyue.lanmesh.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 本地持久化：身份、昵称、信任列表、被拒绝列表。
 *
 * 无账号体系，所有数据只存在于本机 SharedPreferences，卸载即消失。
 */
public class Prefs {

    private static final String FILE = "lanmesh_prefs";
    private static final String K_DEVICE_ID = "device_id";
    private static final String K_NICKNAME = "nickname";
    private static final String K_TRUSTED = "trusted_ids";
    private static final String K_DENIED = "denied_ids";
    private static final String K_LAST_PEER = "last_peer_"; // + deviceId -> "ip:port"
    private static final String K_THEME = "theme_mode";      // 0=跟随系统 1=浅色 2=深色
    private static final String K_LAST_TAB = "last_tab";     // 主题重建后停留的页面
    private static final String K_AUTO_ACCEPT = "auto_accept_files";
    private static final String K_DOWNLOAD_DIR = "download_dir"; // app / public / tree
    private static final String K_SAVE_TREE = "save_tree_uri";
    private static final String K_LAN_ENABLED = "lan_enabled";
    private static final String K_PAGE_ORDER = "page_order";
    private static final String K_PAGE_HIDDEN = "page_hidden";
    private static final String K_HOME_PAGE = "home_page";
    // 兼容旧键（已废弃，仅保留常量避免编译错误）
    private static final String K_HIDE_P2P = "hide_p2p";
    private static final String K_P2P_LAST = "p2p_last";
    private static final String K_WEB_SHARE = "web_share_enabled";
    private static final String K_HOME_TAB = "home_tab";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        ensureDeviceId();
    }

    private String ensureDeviceId() {
        String id = sp.getString(K_DEVICE_ID, null);
        if (id == null || id.length() < 8) {
            id = UUID.randomUUID().toString();
            sp.edit().putString(K_DEVICE_ID, id).apply();
        }
        return id;
    }

    public String deviceId() {
        return ensureDeviceId();
    }

    public String shortId() {
        return Util.shortId(deviceId());
    }

    public String nickname() {
        String n = sp.getString(K_NICKNAME, null);
        if (n == null || n.trim().length() == 0) {
            // 默认昵称带短 ID 后 4 位，方便多台同型号设备区分
            String id = deviceId().replace("-", "");
            n = "设备-" + id.substring(id.length() - 4).toUpperCase();
        }
        return n;
    }

    public void setNickname(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() > 24) n = n.substring(0, 24);
        sp.edit().putString(K_NICKNAME, n).apply();
    }

    public Set<String> trusted() {
        // 返回副本，避免调用方直接改 SharedPreferences 内部集合
        return new HashSet<String>(sp.getStringSet(K_TRUSTED, new HashSet<String>()));
    }

    public boolean isTrusted(String deviceId) {
        return deviceId != null && trusted().contains(deviceId);
    }

    public void trust(String deviceId) {
        if (deviceId == null) return;
        Set<String> s = trusted();
        s.add(deviceId);
        sp.edit().putStringSet(K_TRUSTED, s).apply();
        // 允许过的设备从拒绝列表移除，语义上"改主意"成立
        Set<String> d = denied();
        if (d.remove(deviceId)) {
            sp.edit().putStringSet(K_DENIED, d).apply();
        }
    }

    public Set<String> denied() {
        return new HashSet<String>(sp.getStringSet(K_DENIED, new HashSet<String>()));
    }

    public boolean isDenied(String deviceId) {
        return deviceId != null && denied().contains(deviceId);
    }

    public void deny(String deviceId) {
        if (deviceId == null) return;
        Set<String> s = denied();
        s.add(deviceId);
        sp.edit().putStringSet(K_DENIED, s).apply();
    }

    public void rememberPeerAddress(String deviceId, String host, int port) {
        if (deviceId == null || host == null) return;
        sp.edit().putString(K_LAST_PEER + deviceId, host + ":" + port).apply();
    }

    public String lastPeerAddress(String deviceId) {
        return sp.getString(K_LAST_PEER + deviceId, null);
    }

    /** 0=跟随系统，1=浅色，2=深色。 */
    public int themeMode() {
        return sp.getInt(K_THEME, 0);
    }

    public void setThemeMode(int mode) {
        if (mode < 0 || mode > 2) mode = 0;
        sp.edit().putInt(K_THEME, mode).apply();
    }

    public int lastTab() {
        return sp.getInt(K_LAST_TAB, 0);
    }

    public void setLastTab(int tab) {
        sp.edit().putInt(K_LAST_TAB, tab).apply();
    }

    /** 是否自动接收文件；关闭后需要手动点"接受"。 */
    /** SAF 选定的保存目录（content:// tree URI），空表示用应用专属目录。 */
    public String saveTreeUri() {
        String v = sp.getString(K_SAVE_TREE, "");
        return v == null ? "" : v;
    }

    public void setSaveTreeUri(String uri) {
        sp.edit().putString(K_SAVE_TREE, uri == null ? "" : uri).apply();
    }

    public boolean lanEnabled() {
        return sp.getBoolean(K_LAN_ENABLED, true);
    }

    public void setLanEnabled(boolean v) {
        sp.edit().putBoolean(K_LAN_ENABLED, v).apply();
    }

    /** 页面顺序（0=局域网 1=P2P 2=设置），缺省补全。 */
    public java.util.List<Integer> pageOrder() {
        String s = sp.getString(K_PAGE_ORDER, "0,1,2");
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        if (s != null) {
            for (String part : s.split(",")) {
                try {
                    int k = Integer.parseInt(part.trim());
                    if (k >= 0 && k <= 2 && !out.contains(k)) out.add(k);
                } catch (Throwable ignored) {
                }
            }
        }
        for (int k = 0; k < 3; k++) if (!out.contains(k)) out.add(k);
        return out;
    }

    public void setPageOrder(java.util.List<Integer> order) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(order.get(i));
        }
        sp.edit().putString(K_PAGE_ORDER, sb.toString()).apply();
    }

    public java.util.Set<Integer> pageHidden() {
        java.util.Set<Integer> out = new java.util.HashSet<Integer>();
        String s = sp.getString(K_PAGE_HIDDEN, "");
        if (s != null) {
            for (String part : s.split(",")) {
                try {
                    int k = Integer.parseInt(part.trim());
                    if (k >= 0 && k <= 2) out.add(k);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    public void setPageHidden(java.util.Set<Integer> hidden) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Integer k : hidden) {
            if (!first) sb.append(',');
            sb.append(k);
            first = false;
        }
        sp.edit().putString(K_PAGE_HIDDEN, sb.toString()).apply();
    }

    public int homePage() {
        return sp.getInt(K_HOME_PAGE, 0);
    }

    public void setHomePage(int key) {
        sp.edit().putInt(K_HOME_PAGE, key).apply();
    }

    public boolean hideP2p() {
        return sp.getBoolean(K_HIDE_P2P, false);
    }

    public void setHideP2p(boolean v) {
        sp.edit().putBoolean(K_HIDE_P2P, v).apply();
    }

    public boolean p2pLast() {
        return sp.getBoolean(K_P2P_LAST, false);
    }

    public void setP2pLast(boolean v) {
        sp.edit().putBoolean(K_P2P_LAST, v).apply();
    }

    public boolean autoAcceptFiles() {
        return sp.getBoolean(K_AUTO_ACCEPT, true);
    }

    public void setAutoAcceptFiles(boolean v) {
        sp.edit().putBoolean(K_AUTO_ACCEPT, v).apply();
    }

    /** 下载目录：app=应用专属目录，public=系统下载目录。 */
    public String downloadDir() {
        return sp.getString(K_DOWNLOAD_DIR, "app");
    }

    public void setDownloadDir(String v) {
        sp.edit().putString(K_DOWNLOAD_DIR, v == null ? "app" : v).apply();
    }

    public boolean webShareEnabled() {
        return sp.getBoolean(K_WEB_SHARE, true);
    }

    public void setWebShareEnabled(boolean v) {
        sp.edit().putBoolean(K_WEB_SHARE, v).apply();
    }

    /** 打开应用默认进入的页面（0 局域网 / 1 P2P / 2 设置）。 */
    public int homeTab() {
        return sp.getInt(K_HOME_TAB, 0);
    }

    public void setHomeTab(int tab) {
        sp.edit().putInt(K_HOME_TAB, tab).apply();
    }

    public void untrust(String deviceId) {
        if (deviceId == null) return;
        Set<String> s = trusted();
        if (s.remove(deviceId)) {
            sp.edit().putStringSet(K_TRUSTED, s).apply();
        }
    }
}
