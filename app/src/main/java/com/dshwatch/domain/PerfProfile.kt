package com.dshwatch.domain

import androidx.annotation.VisibleForTesting
import com.dshwatch.domain.model.PerfMode

enum class DeviceTier { LOW, MID, HIGH }

/**
 * 手表性能档。
 *
 * 手表的问题不是算力不够，而是：内存 512MB~1GB、屏幕 1.4 寸、电池 300~600mAh。
 * 因此最主要的优化手段是「少渲染、少重排、少唤醒」：
 *  - maxRenderedMessages：LazyColumn 只保留最近 N 条在组合树里；
 *  - streamThrottleMs：流式增量合并刷新间隔，避免每来一个 token 就重组一次；
 *  - markdownMaxBlocks：解析与渲染的块数上限，超出的折叠；
 *  - enableAnimations：全局关掉非必要动画与光标闪烁；
 *  - enableCodeHighlight：轻量代码着色会带来额外的 AnnotatedString 构建，低端机关闭。
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
                maxRenderedMessages = 30,
                streamThrottleMs = 220L,
                markdownMaxBlocks = 120,
                maxCodeBlockLines = 40,
                maxPinnedReasoningChars = 4_000,
                enableAnimations = false,
                enableCodeHighlight = false,
                enableHaptics = true,
                historyLoadLimit = 60,
                requestHistoryLimit = 16,
            )

            DeviceTier.MID -> PerfProfile(
                tier = DeviceTier.MID,
                mode = mode,
                maxRenderedMessages = 80,
                streamThrottleMs = 130L,
                markdownMaxBlocks = 400,
                maxCodeBlockLines = 80,
                maxPinnedReasoningChars = 12_000,
                enableAnimations = true,
                enableCodeHighlight = false,
                enableHaptics = true,
                historyLoadLimit = 150,
                requestHistoryLimit = 24,
            )

            DeviceTier.HIGH -> PerfProfile(
                tier = DeviceTier.HIGH,
                mode = mode,
                maxRenderedMessages = 200,
                streamThrottleMs = 70L,
                markdownMaxBlocks = 1_200,
                maxCodeBlockLines = 200,
                maxPinnedReasoningChars = 40_000,
                enableAnimations = true,
                enableCodeHighlight = true,
                enableHaptics = true,
                historyLoadLimit = 400,
                requestHistoryLimit = 40,
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
