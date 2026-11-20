package com.dshwatch.net.qq.sso

/**
 * `RequestPacket` 这层壳的读写。
 *
 * 服务端收发老服务的请求时，外面永远套三层：
 *
 * ```
 * RequestPacket { servantName, funcName, sBuffer = RequestDataVersion3 { "名字" -> 0x0A 结构 0x0B } }
 * ```
 *
 * 结构体本身**没有**长度前缀，靠 `0x0A` / `0x0B`（Tars 的 STRUCT_BEGIN / END）括起来；
 * 裸结构直接塞进 `sBuffer` 时服务端不解包也不报错，表现是「根本没有回包」。
 * 注册、推送回执都走这套壳，所以放在一处实现，别各写一份。
 */
internal object TarsPacket {

    private const val HEAD: Byte = 0x0A
    private const val TAIL: Byte = 0x0B

    /** 版本 3 的 `sBuffer`：`map<string, byte[]>`，值外面再套一对结构标记 */
    fun sBufferV3(name: String, struct: ByteArray): ByteArray =
        JceWriter(struct.size + 48).apply {
            stringBytesMap(0, listOf(name to (byteArrayOf(HEAD) + struct + byteArrayOf(TAIL))))
        }.toByteArray()

    fun request(servant: String, func: String, sBuffer: ByteArray): ByteArray =
        JceWriter(sBuffer.size + 96).apply {
            short(1, 3) // 协议版本固定 3
            byte(2, 0) // 普通请求
            int(3, 0) // iMessageType
            int(4, 0) // requestId
            string(5, servant)
            string(6, func)
            bytes(7, sBuffer)
            int(8, 0) // iTimeout
        }.toByteArray()

    /**
     * 从回包里剥出内层结构（已去掉 `0x0A` / `0x0B`）。
     *
     * 依次尝试：`sBuffer` 里是版本 3 的表 → 版本 2 的嵌套表 → 裸结构；
     * 都不行就把整个正文当成结构试一次（有的版本直接回裸结构）。
     * 只校验「首尾是结构标记」，里面是不是想要的那个结构由调用方判断。
     */
    fun unwrapStruct(body: ByteArray): ByteArray? {
        val outer = JceReader(body)
        while (true) {
            val f = runCatching { outer.next() }.getOrNull() ?: break
            if (f.tag != 7 || f.value !is ByteArray) continue
            val sb = f.value as ByteArray
            trim(sb)?.let { return it }
            val table = JceReader(sb)
            while (true) {
                val g = runCatching { table.next() }.getOrNull() ?: break
                val map = g.value as? Map<*, *> ?: continue
                fromMap(map)?.let { return it }
            }
        }
        return trim(body)
    }

    /** 版本 3 的值是 byte[]，版本 2 的值是再一层表 */
    private fun fromMap(map: Map<*, *>): ByteArray? {
        for (v in map.values) {
            val raw = when (v) {
                is ByteArray -> v
                is Map<*, *> -> v.values.firstOrNull() as? ByteArray
                else -> null
            } ?: continue
            trim(raw)?.let { return it }
        }
        return null
    }

    private fun trim(raw: ByteArray): ByteArray? =
        if (raw.size >= 2 && raw[0] == HEAD && raw[raw.size - 1] == TAIL) {
            raw.copyOfRange(1, raw.size - 1)
        } else {
            null
        }

    /**
     * 第一个字段的 tag。
     *
     * 用来区分「这是不是我要的那个结构」：`SvcRespRegister` 以 uin（tag 0）开头，
     * `PushReq` 以 type（tag 1）开头。只比首尾两字节的话，protobuf 正文
     * （字段 1 的头恰好就是 `0x0A`）会被误认成回包。
     */
    fun firstTag(struct: ByteArray): Int? {
        if (struct.isEmpty()) return null
        val b = struct[0].toInt() and 0xFF
        val tag = (b shr 4) and 0x0F
        return if (tag == 0x0F) null else tag
    }

    /** 第一个字段的类型（低 4 位）。tag ≥ 15 时头是两字节，这里不处理 */
    fun firstType(struct: ByteArray): Int? {
        if (struct.isEmpty()) return null
        return (struct[0].toInt() and 0xFF) and 0x0F
    }

    fun intOf(struct: ByteArray, tag: Int): Int? =
        fieldOf(struct, tag)?.let {
            when (it) {
                is Int -> it
                is Long -> it.toInt()
                else -> null
            }
        }

    fun longOf(struct: ByteArray, tag: Int): Long? =
        fieldOf(struct, tag)?.let {
            when (it) {
                is Long -> it
                is Int -> it.toLong()
                else -> null
            }
        }

    private fun fieldOf(struct: ByteArray, tag: Int): Any? {
        val r = JceReader(struct)
        while (true) {
            val f = runCatching { r.next() }.getOrNull() ?: return null
            if (f.tag == tag) return f.value
        }
    }
}
