package com.dshwatch.net.qq.msf

import com.dshwatch.net.qq.sso.ByteReader

/**
 * 最小 protobuf 编解码。
 *
 * 为什么不用现成的库：这里只需要「写几个定长/嵌套字段、读回来按 tag 取值」，
 * 而 QQ 的消息体里有大量我们根本不关心的字段（图片、红包、小程序…）。
 * 手写一个按 wire type 跳过的读法，比生成整套 data class 更不容易被新字段打断 ——
 * 服务端加字段时，未知 tag 直接跳过就好。
 */
internal class ProtoWriter(initial: Int = 128) {

    private val out = java.io.ByteArrayOutputStream(initial)

    fun varint(tag: Int, value: Long): ProtoWriter {
        key(tag, 0)
        writeVarint(value)
        return this
    }

    fun int32(tag: Int, value: Int): ProtoWriter = varint(tag, value.toLong() and 0xFFFFFFFFL)

    fun bool(tag: Int, value: Boolean): ProtoWriter = varint(tag, if (value) 1 else 0)

    fun bytes(tag: Int, value: ByteArray): ProtoWriter {
        key(tag, 2)
        writeVarint(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    fun string(tag: Int, value: String): ProtoWriter = bytes(tag, value.toByteArray(Charsets.UTF_8))

    /** 嵌套消息：长度前缀在写完子消息后才能确定，所以先编码到临时缓冲 */
    fun message(tag: Int, block: ProtoWriter.() -> Unit): ProtoWriter {
        val sub = ProtoWriter().apply(block).toByteArray()
        return bytes(tag, sub)
    }

    private fun key(tag: Int, wire: Int) = writeVarint(((tag shl 3) or wire).toLong())

    private fun writeVarint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt())
                return
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** 一个字段：tag 与原始内容。wire type 0 的值放在 [varint] 里 */
internal class ProtoField(val tag: Int, val wire: Int, val data: ByteArray, val varint: Long) {
    val asString: String get() = String(data, Charsets.UTF_8)
    val asInt: Int get() = varint.toInt()
    val asLong: Long get() = varint

    /** 嵌套消息 */
    fun message(): ProtoReader = ProtoReader(data)
}

internal class ProtoReader(private val data: ByteArray, private var pos: Int = 0) {

    fun hasNext(): Boolean = pos < data.size

    /**
     * 读一个字段。
     *
     * wire type 1 / 5 是定长 64 / 32 位，这里用不到，但必须正确跳过 ——
     * 否则后面所有字段都会错位，表现为「最近消息全是乱码」。
     */
    fun next(): ProtoField {
        val key = readVarint().toInt()
        val tag = key ushr 3
        val wire = key and 7
        return when (wire) {
            0 -> ProtoField(tag, wire, ByteArray(0), readVarint())
            1 -> {
                val b = readBytes(8)
                ProtoField(tag, wire, b, 0)
            }

            2 -> {
                val len = readVarint().toInt()
                ProtoField(tag, wire, readBytes(len), 0)
            }

            5 -> {
                val b = readBytes(4)
                ProtoField(tag, wire, b, 0)
            }

            else -> throw IllegalStateException("未知的 protobuf wire type $wire（tag=$tag）")
        }
    }

    /** 把剩下的字段读完，按 tag 分组（同 tag 可能重复出现，例如 elems） */
    fun all(): Map<Int, List<ProtoField>> {
        val out = LinkedHashMap<Int, MutableList<ProtoField>>()
        while (hasNext()) {
            val f = next()
            out.getOrPut(f.tag) { ArrayList() }.add(f)
        }
        return out
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= data.size) throw IllegalStateException("varint 被截断")
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalStateException("varint 过长")
        }
    }

    private fun readBytes(n: Int): ByteArray {
        if (n < 0 || pos + n > data.size) throw IllegalStateException("字段长度越界：$n")
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    companion object {
        /** 给上层直接按 tag 取第一个值用 */
        fun parse(data: ByteArray): Map<Int, List<ProtoField>> = ProtoReader(data).all()

        /** 常见组合：取第一个 bytes 字段当字符串 */
        fun text(fields: Map<Int, List<ProtoField>>, tag: Int): String =
            fields[tag]?.firstOrNull()?.asString.orEmpty()
    }
}
