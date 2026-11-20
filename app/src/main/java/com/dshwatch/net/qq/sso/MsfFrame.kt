package com.dshwatch.net.qq.sso

/**
 * 长连接上的包格式。
 *
 * 和登录阶段用的是同一套套娃，只差三个数字：
 *
 * | 字段 | 登录（0x0A） | 长连接（0x0B） |
 * |---|---|---|
 * | 协议号 | `0x0A` | `0x0B` |
 * | 外层加密 | `0x02` 全 0 密钥 | `0x01` D2 密钥 |
 * | uin | 取码阶段写 `"0"` | 真实 QQ 号 |
 *
 * 正文结构完全一样：SSO 头（序号、返回码、命令名、会话 id…）夹着业务包。
 * 所以这里只写一份，用参数区分。
 */
internal object MsfFrame {

    const val PROTOCOL_LOGIN = 0x0A
    const val PROTOCOL_SIMPLE = 0x0B

    /** 固定填充，逐字节核对过，别改 */
    private const val FILL_HEX = "01 00 00 00 00 00 00 00 00 00 01 00"

    private val ZERO_KEY = ByteArray(16)

    /** 外层加密方式 */
    enum class Encrypt(val code: Int) {
        /** 不加密，心跳用的就是这种 */
        None(0x00),
        /** 登录态下发的 d2Key */
        D2(0x01),
        /** 全 0 密钥。登录阶段还没有 d2Key，只能用这个 */
        Empty(0x02),
    }

    /**
     * 组一个完整包（含最外层 4 字节总长）。
     *
     * @param type [PROTOCOL_LOGIN] 或 [PROTOCOL_SIMPLE]
     * @param outerExtra 写在明文头里的额外数据：登录包放 d2，其它留空
     * @param innerExtra 写在 SSO 头里的额外数据：换票时放 tgt，其它留空
     */
    fun build(
        type: Int,
        encrypt: Encrypt,
        key: ByteArray,
        seq: Int,
        appId: Long,
        uin: Long,
        command: String,
        body: ByteArray,
        outerExtra: ByteArray = ByteArray(0),
        innerExtra: ByteArray = ByteArray(0),
        imei: ByteArray,
        ksid: ByteArray = ByteArray(0),
        qimei: ByteArray = ByteArray(0),
        sessionId: ByteArray = ByteArray(0),
    ): ByteArray {
        // 头长度字段**不含**正文段，所以先单独成段再回填。
        // 两种包的头**形状完全不同**，不能共用一套字段：
        //   0x0A：序号、subAppId×2、12 字节填充、额外数据（tgt）、命令名、会话 id、imei、ksid…
        //   0x0B：只有命令名、会话 id、额外数据、qimei —— 没有序号，也没有 imei/ksid
        val head = ByteWriter(256).apply {
            int(0) // SSO 头长度，写完回填
            if (type == PROTOCOL_LOGIN) {
                int(seq)
                int(appId.toInt())
                int(appId.toInt())
                hex(FILL_HEX)
                lv32Inclusive(innerExtra)
                lv32Inclusive(command.toByteArray(Charsets.UTF_8))
                lv32Inclusive(sessionId)
                lv32Inclusive(imei)
                int(4)
                short(ksid.size + 2)
                bytes(ksid)
                int(4) // 签名位空：这条链路不走 qsign
                lv32Inclusive(qimei)
            } else {
                lv32Inclusive(command.toByteArray(Charsets.UTF_8))
                lv32Inclusive(sessionId)
                lv32Inclusive(innerExtra)
                lv32Inclusive(qimei)
            }
        }
        val header = head.toByteArray().putInt(0, head.size)
        val plain = ByteWriter(header.size + body.size + 4).apply {
            bytes(header)
            lv32Inclusive(body)
        }.toByteArray()

        val sealed = when (encrypt) {
            Encrypt.None -> plain
            Encrypt.D2 -> Tea.encrypt(plain, if (key.size == 16) key else ZERO_KEY)
            Encrypt.Empty -> Tea.encrypt(plain, ZERO_KEY)
        }

        val uinText = uin.toString().toByteArray(Charsets.UTF_8)
        val outer = ByteWriter(sealed.size + 64).apply {
            int(0)
            int(type)
            byte(encrypt.code)
            // 这一格两种包写法不同：
            //   0x0B（长连接）放序号，0x0A（登录）放额外数据（d2 或空）
            if (type == PROTOCOL_SIMPLE) int(seq) else lv32Inclusive(outerExtra)
            byte(0)
            lv32Inclusive(uinText)
            bytes(sealed)
        }.toByteArray()
        return outer.putInt(0, outer.size)
    }

