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
        return JceWriter(512).apply {
            short(1, 3) // 协议版本，固定 3
            byte(2, 0) // 普通请求包
            int(3, 0) // iMessageType
            int(4, 0) // requestId
            string(5, "PushService")
            string(6, "SvcReqRegister")
            bytes(7, sBuffer(inner))
            int(8, 0) // iTimeout
        }.toByteArray()
    }

    /**
     * `RequestPacket.sBuffer`：**不是内层结构的裸字节**，而是版本 3 的封装
     * `RequestDataVersion3 { map<string, byte[]> }`，表里那一项的值还要再套一层
     * `0x0A … 0x0B` 的结构头。
     *
     * 直接塞裸结构时服务端的表现是**完全不回包**：不是返回错误码，是连回包都没有。
     * 这一点在自检里表现为「等 StatSvc.register 超时」，而同时服务端还在正常下发
     * `ConfigPushSvc.PushReq` —— 很容易误判成网络问题。
     */
    private fun sBuffer(inner: ByteArray): ByteArray {
        val struct = byteArrayOf(STRUCT_HEAD) + inner + byteArrayOf(STRUCT_TAIL)
        return JceWriter(inner.size + 32).apply {
            stringBytesMap(0, listOf("SvcReqRegister" to struct))
        }.toByteArray()
    }

    /**
     * 响应里的 replyCode（tag 2）。0 表示登记成功。
     *
     * 响应正文是 `RequestPacket`，真正的内容埋在 `sBuffer` 的版本 3 表里，
     * 所以先剥两层再读；剥不出来时退回「直接在原始字节里找 tag 2」——
     * 老版本服务端偶尔直接回裸结构。
     */
    fun replyCode(resp: ByteArray): Int = replyCodeIn(unwrap(resp))

    /** 响应里的 iHelloInterval（tag 12），服务端建议的心跳间隔（秒） */
    fun helloInterval(resp: ByteArray): Int {
        val r = JceReader(unwrap(resp))
        while (true) {
            val f = r.next() ?: return 0
            if (f.tag == 12 && f.value is Int) return f.value
        }
    }

    /** `RequestPacket.sBuffer` → `RequestDataVersion3` 表里那一项 → 去掉结构头 */
    private fun unwrap(resp: ByteArray): ByteArray = unwrapOrNull(resp) ?: resp

    /**
     * 这个包到底是不是 `SvcReqRegister` 的回包。
     *
     * 只看「能不能读出 replyCode」不够：读不出来时 [replyCode] 会返回 0，
     * 于是任何垃圾包都会被当成「登记成功」。这里要求真的找到那层版本 3 的表。
     */
    fun looksLikeReply(resp: ByteArray): Boolean =
        runCatching { unwrapOrNull(resp) }.getOrNull() != null

    private fun unwrapOrNull(resp: ByteArray): ByteArray? {
        // 标准形态：RequestPacket.sBuffer → 版本 3 / 2 的表 → `0x0A 结构 0x0B`
        val outer = JceReader(resp)
        while (true) {
            val f = outer.next() ?: break
            if (f.tag != 7 || f.value !is ByteArray) continue
            val sb = f.value as ByteArray
            // 有的版本 sBuffer 里直接是裸结构，不套表
            sb.asReply()?.let { return it }
            val table = JceReader(sb)
            while (true) {
                val g = table.next() ?: break
                val map = g.value as? Map<*, *> ?: continue
                findStruct(map)?.let { return it }
            }
        }
        // 兜底：整个正文就是一个带结构头的结构。
        // 但要过 [asReply] 那道校验 —— protobuf 的正文常常也以 0x0A 开头（字段 1 的定长头），
        // 只看首尾字节会把推送正文当成 register 的回包。
        return resp.asReply()
    }

    /** 从表里找一个「结构」：版本 3 的值是 byte[]，版本 2 的值是再一层表 */
    private fun findStruct(map: Map<*, *>): ByteArray? {
        for (v in map.values) {
            val raw = when (v) {
                is ByteArray -> v
                is Map<*, *> -> v.values.firstOrNull() as? ByteArray
                else -> null
            } ?: continue
            raw.asReply()?.let { return it }
        }
        return null
    }

    /**
     * 认成 `SvcRespRegister` 的条件：`0x0A … 0x0B` 括起来，且里面的首个字段
     * 是 tag 0 的整数（uin）。只校验首尾两字节不够 —— 太多正文恰好也长这样。
     */
    private fun ByteArray.asReply(): ByteArray? {
        if (!startsStruct()) return null
        val body = trimStructMarks()
        if (body.isEmpty()) return null
        val b = body[0].toInt() and 0xFF
        val type = b and 0x0F
        val tag = (b shr 4) and 0x0F
        val numeric = type == JceType.BYTE || type == JceType.INT ||
            type == JceType.LONG || type == JceType.ZERO
        return if (tag == 0 && numeric) body else null
    }

    private fun ByteArray.startsStruct(): Boolean =
        size >= 2 && this[0] == STRUCT_HEAD && this[size - 1] == STRUCT_TAIL

    /** 结构头只在 `0x0A` 开头、`0x0B` 结尾时才剥，避免把正文首字节当标记吃掉 */
    private fun ByteArray.trimStructMarks(): ByteArray =
        if (size >= 2 && this[0] == STRUCT_HEAD && this[size - 1] == STRUCT_TAIL) {
            copyOfRange(1, size - 1)
        } else {
            this
        }

    private fun replyCodeIn(body: ByteArray): Int {
        val r = JceReader(body)
        while (true) {
            val f = r.next() ?: return 0
            if (f.tag == 2 && f.value is Int) return f.value
        }
    }

    /** 结构体的起止标记，Tars 里就是 JCE_TYPE_STRUCT_BEGIN / END 两个字节 */
    private const val STRUCT_HEAD: Byte = 0x0A
    private const val STRUCT_TAIL: Byte = 0x0B
}
