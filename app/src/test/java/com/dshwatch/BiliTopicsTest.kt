package com.dshwatch

import com.dshwatch.domain.bili.BiliTopics
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 动态选题的解析。
 *
 * 这些用例看着琐碎，但每一条都对应一次真实的翻车：
 * 用户在设置里勾掉全部三项、或者老存档里存着已经删掉的 key，
 * 到模型那里就会变成「没有可写的角度」→ 它自己编一个 → 发出去一条莫名其妙的动态。
 */
class BiliTopicsTest {

    @Test
    fun `default selection parses into all three`() {
        assertThat(BiliTopics.of("life,roast,master"))
            .containsExactly(BiliTopics.LIFE, BiliTopics.ROAST, BiliTopics.MASTER)
            .inOrder()
    }

    @Test
    fun `unknown keys are dropped instead of leaking into prompt`() {
        // 老版本可能存过 `diary` 之类，读到不认识的 key 不能当成有效选题
        assertThat(BiliTopics.of("life,diary,vlog"))
            .containsExactly(BiliTopics.LIFE)
    }

    @Test
    fun `whitespace and empty segments are tolerated`() {
        assertThat(BiliTopics.of(" roast , , master "))
            .containsExactly(BiliTopics.ROAST, BiliTopics.MASTER)
            .inOrder()
    }

    @Test
    fun `order is canonical regardless of how it was saved`() {
        // 设置里反复勾选会让字符串顺序漂移，但给模型的描述必须稳定，
        // 否则同样的选择会得到不同的 prompt、不同的输出风格
        assertThat(BiliTopics.of("master,life,roast"))
            .isEqualTo(listOf(BiliTopics.LIFE, BiliTopics.ROAST, BiliTopics.MASTER))
    }

    @Test
    fun `nothing selected falls back to daily life`() {
        // 全勾掉是合法操作（用户可能只想让它安静发点日常），不能变成空 prompt
        assertThat(BiliTopics.summary("")).contains("生活日常")
        assertThat(BiliTopics.summary("   ")).contains("生活日常")
        assertThat(BiliTopics.summary("unknown")).contains("生活日常")
    }

    @Test
    fun `summary mentions master feelings in first person`() {
        val text = BiliTopics.summary("master")
        assertThat(text).contains("主人")
        // 「第一人称」是防止它写成「主人今天很开心」这种第三人称旁白
        assertThat(text).contains("第一人称")
    }

    @Test
    fun `roast asks for specifics`() {
        // 没有「具体到点」这四个字，模型会写「这个视频很有意思」这种废话
        assertThat(BiliTopics.summary("roast")).contains("具体")
    }
}
