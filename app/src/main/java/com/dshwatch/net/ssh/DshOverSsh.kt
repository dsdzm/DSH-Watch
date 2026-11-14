package com.dshwatch.net.ssh

import com.dshwatch.core.Logx
import com.dshwatch.net.dshweb.DshWeb
import com.dshwatch.net.dshweb.DshWebClient
import com.dshwatch.net.dshweb.DshWebError
import com.dshwatch.net.dshweb.DshWebHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 连接链上的四段。每一段失败的原因与处理方式都不同，所以要分开报 */
enum class DshSshStep {
    /** 建立 SSH 连接（含主机密钥校验） */
    SSH,

    /** 在远端启动 `dsh web` 并拿到启动地址 */
    LAUNCH,

    /** 把本地端口接到远端回环端口 */
    TUNNEL,

    /** 用启动令牌换 cookie */
    LOGIN,
}

/**
 * 「SSH 进去 → 起 dsh web → 端口转发 → 登录」这一整条链。
 *
 * 这套流程照抄 dshgo 的 `remote/ssh`：SSH 只当管道，上层协议仍然是 dsh web 的
 * HTTP + WebSocket。好处是**不需要中继、不需要账号、不需要摄像头**，
 * 而这三样在手表上都刚好缺一样。
 *
 * 用完后必须 [DshSshLink.close]，否则远端会留一个转发端口和一条 SSH 连接。
 */
class DshOverSsh(private val client: DshWebClient = DshWebClient()) {

    suspend fun connect(
        target: SshTarget,
        password: String,
        onStep: (DshSshStep, String) -> Unit = { _, _ -> },
    ): DshSshLink = withContext(Dispatchers.IO) {
        if (!target.configured) throw SshError.Network("还没填主机和用户名")

        onStep(DshSshStep.SSH, "正在连接 ${target.label}")
        val tunnel = SshTunnel.connect(target, password)
        Logx.i("ssh", "SSH 已连接 ${target.label}，指纹 ${tunnel.fingerprint.take(16)}…")

        try {
            onStep(DshSshStep.LAUNCH, "正在启动电脑上的 dsh web")
            val result = RemoteDsh.parse(tunnel.exec(RemoteDsh.startScript(target), timeoutMs = 45_000).stdout)
            val started = when (result) {
                is RemoteDshResult.MissingDsh -> throw SshError.Remote(
                    "电脑上找不到 dsh 命令。先装 DSH，或把「启动命令」改成它的绝对路径"
                )
                is RemoteDshResult.Failed -> throw SshError.Remote(result.describe())
                is RemoteDshResult.Started -> result
            }
            val launch = DshWeb.parseLaunchUrl(started.launchUrl)
                ?: throw SshError.Remote("dsh web 打印的地址里没有 token：${started.launchUrl.take(80)}")
            val remotePort = started.launchUrl.substringAfter("127.0.0.1:").substringBefore('/').substringBefore('?').toIntOrNull()
                ?: launch.host.authority.substringAfterLast(':').toIntOrNull()
                ?: throw SshError.Remote("读不出 dsh web 监听的端口：${started.launchUrl.take(80)}")

            onStep(DshSshStep.TUNNEL, "正在建立端口转发（127.0.0.1:$remotePort）")
            val localPort = tunnel.forward(remotePort)

            // 本机连本地端口，但告诉服务端「我是那台 127.0.0.1:remotePort」
            val host = DshWebHost(
                baseUrl = "http://127.0.0.1:$localPort",
                authority = "127.0.0.1:$remotePort",
                hostHeader = "127.0.0.1:$remotePort",
            )

            onStep(DshSshStep.LOGIN, "正在登录（换取会话 cookie）")
            val logged = try {
                client.login(host, launch.token)
            } catch (e: DshWebError) {
                throw SshError.Remote("隧道通了，但 dsh web 不认这个令牌：${e.describeForUser()}")
            }

            DshSshLink(
                tunnel = tunnel,
                host = logged,
                remotePort = remotePort,
                reused = started.reused,
                fingerprint = tunnel.fingerprint,
            )
        } catch (t: Throwable) {
            runCatching { tunnel.close() }
            throw t
        }
    }

    /** 只做「能不能连上、有没有 dsh」，不起服务、不留进程 */
    suspend fun probe(target: SshTarget, password: String): ExecResult = withContext(Dispatchers.IO) {
        val tunnel = SshTunnel.connect(target, password)
        try {
            tunnel.exec(RemoteDsh.probeScript(target), timeoutMs = 15_000)
        } finally {
            tunnel.close()
        }
    }
}

/** 一条活的 SSH → dsh web 链路 */
class DshSshLink(
    private val tunnel: SshTunnel,
    val host: DshWebHost,
    val remotePort: Int,
    /** 复用了电脑上已经在跑的那个 dsh web（历史会话还在） */
    val reused: Boolean,
    val fingerprint: String,
) {
    val alive: Boolean get() = true

    fun close() {
        runCatching { tunnel.close() }
    }
}
