package com.dshwatch.net.dshweb

import com.dshwatch.core.Logx
import com.dshwatch.net.ssh.DshOverSsh
import com.dshwatch.net.ssh.DshSshLink
import com.dshwatch.net.ssh.SshError
import com.dshwatch.net.ssh.SshTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「当前连着的那台电脑」。
 *
 * ## 为什么要有这么个东西
 *
 * 聊天页拿到的只是一份 [com.dshwatch.domain.model.Provider]（含地址、密码），
 * 但真正能发请求需要的是**一条活着的 SSH 隧道 + 一枚 cookie**。
 * 隧道里本地端口是随机的、cookie 是有时效的，它们都不适合塞进设置里持久化。
 *
 * 所以把「连接」这件有状态的事收在这里：连一次，之后聊天都复用它；
 * 手表休眠或网络断了，下次聊天时再连一次即可（[connect] 是幂等的）。
 *
 * ## 为什么不自动重连
 *
 * 自动重连需要判断「是网络抖了，还是用户把电脑关了」。前者重连有意义，
 * 后者只会让界面卡在「正在连接」上一直转圈。这一版让用户自己点连接，
 * 至少每一次失败都能对应到一次明确的动作。
 */
class DshLink {

    private val client = DshWebClient()
    private val connector = DshOverSsh(client)

    @Volatile
    private var link: DshSshLink? = null

    /** 当前隧道对应的目标；用于「同一个目标就不重连」 */
    @Volatile
    private var currentTarget: SshTarget? = null

    @Volatile
    var lastError: String? = null
        private set

    /** 已登录的主机；null 表示当前没连上 */
    fun host(): DshWebHost? = link?.host

    val connected: Boolean get() = link != null

    /** 连上时远端 `dsh web` 的端口，用于界面上显示「连的是哪个实例」 */
    val remotePort: Int? get() = link?.remotePort

    suspend fun connect(
        target: SshTarget,
        password: String,
        onStep: (com.dshwatch.net.ssh.DshSshStep, String) -> Unit = { _, _ -> },
    ): DshSshLink = withContext(Dispatchers.IO) {
        // 目标没变就复用：重新连一次会重建隧道，聊天中途重连等于把这一回合掐断
        link?.let { old -> if (currentTarget == target) return@withContext old }
        close()
        lastError = null
        try {
            val fresh = connector.connect(target, password, onStep)
            link = fresh
            currentTarget = target
            Logx.i("dsh", "已连接 ${target.label}，远端端口 ${fresh.remotePort}")
            fresh
        } catch (t: Throwable) {
            lastError = describe(t)
            throw t
        }
    }

    fun close() {
        runCatching { link?.close() }
        link = null
        currentTarget = null
    }

    private fun describe(t: Throwable): String = when (t) {
        is SshError -> t.describeForUser()
        is DshWebError -> t.describeForUser()
        else -> t.message ?: t.javaClass.simpleName
    }

    // ---------------- 会话操作 ----------------

    suspend fun listSessions(): List<DshSession> {
        val host = host() ?: throw DshWebError.Unauthorized("还没连接电脑")
        return client.listSessions(host)
    }

    suspend fun createSession(): String {
        val host = host() ?: throw DshWebError.Unauthorized("还没连接电脑")
        return client.createSession(host)
    }

    suspend fun prompt(sessionId: String, text: String): String {
        val host = host() ?: throw DshWebError.Unauthorized("还没连接电脑")
        return client.prompt(host, sessionId, text)
    }

    suspend fun cancel(sessionId: String) {
        val host = host() ?: return
        runCatching { client.cancel(host, sessionId) }
    }

    /** 订阅会话流；没连上时返回 null，让调用方给出「先连接」的提示 */
    fun mux(): DshMux? = host()?.let { DshMux(it) }

    fun cancelActive() = client.cancelActive()
}
