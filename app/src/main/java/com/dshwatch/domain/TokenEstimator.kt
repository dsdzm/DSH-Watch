package com.dshwatch.domain

import kotlin.math.ceil

/**
 * 本地 token 估算器。
 *
 * 用途：
 *  1) 上下文体量控制（决定丢弃多少历史消息）；
 *  2) 某些中转站 / 自建服务不返回 usage 时，作为兜底计量。
 *
 * 算法刻意保持轻量（手表 CPU 弱，且流式过程中会频繁调用）：
 *  - CJK 汉字/假名/韩文 ≈ 1 token / 字
 *  - 拉丁字母与数字 ≈ 1 token / 4 字符
 *  - 空白与 ASCII 标点 ≈ 1 token / 4 字符
 *  - 其他（emoji、符号、俄文等）≈ 1 token / 2 字符
 */
object TokenEstimator {

    private const val PER_MESSAGE_OVERHEAD = 4

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0.0
        var latin = 0.0
        var other = 0.0
        for (ch in text) {
            when {
                isCjk(ch) -> cjk += 1.0
                ch.code < 128 -> latin += 1.0
                else -> other += 1.0
            }
        }
        val tokens = cjk + latin / 4.0 + other / 2.0
        return if (tokens < 1.0) 1 else ceil(tokens).toInt()
    }

    fun estimatePair(role: String, content: String): Int =
        estimate(content) + estimate(role) + PER_MESSAGE_OVERHEAD

    /** 估算一组 (role, content) 的总体量 */
    fun estimateMessages(messages: List<Pair<String, String>>): Int =
        messages.sumOf { estimatePair(it.first, it.second) } + 2

    /** 估算一轮对话的量：正文 + 工具参数 + 工具结果都要算进去，否则会低估上下文 */
    fun estimateTurn(turn: com.dshwatch.domain.model.ChatTurn): Int {
        var total = estimatePair(turn.role, turn.content)
        turn.toolCalls.forEach { call ->
            total += estimate(call.name) + estimate(call.arguments) + estimate(call.result) + 8
        }
        return total
    }

    fun estimateTurns(turns: List<com.dshwatch.domain.model.ChatTurn>): Int =
        turns.sumOf { estimateTurn(it) } + 2

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) ||   // 中日韩统一表意
            (c in 0x3400..0x4DBF) ||      // 扩展 A
            (c in 0xF900..0xFAFF) ||      // 兼容表意
            (c in 0x3040..0x30FF) ||      // 平假名 / 片假名
            (c in 0xAC00..0xD7AF) ||      // 韩文音节
            (c in 0x3000..0x303F)         // CJK 符号与标点
    }

    /**
     * 按上下文预算裁剪历史。
     *
     * ⚠️ 这里有一个**必须**遵守的协议约束：
     * `assistant.tool_calls` 消息后面必须紧跟与每个 tool_call.id 对应的 `role=tool` 消息，
     * 少了或者顺序错了，服务端会直接返回 400
     * （"An assistant message with 'tool_calls' must be followed by tool messages..."）。
     *
     * 所以裁剪**不能按单条消息来**，必须把
     *   [assistant(tool_calls), tool, tool, ...]
     * 当作一个不可分割的原子组，整组保留或整组丢弃。
     *
     * 另外，孤立的 tool 消息（前面的 assistant 已经被丢掉）必须**直接剔除**，
     * 否则同样是非法序列。
     *
     * @param fullContext 完整上下文模式：窗口装得下就**全量**发送，一条不裁；
     *   只有估算量超出模型真实窗口（服务端必然 400）时才退化为按预算装填。
     *   这是用户明确要的行为 ——「不要截断上下文，即使导致设备卡顿也不要」：
     *   模型看不到自己前面做过什么，长任务里就会原地打转。
     */
    fun trimToBudget(
        systemTokens: Int,
        history: List<com.dshwatch.domain.model.ChatTurn>,
        contextWindow: Int,
        maxOutputTokens: Int,
        reserveRatio: Double = 0.75,
        maxTurns: Int = Int.MAX_VALUE,
        keepFirstUserTurn: Boolean = true,
        fullContext: Boolean = false,
    ): List<com.dshwatch.domain.model.ChatTurn> {
        val groups = groupsOf(history)

        if (fullContext) {
            // 唯一允许的「裁剪」是超出模型真实窗口 —— 那已经不是体验问题，
            // 而是请求必然失败。此时退化为按预算装填，保住基本可用。
            val limit = contextWindow - maxOutputTokens
            val total = groups.sumOf { g -> g.sumOf { estimateTurn(it) } } + systemTokens
            if (limit > 0 && total <= limit) return groups.flatten()
            return packFromEnd(groups, limit, keepFirstUserTurn)
        }

        val budget = (contextWindow * reserveRatio).toInt() - maxOutputTokens - systemTokens
        if (budget <= 0) {
            // 预算为负时至少给一条最近的合法内容，避免发出空 messages
            return sanitize(history.takeLast(2))
        }
        return packFromEnd(groups, budget, keepFirstUserTurn, maxTurns)
    }

    /**
     * 把历史切成原子组：
     *  - `assistant(tool_calls)` 连同后面紧跟的所有 `role=tool` 消息为一组；
     *  - 孤立的 tool 消息（前面的 assistant 不在）直接剔除。
     */
    private fun groupsOf(
        history: List<com.dshwatch.domain.model.ChatTurn>,
    ): List<List<com.dshwatch.domain.model.ChatTurn>> {
        val groups = ArrayList<List<com.dshwatch.domain.model.ChatTurn>>()
        var index = 0
        while (index < history.size) {
            val turn = history[index]
            when {
                turn.toolCalls.isNotEmpty() -> {
                    val group = ArrayList<com.dshwatch.domain.model.ChatTurn>(4)
                    group.add(turn)
                    var j = index + 1
                    while (j < history.size && history[j].toolCallId.isNotBlank()) {
                        group.add(history[j])
                        j++
                    }
                    groups.add(group)
                    index = j
                }

                turn.toolCallId.isNotBlank() -> {
                    // 孤儿 tool 消息：前面的 assistant 不在，直接丢
                    index++
                }

                else -> {
                    groups.add(listOf(turn))
                    index++
                }
            }
        }
        return groups
    }

    /** 从最后一组往前装，装不下为止；可保留首条用户消息 */
    private fun packFromEnd(
        groups: List<List<com.dshwatch.domain.model.ChatTurn>>,
        budget: Int,
        keepFirstUserTurn: Boolean,
        maxTurns: Int = Int.MAX_VALUE,
    ): List<com.dshwatch.domain.model.ChatTurn> {
        val kept = ArrayList<com.dshwatch.domain.model.ChatTurn>()
        var used = 0
        var keptGroups = 0
        var cursor = groups.lastIndex
        while (cursor >= 0) {
            if (keptGroups >= maxTurns) break
            val group = groups[cursor]
            val cost = group.sumOf { estimateTurn(it) }
            if (used + cost > budget && kept.isNotEmpty()) break
            used += cost
            // addAll(0, ...) 是「整组按原顺序插到最前面」，不会像逐个 addFirst 那样把组内顺序弄反
            kept.addAll(0, group)
            keptGroups++
            cursor--
        }

        // 保底：把**最初的任务描述**留住。
        //
        // 长任务里最危险的不是「忘了刚才那步」，而是「忘了要干什么」——
        // 从后往前装会把第一条用户消息最先丢掉，模型随后就会开始瞎补目标。
        // 第一条消息通常只有几十个字，留在最前面几乎不占预算。
        if (keepFirstUserTurn) {
            val firstUser = groups.firstOrNull {
                it.size == 1 && it[0].role == "user" && it[0].toolCallId.isBlank() && it[0].toolCalls.isEmpty()
            }?.firstOrNull()
            if (firstUser != null && kept.none { it === firstUser }) {
                val cost = estimateTurn(firstUser)
                if (used + cost <= budget || kept.isEmpty()) {
                    kept.add(0, firstUser)
                    used += cost
                }
            }
        }
        return kept
    }

    /** 兜底清洗：保证返回的历史本身是合法的协议序列 */
    private fun sanitize(
        history: List<com.dshwatch.domain.model.ChatTurn>,
    ): List<com.dshwatch.domain.model.ChatTurn> = groupsOf(history).flatten()
}
