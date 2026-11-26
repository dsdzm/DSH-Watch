package com.dshwatch.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 更新日志的文体约束。
 *
 * 关于页是用户除了对话之外最先看到「这个 App 是什么调性」的地方。
 * 之前这里混进了大量开发期的口语（「她会自己开口了」「一眼分得清」），
 * 还列着五十多个 beta 构建号，看起来像内部 changelog 而不是发布说明。
 *
 * 这些约束写死在测试里，改文案时不会悄悄退回去。
 */
class AppChangelogToneTest {

    private val entries = AppChangelog.entries

    @Test
    fun `只列正式版本，不出现 beta`() {
        assertThat(entries).isNotEmpty()
        assertThat(entries.map { it.version }.none { it.contains("beta") }).isTrue()
    }

    @Test
    fun `版本号按时间倒序，最新在最前`() {
        assertThat(entries.first().version).isEqualTo("1.24.1")
        val dates = entries.map { it.date }
        val descending = dates.zipWithNext().all { (a, b) -> a >= b }
        assertThat(descending).isTrue()
    }

    @Test
    fun `不使用第二人称与拟人化措辞`() {
        val banned = listOf("你", "她", "咱", "一眼", "顺手", "干脆", "说白了", "其实")
        val hits = entries.flatMap { e ->
            e.highlights.filter { h -> banned.any { h.contains(it) } }
                .map { "${e.version}: $it" }
        }
        assertThat(hits).isEmpty()
    }

    @Test
    fun `一条一句，不写实现细节与前后对比`() {
        val banned = listOf("之前", "以前", "原来", "不再", "现在")
        val hits = entries.flatMap { e ->
            e.highlights.filter { h -> banned.any { h.contains(it) } }
                .map { "${e.version}: $it" }
        }
        assertThat(hits).isEmpty()
    }

    @Test
    fun `每版不超过四条`() {
        val over = entries.filter { it.highlights.size > 4 }.map { it.version }
        assertThat(over).isEmpty()
    }
}
