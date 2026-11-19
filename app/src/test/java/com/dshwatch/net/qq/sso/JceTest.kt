package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tars 编码的回归。
 *
 * 这几条都是「写错也能编译、只有在服务端掉线时才暴露」的地方，
 * 所以钉在单元测试里，不靠联调发现。
 */
class JceTest {

    private fun hex(b: ByteArray) =
        b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun `字段头 tag 小于 15 时占一个字节`() {
        val out = JceWriter().apply { byte(5, 0x41) }.toByteArray()
        // (5 shl 4) or 0 = 0x50，后面跟值
        assertThat(hex(out)).isEqualTo("50 41")
    }

    @Test
    fun `字段头 tag 大于等于 15 时类型留在低位`() {
        val out = JceWriter().apply { byte(20, 0x41) }.toByteArray()
        // 0xF0 or 0(BYTE) = 0xF0，tag 挪到下一字节 0x14
        assertThat(hex(out)).isEqualTo("F0 14 41")
    }

    @Test
    fun `整数字段为 0 时只写一个零头`() {
        val out = JceWriter().apply { int(3, 0) }.toByteArray()
        // (3 shl 4) or 12(ZERO) = 0x3C
        assertThat(hex(out)).isEqualTo("3C")

        val nonZero = JceWriter().apply { int(3, 7) }.toByteArray()
        assertThat(hex(nonZero)).isEqualTo("32 00 00 00 07")
    }

