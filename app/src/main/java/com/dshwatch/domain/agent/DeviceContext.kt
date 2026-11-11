package com.dshwatch.domain.agent

import com.dshwatch.domain.model.ChatTurn

/**
 * 设备操控期间的上下文折叠。
 *
 * ## 为什么只折历史，不动工具表
 *
 * 操控设备是**长轨迹**任务：一次三十步很常见。真正会随步数放大的是累积成本
 * （历史里的每一屏），而不是工具表 —— 工具表是固定成本，收窄它省下的是
 * 一次性的一万多字，代价却是模型看不见 `write_file`，做完操控想写个总结
 * 只看到「工具列表里没有」，于是向用户解释「我没法写文件」。
 *
 * 早先试过操控期间只下发 `device_*`（`DeviceToolScope.narrow`），真机上
 * 反复出现「模型以为 App 做不到」。收益远小于这种失败，已移除。
 *
 * ## 为什么不做「只发变化」
 *
 * 差分（本步只发变化的行）看起来更省，但它让每一步都依赖上一步的完整列表：
 * 模型记错一行就会按错编号，而编号对应的元素是它自己重新读出来的。
 * 这里选择「每屏自包含 + 旧屏折叠」，出错面小得多。
 */
object DeviceTurnPruner {

    /** 设备工具的统一前缀。折叠靠它识别。 */
    const val PREFIX = "device_"

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
                names[turn.toolCallId]?.startsWith(PREFIX) == true
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
