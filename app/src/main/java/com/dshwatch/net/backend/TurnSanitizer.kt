package com.dshwatch.net.backend

import com.dshwatch.domain.model.ChatTurn

/**
 * 出站消息序列的最后一道防线。
 *
 * ## 为什么必须有这个
 *
 * OpenAI 兼容协议对 tool_calls 的序列有硬性要求：
 * 一条带 `tool_calls` 的 assistant 消息，**后面必须紧跟**与每个 `tool_call.id`
 * 一一对应的 `role=tool` 消息。少一条、多一条、顺序颠倒，服务端都会直接 400：
 *
 * > An assistant message with 'tool_calls' must be followed by tool messages
 * > responding to each 'tool_call.id'. (insufficient tool messages following tool_calls message)
 *
 * 这个约束在任何一层都容易被破坏 —— 历史裁剪切断了消息组、存盘恢复时没展开成
 * assistant + tool 两条、并发更新时丢了一条 tool 结果……
 * 而且破坏之后**本地完全感知不到**，只有在真机请求时才会看到 400。
 *
 * 所以这里做一次结构化的重建：不管上游给了什么，经过 [sanitize] 之后出来的序列
 * 一定满足协议要求。代价只是遍历一遍列表。
 */
object TurnSanitizer {

    fun sanitize(turns: List<ChatTurn>): List<ChatTurn> {
        val out = ArrayList<ChatTurn>(turns.size + 4)
        var i = 0
        while (i < turns.size) {
            val turn = turns[i]

            if (turn.toolCalls.isNotEmpty()) {
                // 收下紧随其后的所有 role=tool
                val following = ArrayList<ChatTurn>(turn.toolCalls.size)
                var j = i + 1
                while (j < turns.size && turns[j].toolCallId.isNotBlank()) {
                    following.add(turns[j])
                    j++
                }
                val answered = following.associateBy { it.toolCallId }

                out.add(turn)
                turn.toolCalls.forEach { call ->
                    // 缺结果的补一条占位。宁可让模型看到「结果缺失」，
                    // 也不能让整次请求因为一条 tool 消息缺失而 400。
                    out.add(
                        answered[call.id] ?: ChatTurn(
                            role = "tool",
                            content = "(工具结果缺失)",
                            toolCallId = call.id,
                        )
                    )
                }
                i = j
                continue
            }

            if (turn.toolCallId.isNotBlank()) {
                // 孤儿 tool 消息：前面的 assistant 已经不在历史里了，直接丢弃
                i++
                continue
            }

            out.add(turn)
            i++
        }
        return out
    }

    /**
     * 供断言/诊断使用：判断一组消息是否满足协议约束。
     * 单测里用它来验证「任何输入经过 sanitize 之后都合法」。
     */
    fun validate(turns: List<ChatTurn>): String? {
        var i = 0
        while (i < turns.size) {
            val turn = turns[i]
            if (turn.toolCalls.isNotEmpty()) {
                if (turn.role != "assistant") {
                    return "下标 $i：带 tool_calls 的消息 role 必须是 assistant，实际是 ${turn.role}"
                }
                val ids = turn.toolCalls.map { it.id }.toSet()
                if (ids.size != turn.toolCalls.size) {
                    return "下标 $i：tool_calls 的 id 有重复"
                }
                var j = i + 1
                val seen = mutableSetOf<String>()
                while (j < turns.size && turns[j].toolCallId.isNotBlank()) {
                    val id = turns[j].toolCallId
                    if (id !in ids) return "下标 $j：tool 消息引用了不存在的 id=$id"
                    if (!seen.add(id)) return "下标 $j：id=$id 的结果重复"
                    j++
                }
                val missing = ids - seen
                if (missing.isNotEmpty()) {
                    return "下标 $i 之后缺少 tool 消息，id=${missing.joinToString()}"
                }
                i = j
                continue
            }
            if (turn.toolCallId.isNotBlank()) {
                return "下标 $i：出现孤儿 tool 消息（前面没有对应的 assistant.tool_calls）"
            }
            i++
        }
        return null
    }
}
