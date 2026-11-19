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
        assertThat(StatSvcRegister.replyCode(ok)).isEqualTo(0)

        val bad = JceWriter().apply {
            long(0, 123L)
            byte(2, 5)
        }.toByteArray()
        assertThat(StatSvcRegister.replyCode(bad)).isEqualTo(5)
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

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
