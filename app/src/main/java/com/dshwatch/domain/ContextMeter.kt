package com.dshwatch.domain

import com.dshwatch.domain.model.ChatTurn

/**
 * 上下文构成分析。
 *
 * ## 它对应生态里的哪两个插件
 *
 * DSH 上最热的两类插件（`billion-context`、`dsh-context`）做的是两件事：
 * **压缩**上下文，以及**看清**上下文花在哪。本项目的前半段其实已经有了：
 *
 * | 能力 | 本项目的实现 |
 * | --- | --- |
 * | 工具结果不占满上下文且可找回 | `ToolResultCompactor`（落盘 + 摘要 + 路径） |
 * | 短结果的冗余裁剪 | `TokenSaverPlugin`（无损折叠） |
 * | 超窗口时按预算装填 | `TokenEstimator.trimToBudget` |
 *
 * 缺的是后半段：**用户在表上看不见这些机制在干什么**。看不到就无从判断
 * 「这轮为什么这么贵」「该不该开新会话」「工具结果是不是把窗口吃掉了」。
 * 所以这里不重做压缩，只做「看清」。
 *
 * ## 为什么不直接抄插件的做法
 *
 * 那两个插件的界面是桌面面板：多列柱状图、hover 提示、按 token 排序的明细表。
 * 手表上没有 hover、一屏十来行，照抄只会得到一张读不懂的图。这里改成
 * 「一根占比条 + 五行数字 + 一句结论」，并且结论要能直接指导动作：
 * 是该开新会话（[Level.HOT]），还是该折叠工具结果（[Insight.reclaimable] 大）。
 */
object ContextMeter {

    /** 上下文里的一段。顺序即展示顺序 */
    enum class Section { SYSTEM, USER, ASSISTANT, TOOL_ARGS, TOOL_RESULTS }

    /**
     * 占用档位。阈值不是拍脑袋：模型的真实窗口在 [Insight.usable] 之外
     * 还要留出输出空间，而请求接近窗口上限时服务端更容易直接 400
     * 或截断回复，所以在 85% 就该提醒，而不是 100%。
     */
    enum class Level { OK, WARN, HOT, OVER }

    data class Part(val section: Section, val tokens: Int)

    data class Insight(
        val parts: List<Part>,
        val total: Int,
        /** 可用预算 = 窗口 − 预留输出 */
        val usable: Int,
        val window: Int,
        val maxOutput: Int,
        /** 实际发出去的条数（裁剪之后） */
        val turns: Int,
        /**
         * 折叠工具结果还能省下多少 token。
         *
         * 只算**能省的那部分**（超出单条上限的部分），而且排除 `read_file` ——
         * 那个工具读的就是内容本身，折叠它等于让模型白读一次。
         */
        val reclaimable: Int,
    ) {
        val ratio: Float get() = if (usable <= 0) 0f else total.toFloat() / usable

        val level: Level get() = when {
            usable <= 0 -> Level.OVER
            ratio >= 1f -> Level.OVER
            ratio >= 0.85f -> Level.HOT
            ratio >= 0.70f -> Level.WARN
            else -> Level.OK
        }

        /** 最占地方的那一段。全是 0 时返回 null（空会话） */
        val heaviest: Part? get() = parts.filter { it.tokens > 0 }.maxByOrNull { it.tokens }

        fun share(section: Section): Float {
            if (total <= 0) return 0f
            return (parts.firstOrNull { it.section == section }?.tokens ?: 0).toFloat() / total
        }
    }

