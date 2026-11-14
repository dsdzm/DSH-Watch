package com.dshwatch.net.remote

import com.dshwatch.core.Logx
import com.dshwatch.net.dshweb.DshSession
import com.dshwatch.net.dshweb.MuxEvent
import com.dshwatch.net.dshweb.MuxWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 连一台 Host 需要的东西。都是配对时落库的那些，不在这里重新推导 */
data class RemoteCredentials(
    val server: String,
    val deviceId: String,
    val hostDeviceId: String,
    /** Host 身份公钥（base64url）。空表示还没 pin，不能连 */
    val hostIdentityKey: String,
    val accessToken: String,
    val refreshToken: String,
)

/** 一条待回答的审批 */
data class DshApproval(
    val eventId: String,
    val sessionId: String,
    val toolName: String,
    val summary: String,
)

/**
 * DSH Remote 上的「会话」。
 *
 * 这一层只做三件事：把 dsh web 的 endpoint 通过 `harness.remote.call` 调过去、
 * 把 `session/follow` 的帧还原成和 SSH 那条路一样的 [MuxEvent]、
 * 把 `$events` 上的审批瀑布收成界面能直接画的一张卡片。
 *
 * ## 为什么审批要单独挂在 `$events`
 *
 * 审批不是"请求-响应"，而是**广播瀑布**：同一个 `eventId` 会推给所有连着的客户端，
 * 谁先答谁算数，答完给其他人推一条 `cancel`。所以它不能跟着某条 `session/follow`
 * 走 —— 那条流断了审批就没了。`$events` 是连接级的，正好是它的载体。
 */
