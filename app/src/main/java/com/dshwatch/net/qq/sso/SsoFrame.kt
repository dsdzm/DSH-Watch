package com.dshwatch.net.qq.sso

/**
 * QQ 登录包的外壳。
 *
 * 一个请求是四层套娃：
 * ```
 * [4 总长]
 *   [4 协议号 0x0A][1 加密方式][4 预留][1 0][uin 字符串]      ← 明文头
 *   TEA(body, 全 0 密钥)                                     ← 整段 body 加密
 *     body 明文：
 *       [4 头长度][4 序号][4 appId][4 subAppId][12 填充][4 TGT]
 *       [命令名][会话 id][IMEI][4][KSID][4 签名位][QIMEI]
 *       [4 正文长度][正文]
 *         [1 02][2 包长][2 8001][2 命令号][2 序号][4 uin]
 *         [1 03][1 加密方式][1 0][4 2][4 版本][4 0]
 *         [1 02][1 01][16 随机][2 0x0131][2 密钥版本][2 公钥长][65 公钥]
 *         TEA(业务正文, 协商密钥)
 *         [1 03]
 * ```
 *
 * 少任何一层服务端都不报错 —— 它只是读不满一个包，然后一直等，客户端读到的是超时。
 * 这类问题几乎无法从客户端侧反推，所以每层的长度口径都在这里对齐过真实抓包。
 */
internal object SsoFrame {

    /** 固定填充，逐字节核对过，别改 */
    private const val FILL_HEX = "01 00 00 00 00 00 00 00 00 00 01 00"

    private const val PROTOCOL_LOGIN = 0x0A

    /** 外层加密方式：2 = 全 0 密钥的 TEA。密钥还没协商，只能用这个 */
    private const val ENCRYPT_EMPTY_KEY = 0x02

    /** 内层加密方式：0x87 = ECDH（允许 ST 回退） */
    private const val METHOD_ECDH = 0x87

    private val EMPTY_KEY = ByteArray(16)

    /** 取码阶段没有登录态，uin 发字面量 "0" */
    private val UIN_ANONYMOUS = "0".toByteArray()

    // ---------------- 发送 ----------------

    /**
     * 组装一个可直接写进 socket 的完整包。
     *
     * @param payload 业务正文，已经过 [oicq] 包装
     * @param uin 取码阶段写匿名 `"0"`；换票（`wtlogin.exchange_emp`）要写真实 QQ 号
     * @param tgt 换票凭证，只有 exchange_emp 用得上，其余留空
     */
    fun packet(
        seq: Int,
        appId: Long,
        command: String,
        payload: ByteArray,
        sessionId: ByteArray,
        device: WatchDevice,
        ksid: ByteArray = ByteArray(0),
        qimei: ByteArray = ByteArray(0),
        uin: Long = 0,
        tgt: ByteArray = ByteArray(0),
    ): ByteArray {
        val head = ByteWriter(128).apply {
            int(0) // 头长度，写完回填
            int(seq)
            int(appId.toInt())
            int(appId.toInt())
            hex(FILL_HEX)
            lv32Inclusive(tgt) // TGT：换票时是凭证，其余为空
            lv32Inclusive(command.toByteArray(Charsets.UTF_8))
            lv32Inclusive(sessionId)
            lv32Inclusive(device.imeiBytes)
            int(4)
            short(ksid.size + 2)
            bytes(ksid)
            int(4) // 签名位空：手表这条链路不走 qsign
            lv32Inclusive(qimei)
        }
        val headerLen = head.size
        val header = head.toByteArray().putInt(0, headerLen)

        // 正文段接在头后面；头长度字段**不含**这一段
        val body = ByteWriter(headerLen + payload.size + 8).apply {
            bytes(header)
            lv32Inclusive(payload)
        }.toByteArray()

        val sealed = Tea.encrypt(body, EMPTY_KEY)
        val outer = ByteWriter(sealed.size + 32).apply {
            int(0) // 总长，写完回填
            int(PROTOCOL_LOGIN)
            byte(ENCRYPT_EMPTY_KEY)
            int(4)
            byte(0)
            lv32Inclusive(if (uin == 0L) UIN_ANONYMOUS else uin.toString().toByteArray(Charsets.UTF_8))
            bytes(sealed)
        }.toByteArray()
        return outer.putInt(0, outer.size)
    }

