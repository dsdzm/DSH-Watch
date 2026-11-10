package com.dshwatch.domain.agent

import com.dshwatch.domain.model.ChatTurn

/**
 * 设备操控期间的两处上下文瘦身。
 *
 * ## 为什么单独拎出来
 *
 * 操控设备是**长轨迹**任务：一次三十步很常见。每一步的固定成本（工具表、
 * 系统提示）与累积成本（历史里的每一屏）都会随步数放大 —— 三十步就是三十次
 * 重复发送。真正需要模型记住的只有「我刚做了什么」和「现在这一屏是什么」，
 * 三步以前的完整控件列表既没用，还会让模型照着旧编号去点。
 *
 * ## 两件事
 *
 *  - [DeviceToolScope]：操控期间只开放 `device_*`，其余工具的描述与 schema
 *    不再随每轮请求发出去；
 *  - [DeviceTurnPruner]：历史里较早的读屏结果只留动作那一行，屏幕正文折叠掉。
 *
 * ## 为什么不做「只发变化」
 *
 * 差分（本步只发变化的行）看起来更省，但它让每一步都依赖上一步的完整列表：
 * 模型记错一行就会按错编号，而编号对应的元素是它自己重新读出来的。
 * 这里选择「每屏自包含 + 旧屏折叠」，出错面小得多。
 */
object DeviceToolScope {

    /** 设备工具的统一前缀。收窄与折叠都靠它识别。 */
    const val PREFIX = "device_"

    /**
     * 只留设备工具。
     *
     * 一个都不剩时**原样返回**：空工具面会让模型直接放弃，
     * 表现为「这轮它只回一段文字，什么都不做」。
     */
    fun narrow(tools: List<AgentTool>): List<AgentTool> {
        val device = tools.filter { it.name.startsWith(PREFIX) }
        return device.ifEmpty { tools }
    }
}

object DeviceTurnPruner {

    /** 保留最近这么多条屏幕全文。再往前只有「做过什么」还有价值。 */
    const val KEEP = 3

    /** 内容短于这个长度就不值得折叠 —— 省不下几个字，反而多一行占位。 */
    private const val MIN_BODY = 120

    /** 动作结果那一行最多这么长才保留（`已点击「发送」` 通常十几个字）。 */
    private const val HEAD_CAP = 80

    /**
     * 折叠较早的设备读屏结果。
     *
     * 只动 `role=tool` 且工具名以 `device_` 开头的消息；其余原样返回。
     * 返回的是**新列表**，不改动入参 —— 调用方还要继续往 turns 里追加。
     */
    fun prune(turns: List<ChatTurn>, keep: Int = KEEP): List<ChatTurn> {
        if (turns.isEmpty()) return turns

        // tool 消息只有 toolCallId，工具名在助手消息的 toolCalls 里
        val names = HashMap<String, String>(turns.size)
        for (turn in turns) {
            for (call in turn.toolCalls) names[call.id] = call.name
        }

        val deviceIndexes = turns.indices.filter { i ->
            val turn = turns[i]
            turn.role == "tool" &&
                names[turn.toolCallId]?.startsWith(DeviceToolScope.PREFIX) == true
        }
        if (deviceIndexes.size <= keep) return turns

        val out = turns.toMutableList()
        val foldUntil = deviceIndexes.size - keep
        for ((seq, idx) in deviceIndexes.withIndex()) {
            if (seq >= foldUntil) break
            out[idx] = fold(turns[idx], seq + 1)
        }
        return out
    }

    /** 折叠标记。已折叠过的内容再折一次会把动作那一行也吃掉，所以要认它。 */
    private const val MARK = "已过时"

    private fun fold(turn: ChatTurn, seq: Int): ChatTurn {
        val text = turn.content
        if (text.length <= MIN_BODY || text.contains(MARK)) return turn

        // 动作结果形如「已点击「发送」\n\n<屏幕>」：动作那一行要留
        val head = text.substringBefore("\n\n")
        val body = text.substringAfter("\n\n", "")
        val omitted = if (body.isEmpty()) text.length else body.length
        val mark = "〔第 $seq 次读屏的屏幕已过时，省略 $omitted 字〕"
        val kept = if (body.isNotEmpty() && head.length <= HEAD_CAP) "$head\n$mark" else mark
        return turn.copy(content = kept)
    }
}
