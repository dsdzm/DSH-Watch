package com.dshwatch.ui.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.nav.rememberWatchNavigator

/**
 * 手机版的底部三个入口。
 *
 * 为什么是这三个：用户打开这个 App 只做三件事 —— 说话、翻记录、调设置。
 * 手表上「设置」是在对话页里按一下才进去的二级页，因为圆屏没有第四个角落；
 * 手机上底部导航是系统惯例，藏起来反而要多点一次。
 */
enum class PhoneTab {
    Chat,
    Sessions,
    Settings,
    ;

    val root: Screen
        get() = when (this) {
            Chat -> Screen.Chat
            Sessions -> Screen.Sessions
            Settings -> Screen.Settings
        }

    companion object {
        /** 某个页面属于哪个 tab；次级页返回 null（不改变 tab 选中态） */
        fun of(screen: Screen): PhoneTab? = when (screen) {
            Screen.Chat -> Chat
            Screen.Sessions -> Sessions
            Screen.Settings -> Settings
            else -> null
        }
    }
}

/**
 * 手机版导航。
 *
 * ## 为什么复用 WatchNavigator 的栈
 *
 * 「配对」「插件」「模型」「酒馆」这些页面在手机上**直接复用**现有实现 ——
 * 它们不需要第二套手机版，而且它们内部到处在调用 `nav.push(...)`。
 * 如果手机版另起一个栈，这些页面内部的跳转会落进一个没人渲染的栈里，
 * 表现是「点了没反应」。
 *
 * 所以这里做的不是「新导航」，而是**给同一个栈加一层 tab**：
 *
 *  - 栈深 1（只有根）= 停在某个 tab 的列表页，画底部导航；
 *  - 栈深 > 1 = 进了次级页，整屏交给那个页面（它自带顶栏和返回）。
 *
 * 切换 tab 时把栈清回根：手机上从「设置」切到「对话」再切回来，
 * 用户期待看到的是设置的首页，而不是上次停留的第三层。
 */
class PhoneNavigator(private val stack: WatchNavigator) {

    var tab by mutableStateOf(PhoneTab.Chat)
        private set

    /** 次级页用的栈。交给 [com.dshwatch.ui.ScreenHost] 渲染，页面内部还在用它 push */
    val watchStack: WatchNavigator get() = stack

    /** 当前该渲染的页面 */
    val current: Screen get() = if (stack.depth > 1) stack.current else tab.root

    val canGoBack: Boolean get() = stack.depth > 1

    fun select(next: PhoneTab) {
        stack.popToRoot()
        tab = next
    }

    /**
     * 打开一个页面。
     *
     * 目标是某个 tab 的根时等价于切 tab —— 否则会出现
     * 「点进会话了，底部还高亮在设置」。
     */
    fun open(screen: Screen) {
        val owner = PhoneTab.of(screen)
        if (owner != null) {
            stack.popToRoot()
            tab = owner
            return
        }
        stack.push(screen)
    }

    fun pop(): Boolean = stack.pop()

    /** 回到当前 tab 的根（底部导航重复点击时用） */
    fun popToRoot() = stack.popToRoot()
}

@Composable
fun rememberPhoneNavigator(): PhoneNavigator {
    val stack = rememberWatchNavigator()
    return remember(stack) { PhoneNavigator(stack) }
}
