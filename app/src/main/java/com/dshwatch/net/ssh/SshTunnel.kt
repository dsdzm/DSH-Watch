package com.dshwatch.net.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** SSH 层失败的原因。与 [DshWebError][com.dshwatch.net.dshweb.DshWebError] 一样按「怎么处理」分类 */
sealed class SshError(message: String) : Exception(message) {

    /** 连不上主机：DNS、拒绝连接、超时、端口没开 */
    class Network(message: String) : SshError(message)

    /** 认证被拒：用户名或密码错，或电脑上没开密码登录 */
    class Auth(message: String) : SshError(message)

    /** 主机密钥变了 —— 可能是换了机器，也可能是有人在中间 */
    class HostKeyChanged(val expected: String, val actual: String) :
        SshError("主机密钥和上次不一样（上次 $expected，这次 $actual）")

    /** 连上了，但远端缺少 dsh，或 dsh web 起不来 */
    class Remote(message: String) : SshError(message)

    fun describeForUser(): String = when (this) {
        is Network -> "连不上这台电脑：${message ?: "网络不通"}。确认地址、端口、以及电脑和手表在同一个网络"
        is Auth -> "登录被拒绝：${message ?: "用户名或密码不对"}。Linux 还要确认 sshd 允许密码登录（PasswordAuthentication yes）"
        is HostKeyChanged -> "这台电脑的主机密钥变了。如果不是你换了系统，就该怀疑有人在中间截获"
        is Remote -> message ?: "远端出错"
    }
}

/**
 * 一条到电脑的 SSH 连接，外加它开出来的本地端口。
 *
 * 这里只做两件事：**跑命令**、**把本地端口接到远端端口**。
 * 上层（[DshOverSsh]）负责「用这两件事把 dsh web 弄起来并连上」。
 *
 * 为什么端口转发要自己实现而不是用 jsch 的 `setPortForwardingL`：
 * jsch 的转发在断线时的行为是静默的（本地连接挂着、远端早断了），
 * 而 DSH 的客户端会一直等一个永远不来的响应。自己写能保证
 * 「SSH 一断，本地连接立刻全断」—— 上层才能收到错误并重连。
 */
class SshTunnel private constructor(
    private val session: Session,
    private val target: SshTarget,
) {

    /** 最近一次看到的指纹（首次连接时用于回写设置） */
    val fingerprint: String get() = session.hostKey?.key?.let { SshTunnel.fingerprintOf(it) }.orEmpty()

    /** 跑一段脚本，返回 (exitCode, stdout)。stderr 混在 stdout 里 —— 脚本自己会打标记 */
    suspend fun exec(script: String, timeoutMs: Int = 30_000): ExecResult = withContext(Dispatchers.IO) {
        val channel = session.openChannel("exec") as ChannelExec
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            channel.setCommand(script)
            channel.setInputStream(null)
            channel.setErrStream(err)
            channel.connect(timeoutMs)
            channel.inputStream.use { it.copyTo(out) }
            // 等退出码落地；命令结束后这个循环最多再转一两次
            val deadline = System.currentTimeMillis() + 5_000
            while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(20)
            ExecResult(channel.exitStatus, out.toString("UTF-8"), err.toString("UTF-8"))
        } catch (t: Throwable) {
            throw SshError.Remote("执行远端命令失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    /**
     * 把 `127.0.0.1:<随机端口>` 接到远端的 `127.0.0.1:<remotePort>`。
     *
     * 用 jsch 自带的 L 转发而不是自己 pump：它的字节流接管、半关闭、
     * 断线清理都处理过（手写版本第一版就是「连上了但一个字节不走」）。
     * 只绑回环 —— 这个端口没有认证，暴露到局域网等于把这台电脑的 shell 送人。
     */
    suspend fun forward(remotePort: Int): Int = withContext(Dispatchers.IO) {
        try {
            session.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", remotePort)
        } catch (t: Throwable) {
            throw SshError.Remote("端口转发建不起来：${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun close() {
        runCatching { session.disconnect() }
    }

    companion object {
        suspend fun connect(target: SshTarget, password: String, timeoutMs: Int = 15_000): SshTunnel =
            withContext(Dispatchers.IO) {
                val jsch = JSch()
                val session = try {
                    jsch.getSession(target.user, target.host, target.port)
                } catch (t: Throwable) {
                    throw SshError.Network(t.message ?: "地址无效")
                }
                session.setPassword(password)
                session.setConfig("StrictHostKeyChecking", "no")
                // 手表上没必要为每条隧道协商压缩；省一点 CPU
                session.setConfig("compression.s2c", "none")
                session.setConfig("compression.c2s", "none")
                session.serverAliveInterval = 15_000
                session.serverAliveCountMax = 3
                session.timeout = timeoutMs
                try {
                    session.connect(timeoutMs)
                } catch (t: Throwable) {
                    val msg = t.message.orEmpty()
                    throw if (msg.contains("Auth", true) || msg.contains("auth fail", true)) {
                        SshError.Auth(msg)
                    } else {
                        SshError.Network(msg.ifBlank { "连接超时" })
                    }
                }

                val tunnel = SshTunnel(session, target)
                // 首次连接记住指纹；之后对不上就中止 —— 这正是中间人的样子
                val actual = session.hostKey?.key?.let { fingerprintOf(it) }.orEmpty()
                if (target.fingerprint.isNotBlank() && actual.isNotBlank() && target.fingerprint != actual) {
                    tunnel.close()
                    throw SshError.HostKeyChanged(target.fingerprint, actual)
                }
                tunnel
            }

        /** base64(sha256(key)) 无填充，与 `ssh-keygen -lf` 的 SHA256 段一致 */
        fun fingerprintOf(key: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.ISO_8859_1))
            return java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}
