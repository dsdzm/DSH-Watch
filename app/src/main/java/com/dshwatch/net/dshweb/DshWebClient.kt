package com.dshwatch.net.dshweb

import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * 直连 `dsh web` 的 RPC 客户端。
 *
 * ## 线格式（来自 ds-harness-remote v1，不是 OpenAI 兼容协议）
 *
 * ```text
 * POST /api/session/list      content-type: application/json
 * {"type":"client-request","rpcId":"<uuid>","method":"session/list",
 *  "payload":{"args":{"_request":{}}}}
 *
 * 200 {"type":"server-response","rpcId":"<uuid>",
 *      "result":{"ok":true,"value":{...}}}
 * 200 {"type":"server-response","rpcId":"<uuid>",
 *      "result":{"ok":false,"error":{"code","message","details"}}}
 * ```
 *
 * 注意**业务失败也是 HTTP 200**，只有载体层拒绝才用状态码（401 cookie 失效、
 * 404 端点不存在、415 类型不对）。所以「HTTP 成功」不等于「调用成功」，
 * 必须看 `result.ok` —— 这一点如果搞错，界面上会出现一堆「成功但没数据」。
 */
class DshWebClient(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    @Volatile
    private var activeCall: Call? = null

    fun cancelActive() {
        runCatching { activeCall?.cancel() }
    }

    /** 用启动令牌换 cookie。返回的是「已登录」的 host，调用方负责持久化 */
    suspend fun login(host: DshWebHost, token: String): DshWebHost =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(DshWeb.loginUrl(host, token))
                .dshHost(host)
                .header("Accept", "text/html,application/json")
                // 必须自己看 302：cookie 在重定向响应上，跟随后就丢了
                .get()
                .build()
            val call = Http.client.newCall(req)
            activeCall = call
            try {
                call.execute().use { resp ->
                    if (resp.code == 401) {
                        throw DshWebError.Unauthorized(
                            "启动令牌被拒绝：可能已换过进程、或填的不是这台机器的地址"
                        )
                    }
                    if (resp.code !in 200..399) {
                        throw DshWebError.Http(resp.code, resp.body?.string().orEmpty().take(200))
                    }
                    val cookies = resp.headers.values("Set-Cookie")
                    val minted = cookies.firstNotNullOfOrNull {
                        DshWeb.parseSetCookie(it, host.authority)
                    } ?: throw DshWebError.Protocol(
                        "登录响应里没有 dsh-auth cookie（HTTP ${resp.code}）"
                    )
                    host.withCookie(minted)
                }
            } finally {
                activeCall = null
            }
        }

    /** 一次一元调用。业务失败抛 [DshWebError.Remote]，载体失败抛 [DshWebError.Http] */
    suspend fun call(
        host: DshWebHost,
        endpoint: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): JsonElement = withContext(Dispatchers.IO) {
        val cookie = host.cookie ?: throw DshWebError.Unauthorized("还没有登录这台电脑")
        if (cookie.isExpired()) throw DshWebError.Unauthorized("登录已过期，请重新粘贴启动地址")

        val rpcId = UUID.randomUUID().toString()
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", endpoint)
            put("payload", buildJsonObject { put("args", args) })
        }
        val req = Request.Builder()
            .url(DshWeb.rpcUrl(host, endpoint))
            .dshHost(host)
            .header("Cookie", cookie.header)
            .header("Accept", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), envelope).toRequestBody(JSON_MEDIA))
            .build()

        val call = Http.client.newCall(req)
        activeCall = call
        try {
            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 401 || resp.code == 403) {
                    throw DshWebError.Unauthorized("服务端拒绝了 cookie（HTTP ${resp.code}）")
                }
                if (resp.code == 404) {
                    // 最常见的一种：地址填对了，但那台机器上跑的不是 dsh web
                    throw DshWebError.Protocol("$endpoint 不存在（HTTP 404）—— 这个地址上可能不是 dsh web")
                }
                if (resp.code !in 200..299) {
                    throw DshWebError.Http(resp.code, text.take(200))
                }
                parseEnvelope(text, rpcId, endpoint)
            }
        } catch (io: IOException) {
            throw DshWebError.Network(io)
        } finally {
            activeCall = null
        }
    }

    private fun parseEnvelope(text: String, rpcId: String, endpoint: String): JsonElement {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw DshWebError.Protocol("响应不是 JSON：${text.take(120)}")
        // rpcId 对不上说明响应串了（代理、缓存、或者同时来了别的请求），必须拒绝
        val echoed = root["rpcId"].stringOrNull
        if (echoed != null && echoed != rpcId) {
            throw DshWebError.Protocol("响应 rpcId 不匹配（$echoed ≠ $rpcId）")
        }
        val result = root["result"] as? JsonObject
            ?: throw DshWebError.Protocol("响应缺少 result：${text.take(120)}")
        val ok = result["ok"].boolOrNull ?: false
        if (!ok) {
            val error = result["error"] as? JsonObject
            val code = error?.get("code").stringOrNull ?: "unknown"
            val message = error?.get("message").stringOrNull ?: "调用失败"
            throw DshWebError.Remote(code, message)
        }
        return result["value"] ?: JsonNull
    }

    // ---------------- 具体接口 ----------------

    /** `GET /` 带 cookie：200 表示服务在且 cookie 有效。没有独立的 health 端点 */
    suspend fun isReachable(host: DshWebHost): Boolean = withContext(Dispatchers.IO) {
        val cookie = host.cookie ?: return@withContext false
        val req = Request.Builder()
            .url(host.baseUrl.trimEnd('/') + "/")
            .dshHost(host)
            .header("Cookie", cookie.header)
            .get()
            .build()
        runCatching {
            Http.client.newCall(req).execute().use { it.code == 200 }
        }.getOrDefault(false)
    }

    /** `session/list`：最近在前，含归档与子代理会话 */
    suspend fun listSessions(host: DshWebHost): List<DshSession> {
        val value = call(host, "session/list", buildJsonObject { put("_request", JsonObject(emptyMap())) })
        val items = (value as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { line ->
            val obj = line as? JsonObject ?: return@mapNotNull null
            val id = obj["sessionId"].stringOrNull
                ?: obj["id"].stringOrNull
                ?: return@mapNotNull null
            DshSession(
                id = id,
                title = obj["title"].stringOrNull.orEmpty(),
                cwd = obj["cwd"].stringOrNull.orEmpty(),
                archived = obj["archived"].boolOrNull ?: false,
                updatedAt = obj["updatedAt"].longOrNull ?: 0L,
            )
        }
    }

    /** `session/create`：不传 workspace 时用 `dsh web` 进程的 cwd */
    suspend fun createSession(host: DshWebHost, model: String = ""): String {
        val value = call(
            host,
            "session/create",
            buildJsonObject { put("request", buildJsonObject { }) },
        )
        val id = (value as? JsonObject)?.get("sessionId").stringOrNull
            ?: throw DshWebError.Protocol("session/create 没有返回 sessionId")
        if (id.isBlank()) throw DshWebError.Protocol("session/create 返回了空的 sessionId")
        return id
    }

    /**
     * `session/prompt`：把一条用户消息投进 Agent 的收件箱。
     *
     * 返回的 requestId 会被回显成 `user/message.source.rpcId`；用同一个 id 重发是
     * 幂等的 —— 手表网络差、重试多，这一点必须依赖服务端而不是本地去重。
     */
    suspend fun prompt(host: DshWebHost, sessionId: String, text: String): String {
        if (text.isBlank()) throw DshWebError.Remote("bad-request", "消息不能为空")
        val requestId = UUID.randomUUID().toString()
        call(
            host,
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
                            )
                        )
                    },
                )
            },
        )
        return requestId
    }

    /** `session/cancel`：打断当前回合；空闲时服务端也回 accepted */
    suspend fun cancel(host: DshWebHost, sessionId: String) {
        call(host, "session/cancel", buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) })
    }

    /**
     * 走隧道时把 Host 头改回远端那台机器。
     *
     * dsh 的围栏按 `Host` 判断「这是不是本机」，并拒绝任何与自己不一致的
     * `Origin`。OkHttp 默认不发 Origin（正合要求），但 Host 会取 URL 的 ——
     * 隧道下那就成了本地随机端口，服务端不认。
     */
    private fun Request.Builder.dshHost(host: DshWebHost): Request.Builder = apply {
        if (host.hostHeader.isNotBlank()) header("Host", host.hostHeader)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** `session/list` 的一项（只取手表会显示的几个字段） */
data class DshSession(
    val id: String,
    val title: String,
    val cwd: String,
    val archived: Boolean,
    val updatedAt: Long,
) {
    val displayTitle: String get() = title.ifBlank { cwd.substringAfterLast('/').ifBlank { id.take(8) } }
}

/**
 * 连接失败的原因。
 *
 * 分开建类型不是为了好看：这几种失败的**处理方式完全不同** ——
 * 401 要去电脑上重开 `dsh web` 拿新地址；404 是地址填错了机器；
 * 网络错误只是没通。混成一个 `Exception("失败")`，用户就只能干瞪眼。
 */
sealed class DshWebError(message: String) : Exception(message) {

    /** cookie 无效/过期，需要重新登录 */
    class Unauthorized(message: String) : DshWebError(message)

    /** 载体层拒绝：状态码 + 原文 */
    class Http(val code: Int, val body: String) : DshWebError("HTTP $code：$body")

    /** 对端不是 dsh web，或协议不符 */
    class Protocol(message: String) : DshWebError(message)

    /** 连不上：DNS、拒绝连接、超时 */
    class Network(val cause2: IOException) : DshWebError(cause2.message ?: "网络错误")

    /** 服务端业务错误（`result.ok == false`） */
    class Remote(val code: String, message: String) : DshWebError(message)

    fun describeForUser(): String = when (this) {
        // message 是平台类型（String!），直接拼接会让整个 when 变成 String?，
        // 所以每个分支都先落到非空值上
        is Unauthorized -> (message ?: "未授权") + "。请在电脑上重启 dsh web，重新复制带 token 的地址"
        is Http -> message ?: "HTTP ${code}"
        is Protocol -> message ?: "对端不是 dsh web"
        is Network -> "连不上这台电脑：${message ?: "网络不通"}（确认同一个 Wi-Fi、端口没被防火墙挡住）"
        is Remote -> "服务端拒绝：$code — ${message ?: "未知原因"}"
    }

    companion object {
        fun from(t: Throwable): DshWebError = when (t) {
            is DshWebError -> t
            is IOException -> Network(t)
            else -> Protocol(t.message ?: t.javaClass.simpleName)
        }
    }
}

/** 取字符串字段；null、数字、对象一律当「没有」，避免一处 JsonNull 把整条链路炸掉 */
internal val JsonElement?.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal val JsonElement?.longOrNull: Long?
    get() = (this as? JsonPrimitive)?.content?.toLongOrNull()

internal val JsonElement?.boolOrNull: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

