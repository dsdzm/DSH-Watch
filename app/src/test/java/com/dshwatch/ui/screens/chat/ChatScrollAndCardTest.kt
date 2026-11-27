package com.dshwatch.ui.screens.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 对话页的「进页面落到底 / 发完跟着走」，以及卡片删除与养鱼卡隔离。
 *
 * 这一批全是用户真机反馈：
 *  - 除了养鱼，别的模式进对话都停在**最开头**，得自己一路翻到最新；
 *  - 发完一句，画面还停在原地，看不到自己发的那条；
 *  - 酒馆角色卡、写作作品卡建出来就删不掉（列表与编辑页都没有入口）；
 *  - 养鱼专用的大肥鱼混在酒馆列表里，点进去、删掉都会破坏养鱼。
 */
class ChatScrollAndCardTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    /* ---------------- 滚动 ---------------- */

    @Test
    fun `编程对话进页面先落到底，不等 atBottom`() {
        val body = code("ui/screens/chat/ChatScreen.kt")
        // 首帧 layoutInfo 还没量出来，scrollToItem 会被夹到 0 —— 必须是 request
        assertThat(body).contains("listState.requestScrollToItem(tail)")
        // 旧实现的关键句：atBottom 不成立就 return。进页面时它几乎总是 false
        assertThat(body).doesNotContain("if (!atBottom) return@LaunchedEffect\n        if (turns.isNotEmpty())")
    }

    @Test
    fun `自己发的那条一定要跟到底`() {
        // 发完消息视口被输入框压过，atBottom 常常是 false；按它决定就停在那儿
        for (path in listOf(
            "ui/screens/chat/ChatScreen.kt",
            "ui/screens/chat/RoleplayChatScreen.kt",
            "ui/phone/PhoneChatScreen.kt",
            "ui/phone/PhoneRoleplayChat.kt",
            "ui/m3e/screens/M3eChatScreen.kt",
        )) {
            val body = code(path)
            assertThat(body).contains("messages.lastOrNull()?.role == Role.USER")
        }
    }

    @Test
    fun `每个聊天页都会请求落位`() {
        val targets = mapOf(
            "ui/screens/chat/ChatScreen.kt" to "requestScrollToItem(tail)",
            "ui/screens/chat/RoleplayChatScreen.kt" to "requestScrollToItem(messages.size)",
            "ui/phone/PhoneChatScreen.kt" to "requestScrollToItem(turns.lastIndex)",
            "ui/phone/PhoneRoleplayChat.kt" to "requestScrollToItem(target)",
            "ui/m3e/screens/M3eChatScreen.kt" to "requestScrollToItem(turns.size + 1)",
        )
        targets.forEach { (path, needle) ->
            assertThat(code(path)).contains(needle)
        }
    }

    @Test
    fun `切换会话时重新落位`() {
        // landed 是一次性的；不随会话重置的话，切到下一条会话会沿用「已经落过底」
        assertThat(code("ui/screens/chat/ChatScreen.kt"))
            .contains("LaunchedEffect(conversation?.id) { landedAtTail = false }")
        assertThat(code("ui/phone/PhoneRoleplayChat.kt"))
            .contains("LaunchedEffect(current.id) { landed = false }")
    }

    /* ---------------- 删除 ---------------- */

    @Test
    fun `编辑页能删卡，并且有二次确认`() {
        val body = code("ui/screens/tavern/CardEditScreen.kt")
        assertThat(body).contains("container.roleCardStore.delete(card.id)")
        assertThat(body).contains("if (confirmDelete)")
        // 删除入口只在「编辑已有卡」时出现：新建卡还没有 id
        assertThat(body).contains("if (cardId.isNotBlank())")
        assertThat(body).contains("删除这张角色卡")
        assertThat(body).contains("删除这则设定")
    }

    @Test
    fun `列表在返回时重读`() {
        // LaunchedEffect(Unit) 只在首次组合跑一次，pop 回来看到的还是旧列表
        assertThat(code("ui/screens/tavern/TavernScreen.kt")).contains("LaunchedEffect(depth)")
        assertThat(code("ui/screens/writing/WritingScreen.kt")).contains("LaunchedEffect(depth)")
        assertThat(code("ui/phone/PhoneScenes.kt")).contains("LaunchedEffect(scene, depth)")
    }

    /* ---------------- 养鱼卡隔离 ---------------- */

    @Test
    fun `大肥鱼不出现在酒馆列表里`() {
        assertThat(code("ui/screens/tavern/TavernScreen.kt")).contains(".filterNot { it.isFishCard }")
        assertThat(code("ui/phone/PhoneScenes.kt")).contains(".filterNot { it.isFishCard }")
    }

    @Test
    fun `养鱼卡被删掉也能自己补回来`() {
        // 养鱼按 id 取固定卡；取不到就整条链路空转，所以入口要顺手落盘
        for (path in listOf(
            "ui/screens/fish/FishWatchScreen.kt",
            "ui/screens/fish/FishPhoneScreen.kt",
        )) {
            assertThat(code(path)).contains("roleCardStore.save(fallback.copy(builtin = true))")
        }
    }

    @Test
    fun `养鱼卡 id 只写一处`() {
        val body = code("domain/roleplay/BuiltinCards.kt")
        assertThat(body).contains("const val FISH_ID = \"builtin-tavern-feiyu\"")
        assertThat(body).contains("id = FISH_ID")
        assertThat(body).contains("val RoleCard.isFishCard")
    }
}
