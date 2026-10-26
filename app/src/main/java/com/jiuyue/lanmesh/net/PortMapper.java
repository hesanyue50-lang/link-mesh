package com.jiuyue.lanmesh.net;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 低成本 NAT 穿透：UPnP-IGD / NAT-PMP 自动端口映射。
 *
 * 为什么不用 TURN：
 *  TURN 需要公网服务器与带宽，违反"零云成本"红线；而家庭宽带路由器
 *  绝大多数支持 UPnP-IGD 或 NAT-PMP，让路由器替我们开一个端口是零成本的。
 *
 * 适用范围：
 *  - UPnP/NAT-PMP 只在**自己的路由器**上有效（家庭/办公室 Wi-Fi）；
 *  - 移动网络（运营商级 NAT）无法映射，会直接返回失败，由上层提示；
 *  - 映射成功后，阶段一的 TCP 服务可以被公网 IP:port 直接访问；
 *    WebRTC 的 ICE 端口由内部随机分配，无法直接复用该映射，
 *    因此这里主要为 TCP 直连服务（以及后续的信令中转）服务。
 *
 * 安全：只映射本机一个 TCP 端口，租期 1 小时，退出时不做显式删除
 * （路由器租期到期自动回收），避免异常退出留下永久映射。
 */
public final class PortMapper {

    private static final String TAG = "LanMeshPortMap";

    public interface Callback {
        void onResult(String method, String externalIp, int externalPort, String error);
    }

    private PortMapper() {}

    private static final java.util.concurrent.atomic.AtomicBoolean MAPPING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile Thread renewThread;