    @Test
    fun `短字符串用一字节长度`() {
        val out = JceWriter().apply { string(5, "PushService") }.toByteArray()
        assertThat(hex(out)).isEqualTo("56 0B " + "PushService".toByteArray().joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        })
    }

    @Test
    fun `上线包外层从版本号开始`() {
        val body = StatSvcRegister.online(
            uin = 10001L,
            guid = ByteArray(16) { it.toByte() },
            deviceName = "HUAWEI ELE-AL00",
            osVersion = "7.1.2",
            sdkLevel = 25L,
        )
        // RequestPacket.version 是 tag1 的 short：11 00 03
        assertThat(hex(body.copyOfRange(0, 3))).isEqualTo("11 00 03")
        // servantName / funcName 都在包里
        val text = String(body, Charsets.ISO_8859_1)
        assertThat(text).contains("PushService")
        assertThat(text).contains("SvcReqRegister")
        assertThat(text).contains("HUAWEI ELE-AL00")
    }

    @Test
    fun `上线包的 uin 用 8 字节长整`() {
        val body = StatSvcRegister.online(
            uin = 2332184782L,
            guid = ByteArray(16),
            deviceName = "HUAWEI ELE-AL00",
            osVersion = "7.1.2",
            sdkLevel = 25L,
        )
        // 内层 SvcReqRegister 紧跟在 sBuffer 那层头之后：找不到就直接找 8 字节序列
        val uinBytes = byteArrayOf(0, 0, 0, 0) + byteArrayOf(
            (2332184782L ushr 24 and 0xFF).toByte(),
            (2332184782L ushr 16 and 0xFF).toByte(),
            (2332184782L ushr 8 and 0xFF).toByte(),
            (2332184782L and 0xFF).toByte(),
        )
        assertThat(indexOf(body, uinBytes)).isGreaterThan(0)
    }

    @Test
    fun `响应里能读出 replyCode`() {
        val ok = JceWriter().apply {
            long(0, 123L)
            byte(2, 0)
        }.toByteArray()
        assertThat(StatSvcRegister.replyCode(response(ok))).isEqualTo(0)

        val bad = JceWriter().apply {
            long(0, 123L)
            byte(2, 5)
        }.toByteArray()
        assertThat(StatSvcRegister.replyCode(response(bad))).isEqualTo(5)
    }

    /**
     * 服务端真的回包长这样：`RequestPacket` 套 `RequestDataVersion3` 的表，
     * 表里那项再套 `0x0A … 0x0B`。只读最外层会读到 `SvcReqRegister` 这个字符串，
     * 把「请求的字段名」当成响应字段。
     */
    @Test
    fun `真正嵌套两层的响应也能读出 replyCode`() {
        val struct = JceWriter().apply {
            long(0, 2332184782L)
            long(1, 7L)
            byte(2, 3) // 服务端拒绝
            int(12, 240)
        }.toByteArray()
        val resp = response(struct)
        assertThat(StatSvcRegister.replyCode(resp)).isEqualTo(3)
        assertThat(StatSvcRegister.helloInterval(resp)).isEqualTo(240)
    }

    /**
     * `sBuffer` 必须是版本 3 的表：`08 | 长度 | 键 | 0A <结构> 0B`。
     * 直接把内层结构塞进去时，服务端连回包都不给 —— 症状是「等 register 超时」。
     */
    @Test
    fun `上线包的 sBuffer 是版本三的表`() {
        val body = StatSvcRegister.online(
            uin = 10001L,
            guid = ByteArray(16),
            deviceName = "HUAWEI ELE-AL00",
            osVersion = "7.1.2",
            sdkLevel = 25L,
        )
        val key = "SvcReqRegister".toByteArray()
        // 表头：map 类型、tag 0 → 0x08，随后 4 字节元素个数 1；
        // 键是 tag 0 的短字符串 → 0x06，再一字节长度
        val head = byteArrayOf(0x08, 0, 0, 0, 1, 0x06, key.size.toByte()) + key
        val at = indexOf(body, head)
        assertThat(at).isGreaterThan(0)
        // 键后面紧跟着 SIMPLE_LIST 的值：类型 13 的头、元素类型 BYTE、4 字节长度、0x0A 开头
        val valueHead = at + head.size
        assertThat(body[valueHead]).isEqualTo(0x0D)
        assertThat(body[valueHead + 1]).isEqualTo(0x00)
        assertThat(body[valueHead + 6]).isEqualTo(0x0A)
    }

    @Test
    fun `没有 replyCode 时按成功处理`() {
        val noCode = JceWriter().apply { long(0, 123L) }.toByteArray()
        assertThat(StatSvcRegister.replyCode(noCode)).isEqualTo(0)
    }

    @Test
    fun `响应里的心跳间隔能读出来`() {
        val body = JceWriter().apply {
            long(0, 123L)
            byte(2, 0)
            int(12, 300)
        }.toByteArray()
        assertThat(StatSvcRegister.helloInterval(body)).isEqualTo(300)
    }

    /**
     * 版本 2 的表是「表里套表」：服务端两种版本都会用，
     * 只认版本 3 会在换版本之后突然读不出 replyCode。
     */
    @Test
    fun `版本二的响应也能读出 replyCode`() {
        val struct = JceWriter().apply {
            long(0, 10001L)
            byte(2, 0)
            int(12, 300)
        }.toByteArray()
        val resp = v2Response(struct)
        assertThat(StatSvcRegister.looksLikeReply(resp)).isTrue()
        assertThat(StatSvcRegister.replyCode(resp)).isEqualTo(0)
        assertThat(StatSvcRegister.helloInterval(resp)).isEqualTo(300)
    }

    @Test
    fun `sBuffer 里直接放裸结构也能认出来`() {
        val struct = JceWriter().apply {
            long(0, 10001L)
            byte(2, 3)
        }.toByteArray()
        val sBuffer = byteArrayOf(0x0A) + struct + byteArrayOf(0x0B)
        val resp = JceWriter().apply {
            short(1, 3)
            byte(2, 0)
            int(3, 0)
            int(4, 0)
            string(5, "PushService")
            string(6, "SvcReqRegister")
            bytes(7, sBuffer)
            int(8, 0)
        }.toByteArray()
        assertThat(StatSvcRegister.replyCode(resp)).isEqualTo(3)
    }

    /**
     * protobuf 的正文常常以 `0x0A` 开头（字段 1、定长），结尾也可能凑出 `0x0B`。
     * 只比首尾两字节就会把推送正文当成 register 的回包，于是「登记成功」了，
     * 后面取消息一直 -10008。所以要连里面第一个字段一起校验。
     */
    @Test
    fun `长得像结构的推送正文不会被当成回包`() {
        // 字段 1（0x0A）+ 长度 5 + 内容 `08 01 10 02` + 结尾 0x0B
        val fake = byteArrayOf(0x0A, 0x05, 0x08, 0x01, 0x10, 0x02, 0x0B)
        assertThat(StatSvcRegister.looksLikeReply(fake)).isFalse()
    }

    /** 把结构按服务端的样子包两层：RequestPacket → sBuffer 表 → 0x0A 结构 0x0B */
    private fun response(struct: ByteArray): ByteArray {
        val framed = byteArrayOf(0x0A) + struct + byteArrayOf(0x0B)
        val sBuffer = JceWriter().apply {
            stringBytesMap(0, listOf("SvcRespRegister" to framed))
        }.toByteArray()
        return JceWriter().apply {
            short(1, 3)
            byte(2, 0)
            int(3, 0)
            int(4, 0)
            string(5, "PushService")
            string(6, "SvcReqRegister")
            bytes(7, sBuffer)
            int(8, 0)
        }.toByteArray()
    }

    /** 版本 2：`map<string, map<string, byte[]>>`，手写更直观 */
    private fun v2Response(struct: ByteArray): ByteArray {
        val framed = byteArrayOf(0x0A) + struct + byteArrayOf(0x0B)
        val key = "SvcRespRegister".toByteArray()
        val keyField = byteArrayOf(0x06, key.size.toByte()) + key
        val value = byteArrayOf(0x0D, 0x00) + be32(framed.size) + framed
        val inner = byteArrayOf(0x08) + be32(1) + keyField + value
        val outerMap = byteArrayOf(0x08) + be32(1) + keyField + inner
        return JceWriter().apply {
            short(1, 3)
            byte(2, 0)
            int(3, 0)
            int(4, 0)
            string(5, "PushService")
            string(6, "SvcReqRegister")
            bytes(7, outerMap)
            int(8, 0)
        }.toByteArray()
    }

    private fun be32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte(),
    )

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
