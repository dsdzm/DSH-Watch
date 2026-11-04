package com.dshwatch.ui.nav

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面转场与返回行为的静态防线。
 *
 * JVM 单测跑不了 Compose 的组合与布局，只能读源码守结构。
 * 这些断言守的都是"编译过、真机上才发现坏了"的类型。
 *
 * ## 1.15.6：自定义转场整套下线
 *
 * 「从按钮长成整屏」连做五版，每版都在真机上冒出新的静默故障
 * （空白背景、先整页出现再播、负圆角闪退、退场没动画）。
 * 现在换成 Compose 官方自带的淡入淡出，整套自定义几何一起删掉。
 *
 * 于是这个测试类也跟着重写：**主要职责从"守住动画的每个细节"变成
 * "守住自定义动画不要再回来"**。留着旧断言只会变成守一具尸体 ——
 * 测试全绿，但守的东西线上早就不存在了。
 */
class ExpansionTransitionRuleTest {

    private val root = File("src/main/java/com/dshwatch")

    private fun read(path: String): String {
        val f = File(root, path)
        assertTrue("找不到 $path", f.isFile)
        return f.readText()
    }

    /** 剥注释再扫。这个项目已经被注释假阳性坑过三次 */
    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    /** 剥掉字符串字面量，避免 `"${foo}"` 里的花括号干扰括号配平 */
    private fun stripStrings(src: String): String =
        src.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "\"\"")

    /**
     * 取函数体（按花括号配平）。
     *
     * 不用"截到下一个特征串"：特征串选错就得到空串，
     * 而 `!空串.contains(x)` 永远为真 —— 断言假通过。
     */
    private fun bodyOf(src: String, signature: String): String {
        val i = src.indexOf(signature)
        if (i < 0) return ""
        val start = src.indexOf('{', i)
        if (start < 0) return ""
        var depth = 0
        for (j in start until src.length) {
            when (src[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start + 1, j)
                }
            }
        }
        return ""
    }

    /** 从 [fromIndex] 之后第一个 [open] 起配平，返回匹配 [close] 的下标 */
    private fun matchBracket(src: String, fromIndex: Int, open: Char, close: Char): Int {
        val start = src.indexOf(open, fromIndex)
        if (start < 0) return -1
        var depth = 0
        for (j in start until src.length) {
            when (src[j]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }

    // ------------------------------------------------------------ 官方转场

    @Test
    fun `只准用 Compose 官方自带的淡入淡出`() {
        val host = stripComments(read("ui/nav/ExpansionHost.kt"))
        assertTrue("要用官方 AnimatedContent", host.contains("AnimatedContent("))
        assertTrue("入场用官方 fadeIn", host.contains("fadeIn()"))
        assertTrue("退场用官方 fadeOut", host.contains("fadeOut()"))
        assertTrue("用 togetherWith 组合两者", host.contains("togetherWith"))
        assertTrue(
            "transitionSpec 只用官方标准转场，不许夹带自定义 spec",
            Regex("transitionSpec = \\{\\s*fadeIn\\(\\) togetherWith fadeOut\\(\\)\\s*\\}").containsMatchIn(host),
        )
        assertTrue(
            "targetState 必须跟着导航栈当前页走",
            host.contains("targetState = nav.current"),
        )
    }

    @Test
    fun `转场宿主里不许再有任何自定义几何`() {
        val host = stripComments(read("ui/nav/ExpansionHost.kt"))
        listOf(
            "graphicsLayer" to "整屏缩放",
            "TransformOrigin" to "缩放原点",
            "Animatable(" to "手写进度动画",
            "CubicBezierEasing" to "自定义曲线",
            "scaleX" to "横向缩放",
            "translationX" to "平移补偿",
            "delay(" to "等一帧的时序握手",
            "withFrameNanos" to "等绘制",
            "rememberGraphicsLayer" to "抓快照",
        ).forEach { (token, what) ->
            assertTrue("不该再有$what（$token）", !host.contains(token))
        }
    }

    @Test
    fun `快照与曲线那套文件必须已经删掉`() {
        listOf(
            "ui/nav/Motion.kt",
            "ui/nav/ExpansionTransition.kt",
            "ui/nav/BackNav.kt",
            "ui/nav/NavigationOrigin.kt",
        ).forEach { path ->
            assertTrue("$path 只服务于自定义转场，应该已删除", !File(root, path).isFile)
        }
    }

    @Test
    fun `导航器不许再参与动画`() {
        val nav = stripComments(read("ui/nav/WatchNavigator.kt"))
        listOf(
            "pushFrom", "popFrom", "leavingScreen", "leaveTarget",
            "lastBackBounds", "clearMotion", "clearLeaving", "Motion(", "Rect",
        ).forEach { token ->
            assertTrue("导航器不该再有转场状态：$token", !nav.contains(token))
        }
        assertTrue("push 仍然入栈", bodyOf(nav, "fun push(screen: Screen)").contains("stack.add(screen)"))
    }

    @Test
    fun `按钮不再上报矩形，入口改回普通 push`() {
        val common = stripComments(read("ui/components/Common.kt"))
        listOf("onBounds", "boundsInRoot", "onGloballyPositioned", "LocalBackButtonBounds").forEach {
            assertTrue("IconTap / WatchTopBar 不该再为转场记位置：$it", !common.contains(it))
        }
        assertTrue("跟手光晕要留着（那是独立功能）", common.contains(".tap(enabled = enabled"))

        val chat = stripComments(read("ui/screens/chat/ChatScreen.kt"))
        assertTrue("历史入口用普通 push", chat.contains("nav.push(Screen.Sessions)"))
        assertTrue("设置入口用普通 push", chat.contains("nav.push(Screen.Settings)"))
        assertTrue("不该再有 pushFrom", !chat.contains("pushFrom"))
    }

    @Test
    fun `两页必须各画各的，不能都画成当前页`() {
        /*
         * 1.15.3 的表现：「点设置，下一页先完整出现，然后才开始播动画」。
         * 根因是 content 收 `() -> Unit`，lambda 里读外层 nav.current，
         * 转场期间两个 slot 都渲染成新页。官方 AnimatedContent 同样有这个坑。
         */
        val host = stripComments(read("ui/nav/ExpansionHost.kt"))
        assertTrue(
            "content 必须接收 Screen 参数",
            Regex("content: @Composable \\(Screen\\) -> Unit").containsMatchIn(host),
        )
        assertTrue("AnimatedContent 要用 lambda 参数渲染", host.contains("content(screen)"))
        assertTrue("省电档仍按当前页渲染", host.contains("content(nav.current)"))

        val app = stripComments(read("ui/AppRoot.kt"))
        assertTrue("AppRoot 必须用 lambda 参数 page 渲染", app.contains(") { page ->") && app.contains("when (page)"))
        assertTrue("GlassBackdropHost 的 resetKey 要跟着 page 走", app.contains("GlassBackdropHost(resetKey = page)"))
    }

    @Test
    fun `转场底下必须垫一层不透明背景`() {
        /*
         * 两页交叉时 alpha 都不满 1，底下露出的必须是主题背景，
         * 不是 Activity 窗口底色（OLED 主题下就是纯黑）。
         */
        val host = stripComments(read("ui/nav/ExpansionHost.kt"))
        assertTrue(
            "要铺一层 colorScheme.background",
            host.contains("background(MaterialTheme.colorScheme.background)"),
        )
    }

    @Test
    fun `减少动画时整段绕过`() {
        val host = stripComments(read("ui/nav/ExpansionHost.kt"))
        assertTrue(
            "reducedMotion 时必须直接渲染并 return",
            Regex("if \\(reducedMotion\\) \\{\\s*content\\(nav\\.current\\)\\s*return").containsMatchIn(host),
        )
        val app = stripComments(read("ui/AppRoot.kt"))
        assertTrue(
            "AppRoot 要把 perf.enableAnimations 传进去",
            app.contains("reducedMotion = !") && app.contains("enableAnimations"),
        )
    }

    @Test
    fun `浮层不能挂在页面子树里`() {
        /*
         * 转场期间两页同时在组合里，浮层挂在页面子树内就会被画两份。
         * 按括号配平求 ExpansionHost 内容 lambda 的真正结尾再比位置，
         * 不能用注释当分隔符（源码先剥过注释，indexOf 会返回 -1，断言恒真）。
         */
        val app = stripStrings(stripComments(read("ui/AppRoot.kt")))
        val hostAt = app.indexOf("com.dshwatch.ui.nav.ExpansionHost(")
        assertTrue("要能找到 ExpansionHost 调用", hostAt >= 0)
        val paramsEnd = matchBracket(app, hostAt, '(', ')')
        assertTrue("参数列表要能配平", paramsEnd >= 0)
        val lambdaEnd = matchBracket(app, paramsEnd, '{', '}')
        assertTrue("内容 lambda 要能配平", lambdaEnd >= 0)
        val overlay = app.indexOf("CrashNotice()")
        assertTrue("要能找到 CrashNotice", overlay >= 0)
        assertTrue("CrashNotice 必须排在 ExpansionHost 的 lambda 外面", overlay > lambdaEnd)
    }

    // ------------------------------------------------------------ 导航行为

    @Test
    fun `返回必须同步出栈，不许卡住`() {
        /*
         * 历史教训：pop 曾经只设 pendingPop 等宿主回调，LOW 档宿主编 return
         * 就永远没人回调 —— 返回键彻底失效，用户只能杀进程。
         */
        val nav = stripComments(read("ui/nav/WatchNavigator.kt"))
        val pop = bodyOf(nav, "fun pop(): Boolean")
        assertTrue("要能取到 pop 的函数体（空串会让断言假通过）", pop.isNotBlank())
        assertTrue("pop 必须同步出栈", pop.contains("stack.removeAt(stack.lastIndex)"))
        assertTrue("栈只剩一页时不能弹", pop.contains("stack.size <= 1"))
        assertTrue("不许再引入 pendingPop 这类中间态", !pop.contains("pendingPop"))

        val app = stripComments(read("ui/AppRoot.kt"))
        val back = bodyOf(app, "BackHandler(enabled = nav.canGoBack)")
        assertTrue("要能取到 BackHandler 的函数体", back.isNotBlank())
        assertTrue("BackHandler 里直接 nav.pop()", back.contains("nav.pop()"))
    }

    // ------------------------------------------------------------ 独立功能

    @Test
    fun `列表页要用带回记忆的滚动状态`() {
        /*
         * 返回后列表跳回顶部，根因是页面被移出组合、rememberScrollState 重建。
         * 这和页面动画无关，换动画方案不能把它一起弄丢。
         */
        listOf(
            "ui/screens/sessions/SessionsScreen.kt" to "sessions",
            "ui/screens/settings/SettingsScreen.kt" to "settings",
            "ui/screens/provider/ProvidersScreen.kt" to "providers",
            "ui/screens/workspace/WorkspaceScreen.kt" to "workspace",
        ).forEach { (path, key) ->
            val s = stripComments(read(path))
            assertTrue(
                "$path 必须用 rememberKeptScalingListState(\"$key\")，否则返回后跳回顶部",
                s.contains("rememberKeptScalingListState(\"$key\")"),
            )
        }
    }
}
