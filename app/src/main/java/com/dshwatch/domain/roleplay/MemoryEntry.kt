package com.dshwatch.domain.roleplay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 记忆的衰减参数。
 *
 * ## 为什么不用艾宾浩斯那条指数曲线
 *
 * 教科书上的 `R = e^(-t/S)` 衰减太快、尾部太短：按它算，一个月前的对话只剩
 * 不到 1%，模型就会表现得"完全没经历过"。真人不是这样 —— 很久以前的事会
 * 变成"只记得个大概"，但仍然在。
 *
 * 所以这里用**幂律**：`R = (1 + t/τ)^(-k)`。它的尾巴长，1 天、1 周、1 个月
 * 各自落在肉眼可辨的档位上，而两三个月后仍然留一点点。这和"模糊的记忆"
 * 这种真实体验更接近。
 *
 * 参数是按 [TAU_DAYS] = 3 天标定的，一条普通记忆的保留度大致是：
 *
 * | 经过 | 保留度 | 状态 |
 * |---|---|---|
 * | 1 天 | 83% | 清楚 |
 * | 7 天 | 46% | 开始模糊 |
 * | 30 天 | 21% | 只记得个大概 |
 * | 90 天 | 10% | 基本想不起来 |
 *
 * 被提起一次（`hits + 1`）时间常数乘 1.9，带强烈情绪再乘最多 3 倍 ——
 * 这就是"越常想起越不容易忘"。
 */
object MemoryDecay {

    /** 基准时间常数（天）。见类注释的标定表 */
    const val TAU_DAYS = 3.0

    /** 每被想起一次，时间常数乘 `1 + hits * HIT_GROWTH` */
    const val HIT_GROWTH = 0.9

    /** 情绪拉满时，时间常数再乘 `1 + EMOTION_GROWTH` */
    const val EMOTION_GROWTH = 2.0

    /** 幂律指数。越大忘得越快；0.5 是标准平方根曲线 */
    const val EXPONENT = 0.65

    /** 低于这个保留度，只记得个大概（注入时截短并加"记不太清了"） */
    const val BLUR_AT = 0.45f

    /** 低于这个保留度，想不起来了 —— 不再进提示词，但文件里还留着 */
    const val FADE_AT = 0.18f

    /** 低于这个保留度且不是永久记忆，算真的忘了，整理时可以清掉 */
    const val DROP_AT = 0.05f

    // ---- 固化成永久记忆的门槛 ----
    // "逐渐"的含义：一次强烈情绪不够，要再被想起一次；或者反复提及足够多次。

    /** 情绪 ≥ 此值 + 被想起 ≥ PERM_EMOTION_HITS 次 → 永久 */
    const val PERM_EMOTION = 0.85f

    /**
     * 强烈情绪的事，再被想起一次就固化。
     *
     * 是 1 而不是 2：第一次只是"记下来"，还没经过任何回忆；
     * 第二次提起才说明它真的在心里留了痕。这也对应"逐渐"——不是当场锁定，
     * 但也不需要反复念五遍。
     */
    const val PERM_EMOTION_HITS = 1

    /**
     * 累计被想起这么多次 → 永久（哪怕每次都很平淡）。
     *
     * 是 4 不是 5：`hits` 只数"后来又想起"的次数，第一次写入不算。
     * 所以门槛 4 对应"大约被提过 5 次"——反复念叨五遍的事，谁都会记住。
     */
    const val PERM_HITS = 4

    /** 情绪中等 + 被想起 3 次也算（不用到 0.85 那么极端） */
    const val PERM_SOFT_EMOTION = 0.60f
    const val PERM_SOFT_HITS = 3

    const val DAY_MS = 86_400_000.0
}

/**
 * 一条记忆。
 *
 * 粒度是**一件事**，不是"一段时间"。这是这次改动的关键：以前一次压缩产出
 * 一段 400 字的摘要，那段话里可能混着七八件事，没法单独衰减 —— 只能整段
 * 一起旧、一起新。拆成事件之后，"三天前那次吵架"和"昨天吃了什么"才能有
 * 不同的寿命。
 *
 * @param at 发生时刻
 * @param text 一句话（30~60 字），第三人称
 * @param hits 被想起的次数（压缩时又提到了同一件事，或用户翻看时点到）
 * @param emotion 情绪强度 0..1，写入时的峰值
 * @param lastHit 上次被想起的时刻；衰减从这里开始算
 * @param permanent 已固化成长期记忆，不再衰减
 * @param label 段落标题的原文。用户手写过就保留，避免每次读写都把
 *   `## t0` 这类自定义标题规范化成时间戳
 */
