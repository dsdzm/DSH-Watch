package com.dshwatch.domain.roleplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 角色卡 → 系统提示的拼装。
 *
 * 这里守的是三件容易悄悄坏掉的事：
 *  1. 酒馆提示里必须带上"就是角色本人"的硬约束 —— 掉一条，模型就开始"作为 AI"；
 *  2. 世界书的关键词命中必须真的生效 —— 否则整本世界书要么全塞进上下文烧 token，
 *     要么一条都进不去、设定形同虚设；
 *  3. 写作提示和酒馆提示必须互斥 —— 拿错一套，续写会变成对话体。
 */
class RolePromptTest {

    private fun sampleCard() = RoleCard(
        id = "c1",
        kind = Scenes.TAVERN,
        name = "老板娘",
        fields = listOf(
            CardField("年龄", "27"),
            CardField("性格", "话少，但记性好"),
        ),
        persona = "在巷子口开一家只有六个座位的小店。",
        userPersona = "常来的客人",
        greeting = "坐吧，今天想吃什么。",
        style = "短句，不用敬语",
    )

    @Test
    fun `tavern prompt carries identity and structure`() {
        val p = TavernPrompt.build(sampleCard(), memory = "上次你欠了我一碗面钱。")

        // 硬约束
        assertThat(p).contains("第一人称直接说话")
        assertThat(p).contains("不要自称 AI")

        // 字段按顺序渲染，键值成对
        assertThat(p).contains("年龄：27")
        assertThat(p).contains("性格：话少，但记性好")
        assertThat(p).contains("在巷子口开一家只有六个座位的小店。")
        assertThat(p).contains("常来的客人")
        assertThat(p).contains("短句，不用敬语")

        // 段落顺序：设定 → 记忆 → 世界书。
        // 先确认"我是谁"，再看"我经历过什么"，最后才是"世界是什么样"。
        // 记忆排在设定之前时，模型会拿早期摘要去覆盖当前人设，改卡也不生效。
        val personaAt = p.indexOf("只有六个座位")
        val memAt = p.indexOf("欠了我一碗面钱")
        assertThat(personaAt).isGreaterThan(0)
        assertThat(memAt).isGreaterThan(personaAt)
        assertThat(p).contains("你记得的事")
    }

    @Test
    fun `empty optional sections do not leave dangling headers`() {
        val bare = RoleCard(id = "c2", name = "路人")
        val p = TavernPrompt.build(bare)
        assertThat(p).contains("姓名：路人")
        // 没有风格就不该出现「说话方式」这个空标题
        assertThat(p).doesNotContain("【说话方式】")
        assertThat(p).doesNotContain("【用户的身份】")
    }

    @Test
    fun `writing prompt is not the tavern prompt`() {
        val w = TavernPrompt.buildWriting(sampleCard())
        assertThat(w).contains("续写正文")
        assertThat(w).contains("200~600 字")
        // 酒馆规则里"默认 1~4 句"绝不能出现在写作提示里
        assertThat(w).doesNotContain("默认 1~4 句")
    }

    @Test
    fun `lore always-on entries survive without hits`() {
        val card = sampleCard().copy(
            entries = listOf(
                LoreEntry(id = "e1", title = "天气", content = "常年下雨。"),
            )
        )
        val lore = TavernPrompt.buildLore(card, extraLore = "今天吃什么")
        assertThat(lore).contains("常年下雨")
    }

    @Test
    fun `keyworded lore only lands on a hit`() {
        val card = sampleCard().copy(
            entries = listOf(
                LoreEntry(
                    id = "e2", title = "旧事", content = "十年前淹死过一个孩子。",
                    keywords = listOf("孩子", "河"),
                )
            )
        )
        assertThat(TavernPrompt.buildLore(card, extraLore = "今天天气不错")).isEmpty()
        assertThat(TavernPrompt.buildLore(card, extraLore = "你听说过河边的事吗"))
            .contains("淹死过一个孩子")
    }

    @Test
    fun `disabled entries never make it in`() {
        val card = sampleCard().copy(
            entries = listOf(
                LoreEntry(id = "e3", title = "废弃", content = "不该出现", enabled = false),
            )
        )
        assertThat(TavernPrompt.buildLore(card, extraLore = "废弃 废弃")).isEmpty()
    }

    @Test
    fun `lore respects the char budget`() {
        val big = "字".repeat(TavernPrompt.LORE_CHAR_BUDGET)
        val card = sampleCard().copy(
            entries = listOf(
                LoreEntry(id = "a", title = "很长的", content = big, priority = 10),
                LoreEntry(id = "b", title = "之后的", content = "这条挤不进来", priority = 1),
            )
        )
        val lore = TavernPrompt.buildLore(card)
        assertThat(lore.length).isAtMost(TavernPrompt.LORE_CHAR_BUDGET + 64)
        assertThat(lore).doesNotContain("这条挤不进来")
    }
}
