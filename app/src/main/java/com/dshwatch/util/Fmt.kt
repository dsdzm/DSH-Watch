package com.dshwatch.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {

    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val dateFmt = ThreadLocal.withInitial { SimpleDateFormat("MM-dd", Locale.getDefault()) }
    private val dateTimeFmt =
        ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    fun clock(ts: Long): String = timeFmt.get()!!.format(Date(ts))

    fun date(ts: Long): String = dateFmt.get()!!.format(Date(ts))

    fun dateTime(ts: Long): String = dateTimeFmt.get()!!.format(Date(ts))

    /** 会话列表用的相对时间：刚刚 / 12:05 / 昨天 / 09-28 */
    fun relative(ts: Long): String {
        if (ts <= 0) return ""
        val now = System.currentTimeMillis()
        val diff = now - ts
        return when {
            diff < 60_000L -> "刚刚"
            isSameDay(ts, now) -> clock(ts)
            isSameDay(ts, now - 86_400_000L) -> "昨天"
            diff < 7 * 86_400_000L -> "${(diff / 86_400_000L)} 天前"
            else -> date(ts)
        }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** 1234 -> 1.2k；1234567 -> 1.23M */
    fun tokens(n: Int): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> trimFixed(n / 1_000.0, 1) + "k"
        else -> trimFixed(n / 1_000_000.0, 2) + "M"
    }

    fun money(v: Double): String {
        if (v <= 0.0) return "—"
        return when {
            v < 0.01 -> "<¥0.01"
            v < 1.0 -> "¥" + trimFixed(v, 4)
            v < 100.0 -> "¥" + trimFixed(v, 3)
            else -> "¥" + trimFixed(v, 2)
        }
    }

    fun cost(usd: Double): String = money(usd)

    /**
     * 长时长的人类可读格式。
     * [duration] 面向的是工具耗时的毫秒级展示（「120ms」），
     * 而「距离下一个计费时段还有多久」可能是几小时甚至跨天，需要另一套量级。
     */
    fun longDuration(ms: Long): String {
        if (ms <= 0) return "0 分钟"
        val totalMinutes = ms / 60_000
        if (totalMinutes < 1) return "不到 1 分钟"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "$minutes 分钟"
            minutes == 0L -> "$hours 小时"
            else -> "$hours 小时 $minutes 分"
        }
    }

    fun percent(v: Float): String = "${(v * 100).toInt()}%"

    /** 文件大小：4300 -> 4.2 KB；1_200_000 -> 1.1 MB。0 或负数返回空串（调用方据此省略这一项） */
    fun bytes(n: Long): String = when {
        n <= 0L -> ""
        n < 1024L -> "$n B"
        n < 1024L * 1024L -> trimFixed(n / 1024.0, 1) + " KB"
        else -> trimFixed(n / (1024.0 * 1024.0), 1) + " MB"
    }

    private fun trimFixed(v: Double, digits: Int): String {
        val s = String.format(Locale.US, "%.${digits}f", v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    fun duration(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> trimFixed(ms / 1000.0, 1) + "s"
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }

    /** 单行预览：折叠换行、截断 */
    fun preview(text: String, max: Int = 46): String {
        val one = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max - 1) + "…"
    }

    fun dayKey(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun startOfMonth(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
