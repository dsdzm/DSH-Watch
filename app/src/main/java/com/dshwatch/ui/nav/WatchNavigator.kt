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
 *
 * 页面切换的动画由 [ExpansionHost] 用官方淡入淡出处理，
 * **导航器本身不参与动画** —— 不记按钮矩形、不存转场事务、不等回调。
 * 之前那套 `Motion` / `leavingScreen` / `leaveTarget` / `pushFrom` 已全部删除。
 */
class WatchNavigator internal constructor(
    private val stack: SnapshotStateList<Screen>,
) {
    val current: Screen get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    val depth: Int get() = stack.size

    /** 普通跳转：动画由宿主根据 current 的变化自己决定，这里只改栈 */
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

        /*
         * 场景页（v1.13）为什么要**自己**编码而不是一律 UNRESTORABLE
         * ------------------------------------------------------------
         * 之前酒馆 / 写作 / 对话页 / 卡编辑全部编码成 `UNRESTORABLE`，
         * 于是「进酒馆 → 进对话 → 被系统回收」之后恢复出来的是 `Chat`。
         * 而 `Screen.Chat` 会按 `settings.useScene` 分发 —— 用户明明在写作场景里，
         * 回来却可能落在别的根页上，看起来就像「退出后跳到了别的界面」。
         *
         * 现在各记各的：恢复出来还是原来那一页，只是参数（卡 id）能带就带。
         * 卡 id 参与编码是安全的：它就是个目录名，`RoleCardStore` 读不到会回退索引摘要。
         */
        private const val TAVERN = "tavern"
        private const val WRITING = "writing"
        private const val ROLEPLAY = "roleplay"
        private const val CARD_EDIT = "cardedit"

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
            Screen.TavernHome -> TAVERN
            Screen.WritingHome -> WRITING
            Screen.RoleplayChat -> ROLEPLAY
            // 卡编辑页要带 kind 与 cardId：新建卡（cardId 为空）恢复后就是空表单，
            // 不丢用户已经输入的内容 —— 因为内容此刻还在内存里，没被系统回收。
            is Screen.CardEdit -> "$CARD_EDIT$SEP${screen.kind}$SEP${screen.cardId}"
            // 记忆文档是只读预览，恢复到根屏没有损失，也不必重建上下文
            is Screen.MemoryDoc -> UNRESTORABLE
        }

        private fun decode(raw: String): Screen {
            val parts = raw.split(SEP, limit = 3)
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
                TAVERN -> Screen.TavernHome
                WRITING -> Screen.WritingHome
                ROLEPLAY -> Screen.RoleplayChat
                CARD_EDIT -> Screen.CardEdit(
                    kind = com.dshwatch.domain.roleplay.Scenes.of(parts.getOrNull(1)),
                    cardId = parts.getOrNull(2).orEmpty(),
                )
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
