package com.dshwatch.net.qq.sso

import java.io.ByteArrayOutputStream

/**
 * QQ 协议用的字节写入器。
 *
 * 没有用 [java.nio.ByteBuffer]，因为它默认大端是 Java 特色、Kotlin 里写起来反而绕；
 * 这里的每个方法都对应协议里一个明确的字段宽度，写错了编译能过、联调才会炸。
 *
 * QQ 协议**全部大端**（网络字节序），没有任何一处是小端。
 */
internal class ByteWriter(initial: Int = 64) {
    private val buf = ByteArrayOutputStream(initial)

    val size: Int get() = buf.size()

    fun byte(v: Int): ByteWriter {
        buf.write(v and 0xFF)
        return this
    }

    fun short(v: Int): ByteWriter {
        byte(v ushr 8)
        byte(v)
        return this
    }

    fun int(v: Int): ByteWriter {
        byte(v ushr 24)
        byte(v ushr 16)
        byte(v ushr 8)
        byte(v)
        return this
    }

    fun long(v: Long): ByteWriter {
        int((v ushr 32).toInt())
        int(v.toInt())
        return this
    }

    fun bytes(b: ByteArray): ByteWriter {
        buf.write(b, 0, b.size)
        return this
    }

    fun zeros(n: Int): ByteWriter {
        repeat(n) { byte(0) }
        return this
    }

    /** 2 字节长度 + 内容。协议里大量使用，改了会让所有 TLV 都错位 */
    fun lv16(b: ByteArray): ByteWriter {
        short(b.size)
        bytes(b)
        return this
    }

    /** 4 字节长度 + 内容 */
    fun lv32(b: ByteArray): ByteWriter {
        int(b.size)
        bytes(b)
        return this
    }

    /** 把一段十六进制字面量直接写进去，用于协议里的固定填充 */
    fun hex(s: String): ByteWriter {
        val clean = s.filter { it != ' ' && it != '\n' }
        require(clean.length % 2 == 0) { "hex 长度必须是偶数：$s" }
        for (i in clean.indices step 2) {
            byte(clean.substring(i, i + 2).toInt(16))
        }
        return this
    }

    fun toByteArray(): ByteArray = buf.toByteArray()
}

/**
 * 字节读取器。
 *
 * 所有读方法在越界时抛 [IllegalStateException] 而不是返回 0 ——
 * 协议解析里「读到 0」和「读不到」必须区分开，前者是合法值，后者说明包结构判断错了。
 */
internal class ByteReader(private val data: ByteArray, var pos: Int = 0) {

    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (remaining < n) {
            throw IllegalStateException("包长度不够：还差 $n 字节，只剩 $remaining")
        }
    }

    fun byte(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun short(): Int {
        need(2)
        val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun int(): Int {
        need(4)
        val v = ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun long(): Long {
        val hi = int().toLong() and 0xFFFFFFFFL
        val lo = int().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun lv16(): ByteArray = bytes(short())

    fun lv32(): ByteArray = bytes(int())

    fun skip(n: Int) {
        need(n)
        pos += n
    }

    /** 读剩下的全部 */
    fun rest(): ByteArray = bytes(remaining)
}
