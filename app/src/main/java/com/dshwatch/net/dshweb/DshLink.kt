package com.dshwatch.net.dshweb

import com.dshwatch.core.Logx
import com.dshwatch.net.remote.DshApproval
import com.dshwatch.net.remote.RemoteCredentials
import com.dshwatch.net.remote.RemoteError
import com.dshwatch.net.remote.RemoteLink
import com.dshwatch.net.remote.fromBase64Url
import com.dshwatch.net.remote.str
import com.dshwatch.net.ssh.DshOverSsh
import com.dshwatch.net.ssh.DshSshLink
import com.dshwatch.net.ssh.SshError
import com.dshwatch.net.ssh.SshTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「当前连着的那台电脑」。
 *
 * ## 两条路，一个出口
 *
 * 连电脑有两条完全不同的链路：
 *
 *  - **SSH**：登进去起 `dsh web`，把端口转发回本机，然后说 dsh web 的 HTTP/WS；
 *    只要有台能登的机器就能用，不需要账号。
 *  - **DSH Remote**：Noise 隧道到中继，靠账号与设备配对；不用开 sshd、不用在同一个网。
 *
 * 上层（聊天、诊断、会话列表）只认这个类。所以两条路在这里收敛成同一组方法，
 * 而不是让每个调用点自己 `if (remote) ... else ...` —— 那种分支一旦漏一处，
 * 表现就是"换了个连接方式，聊天页就空白了"。
 *
 * ## 为什么不自动重连
 *
 * 自动重连需要判断「是网络抖了，还是用户把电脑关了」。前者重连有意义，
 * 后者只会让界面卡在「正在连接」上一直转圈。这一版让用户自己点连接，
 * 至少每一次失败都能对应到一次明确的动作。
 */
class DshLink {

    /** 现在连的是哪条路 */
    enum class Mode { NONE, SSH, REMOTE }

