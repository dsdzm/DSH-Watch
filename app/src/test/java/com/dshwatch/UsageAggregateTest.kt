package com.dshwatch

import com.dshwatch.data.usage.UsageRecord
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.domain.model.TokenUsage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageAggregateTest {

    private fun record(
        ts: Long,
        model: String,
        prompt: Int,
        completion: Int,
        priceIn: Double = 2.0,
        priceOut: Double = 8.0,
    ) = UsageRepository.makeRecord(
        usage = TokenUsage(prompt = prompt, completion = completion),
        providerId = "p1",
        providerName = "DeepSeek",
        model = model,
        conversationId = "c1",
        priceInPerMillion = priceIn,
        priceOutPerMillion = priceOut,
        estimated = false,
        now = ts,
    )

    @Test
    fun `cost is derived from million token pricing`() {
        // 1M 输入 @2 元 + 1M 输出 @8 元
        val r = record(ts = 0L, model = "m", prompt = 1_000_000, completion = 1_000_000)
        assertThat(r.cost).isWithin(1e-6).of(10.0)
    }

    @Test
    fun `aggregate respects the time window`() {
        val day = 86_400_000L
        val base = 1_700_000_000_000L
        val records = listOf(
            record(base - 3 * day, "a", 100, 100),
            record(base - 1 * day, "a", 200, 200),
            record(base, "b", 300, 300),
        )
        val all = UsageRepository.aggregate(records, 0L, Long.MAX_VALUE)
        assertThat(all.requests).isEqualTo(3)
        assertThat(all.prompt).isEqualTo(600)
        assertThat(all.completion).isEqualTo(600)
        assertThat(all.total).isEqualTo(1200)

        // 区间是 [from, to)，所以起点要 +1 才能把 base - day 那条排除掉
        val recent = UsageRepository.aggregate(records, base - day + 1, base + 1)
        assertThat(recent.requests).isEqualTo(1)
        assertThat(recent.byModel.single().model).isEqualTo("b")
    }

    @Test
    fun `by model is sorted by total descending`() {
        val records = listOf(
            record(1, "small", 10, 10),
            record(2, "big", 900, 900),
            record(3, "mid", 100, 100),
        )
        val agg = UsageRepository.aggregate(records, 0, Long.MAX_VALUE)
        assertThat(agg.byModel.map { it.model }).containsExactly("big", "mid", "small").inOrder()
    }

    @Test
    fun `by day groups records of the same calendar day`() {
        val day = 86_400_000L
        val base = 1_700_000_000_000L
        val records = listOf(
            record(base, "m", 10, 10),
            record(base + 1_000, "m", 20, 20),
            record(base + day, "m", 30, 30),
        )
        val agg = UsageRepository.aggregate(records, 0, Long.MAX_VALUE)
        assertThat(agg.byDay).hasSize(2)
        assertThat(agg.byDay.sumOf { it.total }).isEqualTo(120)
        assertThat(agg.byDay.first().requests).isEqualTo(2)
    }

    @Test
    fun `empty input yields empty aggregate`() {
        val agg = UsageRepository.aggregate(emptyList(), 0, Long.MAX_VALUE)
        assertThat(agg.isEmpty).isTrue()
        assertThat(agg.total).isEqualTo(0)
    }

    @Test
    fun `estimated flag is preserved on records`() {
        val r: UsageRecord = UsageRepository.makeRecord(
            usage = TokenUsage(prompt = 1, completion = 1),
            providerId = "p",
            providerName = "n",
            model = "m",
            conversationId = "c",
            priceInPerMillion = 0.0,
            priceOutPerMillion = 0.0,
            estimated = true,
            now = 0L,
        )
        assertThat(r.estimated).isTrue()
        assertThat(r.cost).isEqualTo(0.0)
    }
}