    /** 响应剥壳之后的样子 */
    class Response(
        val type: Int,
        val seq: Int,
        val code: Int,
        val command: String,
        val body: ByteArray,
        /** 服务端回显的会话 id，下次请求要带回去 */
        val sessionId: ByteArray,
    )

    /**
     * 剥掉响应的两层壳。
     *
     * 外层：`[4 总长][4 协议号][1 加密][1 标志][uin 段][密文]`。
     * `uin 段`的长度口径和是否真的存在都不稳定 —— 有的版本写 10 字节的号、
     * 有的版本这一格干脆是别的字段。所以这里按「标准 / 不含自身 / 偏移扫描」
     * 三条路各切一刀，谁能解出合法的 SSO 头就用谁。
     *
     * 判据只有两条，都不依赖字段顺序：
     * - 明文 `[0]` 的头长度必须落在 `16..明文长度`；
     * - 头里能扫出一个像命令名的 lv32 段（含 `.` 或 `_` 的可见 ASCII）。
     *
     * TEA 解错时这两条几乎必然不成立，所以多试几种切法不会误判成「解开了」。
     */
    fun parse(raw: ByteArray, key: ByteArray): Response {
        if (raw.size < 24) throw IllegalStateException("响应只有 ${raw.size} 字节：${raw.head()}")

        val type = be32(raw, 4)
        if (type != PROTOCOL_LOGIN && type != PROTOCOL_SIMPLE) {
            throw IllegalStateException("响应协议号 0x${type.toString(16)}：${raw.head()}")
        }
        val encryptCode = raw[8].toInt() and 0xFF
        val encrypt = Encrypt.entries.firstOrNull { it.code == encryptCode }
            ?: throw IllegalStateException("响应加密方式 0x${encryptCode.toString(16)} 不认识：${raw.head()}")

        var fallback: Response? = null
        // 每种切法失败的原因都记下来：报「认不出」时这几行才是有效信息，
        // 否则只能看到最后一种切法的错，前面试过什么都无从判断。
        val reasons = ArrayList<String>()
        val cuts = cipherCandidates(raw, type, encrypt)
        for (cut in cuts) {
            for ((label, plain) in attempts(cut, encrypt, key)) {
                val resp = try {
                    parsePlain(type, plain)
                } catch (e: Exception) {
                    reasons += "$label ${e.message?.substringBefore('（') ?: "解析失败"}"
                    continue
                }
                // 扫得出命令名才算真认出来了；只有返回码没有命令名的，先记着当兜底
                if (resp.command.isNotEmpty()) return resp
                if (fallback == null) fallback = resp
            }
        }
        fallback?.let { return it }
        val tail = if (reasons.size > 3) "；…共 ${reasons.size} 种" else ""
        throw IllegalStateException(
            "响应认不出（协议 0x${type.toString(16)}、加密 $encryptCode、${raw.size} 字节）：" +
                raw.head(32) + "\n  " + reasons.take(3).joinToString("；") + tail,
        )
    }

    /**
     * 一种切法上的全部解密尝试。
     *
     * 两处「服务端偶尔不按理出牌」的地方，每次都单独开一路试，代价是几毫秒：
     *
     * - **尾部补了零头**：TEA 密文必为 8 的倍数，若切出来不是，说明末尾多写了
     *   几个字节（见过补 4 字节的），按 8 的倍数截一次再解。
     * - **头部写的加密方式和实际密钥不符**：写 `0x01`（D2）却用全 0 密钥的包出现过，
     *   所以同一个切法再用零钥解一遍。
     */
    private fun attempts(cut: Cut, encrypt: Encrypt, key: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>(4)
        val lens = LinkedHashSet<Int>()
        lens += cut.cipher.size
        if (cut.cipher.size % 8 != 0) lens += cut.cipher.size - cut.cipher.size % 8
        for (len in lens) {
            if (len < 16) continue
            val data = if (len == cut.cipher.size) cut.cipher else cut.cipher.copyOf(len)
            if (encrypt == Encrypt.None) {
                out += cut.label to data
                continue
            }
            val k = if (key.size == 16) key else ZERO_KEY
            runCatching { Tea.decrypt(data, k) }.getOrNull()?.let { out += cut.label to it }
            if (!k.contentEquals(ZERO_KEY)) {
                runCatching { Tea.decrypt(data, ZERO_KEY) }.getOrNull()
                    ?.let { out += "${cut.label}·零钥" to it }
            }
        }
        return out
    }

