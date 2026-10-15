package com.dshwatch.domain.agent.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExecTruncateTest {

    @Test
    fun `short text is returned untouched`() {
        val (text, truncated) = truncateHeadTail("hello", 100)
        assertThat(text).isEqualTo("hello")
        assertThat(truncated).isFalse()
    }

    @Test
    fun `exactly at the limit is not truncated`() {
        val raw = "a".repeat(100)
        val (text, truncated) = truncateHeadTail(raw, 100)
        assertThat(text).isEqualTo(raw)
        assertThat(truncated).isFalse()
    }

    @Test
    fun `long text keeps both head and tail`() {
        val raw = "HEAD" + "x".repeat(5000) + "TAIL"
        val (text, truncated) = truncateHeadTail(raw, 1000)

        assertThat(truncated).isTrue()
        assertThat(text).startsWith("HEAD")
        assertThat(text).endsWith("TAIL")
        assertThat(text).contains("省略")
    }

    @Test
    fun `surrogate pairs are never cut in half`() {
        // emoji 占两个 char，切一半会产生无效字符串（写进 JSON 会坏）
        val raw = "😀".repeat(2000)
        repeat(50) { i ->
            val (text, _) = truncateHeadTail(raw, i * 3 + 1)
            assertThat(text.any { Character.isHighSurrogate(it) && text.indexOf(it) == text.length - 1 })
                .isFalse()
            // 每个代理对必须成对出现
            val highs = text.count { Character.isHighSurrogate(it) }
            val lows = text.count { Character.isLowSurrogate(it) }
            assertThat(lows).isEqualTo(highs)
        }
    }

    @Test
    fun `zero limit yields empty but reports truncation`() {
        val (text, truncated) = truncateHeadTail("abc", 0)
        assertThat(text).isEmpty()
        assertThat(truncated).isTrue()
    }

    @Test
    fun `sink keeps everything when under the limit`() {
        val sink = OutputSink(1000)
        sink.append("hello ")
        sink.append("world")
        assertThat(sink.text()).isEqualTo("hello world")
        assertThat(sink.truncated).isFalse()
    }

    @Test
    fun `sink keeps head and tail when over the limit`() {
        val sink = OutputSink(200)
        sink.append("BEGIN")
        sink.append("x".repeat(10_000))
        sink.append("END")

        val text = sink.text()
        assertThat(sink.truncated).isTrue()
        assertThat(text).startsWith("BEGIN")
        assertThat(text).endsWith("END")
        assertThat(text).contains("省略")
        // 内存占用恒定：内容远大于上限，但结果文本仍在 3 倍上限之内（含省略标记）
        assertThat(text.length).isLessThan(600)
    }

    @Test
    fun `sink keeps consuming after the limit so the child never blocks`() {
        // 这条是防「脚本卡死在 println」的：超过上限仍要接受数据
        val sink = OutputSink(50)
        repeat(1000) { sink.append("line-$it\n") }
        assertThat(sink.truncated).isTrue()
        assertThat(sink.text()).contains("line-999")
    }

    @Test
    fun `sink with zero limit drops everything but keeps draining`() {
        val sink = OutputSink(0)
        sink.append("abc")
        assertThat(sink.text()).isEmpty()
        assertThat(sink.truncated).isTrue()
    }
}
