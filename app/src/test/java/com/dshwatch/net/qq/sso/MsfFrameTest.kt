package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 长连接响应包的解析。
 *
 * 这里用一个「服务端格式」的手工包做输入：`[4 总长][4 0x0B][1 加密][1 标志][4 长度][uin]`
 * 之后直接是密文，里面才是 SSO 帧 —— 和请求相比少了两段，偏移照抄请求就会读成垃圾。
 */
class MsfFrameTest {

    private val key = ByteArray(16) { (it * 5 + 1).toByte() }

    private fun serverPacket(
        command: String,
        body: ByteArray,
        seq: Int = 0x1234,
        code: Int = 0,
        useKey: Boolean = true,
        compress: Boolean = false,
        extraField: ByteArray? = null,
        /** 正文长度字段的口径：true = 含自身 4 字节（请求侧一直这么写） */
        bodyLenIncludesSelf: Boolean = true,
        /** 业务型响应的外层带 4 字节序号；登录型响应没有这一段 */
        outerSeq: Int? = null,
        /** 密文尾部多写几个字节（见过补 4 字节的） */
        trailer: ByteArray = ByteArray(0),
        /** 头部写 0x01（D2），实际用全 0 密钥加密 */
        sealWithZeroKey: Boolean = false,
    ): ByteArray {
        val sessionId = byteArrayOf(2, 0xB0.toByte(), 0x5B, 0x8B.toByte())
        val payload = if (compress) zlib(body) else body
        val head = ByteWriter().apply {
            int(0) // 帧长，回填
            int(seq)
            int(code)
            int(4) // 额外数据空
            if (extraField != null) lvIncl(extraField) // 服务端多塞的一段，位置就是这里
            lvIncl(command.toByteArray())
            lvIncl(sessionId)
            int(if (compress) 1 else 0)
        }.toByteArray()
        // 帧长只覆盖到「压缩标志」为止，正文段在外面
        val header = head.copyOf().also {
            it[0] = (head.size ushr 24).toByte()
            it[1] = (head.size ushr 16).toByte()
            it[2] = (head.size ushr 8).toByte()
            it[3] = head.size.toByte()
        }
        val frame = header + lv32Bytes(payload, bodyLenIncludesSelf)
        val sealed = when {
            !useKey -> frame
            sealWithZeroKey -> Tea.encrypt(frame, ByteArray(16))
            else -> Tea.encrypt(frame, key)
        } + trailer
        val outer = ByteWriter().apply {
            int(0)
            int(MsfFrame.PROTOCOL_SIMPLE)
            byte(if (useKey) 0x01 else 0x00)
            if (outerSeq != null) int(outerSeq) else byte(0)
            lvIncl("10001".toByteArray())
            bytes(sealed)
        }.toByteArray()
        val total = outer.size
        outer[0] = (total ushr 24).toByte()
        outer[1] = (total ushr 16).toByte()
        outer[2] = (total ushr 8).toByte()
        outer[3] = total.toByte()
        return outer
    }

    @Test
    fun `解出命令名与正文`() {
        val resp = MsfFrame.parse(serverPacket("MessageSvc.PbGetMsg", byteArrayOf(1, 2, 3)), key)
        assertThat(resp.command).isEqualTo("MessageSvc.PbGetMsg")
        assertThat(resp.seq).isEqualTo(0x1234)
        assertThat(resp.code).isEqualTo(0)
        assertThat(resp.body).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(resp.sessionId).isEqualTo(byteArrayOf(2, 0xB0.toByte(), 0x5B, 0x8B.toByte()))
    }

    @Test
    fun `压缩正文按魔数解出来`() {
        val text = "取消息的正文一般是几千字节的 protobuf，服务端会压".toByteArray()
        val resp = MsfFrame.parse(serverPacket("MessageSvc.PbGetMsg", text, compress = true), key)
        assertThat(resp.command).isEqualTo("MessageSvc.PbGetMsg")
        assertThat(String(resp.body, Charsets.UTF_8)).isEqualTo(String(text, Charsets.UTF_8))
    }

    /**
     * 真实服务端在这个位置上多写过字段，按固定顺序读会一路错位到解压那一步。
     * 这里塞一段额外数据，命令名和正文仍必须能取出来。
     */
    @Test
    fun `头里多一段字段也不会错位`() {
        val resp = MsfFrame.parse(
            serverPacket("StatSvc.register", byteArrayOf(7, 8), extraField = byteArrayOf(1, 2, 3, 4, 5)),
            key,
        )
        assertThat(resp.command).isEqualTo("StatSvc.register")
        assertThat(resp.body).isEqualTo(byteArrayOf(7, 8))
    }

    /**
     * 响应侧的长度口径曾经是「不含自身」—— 按请求的习惯少读 4 字节，
     * zlib 流缺尾，抛的就是 `Unexpected end of ZLIB input stream`。
     */
    @Test
    fun `正文长度不含自身也能解`() {
        val text = "StatSvc.register 的 JCE 回包".toByteArray()
        val resp = MsfFrame.parse(
            serverPacket("StatSvc.register", text, compress = true, bodyLenIncludesSelf = false),
            key,
        )
        assertThat(resp.command).isEqualTo("StatSvc.register")
        assertThat(String(resp.body, Charsets.UTF_8)).isEqualTo(String(text, Charsets.UTF_8))
    }

