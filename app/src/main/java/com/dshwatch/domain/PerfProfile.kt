package com.dshwatch.domain

import androidx.annotation.VisibleForTesting
import com.dshwatch.domain.model.PerfMode

enum class DeviceTier { LOW, MID, HIGH }

/**
 * 设备性能档。
 *
 * 档位按机器的真实内存与核心数分，**不按「是不是手表」**。同一份 APK 要跑在
 * 手机和手表上，而 4+64 的手表比不少低端手机还强 —— 按设备形态一刀切只会
 * 把能跑满的机器压成幻灯片。
 *
 * 仍然保留的优化手段是「少渲染、少重排、少唤醒」，只是阈值按实测放宽了：
 *  - maxRenderedMessages：LazyColumn 只保留最近 N 条在组合树里；
 *  - streamThrottleMs：流式增量合并刷新间隔，避免每来一个 token 就重组一次；
 *  - markdownMaxBlocks：解析与渲染的块数上限，超出的折叠；
 *  - enableAnimations：全局关掉非必要动画与光标闪烁；
 *  - enableCodeHighlight：轻量代码着色会带来额外的 AnnotatedString 构建。
 *
 * 这些数字是「兜底」而不是「目标」：正常会话远到不了这些上限，只有几百轮的
 * 长会话才会碰到。把它们调小换不来可感知的流畅度，只会让长对话读到一半就断。
 */
data class PerfProfile(
    val tier: DeviceTier,
    val mode: PerfMode,
    val maxRenderedMessages: Int,
    val streamThrottleMs: Long,
    val markdownMaxBlocks: Int,
    val maxCodeBlockLines: Int,
    val maxPinnedReasoningChars: Int,
    val enableAnimations: Boolean,
    val enableCodeHighlight: Boolean,
    val enableHaptics: Boolean,
    /** 打开会话时一次性从磁盘读入的消息条数 */
    val historyLoadLimit: Int,
    /** 单次请求携带的历史消息条数上限（超出则从最旧开始丢弃） */
    val requestHistoryLimit: Int,
)

object PerfProfiles {

    fun of(mode: PerfMode, tier: DeviceTier): PerfProfile {
        val effectiveTier = if (mode == PerfMode.POWER_SAVE) DeviceTier.LOW else tier
        val base = when (effectiveTier) {
            DeviceTier.LOW -> PerfProfile(
                tier = DeviceTier.LOW,
                mode = mode,
                maxRenderedMessages = 48,
                streamThrottleMs = 160L,
                markdownMaxBlocks = 240,
                maxCodeBlockLines = 60,
                maxPinnedReasoningChars = 8_000,
                enableAnimations = false,
                enableCodeHighlight = false,
                enableHaptics = true,
                historyLoadLimit = 120,
                requestHistoryLimit = 24,
            )

            DeviceTier.MID -> PerfProfile(
                tier = DeviceTier.MID,
                mode = mode,
                maxRenderedMessages = 140,
                streamThrottleMs = 90L,
                markdownMaxBlocks = 700,
                maxCodeBlockLines = 140,
                maxPinnedReasoningChars = 24_000,
                enableAnimations = true,
                enableCodeHighlight = true,
                enableHaptics = true,
                historyLoadLimit = 300,
                requestHistoryLimit = 32,
            )

            DeviceTier.HIGH -> PerfProfile(
                tier = DeviceTier.HIGH,
                mode = mode,
                maxRenderedMessages = 320,
                streamThrottleMs = 50L,
                markdownMaxBlocks = 2_000,
                maxCodeBlockLines = 320,
                maxPinnedReasoningChars = 64_000,
                enableAnimations = true,
                enableCodeHighlight = true,
                enableHaptics = true,
                historyLoadLimit = 800,
                requestHistoryLimit = 64,
            )
        }

        return when (mode) {
            PerfMode.POWER_SAVE -> base.copy(
                maxRenderedMessages = minOf(base.maxRenderedMessages, 24),
                streamThrottleMs = maxOf(base.streamThrottleMs, 260L),
                enableAnimations = false,
                enableCodeHighlight = false,
                historyLoadLimit = minOf(base.historyLoadLimit, 40),
            )

            PerfMode.BALANCED -> base.copy(
                enableCodeHighlight = false,
                streamThrottleMs = maxOf(base.streamThrottleMs, 110L),
            )

            PerfMode.PERFORMANCE -> base.copy(
                maxRenderedMessages = maxOf(base.maxRenderedMessages, 120),
                streamThrottleMs = 60L,
                enableAnimations = true,
                enableCodeHighlight = true,
            )

            PerfMode.AUTO -> base
        }
    }
}

/**
 * 设备分级。
 *
 * ⚠️ 这里刻意**不再**按「是不是手表」一刀切降级。
 * 4GB RAM + 1200mAh 这类手表（4+64 配置）实际比很多低端手机还强，
 * 旧逻辑用 memoryClass + isWatch + sdkInt 惩罚，会把它误判成低功耗档，
 * 白白关掉语法着色、把渲染条数砍到 30 条。
 *
 * 现在只看两个硬指标：**总内存** 与 **CPU 核心数**。
 *   4GB + 4 核 → HIGH      1GB + 4 核 → MID
 *   2GB + 8 核 → HIGH      512MB + 4 核 → LOW
 */
object PerfDetector {

    @VisibleForTesting
    fun classify(
        isLowRamDevice: Boolean,
        totalMemMb: Int,
        cores: Int,
    ): DeviceTier {
        if (isLowRamDevice || totalMemMb <= 512) return DeviceTier.LOW

        // 低于 1GB 一律 LOW，不再往下算分。
        //
        // 原来的写法是「512MB 以下才强制 LOW，其余靠 mem+cores 打分」，
        // 于是 768MB / 4 核 会得 0+1=1 分落到 LOW —— 碰巧是对的。
        // 但这条规则没有下限保护，谁哪天把打分权重调一下就会翻车。
        //
        // 直接写死下限：800MB 以下的机器上，光是 4 张内置角色卡 + 一份设置
        // 就可能把 Java 堆顶到上限（实测有一台定制设备 maxMemory() 只有 192MB），
        // 在那上面谈「渲染多少条消息」已经没有意义，先保命。
        if (totalMemMb < 800) return DeviceTier.LOW

        var score = 0
        score += when {
            totalMemMb >= 3072 -> 3
            totalMemMb >= 1536 -> 2
            totalMemMb >= 1024 -> 1
            else -> 0
        }
        score += when {
            cores >= 8 -> 3
            cores >= 6 -> 2
            cores >= 4 -> 1
            else -> 0
        }

        return when {
            score >= 4 -> DeviceTier.HIGH
            score >= 2 -> DeviceTier.MID
            else -> DeviceTier.LOW
        }
    }
}
