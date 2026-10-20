package com.jiuyue.lanmesh.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

/**
 * 纯工具方法集合：不做 IO 副作用以外的状态变更。
 *
 * 全部使用 API 19 可用的类（无 java.time / Stream），保证 Dalvik 与 ART 都能跑。
 */
public final class Util {

    private static final String TAG = "LanMeshUtil";

    private Util() {}

    public static String shortId(String deviceId) {
        if (deviceId == null || deviceId.length() < 8) return "--------";
        // 去掉 UUID 的横线后取前 8 位，用户可读且碰撞概率可忽略
        String hex = deviceId.replace("-", "").toUpperCase(Locale.US);
        return hex.substring(0, 8);
    }

    public static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /** 流式计算 SHA-256，内存占用恒定 8KB，1GB 文件也不会 OOM。 */
    public static String sha256(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String sha256(File file) throws Exception {
        if (file == null || !file.isFile()) return "";
        FileInputStream in = new FileInputStream(file);
        try {
            return sha256(in);
        } finally {
            closeQuietly(in);
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f KB", v);
        v /= 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        v /= 1024.0;
        return String.format(Locale.US, "%.2f GB", v);
    }

    public static String humanSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        return humanSize(bytesPerSec) + "/s";
    }

    public static String formatTime(long ts) {
        // API 19 没有 java.time，用 SimpleDateFormat（每次新建，避免线程安全问题）
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        return f.format(new Date(ts));
    }

    public static String formatTimeFull(long ts) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        return f.format(new Date(ts));
    }

    /** 当前系统版本的可读字符串，例如 "4.4.4 (API 19)"。 */
    public static String systemVersion() {
        return Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    /**
     * 取"真正对外的"局域网 IPv4。
     *
     * 直接遍历网卡会踩坑：设备可能同时有 wlan0（主 Wi-Fi）和 wlan1（双 Wi-Fi 加速/
     * 热点），顺序不固定。之前取到了 wlan1 的 192.168.0.108，而其他设备在同一
     * 网段里根本路由不到它，表现就是"网页端在别的设备上打不开"。
     * 这里优先问系统当前活动网络，其次问 Wi-Fi DHCP，最后才回退遍历网卡。
     */
    public static String localIpv4(Context ctx) {
        if (ctx == null) return localIpv4();
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.Network n = cm.getActiveNetwork();
                    if (n != null) {
                        android.net.LinkProperties lp = cm.getLinkProperties(n);
                        if (lp != null) {
                            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                                InetAddress a = la.getAddress();
                                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                                    return a.getHostAddress();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "localIpv4(ctx) failed", t);
        }
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.DhcpInfo d = wm.getDhcpInfo();
                if (d != null && d.ipAddress != 0) {
                    int ip = d.ipAddress;
                    return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                            + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
                }
            }
        } catch (Throwable ignored) {
        }
        return localIpv4();
    }

    /** 是否有非链路本地的公网 IPv6：有的话 P2P 可以绕过 NAT 直连。 */
    public static boolean hasGlobalIpv6() {
        try {
            java.util.Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return false;
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof java.net.Inet6Address
                            && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 取本机局域网 IPv4 地址；无网络时返回 null。 */
    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            String fallback = null;
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName();
                boolean wlan = name != null && name.startsWith("wlan");
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                for (InetAddress addr : Collections.list(addrs)) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    // 优先返回 Wi-Fi 地址，避免拿到 rmnet/tun 上的运营商地址
                    if (wlan) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Throwable t) {
            Log.w(TAG, "localIpv4 failed", t);
        }
        return null;
    }

    public static boolean hasPermission(Context ctx, String permission) {
        if (Build.VERSION.SDK_INT < 23) return true; // 安装即授予
        try {
            return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 传入的文件名可能含路径分隔符或非法字符，统一清洗。 */
    public static String safeFileName(String name) {
        if (name == null || name.trim().length() == 0) return "file";
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (base.length() == 0) base = "file";
        if (base.length() > 120) {
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, 100) + base.substring(dot);
            } else {
                base = base.substring(0, 120);
            }
        }
        return base;
    }

    /** 重名文件自动加 (1)(2)…，避免覆盖用户已收到的文件。 */
    public static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File cand = new File(dir, stem + "(" + i + ")" + ext);
            if (!cand.exists()) return cand;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }
}