    /**
     * 分析一轮请求将要发出去的上下文。
     *
     * [turns] 必须是**裁剪之后**、实际进入请求的那一份 —— 用完整历史算出来的
     * 数字会比真实占用大得多，反而误导用户去开新会话。
     */
    fun analyze(
        systemPrompt: String,
        turns: List<ChatTurn>,
        contextWindow: Int,
        maxOutputTokens: Int,
    ): Insight {
        var user = 0
        var assistant = 0
        var toolArgs = 0
        var toolResults = 0
        var reclaimable = 0

        // toolCallId → 工具名。判断 read_file 例外要用，也用于给「可省」定量
        val nameById = HashMap<String, String>()

        for (turn in turns) {
            if (turn.toolCallId.isNotBlank()) {
                // role = tool：这是工具结果
                val tokens = TokenEstimator.estimatePair(turn.role, turn.content)
                toolResults += tokens
                val toolName = nameById[turn.toolCallId]
                if (toolName != "read_file") {
                    reclaimable += foldableTokens(turn.content)
                }
                continue
            }

            when (turn.role) {
                "user" -> user += TokenEstimator.estimatePair(turn.role, turn.content) +
                    tokenOfImages(turn)

                "assistant" -> {
                    assistant += TokenEstimator.estimatePair(turn.role, turn.content)
                    for (call in turn.toolCalls) {
                        nameById[call.id] = call.name
                        toolArgs += TokenEstimator.estimate(call.name) +
                            TokenEstimator.estimate(call.arguments)
                    }
                }

                // system 段由系统提示统一计入，历史里不会出现；
                // 万一有，也不该算到用户头上
                "system" -> assistant += TokenEstimator.estimatePair(turn.role, turn.content)

                else -> user += TokenEstimator.estimatePair(turn.role, turn.content)
            }
        }

        // +2 是 chat 模板的固定开销，和 TokenEstimator.estimateMessages 保持一致
        val system = if (systemPrompt.isBlank()) 0 else TokenEstimator.estimate(systemPrompt) + 2

        val parts = listOf(
            Part(Section.SYSTEM, system),
            Part(Section.USER, user),
            Part(Section.ASSISTANT, assistant),
            Part(Section.TOOL_ARGS, toolArgs),
            Part(Section.TOOL_RESULTS, toolResults),
        )
        val total = parts.sumOf { it.tokens }
        val usable = (contextWindow - maxOutputTokens).coerceAtLeast(0)

        return Insight(
            parts = parts,
            total = total,
            usable = usable,
            window = contextWindow,
            maxOutput = maxOutputTokens,
            turns = turns.size,
            // 可省的不可能超过工具结果本身
            reclaimable = reclaimable.coerceAtMost(toolResults),
        )
    }

    /**
     * 一条工具结果折叠后能省多少。
     *
     * 与 `ToolResultCompactor.THRESHOLD` / `TokenSaverPlugin.TARGET_CHARS` 对齐：
     * 超过阈值的那部分才是真正会被折掉的内容，阈值以内本来就不动。
     */
    private fun foldableTokens(content: String): Int {
        val threshold = com.dshwatch.domain.agent.ToolResultCompactor.THRESHOLD
        if (content.length <= threshold) return 0
        return TokenEstimator.estimate(content.substring(threshold))
    }

    /**
     * 图片的体积。
     *
     * 视觉模型的图片按像素切块计费，本地算不准；但**完全不计**会让用户在
     * 发了几张图之后看到一个明显偏小的数字，进而误判还有余量。
     * 按常见实现（每张图 512×512 级别约 300–400 token）取一个保守下界。
     */
    private fun tokenOfImages(turn: ChatTurn): Int = turn.images.size * IMAGE_TOKEN_ESTIMATE

    const val IMAGE_TOKEN_ESTIMATE = 320
}

/**
 * 带会话标识的上下文快照。
 *
 * 为什么需要外层这个 id：`ChatEngine` 里切换会话有十来处赋值点，
 * 在每一处都清空快照既容易漏、又容易和「正在发送」的时序打架。
 * 带上会话 id，界面读的时候比一下就行 —— 显示的前提是「这个数字属于
 * 你当前看的这个会话」，别的会话的数字宁可空着也不误导。
 */
data class ContextSnapshot(
    val conversationId: String,
    val insight: ContextMeter.Insight,
)