    private val client = DshWebClient()
    private val connector = DshOverSsh(client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var link: DshSshLink? = null

    /** 当前隧道对应的目标；用于「同一个目标就不重连」 */
    @Volatile
    private var currentTarget: SshTarget? = null

    @Volatile
    private var remote: RemoteLink? = null

    /** SSH 那条路上的 `$events`：审批与回答都挂在它上面 */
    @Volatile
    private var eventsMux: DshMux? = null
    private var eventsJob: Job? = null

    private val _approvals = MutableStateFlow<List<DshApproval>>(emptyList())
    val approvals: StateFlow<List<DshApproval>> = _approvals.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    val mode: Mode
        get() = when {
            remote != null -> Mode.REMOTE
            link != null -> Mode.SSH
            else -> Mode.NONE
        }

    /** 已登录的主机；null 表示当前没连上 */
    fun host(): DshWebHost? = link?.host

    val connected: Boolean get() = link != null || remote?.connected == true

    /** 连上时远端 `dsh web` 的端口，用于界面上显示「连的是哪个实例」；Remote 没有端口 */
    val remotePort: Int? get() = link?.remotePort

    /** 界面上说清楚"这次是怎么连上的" */
    fun describePath(): String = when (mode) {
        Mode.SSH -> "SSH 隧道 · 远端 127.0.0.1:${remotePort ?: "?"}"
        Mode.REMOTE -> "DSH Remote · ${remote?.home?.takeIf { it.isNotBlank() } ?: "已加密"}"
        Mode.NONE -> "未连接"
    }

    // ---------------------------------------------------------------- SSH

    suspend fun connect(
        target: SshTarget,
        password: String,
        onStep: (com.dshwatch.net.ssh.DshSshStep, String) -> Unit = { _, _ -> },
    ): DshSshLink = withContext(Dispatchers.IO) {
        close()
        lastError = null
        try {
            val fresh = connector.connect(target, password, onStep)
            link = fresh
            currentTarget = target
            Logx.i("dsh", "已连接 ${target.label}，远端端口 ${fresh.remotePort}")
            watchApprovals(fresh.host)
            fresh
        } catch (t: Throwable) {
            lastError = describe(t)
            throw t
        }
    }

    // ---------------------------------------------------------------- Remote

    /**
     * 走 DSH Remote 隧道。
     *
     * @param identityKey 本机身份私钥（base64url），配对时生成、之后一直用它
     * @param onTokens 访问令牌轮换后的回调 —— 不写回密钥库，下次冷启动就是 401
     */
    suspend fun connectRemote(
        credentials: RemoteCredentials,
        identityKey: String,
        clientVersion: String,
        json: Json,
        onTokens: (access: String, refresh: String) -> Unit = { _, _ -> },
        onStep: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        close()
        lastError = null
        val key = runCatching { identityKey.fromBase64Url() }.getOrNull()
            ?: throw RemoteError.Protocol("本机身份密钥丢失，需要重新配对")
        val session = RemoteLink(credentials, key, clientVersion, json, onTokens)
        try {
            session.connect(onStep)
            remote = session
            _approvals.value = emptyList()
            scope.launch {
                session.approvals.collect { _approvals.value = it }
            }
            Logx.i("dsh", "Remote 隧道已就绪：${credentials.server}")
        } catch (t: Throwable) {
            lastError = describe(t)
            runCatching { session.close() }
            throw t
        }
    }

    // ---------------------------------------------------------------- 关闭

    fun close() {
        runCatching { eventsJob?.cancel() }
        eventsJob = null
        eventsMux = null
        runCatching { link?.close() }
        link = null
        currentTarget = null
        runCatching { remote?.close() }
        remote = null
        _approvals.value = emptyList()
    }

    private fun describe(t: Throwable): String = when (t) {
        is SshError -> t.describeForUser()
        is DshWebError -> t.describeForUser()
        is RemoteError -> t.message ?: "连接失败"
        else -> t.message ?: t.javaClass.simpleName
    }

    // ---------------------------------------------------------------- 会话操作

    suspend fun listSessions(): List<DshSession> {
        remote?.let { return it.listSessions() }
        val host = host() ?: throw DshWebError.Unauthorized("还没连接电脑")
        return client.listSessions(host)
    }

    suspend fun createSession(): String {
        remote?.let { return it.createSession() }
        val host = host() ?: throw DshWebError.Unauthorized("还没连接电脑")
        return client.createSession(host)
    }

    suspend fun prompt(sessionId: String, text: String): String {
        remote?.let { return it.prompt(sessionId, text) }
        val host = host() ?: throw DshWebError.Unauthorized("还没连接电脑")
        return client.prompt(host, sessionId, text)
    }

    suspend fun cancel(sessionId: String) {
        remote?.let { it.cancel(sessionId); return }
        val host = host() ?: return
        runCatching { client.cancel(host, sessionId) }
    }

    /** 订阅会话流；没连上时返回 null，让调用方给出「先连接」的提示 */
    fun mux(): DshSessionChannel? {
        remote?.let { return RemoteChannel(it) }
        return host()?.let { DshMux(it) }
    }

    fun cancelActive() = client.cancelActive()

    // ---------------------------------------------------------------- 审批

    /**
     * 回答一条审批。
     *
     * SSH 那条路走 HTTP 的 `$events/result`，Remote 走隧道里的同名端点 ——
     * 两者参数一样，只是载体不同。
     */
    suspend fun respondApproval(eventId: String, allow: Boolean) {
        val outcome = buildJsonObject {
            put("kind", "result")
            put("value", if (allow) "allowed-once" else "rejected")
        }
        remote?.let {
            it.respondApproval(eventId, allow)
            return
        }
        val events = eventsMux ?: throw DshWebError.Unauthorized("事件流还没建立")
        val host = host() ?: throw DshWebError.Unauthorized("还没连接电脑")
        val clientId = events.eventsClientId ?: throw DshWebError.Protocol("事件流还没等到 ready，稍后再试")
        client.call(
            host,
            "\$events/result",
            buildJsonObject {
                put("clientId", clientId)
                put("eventId", eventId)
                put("outcome", outcome)
            },
        )
        _approvals.value = _approvals.value.filterNot { it.eventId == eventId }
    }

    private fun watchApprovals(host: DshWebHost) {
        runCatching { eventsJob?.cancel() }
        val mux = DshMux(host)
        eventsMux = mux
        eventsJob = scope.launch {
            try {
                mux.hostEvents().collect { value ->
                    val obj = value as? JsonObject ?: return@collect
                    when (obj.str("type")) {
                        "waterfall" -> {
                            if (obj.str("event") != "approval/request") return@collect
                            val eventId = obj.str("eventId") ?: return@collect
                            val request = obj["request"] as? JsonObject ?: return@collect
                            val toolName = request.str("toolName") ?: "工具"
                            val display = request["displayReason"] as? JsonObject
                            val summary = display?.str("zh") ?: display?.str("en")
                                ?: request.str("reason") ?: "要执行 $toolName"
                            val pending = DshApproval(
                                eventId = eventId,
                                sessionId = obj.str("agentId").orEmpty(),
                                toolName = toolName,
                                summary = summary,
                            )
                            _approvals.value = _approvals.value.filterNot { it.eventId == eventId } + pending
                        }
                        "cancel" -> {
                            val eventId = obj.str("eventId") ?: return@collect
                            _approvals.value = _approvals.value.filterNot { it.eventId == eventId }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) return@launch
                Logx.w("dsh", "SSH 侧的事件流断了：${t.message}")
            }
        }
    }

    /** 把 Remote 的流适配成同一个接口 */
    private class RemoteChannel(private val link: RemoteLink) : DshSessionChannel {
        override fun follow(sessionId: String, maxMessages: Int): Flow<MuxEvent> =
            link.follow(sessionId, maxMessages)
    }
}
