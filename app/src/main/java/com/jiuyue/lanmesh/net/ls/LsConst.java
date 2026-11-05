package com.jiuyue.lanmesh.net.ls;

/** LocalSend 协议常量（版本号必须与官方客户端一致，否则对方可能不认）。 */
public final class LsConst {

    /** 官方 announce 里固定的协议版本 */
    public static final String VERSION = "2.2";
    /** 官方默认端口：既是 HTTP 端口也是多播端口 */
    public static final int HTTP_PORT = 53317;
    public static final String API = "/api/localsend/v2";

    private LsConst() {
    }
}