    /**
     * 服务端在外层头里多写 4 个字节（uin 段之后、密文之前）时，
     * 固定偏移会切在密文中间，解密直接失败 —— 靠「按 8 字节对齐扫偏移」捞回来。
     */
    @Test
    fun `外层多出四个字节也能找对密文起点`() {
        val raw = serverPacket("StatSvc.register", byteArrayOf(1, 2, 3))
        val cut = 19 // [4 总长][4 协议][1 加密][1 标志][4 长度]["10001"]
        val bad = raw.copyOfRange(0, cut) + ByteArray(4) + raw.copyOfRange(cut, raw.size)
        val total = bad.size
        bad[0] = (total ushr 24).toByte()
        bad[1] = (total ushr 16).toByte()
        bad[2] = (total ushr 8).toByte()
        bad[3] = total.toByte()

        val resp = MsfFrame.parse(bad, key)
        assertThat(resp.command).isEqualTo("StatSvc.register")
        assertThat(resp.body).isEqualTo(byteArrayOf(1, 2, 3))
    }

    /**
     * 业务型（0x0B）响应的外层多一段 4 字节序号，正好占住 uin 长度的位置：
     * 按登录型去读，会把这个随机序号当成长度，报「包长度不够：还差 17855240 字节」。
     */
    @Test
    fun `业务型响应外层带序号也能解析`() {
        val resp = MsfFrame.parse(
            serverPacket("StatSvc.register", byteArrayOf(5, 6), outerSeq = 17855240),
            key,
        )
        assertThat(resp.command).isEqualTo("StatSvc.register")
        assertThat(resp.body).isEqualTo(byteArrayOf(5, 6))
    }

    @Test
    fun `加密方式为 0 时不尝试解密`() {
        val resp = MsfFrame.parse(serverPacket("Heartbeat.Alive", ByteArray(0), useKey = false), key)
        assertThat(resp.command).isEqualTo("Heartbeat.Alive")
        assertThat(resp.body).isEmpty()
    }

    /**
     * 密文尾部被多写了 4 个字节：TEA 密文必为 8 的倍数，一旦切出来不是，
     * 解出来的头条数就是随机的。按 8 的倍数截一次再解即可。
     */
    @Test
    fun `密文尾部多出四个字节也能解`() {
        val resp = MsfFrame.parse(
            serverPacket("StatSvc.register", byteArrayOf(4, 5), trailer = ByteArray(4) { 0x7F }),
            key,
        )
        assertThat(resp.command).isEqualTo("StatSvc.register")
        assertThat(resp.body).isEqualTo(byteArrayOf(4, 5))
    }

    /**
     * 头部写 `0x01`（D2）但实际用全 0 密钥加密 —— 服务端在刚认证完的阶段这么回过。
     * 按声明的密钥解是垃圾，退回零钥才解得开。
     */
    @Test
    fun `头部写 D2 实际用零钥加密也能解`() {
        val resp = MsfFrame.parse(
            serverPacket("StatSvc.register", byteArrayOf(6, 7), sealWithZeroKey = true),
            key,
        )
        assertThat(resp.command).isEqualTo("StatSvc.register")
        assertThat(resp.body).isEqualTo(byteArrayOf(6, 7))
    }    @Test
    fun `用错密钥会解出垃圾而不是崩在偏移上`() {
        val e = runCatching {
            MsfFrame.parse(serverPacket("MessageSvc.PbGetMsg", byteArrayOf(9)), ByteArray(16))
        }.exceptionOrNull()
        // 允许两种结果：要么帧长不合理直接报错，要么命令名解成乱码 —— 但不能静默成功
        assertThat(e).isNotNull()
    }