    /**
     * 外层密文的候选切法。
     *
     * 起点固定是 10（总长 4 + 协议号 4 + 加密 1 + 标志 1），uin 段之后才是密文。
     * 两种协议号的外层形状不同：
     *
     * - `0x0A` 响应：`[1 标志][lv32 uin][密文]`
     * - `0x0B` 响应：`[4 序号][1 标志][lv32 uin][密文]` —— 和请求同构，
     *   多出来的那 4 字节序号极易被当成 uin 段的长度字段（读出来是个随机序号，
     *   例如 17855240），于是报「包长度不够：还差 17855240 字节」。
     *
     * TEA 密文永远是 8 的倍数，最后再补一层偏移扫描：
     * 只试 `(总长 - 偏移) % 8 == 0` 的位置，能把前面多写 4 个字节这类偏差也捞回来。
     */
    private fun cipherCandidates(raw: ByteArray, type: Int, encrypt: Encrypt): List<Cut> {
        val out = ArrayList<Cut>(8)
        val start = 10
        // 0x0A：10 起就是 uin 段
        uinTail(raw, start)?.let { out += Cut("0x0A·uin@10", it) }
        // 0x0B：10..14 是序号，14 是标志，15 起才是 uin 段
        if (type == PROTOCOL_SIMPLE) uinTail(raw, start + 5)?.let { out += Cut("0x0B·uin@15", it) }
        // 两种口径都试完还不对，就看谁 8 字节对齐
        if (encrypt == Encrypt.None) return out

        for (off in start until minOf(raw.size - 16, start + 24)) {
            if ((raw.size - off) % 8 != 0) continue
            if (out.any { it.cipher.size == raw.size - off }) continue
            out += Cut("偏移$off", raw.copyOfRange(off, raw.size))
        }
        return out
    }

    /** 一种密文切法。带上标签只为了报错时能说清是哪种切法失败 */
    private class Cut(val label: String, val cipher: ByteArray)

    /**
     * 从 [at] 起按 `[4 长度][内容]` 跳过一个变长段，返回后面的密文。
     *
     * 两种口径都认：长度含自身 4 字节（我们的写入口径）、不含自身（响应侧见过）。
     * 长度明显不合理时返回 null，交给后面的偏移扫描。
     */
    private fun uinTail(raw: ByteArray, at: Int): ByteArray? {
        if (at + 4 > raw.size) return null
        val len = be32(raw, at)
        val end = when {
            len in 4..raw.size - at -> at + len
            len in 0..raw.size - at - 4 -> at + 4 + len
            else -> return null
        }
        if (end >= raw.size) return null
        return raw.copyOfRange(end, raw.size)
    }

    // ---------------- 内层 ----------------

    private fun parsePlain(type: Int, plain: ByteArray): Response {
        if (plain.size < 20) throw IllegalStateException("SSO 明文只有 ${plain.size} 字节：${plain.head()}")
        val headerLen = be32(plain, 0)
        if (headerLen < 16 || headerLen > plain.size) {
            throw IllegalStateException(
                "SSO 头长度 $headerLen 不合理（明文 ${plain.size} 字节）：${plain.head()}",
            )
        }
        val seq = be32(plain, 4)
        val code = be32(plain, 8)
        val (command, afterCommand) = scanCommand(plain, 12, headerLen)
        val sessionId = readSession(plain, afterCommand, headerLen)
        // 正文段从「头长度」处开始，长度值含自身 4 字节
        val body = decodeBody(plain.copyOfRange(headerLen, plain.size))
        return Response(type, seq, code, command, body, sessionId)
    }

    /**
     * 在头里扫出命令名。
     *
     * 从 [from] 开始按 lv32 段往前挪；长度字段不可信就**滑 1 字节**重试 ——
     * 比「按固定顺序读」慢一点，但服务端多/少一个字段也只是少认一个命令名，不会错位。
     *
     * @return 命令名与它所在段的结束位置（后面紧跟会话 id）
     */
    private fun scanCommand(b: ByteArray, from: Int, to: Int): Pair<String, Int> {
        var p = from
        while (p + 4 <= to) {
            val n = be32(b, p) - 4
            if (n <= 0 || n > to - p - 4) {
                p++
                continue
            }
            val raw = b.copyOfRange(p + 4, p + 4 + n)
            val printable = raw.isNotEmpty() && raw.all { it.toInt() in 0x20..0x7E }
            if (printable) {
                val text = String(raw, Charsets.UTF_8)
                if (text.contains('.') || text.contains('_')) return text to (p + 4 + n)
            }
            p++
        }
        return "" to from
    }

