package com.dshwatch

import com.dshwatch.ui.markdown.MarkdownParser
import com.dshwatch.ui.markdown.MdBlock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses headings with level`() {
        val blocks = MarkdownParser.parse("# 一级\n## 二级\n### 三级")
        assertThat(blocks).hasSize(3)
        assertThat((blocks[0] as MdBlock.Heading).level).isEqualTo(1)
        assertThat((blocks[1] as MdBlock.Heading).text).isEqualTo("二级")
    }

    @Test
    fun `parses fenced code block with language`() {
        val src = "说明：\n\n```kotlin\nfun main() {}\n```\n\n结束"
        val blocks = MarkdownParser.parse(src)
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertThat(code.language).isEqualTo("kotlin")
        assertThat(code.code).isEqualTo("fun main() {}")
        assertThat(code.closed).isTrue()
        assertThat(blocks.filterIsInstance<MdBlock.Paragraph>()).hasSize(2)
    }

    @Test
    fun `unclosed code block still renders while streaming`() {
        val blocks = MarkdownParser.parse("```python\nprint(1)")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertThat(code.closed).isFalse()
        assertThat(code.code).isEqualTo("print(1)")
    }

    @Test
    fun `parses bullets and ordered list`() {
        val blocks = MarkdownParser.parse("- 甲\n- 乙\n\n1. 一\n2. 二")
        assertThat(blocks.filterIsInstance<MdBlock.Bullet>()).hasSize(2)
        val ordered = blocks.filterIsInstance<MdBlock.Ordered>()
        assertThat(ordered).hasSize(2)
        assertThat(ordered[0].number).isEqualTo("1")
    }

    @Test
    fun `parses table with header separator`() {
        val src = "| 名称 | 值 |\n|---|---|\n| a | 1 |\n| b | 2 |"
        val table = MarkdownParser.parse(src).filterIsInstance<MdBlock.Table>().single()
        assertThat(table.header).isEqualTo(listOf("名称", "值"))
        assertThat(table.rows).hasSize(2)
        assertThat(table.rows[1]).isEqualTo(listOf("b", "2"))
    }

    @Test
    fun `parses blockquote and divider`() {
        val blocks = MarkdownParser.parse("> 引用第一行\n> 第二行\n\n---")
        assertThat((blocks[0] as MdBlock.Quote).text).isEqualTo("引用第一行\n第二行")
        assertThat(blocks[1]).isEqualTo(MdBlock.Divider)
    }

    @Test
    fun `respects max blocks limit`() {
        val src = (1..500).joinToString("\n\n") { "段落 $it" }
        val blocks = MarkdownParser.parse(src, maxBlocks = 10)
        assertThat(blocks.size).isAtMost(10)
    }

    @Test
    fun `plain text becomes a single paragraph keeping line breaks`() {
        val blocks = MarkdownParser.parse("第一行\n第二行")
        assertThat(blocks).hasSize(1)
        assertThat((blocks[0] as MdBlock.Paragraph).text).isEqualTo("第一行\n第二行")
    }
}
