package com.dshwatch.data.store

import com.dshwatch.domain.roleplay.EmotionScore
import com.dshwatch.domain.roleplay.MemoryEntry
import com.dshwatch.domain.roleplay.MemorySimilarity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Calendar

/**
 * 「像人一样的记忆」的行为契约。
 *
 * 用户的原话是「记忆按人的仿真来做，会有遗忘，按照时间来遗忘，多次提到会加深
 * 印象。带有极大情绪波动和令人印象深刻的，会逐渐变成永久记忆」。
 *
 * 所以这里的用例是那四句话的逐条翻译 —— 参数可以调，行为不能丢。
 */
class MemoryLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 86_400_000L
    private val base = 1_700_000_000_000L

    private fun store(now: Long) = MemoryStore(
        workspaceRoot = { tmp.root },
        io = UnconfinedTestDispatcher(),
        clock = { now },
    )

    // ---- 1. 按时间遗忘 ----

    @Test
    fun `普通记忆随时间变淡，但没有断崖式消失`() {
        val e = MemoryEntry(at = base, text = "今天一起吃了火锅")

        assertThat(e.retention(base)).isEqualTo(1f)
        assertThat(e.retention(base + day)).isWithin(0.04f).of(0.83f)
        assertThat(e.retention(base + 7 * day)).isWithin(0.06f).of(0.46f)
        assertThat(e.retention(base + 30 * day)).isWithin(0.06f).of(0.21f)
        // 三个月后还有一点印象 —— 这是幂律曲线相对指数曲线的意义
        assertThat(e.retention(base + 90 * day)).isGreaterThan(0.05f)
        assertThat(e.retention(base + 90 * day)).isLessThan(0.15f)
    }

    @Test
    fun `一周前的事还算清楚，一个月前只剩大概`() {
        val e = MemoryEntry(at = base, text = "聊过她想搬去海边住")
        assertThat(e.isBlurred(base + 3 * day)).isFalse()
        assertThat(e.isBlurred(base + 30 * day)).isTrue()
        assertThat(e.isFaded(base + 30 * day)).isFalse()
        // 半年后才算真的想不起来
        assertThat(e.isFaded(base + 200 * day)).isTrue()
    }

    // ---- 2. 多次提到会加深印象 ----

    @Test
    fun `同一件事再说一遍只加深，不会多出一条`() = runTest {
        val s = store(base)
        s.appendEvents("c", listOf(MemoryEntry(at = base, text = "她说她最怕一个人待着")))
        s.appendEvents(
            "c",
            listOf(MemoryEntry(at = base + 3 * day, text = "她说她最怕一个人待着，晚上必须开着灯")),
        )

        val list = s.entries("c")
        assertThat(list).hasSize(1)
        val e = list.single()
        assertThat(e.hits).isEqualTo(1)
        // 正文取信息量更大的那份
        assertThat(e.text).contains("必须开着灯")
        // 强化过的比没强化过的活得久
        val plain = MemoryEntry(at = base, text = "她说她最怕一个人待着")
        assertThat(e.retention(base + 30 * day)).isGreaterThan(plain.retention(base + 30 * day))
    }

    @Test
    fun `不同的事各记一条，不会被误合并`() = runTest {
        val s = store(base)
        s.appendEvents(
            "c",
            listOf(
                MemoryEntry(at = base, text = "她说她最怕一个人待着"),
                MemoryEntry(at = base, text = "今天一起吃了火锅，她不吃香菜"),
            ),
        )
        assertThat(s.entries("c")).hasSize(2)

        // 只差一个字，但是两件事 —— 合并了就等于把「狗」这条丢掉
        s.appendEvents("c", listOf(MemoryEntry(at = base + day, text = "她说她喜欢猫")))
        s.appendEvents("c", listOf(MemoryEntry(at = base + 2 * day, text = "她说她喜欢狗")))
        assertThat(s.entries("c")).hasSize(4)
    }

    @Test
    fun `模型指认的第 N 条会合并，哪怕换了说法`() = runTest {
        val s = store(base)
        s.appendEvents("c", listOf(MemoryEntry(at = base, text = "答应了陪她看电影")))
        val existing = s.entries("c")

        // 措辞完全不同，字面匹配认不出 —— 靠模型标的 [=1]
        s.appendEvents(
            "c",
            events = emptyList(),
            merges = listOf(
                existing[0] to MemoryEntry(at = base + day, text = "答应了每周日陪她看一场电影，约在晚上八点")
            ),
        )

        val list = s.entries("c")
        assertThat(list).hasSize(1)
        assertThat(list.single().hits).isEqualTo(1)
        assertThat(list.single().text).contains("晚上八点")
    }

    // ---- 3. 强烈情绪 → 逐渐永久 ----

    @Test
    fun `强烈情绪的事再提一次就固化成永久记忆`() = runTest {
        val s = store(base)
        s.appendEvents(
            "c",
            listOf(MemoryEntry(at = base, text = "她哭着说再也不想一个人过生日", emotion = 0.9f)),
        )
        // 第一次只是记下来，还没固化
        assertThat(s.entries("c").single().permanent).isFalse()

        s.appendEvents(
            "c",
            listOf(MemoryEntry(at = base + day, text = "她哭着说再也不想一个人过生日了", emotion = 0.9f)),
        )
        val e = s.entries("c").single()
        assertThat(e.permanent).isTrue()
        // 十年后仍然满血
        assertThat(e.retention(base + 3650 * day)).isEqualTo(1f)
    }

    @Test
    fun `平平淡淡的事要反复提很多次才会固化`() = runTest {
        val s = store(base)
        repeat(4) { i ->
            s.appendEvents("c", listOf(MemoryEntry(at = base + i * day, text = "一起打了两把游戏")))
        }
        // 4 次还不够（门槛是 5，且情绪低）
        assertThat(s.entries("c").single().permanent).isFalse()

        s.appendEvents("c", listOf(MemoryEntry(at = base + 4 * day, text = "一起打了两把游戏")))
        assertThat(s.entries("c").single().permanent).isTrue()
    }

    // ---- 4. 注入时的表现 ----

    @Test
    fun `渲染成两档，模糊的会带一句记不太清了`() = runTest {
        val s = store(base)
        s.appendEvents(
            "c",
            listOf(
                MemoryEntry(at = base - 20 * day, text = "聊过她想搬去海边住，说想开个小书店"),
                MemoryEntry(at = base, text = "今天一起吃了火锅"),
            ),
        )

        val text = s.render("c", base)
        assertThat(text).contains("记得很清楚")
        assertThat(text).contains("火锅")
        assertThat(text).contains("只剩模糊印象")
        assertThat(text).contains("记不太清了")
    }

    @Test
    fun `彻底忘了的事不再进提示词`() = runTest {
        val s = store(base)
        s.appendEvents("c", listOf(MemoryEntry(at = base - 400 * day, text = "很久以前聊过的事")))
        assertThat(s.render("c", base)).isEmpty()
        // 但文件里还在，用户翻得到
        assertThat(s.read("c")).contains("很久以前聊过的事")
    }

    @Test
    fun `永久记忆不因为预算被挤掉`() = runTest {
        val s = store(base)
        val long = "字".repeat(200)
        s.appendEvents(
            "c",
            listOf(
                MemoryEntry(at = base - 300 * day, text = "很久以前最重要的约定", emotion = 0.95f),
            ),
        )
        // 手动补一条够格固化的
        s.appendEvents(
            "c",
            listOf(MemoryEntry(at = base - 300 * day + day, text = "很久以前最重要的约定啊", emotion = 0.95f)),
        )
        repeat(40) { i ->
            s.appendEvents("c", listOf(MemoryEntry(at = base - i * day, text = "第${i}条$long")))
        }

        val text = s.render("c", base)
        assertThat(text).contains("最重要的约定")
        assertThat(text.length).isAtMost(MemoryStore.PROMPT_BUDGET + 400)
    }

    // ---- 5. 老文件升级 ----

    @Test
    fun `旧格式文件升级时从当前时刻起算，不会当场淡光`() = runTest {
        // 模拟一个用了三个月的老用户：文件里只有 ## 时间戳 + 整段摘要
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val d1 = fmt.format(java.util.Date(base - 90 * day))
        val d2 = fmt.format(java.util.Date(base - 60 * day))
        java.io.File(tmp.root, "memory/old.md").apply {
            parentFile?.mkdirs()
            writeText(
                """
                # 长期记忆

                ## $d1
                ### 对话摘要（大肥鱼）
                三个月前聊过的事，她当时说想去海边。

                ## $d2
                ### 对话摘要（大肥鱼）
                后来吵架了。
                """.trimIndent()
            )
        }

        val s = store(base)
        val text = s.render("old", base)

        // 升级后仍然看得见 —— 否则用户体验就是"更新完记忆全没了"
        assertThat(text).contains("想去海边")
        assertThat(text).contains("吵架")
        // 旧版的小标题应当被剥掉，不占卡片第一行
        assertThat(text).doesNotContain("### 对话摘要")

        // 迁移标记写进文件，第二次不再重复推后
        assertThat(s.read("old")).contains(MemoryStore.MIGRATION_MARK)
        // 之后按正常节奏衰减：一个月后进入模糊档
        assertThat(s.render("old", base + 30 * day)).contains("记不太清了")
    }

    @Test
    fun `迁移之后按各自的保留度正常衰减`() = runTest {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val old = fmt.format(java.util.Date(base - 90 * day))
        java.io.File(tmp.root, "memory/mix.md").apply {
            parentFile?.mkdirs()
            writeText(
                """
                # 长期记忆

                ## $old
                很久以前很重要的事
                """.trimIndent()
            )
        }
        val s = store(base)
        assertThat(s.render("mix", base)).contains("很久以前很重要的事")
        // 30 天后开始模糊
        val later = s.render("mix", base + 30 * day)
        assertThat(later).contains("记不太清了")
    }

    // ---- 6. 文件可读性 ----

    @Test
    fun `元数据藏在 HTML 注释里，正文照常可读`() = runTest {
        val s = store(base)
        s.appendEvents(
            "c",
            listOf(MemoryEntry(at = base, text = "她说她最怕一个人待着", emotion = 0.8f)),
        )
        val raw = s.read("c")

        assertThat(raw).contains("## ")
        assertThat(raw).contains("<!--m ")
        assertThat(raw).contains("e0.80")
        assertThat(raw).contains("她说她最怕一个人待着")
        assertThat(raw).contains(MemoryStore.MIGRATION_MARK)
    }

    @Test
    fun `渲染出来的就是模型看到的，分档且省字`() = runTest {
        val s = store(base)
        s.appendEvents(
            "c",
            listOf(
                MemoryEntry(at = base - 20 * day, text = "聊过她想搬去海边住，说想开个小书店"),
                MemoryEntry(at = base - 5 * day, text = "她说她最怕一个人待着，晚上必须开着灯"),
                MemoryEntry(at = base, text = "今天一起吃了火锅，她不吃香菜", emotion = 0.75f),
            ),
        )
        val text = s.render("c", base)

        // 两档分开，前半清楚、后半模糊
        assertThat(text.indexOf("记得很清楚")).isLessThan(text.indexOf("只剩模糊印象"))
        // 同一年不写年份 —— 一次几十条时能省下几百字
        assertThat(text).doesNotContain("${java.util.Calendar.getInstance().get(Calendar.YEAR)}年")
        // 「最怕」这类固定搭配要被识别成情绪内容
        assertThat(s.entries("c").first { it.text.contains("最怕") }.emotion).isGreaterThan(0.4f)
    }

    // ---- 7. 两条底层判据 ----

    @Test
    fun `字面信号能区分平淡和激动`() {
        assertThat(EmotionScore.of("今天吃了面")).isLessThan(0.2f)
        assertThat(EmotionScore.of("对不起，我不该说那种话，我真的怕你走"))
            .isGreaterThan(0.5f)
        assertThat(EmotionScore.of("我发誓再也不骗你了")).isGreaterThan(0.5f)
    }

    @Test
    fun `本地只认近乎一字不差，语义相近交给模型`() {
        // 一字不差的重复：兜底能兜住
        assertThat(MemorySimilarity.same("一起吃了火锅", "一起吃了火锅")).isTrue()

        /*
         * 语义相近但措辞不同 —— 本地**故意**不合并。
         *
         * 放宽到能认这种，就必然把「她喜欢猫 / 她喜欢狗」也认成同一件事，
         * 一合并就丢掉一条真实内容。这类判断交给模型标 [=N]（见下面的用例）。
         */
        assertThat(
            MemorySimilarity.same("她怕一个人待着", "她说她最怕一个人待着，晚上必须开着灯")
        ).isFalse()
        assertThat(MemorySimilarity.same("她喜欢猫", "她喜欢狗")).isFalse()
    }
}
