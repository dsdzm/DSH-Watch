package com.dshwatch

import com.dshwatch.domain.DeepSeekPricing
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/**
 * 峰谷时段是**会改的**规则（这份实现已经跟着官方改过一轮：
 * 早先是「00:30–08:30 优惠」，2026-08 起换成工作日两段高峰 + 周末全天空闲）。
 * 所以这里把边界钉死，将来改动时这些用例会立刻变红，提醒去核对官方公告。
 */
class DeepSeekPricingTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(2026, 10, day, hour, minute)

    // 2026-10-14 是周三，10-16 周五，10-17 周六，10-19 周一

    @Test
    fun `workday 10am is peak`() {
        val w = DeepSeekPricing.windowAt(at(14, 10))
        assertThat(w.peak).isTrue()
        // 下一次切换是 12:00，还有 2 小时
        assertThat(w.untilChangeMs).isEqualTo(2 * 3_600_000L)
    }

    @Test
    fun `peak starts exactly at 9am`() {
        assertThat(DeepSeekPricing.windowAt(at(14, 9, 0)).peak).isTrue()
        assertThat(DeepSeekPricing.windowAt(at(14, 8, 59)).peak).isFalse()
    }

    @Test
    fun `peak ends exactly at 12am`() {
        assertThat(DeepSeekPricing.windowAt(at(14, 11, 59)).peak).isTrue()
        assertThat(DeepSeekPricing.windowAt(at(14, 12, 0)).peak).isFalse()
    }

    @Test
    fun `workday lunch break is off peak`() {
        val w = DeepSeekPricing.windowAt(at(14, 13))
        assertThat(w.peak).isFalse()
        // 13:00 之后下一次切换是 14:00
        assertThat(w.untilChangeMs).isEqualTo(3_600_000L)
    }

    @Test
    fun `workday afternoon 15pm is peak again`() {
        assertThat(DeepSeekPricing.windowAt(at(14, 15)).peak).isTrue()
    }

    @Test
    fun `workday evening is off peak and rolls to next workday 9am`() {
        val w = DeepSeekPricing.windowAt(at(14, 20))
        assertThat(w.peak).isFalse()
        // 周三 20:00 → 周四 09:00 = 13 小时
        assertThat(w.untilChangeMs).isEqualTo(13 * 3_600_000L)
    }

    @Test
    fun `saturday is off peak all day`() {
        assertThat(DeepSeekPricing.windowAt(at(17, 10)).peak).isFalse()
        assertThat(DeepSeekPricing.windowAt(at(17, 15)).peak).isFalse()
        assertThat(DeepSeekPricing.windowAt(at(18, 10)).peak).isFalse()
    }

    @Test
    fun `friday evening rolls all the way to monday 9am`() {
        // 这是最容易写错的一个：周五晚的下一个「高峰」不是明天，而是下周一。
        // 中间整段周末都是空闲，所以倒计时会跳到 61 小时。
        val w = DeepSeekPricing.windowAt(at(16, 20))
        assertThat(w.peak).isFalse()
        assertThat(w.untilChangeMs).isEqualTo(61 * 3_600_000L)
    }

    @Test
    fun `saturday counts down to monday 9am`() {
        val w = DeepSeekPricing.windowAt(at(17, 12))
        assertThat(w.peak).isFalse()
        // 周六 12:00 → 周一 09:00 = 45 小时
        assertThat(w.untilChangeMs).isEqualTo(45 * 3_600_000L)
    }

    @Test
    fun `monday morning is peak immediately at 9am`() {
        assertThat(DeepSeekPricing.windowAt(at(19, 9, 30)).peak).isTrue()
    }

    @Test
    fun `deepseek detection covers official url model name and provider name`() {
        assertThat(DeepSeekPricing.isDeepSeek("https://api.deepseek.com/v1", "deepseek-chat")).isTrue()
        // 中转站域名里没有 deepseek，但模型名有
        assertThat(DeepSeekPricing.isDeepSeek("https://my-gateway.example/v1", "deepseek-reasoner")).isTrue()
        // 自建时把名字起成别的
        assertThat(DeepSeekPricing.isDeepSeek("", "", "我的 DeepSeek")).isTrue()
        assertThat(DeepSeekPricing.isDeepSeek("https://api.openai.com/v1", "gpt-4o")).isFalse()
    }
}
