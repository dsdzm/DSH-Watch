package com.dshwatch.domain

/**
 * 一次性文本请求的参数。
 *
 * 用途：长期记忆压缩、标题生成这类「只要一段纯文本、不需要工具、不进历史」的调用。
 * 单独定义而不是复用 ChatRequest，是为了让调用方不用关心 provider / token / stream
 * 这些它本来就取不到的字段 —— 那些由 ChatEngine 在内部补齐。
 */
data class ResponseProbe(
    val systemPrompt: String,
    val userText: String,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 800,
)

/**
 * 一次后台请求实际要打给谁。
 *
 * 存在的理由是「别猜」。摘要这类请求以前只拿 `provider.defaultModel`，
 * 而用户聊天用的是会话自己的模型 —— 两者不一致时，聊天好好的、摘要却
 * 打到一个不存在的模型名上，回来一片空白，界面只能说「模型没有返回内容」。
 * 现在把「哪家、哪个模型、用的哪把 Key」一起算好，既能照着发请求，
 * 也能在失败时原样告诉用户到底是哪个模型不对。
 */
data class BriefTarget(
    val provider: com.dshwatch.domain.model.Provider,
    val model: String,
    val apiKey: String = "",
    val serverToken: String = "",
) {
    /** 「glm-4.6 @ 智谱」这种写法，给用户看的一句话 */
    val label: String get() = "$model @ ${provider.name}"
}
