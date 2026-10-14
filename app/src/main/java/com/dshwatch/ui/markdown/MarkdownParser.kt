package com.dshwatch.ui.markdown

/**
 * 轻量 Markdown 解析器（无 WebView、无第三方库）。
 *
 * 为什么自己写：
 *  AndroidView + WebView 在 512MB 内存的手表上光初始化就要 30~60MB，
 *  而且每次流式刷新都要重新 loadData，会直接把表卡死。
 *  这里只解析「聊天里真正会出现」的子集，成本是纯字符串扫描，可以随流式刷新反复调用。
 *
 * 支持：标题、无序/有序列表、引用、分割线、围栏代码块、表格、以及行内样式（见 MarkdownInline）。
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val level: Int, val text: String, val marker: Char) : MdBlock
    data class Ordered(val level: Int, val number: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock

    /** 表格：header 为空表示只有数据行 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

object MarkdownParser {

    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^([-*+])\\s+(.*)$")
    private val orderedRegex = Regex("^(\\d{1,3})[.)]\\s+(.*)$")
    private val quoteRegex = Regex("^>\\s?(.*)$")
    private val dividerRegex = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    private val tableSepRegex = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val fenceRegex = Regex("^\\s*(```|~~~)\\s*([\\w+#.\\-]*)\\s*$")

    fun parse(source: String, maxBlocks: Int = 1_000): List<MdBlock> {
        if (source.isEmpty()) return emptyList()
        val lines = source.split('\n')
        val blocks = ArrayList<MdBlock>(32)
        var i = 0

        while (i < lines.size && blocks.size < maxBlocks) {
            val raw = lines[i]
            val line = raw.trimEnd()

            // 1) 围栏代码块
            val fence = fenceRegex.find(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val lang = fence.groupValues[2]
                val sb = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    val cur = lines[i]
                    if (cur.trimStart().startsWith(marker) && fenceRegex.find(cur.trimEnd()) != null) {
                        closed = true
                        i++
                        break
                    }
                    sb.append(cur).append('\n')
                    i++
                }
                blocks.add(MdBlock.Code(lang.lowercase(), sb.toString().trimEnd('\n'), closed))
                continue
            }

            // 2) 空行
            if (line.isBlank()) {
                i++
                continue
            }

            // 3) 分割线
            if (dividerRegex.matches(line)) {
                blocks.add(MdBlock.Divider)
                i++
                continue
            }

            // 4) 标题
            val h = headingRegex.find(line)
            if (h != null) {
                blocks.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim()))
                i++
                continue
            }

            // 5) 表格（当前行含 |，下一行是分隔行）
            if (line.contains('|') && i + 1 < lines.size && tableSepRegex.matches(lines[i + 1].trim())) {
                val header = splitRow(line)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows.add(splitRow(lines[i]))
                    i++
                }
                blocks.add(MdBlock.Table(header, rows))
                continue
            }

            // 6) 引用
            val q = quoteRegex.find(line)
            if (q != null) {
                val sb = StringBuilder(q.groupValues[1])
                i++
                while (i < lines.size) {
                    val m = quoteRegex.find(lines[i].trimEnd()) ?: break
                    sb.append('\n').append(m.groupValues[1])
                    i++
                }
                blocks.add(MdBlock.Quote(sb.toString()))
                continue
            }

            // 7) 列表
            val b = bulletRegex.find(line)
            if (b != null) {
                blocks.add(MdBlock.Bullet(indentLevel(raw), b.groupValues[2], b.groupValues[1][0]))
                i++
                continue
            }
            val o = orderedRegex.find(line)
            if (o != null) {
                blocks.add(MdBlock.Ordered(indentLevel(raw), o.groupValues[1], o.groupValues[2]))
                i++
                continue
            }

            // 8) 段落：一直吃到空行或下一个块级标记
            val sb = StringBuilder(line.trim())
            i++
            while (i < lines.size) {
                val cur = lines[i]
                val t = cur.trimEnd()
                if (t.isBlank()) break
                if (fenceRegex.find(t) != null) break
                if (headingRegex.find(t) != null) break
                if (bulletRegex.find(t) != null) break
                if (orderedRegex.find(t) != null) break
                if (quoteRegex.find(t) != null) break
                if (dividerRegex.matches(t)) break
                sb.append('\n').append(t.trim())
                i++
            }
            blocks.add(MdBlock.Paragraph(sb.toString()))
        }

        return blocks
    }

    /** 2 空格或 1 个 Tab 算一级缩进 */
    private fun indentLevel(raw: String): Int {
        var spaces = 0
        for (c in raw) {
            when (c) {
                ' ' -> spaces++
                '\t' -> spaces += 2
                else -> return (spaces / 2).coerceAtMost(4)
            }
        }
        return 0
    }

    fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split('|').map { it.trim() }
    }
}
