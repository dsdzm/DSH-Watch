package com.dshwatch.domain.qq

import com.dshwatch.core.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** 群里被 @ 的一条消息。字段名跟着 QQ 的 openid 走，不是群号/QQ 号 */
data class QqGroupMessage(
    val groupOpenid: String,
    val senderOpenid: String,
    val content: String,
    val msgId: String,
)

enum class QqState { IDLE, CONNECTING, ONLINE, RECONNECTING, FAILED }

/**
 * QQ 机器人网关的 WebSocket 客户端。
 *
 * 这一层只做「连上、说话、别掉」，业务在 [QqBridge]。分开的原因是：
 * 断线重连的状态机是**最容易写错又最难复现**的部分（手表上息屏一次就掉），
 * 混进业务逻辑里以后，任何一次改业务都可能顺手把重连改坏。
 *
 * 状态机对应网关的 opcode：
 *
 * | op | 含义 | 这里怎么处理 |
 * |----|------|--------------|
 * | 10 | Hello | 拿 heartbeat_interval 起心跳，然后发 Identify |
 * | 11 | 心跳 ACK | 记一次存活，用于判断「是不是假连接」 |
 * | 0  | Dispatch | READY 记 session_id；群 @ 事件交出去 |
 * | 7  | 要求重连 | 直接断开重连（会话还能续） |
 * | 9  | 会话失效 | 清 session/seq，重新 Identify（不能 Resume） |
 *
 * 重连用指数退避，上限 60 秒：断网时每秒重试一次只会被网关限流，
 * 表现是「越重连越连不上」。
 */
