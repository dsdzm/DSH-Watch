package com.dshwatch.domain

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Test

class TranscriptFileTest {

    @Test
    fun `utf8 without bom decodes as is`() {
        val s = "#===========================================#\n[USER]:\n雨下到第九天。"
        assertThat(TranscriptFile.decode(s.toByteArray(Charsets.UTF_8))).isEqualTo(s)
    }

    @Test
    fun `utf8 bom is stripped`() {
        val body = "[AI]:\n他把手电往下压了压。"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray()
        // BOM 留着的话，第一行会变成 "\uFEFF[AI]:\n"，分段标记就认不出来了
        assertThat(TranscriptFile.decode(bytes)).isEqualTo(body)
    }

    @Test
    fun `gb18030 file is not mojibake`() {
        // Windows 记事本默认存 GBK，这是最常见的「导入进来全是锟斤拷」的来源
        val s = "[USER]:\n涵洞的水位已经漫过了标尺的第三格。"
        val bytes = s.toByteArray(Charset.forName("GB18030"))
        assertThat(TranscriptFile.decode(bytes)).isEqualTo(s)
    }

    @Test
    fun `empty input is empty string`() {
        assertThat(TranscriptFile.decode(ByteArray(0))).isEmpty()
    }

    @Test
    fun `stream over the limit is refused whole`() {
        // 宁可整个不导，也不要把半截对话写进会话 —— 截断点之后的上下文全丢了，
        // 用户却以为导入成功
        val big = ByteArray(4096) { 'a'.code.toByte() }
        assertThat(TranscriptFile.read(ByteArrayInputStream(big), limit = 1024)).isNull()
        assertThat(TranscriptFile.read(ByteArrayInputStream(big), limit = 8192)).hasLength(4096)
    }

    @Test
    fun `text files are recognised by extension`() {
        assertThat(TranscriptFile.looksLikeText("exports/对话-1105.txt")).isTrue()
        assertThat(TranscriptFile.looksLikeText("A.TXT")).isTrue()
        assertThat(TranscriptFile.looksLikeText("note.md")).isTrue()
        assertThat(TranscriptFile.looksLikeText("photo.png")).isFalse()
        assertThat(TranscriptFile.looksLikeText("txt")).isFalse()
    }
}