data class MemoryEntry(
    val at: Long,
    val text: String,
    val hits: Int = 0,
    val emotion: Float = 0f,
    val lastHit: Long = at,
    val permanent: Boolean = false,
    val label: String = "",
) {

    /**
     * 当前保留度 0..1。
     *
     * 永久记忆恒为 1。其余按幂律衰减，时间常数随 [hits] 与 [emotion] 增长。
     */
    fun retention(now: Long): Float {
        if (permanent) return 1f
        val days = ((now - lastHit).coerceAtLeast(0L)) / MemoryDecay.DAY_MS
        val tau = MemoryDecay.TAU_DAYS *
            (1.0 + hits * MemoryDecay.HIT_GROWTH) *
            (1.0 + emotion * MemoryDecay.EMOTION_GROWTH)
        return (1.0 + days / tau).pow(-MemoryDecay.EXPONENT).toFloat()
    }

    /** 已经想不起来了（不进提示词，但文件里保留） */
    fun isFaded(now: Long): Boolean = !permanent && retention(now) < MemoryDecay.FADE_AT

    /** 只记得个大概（注入时截短） */
    fun isBlurred(now: Long): Boolean =
        !permanent && retention(now) < MemoryDecay.BLUR_AT

    /** 真的忘了，整理时可以清掉 */
    fun isDropped(now: Long): Boolean = !permanent && retention(now) < MemoryDecay.DROP_AT

    /**
     * 被重新提起：加深印象。
     *
     * 情绪取两者的较大值 —— 一件事第一次讲得平淡、第二次讲得激动，
     * 印象应该按激动的那次算。
     */
    fun reinforce(by: MemoryEntry, now: Long): MemoryEntry = copy(
        // 正文用信息量更大的那份：字数多通常意味着细节更多
        text = if (by.text.length > text.length) by.text else text,
        at = min(at, by.at),
        hits = hits + 1,
        emotion = max(emotion, by.emotion),
        lastHit = now,
        permanent = permanent || isConsolidated(hits + 1, max(emotion, by.emotion)),
    )

    /** 判断是否达到固化门槛 */
    private fun isConsolidated(hits: Int, emotion: Float): Boolean =
        (emotion >= MemoryDecay.PERM_EMOTION && hits >= MemoryDecay.PERM_EMOTION_HITS) ||
            hits >= MemoryDecay.PERM_HITS ||
            (emotion >= MemoryDecay.PERM_SOFT_EMOTION && hits >= MemoryDecay.PERM_SOFT_HITS)

    /** 按当前状态补一次固化判定（新写入的条目也可能直接够格） */
    fun consolidated(): MemoryEntry =
        if (permanent || !isConsolidated(hits, emotion)) this
        else copy(permanent = true)

    /**
     * 模糊后的注入形态：留前半句 + 一句"记不太清了"。
     *
     * 不直接删掉，是因为"隐约记得有这回事"本身就是一种真实的记忆状态，
     * 模型据此可以说"好像提过……是不是……"，比完全不知道更像人。
     */
    fun blurredText(): String {
        val clean = text.trim()
        if (clean.length <= 24) return "$clean（记不太清了）"
        val cut = clean.take(24)
        val stop = cut.lastIndexOfAny(charArrayOf('。', '，', '、', '；', ' '))
        val head = if (stop >= 8) cut.substring(0, stop) else cut
        return "$head……（记不太清了）"
    }
}

/**
 * 从一句话里估情绪强度。
 *
 * ## 为什么要本地估一遍
 *
 * 压缩时已经让模型给每条打标（`[-]` / `[!]` / `[*]`），但模型不总听话 ——
 * 可能整段只给一个标记、可能忘了标、也可能把平淡的流水账标成 `[!]`。
 * 本地这条通道只看字面信号，稳定、可测试，两边取**较大值**：
 * 模型说重要、或字面明显激动，都算数；两边都说平淡才是真的平淡。
 *
 * 词表刻意收得克制。宁可漏判（那条记忆就是普通寿命），也不要把
 * "今天吃了面"判成刻骨铭心 —— 误判成永久记忆是没法回退的。
 */
object EmotionScore {

