package com.dshwatch.net.ssh

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 用真的 sshd 验证客户端。
 *
 * 手写/接线错一个字节，编译照样通过，只有真连一次才知道行不行。
 * 这里在 JVM 里起一个 Apache sshd，跑通三件对这次改动最要紧的事：
 * 密码认证、`exec` 拿输出、`direct-tcpip` 端口转发。
 */
class SshTunnelTest {

    private lateinit var sshd: SshServer
    private var port = 0

    private val target get() = SshTarget(host = "127.0.0.1", port = port, user = "watch")

    @Before
    fun setUp() {
        sshd = SshServer.setUpDefaultServer()
        sshd.host = "127.0.0.1"
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(File.createTempFile("hostkey", ".ser").toPath())
        sshd.passwordAuthenticator = PasswordAuthenticator { user, pass, _ -> user == "watch" && pass == "swordfish" }
        sshd.commandFactory = ProcessShellCommandFactory.INSTANCE
        sshd.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        sshd.start()
        port = sshd.port
    }

    @After
    fun tearDown() {
        runCatching { sshd.stop(true) }
    }

    @Test
    fun `密码对了就能连上，exec 能拿到 stdout`() = runBlocking {
        val tunnel = SshTunnel.connect(target, "swordfish")
        try {
            val r = tunnel.exec("echo DSHW url http://127.0.0.1:3080/?token=abc")
            assertThat(r.exitCode).isEqualTo(0)
            assertThat(r.stdout).contains("DSHW url http://127.0.0.1:3080/?token=abc")
        } finally {
            tunnel.close()
        }
    }

    @Test
    fun `密码错了报的是认证失败，不是网络错误`() = runBlocking {
        val error = runCatching { SshTunnel.connect(target, "wrong") }.exceptionOrNull()
        assertThat(error).isInstanceOf(SshError.Auth::class.java)
    }

    @Test
    fun `端口转发真的把字节送到了远端服务`() = runBlocking {
        // 远端服务：收到什么就回什么，并加一个前缀
        val echo = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        Thread({
            while (!echo.isClosed) {
                val c = runCatching { echo.accept() }.getOrNull() ?: break
                Thread({
                    c.getOutputStream().write("pong:".toByteArray())
                    c.getOutputStream().flush()
                    c.close()
                }).apply { isDaemon = true }.start()
            }
        }, "echo").apply { isDaemon = true }.start()

        val tunnel = SshTunnel.connect(target, "swordfish")
        try {
            val localPort = tunnel.forward(echo.localPort)
            assertThat(localPort).isGreaterThan(0)
            Socket("127.0.0.1", localPort).use { s ->
                s.soTimeout = 5_000
                val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertThat(text).isEqualTo("pong:")
            }
        } finally {
            tunnel.close()
            echo.close()
        }
    }

    @Test
    fun `主机密钥变了要中止`() = runBlocking {
        val first = SshTunnel.connect(target, "swordfish")
        val fp = first.fingerprint
        first.close()
        assertThat(fp).isNotEmpty()

        // 换一台「别的机器」：新 sshd、新密钥
        val other = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File.createTempFile("other", ".ser").toPath())
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == "watch" && p == "swordfish" }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        other.start()
        try {
            val error = runCatching {
                SshTunnel.connect(
                    SshTarget(host = "127.0.0.1", port = other.port, user = "watch", fingerprint = fp),
                    "swordfish",
                )
            }.exceptionOrNull()
            assertThat(error).isInstanceOf(SshError.HostKeyChanged::class.java)
        } finally {
            other.stop(true)
        }
    }
}
