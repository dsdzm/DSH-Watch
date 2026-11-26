package com.dshwatch.domain.fish

import java.util.Calendar

/**
 * 「主动发言」的时间规则。
 *
 * ## 为什么时间点是字符串
 *
 * 这些值要落进设置 JSON、要在界面上被用户直接改、还要能从一个设备的备份
 * 搬到另一台设备上读。用 `LocalTime` 或毫秒时间戳都会把「今天几号」「哪个时区」
 * 混进来，而这里要表达的只是「每天的这个钟点」。`"07:00"` 是人能读、能校验、
 * 不会歧义的最小形式。
 *
 * ## 两套来源
 *
 * 1. **默认节律**：用户设的四个点（默认 7、12、18、22）。这是"她每天大致
 *    什么时候会想起你"，属于设置。
 * 2. **她自己约的**：模型通过 `schedule_proactive` 工具登记的一次性时间点
 *    （"两小时后问问他面试怎么样"）。这属于一次性的计划，存 [Plans]。
 *
 * 两者都在 [next] 里合成「下一次该在什么时候醒」。用同一个闹钟串行排队，
 * 而不是为每个时间点各挂一个 AlarmManager：手表上闹钟数目越多，被 ROM
 * 合并、丢弃的概率越大，而且取消时要逐个对齐 —— 一个队列只有一处真相。
 */
object FishProactive {

    /** 默认四次：早上、中午、傍晚、睡前 */
    val DEFAULT_TIMES: List<String> = listOf("07:00", "12:00", "18:00", "22:00")

    /** 单次主动发言距现在至少要隔多久（分钟）。太近等于"刚说完又发一条" */
    const val MIN_DELAY_MINUTES = 5

    /** 单次主动发言最远能约多久（分钟）。超过一天就没有"主动"的意味了 */
    const val MAX_DELAY_MINUTES = 24 * 60

    /** 一次性计划最多同时挂几条。多了说明它在刷屏，该被清掉 */
    const val MAX_PLANS = 5

    /**
     * 把用户输入的 `HH:mm` 列表整理成规范形式。
     *
     * 容忍 `7:00`、`07：00`（全角冒号）、空格、重复项与乱序 —— 这些在
     * 手表的输入法上一个都不奇怪。认不出的项直接丢掉，而不是让整个列表失效：
     * 用户改坏了一个时间点，不该连带另外三个都不响。
     */
    fun normalize(raw: String): List<String> =
        raw.split(',', '，', '\n', ' ')
            .mapNotNull { parseMinutes(it) }
            .distinct()
            .sorted()
            .map { format(it) }

    fun normalizeList(times: List<String>): List<String> =
        times.mapNotNull { parseMinutes(it) }.distinct().sorted().map { format(it) }

    /** `"07:30"` → 450；格式不对返回 null */
    fun parseMinutes(text: String): Int? {
        val t = text.trim().replace('：', ':')
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun format(minutes: Int): String {
        val m = ((minutes % (24 * 60)) + 24 * 60) % (24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * 今天（或明天）这个钟点的时间戳。
     *
     * 用 `> now` 而不是 `>= now`：正好落在这一分钟时，如果算成"现在"，
     * 排完立刻触发一次，然后排下一次又是同一分钟 —— 一天里会连着响。
     * 留一分钟的余量，让它排到明天。
     */
    fun nextAt(now: Long, hhmm: String): Long? {
        val minutes = parseMinutes(hhmm) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now + 60_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** 四个默认时点里最早的那个 */
    fun nextDefault(now: Long, times: List<String>): Long? =
        normalizeList(times).mapNotNull { nextAt(now, it) }.minOrNull()

    /**
     * 下一次该醒的时刻：默认节律与一次性计划里最早的那一个。
     *
     * 一次性计划**即使总开关关着也仍然生效** —— 那是她在对话里明确答应过
     * "待会儿来找你"的事。总开关管的是"每天那四次还要不要"，不该顺手
     * 把答应过的话也吞掉。
     */
    fun next(
        now: Long,
        times: List<String>,
        plans: List<Long>,
        enabled: Boolean = true,
    ): Long? {
        val fromDefaults = if (enabled) nextDefault(now, times) else null
        val fromPlans = plans.filter { it > now + 60_000L }.minOrNull()
        return listOfNotNull(fromDefaults, fromPlans).minOrNull()
    }

    /**
     * 给模型的那段"提示"。
     *
     * 它是以**用户身份**塞进请求里的（见 ChatEngine.proactive），因为绝大多数
     * 兼容端点不接受两条连续的 system / assistant。所以措辞上必须让模型明白
     * 「这不是对方说的话，是系统给你的一个时机」，同时又不能让它把这件事
     * 说出口 —— 提示里明确禁止出现"系统""提醒""主动消息"这些词。
     */
    fun hint(at: Long, note: String, recentUser: String = ""): String = buildString {
        append("（系统提示，对方看不到这条消息）现在是 ")
        append(clockOf(at))
        append("（")
        append(dayPart(at))
        append("）。")
        append("你忽然想起他，想主动给他发条消息。\n")
        if (note.isNotBlank()) {
            append("之前你跟自己说过，这个时候要跟他说：").append(note.trim()).append("\n")
        }
        if (recentUser.isNotBlank()) {
            append("你们上次聊到：").append(recentUser.trim().take(60)).append("\n")
        }
        append("像平常一样发 1~3 条短消息。不要解释你在做什么，")
        append("不要提「主动」「提醒」「系统」「定时」这些字，就像随手拿起手机发的。")
        append("这会儿要是真没什么想说的，就只输出 [已读]。")
    }

    /** `14:05` */
    fun clockOf(at: Long): String = runCatching {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }.getOrDefault("")

    /** 给模型一个粗略的时段感，比精确到分钟更像人 */
    fun dayPart(at: Long): String = runCatching {
        when (val h = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)) {
            in 5..8 -> "清早"
            in 9..10 -> "上午"
            in 11..12 -> "中午"
            in 13..16 -> "下午"
            in 17..18 -> "傍晚"
            in 19..21 -> "晚上"
            else -> "夜里"
        }
    }.getOrDefault("")

    /**
     * 通知里的那一行字。
     *
     * 取第一条气泡就够了：一条主动消息本来就是一两句，通知栏也只显示得下
     * 一行半。太长的内容截断并补省略号，而不是让系统自己去截 —— 系统截出来
     * 常常断在半个词上。
     */
    fun preview(text: String): String {
        // 思考块在通知里同样不能露头：界面上剥了、通知里没剥，等于没修
        val body = com.dshwatch.domain.roleplay.FishBubbles.stripThinking(text)
        val firstLine = body.split('\n').firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val single = firstLine.replace(Regex("\\s+"), " ")
        return if (single.length <= 60) single else single.take(60).trimEnd() + "…"
    }
}
