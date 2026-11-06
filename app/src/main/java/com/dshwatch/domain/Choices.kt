package com.dshwatch.domain

/**
 * 从回复末尾把「A. / B. / C.」这类选项拆出来。
 *
 * ## 为什么是判文本，而不是让模型写控件
 *
 * 模型输出结构化东西最稳的形式永远是纯文本。要求它写 `<button data-pick=...>`
 * 时，引号、转义、属性名任何一处错都会让整块界面变成死的，而用户看到的
 * 只是"点了没反应"。反过来，`A. xxx` 这种行几乎是所有模型的默认习惯，
 * 拆它只需要一个正则，失败了也只是回落成普通文字 —— 没有中间状态。
 *
 * ## 判定为什么这么严
 *
 * 只认**结尾连续的一段**、**编号必须从 A/1/一 开始且逐行递增**、**每行不超过
 * [MAX_ITEM_CHARS] 字**。三条同时满足才算选项。
 *
 * 剩下的模糊地带是「结尾的有序列表」：`1. 先备份 2. 再升级` 和选项长得一样。
 * 数字编号因此多一道检查 —— 引导句以冒号收尾（"排查顺序如下："）又不含
 * "选项/选择/你想"时按步骤处理；字母编号不设限，正文几乎不会用 A/B/C 列步骤。
 *
 * 漏判的代价是选项退化成纯文字，用户得手打；错判的代价是那几行从气泡里
 * 挪到按钮上 —— 内容还在、仍然可读，所以宁可偶尔错判，也不要漏掉真正能点的选项。
 */
object Choices {

    /** 一个选项 */
    data class Choice(
        /** 序号原文：A / b / 1 / 二 */
        val label: String,
        /** 去掉序号后的正文 */
        val text: String,
        /** 要作为用户回复发出的整行，如「A. 走过去看看」 */
        val raw: String,
        /** 「自定义回复 / 其他」：点了应该打开输入框，而不是发这句字面量 */
        val custom: Boolean,
    )

    /** 解析结果 */
    data class Parsed(
        /** 去掉选项块之后的正文 */
        val body: String,
        val choices: List<Choice>,
    ) {
        val hasChoices: Boolean get() = choices.size >= 2
    }

    private const val MAX_ITEM_CHARS = 64
    private const val MAX_CHOICES = 8

    /** `A. xxx` / `1、xxx` / `**B.** xxx` / `- C) xxx` / `二：xxx` */
    private val ROW = Regex(
        """^\s*(?:[-*•]\s*)?(?:\*\*)?\s*([A-Ha-h]|\d{1,2}|[一二三四五六七八])\s*(?:\*\*)?\s*[.、,，)）:：]\s*(?:\*\*)?\s*(.+?)\s*(?:\*\*)?$""",
    )

    /** 选项块上方的引导语，识别到就一起从正文里去掉 */
    private val HEADING = Regex("""^\s*(?:选项|选择|可选|你的选择|请选择|下一步)\s*[：:]?\s*$""")

    private val CUSTOM_WORDS = listOf("自定义", "其他", "其它", "自己说", "自由回复", "自行输入")

    /** 引导句里出现这些词，说明它真的在问"你想怎么选" */
    private val OPTION_HINT = listOf("选项", "选择", "你想", "你要", "怎么办", "怎么做", "如何", "哪")

    fun parse(text: String): Parsed {
        if (text.isBlank()) return Parsed(text, emptyList())

        val lines = text.lines()
        val last = lines.indexOfLast { it.isNotBlank() }
        if (last < 0) return Parsed(text, emptyList())

        // 从结尾往上收集连续匹配的行
        val rows = ArrayDeque<Pair<Int, Choice>>()
        var i = last
        while (i >= 0 && rows.size < MAX_CHOICES) {
            val line = lines[i]
            if (line.isBlank()) break
            val m = ROW.matchEntire(line) ?: break
            val label = m.groupValues[1]
            val content = m.groupValues[2].trim().trim('＊', '*').trim()
            // 太长说明这是正文段落，不是选项
            if (content.isEmpty() || content.length > MAX_ITEM_CHARS) break
            rows.addFirst(
                i to Choice(
                    label = label,
                    text = content,
                    raw = normalizeRaw(label, content),
                    custom = CUSTOM_WORDS.any { content.contains(it) },
                ),
            )
            i--
        }

        if (rows.size < 2) return Parsed(text, emptyList())

        /*
         * 数字编号还要多一层： 「步骤如下：1. 备份 2. 升级 3. 回滚」也是这个形状，
         * 但它不是让用户挑，是让用户照着做。
         *
         * 判据取引导句的结尾：以冒号收尾、又没出现"选项/选择/你想"这类词，
         * 就当成步骤列表放回正文。字母编号不做这层限制 —— 正文几乎不会用
         * "A. B. C." 来列步骤，那是选项的专属格式。
         */
        if (rows.first().second.label.first().isDigit()) {
            var h = rows.first().first - 1
            while (h >= 0 && lines[h].isBlank()) h--
            if (h >= 0) {
                val lead = lines[h].trim()
                val colon = lead.endsWith("：") || lead.endsWith(":")
                if (colon && OPTION_HINT.none { lead.contains(it) }) {
                    return Parsed(text, emptyList())
                }
            }
        }

        // 编号必须从 A / 1 / 一 起、逐行递增
        val start = orderOf(rows.first().second.label) ?: return Parsed(text, emptyList())
        if (start != 1) return Parsed(text, emptyList())
        rows.forEachIndexed { idx, (_, c) ->
            if (orderOf(c.label) != start + idx) return Parsed(text, emptyList())
        }

        var cut = rows.first().first
        // 紧贴在上面的「选项：」也一并去掉
        var h = cut - 1
        while (h >= 0 && lines[h].isBlank()) h--
        if (h >= 0 && HEADING.matches(lines[h])) cut = h

        val body = lines.subList(0, cut).joinToString("\n").trimEnd()
        return Parsed(body, rows.map { it.second })
    }

    /** 发出去的整行要干净：去掉 Markdown 加粗与多余空白 */
    private fun normalizeRaw(label: String, content: String): String {
        val clean = content.replace("**", "").trim()
        return "$label. $clean"
    }

    /** A→1, B→2, 1→1, 一→1 */
    private fun orderOf(label: String): Int? {
        val s = label.trim()
        if (s.isEmpty()) return null
        val c = s[0]
        return when {
            c in 'A'..'H' -> c - 'A' + 1
            c in 'a'..'h' -> c - 'a' + 1
            c in '0'..'9' -> s.toIntOrNull()
            else -> "一二三四五六七八".indexOf(c).takeIf { it >= 0 }?.plus(1)
        }
    }
}