    /**
     * 包一层 oicq 头，并用协商密钥加密业务正文。
     *
     * 每次请求都要新的 [Ecdh.Session]：公钥随包发过去，服务端据此算出同一个共享密钥。
     * 响应也要用同一份密钥解，所以调用方必须留着它。
     */
    fun oicq(
        session: Ecdh.Session,
        commandId: Int,
        body: ByteArray,
        uin: Long = 0,
    ): ByteArray {
        val cipher = Tea.encrypt(body, session.shareKey)
        val w = ByteWriter(cipher.size + 128).apply {
            byte(0x02)
            short(0) // 整包长度，写完回填
            short(8001)
            short(commandId)
            short(1)
            int(uin.toInt())
            byte(0x03)
            byte(METHOD_ECDH)
            byte(0)
            int(2)
            int(0) // 客户端版本：手表固定 0
            int(0)
            byte(0x02)
            byte(0x01)
            bytes(random16())
            short(0x0131)
            short(1) // 服务端公钥版本
            short(session.publicKey.size)
            bytes(session.publicKey)
            bytes(cipher)
            byte(0x03)
        }
        val out = w.toByteArray()
        return out.putShort(1, out.size)
    }

    // ---------------- 接收 ----------------

    /** 响应剥完壳之后剩下的东西 */
    class Response(val seq: Int, val command: String, val plain: ByteArray)

    /**
     * 剥掉响应外壳并解出 oicq 正文。
     *
     * 响应头比请求短：`[1 flag][2 包长][2 版本][2 命令号][2 序号][4 uin][1][1 加密][1]`
     * 之后直接就是密文，没有请求里那段公钥头。
     */
    fun unwrap(raw: ByteArray, shareKey: ByteArray): Response {
        if (raw.size < 20) throw IllegalStateException("响应只有 ${raw.size} 字节：${raw.head()}")

        val r = ByteReader(raw, 4)
        val protocol = r.int()
        if (protocol != PROTOCOL_LOGIN) {
            throw IllegalStateException("响应协议号 0x${protocol.toString(16)}，原始：${raw.head()}")
        }
        val encrypt = r.byte()
        if (encrypt != ENCRYPT_EMPTY_KEY) throw IllegalStateException("响应加密方式 $encrypt，暂不支持")
        // 响应里 uin 前只有一个 0x00，比请求少 3 字节（请求是 [4 预留][1 0]）。
        // 照抄请求的偏移会把后面的长度读成垃圾，然后在解密前就炸掉。
        r.byte()
        r.readLv32() // uin 字符串
        val body = Tea.decrypt(r.rest(), EMPTY_KEY)

        val br = ByteReader(body)
        val headerLen = br.int()
        if (headerLen < 8 || headerLen > body.size) {
            throw IllegalStateException("响应头长度 $headerLen 不合理，原始：${raw.head()}")
        }
        val hr = ByteReader(body, 4)
        val seq = hr.int()
        val code = hr.int()
        val message = String(hr.readLv32(), Charsets.UTF_8)
        val command = String(hr.readLv32(), Charsets.UTF_8)
        hr.readLv32() // 会话 id
        val compress = hr.int()
        if (code != 0) {
            throw IllegalStateException("服务端返回 $code${if (message.isEmpty()) "" else "：$message"}（$command）")
        }

        val pr = ByteReader(body, headerLen)
        val payloadLen = pr.int() - 4
        val payload = pr.bytes(payloadLen)
        val plain = if (compress == 1) inflate(payload) else payload

        return Response(seq, command, openOicq(plain, shareKey))
    }

