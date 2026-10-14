package com.dshwatch.net.backend

import com.dshwatch.core.Logx
import com.dshwatch.net.Http
import com.dshwatch.net.SseReader
import com.dshwatch.net.model.OaiChunk
import com.dshwatch.net.model.OaiModelList
import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ReasoningEffort
import com.dshwatch.domain.model.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 直连模式：任何 OpenAI 兼容的 /chat/completions 服务。
 *
 * 兼容性处理：
 *  - 服务端不认识 `stream_options` 而返回 400 时，自动去掉该字段重试一次（不重复计费，因为请求被拒了）；
 *  - usage 字段在流式响应里可能出现在任意 chunk（OpenAI 放在最后一个带空 choices 的 chunk，
 *    DeepSeek 直接放在最后一个 chunk），所以只要 chunk 里出现就上报；
 *  - 思维链字段同时兼容 `reasoning_content`（DeepSeek）与 `reasoning`（部分中转站）。
 */
class OpenAiCompatBackend(private val json: Json) : ChatBackend {

    @Volatile
    private var activeCall: Call? = null

    override fun cancelActive() {
        runCatching { activeCall?.cancel() }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
        if (request.baseUrl.isBlank()) {
            emit(ChatEvent.Failure(DshError.BadConfig("Base URL 为空，请先在「供应商」里填写")))
            return@flow
        }
        if (request.apiKey.isBlank()) {
            emit(ChatEvent.Failure(DshError.Auth("API Key 为空")))
            return@flow
        }

        val endpoint = endpointOf(request.baseUrl, "chat/completions")
        var withUsage = request.usageInStream && request.stream
        var attempt = 0

        while (true) {
            val call = Http.client.newCall(buildChatRequest(endpoint, request, withUsage))
            activeCall = call
            var retryWithoutUsage = false
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                        if (resp.code == 400 && attempt == 0 && withUsage) {
                            retryWithoutUsage = true
                        } else {
                            emit(ChatEvent.Failure(DshError.Http(resp.code, text)))
                            return@flow
                        }
                    } else {
                        if (request.stream) emitStreaming(resp) else emitWhole(resp)
                        return@flow
                    }
                }
            } catch (io: IOException) {
                // 主动 cancelActive() 也会走到这里，此时协程已被取消，不要再报错误
                if (currentCoroutineContext().isActive) emit(ChatEvent.Failure(DshError.Network(io)))
                return@flow
            } catch (t: Throwable) {
                emit(ChatEvent.Failure(DshError.from(t)))
                return@flow
            } finally {
                activeCall = null
            }

            if (!retryWithoutUsage) return@flow
            Logx.w(TAG, "服务端拒绝 stream_options，去掉后重试")
            withUsage = false
            attempt++
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): List<String> {
        // 复用 rawGet：它内部已经切到 Dispatchers.IO。
        // 这里原来是裸的 call.execute()，而「供应商」页是从 Main dispatcher 调它的，
        // 真机上的表现就是「拉取失败: NetworkOnMainThreadException」。
        val (code, body) = rawGet(endpointOf(provider.normalizedBaseUrl(), "models"), apiKey)
        if (code !in 200..299) throw DshError.Http(code, body)
        val list = runCatching { json.decodeFromString(OaiModelList.serializer(), body) }
            .getOrElse { throw DshError.Parse("模型列表无法解析：${body.take(160)}") }
        return list.data.map { it.id }.filter { it.isNotBlank() }.sorted()
    }

    /**
     * 连通性自检。
     *
     * ⚠️ 旧实现只测了 `GET /models` 就返回「连通」——而聊天走的是
     * `POST /chat/completions`，两条路径的鉴权方式、请求体、响应形态完全不同。
     * 结果就是「测试通过但发消息失败」，非常难排查。
     * 现在直接复用 [diagnose]，把对话接口和流式输出也一并验证。
     */
    override suspend fun testConnection(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): String {
        val steps = diagnose(provider, apiKey, serverToken)
        val failed = steps.firstOrNull { !it.ok }
        if (failed != null) {
            throw DshError.Server("「${failed.name}」失败：\n${failed.detail}")
        }
        val models = steps.firstOrNull { it.name == STEP_MODELS }?.detail.orEmpty()
        val stream = steps.firstOrNull { it.name == STEP_STREAM }?.detail.orEmpty()
        return buildString {
            append("4 项全部通过")
            if (models.isNotBlank()) append(" · ").append(models)
            if (stream.isNotBlank()) append(" · ").append(stream.take(60))
        }
    }

    /**
     * 分步诊断，任何一步失败都不中断（把后面几步的真实结果也跑出来），
     * 便于一次性定位到底卡在哪一层。
     */
    override suspend fun diagnose(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): List<DiagStep> {
        val steps = ArrayList<DiagStep>(4)
        val base = provider.normalizedBaseUrl()
        val model = provider.defaultModel.ifBlank { provider.models.firstOrNull().orEmpty() }

        // 1) 配置自检
        val problems = buildList {
            if (base.isBlank()) add("接口地址为空")
            if (apiKey.isBlank()) add("API Key 为空")
            if (model.isBlank()) add("没有选择默认模型")
            if (base.isNotBlank() && !base.startsWith("http://") && !base.startsWith("https://")) {
                add("地址缺少 http(s):// 前缀")
            }
        }
        steps += DiagStep(
            name = STEP_CONFIG,
            ok = problems.isEmpty(),
            detail = if (problems.isEmpty()) "$base · $model" else problems.joinToString("；"),
        )
        if (base.isBlank() || apiKey.isBlank()) return steps

        // 2) GET /models
        steps += runStep(STEP_MODELS) {
            val (code, body) = rawGet(endpointOf(base, "models"), apiKey)
            if (code !in 200..299) throw DshError.Http(code, body)
            val list = runCatching { json.decodeFromString(OaiModelList.serializer(), body) }
                .getOrElse { throw DshError.Parse("返回的不是模型列表：${body.take(160)}") }
                .data.map { it.id }.filter { it.isNotBlank() }
            if (list.isEmpty()) {
                "接口可用但返回空列表"
            } else {
                "${list.size} 个模型（${list.take(3).joinToString(", ")}）"
            }
        }

        if (model.isBlank()) return steps

        // 3) 非流式对话
        steps += runStep(STEP_CHAT) {
            val payload = buildBody(
                model = model,
                systemPrompt = "",
                history = listOf(ChatTurn(role = "user", content = "ping")),
                temperature = 0f,
                topP = 1f,
                maxTokens = 16,
                stream = false,
                reasoningEffort = ReasoningEffort.DEFAULT,
                withUsage = false,
            )
            val (code, body) = rawPost(
                url = endpointOf(base, "chat/completions"),
                apiKey = apiKey,
                body = payload,
                accept = "application/json",
            )
            if (code !in 200..299) throw DshError.Http(code, body)
            val chunk = runCatching { json.decodeFromString(OaiChunk.serializer(), body) }
                .getOrElse { throw DshError.Parse("返回的不是对话结果：${body.take(200)}") }
            chunk.error?.let { throw DshError.Server(it.message ?: "服务端返回 error 字段") }
            val text = chunk.choices.firstOrNull()?.message?.content.orEmpty()
            val usage = chunk.usage?.toTokenUsage()
            buildString {
                if (text.isBlank()) append("接口通了但回复为空") else append("回复「${text.take(30)}」")
                if (usage != null && !usage.isEmpty) append(" · usage ${usage.total} tokens")
                else append(" · 未返回 usage")
            }
        }

        // 4) 流式对话（真正走发送消息时的那条路）
        steps += runStep(STEP_STREAM) {
            val request = ChatRequest(
                provider = provider,
                baseUrl = base,
                apiKey = apiKey,
                serverToken = serverToken,
                model = model,
                systemPrompt = "",
                history = listOf(ChatTurn(role = "user", content = "hi")),
                temperature = 0f,
                topP = 1f,
                maxTokens = 16,
                stream = true,
                reasoningEffort = ReasoningEffort.DEFAULT,
                usageInStream = provider.requestUsageInStream,
            )
            var text = ""
            var usage: TokenUsage? = null
            var failure: DshError? = null
            streamChat(request).collect { ev ->
                when (ev) {
                    is ChatEvent.Text -> if (text.length < 400) text += ev.delta
                    is ChatEvent.Usage -> usage = ev.usage
                    is ChatEvent.Failure -> failure = ev.error
                    else -> Unit
                }
            }
            failure?.let { throw it }
            if (text.isBlank()) throw DshError.Parse("连接与鉴权都正常，但流式响应里没有文本")
            buildString {
                append("流式正常，首段「${text.take(30)}」")
                if (usage != null && !usage!!.isEmpty) append(" · usage ${usage!!.total} tokens")
                else append(" · 服务端未返回 usage，用量将走本地估算")
            }
        }

        return steps
    }

    // ---------------- 诊断辅助 ----------------

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

    private suspend fun rawGet(url: String, apiKey: String): Pair<Int, String> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            Http.client.newCall(req).execute().use { r ->
                r.code to r.body?.string().orEmpty()
            }
        }

    private suspend fun rawPost(
        url: String,
        apiKey: String,
        body: String,
        accept: String,
    ): Pair<Int, String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", accept)
            .post(body.toRequestBody(Http.JSON_MEDIA))
            .build()
        Http.client.newCall(req).execute().use { r ->
            r.code to r.body?.string().orEmpty()
        }
    }

    // ------------------------------------------------------------------

    private suspend fun FlowCollector<ChatEvent>.emitStreaming(resp: Response) {
        val source = resp.body?.source()
        if (source == null) {
            emit(ChatEvent.Failure(DshError.Parse("响应体为空")))
            return
        }
        val reader = SseReader(source)
        var sawAny = false
        while (true) {
            val ev = reader.read() ?: break
            val data = ev.data.trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break

            // 注意：continue/break 不能写在 inline lambda 里，所以先取值再判断
            val chunk = runCatching { json.decodeFromString(OaiChunk.serializer(), data) }.getOrNull()
            if (chunk == null) {
                Logx.d(TAG, "跳过无法解析的 chunk: ${data.take(120)}")
                continue
            }
            sawAny = true

            chunk.error?.let {
                emit(ChatEvent.Failure(DshError.Server(it.message ?: it.code ?: "未知错误")))
                return
            }
            chunk.usage?.let { emit(ChatEvent.Usage(it.toTokenUsage())) }

            val choice = chunk.choices.firstOrNull() ?: continue
            val delta = choice.delta ?: choice.message
            delta?.reasoningText()?.takeIf { it.isNotEmpty() }
                ?.let { emit(ChatEvent.Reasoning(it)) }
            delta?.content?.takeIf { it.isNotEmpty() }
                ?.let { emit(ChatEvent.Text(it)) }

            // tool_calls 按 index 分片下发，交给上层累积
            delta?.toolCalls?.forEach { tc ->
                emit(
                    ChatEvent.ToolCallDelta(
                        index = tc.index,
                        id = tc.id,
                        name = tc.function?.name,
                        argsDelta = tc.function?.arguments,
                    )
                )
            }
            choice.finishReason?.takeIf { it.isNotBlank() }
                ?.let { emit(ChatEvent.Finish(it)) }
        }
        if (!sawAny) {
            emit(ChatEvent.Failure(DshError.Parse("服务端没有返回任何流式数据，可能未正确支持 SSE")))
            return
        }
        emit(ChatEvent.Completed)
    }

    private suspend fun FlowCollector<ChatEvent>.emitWhole(resp: Response) {
        val text = resp.body?.string().orEmpty()
        val chunk = runCatching { json.decodeFromString(OaiChunk.serializer(), text) }
            .getOrElse { throw DshError.Parse("非 JSON 响应：${text.take(200)}") }
        chunk.error?.let { throw DshError.Server(it.message ?: "未知错误") }
        val choice = chunk.choices.firstOrNull()
        val msg = choice?.message
        msg?.reasoningText()?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Reasoning(it)) }
        msg?.content?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Text(it)) }
        msg?.toolCalls?.forEach { tc ->
            emit(ChatEvent.ToolCallDelta(tc.index, tc.id, tc.function?.name, tc.function?.arguments))
        }
        choice?.finishReason?.takeIf { it.isNotBlank() }?.let { emit(ChatEvent.Finish(it)) }
        chunk.usage?.let { emit(ChatEvent.Usage(it.toTokenUsage())) }
        emit(ChatEvent.Completed)
    }

    private fun buildChatRequest(endpoint: String, r: ChatRequest, withUsage: Boolean): Request {
        val payload = buildBody(
            model = r.model,
            systemPrompt = r.systemPrompt,
            history = r.history,
            temperature = r.temperature,
            topP = r.topP,
            maxTokens = r.maxTokens,
            stream = r.stream,
            reasoningEffort = r.reasoningEffort,
            withUsage = withUsage,
            tools = r.tools,
            toolChoice = r.toolChoice,
        )
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${r.apiKey}")
            .header("Accept", if (r.stream) "text/event-stream" else "application/json")
            .post(payload.toRequestBody(Http.JSON_MEDIA))
            .build()
    }

    /** 请求体构造，诊断和正式请求共用，保证「测的就是发的」 */
    private fun buildBody(
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
        reasoningEffort: ReasoningEffort,
        withUsage: Boolean,
        tools: List<JsonObject> = emptyList(),
        toolChoice: String = "auto",
    ): String {
        val payload: JsonObject = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                if (systemPrompt.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                }
                // 出站前强制修复序列，保证 tool_calls 后面一定跟着配对的 tool 消息
                TurnSanitizer.sanitize(history).forEach { turn -> addTurn(turn) }
            }
            put("temperature", temperature.toDouble())
            put("top_p", topP.toDouble())
            put("max_tokens", maxTokens)
            put("stream", stream)
            reasoningEffort.wire?.let { put("reasoning_effort", it) }
            if (withUsage) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") { tools.forEach { add(it) } }
                put("tool_choice", toolChoice)
            }
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    /**
     * 把一条历史翻译成协议消息。三种形态：
     *  - 工具结果：role=tool + tool_call_id
     *  - 带工具调用的助手消息：content 可为 null，必须带 tool_calls 数组
     *  - 普通消息：role + content
     */
    private fun JsonArrayBuilder.addTurn(turn: ChatTurn) {
        when {
            turn.toolCallId.isNotBlank() -> addJsonObject {
                put("role", "tool")
                put("tool_call_id", turn.toolCallId)
                put("content", turn.content)
            }

            turn.toolCalls.isNotEmpty() -> addJsonObject {
                put("role", turn.role)
                put("content", if (turn.content.isBlank()) JsonNull else JsonPrimitive(turn.content))
                putJsonArray("tool_calls") {
                    turn.toolCalls.forEach { call ->
                        addJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments.ifBlank { "{}" })
                            }
                        }
                    }
                }
            }

            else -> addJsonObject {
                put("role", turn.role)
                put("content", turn.content)
            }
        }
    }

    companion object {
        private const val TAG = "OpenAI"

        const val STEP_CONFIG = "配置检查"
        const val STEP_MODELS = "模型列表"
        const val STEP_CHAT = "对话接口"
        const val STEP_STREAM = "流式输出"

        fun endpointOf(baseUrl: String, path: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return "$base/$path"
        }
    }
}
