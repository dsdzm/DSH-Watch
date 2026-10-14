package com.dshwatch.domain.agent

/**
 * 模型向用户提出的问题。
 *
 * agent 干活干到一半发现「信息不够」时的唯一出路 —— 没有这个工具，
 * 它只能靠猜，或者把问题写进正文里等你回复，而那已经是**任务结束**了。
 */
data class PendingQuestion(
    /** 关联的 tool_call id，用来把回答写回对应的工具卡片 */
    val callId: String,
    val question: String,
    /** 可选项；为空表示只能自由输入 */
    val options: List<String> = emptyList(),
    /** 是否允许在选项之外自由输入 */
    val allowFreeText: Boolean = true,
)

/**
 * `ask_user` 工具与 UI 之间的桥。
 *
 * 为什么需要它：工具集（[ToolRegistry]）是在 `AppContainer` 里构造的，
 * 而提问要等用户点按钮才可能有结果 —— 这个「等」必须发生在对话引擎里
 * （只有它持有协程作用域和取消逻辑）。两边构造顺序相反，所以用一个
 * 可后置注册的通道把它们接起来，而不是让工具反向持有引擎。
 */
class UserPromptChannel {

    /** 由对话引擎在构造时注册；未注册（如单测环境）时提问会直接失败而不是挂死 */
    @Volatile
    var handler: (suspend (PendingQuestion) -> String?)? = null

    suspend fun ask(question: PendingQuestion): String? = handler?.invoke(question)
}
