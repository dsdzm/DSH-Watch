package com.dshwatch.domain.fish

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

/**
 * 动态的纯逻辑。
 *
 * 这里钉的每一条都对应一次真实的模型输出：序号、项目符号、引号、前缀、
 * 以及"一句太长"。清洗漏一种，动态页上就会挂着一条 `1.` 或者一段三百字的作文。
 */
class FishMomentsTest {

    @Test
    fun `parse strips numbering bullets quotes and prefixes`() {
        val raw = """
            1. 楼下那家店今天没开门，白跑一趟。
            - 尾巴又抽筋了，懒得动。
            · 他说我记性好，其实只是记得住跟他有关的事。
            动态：今天有点困。
        """.trimIndent()

        val out = FishMoments.parse(raw, limit = 10)
        assertThat(out).hasSize(4)
        assertThat(out[0]).isEqualTo("楼下那家店今天没开门，白跑一趟。")
        assertThat(out[1]).isEqualTo("尾巴又抽筋了，懒得动。")
        assertThat(out[2]).isEqualTo("他说我记性好，其实只是记得住跟他有关的事。")
        assertThat(out[3]).isEqualTo("今天有点困。")
    }

    @Test
    fun `parse drops quotes and blank lines`() {
        val out = FishMoments.parse("\n\n“今天下了很久的雨。”\n\n", limit = 3)
        assertThat(out).containsExactly("今天下了很久的雨。")
    }

    @Test
    fun `parse keeps only the requested number of lines`() {
        val raw = "第一条写得够长。\n第二条也够长。\n第三条也是。"
        assertThat(FishMoments.parse(raw, limit = 2)).hasSize(2)
    }

    @Test
    fun `parse drops fragments that are too short to be a thought`() {
        // 「嗯。」这种不是动态，是没生成出来
        val out = FishMoments.parse("嗯。\n今天在楼下看见一只三花的猫。", limit = 2)
        assertThat(out).containsExactly("今天在楼下看见一只三花的猫。")
    }

    @Test
    fun `parse truncates runaway text`() {
        val long = "啊".repeat(400)
        val out = FishMoments.parse(long, limit = 1)
        assertThat(out.first().length).isEqualTo(FishMoments.MAX_LEN)
    }

    @Test
    fun `day key and start of day agree on local midnight`() {
        val noon = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 30)
        }.timeInMillis
        val midnight = FishMoments.startOfDay(noon)

        assertThat(FishMoments.dayKey(midnight)).isEqualTo(FishMoments.dayKey(noon))
        // 0 点整也要算在"这一天"，不能因为毫秒差被推到前一天
        assertThat(midnight).isEqualTo(FishMoments.startOfDay(midnight))
        assertThat(FishMoments.dayKey(midnight - 1)).isNotEqualTo(FishMoments.dayKey(noon))
    }

    @Test
    fun `stamp says today and yesterday`() {
        val now = System.currentTimeMillis()
        val today = FishMoments.stamp(now, now)
        val yesterday = FishMoments.stamp(FishMoments.startOfDay(now) - 12 * 3600_000L, now)

        assertThat(today).startsWith("今天")
        assertThat(yesterday).startsWith("昨天")
    }

    @Test
    fun `mood label never exceeds a circle screen width`() {
        assertThat(FishMoments.moodOf("有点低落但是还好").length).isAtMost(4)
        assertThat(FishMoments.moodOf("").length).isEqualTo(0)
    }
}
