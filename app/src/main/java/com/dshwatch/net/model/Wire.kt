package com.dshwatch.net.model

import com.dshwatch.domain.model.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容协议的 wire 结构。
 * 用 ignoreUnknownKeys 容忍各家中转站多出来的字段；
 * 这里只声明我们真正会读的字段，避免手表上无谓的反射开销。
 */

@Serializable
data class OaiDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("reasoning") val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OaiToolCall>? = null,
) {
    /** 兼容不同厂商对思维链字段的命名 */
    fun reasoningText(): String? = reasoningContent ?: reasoning
}

@Serializable
data class OaiToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OaiFunctionCall? = null,
)

@Serializable
data class OaiFunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class OaiChoice(
    val index: Int = 0,
    val delta: OaiDelta? = null,
    val message: OaiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OaiPromptDetails(
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
)

@Serializable
data class OaiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** DeepSeek 上下文档位命中 */
    @SerialName("prompt_cache_hit_tokens") val cacheHit: Int = 0,
    @SerialName("prompt_tokens_details") val promptDetails: OaiPromptDetails? = null,
) {
    fun toTokenUsage(): TokenUsage = TokenUsage(
        prompt = promptTokens,
        completion = completionTokens,
        cached = maxOf(cacheHit, promptDetails?.cachedTokens ?: 0),
    )
}

@Serializable
data class OaiErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

@Serializable
data class OaiChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OaiChoice> = emptyList(),
    val usage: OaiUsage? = null,
    val error: OaiErrorBody? = null,
)

@Serializable
data class OaiModelEntry(val id: String = "")

@Serializable
data class OaiModelList(val data: List<OaiModelEntry> = emptyList())

// ---------------- DSH 服务器（dsh-workspace 远程访问协议） ----------------

@Serializable
data class DshPairRequest(
    val code: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class DshPairResponse(
    val token: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0,
    val scopes: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class DshSessionRef(
    val id: String = "",
    val title: String = "",
    val workspace: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0,
)

@Serializable
data class DshSendRequest(
    val content: String,
    val model: String = "",
    val stream: Boolean = true,
    /** read / write / full，对齐 DSH 的权限档位 */
    val permission: String = "full",
)

@Serializable
data class DshStreamEvent(
    val type: String = "",
    val delta: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val title: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val usage: OaiUsage? = null,
    val error: String? = null,
    val message: String? = null,
) {
    fun reasoningText(): String? = reasoningContent ?: reasoning
}
