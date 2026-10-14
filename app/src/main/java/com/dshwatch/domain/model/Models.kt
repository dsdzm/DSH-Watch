package com.dshwatch.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role(val wire: String, val label: String) {
    SYSTEM("system", "系统"),
    USER("user", "我"),
    ASSISTANT("assistant", "DSH");

    companion object {
        fun fromWire(w: String?): Role = when (w?.lowercase()) {
            "system", "developer" -> SYSTEM
            "user" -> USER
            else -> ASSISTANT
        }
    }
}

@Serializable
enum class MessageStatus {
    PENDING,
    STREAMING,
    DONE,
    STOPPED,
    ERROR,
}

/** 一次请求/回答的 token 计量。字段名对齐 OpenAI 兼容协议的 usage 结构。 */
@Serializable
data class TokenUsage(
    val prompt: Int = 0,
    val completion: Int = 0,
    val reasoning: Int = 0,
    val cached: Int = 0,
) {
    val total: Int get() = prompt + completion

    val isEmpty: Boolean get() = prompt == 0 && completion == 0

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        prompt = prompt + other.prompt,
        completion = completion + other.completion,
        reasoning = reasoning + other.reasoning,
        cached = cached + other.cached,
    )

    companion object {
        val ZERO = TokenUsage()
    }
}

/** 一次工具调用的完整生命周期 */
@Serializable
enum class ToolStatus {
    /** 模型已请求，尚未处理 */
    PENDING,

    /** 等待用户审批 */
    AWAITING_APPROVAL,

    /** 执行中 */
    RUNNING,

    SUCCESS,
    FAILED,

    /** 用户拒绝执行 */
    DENIED,

    /** 因为停止 / 步数超限被跳过 */
    SKIPPED,
}

/**
 * 工具调用记录。
 * 作为 [ChatMessage.toolCalls] 挂在助手消息上，同时也会被翻译成协议里的
 * assistant.tool_calls 与 role=tool 两种消息发回模型。
 */
@Serializable
data class ToolCallRecord(
    /** 模型给出的调用 id，回传 role=tool 时必须原样带回去 */
    val id: String,
    val name: String,
    /** 模型给的原始 JSON 字符串（流式拼接出来的），回传时要原样回去 */
    val arguments: String = "",
    val status: ToolStatus = ToolStatus.PENDING,
    /** 执行结果（已截断），回传给模型的内容 */
    val result: String = "",
    /** 界面上展示的一行参数摘要 */
    val summary: String = "",
    val startedAt: Long = 0L,
    val elapsedMs: Long = 0L,
    /**
     * 这次调用产出的文件（工作空间内相对路径）。
     * 目前只有 build_project 会填；界面据此渲染成可打开/导出的「产物卡片」。
     */
    val artifact: String = "",
) {
    val isSettled: Boolean
        get() = status == ToolStatus.SUCCESS || status == ToolStatus.FAILED ||
            status == ToolStatus.DENIED || status == ToolStatus.SKIPPED
}

/**
 * 发给模型的一条历史消息。
 * 普通文本 / 带工具调用的助手消息 / 工具执行结果，三种形态用同一个结构表达。
 */
data class ChatTurn(
    val role: String,
    val content: String = "",
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val toolCallId: String = "",
) {
    val isEmpty: Boolean get() = content.isBlank() && toolCalls.isEmpty()
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String = "",
    /** DeepSeek-R1 / o 系列的思维链内容，单独存放便于折叠展示 */
    val reasoning: String = "",
    val createdAt: Long = 0L,
    val status: MessageStatus = MessageStatus.DONE,
    val model: String = "",
    val providerId: String = "",
    val usage: TokenUsage? = null,
    val error: String = "",
    /** 触发本条回答的用户消息 id，用于「重新生成」 */
    val parentId: String = "",
    /** Agent 模式下这一轮发起的工具调用 */
    val toolCalls: List<ToolCallRecord> = emptyList(),
    /** Agent 模式下的第几步，从 1 开始；0 表示普通对话 */
    val stepIndex: Int = 0,
) {
    val isStreaming: Boolean get() = status == MessageStatus.STREAMING
    val hasReasoning: Boolean get() = reasoning.isNotBlank()
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

@Serializable
data class Conversation(
    val id: String,
    val title: String = "新对话",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val providerId: String = "",
    val model: String = "",
    val presetId: String = "",
    val usage: TokenUsage = TokenUsage.ZERO,
    val messageCount: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** DSH 服务器模式下的远端会话 id；直连模式为空 */
    val remoteSessionId: String = "",
    /**
     * 本会话的工作目录（相对于工作空间根），例如 `progress/a1b2c3`。
     *
     * agent 的所有文件操作都相对它解析 —— 这样多个会话的产物互不干扰，
     * 也就不会再出现「上个会话留下的 index.html 被这个会话的 build 捡走」。
     * 空串表示直接用工作空间根目录（老会话就是这种，保持兼容）。
     */
    val workDir: String = "",
) {
    val displayTitle: String get() = title.ifBlank { "新对话" }
}