    /**
     * 0x0B 的**内层头**和 0x0A 完全是两回事：
     *
     * ```
     * 0x0A：序号 | subAppId×2 | 12 字节填充 | tgt | 命令名 | 会话 id | imei | …
     * 0x0B：命令名 | 会话 id | 额外数据 | qimei
     * ```
     *
     * 照抄 0x0A 的头去发 0x0B，服务端会把 subAppId 当命令名长度，整包作废。
     * 这一条把 0x0B 的明文头逐段读一遍钉住。
     */
    @Test
    fun `业务型包的内层头只有命令名和会话 id`() {
        val raw = MsfFrame.build(
            type = MsfFrame.PROTOCOL_SIMPLE,
            encrypt = MsfFrame.Encrypt.None,
            key = ByteArray(16),
            seq = 0x2233,
            appId = 0x2002EDBD,
            uin = 10001L,
            command = "MessageSvc.PbGetMsg",
            body = byteArrayOf(1, 2, 3),
            imei = "imei".toByteArray(),
            sessionId = byteArrayOf(2, 0xB0.toByte(), 0x5B, 0x8B.toByte()),
        )
        val r = ByteReader(raw, 4)
        assertThat(r.int()).isEqualTo(MsfFrame.PROTOCOL_SIMPLE)
        assertThat(r.byte()).isEqualTo(MsfFrame.Encrypt.None.code)
        assertThat(r.int()).isEqualTo(0x2233) // 外层序号
        assertThat(r.byte()).isEqualTo(0)
        r.lv() // uin 段
        r.int() // 内层头长度（含自身 4 字节），下面按顺序读

        // 内层头：命令名打头，没有 subAppId，也没有 imei
        val command = String(r.lv(), Charsets.UTF_8)
        assertThat(command).isEqualTo("MessageSvc.PbGetMsg")
        val sessionId = r.lv()
        assertThat(sessionId.size).isEqualTo(4)
        val extra = r.lv()
        assertThat(extra.size).isEqualTo(0) // 签名位留空
        val qimei = r.lv()
        assertThat(qimei.size).isEqualTo(0)
        // 头之后才是正文
        val body = r.lv()
        assertThat(body).isEqualTo(byteArrayOf(1, 2, 3))
    }

    /**
     * 0x0A 与 0x0B 的外层头**形状不同**，这是最容易照抄错的一处：
     *
     * - 0x0A（登录型，如 StatSvc.register）：`[总长][0x0A][加密][d2 长度][d2][0][uin]`
     * - 0x0B（业务型，如 PbGetMsg）  ：`[总长][0x0B][加密][序号][0][uin]`
     *
     * 把 register 写成 0x0B，服务端一律回 -10008 —— 它会认为这条连接没登录。
     */
    @Test
    fun `登录型包的外层第二个字段是 d2 不是序号`() {
        val d2 = ByteArray(0x70) { 0x11 }
        val raw = MsfFrame.build(
            type = MsfFrame.PROTOCOL_LOGIN,
            encrypt = MsfFrame.Encrypt.D2,
            key = key,
            seq = 0x1234,
            appId = 0x2002EDBD,
            uin = 10001L,
            command = "StatSvc.register",
            body = byteArrayOf(1, 2, 3),
            outerExtra = d2,
            innerExtra = byteArrayOf(9),
            imei = "imei".toByteArray(),
        )
        val r = ByteReader(raw, 4)
        assertThat(r.int()).isEqualTo(MsfFrame.PROTOCOL_LOGIN)
        assertThat(r.byte()).isEqualTo(MsfFrame.Encrypt.D2.code)
        // 长度 = d2 实际长度 + 4，后面紧跟 d2 本体 —— 不是 0x1234
        assertThat(r.int()).isEqualTo(d2.size + 4)
        assertThat(r.bytes(d2.size)).isEqualTo(d2)
        assertThat(r.byte()).isEqualTo(0)
        assertThat(r.int()).isEqualTo(9) // uin 段的长度，含自身 4 字节
        assertThat(String(r.bytes(5), Charsets.UTF_8)).isEqualTo("10001")
    }

    @Test
    fun `业务型包的外层第二个字段是序号`() {
        val raw = MsfFrame.build(
            type = MsfFrame.PROTOCOL_SIMPLE,
            encrypt = MsfFrame.Encrypt.D2,
            key = key,
            seq = 0x2233,
            appId = 0x2002EDBD,
            uin = 10001L,
            command = "MessageSvc.PbGetMsg",
            body = byteArrayOf(1, 2, 3),
            imei = "imei".toByteArray(),
        )
        val r = ByteReader(raw, 4)
        assertThat(r.int()).isEqualTo(MsfFrame.PROTOCOL_SIMPLE)
        assertThat(r.byte()).isEqualTo(MsfFrame.Encrypt.D2.code)
        assertThat(r.int()).isEqualTo(0x2233)
        assertThat(r.byte()).isEqualTo(0)
        assertThat(r.int()).isEqualTo(9) // uin 段的长度，含自身 4 字节
        assertThat(String(r.bytes(5), Charsets.UTF_8)).isEqualTo("10001")
    }
}

/**
 * 长度值含自身 4 字节 —— 协议里 lv32 全是这个口径。
 *
 * 不能叫 `lv32`：[ByteWriter] 自带同名成员（长度不含自身），成员优先，
 * 扩展会被**静默忽略**，包里的长度全部少 4，服务端读到的是错位数据。
 */
/** 长度含自身 4 字节的段 */
private fun ByteReader.lv(): ByteArray = bytes(int() - 4)

private fun ByteWriter.lvIncl(b: ByteArray): ByteWriter {
    int(b.size + 4)
    return bytes(b)
}

private fun lv32Bytes(b: ByteArray, includesSelf: Boolean = true): ByteArray {
    val v = if (includesSelf) b.size + 4 else b.size
    return byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte(),
    ) + b
}

private fun zlib(b: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.DeflaterOutputStream(out).use { it.write(b) }
    return out.toByteArray()
}
