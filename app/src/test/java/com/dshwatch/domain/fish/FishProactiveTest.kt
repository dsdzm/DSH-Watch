package com.dshwatch.domain.fish

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

/**
 * 主动发言的时间规则。
 *
 * 这一层的错误很难在真机上发现：它一天只跑四次，错了要等到明天才看得出来，
 * 而那时候人已经不在调代码的状态里了。所以能算的都在这里算清楚。
 */
class FishProactiveTest {

    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun clockOf(ts: Long): String = FishProactive.clockOf(ts)

    @Test
    fun `整理时间列表：去重、补零、排序、容忍全角冒号`() {
        val list = FishProactive.normalize("7:00, 12：00, 18:00, 07:00，22:30")
        assertThat(list).isEqualTo(listOf("07:00", "12:00", "18:00", "22:30"))
    }

    @Test
    fun `认不出的时间点被丢掉，不会连累别的`() {
        // 用户把其中一个改坏了，另外三个仍然该响
        val list = FishProactive.normalize("07:00, 25:00, abc, 12:00")
        assertThat(list).isEqualTo(listOf("07:00", "12:00"))
    }

    @Test
    fun `格式校验`() {
        assertThat(FishProactive.parseMinutes("00:00")).isEqualTo(0)
        assertThat(FishProactive.parseMinutes("23:59")).isEqualTo(23 * 60 + 59)
        assertThat(FishProactive.parseMinutes("24:00")).isNull()
        assertThat(FishProactive.parseMinutes("12:60")).isNull()
        assertThat(FishProactive.parseMinutes("")).isNull()
        assertThat(FishProactive.parseMinutes("12")).isNull()
    }

    @Test
    fun `今天还没到这个点就排在今天`() {
        val now = at(6, 0)
        val next = FishProactive.nextAt(now, "07:00")
        assertThat(next).isNotNull()
        assertThat(clockOf(next!!)).isEqualTo("07:00")
        // 同一天
        assertThat(next - now).isLessThan(2 * 60 * 60 * 1000L)
    }

    @Test
    fun `已经过了就排到明天`() {
        val now = at(8, 0)
        val next = FishProactive.nextAt(now, "07:00")!!
        assertThat(clockOf(next)).isEqualTo("07:00")
        // 至少 20 小时之后
        assertThat(next - now).isGreaterThan(20 * 60 * 60 * 1000L)
    }

    @Test
    fun `正落在这一分钟也不会立刻响`() {
        // 否则排完就触发，触发时又排到同一分钟，一天里连着响
        val now = at(7, 0)
        val next = FishProactive.nextAt(now, "07:00")!!
        assertThat(next - now).isGreaterThan(60_000L)
    }

    @Test
    fun `四个默认点里取最近的那个`() {
        val now = at(13, 0)
        val next = FishProactive.nextDefault(now, FishProactive.DEFAULT_TIMES)!!
        assertThat(clockOf(next)).isEqualTo("18:00")
    }

    @Test
    fun `一次性计划比每天的四个点更早时优先`() {
        val now = at(13, 0)
        val plan = now + 30 * 60 * 1000L
        val next = FishProactive.next(
            now = now,
            times = FishProactive.DEFAULT_TIMES,
            plans = listOf(plan),
            enabled = true,
        )!!
        assertThat(next).isEqualTo(plan)
    }

    @Test
    fun `关掉总开关之后，她自己约的仍然生效`() {
        val now = at(13, 0)
        val plan = now + 30 * 60 * 1000L
        assertThat(
            FishProactive.next(now, FishProactive.DEFAULT_TIMES, listOf(plan), enabled = false),
        ).isEqualTo(plan)
        // 没有计划就彻底安静
        assertThat(
            FishProactive.next(now, FishProactive.DEFAULT_TIMES, emptyList(), enabled = false),
        ).isNull()
    }

    @Test
    fun `过期的计划不参与排队`() {
        val now = at(13, 0)
        val past = now - 10 * 60 * 1000L
        val next = FishProactive.next(
            now = now,
            times = emptyList(),
            plans = listOf(past),
            enabled = false,
        )
        assertThat(next).isNull()
    }

    @Test
    fun `提示里说清这不是对方说的话，并且禁止把机制讲出来`() {
        val hint = FishProactive.hint(
            at = at(12, 5),
            note = "问他面试结果",
            recentUser = "今天有点紧张",
        )
        assertThat(hint).contains("对方看不到")
        assertThat(hint).contains("12:05")
        assertThat(hint).contains("问他面试结果")
        assertThat(hint).contains("今天有点紧张")
        // 这几个词一出现，戏就完了
        assertThat(hint).contains("不要提")
        assertThat(hint).contains("[已读]")
    }

    @Test
    fun `通知只取第一句，太长才截断`() {
        assertThat(FishProactive.preview("吃了吗\n还没")).isEqualTo("吃了吗")
        assertThat(FishProactive.preview("  ")).isEmpty()

        val long = "一".repeat(100)
        val preview = FishProactive.preview(long)
        assertThat(preview.length).isEqualTo(61)
        assertThat(preview.endsWith("…")).isTrue()
    }

    @Test
    fun `通知不显示思考块`() {
        // 界面上剥了、通知里没剥，等于没修：锁屏上那行字同样是"她发的"
        val raw = "<thought>要不要问吃没吃</thought>\n吃了吗"
        assertThat(FishProactive.preview(raw)).isEqualTo("吃了吗")
    }
}