    /** 强烈情绪词：出现一个就足以把情绪推到高位 */
    private val STRONG = listOf(
        "爱你", "我爱你", "恨你", "我恨", "想你", "别走", "不要走", "离开我",
        "对不起", "原谅", "分手", "绝交", "再也不", "最后一次", "第一次",
        "发誓", "答应我", "保证", "秘密", "只有你", "永远", "一辈子",
        "崩溃", "哭了", "哭得", "心疼", "难受", "害怕", "恐惧", "绝望",
        "自杀", "去死", "活着", "死了", "葬礼", "生病", "住院", "手术",
        "惊喜", "感动", "谢谢你", "救了我", "救你", "保护你",
        // 「最怕一个人待着」这类是最典型的刻骨铭心，但"怕"单字太宽，
        // 只收固定搭配
        "最怕", "孤独", "舍不得", "离不开", "被抛弃", "没人要",
        "想见你", "撑不住", "扛不住", "一个人过",
    )

    /** 中等情绪词：需要配合其它信号 */
    private val MEDIUM = listOf(
        "喜欢", "讨厌", "生气", "难过", "开心", "高兴", "委屈", "孤单",
        "寂寞", "想念", "思念", "担心", "后悔", "抱歉", "感动", "温暖",
        "重要", "珍惜", "在乎", "讨厌你", "烦", "累", "压力",
    )

    /** 具体细节标记：专名、数字、日期，说明这件事有"可回忆的形状" */
    private val DETAIL = Regex("""\d|[A-Za-z]{2,}|「[^」]+」|"[^"]+"""")

    fun of(text: String): Float {
        if (text.isBlank()) return 0f
        var score = 0f

        // 情绪词
        if (STRONG.any { text.contains(it) }) score += 0.55f
        val mediumHits = MEDIUM.count { text.contains(it) }
        score += min(0.35f, mediumHits * 0.15f)

        // 标点强度：连续感叹号 / 问号
        val bangs = Regex("""[!！]{1,}""").findAll(text).sumOf { it.value.length }
        val asks = Regex("""[?？]{2,}""").findAll(text).count()
        score += min(0.20f, bangs * 0.08f + asks * 0.10f)

        // 具体细节：有名字、有数字的事更容易被记住
        if (DETAIL.containsMatchIn(text)) score += 0.10f

        // 长度：一句话都写不长的，通常也不是什么大事
        if (text.length >= 30) score += 0.05f

        return score.coerceIn(0f, 1f)
    }

    /**
     * 解析模型给的标记。
     *
     * `[*]` 是她自己认为重要的事，`[!]` 是情绪冲击，`[-]` 是普通流水。
     * 认不出来就按普通处理，交给本地启发式兜底。
     */
    fun fromMarker(marker: Char): Float = when (marker) {
        '*' -> 0.90f
        '!' -> 0.72f
        '~' -> 0.45f
        else -> 0.10f
    }
}

/**
 * 两句话是不是在说同一件事。
 *
 * ## 这只是兜底，主判据是模型
 *
 * 「多次提到会加深印象」的准确判断只能靠语义 —— 而压缩时本来就要调一次模型，
 * 顺手把现有记忆的编号给它、让它标 `[=3]` 说明"这条是第 3 条的补充"，
 * 既准确又不额外花钱（见 [MemoryCompressor]）。
 *
 * 这里管的是模型不听话时的兜底：**只认近乎一字不差的重复**。
 *
 * 试过更宽松的方案（2-gram 重叠 0.46、最长公共子串 0.80），问题都是
 * 「她喜欢猫」和「她喜欢狗」重叠 75~83%，会被判成同一件事，
 * 一合并就把「狗」这条丢掉了。丢用户说过的话，比多留一条重复记忆糟得多。
 *
 * 所以阈值定在 92%：只有"几乎原文重述"才合并。
 */
object MemorySimilarity {

    /** 低于此分认为不是同一件事。高阈值是刻意的，见类注释 */
    const val THRESHOLD = 0.92f

    fun score(a: String, b: String): Float {
        val x = clean(a)
        val y = clean(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        val run = longestCommonRun(x, y)
        return run.toFloat() / min(x.length, y.length)
    }

    fun same(a: String, b: String): Boolean = score(a, b) >= THRESHOLD

    private fun clean(s: String): String = s.filter { it.isLetterOrDigit() }

    /** 最长公共连续子串的长度，滚动数组实现 */
    private fun longestCommonRun(x: String, y: String): Int {
        if (x.isEmpty() || y.isEmpty()) return 0
        var best = 0
        var prev = IntArray(y.length + 1)
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            for (j in 1..y.length) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > best) best = cur[j]
            }
            val swap = prev
            prev = cur
            cur = swap
            cur.fill(0)
        }
        return best
    }
}
