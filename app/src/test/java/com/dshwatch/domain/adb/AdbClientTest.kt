package com.dshwatch.domain.adb

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.security.Signature
import java.util.concurrent.atomic.AtomicReference

/**
 * 用一个假 adbd 验证握手与执行流程。
 *
 * 真机测试没法在 CI 里跑，但协议本身是确定的：服务端发什么、客户端该回什么，
 * 都能在本地搭出来。这一层挡住的是「字段顺序写错」「忘了回 OKAY」这类问题 ——
 * 它们在真机上只表现为连接卡住，不会给任何提示。
 */
class AdbClientTest {

    @Test
    fun `完成认证并读回标准输出与退出码`() {
        AdbKey.forget()
        val dir = Files.createTempDirectory("adb-client-test").toFile()
        val pair = AdbKey.loadOrCreate(dir)

        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable?>(null)
        val publicKeyText = AtomicReference<String>("")

        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()

                    // 1. CNXN
                    val cnxn = readMessage(input)
                    check(cnxn.command == AdbCommand.CNXN) { "首包应为 CNXN，实际 ${cnxn.command}" }

                    // 2. AUTH token
                    val token = ByteArray(20) { (it + 1).toByte() }
                    writeMessage(output, AdbMessage(AdbCommand.AUTH, AUTH_TOKEN, 0, token))

                    // 3. 客户端签名
                    val signed = readMessage(input)
                    check(signed.command == AdbCommand.AUTH && signed.arg0 == AUTH_SIGNATURE) {
                        "第二包应为签名，实际 ${signed.command}/${signed.arg0}"
                    }
                    val verifier = Signature.getInstance("SHA1withRSA")
                    verifier.initVerify(pair.public)
                    verifier.update(token)
                    check(verifier.verify(signed.payload)) { "签名验证失败" }

                    // 4. 再来一次 token，逼客户端发公钥
                    writeMessage(output, AdbMessage(AdbCommand.AUTH, AUTH_TOKEN, 0, token))
                    val publicKey = readMessage(input)
                    check(publicKey.command == AdbCommand.AUTH && publicKey.arg0 == AUTH_PUBLIC_KEY)
                    publicKeyText.set(publicKey.payload.toString(Charsets.UTF_8).trim('\u0000'))

                    // 5. 认证通过
                    writeMessage(output, AdbMessage(AdbCommand.CNXN, 0x01000000, 256 * 1024))

                    // 6. OPEN
                    val open = readMessage(input)
                    check(open.command == AdbCommand.OPEN) { "应打开数据流，实际 ${open.command}" }

                    // 7. OKAY + 输出 + 退出码
                    writeMessage(output, AdbMessage(AdbCommand.OKAY, 7, 1))
                    writeMessage(
                        output,
                        AdbMessage(AdbCommand.WRTE, 7, 1, v2(1, ShellV2.STDOUT, "ok\n".toByteArray())),
                    )
                    writeMessage(
                        output,
                        AdbMessage(AdbCommand.WRTE, 7, 1, v2(1, ShellV2.EXIT, byteArrayOf(0))),
                    )
                    // 等客户端确认，再关闭
                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.CLSE, 7, 1))
                    runCatching { readMessage(input) }
                }
            }.onFailure { failure.set(it) }
        }.apply { isDaemon = true; start() }

        val client = AdbClient("127.0.0.1", server.localPort, pair)
        val out = StringBuilder()
        try {
            client.connect(connectTimeoutMs = 3_000, authTimeoutMs = 5_000)
            val code = client.exec("echo ok", timeoutMs = 5_000, onStdout = { out.append(it.toString(Charsets.UTF_8)) })
            assertThat(code).isEqualTo(0)
            assertThat(out.toString()).isEqualTo("ok\n")
        } finally {
            client.close()
            server.close()
        }

        thread.join(3_000)
        assertThat(failure.get()).isNull()
        assertThat(publicKeyText.get()).endsWith(" dsh@watch")
    }

    @Test
    fun `设备拒绝打开数据流时抛出可读错误`() {
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(Files.createTempDirectory("adb-reject").toFile())
        val server = ServerSocket(0)

        Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output: OutputStream = socket.getOutputStream()
                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.CNXN, 0x01000000, 256 * 1024))
                    // 两种 v2/raw 形式被拒后客户端还会试 shell:，三级都拒掉
                    repeat(3) {
                        readMessage(input)
                        writeMessage(output, AdbMessage(AdbCommand.CLSE, 0, 0, "closed by peer\u0000".toByteArray()))
                    }
                    Thread.sleep(200)
                }
            }
        }.apply { isDaemon = true; start() }

        val client = AdbClient("127.0.0.1", server.localPort, pair)
        try {
            client.connect(connectTimeoutMs = 3_000, authTimeoutMs = 3_000)
            val error = runCatching { client.exec("ls", timeoutMs = 3_000) }.exceptionOrNull()
            assertThat(error).isInstanceOf(AdbException::class.java)
            assertThat(error!!.message).contains("设备拒绝执行")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `设备不支持 v2 时自动降级到 legacy 通道`() {
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(Files.createTempDirectory("adb-legacy").toFile())
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable?>(null)
        val secondService = AtomicReference("")

        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()

                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.CNXN, 0x01000000, 256 * 1024))

                    val openV2 = readMessage(input)
                    check(openV2.command == AdbCommand.OPEN) { "首包应为 OPEN" }
                    writeMessage(output, AdbMessage(AdbCommand.OKAY, 7, 1))
                    // 小端与大端都超限的头部：客户端应判定格式不符并降级
                    writeMessage(
                        output,
                        AdbMessage(AdbCommand.WRTE, 7, 1, byteArrayOf(1, 1, -1, -1, -1, -1)),
                    )

                    // 跳过可能的 CLSE，等第二条 OPEN
                    var next = readMessage(input)
                    var guard = 0
                    while (next.command != AdbCommand.OPEN && guard++ < 8) next = readMessage(input)
                    secondService.set(next.payload.toString(Charsets.UTF_8).trim('\u0000'))

                    writeMessage(output, AdbMessage(AdbCommand.OKAY, 8, 1))
                    writeMessage(output, AdbMessage(AdbCommand.WRTE, 8, 1, "ok\n".toByteArray()))
                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.CLSE, 8, 1))
                    runCatching { readMessage(input) }
                }
            }.onFailure { failure.set(it) }
        }.apply { isDaemon = true; start() }

        val client = AdbClient("127.0.0.1", server.localPort, pair)
        val out = StringBuilder()
        try {
            client.connect(connectTimeoutMs = 3_000, authTimeoutMs = 5_000)
            val code = client.exec(
                "echo ok",
                timeoutMs = 5_000,
                onStdout = { out.append(it.toString(Charsets.UTF_8)) },
            )
            assertThat(out.toString()).isEqualTo("ok\n")
            // legacy 不返回退出码，流正常关闭即视为成功
            assertThat(code).isEqualTo(0)
        } finally {
            client.close()
            server.close()
        }

        thread.join(3_000)
        assertThat(failure.get()).isNull()
        assertThat(secondService.get()).startsWith("shell,raw:")
    }

    @Test
    fun `v2 打开成功却没有数据时继续尝试下一种服务形式`() {
        /*
         * 真机现场：OPEN 拿到 OKAY，随后立刻 CLSE，一个字节都没有，
         * 退出码保持 -1。这种「空流」不能当成命令结果交出去。
         */
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(Files.createTempDirectory("adb-empty").toFile())
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable?>(null)
        val services = ArrayList<String>()

        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()

                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.CNXN, 0x01000000, 256 * 1024))

                    val first = readMessage(input)
                    services += first.payload.toString(Charsets.UTF_8).trim('\u0000')
                    writeMessage(output, AdbMessage(AdbCommand.OKAY, 7, 1))
                    writeMessage(output, AdbMessage(AdbCommand.CLSE, 7, 1))

                    // 客户端会先回一条 CLSE 确认旧流，跳过它再等新的 OPEN
                    var second = readMessage(input)
                    var guard = 0
                    while (second.command != AdbCommand.OPEN && guard++ < 8) second = readMessage(input)
                    services += second.payload.toString(Charsets.UTF_8).trim('\u0000')
                    writeMessage(output, AdbMessage(AdbCommand.OKAY, 8, 2))
                    writeMessage(output, AdbMessage(AdbCommand.WRTE, 8, 2, "ok\n".toByteArray()))
                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.CLSE, 8, 2))
                    runCatching { readMessage(input) }
                }
            }.onFailure { failure.set(it) }
        }.apply { isDaemon = true; start() }

        val client = AdbClient("127.0.0.1", server.localPort, pair)
        val out = StringBuilder()
        try {
            client.connect(connectTimeoutMs = 3_000, authTimeoutMs = 5_000)
            val code = client.exec(
                "echo ok",
                timeoutMs = 5_000,
                onStdout = { out.append(it.toString(Charsets.UTF_8)) },
            )
            assertThat(out.toString()).isEqualTo("ok\n")
            assertThat(code).isEqualTo(0)
        } finally {
            client.close()
            server.close()
        }

        thread.join(3_000)
        assertThat(failure.get()).isNull()
        assertThat(services).hasSize(2)
        assertThat(services[0]).startsWith("shell,v2,raw:")
        assertThat(services[1]).startsWith("shell,raw:")
    }

    @Test
    fun `有输出但没有 EXIT 子包时按成功处理`() {
        /*
         * 部分 adbd 在命令结束时只发 CLSE，不发 EXIT 子包。
         * 输出已经在手，把它判成失败会让模型以为命令挂了。
         */
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(Files.createTempDirectory("adb-noexit").toFile())
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable?>(null)

        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.CNXN, 0x01000000, 256 * 1024))
                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.OKAY, 7, 1))
                    writeMessage(output, AdbMessage(AdbCommand.WRTE, 7, 1, v2(1, ShellV2.STDOUT, "done\n".toByteArray())))
                    readMessage(input)
                    writeMessage(output, AdbMessage(AdbCommand.CLSE, 7, 1))
                    runCatching { readMessage(input) }
                }
            }.onFailure { failure.set(it) }
        }.apply { isDaemon = true; start() }

        val client = AdbClient("127.0.0.1", server.localPort, pair)
        val out = StringBuilder()
        try {
            client.connect(connectTimeoutMs = 3_000, authTimeoutMs = 5_000)
            val code = client.exec("true", timeoutMs = 5_000, onStdout = { out.append(it.toString(Charsets.UTF_8)) })
            assertThat(out.toString()).isEqualTo("done\n")
            assertThat(code).isEqualTo(0)
        } finally {
            client.close()
            server.close()
        }

        thread.join(3_000)
        assertThat(failure.get()).isNull()
    }

    private fun v2(id: Int, type: Int, data: ByteArray): ByteArray {
        val out = ByteArray(6 + data.size)
        out[0] = id.toByte()
        out[1] = type.toByte()
        putIntLe(out, 2, data.size)
        data.copyInto(out, 6)
        return out
    }
}
