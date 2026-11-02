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