    public static void map(final Context ctx, final int localPort, final Callback cb) {
        // 重复开局域网会再开一条映射，同一外部端口在路由器上直接 718 冲突
        if (!MAPPING.compareAndSet(false, true)) {
            cb.onResult(null, null, 0, "端口映射正在进行");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                String natpmpError = null;
                try {
                    Result r = tryNatPmp(ctx, localPort);
                    if (r != null) {
                        cb.onResult("NAT-PMP", r.ip, r.port, null);
                        startRenew(ctx, localPort);
                        return;
                    }
                } catch (Throwable t) {
                    natpmpError = t.getMessage();
                    Log.w(TAG, "natpmp failed", t);
                }
                try {
                    Result r = tryUpnp(ctx, localPort);
                    if (r != null) {
                        cb.onResult("UPnP", r.ip, r.port, null);
                        startRenew(ctx, localPort);
                        return;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "upnp failed", t);
                    if (natpmpError == null) natpmpError = t.getMessage();
                }
                cb.onResult(null, null, 0,
                        natpmpError == null ? "路由器未开启 UPnP/NAT-PMP" : natpmpError);
                } finally {
                    MAPPING.set(false);
                }
            }
        }, "lanmesh-portmap").start();
    }

    /**
     * 租期 3600 秒，但路由器常在租期过半就回收；每 1800 秒重发一次映射
     * （AddPortMapping 同端口是幂等的），连续两次都失败就放弃。
     */
    private static void startRenew(final Context ctx, final int localPort) {
        Thread old = renewThread;
        if (old != null && old.isAlive()) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1800000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    boolean ok = false;
                    for (int i = 0; i < 2 && !ok; i++) {
                        try {
                            Result r = tryNatPmp(ctx, localPort);
                            if (r == null) r = tryUpnp(ctx, localPort);
                            ok = r != null;
                        } catch (Throwable t2) {
                            Log.w(TAG, "renew failed", t2);
                        }
                    }
                    if (!ok) {
                        Log.w(TAG, "port mapping renewal gave up");
                        return;
                    }
                }
            }
        }, "lanmesh-portmap-renew");
        t.setDaemon(true);
        renewThread = t;
        t.start();
    }

    /** 关闭局域网时停止续约。 */
    public static void stopRenew() {
        Thread t = renewThread;
        renewThread = null;
        if (t != null) t.interrupt();
        MAPPING.set(false);
    }

    private static class Result {
        String ip;
        int port;
    }

    // ---------------- NAT-PMP ----------------

    private static Result tryNatPmp(Context ctx, int localPort) throws Exception {
        InetAddress gw = gateway(ctx);
        if (gw == null) return null;
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(2000);
        try {
            // 1) 请求外部地址（opcode 0）
            byte[] req = new byte[]{0, 0};
            socket.send(new DatagramPacket(req, req.length, new InetSocketAddress(gw, 5351)));
            byte[] buf = new byte[32];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            // 只接受来自网关 5351 的应答：同网段任何主机都能抢先伪造一个外部 IP
            long until = System.currentTimeMillis() + 2000;
            while (true) {
                if (System.currentTimeMillis() > until) return null;
                p.setLength(buf.length);
                socket.receive(p);
                if (!gw.equals(p.getAddress()) || p.getPort() != 5351) continue;
                if (p.getLength() >= 12 && buf[1] == (byte) 128 && buf[2] == 0) break;
                return null;
            }
            String externalIp = (buf[8] & 0xFF) + "." + (buf[9] & 0xFF) + "."
                    + (buf[10] & 0xFF) + "." + (buf[11] & 0xFF);
            // RFC 6886：0.0.0.0 表示"公网地址与本机相同"，不能当作可用外部地址
            if ("0.0.0.0".equals(externalIp)) {
                Log.i(TAG, "natpmp reports 0.0.0.0, no NAT");
                return null;
            }

            // 2) 映射 TCP 端口（opcode 2）
            byte[] m = new byte[12];
            m[0] = 0;
            m[1] = 2;
            m[2] = 0;
            m[3] = 0;
            m[4] = (byte) (localPort >> 8);
            m[5] = (byte) localPort;
            m[6] = m[4];
            m[7] = m[5];
            int lifetime = 3600;
            m[8] = (byte) (lifetime >> 24);
            m[9] = (byte) (lifetime >> 16);
            m[10] = (byte) (lifetime >> 8);
            m[11] = (byte) lifetime;
            socket.send(new DatagramPacket(m, m.length, new InetSocketAddress(gw, 5351)));
            byte[] rbuf = new byte[32];
            DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
            socket.receive(rp);
            if (rp.getLength() < 16 || rbuf[1] != (byte) 130 || rbuf[2] != 0) return null;
            int mapped = ((rbuf[10] & 0xFF) << 8) | (rbuf[11] & 0xFF);
            if (mapped <= 0) return null;
            Result r = new Result();
            r.ip = externalIp;
            r.port = mapped;
            Log.i(TAG, "NAT-PMP mapped " + externalIp + ":" + mapped);
            return r;
        } finally {
            socket.close();
        }
    }

    private static InetAddress gateway(Context ctx) {
        // API 23+ 走默认路由：Wi-Fi / 以太网 / 热点 / USB 共享都能拿到网关，
        // DhcpInfo 只有 Wi-Fi 有，其余场景会静默返回 null（NAT-PMP 直接放弃）
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network n = cm == null ? null : cm.getActiveNetwork();
                android.net.LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
                if (lp != null) {
                    for (android.net.RouteInfo r : lp.getRoutes()) {
                        if (r.isDefaultRoute() && r.getGateway() != null
                                && !r.getGateway().isAnyLocalAddress()) {
                            return r.getGateway();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            DhcpInfo info = wm.getDhcpInfo();
            if (info == null || info.gateway == 0) return null;
            int g = info.gateway;
            String ip = (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "."
                    + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
            return InetAddress.getByName(ip);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- UPnP IGD ----------------

    private static Result tryUpnp(Context ctx, int localPort) throws Exception {
        List<String> locations = ssdpDiscover(ctx);
        for (String location : locations) {
            try {
                String xml = httpGet(location, 4000);
                String[] svc = parseIgdService(xml);
                if (svc == null) continue;
                String controlUrl = svc[0];
                String serviceType = svc[1];
                String fullControl = resolveUrl(location, controlUrl);

                String ip = soap(fullControl, serviceType,
                        "<u:GetExternalIPAddress xmlns:u=\"" + serviceType + "\"></u:GetExternalIPAddress>");
                String externalIp = match(ip, "<NewExternalIPAddress>(.*?)</NewExternalIPAddress>");
                if (externalIp == null || externalIp.length() == 0) continue;

                String internalIp = localIp(ctx);
                if (internalIp == null || internalIp.length() == 0
                        || "null".equals(internalIp)) {
                    continue;
                }
                // 先试随机外部端口：默认端口 45900 是全部设备共用的，
                // 同一局域网第二台设备必然拿到 718 ConflictInMappingEntry
                int[] candidates = {randomExternalPort(), localPort, randomExternalPort()};
                for (int extPort : candidates) {
                    String body = "<u:AddPortMapping xmlns:u=\"" + serviceType + "\">"
                            + "<NewRemoteHost></NewRemoteHost>"
                            + "<NewExternalPort>" + extPort + "</NewExternalPort>"
                            + "<NewProtocol>TCP</NewProtocol>"
                            + "<NewInternalPort>" + localPort + "</NewInternalPort>"
                            + "<NewInternalClient>" + internalIp + "</NewInternalClient>"
                            + "<NewEnabled>1</NewEnabled>"
                            + "<NewPortMappingDescription>LanMesh-" + localPort + "</NewPortMappingDescription>"
                            + "<NewLeaseDuration>3600</NewLeaseDuration>"
                            + "</u:AddPortMapping>";
                    String resp = soap(fullControl, serviceType, body);
                    if (resp != null) {
                        Result r = new Result();
                        r.ip = externalIp;
                        r.port = extPort;
                        Log.i(TAG, "UPnP mapped " + externalIp + ":" + extPort
                                + " -> " + internalIp + ":" + localPort);
                        return r;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "upnp try " + location + " failed: " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * 双层 NAT：先向默认网关发 SSDP；再对常见上一级网关（192.168.1.1 / 192.168.0.1）
     * 发**单播** M-SEARCH。部分光猫会响应单播 SSDP，能多穿透一层。
     */
    private static List<String> gatewayCandidates(Context ctx) {
        List<String> out = new ArrayList<String>();
        InetAddress gw = gateway(ctx);
        if (gw != null) out.add(gw.getHostAddress());
        String[] common = new String[]{"192.168.1.1", "192.168.0.1", "192.168.31.1"};
        for (String s : common) {
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    private static List<String> ssdpDiscover(Context ctx) throws Exception {
        List<String> out = new ArrayList<String>();
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(1200);
        try {
            String msg = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 1\r\n"
                    + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
            byte[] data = msg.getBytes("UTF-8");
            InetAddress group = InetAddress.getByName("239.255.255.250");

            List<InetAddress> targets = new ArrayList<InetAddress>();
            targets.add(group);
            try {
                for (String g : gatewayCandidates(ctx)) {
                    InetAddress a = InetAddress.getByName(g);
                    if (!targets.contains(a)) targets.add(a);
                }
            } catch (Throwable ignored) {
            }

            for (int round = 0; round < 2; round++) {
                for (InetAddress target : targets) {
                    try {
                        socket.send(new DatagramPacket(data, data.length, target, 1900));
                    } catch (Throwable ignored) {
                    }
                }
                long end = System.currentTimeMillis() + 1500;
                while (System.currentTimeMillis() < end) {
                    byte[] buf = new byte[2048];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    }
                    String resp = new String(buf, 0, p.getLength(), "UTF-8");
                    String loc = match(resp, "(?i)LOCATION:\\s*(\\S+)");
                    if (loc != null && !out.contains(loc)) out.add(loc);
                }
                if (!out.isEmpty()) break;
            }
        } finally {
            socket.close();
        }
        return out;
    }

    /** 返回 [controlURL, serviceType]，找不到返回 null。 */
    private static String[] parseIgdService(String xml) {
        Pattern p = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        while (m.find()) {
            String block = m.group(1);
            String type = match(block, "<serviceType>(.*?)</serviceType>");
            if (type == null) continue;
            if (!type.contains("WANIPConnection") && !type.contains("WANPPPConnection")) continue;
            String control = match(block, "<controlURL>(.*?)</controlURL>");
            if (control == null) continue;
            return new String[]{control, type};
        }
        return null;
    }

    private static String soap(String controlUrl, String serviceType, String actionXml) {
        HttpURLConnection con = null;
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body>" + actionXml + "</s:Body></s:Envelope>";
            URL url = new URL(controlUrl);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(4000);
            con.setReadTimeout(6000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            con.setRequestProperty("SOAPAction", "\"" + serviceType + "#"
                    + (actionXml.contains("AddPortMapping") ? "AddPortMapping" : "GetExternalIPAddress") + "\"");
            OutputStream os = con.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = con.getResponseCode();
            String resp = readAll(code >= 400 ? con.getErrorStream() : con.getInputStream());
            return code == 200 ? resp : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String httpGet(String urlStr, int timeout) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setConnectTimeout(timeout);
        con.setReadTimeout(timeout);
        try {
            return readAll(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 512 * 1024) {
                throw new IllegalStateException("response too large");
            }
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String resolveUrl(String base, String path) {
        try {
            if (path.startsWith("http://") || path.startsWith("https://")) return path;
            URL b = new URL(base);
            if (path.startsWith("/")) {
                return b.getProtocol() + "://" + b.getHost()
                        + (b.getPort() > 0 ? ":" + b.getPort() : "") + path;
            }
            String dir = base.substring(0, base.lastIndexOf('/') + 1);
            return dir + path;
        } catch (Throwable t) {
            return path;
        }
    }

    private static String match(String text, String regex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String localIp(Context ctx) {
        // 必须传 ctx：不传会走网卡遍历兜底，多网卡（Wi-Fi + 热点）时可能取到另一张网卡的地址，
        // 路由器照样返回 200，但外部访问进不来（"假成功"）
        return com.jiuyue.lanmesh.core.Util.localIpv4(ctx);
    }

    private static final java.util.Random RND = new java.util.Random();

    private static int randomExternalPort() {
        return 40000 + RND.nextInt(20000);
    }
}
