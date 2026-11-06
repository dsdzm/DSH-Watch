package com.dshwatch.domain

/**
 * 面板标记：让模型用几行纯文本描述一块「状态板」，由原生控件渲染。
 *
 * ## 为什么不用 HTML
 *
 * HTML 能做出任何样子，但代价是模型每次要写几百个字符的标签和样式，
 * 手表上下文本来就紧；而且一处引号写错，整块界面就废了。
 * 面板是高频出现的东西（每次对话可能都带一块），必须满足三个条件：
 * **写得短、错不了、渲染快**。所以用围栏 + 竖线分隔，跟 Markdown 表格一个手感。
 *
 * ## 格式
 *
 * ```
 * ```panel 公子 · 墨染 | 江南书香世家嫡子
 * 近侍 | 柳儿 | 48/100
 *   父 | 陆先生已去，老爷未至
 *   险 | 中门已落，宜早掌灯
 * 宅邸 | 环境与规矩
 * ```
 * ```
 *
 * - 开头那行是**标题**：`主标题 | 副标题`，副标题可省；
 * - **顶格**行是条目：`名称 | 标签 | 数值`，标签与数值都可省；
 * - **缩进两格**的行是上一条目的详情，点「阅」展开；
 * - 数值写成 `48/100` 会自动渲染成进度条。
 */
object Panels {

    data class Detail(val key: String, val text: String)

    data class Row(
        val name: String,
        val tag: String,
        val value: String,
        val details: List<Detail>,
    ) {
        val expandable: Boolean get() = details.isNotEmpty()
    }

    data class Panel(
        val title: String,
        val subtitle: String,
        val rows: List<Row>,
    )

    /** 一条消息里挖出来的全部面板 */
    data class Result(
        /** 去掉面板围栏后的正文 */
        val body: String,
        val panels: List<Panel>,
    )

    private const val MAX_PANELS = 3

    /**
     * 围栏。`panel` / `card` / `面板` 都认 —— 模型对这三个词的使用频率差不多，
     * 只认一个会白白丢掉一半。
     */
    private val FENCE = Regex(
        "```(?:panel|card|面板)[ \\t]*([^\\n]*)\\r?\\n([\\s\\S]*?)```",
        RegexOption.IGNORE_CASE,
    )

    private val RATIO = Regex("""^\s*(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\s*$""")

    fun extract(text: String): Result {
        if (text.isBlank() || !text.contains("```")) return Result(text, emptyList())

        val panels = ArrayList<Panel>()
        for (m in FENCE.findAll(text)) {
            if (panels.size >= MAX_PANELS) break
            parseBlock(m.groupValues[1], m.groupValues[2])?.let { panels += it }
        }
        if (panels.isEmpty()) return Result(text, emptyList())

        // 只挖掉真正解析成功的那些块；解析失败的留在正文里，
        // 用户至少还能看到原文，不会凭空少一段
        var body = text
        FENCE.findAll(text).forEach { m ->
            if (parseBlock(m.groupValues[1], m.groupValues[2]) != null) {
                body = body.replace(m.value, "")
            }
        }
        return Result(body.trim(), panels)
    }

    /**
     * 解析一块。
     *
     * 容错是刻意留的：模型多写一个空行、少写一个竖线都不该让整块作废 ——
     * 作废的结果是用户看到一堆 ```panel 原文，比少一列难看得多。
     * 只有「一行都解析不出条目」时才放弃，交回正文。
     */
    private fun parseBlock(head: String, body: String): Panel? {
        val lines = body.lines().map { it.trimEnd() }
        var titleLine = head.trim()
        var start = 0
        if (titleLine.isBlank()) {
            // 标题写在围栏内部第一行，也认
            val first = lines.indexOfFirst { it.isNotBlank() }
            if (first < 0) return null
            titleLine = lines[first].trim()
            start = first + 1
        }

        val (title, subtitle) = splitTitle(titleLine)

        val rows = ArrayList<Row>()
        var details = ArrayList<Detail>()
        fun flush() {
            if (rows.isNotEmpty()) {
                val last = rows.removeAt(rows.lastIndex)
                rows += last.copy(details = details)
            }
            details = ArrayList()
        }

        for (i in start until lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) continue
            val indented = raw.startsWith("  ") || raw.startsWith("\t") || raw.startsWith("　")
            if (indented) {
                if (rows.isEmpty()) continue
                val t = raw.trim()
                val parts = t.split("|").map { it.trim() }
                details += if (parts.size >= 2) {
                    Detail(parts[0], parts.drop(1).joinToString(" | "))
                } else {
                    Detail("", t)
                }
            } else {
                flush()
                val parts = raw.trim().split("|").map { it.trim() }
                if (parts.isEmpty() || parts[0].isBlank()) continue
                rows += when (parts.size) {
                    1 -> Row(parts[0], "", "", emptyList())
                    2 -> Row(parts[0], "", parts[1], emptyList())
                    else -> Row(parts[0], parts[1], parts.drop(2).joinToString(" | "), emptyList())
                }
            }
        }
        flush()

        if (rows.isEmpty()) return null
        return Panel(title, subtitle, rows)
    }

    /** `公子 · 墨染 | 江南书香世家嫡子` → 标题 + 副标题 */
    private fun splitTitle(line: String): Pair<String, String> {
        val parts = line.split("|").map { it.trim() }
        return if (parts.size >= 2) {
            parts[0] to parts.drop(1).joinToString(" | ")
        } else {
            parts[0] to ""
        }
    }

    /** `48/100` → (48f, 100f)，用来画进度条；不是这个形状就返回 null */
    fun ratioOf(value: String): Pair<Float, Float>? {
        val m = RATIO.matchEntire(value) ?: return null
        val a = m.groupValues[1].toFloatOrNull() ?: return null
        val b = m.groupValues[2].toFloatOrNull() ?: return null
        if (b <= 0f) return null
        return a to b
    }
}
