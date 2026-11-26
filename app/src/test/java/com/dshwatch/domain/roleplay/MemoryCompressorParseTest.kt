package com.dshwatch.domain.roleplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 压缩输出 → 记忆事件的解析。
 *
 * 这一步决定了「哪些事记得牢、哪些只是流水」以及「哪条是在说同一件事」，
 * 是整套类人记忆的入口。模型输出不可控，所以解析必须容错：
 * 认不出格式时宁可多切一条，也不能整段丢掉。
 */
class MemoryCompressorParseTest {

    private val remembered = listOf(
        MemoryEntry(at = 1_000L, text = "答应了陪她看电影"),
        MemoryEntry(at = 2_000L, text = "用户养了一只叫团子的橘猫"),
    )

    @Test
    fun `标记决定初始情绪强度`() {
        val p = MemoryCompressor.parseEvents(
            """
            [*] 答应了每周日陪她看电影
            [!] 因为搬家的事吵了一架，她哭了
            [-] 用户不太吃香菜
            """.trimIndent(),
            at = 5_000L,
            remembered = emptyList(),
        )

        assertThat(p.fresh).hasSize(3)
        assertThat(p.fresh[0].emotion).isGreaterThan(0.85f)
        assertThat(p.fresh[1].emotion).isWithin(0.1f).of(0.72f)
        assertThat(p.fresh[2].emotion).isLessThan(0.2f)
    }

    @Test
    fun `等号标记指向已有条目，不新增`() {
        val p = MemoryCompressor.parseEvents(
            "[=1] 答应了每周日陪她看电影，约在晚上八点",
            at = 5_000L,
            remembered = remembered,
        )

        assertThat(p.fresh).isEmpty()
        assertThat(p.merges).hasSize(1)
        assertThat(p.merges[0].first.text).isEqualTo("答应了陪她看电影")
        assertThat(p.merges[0].second.text).contains("晚上八点")
    }

    @Test
    fun `编号越界时退化成新事件，不丢内容`() {
        val p = MemoryCompressor.parseEvents(
            "[=99] 一件完全新的事",
            at = 5_000L,
            remembered = remembered,
        )

        assertThat(p.merges).isEmpty()
        assertThat(p.fresh).hasSize(1)
        assertThat(p.fresh[0].text).isEqualTo("一件完全新的事")
    }

    @Test
    fun `模型不按格式时按句子切分兜底`() {
        val p = MemoryCompressor.parseEvents(
            "记忆：用户养了一只猫。她今天搬家了。他们吵了一架。",
            at = 5_000L,
            remembered = emptyList(),
        )

        assertThat(p.fresh).isNotEmpty()
        assertThat(p.fresh.map { it.text }).contains("她今天搬家了")
    }

    @Test
    fun `单条事件有字数上限`() {
        val p = MemoryCompressor.parseEvents(
            "[-] " + "很长的内容".repeat(40),
            at = 5_000L,
            remembered = emptyList(),
        )

        assertThat(p.fresh).hasSize(1)
        assertThat(p.fresh[0].text.length).isAtMost(80)
    }
}
