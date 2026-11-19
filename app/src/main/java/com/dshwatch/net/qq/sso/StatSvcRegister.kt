package com.dshwatch.net.qq.sso

/**
 * `StatSvc.register`：把这条长连接登记成「某个人在线」。
 *
 * 为什么必须发这一步：`wtlogin.exchange_emp` 只证明「这张 tgt 还有效」，
 * 服务端这时并不知道这条 TCP 要开始收消息，也不给它分配推送队列。
 * 直接发 `MessageSvc.PbGetMsg` 会被回 **-10008** —— 那个码的意思是
 * 「这条连接没登录」，不是包写错了，改包体怎么改都出不来。
 *
 * 结构是两层 Tars：
 *
 * ```
 * RequestPacket { servantName="PushService", funcName="SvcReqRegister", sBuffer=? }
 *   └─ sBuffer: SvcReqRegister { uin, bid, status, guid, deviceName, ... }
 * ```
 */
internal object StatSvcRegister {

    /** 三个终端位（手机 / PC / 平板）一起占，官方在线态就是这么报的 */
    private const val BID = 7L

    /** 11 = 在线。31 离开、41 忙碌、50 隐身，别写错 */
    private const val STATUS_ONLINE = 11

    /** 2052 = 简体中文 */
    private const val LOCALE_CN = 2052

    fun online(
        uin: Long,
        guid: ByteArray,
        deviceName: String,
        osVersion: String,
        sdkLevel: Long,
    ): ByteArray {
        // 内层：SvcReqRegister。字段必须按 tag 升序写
        val inner = JceWriter(256).apply {
            long(0, uin)
            long(1, BID)
            int(4, STATUS_ONLINE)
            long(11, sdkLevel) // iOSVersion：这里报的是 Android API 级别
            byte(12, 1) // cNetType：1 = WiFi
            bytes(16, guid)
            int(17, LOCALE_CN)
            string(19, deviceName) // strDevName
            string(20, deviceName) // strDevType
            string(21, osVersion) // strOSVer
            byte(22, 1) // bOpenPush：不开就收不到推送
        }.toByteArray()

        // 外层：RequestPacket。tag 从 1 开始，比内层整体错一位
        return JceWriter(512).apply {
            short(1, 3) // 协议版本，固定 3
            byte(2, 0) // 普通请求包
            int(3, 0) // iMessageType
            int(4, 0) // requestId
            string(5, "PushService")
            string(6, "SvcReqRegister")
            bytes(7, inner)
            int(8, 0) // iTimeout
        }.toByteArray()
    }

    /**
     * 响应里的 replyCode（tag 2）。0 表示登记成功。
     *
     * 读不到就当 0 —— 老版本服务端偶尔不回这个字段，此时 SSO 层返回码为 0 已足够。
     */
    fun replyCode(resp: ByteArray): Int {
        val r = JceReader(resp)
        while (true) {
            val f = r.next() ?: return 0
            if (f.tag == 2 && f.value is Int) return f.value
        }
    }

    /** 响应里的 iHelloInterval（tag 12），服务端建议的心跳间隔（秒） */
    fun helloInterval(resp: ByteArray): Int {
        val r = JceReader(resp)
        while (true) {
            val f = r.next() ?: return 0
            if (f.tag == 12 && f.value is Int) return f.value
        }
    }
}
