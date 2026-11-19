package com.dshwatch.net.qq.sso

import com.dshwatch.core.Logx
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 一条到 QQ 登录服务器的裸 TCP 连接。
 *
 * 协议不是 HTTP，没有任何文本头 —— 发出去的就是上面那几层二进制包，
 * 回来的是「4 字节总长 + 内容」。所以这里不能复用项目里的 OkHttp，
 * 只能自己管 socket。
 *
 * 一次登录只发三四个包，每个包单独建连反而更省事：长连接要额外维护心跳和
 * 断线重连，而登录阶段一旦断开本来就该从头扫一次码。
 */
internal class SsoSocket(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : Closeable {

    private var socket: Socket? = null

    private fun ensure(): Socket {
        socket?.let { if (!it.isClosed && it.isConnected) return it }
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        socket = s
        return s
    }

    /**
     * 发一个包并等一个回包。
     *
     * @return 完整响应（含最外层 4 字节长度）
     */
    fun roundTrip(packet: ByteArray): ByteArray {
        val s = ensure()
        s.getOutputStream().apply { write(packet); flush() }

        val input = s.getInputStream()
        val head = readFully(input, 4)
        val total = be32(head)
        if (total < 4 || total > MAX_PACKET) {
            throw IllegalStateException("响应长度异常：$total")
        }
        val rest = readFully(input, total - 4)
        Logx.d(TAG, "收包 ${total} 字节")
        return head + rest
    }

    private fun readFully(input: java.io.InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw IllegalStateException("连接被关闭：只读到 $off/$n 字节")
            off += r
        }
        return out
    }

    private fun be32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "SsoSocket"

        /** QQ 的登录入口，这个域名十几年没变过 */
        const val DEFAULT_HOST = "msfwifi.3g.qq.com"
        const val DEFAULT_PORT = 8080

        const val CONNECT_TIMEOUT_MS = 10_000

        /**
         * 读超时。服务端收不满一个包时会一直等、既不回包也不断开，
         * 所以这个值同时是「这条链路到底通不通」的判据，别调太大。
         */
        const val READ_TIMEOUT_MS = 15_000

        /** 单个包上限，正常响应都在几 KB */
        private const val MAX_PACKET = 2 * 1024 * 1024
    }
}
