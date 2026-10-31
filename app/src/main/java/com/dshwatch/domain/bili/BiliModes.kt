package com.dshwatch.domain.bili

import com.dshwatch.domain.model.AppSettings

/**
 * 刷完之后的动作。
 *
 * 为什么是三选一而不是三个开关：这三件事在「一轮刷 B站」里是互斥的 ——
 * 一条视频要么评论、要么私信 UP 主、要么只记进日报。三个开关能组合出
 * 「又评论又私信」这种用户从没想要、但模型一定会去做的状态。
 */
enum class BiliMode(val id: String) {
    /** 只写日报，不对外说任何话 */
    SUMMARY("summary"),

    /** 在视频下发评论 / 回复别人 */
    COMMENT("comment"),

    /** 给 UP 主发私信 */
    DM("dm"),
    ;

    companion object {
        fun of(id: String): BiliMode? = entries.firstOrNull { it.id == id }
    }
}

object BiliModes {

    /**
     * 解析当前模式。
     *
     * `biliMode` 为空说明是 1.9 及更早的存档：那时只有「允许发评论」一个布尔，
     * 开着就当评论模式，关着就是只总结。这样升级上来的用户不会突然不能发。
     */
    fun of(settings: AppSettings): BiliMode {
        BiliMode.of(settings.biliMode)?.let { return it }
        return if (settings.biliCommentEnabled) BiliMode.COMMENT else BiliMode.SUMMARY
    }

    fun allowsComment(settings: AppSettings): Boolean = of(settings) == BiliMode.COMMENT

    fun allowsDm(settings: AppSettings): Boolean = of(settings) == BiliMode.DM
}

/** 每日定时任务的时间点。放这里是因为工具、设置页、Receiver 都要用同一套格式化 */
object BiliClock {

    fun hhmm(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    /**
     * 下一个触发时刻（毫秒）。
     *
     * 今天的点已经过了就顺延到明天 —— 用 `Calendar` 而不是「当前 + 24h」，
     * 否则改了时间之后第一次触发会差一整天的偏移。
     */
    fun nextTrigger(now: Long, hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        cal.set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now + 60_000L) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
