package com.dshwatch.domain.roleplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 养鱼场景的提示词。
 *
 * 守三件事：
 *  1. 角色设定在（否则就只是个通用陪聊）；
 *  2. 聊天形态的约束在 —— 分条、短句、不写动作，这是"像真人"的骨架；
 *  3. 去鱼八股的规则在，并且**排在最后**（模型对结尾最敏感）。
 */
class FishPromptTest {

    private val card = BuiltinCards.feiyu()

    @Test
    fun `包含角色设定与记忆`() {
        val p = FishPrompt.build(card, memory = "（记得很清楚）\n· 用户不吃香菜")
        assertThat(p).contains("大肥鱼")
        assertThat(p).contains("尾巴")
        assertThat(p).contains("不吃香菜")
    }

    @Test
    fun `要求分条短句并禁止动作描写`() {
        val p = FishPrompt.build(card)
        assertThat(p).contains("换行就是另一条消息")
        assertThat(p).contains("40")
        assertThat(p).contains("不要写 *动作*")
        // 没有这条，模型很容易把酒馆那套"*她低下头*"带进来
        assertThat(p).contains("你只是在打字")
    }

    @Test
    fun `去鱼八股的规则在最后一段`() {
        val p = FishPrompt.build(card)
        val tone = p.indexOf("文风铁律")
        assertThat(tone).isGreaterThan(0)
        // 规则放在结尾，避免被前面的性格描述稀释
        assertThat(tone).isGreaterThan(p.length / 2)
        listOf("鱼八股", "说人话", "觉得羞耻就重写").forEach {
            assertThat(p).contains(it)
        }
        // 三类典型句式都要点名，否则模型只会躲开其中一类
        assertThat(p).contains("这话我记下了")
        assertThat(p).contains("睡吧，我守着")
        assertThat(p).contains("空气仿佛凝固了")
    }

    @Test
    fun `不出现酒馆专用的面板与选项规则`() {
        val p = FishPrompt.build(card)
        // 这些是酒馆 CORE_RULES 里的东西，聊天框里出现就是灾难
        listOf("```html", "```panel", "A. 走过去看看", "Markdown 格式（除必要时").forEach {
            assertThat(p).doesNotContain(it)
        }
    }

    @Test
    fun `有情绪、不讨好、可以沉默`() {
        val p = FishPrompt.build(card)
        // 默认助手人格会无条件接话、无条件共情、每句都问回去 —— 这三条都要拧回来
        assertThat(p).contains("你不是客服")
        assertThat(p).contains("心情")
        assertThat(p).contains("不要每句都接")
        assertThat(p).contains("[已读]")
        assertThat(p).contains("不想聊就说不想聊")
    }

    @Test
    fun `情绪规则排在文风铁律之前`() {
        val p = FishPrompt.build(card)
        // 文风是硬约束，必须压在最末尾；情绪是人格描述，放在它前面
        assertThat(p.indexOf("你有自己的状态")).isLessThan(p.indexOf("文风铁律"))
    }

    @Test
    fun `不出现本应用不存在的工具名`() {
        val p = FishPrompt.build(card)
        listOf(
            "send_message", "send_sticker", "memory_append", "web_search",
            "replyToMessageId", "list_stickers",
        ).forEach { assertThat(p).doesNotContain(it) }
    }

    @Test
    fun `禁止把挑选句子的过程写进气泡`() {
        val p = FishPrompt.build(card)
        // 用户截图里原样出现的句式，必须被点名
        assertThat(p).contains("简单问一句")
        assertThat(p).contains("压到三条")
        assertThat(p).contains("发了")
        assertThat(p).contains("只发出去，不要写过程")
        // 必须压在整个提示的最末尾：它是输出格式，不是风格偏好
        assertThat(p.lastIndexOf("只发出去，不要写过程")).isGreaterThan(p.lastIndexOf("文风铁律"))
        // 要给出可执行的边界，而不只是一句"别想"
        assertThat(p).contains("还是在对自己说")
    }

    @Test
    fun `提示词没有膨胀`() {
        // 每轮都要带，太长会挤压记忆与历史
        assertThat(FishPrompt.build(card).length).isLessThan(4_000)
    }

    @Test
    fun `养鱼是独立场景且不走工具`() {
        assertThat(Scenes.FISH).isNotEqualTo(Scenes.TAVERN)
        assertThat(Scenes.label(Scenes.FISH)).isEqualTo("养鱼")
        assertThat(Scenes.usesAgent(Scenes.FISH)).isFalse()
        assertThat(Scenes.all).contains(Scenes.FISH)
    }
}
