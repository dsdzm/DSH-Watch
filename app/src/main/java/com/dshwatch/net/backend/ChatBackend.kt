package com.dshwatch.net.backend

import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ReasoningEffort
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.model.TokenUsage
import kotlinx.coroutines.flow.Flow

data class ChatRequest(
    val provider: Provider,
    val baseUrl: String,
    /** OPENAI_COMPAT 用 */
    val apiKey: String = "",
    /** DSH_SERVER 用 */
    val serverToken: String = "",
    val model: String,
    val systemPrompt: String,
    /** 历史消息（支持带 tool_calls 的助手消息与 role=tool 的结果消息） */
    val history: List<ChatTurn>,
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
    val stream: Boolean,
    val reasoningEffort: ReasoningEffort,
    val usageInStream: Boolean,
    val remoteSessionId: String = "",
    /** OpenAI function-calling 格式的工具声明；为空表示不带工具（纯对话） */
    val tools: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    /** auto / none / required */
    val toolChoice: String = "auto",
) {
    val agentEnabled: Boolean get() = tools.isNotEmpty()
    val displayModel: String get() = model.ifBlank { "default" }
}

sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent

    /**
     * 工具调用的流式增量。
     * OpenAI 兼容协议里 tool_calls 是按 index 分片下发的：
     * 第一片带 id 与 function.name，后续片只带 function.arguments 的字符串片段，
     * 必须按 index 累积拼接才能得到完整参数。
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsDelta: String?,
    ) : ChatEvent

    /** 本轮结束原因：stop / length / tool_calls */
    data class Finish(val reason: String) : ChatEvent
    data class Reasoning(val delta: String) : ChatEvent
    data class Usage(val usage: TokenUsage) : ChatEvent
    /** DSH 服务器会回传自动生成的会话标题 */
    data class Title(val title: String) : ChatEvent
    /** DSH 服务器首次回答时创建的远端会话 id */
    data class RemoteSession(val id: String) : ChatEvent
    data class Failure(val error: DshError) : ChatEvent
    data object Completed : ChatEvent
}

sealed class DshError(message: String, val hint: String? = null) : Exception(message) {

    class Http(val code: Int, val body: String) : DshError("HTTP $code", httpHint(code, body))

    /**
     * 网络层异常。
     * 之前这里只保留 `cause.message`，而界面只显示 friendly（"网络不可达"），
     * 底层到底是 DNS 失败、TLS 失败还是明文被系统拦截，用户完全看不到 → 无法定位。
     * 现在把异常类名一并带上，统一由 [describeForUser] 展示。
     */
    class Network(val origin: Throwable) : DshError(
        "${origin.javaClass.simpleName}: ${origin.message ?: "无详细信息"}",
        "检查手表 Wi-Fi / 代理；若已联网，可能是地址用了明文 HTTP 被系统拦截。熄屏失败请打开「后台保持连接」",
    )

    class Auth(val detail: String) : DshError("鉴权失败", detail)

    class BadConfig(val detail: String) : DshError("配置有误", detail)

    /** detail 直接作为 message，保证即使界面不展示 hint 也能看到服务端原话 */
    class Server(val detail: String) : DshError(detail)

    class Parse(val detail: String) : DshError("响应解析失败", detail)

    object Canceled : DshError("已停止")


    val friendly: String
        get() = when (this) {
            is Http -> when (code) {
                400 -> "请求被拒绝（400）"
                401 -> "API Key 无效（401）"
                402 -> "余额不足（402）"
                403 -> "无权限（403）"
                404 -> "接口不存在（404）"
                422 -> "参数不合法（422）"
                429 -> "触发限流，请稍后重试（429）"
                in 500..599 -> "服务端故障（$code）"
                else -> "HTTP $code"
            }

            is Network -> "网络请求失败"
            is Auth -> "鉴权失败"
            is BadConfig -> "配置有误"
            is Server -> "服务端错误"
            is Parse -> "响应格式异常"
            Canceled -> "已停止"
        }

    /**
     * 给用户看的完整说明：结论 + 底层原因 + 建议。
     * 任何一层都不能省——手表上没法连电脑看日志，界面就是唯一的排查入口。
     */
    fun describeForUser(): String {
        val sb = StringBuilder(friendly)
        // Http 的 message 就是 "HTTP 400"，和 friendly 重复，跳过
        if (this !is Http) {
            val raw = message?.trim().orEmpty()
            if (raw.isNotBlank() && raw != friendly) {
                sb.append('\n').append(raw.take(300))
            }
        }
        hint?.let { sb.append('\n').append(it) }
        return sb.toString()
    }

    companion object {
        fun from(t: Throwable): DshError = when (t) {
            is DshError -> t
            is java.io.IOException -> Network(t)
            is kotlinx.serialization.SerializationException -> Parse(t.message ?: "JSON 解析失败")
            else -> DshError.Server("${t.javaClass.simpleName}: ${t.message ?: "未知错误"}")
        }
    }
}

/** 诊断结果的一步 */
data class DiagStep(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val elapsedMs: Long = 0L,
)

interface ChatBackend {
    /** @return 文本流；调用方取消协程即可中断（实现内部会 cancel 对应的 Call） */
    fun streamChat(request: ChatRequest): Flow<ChatEvent>

    /** 拉取模型列表；失败时抛 [DshError] */
    suspend fun listModels(provider: Provider, apiKey: String, serverToken: String): List<String>

    /** 连通性自检，返回一句人类可读的成功信息；失败抛 [DshError] */
    suspend fun testConnection(provider: Provider, apiKey: String, serverToken: String): String

    /**
     * 分步诊断：把「配置 → 列表 → 非流式对话 → 流式对话」每一步的真实结果都返回，
     * 不抛异常。界面上逐条展示，用来定位「测试能过但聊天失败」这类问题。
     */
    suspend fun diagnose(provider: Provider, apiKey: String, serverToken: String): List<DiagStep>

    /** 主动中断当前请求 */
    fun cancelActive()
}

internal fun Role.wireName(): String = when (this) {
    Role.SYSTEM -> "system"
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
}

private fun httpHint(code: Int, body: String): String {
    val trimmed = body.trim().take(300)
    val hint = when (code) {
        401, 403 -> "检查 API Key 是否正确、是否有多余空格"
        404 -> "Base URL 通常需要包含 /v1"
        429 -> "降低频率，或换用不限额的模型"
        else -> null
    }
    return if (hint == null) trimmed else "$hint\n$trimmed"
}
