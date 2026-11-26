package com.dshwatch.domain.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair

/**
 * 一个最小可用的 ADB 客户端。
 *
 * 只实现三件事：握手认证、打开一条 `shell,v2,raw:` 流、把输出读干净。
 * 文件同步、端口转发、日志流一概不做 —— 这个应用需要的是「跑一条命令、拿回结果」，
 * 多实现一种服务就多一份在旧 ROM 上踩坑的机会。
 *
 * ## 认证流程
 *
 * ```
 * 客户端 → CNXN（声明支持的 feature）
 * 服务端 → AUTH(TYPE_TOKEN, 20 字节随机数)
 * 客户端 → AUTH(TYPE_SIGNATURE, RSA 签名)
 * 服务端 → 若认识这把密钥：CNXN；否则再来一次 AUTH(TYPE_TOKEN)
 * 客户端 → AUTH(TYPE_PUBLIC_KEY, 公钥文本)
 * 手机弹出「允许 USB 调试吗？」→ 用户点允许 → 服务端回 CNXN
 * ```
 *
 * 第二次 AUTH 之后超时要给足 —— 这段时间全花在等人点屏幕。超时设 8 秒的话，
 * 用户还没看清弹窗就已经失败了，而且失败信息只会是「连接超时」。
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: KeyPair,
) : Closeable {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var maxPayload = MAX_PAYLOAD
    private var remoteId = -1

    /*
     * 流编号必须递增，不能每条命令都用 1。
     *
     * 降级重试时旧流刚发过 CLSE，adbd 侧还没回收；新流再用同一个编号，
     * 它的输出会被算到那条正在关闭的流上，表现为「OPEN 成功、CLSE 到达、
     * 一个字节都没有」。
     */
    private var localSeed = 0

    /** 最近一次执行收到的原始字节（十六进制，最多 96 字节），仅用于排错。 */
    private val raw = java.io.ByteArrayOutputStream()
    private var recordRaw = true

    val diagnostics: String get() = hexOf(raw.toByteArray(), 0, 96)

    val endpoint: String get() = "$host:$port"

    /**
     * 连接并完成认证。
     *
     * @param connectTimeoutMs TCP 建连上限
     * @param authTimeoutMs 等待用户点「允许」的上限
     */
    fun connect(connectTimeoutMs: Int = 6_000, authTimeoutMs: Int = 120_000) {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = authTimeoutMs
        } catch (e: Exception) {
            runCatching { s.close() }
            throw AdbException(describeConnect(e), e)
        }
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()

        writeMessage(output!!, AdbMessage(
            command = AdbCommand.CNXN,
            arg0 = 0x01000000,
            arg1 = MAX_PAYLOAD,
            payload = "host::features=shell_v2,cmd,stat_v2,apex\u0000".toByteArray(),
        ))

        // 0 = 还没签名，1 = 已发签名，2 = 已发公钥
        var stage = 0
        while (true) {
            val message = try {
                readMessage(input!!)
            } catch (e: SocketTimeoutException) {
                throw AdbException(
                    if (stage >= 2) "等待设备确认超时。请检查手机屏幕是否弹出了「允许调试」。" else "连接超时。",
                    e,
                )
            }
            when (message.command) {
                AdbCommand.CNXN -> {
                    maxPayload = minOf(message.arg1, MAX_PAYLOAD).coerceAtLeast(4096)
                    s.soTimeout = 0
                    return
                }

                AdbCommand.AUTH -> {
                    when (message.arg0) {
                        AUTH_TOKEN -> when (stage) {
                            0 -> {
                                stage = 1
                                reply(AdbMessage(AdbCommand.AUTH, AUTH_SIGNATURE, 0, AdbKey.sign(key, message.payload)))
                            }

                            1 -> {
                                stage = 2
                                val text = AdbKey.publicKeyText(key) + "\u0000"
                                reply(AdbMessage(AdbCommand.AUTH, AUTH_PUBLIC_KEY, 0, text.toByteArray()))
                            }

                            else -> throw AdbException("设备拒绝了调试授权。请在手机上重新点「允许」。")
                        }

                        else -> throw AdbException("设备返回了未知的认证类型：${message.arg0}")
                    }
                }

                AdbCommand.CLSE -> throw AdbException("设备关闭了连接：${textOf(message.payload)}")
                AdbCommand.STLS -> throw AdbException("设备要求 TLS 连接，本机当前不支持。")
                else -> Unit
            }
        }
    }

    /**
     * 执行一条命令。
     *
     * 优先走 `shell,v2,raw:`（能拿到退出码，stdout 与 stderr 分开）。若设备不认这种
     * 服务形式、或返回的子包格式对不上，自动退回 `shell,raw:`：输出仍是原文，
     * 只是拿不到退出码。降级只在「一个字节都还没收到」时发生 —— 已经吐了一半再重跑
     * 会让同一段内容出现两次。
     *
     * @return 退出码。走 legacy 通道或设备不返回时为 -1。
     */
    fun exec(
        command: String,
        timeoutMs: Int = 30_000,
        onStdout: (ByteArray) -> Unit = {},
        onStderr: (ByteArray) -> Unit = {},
    ): Int {
        var received = 0
        val counting: (ByteArray) -> Unit = {
            received += it.size
            onStdout(it)
        }
        raw.reset()
        val services = listOf(
            "shell,v2,raw:$command" to true,
            "shell,raw:$command" to false,
            "shell:$command" to false,
        )
        var last: AdbException? = null
        for ((index, entry) in services.withIndex()) {
            val (service, v2) = entry
            // 只留第一级的原始字节：诊断时关心的就是「设备到底回了什么格式」
            recordRaw = index == 0
            try {
                val code = execStream(service, timeoutMs, v2, counting, onStderr)
                /*
                 * v2 通道可能「握手成功但什么都没给」：拿到 OKAY、立刻 CLSE、
                 * 没有输出也没有退出码。这种情况不能当成空结果交出去，换下一种
                 * 服务形式再试 —— 用户要的是命令跑出结果，不是一条空流。
                 */
                if (code < 0 && received == 0 && index < services.lastIndex) continue
                /*
                 * 有输出但没有 EXIT 子包：命令其实跑完了，只是设备没按约定给退出码。
                 * 按失败上报会让模型看到「命令失败」而实际结果就在下面，容易误判。
                 */
                return if (code < 0 && received > 0) 0 else code
            } catch (e: AdbException) {
                last = e
                if (!e.fallback || received > 0) throw e
                // 放弃的这条流要显式关掉，否则设备侧的子进程会一直挂着
                val dead = remoteId
                if (dead >= 0) {
                    runCatching { reply(AdbMessage(AdbCommand.CLSE, localSeed, dead)) }
                }
            }
        }
        throw last ?: AdbException("无法打开命令通道")
    }

    private fun execStream(
        service: String,
        timeoutMs: Int,
        v2: Boolean,
        onStdout: (ByteArray) -> Unit,
        onStderr: (ByteArray) -> Unit,
    ): Int {
        val out = output ?: throw AdbException("尚未连接")
        val inp = input ?: throw AdbException("尚未连接")
        socket?.soTimeout = timeoutMs
        val deadline = System.currentTimeMillis() + timeoutMs

        val localId = ++localSeed
        val name = service.toByteArray()
        val payload = ByteArray(name.size + 1)
        name.copyInto(payload)
        writeMessage(out, AdbMessage(AdbCommand.OPEN, localId, 0, payload))

        /*
         * OPEN 之后设备可能把 OKAY 和第一条 WRTE 一次写出来。早到的 WRTE 要留着：
         * 以前这里直接 `continue` 丢掉，短命令（echo 一行）的整段输出就此消失，
         * 表现成「连接成功但命令没有任何返回」。
         */
        val early = ArrayList<ByteArray>(2)
        remoteId = -1
        while (true) {
            val message = readOrTimeout(inp, deadline)
            when (message.command) {
                AdbCommand.OKAY -> {
                    if (remoteId < 0) {
                        remoteId = message.arg0
                        if (remoteId < 0) throw AdbException("设备返回了无效的数据流编号")
                    }
                    break
                }

                AdbCommand.WRTE -> early += message.payload

                AdbCommand.CLSE -> throw AdbException(
                    "设备拒绝执行：${textOf(message.payload).ifBlank { "没有权限或命令不可用" }}",
                    fallback = true,
                )

                else -> Unit
            }
        }

        val decoder = if (v2) ShellV2Decoder() else null
        // legacy 通道不返回退出码，流正常关闭即视为成功（0）；v2 等设备给 EXIT
        var exit = if (v2) -1 else 0

        fun consume(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            if (recordRaw && raw.size() < 96) {
                val room = 96 - raw.size()
                raw.write(chunk, 0, minOf(room, chunk.size))
            }
            if (decoder == null) {
                onStdout(chunk)
                return
            }
            for (packet in decoder.feed(chunk)) {
                when (packet.type) {
                    ShellV2.STDOUT -> onStdout(packet.data)
                    ShellV2.STDERR -> onStderr(packet.data)
                    ShellV2.EXIT -> exit = packet.data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                    else -> Unit
                }
            }
        }

        for (chunk in early) consume(chunk)
        if (early.isNotEmpty()) reply(AdbMessage(AdbCommand.OKAY, localId, remoteId))

        var closed = false
        while (!closed) {
            val message = readOrTimeout(inp, deadline)
            when (message.command) {
                AdbCommand.WRTE -> {
                    consume(message.payload)
                    // 每个 WRTE 都要确认，否则服务端窗口不释放，大输出会卡住
                    reply(AdbMessage(AdbCommand.OKAY, localId, remoteId))
                }

                AdbCommand.CLSE -> {
                    reply(AdbMessage(AdbCommand.CLSE, localId, remoteId))
                    closed = true
                }

                AdbCommand.OKAY -> Unit
                else -> Unit
            }
        }
        return exit
    }

    /**
     * 按**总截止时间**读，而不是每条消息重置一次超时。
     *
     * 命令可能长时间没有输出（轮询、等待某个服务起来），按消息设超时会让
     * 一个本该 60 秒结束的命令活到 60 分钟 —— 每条心跳都把表拨回去。
     */
    private fun readOrTimeout(inp: InputStream, deadline: Long): AdbMessage {
        val remain = deadline - System.currentTimeMillis()
        if (remain <= 0) throw AdbException("命令执行超时")
        socket?.soTimeout = remain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return try {
            readMessage(inp)
        } catch (e: SocketTimeoutException) {
            throw AdbException("命令执行超时", e)
        }
    }

    private fun reply(message: AdbMessage) {
        output?.let { writeMessage(it, message) }
    }

    private fun textOf(payload: ByteArray): String =
        payload.toString(Charsets.UTF_8).trim('\u0000', '\n', ' ')

    override fun close() {
        runCatching { output?.flush() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun describeConnect(e: Exception): String = when {
        e is SocketTimeoutException -> "连接 $endpoint 超时。请确认手机与手表在同一网络，端口已开放。"
        else -> "无法连接 $endpoint：${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        /** CNXN 协商的载荷上限，设备给的更小时以设备为准 */
    }
}
