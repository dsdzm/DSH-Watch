package com.dshwatch.net

/**
 * 把 HTML 压成纯文本。
 *
 * ## 为什么必须做这件事
 * 手表上的 token 比电脑上贵得多（窄屏交互、按次计费、上下文窗口也更紧张），
 * 而一个普通网页的 HTML 里**九成以上是标签、脚本和内联样式** ——
 * 模型要的只是那几句话。原样丢回去，等于把一次工具调用的开销浪费在
 * `<div class="nav-wrapper">` 这种它根本不看的东西上。
 *
 * ## 取舍
 * 这是**正则清洗，不是 HTML 解析器**。理由和 `SourceValidator` 里一样：
 * 引一个真正的解析器要几 MB、还要防 XXE，而这里的目标只是「把噪音去掉」——
 * 去掉之后的文本是给模型读的，不是给浏览器渲染的，容忍度很高。
 * 因此宁可少切一点（保留一些多余空白），也不冒险切掉正文。
 */
object HtmlText {

    private val SCRIPT = Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STYLE = Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val NOSCRIPT = Regex("<noscript\\b[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val HEAD = Regex("<head\\b[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** 这些标签的**结束**位置天然就是换行点；不补的话整页会挤成一行，模型很难读 */
    private val BLOCK_END = Regex(
        "</(p|div|section|article|header|footer|li|tr|h[1-6]|blockquote|pre|table|ul|ol|nav|aside)\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val BLANK_LINES = Regex("\\n\\s*\\n\\s*\\n+")
    private val TRAILING_SPACE = Regex("[ \\t]+\\n")

    /** 这些标签里的内容通常是导航/页脚噪音，整块丢掉 —— 保留它们只会稀释正文 */
    private val NOISE_BLOCK = Regex(
        "<(nav|footer|aside)\\b[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun isHtml(contentType: String, body: String): Boolean {
        if (contentType.contains("html", ignoreCase = true)) return true
        val head = body.take(400).trimStart()
        return head.startsWith("<!doctype", ignoreCase = true) ||
            head.startsWith("<html", ignoreCase = true)
    }

    /**
     * @param aggressive 去掉 nav/footer/aside 整块。
     *   对「读一篇文章」是对的，但对「看看这个页面有哪些导航项」就会误伤，
     *   所以由调用方决定（工具里暴露成 `skip_nav` 选项）。
     */
    fun extract(body: String, aggressive: Boolean = true): String {
        var text = body
        text = SCRIPT.replace(text, " ")
        text = STYLE.replace(text, " ")
        text = NOSCRIPT.replace(text, " ")
        text = COMMENT.replace(text, " ")
        // <head> 里除了 title 没有值得看的，整块去掉
        text = HEAD.replace(text, " ")
        if (aggressive) text = NOISE_BLOCK.replace(text, " ")

        text = BR.replace(text, "\n")
        text = BLOCK_END.replace(text, "\n")
        text = TAG.replace(text, "")

        text = decodeEntities(text)
        text = text.replace('\u00A0', ' ')
        text = TRAILING_SPACE.replace(text, "\n")
        text = BLANK_LINES.replace(text, "\n\n")

        return text.lines().joinToString("\n") { it.trim() }.trim()
    }

    /**
     * 常用实体解码。
     * 只处理最常见的那些 —— 完整的实体表有两千多项，全塞进来是浪费。
     * 数字实体（`&#123;` / `&#x1F600;`）单独处理，因为中文站点里很常见。
     */
    fun decodeEntities(s: String): String {
        var out = s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&") // 必须最后做，否则 &amp;lt; 会被解成 <

        out = NUMERIC.replace(out) { m ->
            val raw = m.groupValues[1]
            val code = runCatching {
                if (raw.startsWith("x") || raw.startsWith("X")) {
                    raw.drop(1).toInt(16)
                } else {
                    raw.toInt()
                }
            }.getOrNull()
            if (code == null || code <= 0 || code > 0x10FFFF) m.value else String(Character.toChars(code))
        }
        return out
    }

    private val NUMERIC = Regex("&#(x?[0-9A-Fa-f]+);")
}
