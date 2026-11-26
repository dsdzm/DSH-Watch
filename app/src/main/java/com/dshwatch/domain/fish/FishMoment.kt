package com.dshwatch.domain.fish

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 一条「动态」。
 *
 * ## 这是什么
 *
 * 她自己的小号。每天一到两条，记今天发生了什么、她怎么想的 ——
 * 不是摘要，不是日记体，是那种发出去也没人看、纯粹想写下来的碎碎念。
 *
 * ## 为什么和长期记忆分开存
 *
 * 记忆是**给模型看的**：一条一件事、第三人称、会衰减、会合并。
 * 动态是**给人看的**：第一人称、有语气、不衰减、按天排。
 * 混成一份，两边都会被对方的格式拖累 —— 记忆里出现「今天好累啊」这种句子，
 * 模型下轮就会拿它当事实复述；动态里出现「[*] 用户怕黑」，一眼就是机器写的。
 *
 * 所以两份数据、两套提示词、两个页面。
 */
@Serializable
data class FishMoment(
    /** 稳定的去重键：同一时刻同一句话只存一条 */
    val id: Int,
    /** 写下它的时刻（不是"事件发生"的时刻 —— 动态是当下写的） */
    val at: Long,
    val text: String,
    /** 这一天里她大概的心情，用来在时间线上配一个点。空 = 不知道 */
    val mood: String = "",
)

/**
 * 动态的纯逻辑：日期分桶、清洗、限量。
 *
 * 放在 domain 里而不是 UI 里，是因为「一天算几条」「哪些行要丢掉」
 * 这些规则必须有单测钉住 —— 模型输出的行首标记（`1.`、`·`、`- `）
 * 千奇百怪，清洗漏一种，动态页上就会挂着一条「1.」。
 */
object FishMoments {

    /** 一天最多自动写几条。再多就变成流水账，没人看 */
    const val AUTO_PER_DAY = 1

    /** 手动再催也最多到这么多，防止连点把存储撑爆 */
    const val MAX_PER_DAY = 3

    /** 只保留这么多条，更早的丢掉（约三个月） */
    const val MAX_KEEP = 120

    /** 一条动态的长度上限（字）。太长就不是"动态"了 */
    const val MAX_LEN = 120

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 本地时区的「哪一天」。跨时区飞行时按当地时间算，符合直觉 */
    fun dayKey(at: Long): String = synchronized(dateFormat) { dateFormat.format(Date(at)) }

    fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 时间线上那行小字：今天 14:20 / 昨天 09:10 / 11月20日 21:03 */
    fun stamp(at: Long, now: Long = System.currentTimeMillis()): String {
        val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
        val today = dayKey(now)
        val day = dayKey(at)
        val yesterday = dayKey(startOfDay(now) - 1)
        return when (day) {
            today -> "今天 $clock"
            yesterday -> "昨天 $clock"
            else -> "${SimpleDateFormat("M月d日", Locale.US).format(Date(at))} $clock"
        }
    }

    /**
     * 把模型输出拆成一条条动态。
     *
     * 模型很爱加序号、项目符号、引号，也爱写「动态：」这种前缀。
     * 这些都不是她说话的样子，一律剥掉；空行直接跳过。
     */
    fun parse(raw: String, limit: Int = AUTO_PER_DAY): List<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                line
                    .removePrefix("- ")
                    .removePrefix("· ")
                    .removePrefix("* ")
                    .replace(Regex("^\\d+[.、)）]\\s*"), "")
                    .replace(Regex("^(动态|碎碎念|记录)[:：]\\s*"), "")
                    .trim()
                    .trim('"', '“', '”', '\'')
            }
            .filter { it.length >= 4 }
            .map { if (it.length > MAX_LEN) it.take(MAX_LEN) else it }
            .take(limit)
            .toList()

    /** 心情标签：只认几个，多了在圆屏上放不下 */
    fun moodOf(raw: String): String {
        val t = raw.trim()
        return when {
            t.isEmpty() -> ""
            t.length <= 4 -> t
            else -> t.take(4)
        }
    }
}
