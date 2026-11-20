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
            int(4, STATUS_ONLINE) // iStatus：11 = 在线
            byte(8, 0) // bKikPC
            byte(9, 0) // bKikWeak
            long(10, 0) // timeStamp
            long(11, sdkLevel) // iOSVersion：这里报的是 Android API 级别
            byte(12, 1) // cNetType：1 = WiFi
            byte(14, 0) // bRegType
            bytes(16, guid)
            int(17, LOCALE_CN)
            string(19, deviceName) // strDevName
            string(20, deviceName) // strDevType
            string(21, osVersion) // strOSVer
            byte(22, 1) // bOpenPush：不开就收不到推送
            long(23, 0) // iLargeSeq
            byte(34, 0) // bIsSetStatus
            byte(36, 0) // bSetMute
        }.toByteArray()

        // 外层：RequestPacket。tag 从 1 开始，比内层整体错一位
        return TarsPacket.request(
            servant = "PushService",
            func = "SvcReqRegister",
            sBuffer = TarsPacket.sBufferV3("SvcReqRegister", inner),
        )
    }

    /**
     * 响应里的 replyCode（tag 2）。0 表示登记成功。
     *
     * 响应正文是 `RequestPacket`，真正的内容埋在 `sBuffer` 的版本 3 表里，
     * 剥壳交给 [TarsPacket.unwrapStruct]；剥不出来时按裸结构读 ——
     * 老版本服务端偶尔直接回裸结构。
     */
    fun replyCode(resp: ByteArray): Int = replyCodeIn(unwrap(resp))

    /** 响应里的 iHelloInterval（tag 12），服务端建议的心跳间隔（秒） */
    fun helloInterval(resp: ByteArray): Int = TarsPacket.intOf(unwrap(resp), 12) ?: 0

    private fun unwrap(resp: ByteArray): ByteArray =
        runCatching { TarsPacket.unwrapStruct(resp) }.getOrNull() ?: resp

    /**
     * 这个包到底是不是 `SvcReqRegister` 的回包。
     *
     * 只看「能不能读出 replyCode」不够：读不出来时 [replyCode] 会返回 0，
     * 于是任何垃圾包都会被当成「登记成功」。真正的判据是：剥出结构，且首个字段
     * 是 tag 0 的 uin —— protobuf 正文常常也以 `0x0A` 开头，只比首尾会误判。
     */
    fun looksLikeReply(resp: ByteArray): Boolean = runCatching {
        val struct = TarsPacket.unwrapStruct(resp) ?: return@runCatching false
        val type = TarsPacket.firstType(struct)
        TarsPacket.firstTag(struct) == 0 && (type == JceType.BYTE || type == JceType.INT ||
            type == JceType.LONG || type == JceType.ZERO)
    }.getOrDefault(false)

    private fun replyCodeIn(body: ByteArray): Int {
        val r = JceReader(body)
        while (true) {
            val f = runCatching { r.next() }.getOrNull() ?: return 0
            if (f.tag == 2) {
                return when (val v = f.value) {
                    is Int -> v
                    is Long -> v.toInt()
                    else -> 0
                }
            }
        }
    }
}
