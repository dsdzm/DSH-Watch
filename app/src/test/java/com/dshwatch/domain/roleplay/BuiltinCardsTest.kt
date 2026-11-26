package com.dshwatch.domain.roleplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 内置卡的回归测试。
 *
 * 重点是防两件事：
 *
 *  1. **内置卡 id 必须固定且唯一。** [com.dshwatch.data.store.RoleCardStore.ensureBuiltins]
 *     靠 id 判断「这张装过没有」，id 留空的话每次启动都会再写一份。
 *  2. **提示词里不能出现本应用没有的能力。** 大肥鱼那张卡改编自一份群聊 bot 的人设，
 *     原文里写了 `send_sticker` / `memory_append` / `web_search` 这类工具名。
 *     酒馆场景是纯对话（`tools` 为空），模型照着做只会输出一堆没人执行的调用文本。
 */
class BuiltinCardsTest {

    @Test
    fun `内置卡都有固定且唯一的 id`() {
        val cards = BuiltinCards.all()
        assertThat(cards).isNotEmpty()
        cards.forEach { assertThat(it.id).isNotEmpty() }
        assertThat(cards.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `内置卡名称不重复`() {
        assertThat(BuiltinCards.all().map { it.name }).containsNoDuplicates()
    }

    @Test
    fun `大肥鱼是可直接开聊的陪伴卡`() {
        val card = BuiltinCards.all().first { it.name == "大肥鱼" }
        assertThat(card.kind).isEqualTo(Scenes.TAVERN)
        // 没有开场白，用户点进去就是一片空白，不知道该说什么
        assertThat(card.greeting).isNotEmpty()
        // 陪伴卡必须开长期记忆，否则「她记得你」立不住
        assertThat(card.longTermMemory).isTrue()
        assertThat(card.persona.length).isGreaterThan(300)
        assertThat(card.style).isNotEmpty()
    }

    @Test
    fun `提示词里不出现本应用不存在的工具`() {
        val card = BuiltinCards.all().first { it.name == "大肥鱼" }
        val prompt = TavernPrompt.build(card)

        // 这些是原文里的群聊 bot 工具，酒馆场景一个都没有
        listOf(
            "send_message", "send_sticker", "list_stickers", "collect_sticker",
            "sticker_note", "get_sticker_image", "get_message_images",
            "memory_append", "memory_query", "memory_remove",
            "replyToMessageId", "web_fetch",
        ).forEach { assertThat(prompt).doesNotContain(it) }
    }

    @Test
    fun `大肥鱼的提示词没有膨胀到拖慢每一轮`() {
        val card = BuiltinCards.all().first { it.name == "大肥鱼" }
        // 角色卡每轮都会进系统提示，太长既费钱又稀释注意力
        assertThat(TavernPrompt.build(card).length).isLessThan(3000)
    }

    @Test
    fun `说话方式里明确禁止了文案腔`() {
        val card = BuiltinCards.all().first { it.name == "大肥鱼" }
        // 「鱼八股」的核心就是靠语气词和拟声词撑人设，这条规则必须在场
        assertThat(card.style).contains("语气词")
        assertThat(card.style).contains("内心戏")
    }
}
