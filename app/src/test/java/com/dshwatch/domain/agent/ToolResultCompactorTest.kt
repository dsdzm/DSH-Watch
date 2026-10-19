package com.dshwatch.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 大工具结果落盘摘要的格式。
 *
 * 这些断言锁住的是「模型还能不能找回中间内容」：
 * 只要摘要里路径或 offset 提示丢了一样，模型就会退回去重跑工具，
 * 上下文又会被撑爆 —— 等于这次优化白做。
 */
class ToolResultCompactorTest {

    @Test
    fun `short content passes through untouched`() {
        val text = "42"
        assertThat(ToolResultCompactor.digest(text, "notes/tool-output/x.txt")).isEqualTo(text)
    }

    @Test
    fun `long content keeps head tail and the full-text path`() {
        val head = "HEAD-LINE\n".repeat(300)          // 3000 字
        val middle = "MIDDLE-MARKER-不该出现在摘要里".repeat(20)
        val tail = "TAIL-LINE\n".repeat(300)          // 3000 字
        val content = head + middle + tail

        val digest = ToolResultCompactor.digest(content, "notes/tool-output/read_file-abc.txt")

        assertThat(digest).startsWith(content.take(ToolResultCompactor.HEAD_CHARS))
        assertThat(digest).endsWith(content.takeLast(ToolResultCompactor.TAIL_CHARS))
        assertThat(digest).contains("notes/tool-output/read_file-abc.txt")
        assertThat(digest).contains("read_file")
        assertThat(digest).contains("offset=")
        // 中间那段的标记不应该被塞进摘要 —— 否则等于没省上下文
        assertThat(digest).doesNotContain("MIDDLE-MARKER")
        // 但必须明确告诉模型「省了多少」，否则它会以为内容本身就这么短
        assertThat(digest).contains("中间")
        assertThat(digest).contains("已省略")
    }

    @Test
    fun `digest without a path tells the model the middle is lost`() {
        val content = "A".repeat(3_000) + "B".repeat(3_000)
        val digest = ToolResultCompactor.digest(content, relPath = null)

        assertThat(digest).contains("落盘失败")
        assertThat(digest).doesNotContain("notes/tool-output/")
    }

    @Test
    fun `digest is much smaller than the original`() {
        val content = "x".repeat(40_000)
        val digest = ToolResultCompactor.digest(content, "notes/tool-output/x.txt")
        assertThat(digest.length).isLessThan(4_000)
    }
}
