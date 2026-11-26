package com.dshwatch.domain.adb

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * ADB 协议层错误，带一句能直接给用户看的原因。
 *
 * [fallback] 表示「这条连接本身是通的，只是某种服务形式不被接受」——
 * 例如设备不支持 `shell,v2,raw:` 或 v2 子包格式对不上。调用方可以据此
 * 换一种服务名重试，而不是把整次连接判死。
 */
class AdbException(
    message: String,
    cause: Throwable? = null,
    val fallback: Boolean = false,
) : Exception(message, cause)

/** 一条 ADB 包。载荷长度上限由 CNXN 的 arg1 协商，这里只做防御性检查。 */
internal data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    fun bytes(): ByteArray {
        val out = ByteArray(24 + payload.size)
        putIntLe(out, 0, command)
        putIntLe(out, 4, arg0)
        putIntLe(out, 8, arg1)
        putIntLe(out, 12, payload.size)
        putIntLe(out, 16, checksum(payload))
        putIntLe(out, 20, command.inv())
        payload.copyInto(out, 24)
        return out
    }
}

internal object AdbCommand {
    const val CNXN = 0x4E584E43
    const val AUTH = 0x48545541
    const val OPEN = 0x4E45504F
    const val OKAY = 0x59414B4F
    const val WRTE = 0x45545257
    const val CLSE = 0x45534C43
    const val STLS = 0x534C5453
}

internal const val AUTH_TOKEN = 1
internal const val AUTH_SIGNATURE = 2
internal const val AUTH_PUBLIC_KEY = 3

internal const val MAX_PAYLOAD = 256 * 1024

internal fun checksum(data: ByteArray): Int {
    var sum = 0
    for (b in data) sum += b.toInt() and 0xFF
    return sum
}

internal fun writeMessage(out: OutputStream, message: AdbMessage) {
    out.write(message.bytes())
    out.flush()
}

/**
 * 读一条完整消息。
 *
 * 校验 magic 是必须的：TCP 上粘包、半包都可能发生，不校验就会把
 * 后续载荷当成消息头解析，报出来的错与真实原因毫无关系。
 */
internal fun readMessage(input: InputStream): AdbMessage {
    val header = ByteArray(24)
    readFully(input, header)
    val command = intLe(header, 0)
    val arg0 = intLe(header, 4)
    val arg1 = intLe(header, 8)
    val length = intLe(header, 12)
    val magic = intLe(header, 20)
    if (magic != command.inv()) {
        throw AdbException("ADB 消息头校验失败（magic 不匹配）")
    }
    if (length < 0 || length > MAX_PAYLOAD) {
        throw AdbException("ADB 消息长度异常：$length")
    }
    val payload = if (length == 0) ByteArray(0) else ByteArray(length).also {
        readFully(input, it)
    }
    return AdbMessage(command, arg0, arg1, payload)
}

internal fun readFully(input: InputStream, dst: ByteArray) {
    var read = 0
    while (read < dst.size) {
        val n = input.read(dst, read, dst.size - read)
        if (n < 0) throw EOFException("连接被对端关闭")
        read += n
    }
}

internal fun putIntLe(dst: ByteArray, offset: Int, value: Int) {
    dst[offset] = (value and 0xFF).toByte()
    dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

internal fun intLe(src: ByteArray, offset: Int): Int =
    (src[offset].toInt() and 0xFF) or
        ((src[offset + 1].toInt() and 0xFF) shl 8) or
        ((src[offset + 2].toInt() and 0xFF) shl 16) or
        ((src[offset + 3].toInt() and 0xFF) shl 24)

internal fun intBe(src: ByteArray, offset: Int): Int =
    ((src[offset].toInt() and 0xFF) shl 24) or
        ((src[offset + 1].toInt() and 0xFF) shl 16) or
        ((src[offset + 2].toInt() and 0xFF) shl 8) or
        (src[offset + 3].toInt() and 0xFF)

/** 把一段字节打成 `01 02 0a ff` 形式，只用于出错时给人看。 */
internal fun hexOf(src: ByteArray, offset: Int, count: Int): String {
    val end = minOf(src.size, offset + count)
    if (offset >= end) return ""
    val sb = StringBuilder(count * 3)
    for (i in offset until end) {
        if (sb.isNotEmpty()) sb.append(' ')
        val v = src[i].toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private const val HEX = "0123456789abcdef"

/** shell v2 的一个子包。type 见 [ShellV2] 里的常量。 */
internal data class ShellPacket(val id: Int, val type: Int, val data: ByteArray)

/**
 * shell v2 的拆包器。
 *
 * 一条 WRTE 里可能塞了多个子包，也可能一个子包跨两条 WRTE —— adbd 只保证
 * 字节流顺序，不保证边界。所以必须自己缓冲、按长度切，不能假设一一对应。
 */
internal class ShellV2Decoder {
    private var buffer = ByteArray(0)

    private companion object {
        const val MAX_BUFFER = 2 * 1024 * 1024
    }

    fun feed(chunk: ByteArray): List<ShellPacket> {
        if (chunk.isNotEmpty()) {
            val merged = ByteArray(buffer.size + chunk.size)
            buffer.copyInto(merged, 0)
            chunk.copyInto(merged, buffer.size)
            buffer = merged
        }
        /*
         * 缓冲上限。若长度字段被解成一个很大却「合法」的值，解析会一直等这块数据
         * 到齐，缓冲随之涨到几十 MB —— 在手表上就是一次 OOM，堆栈里只看得见
         * ByteArrayOutputStream.grow。宁可报错降级，也不要吃满堆。
         */
        if (buffer.size > MAX_BUFFER) {
            throw AdbException(
                "shell 数据缓冲超过 ${MAX_BUFFER / 1024} KB，疑似长度字段解析错误",
                fallback = true,
            )
        }
        val out = ArrayList<ShellPacket>(2)
        var offset = 0
        while (buffer.size - offset >= ShellV2.HEADER_BYTES) {
            val id = buffer[offset].toInt() and 0xFF
            val type = buffer[offset + 1].toInt() and 0xFF
            val length = lengthAt(offset)
            if (buffer.size - offset - ShellV2.HEADER_BYTES < length) break
            val start = offset + ShellV2.HEADER_BYTES
            out += ShellPacket(id, type, buffer.copyOfRange(start, start + length))
            offset = start + length
        }
        buffer = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
        return out
    }

    /**
     * 读子包长度。
     *
     * 协议规定 length 是小端，绝大多数设备也确实如此。但已经出现过「按小端读出
     * 一个荒谬的大数」的现场（0x64000000 = 1677721600，而真实长度是 100），
     * 说明这条链路上有实现按大端写。这里先按小端，越界再按大端试一次：
     * 两者不会同时落在合法区间里，所以兜底不会误判正常设备。
     */
    private fun lengthAt(offset: Int): Int {
        val le = intLe(buffer, offset + 2)
        if (le in 0..MAX_PAYLOAD) return le
        val be = intBe(buffer, offset + 2)
        if (be in 0..MAX_PAYLOAD) return be
        throw AdbException(
            "shell 数据格式不匹配（长度 $le，头部 ${hexOf(buffer, offset, 12)}）",
            fallback = true,
        )
    }
}

internal object ShellV2 {
    /** id(1) + type(1) + 长度(4)，共 6 字节 */
    const val HEADER_BYTES = 6

    const val STDIN = 0
    const val STDOUT = 1
    const val STDERR = 2
    const val EXIT = 3
    const val CLOSE_STDIN = 4
}
