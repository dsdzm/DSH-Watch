package com.dshwatch.domain.roleplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * 聊天节奏。
 *
 * 这些数字不是随便定的：读得太快像抢话，想得太短像脚本，打字太快就是打印机。
 * 测试盯的是**单调性与上下限** —— 具体数值以后可以调，但"越长越慢、慢得有限"
 * 这条不能破，否则长回复会播到用户以为卡死。
 */
class FishRhythmTest {

    @Test
    fun `消息越长读得越久，但有上限`() {
        assertThat(FishRhythm.readDelay("嗯")).isLessThan(FishRhythm.readDelay("今天加班到现在还没吃饭"))
        assertThat(FishRhythm.readDelay("字".repeat(500))).isAtMost(2_200L)
    }

    @Test
    fun `思考时间有下限，不会像在等脚本`() {
        assertThat(FishRhythm.thinkDelay("")).isAtLeast(550L)
        assertThat(FishRhythm.thinkDelay("字".repeat(500))).isAtMost(2_600L)
    }

    @Test
    fun `打字时间按字数走，并封顶`() {
        assertThat(FishRhythm.typingDelay("嗯")).isAtLeast(260L)
        assertThat(FishRhythm.typingDelay("字".repeat(20))).isEqualTo(2_200L)
        assertThat(FishRhythm.typingDelay("字".repeat(200))).isAtMost(2_600L)
    }

    @Test
    fun `条间停顿有随机但有范围`() {
        val r = Random(7)
        repeat(50) {
            val gap = FishRhythm.bubbleGap(r)
            assertThat(gap).isAtLeast(350L)
            assertThat(gap).isLessThan(810L)
        }
    }

    @Test
    fun `沉默标记的几种写法都认得`() {
        listOf("[已读]", "（已读）", "(已读)", "已读").forEach {
            assertThat(FishRhythm.isSilent(it)).isTrue()
        }
        assertThat(FishRhythm.isSilent("已读不回")).isFalse()
        assertThat(FishRhythm.isSilent("嗯")).isFalse()
    }
}
