package com.dshwatch.data.store

import com.dshwatch.domain.fish.FishMoment
import com.dshwatch.domain.fish.FishMoments
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 动态存储。
 *
 * 重点不在"能存能读"，而在两条会真实发生的边界：
 *  1. 模型在没新素材时会一字不差地重写一条 —— 不能因此出现两条一样的；
 *  2. 一天连点三次「写一条」之后，第四次要被挡住（[FishMoments.MAX_PER_DAY]）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MomentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = MomentStore(
        dir = tmp.root,
        json = Json { ignoreUnknownKeys = true },
        io = UnconfinedTestDispatcher(),
    )

    private fun moment(at: Long, text: String) = FishMoment(id = (at / 1000).toInt(), at = at, text = text)

    @Test
    fun `adds in order and reads back newest first`() = runTest {
        val s = store()
        s.add(moment(1_000, "早上去了趟菜市场。"))
        s.add(moment(2_000, "下午风很大。"))

        val all = s.all()
        assertThat(all).hasSize(2)
        assertThat(all.first().text).isEqualTo("下午风很大。")
    }

    @Test
    fun `identical text is not stored twice`() = runTest {
        val s = store()
        assertThat(s.add(moment(1_000, "今天有点困。"))).isNotNull()
        assertThat(s.add(moment(9_000, "今天有点困。"))).isNull()
        assertThat(s.all()).hasSize(1)
    }

    @Test
    fun `counts only today`() = runTest {
        val s = store()
        val now = System.currentTimeMillis()
        s.add(moment(now, "今天写的第一条。"))
        s.add(moment(FishMoments.startOfDay(now) - 3600_000L, "昨天的。"))

        assertThat(s.countOn(now)).isEqualTo(1)
    }

    @Test
    fun `removing the newest leaves the rest`() = runTest {
        val s = store()
        val a = s.add(moment(1_000, "第一条记录。"))!!
        s.add(moment(2_000, "第二条记录。"))

        assertThat(s.remove(a.id)).isTrue()
        assertThat(s.all().map { it.text }).containsExactly("第二条记录。")
    }

    @Test
    fun `keeps at most the configured number of moments`() = runTest {
        val s = store()
        repeat(FishMoments.MAX_KEEP + 5) { i ->
            s.add(moment(1_000L + i * 60_000L, "第 $i 条，够长了吧。"))
        }
        assertThat(s.all()).hasSize(FishMoments.MAX_KEEP)
        // 保留的是最近的那些
        assertThat(s.all().first().text).contains("${FishMoments.MAX_KEEP + 4}")
    }

    @Test
    fun `survives a corrupted file by starting over`() = runTest {
        val file = tmp.newFile("fish-moments.json")
        file.writeText("{ not json")
        val s = store()

        assertThat(s.all()).isEmpty()
        s.add(moment(1_000, "坏过一次之后还能写。"))
        assertThat(s.all()).hasSize(1)
    }
}