    /**
     * 解开 oicq 那层。
     *
     * 定长头 16 字节，之后是密文，最后一个字节是 0x03 结束符 ——
     * 长度字段与真实内容偶尔差一两个字节，所以不按它切，直接解到包尾再去掉结束符。
     */
    private fun openOicq(payload: ByteArray, shareKey: ByteArray): ByteArray {
        val r = ByteReader(payload)
        val flag = r.byte()
        if (flag != 0x02) throw IllegalStateException("oicq 头标志 0x${flag.toString(16)}")
        r.short() // 包长
        r.short() // 版本
        r.short() // 命令号
        r.short() // 序号
        r.int() // uin
        r.byte()
        val method = r.byte()
        if (method != 0 && method != 0x03) {
            throw IllegalStateException("oicq 响应加密方式 0x${method.toString(16)}")
        }
        r.byte()

        var cipher = r.rest()
        if (cipher.isNotEmpty() && cipher.last() == 0x03.toByte()) cipher = cipher.copyOf(cipher.size - 1)
        return if (method == 0) Tea.decrypt(cipher, shareKey) else cipher
    }

    /**
     * 只拆 oicq 那层包装，不碰外壳。
     *
     * 有些走 0x0A 通道的命令（`StatSvc.register` 就是）回包仍是 oicq 包装：
     * `[02][2 包长][2 版本][2 命令号][2 序号][4 uin][1][1 加密][1][正文][03]`。
     * 直接把这个包丢给 JCE 解析器，第一个字节 0x02 会被当成「tag 0、类型 INT」，
     * 接着读到的 4 字节是包长（例如 `01 10 61 C8`），于是报
     * 「包长度不够：还差 17850632 字节」—— 那个数字其实是包长。
     *
     * @return 拆出来的正文；不是 oicq 包装、或内层用了协商密钥（这条链路拿不到）时返回 null
     */
    fun peelOicq(payload: ByteArray): ByteArray? {
        if (payload.size < 18 || payload[0] != 0x02.toByte()) return null
        val r = ByteReader(payload)
        r.byte() // 标志
        r.short() // 包长
        r.short() // 版本
        r.short() // 命令号
        r.short() // 序号
        r.int() // uin
        r.byte()
        val method = r.byte()
        if (method != 0) return null
        r.byte()
        var rest = r.rest()
        if (rest.isNotEmpty() && rest.last() == 0x03.toByte()) rest = rest.copyOf(rest.size - 1)
        return rest
    }

    // ---------------- 小工具 ----------------

    /**
     * 4 字节长度 + 内容，长度值**含自身 4 字节** —— 协议里 lv32 全是这个口径。
     *
     * 不能叫 `lv32`：[ByteWriter] 已有同名成员，Kotlin 解析时成员优先，
     * 扩展会被静默忽略。那个坑的表现是「服务端不回包」，不是编译错误。
     */
    private fun ByteWriter.lv32Inclusive(b: ByteArray): ByteWriter {
        int(b.size + 4)
        return bytes(b)
    }

    /** 同 [lv32]，读侧：先读 4 字节长度，再取「长度 - 4」个字节 */
    private fun ByteReader.readLv32(): ByteArray = bytes(int() - 4)

    private fun inflate(d: ByteArray): ByteArray =
        java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(d)).use { it.readBytes() }

    private fun random16(): ByteArray = ByteArray(16).also { java.util.Random().nextBytes(it) }

    internal fun ByteArray.putInt(at: Int, v: Int): ByteArray {
        this[at] = (v ushr 24).toByte()
        this[at + 1] = (v ushr 16).toByte()
        this[at + 2] = (v ushr 8).toByte()
        this[at + 3] = v.toByte()
        return this
    }

    internal fun ByteArray.putShort(at: Int, v: Int): ByteArray {
        this[at] = (v ushr 8).toByte()
        this[at + 1] = v.toByte()
        return this
    }

    internal fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun ByteArray.head(): String = copyOfRange(0, minOf(48, size)).hex()
}
