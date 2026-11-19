package com.dshwatch.net.qq.sso

/**
 * Tars / JCE 编码。
 *
 * QQ 里有两套序列化并存：新服务（消息、好友）用 protobuf，
 * 老服务（`StatSvc.register`、好友列表）还在用 Tars（腾讯内部叫 JCE）。
 * 两套的字段号都从 0 开始，但**字节格式完全不同**，不能混用同一套写入器。
 *
 * 规则很短，但有两个地方特别容易写错：
 *
 * 1. 字段头是**一字节**：低 4 位类型、高 4 位 tag。tag ≥ 15 时高 4 位写 `0xF`，
 *    真正的 tag 挪到下一字节 —— 不是「tag 放不下就换两字节头」，低 4 位仍是类型。
 * 2. 结构体（struct）字段没有长度前缀，靠 `STRUCT_BEGIN` / `STRUCT_END` 括起来。
 *    顶层结构不需要这两个头（整段就是一个结构）。
 */
internal object JceType {
    const val BYTE = 0
    const val SHORT = 1
    const val INT = 2
    const val LONG = 3
    const val STRING1 = 6
    const val STRING4 = 7
    const val MAP = 8
    const val LIST = 9
    const val STRUCT_BEGIN = 10
    const val STRUCT_END = 11
    const val ZERO = 12
    const val SIMPLE_LIST = 13
}

internal class JceWriter(initial: Int = 256) {
    private val out = ByteWriter(initial)

    val size: Int get() = out.size

    private fun head(type: Int, tag: Int) {
        if (tag < 15) {
            out.byte((tag shl 4) or type)
        } else {
            out.byte(0xF0 or type)
            out.byte(tag)
        }
    }

    fun byte(tag: Int, v: Int) {
        head(JceType.BYTE, tag)
        out.byte(v)
    }

    fun short(tag: Int, v: Int) {
        head(JceType.SHORT, tag)
        out.short(v)
    }

    /** 值为 0 时按 Tars 惯例只写一个「零」头，省 4 字节 —— 服务端一样能读出 0 */
    fun int(tag: Int, v: Int) {
        if (v == 0) {
            head(JceType.ZERO, tag)
        } else {
            head(JceType.INT, tag)
            out.int(v)
        }
    }

    fun long(tag: Int, v: Long) {
        if (v == 0L) {
            head(JceType.ZERO, tag)
        } else {
            head(JceType.LONG, tag)
            out.long(v)
        }
    }

    fun string(tag: Int, v: String) {
        val b = v.toByteArray(Charsets.UTF_8)
        if (b.size <= 255) {
            head(JceType.STRING1, tag)
            out.byte(b.size)
        } else {
            head(JceType.STRING4, tag)
            out.int(b.size)
        }
        out.bytes(b)
    }

    /** 字节数组走 SIMPLE_LIST：外面还套一层「元素类型 = BYTE」的头 */
    fun bytes(tag: Int, v: ByteArray) {
        head(JceType.SIMPLE_LIST, tag)
        head(JceType.BYTE, 0)
        out.int(v.size)
        out.bytes(v)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class JceReader(private val data: ByteArray) {
    private val r = ByteReader(data)

    class Field(val tag: Int, val type: Int, val value: Any?)

    /** 读一个字段；到末尾返回 null */
    fun next(): Field? {
        if (r.remaining <= 0) return null
        val (type, tag) = head()
        if (type == JceType.STRUCT_END) return null
        val value: Any? = when (type) {
            JceType.BYTE -> r.byte()
            JceType.SHORT -> r.short()
            JceType.INT -> r.int()
            JceType.LONG -> r.long()
            JceType.STRING1 -> String(r.bytes(r.byte()), Charsets.UTF_8)
            JceType.STRING4 -> String(r.bytes(r.int()), Charsets.UTF_8)
            JceType.SIMPLE_LIST -> {
                r.byte() // 元素类型，这里只会是 BYTE
                r.bytes(r.int())
            }
            JceType.ZERO -> 0
            JceType.STRUCT_BEGIN -> {
                skipStruct()
                null
            }
            else -> {
                skipValue(type)
                null
            }
        }
        return Field(tag, type, value)
    }

    private fun head(): Pair<Int, Int> {
        val b = r.byte()
        val type = b and 0x0F
        var tag = (b shr 4) and 0x0F
        if (tag == 0x0F) tag = r.byte()
        return type to tag
    }

    private fun skipStruct() {
        while (r.remaining > 0) {
            val (type, _) = head()
            if (type == JceType.STRUCT_END) return
            skipValue(type)
        }
    }

    private fun skipValue(type: Int) {
        when (type) {
            JceType.BYTE -> r.skip(1)
            JceType.SHORT -> r.skip(2)
            JceType.INT -> r.skip(4)
            JceType.LONG -> r.skip(8)
            JceType.STRING1 -> r.skip(r.byte())
            JceType.STRING4 -> r.skip(r.int())
            JceType.SIMPLE_LIST -> {
                r.skip(1)
                r.skip(r.int())
            }
            JceType.MAP -> {
                val n = r.int()
                repeat(n) {
                    skipElement()
                    skipElement()
                }
            }
            JceType.LIST -> {
                val n = r.int()
                repeat(n) { skipElement() }
            }
            JceType.STRUCT_BEGIN -> skipStruct()
            JceType.ZERO, JceType.STRUCT_END -> Unit
            else -> throw IllegalStateException("不认识的 JCE 类型 $type")
        }
    }

    /** 容器里的元素同样带「tag 0 + 类型」的头 */
    private fun skipElement() {
        val (type, _) = head()
        skipValue(type)
    }
}
