package com.dshwatch.net.backend

import com.dshwatch.core.Logx
import com.dshwatch.net.Http
import com.dshwatch.net.SseReader
import com.dshwatch.net.model.DshPairRequest
import com.dshwatch.net.model.DshPairResponse
import com.dshwatch.net.model.DshSessionRef
import com.dshwatch.net.model.DshStreamEvent
import com.dshwatch.net.model.OaiChunk
import com.dshwatch.net.model.OaiModelList
import com.dshwatch.domain.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * DSH 服务器模式：连接你自己电脑上运行的 DeepSeek Harness。
 *
 * 说明（重要）：
 *  DSH 的移动端远程访问由 `dsh-workspace` 插件提供。不同小版本的路径与字段偶有差异，
 *  因此本文件把所有路径集中在 [Endpoints]、所有字段名集中在 net/model/Wire.kt 的
 *  `Dsh*` 数据结构里。如果你的服务端版本对不上，只改这两个地方即可，不用动 UI 和业务逻辑。
 *
 * 协议假设：
 *  1) POST {PAIR}      body {code, device_name}      -> {token, expires_at, scopes}
 *  2) POST {SESSIONS}  body {title?}                 -> {id, ...}
 *  3) POST {SESSIONS}/{id}/messages  body {content, model, stream:true, permission}
 *       -> SSE，事件形如 {"type":"delta","delta":"..."} / {"type":"usage","usage":{...}} / {"type":"done"}
 *  4) GET  {MODELS}    -> OpenAI 风格 {"data":[{"id":"..."}]}
 *  5) GET  {HEALTH}    -> 任意 200 响应即视为通则
 *
 *  流式解析器同时兼容「DSH 原生事件」与「OpenAI chunk」两种形态，
 *  因此即使某些版本直接复用 OpenAI 协议也能跑。
 */
class DshServerBackend(private val json: Json) : ChatBackend {

    object Endpoints {
        const val HEALTH = "/api/v1/health"
        const val PAIR = "/api/v1/devices/pair"
        const val SESSIONS = "/api/v1/sessions"
        const val MODELS = "/api/v1/models"
        const val USAGE = "/api/v1/usage"

        fun messages(sessionId: String) = "$SESSIONS/$sessionId/messages"
    }

    @Volatile
    private var activeCall: Call? = null

    override fun cancelActive() {
        runCatching { activeCall?.cancel() }
    }

    // ---------------- 配对 ----------------

