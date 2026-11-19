package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 包结构测试。
 *
 * 重点验每个长度字段的口径：QQ 的长度几乎都是「含自己那几字节」，
 * 算错一位服务端不会报错 —— 它只是读不满一个包，然后一直等，客户端表现为超时。
 */
class SsoFrameTest {

    private val device = WatchDevice(
        guid = ByteArray(16) { it.toByte() },
        imei = "8612345678901",
    )

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun be16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun build(payload: ByteArray = ByteArray(4)) = SsoFrame.packet(
        seq = 0x1234,
        appId = device.subAppId,
        command = "wtlogin.trans_emp",
        payload = payload,
        sessionId = byteArrayOf(1, 2, 3, 4),
        device = device,
    )

    @Test
    fun `最外层长度等于实际长度`() {
        val pkt = build(ByteArray(100) { 7 })
        assertThat(be32(pkt, 0)).isEqualTo(pkt.size)
    }

    @Test
    fun `明文头是协议号加加密方式和 uin`() {
        val pkt = build()
        // [4 总长][4 0x0A][1 加密方式 2][4 预留][1 0][uin 字符串]
        assertThat(be32(pkt, 4)).isEqualTo(0x0A)
        assertThat(pkt[8].toInt()).isEqualTo(0x02)
        assertThat(be32(pkt, 9)).isEqualTo(4)
        assertThat(pkt[13].toInt()).isEqualTo(0)
        assertThat(be32(pkt, 14)).isEqualTo(5) // "0" 的长度 + 4
        assertThat(pkt[18].toInt()).isEqualTo('0'.code)
    }

    @Test
    fun `整段正文是全 0 密钥加密的`() {
        // 明文头之后第一块密文必然不是明文本身，否则说明忘了加密
        val pkt = build(ByteArray(32) { 0 })
        val sealed = pkt.copyOfRange(19, pkt.size)
        assertThat(sealed.size % 8).isEqualTo(0)
        val plain = Tea.decrypt(sealed, ByteArray(16))
        // 解出来第一个字段是头长度，必须自洽
        val headerLen = be32(plain, 0)
        assertThat(headerLen).isGreaterThan(8)
        assertThat(headerLen).isLessThan(plain.size)
    }

    @Test
    fun `正文头里能找到命令名和 imei`() {
        val pkt = build()
        val plain = Tea.decrypt(pkt.copyOfRange(19, pkt.size), ByteArray(16))
        val text = String(plain, Charsets.ISO_8859_1)
        assertThat(text).contains("wtlogin.trans_emp")
        assertThat(text).contains(device.imei)
    }

    @Test
    fun `oicq 头长度字段自洽`() {
        val oicq = SsoFrame.oicq(Ecdh.session(), 0x812, ByteArray(37) { 1 })
        assertThat(be16(oicq, 1)).isEqualTo(oicq.size)
        assertThat(oicq.first().toInt()).isEqualTo(0x02)
        assertThat(oicq.last().toInt()).isEqualTo(0x03)
    }

    @Test
    fun `oicq 头里带公钥和 ECDH 标记`() {
        val session = Ecdh.session()
        val oicq = SsoFrame.oicq(session, 0x812, ByteArray(8))
        // [02][2 长度][2 8001][2 命令][2 序号][4 uin][03][加密方式][0]
        // 0x87 的最高位是 1，直接 toInt() 会被符号扩展成负数，必须掩码
        assertThat(oicq[14].toInt() and 0xFF).isEqualTo(0x87)
        // 定长头 117 字节，公钥是最后 65 字节
        val pub = oicq.copyOfRange(52, 117)
        assertThat(pub[0].toInt()).isEqualTo(0x04)
        assertThat(pub).isEqualTo(session.publicKey)
    }

    @Test
    fun `tlv 编解码往返`() {
        val encoded = TlvMap()
            .t33(device.guid)
            .t35(WatchDevice.PRODUCT_TYPE)
            .encode()

        val back = TlvMap.decode(ByteReader(encoded), strict = true)
        assertThat(back).containsKey(0x33)
        assertThat(back[0x33]).isEqualTo(device.guid)
        assertThat(back).containsKey(0x35)
        assertThat(back[0x35]!!.size).isEqualTo(4)
    }

    @Test
    fun `tlv 表保持插入顺序`() {
        val encoded = TlvMap().t35(8).t33(device.guid).t1d(1).encode()
        val back = TlvMap.decode(ByteReader(encoded), strict = true).keys.toList()
        assertThat(back).containsExactly(0x35, 0x33, 0x1d).inOrder()
    }

    @Test
    fun `tlv 遇到坏长度不崩`() {
        val bad = byteArrayOf(0x00, 0x33, 0x03, 0xE7.toByte(), 0x01, 0x02)
        val back = TlvMap.decode(ByteReader(bad), strict = false)
        assertThat(back).isEmpty()
    }

    @Test
    fun `设备指纹每次都不一样但形状固定`() {
        val a = WatchDevice.create(java.util.Random(1))
        val b = WatchDevice.create(java.util.Random(2))
        assertThat(a.guid).isNotEqualTo(b.guid)
        assertThat(a.imei).hasLength(15)
        assertThat(a.imei.startsWith("86")).isTrue()
        assertThat(a.imei.all { it.isDigit() }).isTrue()
    }

    @Test
    fun `签名摘要长度是 16 字节`() {
        // 服务端按 16 字节比对，长度不对直接返回 45
        assertThat(WatchDevice.APK_SIGN).hasLength(16)
    }

    /**
     * `StatSvc.register` 的回包是 oicq 包装：首字节 0x02 + 2 字节包长。
     * 不拆这一层直接喂 JCE，会把包长当成字段长度，报「还差 1785 万字节」。
     */
    @Test
    fun `拆掉 oicq 包装`() {
        val jce = JceWriter().apply { int(2, 0) }.toByteArray()
        val payload = ByteWriter().apply {
            byte(0x02)
            short(0x0110) // 包长 272 —— 正是错误信息里那个 17850632 的高两位
            short(0x8001)
            short(0x11)
            short(1)
            int(2332184782L.toInt())
            byte(0)
            byte(0) // 加密方式 0 = 明文
            byte(0)
            bytes(jce)
            byte(0x03)
        }.toByteArray()

        assertThat(SsoFrame.peelOicq(payload)).isEqualTo(jce)
    }

    @Test
    fun `不是 oicq 包装时原样返回 null`() {
        val plainJce = JceWriter().apply { int(2, 0) }.toByteArray()
        assertThat(SsoFrame.peelOicq(plainJce)).isNull()
        // 内层用了协商密钥：这条链路没有密钥，宁可返回 null 交给上层报错
        val encrypted = ByteWriter().apply {
            byte(0x02); short(32); short(0x8001); short(0x11); short(1); int(1)
            byte(0); byte(0x87); byte(0)
            bytes(ByteArray(16))
            byte(0x03)
        }.toByteArray()
        assertThat(SsoFrame.peelOicq(encrypted)).isNull()
    }
}
