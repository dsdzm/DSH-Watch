package com.dshwatch.net.qq.msf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * protobuf 编解码。
 *
 * 这里的原则是「不认识的字段必须能跳过」—— 服务端随时会加新字段，
 * 一旦跳不过去，后面所有字段都会错位，表现成「消息内容全是乱码」。
 */
class ProtoTest {

    @Test
    fun `基本类型往返`() {
        val bytes = ProtoWriter()
            .int32(1, 42)
            .varint(2, 2332184782L) // 大于 Int 的 QQ 号
            .strings(3, "你好")
            .bool(4, true)
            .toByteArray()

        val f = ProtoReader.parse(bytes)
        assertThat(f[1]!!.first().asInt).isEqualTo(42)
        assertThat(f[2]!!.first().asLong).isEqualTo(2332184782L)
        assertThat(f[3]!!.first().asString).isEqualTo("你好")
        assertThat(f[4]!!.first().asInt).isEqualTo(1)
    }

    @Test
    fun `嵌套消息与重复字段`() {
        val bytes = ProtoWriter().apply {
            message(1) {
                message(1) { varint(1, 10001L) }
            }
            bytes(2, byteArrayOf(9, 8, 7))
            message(3) { string(1, "a") }
            message(3) { string(1, "b") }
        }.toByteArray()

        val f = ProtoReader.parse(bytes)
        val inner = ProtoReader.parse(ProtoReader.parse(f[1]!!.first().data)[1]!!.first().data)
        assertThat(inner[1]!!.first().asLong).isEqualTo(10001L)
        assertThat(f[2]!!.first().data).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(f[3]!!.map { ProtoReader.parse(it.data)[1]!!.first().asString })
            .containsExactly("a", "b").inOrder()
    }

    @Test
    fun `定长字段会被正确跳过`() {
        // wire type 1（fixed64）与 5（fixed32）在消息里很常见，跳过错了后面全乱
        val raw = byteArrayOf(
            0x11, 1, 2, 3, 4, 5, 6, 7, 8, // tag 2, wire 1
            0x0D, 9, 9, 9, 9, // tag 1, wire 5
            0x08, 7, // tag 1, wire 0 = 7
        )
        val f = ProtoReader.parse(raw)
        assertThat(f[2]!!.first().data).hasLength(8)
        // tag 1 出现了两次：一个定长 32 位（读不出来）和一个 varint 7
        assertThat(f[1]!!.map { it.asInt }).contains(7)
    }

    @Test
    fun `截断的 varint 会报错而不是静默返回`() {
        val broken = byteArrayOf(0x08, 0x80.toByte()) // 声明还有后续字节，但没了
        val e = runCatching { ProtoReader.parse(broken) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
    }
}

/** 测试里读起来顺一点：string 这个名字被 Kotlin 关键字占了语义，用别名 */
private fun ProtoWriter.strings(tag: Int, v: String) = string(tag, v)
