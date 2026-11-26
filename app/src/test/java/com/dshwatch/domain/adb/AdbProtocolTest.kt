package com.dshwatch.domain.adb

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

class AdbProtocolTest {

    @Test
    fun `消息编码后可原样读回`() {
        val payload = "shell,v2,raw:echo hi\u0000".toByteArray()
        val original = AdbMessage(AdbCommand.OPEN, 1, 0, payload)
        val bytes = original.bytes()

        assertThat(bytes).hasLength(24 + payload.size)
        val read = readMessage(ByteArrayInputStream(bytes))
        assertThat(read.command).isEqualTo(AdbCommand.OPEN)
        assertThat(read.arg0).isEqualTo(1)
        assertThat(read.arg1).isEqualTo(0)
        assertThat(read.payload).isEqualTo(payload)
    }

    @Test
    fun `magic 不匹配时拒绝解析`() {
        val bytes = AdbMessage(AdbCommand.CNXN, 1, 2).bytes()
        // 翻掉 magic 的最后一个字节
        bytes[23] = (bytes[23].toInt() xor 0xFF).toByte()

        val error = runCatching { readMessage(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AdbException::class.java)
    }

    @Test
    fun `长度为 0 的消息没有载荷`() {
        val read = readMessage(ByteArrayInputStream(AdbMessage(AdbCommand.OKAY, 3, 4).bytes()))
        assertThat(read.payload).isEmpty()
    }

    @Test
    fun `checksum 是字节和`() {
        assertThat(checksum(byteArrayOf(0x01, 0x02, 0xFF.toByte()))).isEqualTo(0x102)
        assertThat(checksum(ByteArray(0))).isEqualTo(0)
    }

    @Test
    fun `v2 拆包能处理一条数据里的多个包`() {
        val decoder = ShellV2Decoder()
        val merged = packet(1, ShellV2.STDOUT, "hello".toByteArray()) +
            packet(1, ShellV2.STDERR, "warn".toByteArray()) +
            packet(1, ShellV2.EXIT, byteArrayOf(0))

        val packets = decoder.feed(merged)
        assertThat(packets).hasSize(3)
        assertThat(packets[0].type).isEqualTo(ShellV2.STDOUT)
        assertThat(packets[0].data.toString(Charsets.UTF_8)).isEqualTo("hello")
        assertThat(packets[1].type).isEqualTo(ShellV2.STDERR)
        assertThat(packets[2].type).isEqualTo(ShellV2.EXIT)
    }

    @Test
    fun `v2 拆包能处理跨块的半个包`() {
        val decoder = ShellV2Decoder()
        val full = packet(2, ShellV2.STDOUT, "abcdef".toByteArray())
        val first = decoder.feed(full.copyOfRange(0, 4))
        assertThat(first).isEmpty()

        val second = decoder.feed(full.copyOfRange(4, full.size))
        assertThat(second).hasSize(1)
        assertThat(second[0].data.toString(Charsets.UTF_8)).isEqualTo("abcdef")
    }

    @Test
    fun `v2 拆包在包边界处不留残渣`() {
        val decoder = ShellV2Decoder()
        val a = packet(1, ShellV2.STDOUT, "aa".toByteArray())
        val b = packet(1, ShellV2.STDOUT, "bb".toByteArray())

        assertThat(decoder.feed(a)).hasSize(1)
        val second = decoder.feed(b)
        assertThat(second).hasSize(1)
        assertThat(second[0].data.toString(Charsets.UTF_8)).isEqualTo("bb")
    }

    @Test
    fun `长度按大端写出时仍能解析`() {
        /*
         * 真机现场：长度 100 被读成 1677721600（0x64000000）。
         * 这个数只能来自「按大端读小端数据」，说明链路上有实现按大端写长度。
         * 兜底策略是：小端值越界时再按大端试一次。
         */
        val decoder = ShellV2Decoder()
        val data = "ok\n".toByteArray()
        val out = ByteArray(6 + data.size)
        out[0] = 1
        out[1] = ShellV2.STDOUT.toByte()
        out[2] = 0
        out[3] = 0
        out[4] = 0
        out[5] = data.size.toByte()
        data.copyInto(out, 6)

        val packets = decoder.feed(out)
        assertThat(packets).hasSize(1)
        assertThat(packets[0].data.toString(Charsets.UTF_8)).isEqualTo("ok\n")
    }

    @Test
    fun `两种字节序都不成立时报错并带上头部字节`() {
        val decoder = ShellV2Decoder()
        // 头部 01 01 ff ff ff ff：小端与大端都远超上限
        val bad = byteArrayOf(
            1, 1,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val error = runCatching { decoder.feed(bad) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AdbException::class.java)
        // 出错要能降级到 legacy 通道，也要把现场留下来
        assertThat((error as AdbException).fallback).isTrue()
        assertThat(error.message).contains("01 01 ff ff ff ff")
    }

    private fun packet(id: Int, type: Int, data: ByteArray): ByteArray {
        val out = ByteArray(6 + data.size)
        out[0] = id.toByte()
        out[1] = type.toByte()
        putIntLe(out, 2, data.size)
        data.copyInto(out, 6)
        return out
    }
}