class RemoteLink(
    private var credentials: RemoteCredentials,
    private val identityKey: ByteArray,
    private val clientVersion: String,
    private val json: Json,
    /** 刷新令牌后把新的两枚写回密钥库；不写回去下次冷启动就是 401 */
    private val onTokens: (access: String, refresh: String) -> Unit = { _, _ -> },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tunnel: RemoteTunnel? = null

    private val _approvals = MutableStateFlow<List<DshApproval>>(emptyList())
    val approvals: StateFlow<List<DshApproval>> = _approvals.asStateFlow()

    @Volatile
    var home: String = ""
        private set

    val connected: Boolean get() = tunnel?.phase?.value == RemoteTunnel.Phase.READY

    suspend fun connect(onStep: (String) -> Unit = {}) {
        if (connected) return
        onStep("正在连接 ${credentials.server}")
        val fresh = try {
            openTunnel()
        } catch (e: RemoteError.Remote) {
            throw RemoteError.Remote(e.errorCode, humanize(e.errorCode, e.message ?: e.errorCode))
        }
        tunnel = fresh
        home = fresh.home
        scope.launch { collectApprovals(fresh) }
    }

    /**
     * 建隧道；访问令牌过期时刷新一次再重试。
     *
     * 只重试一次：刷新令牌本身也可能失效（被吊销、换设备），
     * 那时候再多试几次只会让用户在"正在连接"上多等半分钟。
     */
    private suspend fun openTunnel(): RemoteTunnel {
        val first = build(credentials.accessToken)
        try {
            first.connect()
            return first
        } catch (e: RemoteError.Remote) {
            if (e.errorCode !in AUTH_CODES) throw e
            Logx.i("remote", "访问令牌失效（${e.errorCode}），刷新一次")
            val refreshed = refreshTokens()
            val second = build(refreshed)
            second.connect()
            return second
        }
    }

    private fun build(access: String): RemoteTunnel {
        val key = runCatching { credentials.hostIdentityKey.fromBase64Url() }.getOrNull()
        if (key == null || key.size != 32) {
            throw RemoteError.Protocol("这台电脑的身份公钥缺失或损坏，需要重新配对")
        }
        return RemoteTunnel(
            server = credentials.server,
            deviceId = credentials.deviceId,
            accessToken = access,
            hostDeviceId = credentials.hostDeviceId,
            identityPrivateKey = identityKey,
            hostIdentityKey = key,
            clientVersion = clientVersion,
            json = json,
        )
    }

    private suspend fun refreshTokens(): String {
        val refresh = credentials.refreshToken
        if (refresh.isBlank()) throw RemoteError.Unauthorized()
        val tokens = RemoteServer(credentials.server).refresh(credentials.deviceId, refresh)
        credentials = credentials.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
        )
        onTokens(tokens.accessToken, tokens.refreshToken)
        return tokens.accessToken
    }

    // ---------------------------------------------------------------- 会话

    suspend fun listSessions(): List<DshSession> {
        val value = call("session/list", buildJsonObject { put("_request", JsonObject(emptyMap())) })
        return DshSessionList.parse(value)
    }

    suspend fun createSession(): String {
        val value = call("session/create", buildJsonObject { put("request", JsonObject(emptyMap())) })
        val id = (value as? JsonObject)?.str("sessionId")?.takeIf { it.isNotBlank() }
            ?: throw RemoteError.Protocol("session/create 没有返回 sessionId")
        return id
    }

    suspend fun prompt(sessionId: String, text: String): String {
        if (text.isBlank()) throw RemoteError.Remote("bad-request", "消息不能为空")
        val requestId = java.util.UUID.randomUUID().toString()
        call(
            "session/prompt",
            buildJsonObject {
                put(
                    "request",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("sessionId", sessionId)
                        put("mode", "queue")
                        put(
                            "content",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    }
                                )
                            ),
                        )
                    },
                )
            },
        )
        return requestId
    }

    suspend fun cancel(sessionId: String) {
        runCatching {
            call("session/cancel", buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) })
        }
    }

    /** 订阅一个会话；帧格式与 dsh web 的 mux 相同，所以解析层能直接复用 */
    fun follow(sessionId: String, maxMessages: Int = 60): Flow<MuxEvent> {
        val t = tunnel ?: throw RemoteError.Closed("还没连接电脑")
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("address", buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                })
                put("assistantStream", true)
                put("maxMessages", maxMessages)
            })
        }
        return t.openStream("session/follow", args).mapNotNull { MuxWire.parseFollow(it) }
    }

    private suspend fun call(endpoint: String, args: JsonObject): JsonElement {
        val t = tunnel ?: throw RemoteError.Closed("还没连接电脑")
        return t.call(endpoint, args)
    }

    // ---------------------------------------------------------------- 审批

    private suspend fun collectApprovals(t: RemoteTunnel) {
        try {
            t.hostEvents.collect { value ->
                val obj = value as? JsonObject ?: return@collect
                when (obj.str("type")) {
                    "waterfall" -> {
                        val event = waterfallOf(obj) ?: return@collect
                        if (event.event != "approval/request") return@collect
                        val request = event.request
                        val toolName = request.str("toolName") ?: "工具"
                        val display = request.obj("displayReason")
                        val summary = display?.str("zh") ?: display?.str("en")
                            ?: request.str("reason") ?: "要执行 $toolName"
                        val pending = DshApproval(
                            eventId = event.eventId,
                            sessionId = event.agentId,
                            toolName = toolName,
                            summary = summary,
                        )
                        _approvals.value = (_approvals.value.filterNot { it.eventId == pending.eventId } + pending)
                    }
                    "cancel" -> {
                        val eventId = obj.str("eventId") ?: return@collect
                        _approvals.value = _approvals.value.filterNot { it.eventId == eventId }
                    }
                }
            }
        } catch (t2: Throwable) {
            if (t2 is kotlinx.coroutines.CancellationException) throw t2
            Logx.w("remote", "审批事件流断了：${t2.message}")
        }
    }

    /**
     * 回答一条审批。
     *
     * 只给「允许一次 / 拒绝」两个选项：远端协议没有 `allow-session`，
     * 承诺一个做不到的"总是允许"比不给这个按钮更糟。
     */
    suspend fun respondApproval(eventId: String, allow: Boolean) {
        val t = tunnel ?: throw RemoteError.Closed("还没连接电脑")
        val outcome = buildJsonObject {
            put("kind", "result")
            put("value", if (allow) "allowed-once" else "rejected")
        }
        t.respond(eventId, outcome)
        _approvals.value = _approvals.value.filterNot { it.eventId == eventId }
    }

    fun close() {
        runCatching { tunnel?.close() }
        tunnel = null
        _approvals.value = emptyList()
        scope.cancel()
    }

    companion object {
        private val AUTH_CODES = setOf("AUTH_INVALID", "TOKEN_EXPIRED", "AUTH_REQUIRED", "ACCOUNT_AUTH_REQUIRED")

        /** 把服务端的错误码翻成用户能做的事，而不是把英文原样糊在手表上 */
        fun humanize(code: String, message: String): String = when (code) {
            "HOST_OFFLINE" -> "电脑不在线：那边可能休眠了，或者 DSH Remote 插件没开"
            "CONNECTION_REPLACED" -> "这条连接被同一台手表的另一次连接顶掉了"
            "AUTH_INVALID", "TOKEN_EXPIRED", "AUTH_REQUIRED", "ACCOUNT_AUTH_REQUIRED" ->
                "登录已失效，到「设置 → 手机配对」重新扫一次"
            "DEVICE_REVOKED", "DEVICE_NOT_FOUND" -> "这台手表已被服务端移除，需要重新配对"
            "MEMBERSHIP_REQUIRED" -> "账号没有远程连接的权限"
            "PEER_IDENTITY_MISMATCH" -> "电脑的身份公钥变了，可能是重装过；重新配对一次"
            "CAPABILITY_NOT_SUPPORTED" -> "这个服务端不支持手表用的 Relay 通道"
            else -> message
        }
    }
}

/** `session/list` 的解析。两边（HTTP 与隧道）返回的是同一个结构 */
object DshSessionList {

    fun parse(value: JsonElement): List<DshSession> {
        val items = (value as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { line ->
            val obj = line as? JsonObject ?: return@mapNotNull null
            val id = obj.str("sessionId") ?: obj.str("id") ?: return@mapNotNull null
            DshSession(
                id = id,
                title = obj.str("title").orEmpty(),
                cwd = obj.str("cwd").orEmpty(),
                archived = obj.bool("archived") ?: false,
                updatedAt = obj.long("updatedAt") ?: 0L,
            )
        }
    }
}