class QqGateway(
    private val api: QqApi,
    private val scope: CoroutineScope,
    private val onGroupAt: suspend (QqGroupMessage) -> Unit,
    private val onState: (QqState) -> Unit = {},
) {

    companion object {
        private const val TAG = "QqGateway"

        /** `1 shl 25` = GROUP_AND_C2C_EVENT，一个值同时覆盖群 @ 与单聊 */
        private const val INTENTS = 33554432

        private const val MAX_BACKOFF_MS = 60_000L
        private const val MIN_BACKOFF_MS = 2_000L
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 长连接必须用独立的 client。
     *
     * [com.dshwatch.net.Http.client] 的 readTimeout 是 180 秒，对 SSE 合适，
     * 对 WebSocket 是致命的 —— 网关平时几百秒不说一句话，读超时一到就断，
     * 而现象是「每隔三分钟掉一次线」，很容易被误判成网络问题。
     * 这里 readTimeout 设 0（不超时），改用 pingInterval 保活。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var running = false

    @Volatile
    private var ws: WebSocket? = null

    private var loopJob: Job? = null
    private var heartbeatJob: Job? = null

    /** 最近一个 Dispatch 的 s，心跳和 Resume 都要带 */
    @Volatile
    private var lastSeq: Int? = null

    @Volatile
    private var sessionId: String = ""

    @Volatile
    private var heartbeatAcked = true

    /** 本次连接用的 token。鉴权信息在握手时就发过了，Identify 里再带一份 */
    @Volatile
    private var currentToken: String = ""

    fun start() {
        if (running) return
        running = true
        loopJob = scope.launch { connectLoop() }
    }

    fun stop() {
        running = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { ws?.close(1000, "bye") }
        ws = null
        loopJob?.cancel()
        loopJob = null
        onState(QqState.IDLE)
    }

    // ------------------------------------------------------------------
    // 连接主循环
    // ------------------------------------------------------------------

    private suspend fun connectLoop() {
        var attempt = 0
        while (running && scope.isActive) {
            try {
                onState(if (attempt == 0) QqState.CONNECTING else QqState.RECONNECTING)
                connectOnce()
                // connectOnce 正常返回 = 连接已经断开，走退避
            } catch (t: Throwable) {
                Logx.w(TAG, "连接失败：${t.message}")
                onState(QqState.FAILED)
            }
            if (!running) break
            attempt++
            val backoff = min(MAX_BACKOFF_MS, MIN_BACKOFF_MS * (1L shl min(attempt, 5)))
            delay(backoff)
        }
        onState(QqState.IDLE)
    }

    /** 建立一次连接并阻塞到它断开 */
    private suspend fun connectOnce() {
        val gatewayUrl = api.gateway()
        val token = api.token()
        currentToken = token
        val request = Request.Builder()
            .url(gatewayUrl)
            .header("Authorization", "QQBot $token")
            .header("X-Union-Appid", api.appId.trim())
            .build()

        val closed = CompletableDeferred<Unit>()
        heartbeatAcked = true

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logx.d(TAG, "WebSocket 已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 回调在 OkHttp 的线程上，业务处理要回协程作用域
                scope.launch { handlePayload(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logx.w(TAG, "WebSocket 断开：${t.message}")
                if (!closed.isCompleted) closed.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logx.d(TAG, "WebSocket 关闭：$code $reason")
                if (!closed.isCompleted) closed.complete(Unit)
            }
        })
        ws = socket
        closed.await()
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { socket.cancel() }
        ws = null
    }

    // ------------------------------------------------------------------
    // 协议
    // ------------------------------------------------------------------

    private suspend fun handlePayload(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val op = (obj["op"] as? JsonPrimitive)?.intOrNull ?: return

        when (op) {
            10 -> onHello(obj)
            11 -> heartbeatAcked = true
            0 -> onDispatch(obj)
            7 -> {
                // 服务端要求重连，会话还能续
                Logx.d(TAG, "网关要求重连（op=7）")
                runCatching { ws?.close(1000, "reconnect") }
            }
            9 -> {
                // session 失效：必须清干净重新 Identify，否则会一直被踢
                Logx.w(TAG, "会话失效（op=9），重新登录")
                sessionId = ""
                lastSeq = null
                runCatching { ws?.close(1000, "invalid session") }
            }
            else -> Logx.d(TAG, "未处理的 opcode=$op")
        }
    }

    private fun onHello(obj: JsonObject) {
        val d = obj["d"] as? JsonObject
        val interval = (d?.get("heartbeat_interval") as? JsonPrimitive)?.longOrNull ?: 41_250L
        Logx.d(TAG, "Hello，心跳间隔 ${interval}ms")

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(interval)
                if (!heartbeatAcked) {
                    // 上一次心跳没回音 = 连接已经死了（NAT 超时、基站切换），
                    // 但 socket 还没报错。这时候必须主动断开，否则会一直挂着不重连。
                    Logx.w(TAG, "心跳无响应，主动断开重连")
                    runCatching { ws?.close(1000, "heartbeat timeout") }
                    break
                }
                heartbeatAcked = false
                sendRaw("{\"op\":1,\"d\":${lastSeq ?: "null"}}")
            }
        }
        // 有 session 且见过 seq 才能续；否则从头登录
        if (sessionId.isNotBlank() && lastSeq != null) sendResume() else sendIdentify()
    }

    private fun sendIdentify() {
        val token = currentToken
        if (token.isBlank()) {
            Logx.w(TAG, "没有 token，Identify 跳过")
            return
        }
        val payload = buildString {
            append("{\"op\":2,\"d\":{\"token\":\"QQBot ").append(token)
                .append("\",\"intents\":").append(INTENTS)
                .append(",\"shard\":[0,1],\"properties\":{\"\$os\":\"android\",")
                .append("\"\$browser\":\"dsh-watch\",\"\$device\":\"watch\"}}}")
        }
        sendRaw(payload)
    }

    private fun sendResume() {
        val token = currentToken
        if (token.isBlank() || sessionId.isBlank()) {
            sendIdentify()
            return
        }
        val seq = lastSeq
        val payload = buildString {
            append("{\"op\":6,\"d\":{\"token\":\"QQBot ").append(token)
                .append("\",\"session_id\":\"").append(sessionId).append("\",\"seq\":")
                .append(seq ?: 1).append("}}")
        }
        sendRaw(payload)
    }

    private fun sendRaw(text: String) {
        val socket = ws ?: return
        if (!socket.send(text)) Logx.w(TAG, "发送失败：$text")
    }

    private fun onDispatch(obj: JsonObject) {
        val seq = (obj["s"] as? JsonPrimitive)?.intOrNull
        if (seq != null) lastSeq = seq
        val type = (obj["t"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val d = obj["d"] as? JsonObject ?: return

        when (type) {
            "READY" -> {
                sessionId = (d["session_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                onState(QqState.ONLINE)
                Logx.d(TAG, "机器人已上线，session=$sessionId")
            }

            "RESUMED" -> {
                onState(QqState.ONLINE)
                Logx.d(TAG, "会话已恢复")
            }

            "GROUP_AT_MESSAGE_CREATE" -> {
                val msg = parseGroupMessage(d) ?: return
                scope.launch {
                    runCatching { onGroupAt(msg) }
                        .onFailure { Logx.w(TAG, "处理群消息失败：${it.message}") }
                }
            }

            else -> Logx.d(TAG, "忽略事件 $type")
        }
    }

    /**
     * 解析群 @ 消息。
     *
     * 作者字段有两个名字：文档写 `author.user_openid`，实际群消息下发的是
     * `author.member_openid`。两个都读 —— 只认一个的话，白名单永远匹配不上，
     * 现象是「机器人收到了但一句话都不回」，日志里还看不出问题。
     */
    private fun parseGroupMessage(d: JsonObject): QqGroupMessage? {
        val groupOpenid = (d["group_openid"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val author = d["author"] as? JsonObject
        val sender = (author?.get("member_openid") as? JsonPrimitive)?.contentOrNull
            ?: (author?.get("user_openid") as? JsonPrimitive)?.contentOrNull
            ?: (author?.get("id") as? JsonPrimitive)?.contentOrNull
            ?: ""
        val id = (d["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val content = (d["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        if (groupOpenid.isBlank() || id.isBlank()) return null
        return QqGroupMessage(
            groupOpenid = groupOpenid,
            senderOpenid = sender,
            content = cleanGroupContent(content),
            msgId = id,
        )
    }
}

/**
 * 清理正文。
 *
 * 群 @ 的消息正文形如 `" /help"` 或 `<@!123456> 你好`：前者带一个前导空格
 * （@ 与正文之间的分隔），后者是行内 @ 标签。直接喂给模型会让它以为
 * 用户真的打了尖括号，回答里跟着学。
 *
 * 放在文件级 + internal 只为了能单测：协议解析里这种「看似无害的字符串处理」
 * 恰恰是最容易在真机上出问题、又最难在界面里发现的部分。
 */
internal fun cleanGroupContent(raw: String): String =
    raw.replace(Regex("<@!?[0-9]+>"), "").trim()
