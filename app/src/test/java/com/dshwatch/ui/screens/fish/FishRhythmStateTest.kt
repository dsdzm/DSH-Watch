package com.dshwatch.ui.screens.fish

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 节奏状态的收尾。
 *
 * 这里只测一件真出过事故的事：**收尾不能把已经说出来的话藏回去**。
 *
 * 现场是"她回了一句，闪一下就没了"。原因不在动画，在收尾那一步：
 * 旧实现用 `split(正文).size` 算"该显示几条"，可流式期间正文只在 `live` 里、
 * 消息对象是空的，引擎又是"先清 live 再写正文"，协程读到空窗就得出 0，
 * 于是 `take(0)` 把气泡全藏了。
 *
 * 修法是收尾**不再做任何数量换算**：把这条消息从节奏表里删掉，让它回到
 * "历史消息"分支 —— 正文有多少显示多少。下面这几条就是在钉这件事。
 */
class FishRhythmStateTest {

    @Test
    fun `手机版收尾后气泡全部显示`() {
        val r = FishReveal()
        r.revealed["m1"] = 2

        // 播到一半：只该显示已经发出去的两条
        assertThat(r.count("m1", 4)).isEqualTo(2)

        r.settle("m1")

        // 收尾：交还给消息本身
        assertThat(r.count("m1", 4)).isEqualTo(4)
        assertThat(r.typingId).isNull()
    }

    @Test
    fun `手机版收尾时连正文都读不到也不会变空`() {
        val r = FishReveal()
        r.revealed["m1"] = 0
        r.typingId = "m1"

        r.settle("m1")

        // 正文后来才有（finalize 之后），这里只保证"不再被节奏表压着"
        assertThat(r.count("m1", 3)).isEqualTo(3)
    }

    @Test
    fun `手表版收尾后气泡全部显示`() {
        val r = FishWatchRhythm()
        assertThat(r.begin("m1")).isTrue()
        r.typingBubble("m1", 0, 3)

        // 播到一半：只有一条气泡，而且只露出三个字
        assertThat(r.count("m1", 4)).isEqualTo(1)
        assertThat(r.frame("m1", 0, "今天有点累")).isEqualTo("今天有")

        r.settle("m1")

        // 收尾：交还给消息本身 —— 条数、字数都不再由节奏表说话
        assertThat(r.count("m1", 4)).isEqualTo(4)
        assertThat(r.frame("m1", 0, "今天有点累")).isEqualTo("今天有点累")
        assertThat(r.phase).isEqualTo(FishPhase.IDLE)
    }

    @Test
    fun `只有正在打的那条是半截`() {
        val r = FishWatchRhythm()
        r.begin("m1")

        // 第一条已经发完，第二条正在打
        r.typingBubble("m1", 0, 4)
        assertThat(r.count("m1", 3)).isEqualTo(1)
        r.typingBubble("m1", 1, 2)

        assertThat(r.frame("m1", 0, "加班到现在")).isEqualTo("加班到现在")
        assertThat(r.frame("m1", 1, "饭还没吃")).isEqualTo("饭还")
        // 已经发出去的两条 + 正在长的那条
        assertThat(r.count("m1", 3)).isEqualTo(2)
    }

    @Test
    fun `同一条消息只播一次`() {
        val r = FishWatchRhythm()
        assertThat(r.begin("m1")).isTrue()
        // 重组、滚动都会重跑副作用；重播会让同一条回复在屏幕上再长一遍
        assertThat(r.begin("m1")).isFalse()
    }

    @Test
    fun `历史消息不在表里就是全显示`() {
        val r = FishWatchRhythm()
        assertThat(r.count("never-seen", 40)).isEqualTo(40)
        assertThat(FishReveal().count("never-seen", 40)).isEqualTo(40)
    }

    @Test
    fun `条数与字数都不会越过正文`() {
        val r = FishWatchRhythm()
        r.begin("m1")

        // 重新生成之后正文可能比之前短：已经发到第 3 条，而现在只有 2 条
        r.typingBubble("m1", 3, 0)
        assertThat(r.count("m1", 2)).isEqualTo(2)

        // 记到第 99 个字，但这句只有 4 个字 —— 切片不能越界
        r.typingBubble("m1", 0, 99)
        assertThat(r.frame("m1", 0, "一二三四")).isEqualTo("一二三四")
    }

    @Test
    fun `打断会让正在跑的那条立刻收尾`() {
        val r = FishWatchRhythm()
        val before = r.interruptFlag
        r.interrupt()
        assertThat(r.interruptFlag).isGreaterThan(before)
    }
}

/** 表盘上一条回复的最终样子 */
class FishReplyTextTest {

    @Test
    fun `保留换行但去掉列表符号`() {
        val raw = "- 几点了都\n- 吃没吃"
        assertThat(replyText(raw)).isEqualTo("几点了都\n吃没吃")
    }

    @Test
    fun `去掉 Markdown 记号`() {
        assertThat(replyText("**行吧** 那你到家和我说一声")).isEqualTo("行吧 那你到家和我说一声")
    }

    @Test
    fun `连续空行压成一个`() {
        assertThat(replyText("在\n\n\n\n嗯")).isEqualTo("在\n\n嗯")
    }

    @Test
    fun `沉默标记原样保留，交给上层判断`() {
        // 这里不能把「[已读]」清洗掉：界面要靠它决定只留回执、不画卡片
        assertThat(replyText("[已读]")).isEqualTo("[已读]")
    }
}
