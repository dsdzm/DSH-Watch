package com.dshwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

sealed interface Screen {
    /** 主页：胶囊菜单（3 页分页），也是导航栈的根 */
    data object Home : Screen
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

    /** 设置页的二级分组（id 见 SettingsGroups） */
    data class SettingsGroup(val id: String) : Screen
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

    /** 回到根（主页） */
    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * 回到对话页。
     *
     * 根是主页，所以对话页是「根 + 1」这一层：先回根，再压入 Chat。
     * 已在对话页时什么也不做，避免把 Chat 叠两层。
     */
    fun toChat() {
        popToRoot()
        if (stack.lastOrNull() != Screen.Chat) stack.add(Screen.Chat)
    }

    companion object {
        private const val SEP = "|"

        private fun encode(screen: Screen): String = when (screen) {
            Screen.Home -> "home"
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
            is Screen.ProviderEdit -> "pedit${SEP}${screen.providerId.orEmpty()}"
            is Screen.Input -> "input${SEP}${screen.initial}"
            is Screen.SettingsGroup -> "sgroup${SEP}${screen.id}"
        }

        private fun decode(raw: String): Screen {
            val parts = raw.split(SEP, limit = 2)
            return when (parts[0]) {
                "home" -> Screen.Home
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
                "wsp" -> Screen.WorkspacePreview(parts.getOrNull(1).orEmpty())
                "pedit" -> Screen.ProviderEdit(parts.getOrNull(1)?.ifBlank { null })
                "input" -> Screen.Input(parts.getOrNull(1).orEmpty())
                "sgroup" -> Screen.SettingsGroup(parts.getOrNull(1).orEmpty())
                else -> Screen.Home
            }
        }

        val Saver: Saver<SnapshotStateList<Screen>, ArrayList<String>> = Saver(
            save = { list -> ArrayList(list.map(::encode)) },
            restore = { saved ->
                val restored = saved.mapNotNull { runCatching { decode(it) }.getOrNull() }
                mutableStateListOf<Screen>().apply {
                    if (restored.isEmpty()) add(Screen.Home) else addAll(restored)
                }
            },
        )
    }
}

@Composable
fun rememberWatchNavigator(): WatchNavigator {
    val stack = rememberSaveable(saver = WatchNavigator.Saver) {
        mutableStateListOf<Screen>(Screen.Home)
    }
    val navigator = remember { WatchNavigator(stack) }
    return navigator
}
