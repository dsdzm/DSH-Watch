package com.dshwatch.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航栈的编码 / 解码与栈操作。
 *
 * 这组用例是为一个真实反馈补的：「从酒馆 / 写作页出去再回来，跳到了别的界面」。
 *
 * 根因不在某一行写错，而在**编码策略**：场景页原先一律编码成 `UNRESTORABLE`，
 * 恢复出来落回 `Screen.Chat`，而 `Chat` 会按 `settings.useScene` 重新分发 ——
 * 于是用户明明停在写作页，回来却可能落在编程页上。
 *
 * 现在每页各记各的。这里把往返关系钉住，避免以后图省事又全部折成根屏。
 */
class WatchNavigatorTest {

    /** 借 Saver 走一遍真实的恢复路径，而不是直接调私有的 decode */
    private fun restore(vararg encoded: String): List<Screen> =
        Snapshot.withMutableSnapshot {
            WatchNavigator.Saver.restore(arrayListOf(*encoded))
        }!!.toList()

    private fun save(list: List<Screen>): ArrayList<String> {
        val saver = WatchNavigator.Saver
        val state = Snapshot.withMutableSnapshot { mutableStateListOf<Screen>().apply { addAll(list) } }
        return Snapshot.withMutableSnapshot { with(saver) { StubScope.save(state) } }!!
    }

    @Test
    fun `根屏往返后还是根屏`() {
        assertEquals(listOf(Screen.Chat), restore("chat"))
        assertEquals(listOf("chat"), save(listOf(Screen.Chat)))
    }

    @Test
    fun `场景页各自恢复成自己，不折回根屏`() {
        val restored = restore("chat", "tavern", "writing", "roleplay")
        assertEquals(
            listOf(Screen.Chat, Screen.TavernHome, Screen.WritingHome, Screen.RoleplayChat),
            restored,
        )

        assertEquals(
            listOf("chat", "tavern", "writing", "roleplay"),
            save(listOf(Screen.Chat, Screen.TavernHome, Screen.WritingHome, Screen.RoleplayChat)),
        )
    }

    @Test
    fun `卡编辑页带上 kind 与 cardId`() {
        val last = restore("chat", "cardedit|writing|abc123").last()
        assertTrue(last is Screen.CardEdit)
        last as Screen.CardEdit
        assertEquals("writing", last.kind)
        assertEquals("abc123", last.cardId)

        assertEquals(
            listOf("chat", "cardedit|writing|abc123"),
            save(listOf(Screen.Chat, Screen.CardEdit("writing", "abc123"))),
        )
    }

    @Test
    fun `未知编码落回根屏，不会让栈变空`() {
        // nav.current 是 last()，空栈会当场抛异常 —— 冷启动崩溃的常见来源
        assertEquals(listOf(Screen.Chat), restore("未来某个版本的页面"))
    }

    @Test
    fun `带参数的页面往返不丢参数`() {
        val restored = restore("chat", "input|你好", "sgroup|appearance", "pedit|p1")
        assertEquals(Screen.Input("你好"), restored[1])
        assertEquals(Screen.SettingsGroup("appearance"), restored[2])
        assertEquals(Screen.ProviderEdit("p1"), restored[3])
    }

    @Test
    fun `push 与 pop 维护深度与当前页`() {
        val nav = WatchNavigator(mutableStateListOf<Screen>(Screen.Chat))
        assertEquals(1, nav.depth)
        assertFalse(nav.canGoBack)

        nav.push(Screen.Settings)
        assertEquals(2, nav.depth)
        assertEquals(Screen.Settings, nav.current)
        assertTrue(nav.canGoBack)

        nav.popToRoot()
        assertEquals(1, nav.depth)
        assertEquals(Screen.Chat, nav.current)
    }

    @Test
    fun `pop 到底后返回 false 而不是抛异常`() {
        val nav = WatchNavigator(mutableStateListOf<Screen>(Screen.Chat))
        // BackHandler 已经在 canGoBack 上拦过一道，但代码里仍有直接调 pop() 的地方
        assertFalse(nav.pop())
        assertEquals(Screen.Chat, nav.current)
    }

    @Test
    fun `replaceTop 空栈时补一个，不会越界`() {
        val nav = WatchNavigator(mutableStateListOf())
        nav.replaceTop(Screen.Settings)
        assertEquals(Screen.Settings, nav.current)
        assertEquals(1, nav.depth)
    }

    private object StubScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }
}
