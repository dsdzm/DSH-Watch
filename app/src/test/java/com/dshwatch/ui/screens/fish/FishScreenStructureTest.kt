package com.dshwatch.ui.screens.fish

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 「养鱼」两套界面的结构。
 *
 * 这一页的价值全在"像不像在跟人发消息"，而这件事编译不会报错、单测也不会失败 ——
 * 把气泡改回一列纯文本，代码照样跑。所以盯着几个不可退让的点：
 *
 *  1. 手机版仍是"一条回复拆成多个气泡 + 左右分栏 + 头像 + 贴着键盘的输入条"；
 *  2. 手表版是**另一套实现**：M3E 骨架 + 自适应列表 + 圆 FAB，
 *     而且不引用手机版的任何界面代码（两边约束不同，混用必然两边都改不动）；
 *  3. 拆条用同一份规则（`FishBubbles`）：手机上一条条出现，手表上每条再逐字长；
 *  4. 收尾时不把已经说出来的话藏回去（那条"闪一下就消失"的 bug）；
 *  5. 设置里置了 `picker` 就必须真的画出选择器 —— 只置状态不渲染，
 *     表现是"点了一下什么都没发生"，看起来像卡死。
 */
class FishScreenStructureTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    @Test
    fun `手机版回复按气泡拆开`() {
        val body = code("ui/screens/fish/FishPhoneScreen.kt")
        assertThat(body).contains("FishBubbles.split(")
        assertThat(body).contains("forEachIndexed")
    }

    @Test
    fun `手表版不套用手机版的任何界面代码`() {
        val body = code("ui/screens/fish/FishWatchScreen.kt")
        /*
         * 拆条规则（`FishBubbles`）是**领域**代码，不是界面 —— 两端必须共用，
         * 否则同一段回复在两块屏上条数不一样。所以这里放它过去，挡住的是
         * 界面与状态机：真套过来，两套排版会互相拽着谁也改不动。
         */
        assertThat(body).contains("FishBubbles.split(")
        assertThat(body).doesNotContain("rememberFishReveal")
        assertThat(body).doesNotContain("FishPhoneScreen")
        // 手机排版的痕迹
        assertThat(body).doesNotContain("imePadding()")
        assertThat(body).doesNotContain("statusBarsPadding()")
        assertThat(body).doesNotContain("PhoneAvatar")
        // 自己那套节奏
        assertThat(body).contains("FishWatchRhythm")
    }

    @Test
    fun `手表版也按气泡分段，段内逐字长出来`() {
        val body = code("ui/screens/fish/FishWatchScreen.kt")
        /*
         * 曾经这里是"整段回复一张卡、一个字一个字往外长"。看着像打字，
         * 但模型给的一段话本来就带换行 —— 三句话被显示成一段，不像人发消息，
         * 而且和手机版的条数对不上。现在按 `FishBubbles` 拆，段内继续逐字。
         */
        assertThat(body).contains("FishBubbles.split(")
        assertThat(body).contains("bubbles.take(count)")
        assertThat(body).contains("rhythm.frame(")
        assertThat(body).contains("FishRhythm.bubbleGap()")
        // 旧的"整段切片"接口不该再回来（它只认识一条气泡）
        assertThat(body).doesNotContain("fun chars(id: String")
        assertThat(body).doesNotContain("text.take(n)")
    }

    @Test
    fun `模式选择器真的被画出来`() {
        val body = code("ui/screens/fish/FishWatchScreen.kt")
        /*
         * 这一条是给一个真实事故收口的：`picker` 在两处被置为 true
         * （顶栏的模式图标、菜单里的「换个模式」），但没有任何地方渲染它。
         * 点下去菜单关掉、屏幕上什么都不出现 —— 用户描述成「卡住了，
         * 切不了模式也退不出去」。
         */
        assertThat(body).contains("if (picker)")
        assertThat(body).contains("Scenes.all.forEach")
        // 选了别的模式要把 useScene 落盘，并回到根屏重新分发
        assertThat(body).contains("it.copy(useScene = id)")
        assertThat(body).contains("nav.popToRoot()")
    }

    @Test
    fun `手表版全部用 M3E 组件`() {
        val body = code("ui/screens/fish/FishWatchScreen.kt")
        // 骨架：时间浮层让位、顶栏可用普通形态
        assertThat(body).contains("M3eScaffold(")
        /*
         * 顶栏不再是 stackedBar（一排居中图标 + 第二行标题），
         * 改成和酒馆一样的「返回在左 / 标题居中 / 动作在右」。
         * 状态那半句话挪进了副标题 —— 四个模式顶栏一个样，用户不用重新学。
         */
        assertThat(body).contains("stackedBar = false")
        assertThat(body).doesNotContain("stackedBar = true")
        // 列表：贴边项在绘制阶段缩放，天然嵌进圆周
        assertThat(body).contains("M3eList(")
        // 弹层与按钮也走 M3E，不在 M3E 页里混经典样式
        assertThat(body).contains("M3eDialog(")
        assertThat(body).contains("M3eFab(")
        assertThat(body).contains("M3eIconTap(")
        // 经典那套（GlassDialog / 自绘 TextButton 菜单）不应出现
        assertThat(body).doesNotContain("ScenePickerDialog(")
        assertThat(body).doesNotContain("GlassDialog")
    }

    @Test
    fun `手表版仍是左右两列气泡`() {
        val body = code("ui/screens/fish/FishWatchScreen.kt")
        // 聊天的辨识度全在"她左我右"：试过把她说的话铺满整宽、我的话缩成右侧引文，
        // 读起来像文档流不像对话
        assertThat(body).contains("HerBubble(")
        assertThat(body).contains("MyBubble(")
        assertThat(body).contains("Arrangement.Start")
        assertThat(body).contains("Arrangement.End")
        // 气泡宽度跟内容走，不铺满整行：**只说一句「嗯」时占满整行会像一张卡片**
        assertThat(body).contains("widthIn(max = bubbleMaxWidth())")
        assertThat(body).doesNotContain("HerNote(")
        assertThat(body).doesNotContain("MyNote(")
    }

    @Test
    fun `手表版气泡把时间装在气泡里`() {
        val body = code("ui/screens/fish/FishWatchScreen.kt")
        /*
         * 参考图（微信）里时间是气泡右下角那一小撮灰字，不是气泡外的居中提示。
         * 单独一行每条多占一行高度；居中灰字看着像系统提示。
         * 这里用 AnnotatedString 把时间接在正文末尾。
         */
        assertThat(body).contains("bubbleText(")
        assertThat(body).contains("AnnotatedString")
        // 四角统一，不再给「说话人那一侧」收一个小角
        assertThat(body).doesNotContain("bottomStart = 5.dp")
        assertThat(body).doesNotContain("bottomEnd = 5.dp")
    }

    @Test
    fun `养鱼没有「重新开一段」`() {
        val watch = code("ui/screens/fish/FishWatchScreen.kt")
        assertThat(watch).doesNotContain("重新开一段")
        /*
         * 养鱼是一个「人」：记忆文档与压缩点挂在同一个 conversationId 上。
         * 多开一段就既不记得刚才也不记得昨天 —— 所以两个界面都不给入口。
         */
        val phone = code("ui/screens/fish/FishPhoneScreen.kt")
        assertThat(phone).doesNotContain("fun newChat()")
        assertThat(phone).contains("onNewChat = null")
    }

    @Test
    fun `手表版把 FAB 抬到圆内`() {
        val body = code("ui/screens/fish/FishWatchScreen.kt")
        // M3eScaffold 默认把底栏贴在屏幕底 8dp —— 圆盘上那已经在圆周之外
        assertThat(body).contains("LocalCircle.current")
        assertThat(body).contains("0.90f")
    }

    @Test
    fun `收尾不把已经说出来的话藏回去`() {
        // 手机版：不再用"算出来的条数"收尾
        val reveal = code("ui/screens/fish/FishReveal.kt")
        assertThat(reveal).contains("reveal.settle(msg.id)")
        assertThat(reveal).doesNotContain("revealed[msg.id] = FishBubbles.split")

        // 手表版：同样在收尾时交还给消息本身
        val watch = code("ui/screens/fish/FishWatchScreen.kt")
        assertThat(watch).contains("rhythm.settle(msg.id)")
    }

    @Test
    fun `有读、想、打字三段节奏`() {
        // 两块屏共用同一套时延函数：同一个角色，节奏不能两块屏各一套
        listOf("ui/screens/fish/FishReveal.kt", "ui/screens/fish/FishWatchScreen.kt").forEach { path ->
            val body = code(path)
            assertThat(body).contains("FishRhythm.readDelay(")
            assertThat(body).contains("FishRhythm.thinkDelay(")
        }
        assertThat(code("ui/screens/fish/FishReveal.kt")).contains("FishRhythm.typingDelay(")
    }

    @Test
    fun `支持插话与沉默`() {
        val body = code("ui/screens/fish/FishPhoneScreen.kt")
        assertThat(body).contains("reveal.interrupt()")
        assertThat(body).contains("engine.stop()")
        assertThat(body).contains("FishRhythm.isSilent(")

        val watch = code("ui/screens/fish/FishWatchScreen.kt")
        assertThat(watch).contains("rhythm.interrupt()")
        assertThat(watch).contains("FishRhythm.isSilent(")
    }

    @Test
    fun `手表上插话走输入页而不是盖住她的话`() {
        val input = code("ui/screens/input/InputScreen.kt")
        // 输入页在 fish 会话里发送时先停再发：她就是"听到一半改口"
        assertThat(input).contains("Scenes.FISH")
        assertThat(input).contains("container.engine.stop()")
        // 编程快捷语（解释 / 修 Bug）不能出现在养鱼里
        assertThat(input).contains("if (!fishMode)")
    }

    @Test
    fun `手机版有头像、时间戳与左右分栏`() {
        val body = code("ui/screens/fish/FishPhoneScreen.kt")
        assertThat(body).contains("PhoneAvatar(")
        assertThat(body).contains("PhoneTimeDivider(")
        assertThat(body).contains("Modifier.weight(1f)")
    }

    @Test
    fun `手机版输入条跟键盘走`() {
        val body = code("ui/screens/fish/FishPhoneScreen.kt")
        // 输入条换成和编程页共用的那颗胶囊：不再自画 TextField，也就没有 KeyboardActions
        assertThat(body).contains("imePadding()")
        assertThat(body).contains("PhoneComposer(")
        assertThat(body).contains("streamingSend = true")
        assertThat(body).contains("showAttach = false")
    }

    @Test
    /**
     * 分派只看设置里的设备类型（`LocalPhoneUi` → `PhoneRoot`），不看硬件特征。
     *
     * 这条断言是防回归的：曾经这里写的是 `profile.isWatch`，用户在手机上把
     * 设备类型选成「手表」后，整机是手表界面，只有养鱼页还是手机版。
     * 现在手机那一路由 `PhoneRoot` 直接给出 `PhoneNavigator`，
     * `FishScreen` 只剩手表实现。
     */
    fun `按设置里的设备类型分派两套界面`() {
        val root = code("ui/phone/PhoneRoot.kt")
        assertThat(root).contains("Scenes.FISH ->")
        assertThat(root).contains("FishPhoneScreen(")

        val body = code("ui/screens/fish/FishScreen.kt")
        assertThat(body).doesNotContain("profile.isWatch")
        assertThat(body).contains("FishWatchScreen(")
    }

    @Test
    fun `养鱼对话页显示用户带来的图片与文件`() {
        /*
         * 她能看见用户发的照片（多模态输入），但用户自己以前看不见 ——
         * 屏幕上只剩自己打的字，会以为照片没发出去。
         */
        listOf("ui/screens/fish/FishPhoneScreen.kt", "ui/screens/fish/FishWatchScreen.kt").forEach { path ->
            val body = code(path)
            assertThat(body).contains("FishAttachmentNotes(")
            assertThat(body).contains("FishMediaStrip(")
            assertThat(body).contains("userMedia(")
        }
        // 手机端还要能**发**：只有显示没有入口，附件功能等于不存在
        val phone = code("ui/screens/fish/FishPhoneScreen.kt")
        assertThat(phone).contains("PhoneAttachSheet(")
        assertThat(phone).contains("showAttach = true")
        // 图片附件要转成 MediaRef 复用已有的查看器，而不是另写一个
        val strip = code("ui/screens/fish/FishAttachments.kt")
        assertThat(strip).contains("MediaRef(")
    }

    @Test
    fun `养鱼设置能从两端进入`() {
        val nav = code("ui/nav/WatchNavigator.kt")
        assertThat(nav).contains("FishSettings")

        // 手表：菜单里那一行
        assertThat(code("ui/screens/fish/FishWatchScreen.kt")).contains("Screen.FishSettings")
        // 手机：抽屉里的齿轮
        assertThat(code("ui/screens/fish/FishPhoneScreen.kt")).contains("Screen.FishSettings")
        // 真正的落点
        assertThat(code("ui/AppRoot.kt")).contains("FishSettingsScreen(")
    }

    @Test
    fun `主动发言的提示只进请求，不落成用户消息`() {
        val engine = code("domain/ChatEngine.kt")
        // 公开入口
        assertThat(engine).contains("fun proactive(")
        /*
         * 提醒是"系统在她耳边说了一句"，不是对方发来一条。落成用户消息的话，
         * 下一轮历史里会出现一句没人说过的话，模型之后还会引用它。
         */
        assertThat(engine).contains("proactiveHint != null")
        // 命中这条分支时既不造用户消息，也不拿它当"第一句用户消息"起标题
        assertThat(engine).contains("newUserMessage = null")
        assertThat(engine).contains("workingList = history")
        assertThat(engine).contains("ChatTurn(role = \"user\", content = proactiveHint)")
        // 工具面只放行一个
        assertThat(engine).contains("FISH_TOOL_WHITELIST")
    }

    @Test
    fun `约时间的工具只在养鱼场景出现`() {
        val tools = code("domain/agent/tools/BuiltinTools.kt")
        assertThat(tools).contains("ScheduleProactiveTool")
        assertThat(tools).contains("\"schedule_proactive\"")
        // 编程场景也挂上它，模型会把它当日程管理用
        assertThat(tools).contains("Scenes.FISH")
        assertThat(tools).contains("override val enabled")

        // 主动发言发生在用户不在场的时候，弹审批只会挂到超时
        val policy = code("domain/agent/ApprovalPolicy.kt")
        assertThat(policy).contains("schedule_proactive")
    }

    @Test
    fun `到点由闹钟拉起，不依赖 App 在前台`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("ProactiveReceiver")
        assertThat(manifest).contains("com.dshwatch.action.FISH_PROACTIVE")
        // 开机要重排，否则重启一次就再也收不到她主动来的消息
        assertThat(manifest).contains("android.intent.action.BOOT_COMPLETED")

        val schedule = code("domain/fish/FishProactiveSchedule.kt")
        assertThat(schedule).contains("setExactAndAllowWhileIdle")
        assertThat(schedule).contains("AlarmManager.RTC_WAKEUP")
    }

    @Test
    fun `场景是独立的 fish，并且由提示词分到 FishPrompt`() {
        val engine = code("domain/ChatEngine.kt")
        assertThat(engine).contains("Scenes.FISH")
        assertThat(engine).contains("FishPrompt.build(")
    }
}
