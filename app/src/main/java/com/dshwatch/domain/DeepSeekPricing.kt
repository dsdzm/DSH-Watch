package com.dshwatch.domain

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * DeepSeek API 的峰谷计费时段。
 *
 * ## 规则（以官方开放平台公告为准，此处按 2026-08-23 起生效的口径实现）
 *  - **高峰**：北京时间 周一至周五 `09:00–12:00`、`14:00–18:00`
 *  - **空闲**：其余全部时间，**周六周日全天都算空闲**
 *  - 空闲单价 = 高峰单价的一半
 *
 * ## 为什么要做成工具
 * 这条规则改过好几轮（早先是「00:30–08:30 优惠」，后来换成现在的峰谷制），
 * 而且用户的直觉往往是错的 —— 晚上写代码的人会以为夜里便宜，
 * 实际上现在的空闲时段是白天和深夜，**工作日 9-12 点、14-18 点恰恰最贵**。
 * 所以时间段的判断集中在 [windowAt] 一处，将来官方再调只改这个文件。
 */
object DeepSeekPricing {

    /** 高峰时段（工作日的两个区间） */
    private val peakRanges = listOf(
        LocalTime.of(9, 0) to LocalTime.of(12, 0),
        LocalTime.of(14, 0) to LocalTime.of(18, 0),
    )

    /**
     * @param peak 当前是否处于高峰时段
     * @param untilChangeMs 距离下一次时段切换还有多少毫秒
     */
    data class Window(val peak: Boolean, val untilChangeMs: Long) {
        val isOffPeak: Boolean get() = !peak
    }

    /**
     * 判断某个时刻处在哪个时段。
     *
     * 实现在「下一次切换点」上做了统一处理：把候选切换点（今天剩下的、明天、下周一）
     * 按时间排好取第一个晚于当前的 —— 这样周末、跨天、周五晚上这些边界情况
     * 不需要各自写一遍 if。
     */
    fun windowAt(now: LocalDateTime): Window {
        val day = now.dayOfWeek
        val weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        val time = now.toLocalTime()

        val peak = !weekend && peakRanges.any { (from, to) -> !time.isBefore(from) && time.isBefore(to) }

        val next = nextBoundary(now, weekend)
        val ms = ChronoUnit.MILLIS.between(now, next).coerceAtLeast(0)
        return Window(peak = peak, untilChangeMs = ms)
    }

    fun windowAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): Window =
        windowAt(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone))

    /**
     * 下一个时段切换点。
     *
     * 注意周五：它的下一个高峰是**下周一** 09:00，中间整段周末都是空闲，
     * 所以不能简单地「往后找 3 小时」。
     */
    private fun nextBoundary(now: LocalDateTime, weekend: Boolean): LocalDateTime {
        val time = now.toLocalTime()
        val day = now.dayOfWeek
        val today = now.toLocalDate()

        if (weekend) {
            // 周末全天空闲，下一个切换点是下周一 09:00
            var d = today
            do {
                d = d.plusDays(1)
            } while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY)
            return LocalDateTime.of(d, peakRanges.first().first)
        }

        // 工作日：今天剩下的边界点
        for ((from, to) in peakRanges) {
            if (time.isBefore(from)) return LocalDateTime.of(today, from)   // 即将进入高峰
            if (time.isBefore(to)) return LocalDateTime.of(today, to)      // 即将进入空闲
        }

        // 当天 18:00 之后：下一个切换点是「下一个工作日」的 09:00
        var d = today
        do {
            d = d.plusDays(1)
        } while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY)
        return LocalDateTime.of(d, peakRanges.first().first)
    }

    /**
     * 这个供应商是不是 DeepSeek。
     *
     * 三个来源都要看：很多人用的是中转站（域名里没有 deepseek），
     * 但模型名仍然会写 `deepseek-chat`；也有人自建后把名字起成别的。
     */
    fun isDeepSeek(baseUrl: String, model: String, providerName: String = ""): Boolean {
        val haystack = "$baseUrl $model $providerName".lowercase()
        return haystack.contains("deepseek")
    }
}