    /** 命令名后面那一段就是服务端会话 id，长度对不上就当没有 */
    private fun readSession(b: ByteArray, at: Int, headerLen: Int): ByteArray {
        if (at + 4 > headerLen) return ByteArray(0)
        val n = be32(b, at) - 4
        if (n <= 0 || n > headerLen - at - 4) return ByteArray(0)
        return b.copyOfRange(at + 4, at + 4 + n)
    }

    /**
     * 拆正文段并解压。
     *
     * 正文段是 `[4 长度][数据]`，但这个长度**含不含自身 4 字节，服务端两种都出现过**：
     * 请求侧我们一直按「含自身」写（服务端认），响应侧却按「不含自身」写 ——
     * 照抄就会少读 4 字节，zlib 流缺尾，报的正是
     * `EOFException: Unexpected end of ZLIB input stream`。
     *
     * 所以这里不猜口径：把三种切法（含自身 / 不含自身 / 直到包尾）依次喂给解压器，
     * 谁能解出完整流就用谁。未压缩的正文没有校验手段，按「含自身」取，长度不合理时取到包尾。
     *
     * 是否压缩**不看标志位看魔数**：zlib 一定以 `0x78` 开头，gzip 是 `0x1F 8B`。
     * JCE 和 protobuf 正文首字节是 tag/字段号，从 0x08、0x0A 这种小值起，
     * 撞上这两个魔数的概率为零；而标志位一错位就会把明文当压缩数据解。
     */
    private fun decodeBody(tail: ByteArray): ByteArray {
        if (tail.size < 4) return tail
        val declared = be32(tail, 0)
        val data = tail.copyOfRange(4, tail.size)

        // 含自身：len - 4；不含自身：len；兜底：整段
        val candidates = listOf(declared - 4, declared, data.size)
            .filter { it in 0..data.size }
            .distinct()
            .map { data.copyOfRange(0, it) }

        // 三种切法的起点相同，是不是压缩只看首两个字节
        if (!looksCompressed(data)) {
            val len = declared - 4
            return if (len in 0..data.size) data.copyOfRange(0, len) else data
        }
        for (c in candidates) {
            inflate(c)?.let { return it }
        }
        throw IllegalStateException(
            "正文解压失败（声明 $declared 字节，实到 ${data.size}，开头 ${data.head()}）",
        )
    }

    private fun looksCompressed(b: ByteArray): Boolean {
        if (b.size < 2) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return b0 == 0x78 || (b0 == 0x1F && b1 == 0x8B)
    }

    private fun inflate(d: ByteArray): ByteArray? = runCatching {
        val src = java.io.ByteArrayInputStream(d)
        val stream = if ((d[0].toInt() and 0xFF) == 0x1F) {
            java.util.zip.GZIPInputStream(src)
        } else {
            java.util.zip.InflaterInputStream(src)
        }
        stream.use { it.readBytes() }
    }.getOrNull()

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** 前 [n] 字节的十六进制，用于把真实字节带进错误信息 */
    private fun ByteArray.head(n: Int = 40): String =
        copyOfRange(0, minOf(n, size)).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    // ---------------- 小工具（与 SsoFrame 同一口径） ----------------

    private fun ByteWriter.lv32Inclusive(b: ByteArray): ByteWriter {
        int(b.size + 4)
        return bytes(b)
    }

    /**
     * 读一个「长度含自身 4 字节」的段。
     *
     * 不能写成 `lv32`：[ByteReader] 自带同名成员（长度不含自身），
     * 成员优先，扩展会被静默忽略 —— 那个坑的表现是后续字段整体错位。
     */
    private fun ByteReader.readLv32(): ByteArray = bytes(int() - 4)

    internal fun ByteArray.putInt(at: Int, v: Int): ByteArray {
        this[at] = (v ushr 24).toByte()
        this[at + 1] = (v ushr 16).toByte()
        this[at + 2] = (v ushr 8).toByte()
        this[at + 3] = v.toByte()
        return this
    }
}
