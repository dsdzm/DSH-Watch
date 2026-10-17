package com.dshwatch.domain.agent

import com.dshwatch.domain.model.ToolCallRecord
import com.dshwatch.domain.model.ToolStatus

/**
 * 无进展检测 —— 替代「最多 20 步」这个固定上限。
 *
 * ## 为什么不再用固定步数
 *
 * 步数是**最省事**的停止条件，但它和任务的实际复杂度没有关系：
 * 一次要读 8 个文件、改 5 个文件的重构，20 步根本不够；
 * 而模型如果卡在原地，20 步全是重复调同一个工具，白白烧掉二十轮 token。
 *
 * 真正该检测的不是"走了多少步"，而是"**还在往前走吗**"。
 *
 * ## 判据
 *
 * 每步算一次「进展指纹」：成功调用的 `工具名 + 参数`，外加产物路径。
 * 指纹存在一个去重集合里，于是：
 *
 * - 逐步写 5 个不同文件 → 每步都有新指纹 → 一直算进展（**不会误杀长任务**）
 * - 同一个工具、同样的参数反复调 → 第 2 步起没有新指纹 → 累计无进展
 * - 连续 3 步无新指纹 → 判定停滞
 *
 * 另外一条独立判据：连续 2 步**全部失败**。这种步态不一定表现为"无新指纹"
 * （失败的调用根本不进指纹），但同样是卡住 —— 通常是参数写错或环境不对，重试也没用。
 *
 * 被拒绝的情况由 ChatEngine 自己处理（它要先把"被拒"回灌给模型一次），这里不重复判断。
 */
class ProgressTracker(private val stallThreshold: Int = 3) {

    /** 已经出现过的进展指纹 */
    private val seen = HashSet<String>()

    private var noProgressStreak = 0
    private var allFailedStreak = 0

    sealed interface Verdict {
        /** 有进展，继续跑 */
        data class Progress(val fresh: Int) : Verdict

        /** 本步没有新进展，但还没到阈值 */
        data class NoProgress(val streak: Int) : Verdict

        /** 判定停滞，该停了 */
        data class Stalled(val reason: String) : Verdict
    }

    fun record(records: List<ToolCallRecord>): Verdict {
        if (records.isEmpty()) return Verdict.Progress(0)

        val succeeded = records.filter { it.status == ToolStatus.SUCCESS }
        val allFailed = records.all { it.status == ToolStatus.FAILED }

        // 只有成功的调用才算"进展"：一次失败的调用即使参数全新，也没有推进任何事情
        val fingerprints = buildList {
            succeeded.forEach { call ->
                add("${call.name}:${normalize(call.arguments)}")
                if (call.artifact.isNotBlank()) add("artifact:${call.artifact}")
            }
        }
        val fresh = fingerprints.filterNot { it in seen }
        seen += fresh

        allFailedStreak = if (allFailed) allFailedStreak + 1 else 0
        if (allFailedStreak >= 2) {
            return Verdict.Stalled("连续 $allFailedStreak 步的工具调用全部失败，已停下。")
        }

        noProgressStreak = if (fresh.isEmpty()) noProgressStreak + 1 else 0
        if (noProgressStreak >= stallThreshold) {
            return Verdict.Stalled("连续 $noProgressStreak 步没有产生新结果，已停下。")
        }

        return if (fresh.isEmpty()) Verdict.NoProgress(noProgressStreak) else Verdict.Progress(fresh.size)
    }

    /**
     * 参数归一化：只用于**比较**，不要求语义等价。
     * 去掉空白就够 —— `{"a": 1}` 和 `{"a":1}` 是同一个调用。
     */
    private fun normalize(args: String): String = args.filterNot { it.isWhitespace() }
}
