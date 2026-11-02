package com.dshwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

sealed interface Screen {
    data object Chat : Screen
    data object Sessions : Screen
    data object Settings : Screen
    data object Providers : Screen
    data class ProviderEdit(val providerId: String?) : Screen
    data object Usage : Screen
    data object Pair : Screen
    data object About : Screen
    data object Diagnostics : Screen
    data object Workspace : Screen
    data class WorkspacePreview(val path: String) : Screen
    data class Input(val initial: String = "") : Screen
    data object ExecSettings : Screen

    /** B站扫码登录 */
    data object BiliLogin : Screen

    /** 设置页的二级分组（id 见 SettingsGroups） */
    data class SettingsGroup(val id: String) : Screen

    /** 加入群聊：二维码 + 群号 */
    data object GroupChat : Screen

    /** 捐赠支持：微信收款码 */
    data object Donate : Screen

    /** 特别感谢 */
    data object Thanks : Screen

    // ---------------- 场景（v1.13） ----------------
    /** 酒馆：角色卡列表 */
    data object TavernHome : Screen

    /** 写作：作品设定列表 */
    data object WritingHome : Screen

    /** 角色扮演 / 写作的对话页（场景由 Conversation.scene 决定） */
    data object RoleplayChat : Screen

    /** 卡编辑：kind = tavern / writing，cardId 为空表示新建 */
    data class CardEdit(val kind: String, val cardId: String) : Screen

    /** 长期记忆文档的只读预览 */
    data class MemoryDoc(val cardId: String) : Screen
}

/**
 * 极简导航栈。
 *
 * 不用 navigation-compose：手表上屏幕少、参数简单，
 * 引入 NavHost 会多出 300KB 左右的依赖和一层转场动画，反而拖慢低端机。
 * 这里只维护一个 SnapshotStateList，配合 BackHandler 即可。
 */
class WatchNavigator internal constructor(
    private val stack: SnapshotStateList<Screen>,
) {
    val current: Screen get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    val depth: Int get() = stack.size

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceTop(screen: Screen) {
        if (stack.isEmpty()) {
            stack.add(screen)
        } else {
            stack[stack.lastIndex] = screen
        }
    }

    /** 回到根（对话页） */
    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun toChat() {
        popToRoot()
    }

    companion object {
        private const val SEP = "|"

        /** 不参与状态恢复的页面：decode 时一律落回根屏 */
        private const val UNRESTORABLE = "root"

        private fun encode(screen: Screen): String = when (screen) {
            Screen.Chat -> "chat"
            Screen.Sessions -> "sessions"
            Screen.Settings -> "settings"
            Screen.Providers -> "providers"
            Screen.Usage -> "usage"
            Screen.Pair -> "pair"
            Screen.About -> "about"
            Screen.Diagnostics -> "diag"
            Screen.Workspace -> "ws"
            is Screen.WorkspacePreview -> "wsp${SEP}${screen.path}"
            Screen.ExecSettings -> "exec"
            Screen.BiliLogin -> "bililogin"
            Screen.GroupChat -> "group"
            Screen.Donate -> "donate"
            Screen.Thanks -> "thanks"
            is Screen.ProviderEdit -> "pedit${SEP}${screen.providerId.orEmpty()}"
            is Screen.Input -> "input${SEP}${screen.initial}"
            is Screen.SettingsGroup -> "sgroup${SEP}${screen.id}"
            // 场景页（v1.13）。这几个是**过程页**，不该在进程被杀后恢复：
            // 恢复出来的是一个没有任何会话上下文的空酒馆/空对话，比回到根屏更糟。
            // 所以编码成一个不参与恢复的占位，decode 时统一落回根屏。
            is Screen.CardEdit -> UNRESTORABLE
            is Screen.MemoryDoc -> UNRESTORABLE
            Screen.RoleplayChat -> UNRESTORABLE
            Screen.TavernHome -> UNRESTORABLE
            Screen.WritingHome -> UNRESTORABLE
        }

        private fun decode(raw: String): Screen {
            val parts = raw.split(SEP, limit = 2)
            return when (parts[0]) {
                "chat" -> Screen.Chat
                "sessions" -> Screen.Sessions
                "settings" -> Screen.Settings
                "providers" -> Screen.Providers
                "usage" -> Screen.Usage
                "pair" -> Screen.Pair
                "about" -> Screen.About
                "diag" -> Screen.Diagnostics
                "ws" -> Screen.Workspace
                "exec" -> Screen.ExecSettings
                "bililogin" -> Screen.BiliLogin
                "group" -> Screen.GroupChat
                "donate" -> Screen.Donate
                "thanks" -> Screen.Thanks
                "wsp" -> Screen.WorkspacePreview(parts.getOrNull(1).orEmpty())
                "pedit" -> Screen.ProviderEdit(parts.getOrNull(1)?.ifBlank { null })
                "input" -> Screen.Input(parts.getOrNull(1).orEmpty())
                "sgroup" -> Screen.SettingsGroup(parts.getOrNull(1).orEmpty())
                UNRESTORABLE -> Screen.Chat
                else -> Screen.Chat
            }
        }

        val Saver: Saver<SnapshotStateList<Screen>, ArrayList<String>> = Saver(
            save = { list -> ArrayList(list.map(::encode)) },
            restore = { saved ->
                val restored = saved.mapNotNull { runCatching { decode(it) }.getOrNull() }
                mutableStateListOf<Screen>().apply {
                    if (restored.isEmpty()) add(Screen.Chat) else addAll(restored)
                }
            },
        )
    }
}

@Composable
fun rememberWatchNavigator(): WatchNavigator {
    val stack = rememberSaveable(saver = WatchNavigator.Saver) {
        mutableStateListOf<Screen>(Screen.Chat)
    }
    val navigator = remember { WatchNavigator(stack) }
    return navigator
}