    /** 用一次性配对码换取设备令牌 */
    suspend fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
    ): DshPairResponse = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val url = url(baseUrl, Endpoints.PAIR)
        val payload = json.encodeToString(
            DshPairRequest.serializer(),
            DshPairRequest(code = code.trim(), deviceName = deviceName.ifBlank { "DSH Watch" })
        )
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(Http.JSON_MEDIA))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DshError.Http(resp.code, text)
            val parsed = runCatching { json.decodeFromString(DshPairResponse.serializer(), text) }
                .getOrElse { throw DshError.Parse("配对响应无法解析：${text.take(200)}") }
            if (parsed.token.isBlank()) {
                throw DshError.Server(parsed.error ?: "服务端未返回设备令牌")
            }
            parsed
        }
    }

    suspend fun listRemoteSessions(
        provider: Provider,
        serverToken: String,
    ): List<DshSessionRef> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url(provider.normalizedBaseUrl(), Endpoints.SESSIONS))
            .header("Authorization", "Bearer $serverToken")
            .get()
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DshError.Http(resp.code, text)
            runCatching { json.decodeFromString(ListSerializer(DshSessionRef.serializer()), text) }
                .getOrElse { throw DshError.Parse("会话列表无法解析：${text.take(160)}") }
        }
    }

    // ---------------- 聊天 ----------------

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
        if (request.baseUrl.isBlank()) {
            emit(ChatEvent.Failure(DshError.BadConfig("DSH 服务器地址为空")))
            return@flow
        }
        if (request.serverToken.isBlank()) {
            emit(ChatEvent.Failure(DshError.Auth("设备未配对，请先在「配对」页输入配对码")))
            return@flow
        }

        var sessionId = request.remoteSessionId
        try {
            if (sessionId.isBlank()) {
                sessionId = createSession(request)
                emit(ChatEvent.RemoteSession(sessionId))
            }
        } catch (t: Throwable) {
            emit(ChatEvent.Failure(DshError.from(t)))
            return@flow
        }

        val lastUser = request.history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val payload = buildJsonObject {
            put("content", lastUser)
            if (request.systemPrompt.isNotBlank()) put("system", request.systemPrompt)
            if (request.model.isNotBlank()) put("model", request.model)
            put("stream", request.stream)
            put("permission", "full")
        }

        val url = url(request.baseUrl, Endpoints.messages(sessionId))
        val httpReq = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${request.serverToken}")
            .header("Accept", if (request.stream) "text/event-stream" else "application/json")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(Http.JSON_MEDIA))
            .build()

        val call = Http.client.newCall(httpReq)
        activeCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    emit(ChatEvent.Failure(DshError.Http(resp.code, text)))
                    return@flow
                }
                if (request.stream) emitStreaming(resp) else emitWhole(resp)
            }
        } catch (io: IOException) {
            if (currentCoroutineContext().isActive) emit(ChatEvent.Failure(DshError.Network(io)))
        } catch (t: Throwable) {
            emit(ChatEvent.Failure(DshError.from(t)))
        } finally {
            activeCall = null
        }
    }.flowOn(Dispatchers.IO)

    private fun createSession(request: ChatRequest): String {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                if (request.model.isNotBlank()) put("model", request.model)
                put("title", JsonPrimitive(""))
            }
        )
        val req = Request.Builder()
            .url(url(request.baseUrl, Endpoints.SESSIONS))
            .header("Authorization", "Bearer ${request.serverToken}")
            .post(body.toRequestBody(Http.JSON_MEDIA))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DshError.Http(resp.code, text)
            val ref = runCatching { json.decodeFromString(DshSessionRef.serializer(), text) }
                .getOrElse { throw DshError.Parse("会话创建响应无法解析：${text.take(200)}") }
            if (ref.id.isBlank()) throw DshError.Server("服务端未返回会话 id")
            return ref.id
        }
    }

    override suspend fun listModels(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): List<String> {
        // 复用 rawGet（内部已切 IO）。原来这里是裸的 execute()，
        // 从 Main dispatcher 调用会直接抛 NetworkOnMainThreadException。
        val (code, body) = rawGet(url(provider.normalizedBaseUrl(), Endpoints.MODELS), serverToken)
        if (code !in 200..299) throw DshError.Http(code, body)
        val list = runCatching { json.decodeFromString(OaiModelList.serializer(), body) }
            .getOrElse { throw DshError.Parse("模型列表无法解析：${body.take(160)}") }
        return list.data.map { it.id }.filter { it.isNotBlank() }.sorted()
    }

    override suspend fun testConnection(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val base = provider.normalizedBaseUrl()
        val health = Request.Builder()
            .url(url(base, Endpoints.HEALTH))
            .header("Authorization", "Bearer $serverToken")
            .get()
            .build()
        val healthy = runCatching {
            Http.client.newCall(health).execute().use { resp ->
                if (!resp.isSuccessful) throw DshError.Http(resp.code, resp.body?.string().orEmpty())
            }
            true
        }.onFailure { Logx.d(TAG, "health 探测失败：${it.message}") }.getOrDefault(false)

        if (healthy) return@withContext "已连接 DSH（${base}）"

        val models = listModels(provider, apiKey, serverToken)
        "已连接 DSH，可用模型 ${models.size} 个"
    }

    /**
     * DSH 服务器诊断。
     * 只做只读探测（health / models / sessions），**不创建会话**，避免在服务端留下垃圾数据。
     */
    override suspend fun diagnose(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): List<DiagStep> {
        val steps = ArrayList<DiagStep>(4)
        val base = provider.normalizedBaseUrl()

        val problems = buildList {
            if (base.isBlank()) add("服务器地址为空")
            if (serverToken.isBlank()) add("设备未配对（没有令牌）")
            if (base.isNotBlank() && !base.startsWith("http://") && !base.startsWith("https://")) {
                add("地址缺少 http(s):// 前缀")
            }
        }
        steps += DiagStep(
            name = STEP_CONFIG,
            ok = problems.isEmpty(),
            detail = if (problems.isEmpty()) base else problems.joinToString("；"),
        )
        if (base.isBlank() || serverToken.isBlank()) return steps

        steps += runStep(STEP_HEALTH) {
            val (code, body) = rawGet(base + Endpoints.HEALTH, serverToken)
            if (code !in 200..299) {
                throw DshError.Http(code, body.ifBlank { "该版本可能没有 health 接口" })
            }
            "服务端可达"
        }

        steps += runStep(STEP_MODELS) {
            val (code, body) = rawGet(base + Endpoints.MODELS, serverToken)
            if (code !in 200..299) throw DshError.Http(code, body)
            val list = runCatching {
                json.decodeFromString(OaiModelList.serializer(), body)
            }.getOrElse { throw DshError.Parse("返回的不是模型列表：${body.take(160)}") }
            val ids = list.data.map { it.id }.filter { it.isNotBlank() }
            if (ids.isEmpty()) "接口可用但返回空列表" else "${ids.size} 个模型（${ids.take(3).joinToString(", ")}）"
        }

        steps += runStep(STEP_SESSIONS) {
            val (code, body) = rawGet(base + Endpoints.SESSIONS, serverToken)
            if (code !in 200..299) throw DshError.Http(code, body)
            val list = runCatching {
                json.decodeFromString(ListSerializer(DshSessionRef.serializer()), body)
            }.getOrElse { throw DshError.Parse("会话列表格式不符：${body.take(160)}") }
            "${list.size} 个会话"
        }

        return steps
    }

    private suspend fun runStep(name: String, block: suspend () -> String): DiagStep {
        val t0 = System.currentTimeMillis()
        return try {
            DiagStep(name, true, block(), System.currentTimeMillis() - t0)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            val e = DshError.from(t)
            DiagStep(name, false, e.describeForUser(), System.currentTimeMillis() - t0)
        }
    }

    private suspend fun rawGet(url: String, token: String): Pair<Int, String> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            Http.client.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
        }

    // ------------------------------------------------------------------

    private suspend fun FlowCollector<ChatEvent>.emitStreaming(resp: Response) {
        val source = resp.body?.source()
        if (source == null) {
            emit(ChatEvent.Failure(DshError.Parse("响应体为空")))
            return
        }
        val reader = SseReader(source)
        while (true) {
            val ev = reader.read() ?: break
            val data = ev.data.trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break

            // 先按 DSH 原生事件解析
            val legacy = runCatching { json.decodeFromString(DshStreamEvent.serializer(), data) }.getOrNull()
            if (legacy != null && legacy.type.isNotBlank()) {
                when (legacy.type.lowercase()) {
                    "delta", "text", "message", "content" -> {
                        val t = legacy.delta ?: legacy.content
                        if (!t.isNullOrEmpty()) emit(ChatEvent.Text(t))
                    }
                    "reasoning", "thinking" -> {
                        legacy.reasoningText()?.takeIf { it.isNotEmpty() }
                            ?.let { emit(ChatEvent.Reasoning(it)) }
                    }
                    "usage" -> legacy.usage?.let { emit(ChatEvent.Usage(it.toTokenUsage())) }
                    "title" -> legacy.title?.takeIf { it.isNotBlank() }?.let { emit(ChatEvent.Title(it)) }
                    "session", "session_id" -> legacy.sessionId?.takeIf { it.isNotBlank() }
                        ?.let { emit(ChatEvent.RemoteSession(it)) }
                    "error" -> {
                        emit(ChatEvent.Failure(DshError.Server(legacy.error ?: legacy.message ?: "未知错误")))
                        return
                    }
                    "done", "completed", "end" -> break
                    else -> Unit
                }
                continue
            }

            // 再退回 OpenAI chunk 形态
            val oai = runCatching { json.decodeFromString(OaiChunk.serializer(), data) }.getOrNull()
                ?: continue
            oai.error?.let {
                emit(ChatEvent.Failure(DshError.Server(it.message ?: "未知错误")))
                return
            }
            oai.usage?.let { emit(ChatEvent.Usage(it.toTokenUsage())) }
            val choice = oai.choices.firstOrNull() ?: continue
            val d = choice.delta ?: choice.message
            d?.reasoningText()?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Reasoning(it)) }
            d?.content?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Text(it)) }
        }
        emit(ChatEvent.Completed)
    }

    private suspend fun FlowCollector<ChatEvent>.emitWhole(resp: Response) {
        val text = resp.body?.string().orEmpty()
        val legacy = runCatching { json.decodeFromString(DshStreamEvent.serializer(), text) }.getOrNull()
        if (legacy != null && (legacy.content != null || legacy.delta != null || legacy.type.isNotBlank())) {
            legacy.error?.let { throw DshError.Server(it) }
            legacy.reasoningText()?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Reasoning(it)) }
            (legacy.content ?: legacy.delta)?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Text(it)) }
            legacy.usage?.let { emit(ChatEvent.Usage(it.toTokenUsage())) }
            emit(ChatEvent.Completed)
            return
        }
        val oai = runCatching { json.decodeFromString(OaiChunk.serializer(), text) }
            .getOrElse { throw DshError.Parse("响应无法解析：${text.take(200)}") }
        val msg = oai.choices.firstOrNull()?.message
        msg?.reasoningText()?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Reasoning(it)) }
        msg?.content?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Text(it)) }
        oai.usage?.let { emit(ChatEvent.Usage(it.toTokenUsage())) }
        emit(ChatEvent.Completed)
    }

    companion object {
        private const val TAG = "DshServer"

        const val STEP_CONFIG = "配置检查"
        const val STEP_HEALTH = "服务端可达"
        const val STEP_MODELS = "模型列表"
        const val STEP_SESSIONS = "会话列表"

        fun url(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + path
    }
}
