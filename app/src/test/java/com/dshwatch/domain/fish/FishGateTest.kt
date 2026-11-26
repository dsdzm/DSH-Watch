package com.dshwatch.domain.fish

import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 「养鱼会自己联网」这件事的闸门。
 *
 * ## 为什么值得一组专门的测试
 *
 * 这是全应用唯一一个**用户不在场时也会发请求**的模式。它的开关一旦松动，
 * 后果不是"界面难看"，而是"用户没同意就花他的额度"：他只是点进养鱼页
 * 看了一眼、一句话没说，之后每天四次请求照发，还查不出是谁发的。
 *
 * 所以这里钉三件事：
 *  1. 默认必须是关的；
 *  2. 关着时三道闸（排闹钟 / 主动发言 / 写动态）都要真的拦住；
 *  3. 两个入口（记录点开 / 顶栏切模式）必须落到同一个界面。
 */
class FishGateTest {

    private fun read(path: String): String {
        val f = File(path)
        assertThat(f.isFile).isTrue()
        return f.readText()
    }

    // ---------------- 开关本身 ----------------

    @Test
    fun `养鱼默认不开启`() {
        assertThat(AppSettings().fishEnabled).isFalse()
    }

    @Test
    fun `排闹钟前先看总开关`() {
        val schedule = read("src/main/java/com/dshwatch/domain/fish/FishProactiveSchedule.kt")
        assertThat(schedule).contains("if (!settings.fishEnabled) {")
        // 关掉时必须**撤掉**已经排上的那一个，否则升级/关闭之后还会响一次
        val gate = schedule.indexOf("if (!settings.fishEnabled) {")
        val cancel = schedule.indexOf("cancel(context)", gate)
        assertThat(cancel).isGreaterThan(gate)
    }

    @Test
    fun `主动发言执行前再看一次总开关`() {
        // 闹钟撤不干净（系统已放出的那一次）时，这是最后一道闸
        val runner = read("src/main/java/com/dshwatch/service/FishProactiveRunner.kt")
        assertThat(runner).contains("if (!settings.fishEnabled) {")
        assertThat(runner).contains("这一轮主动发言跳过")
    }

    @Test
    fun `动态生成也要拦住`() {
        val writer = read("src/main/java/com/dshwatch/service/FishMomentWriter.kt")
        assertThat(writer).contains("if (!settings.fishEnabled && !force) {")
    }

    /**
     * 一句都没说过就不主动开口。
     *
     * 以前这里会 `startNew` 新开一条会话 —— 于是"点进去看了一眼"等于
     * "以后每天帮她开一次对话"，屏幕上凭空多出没聊过的记录。
     */
    @Test
    fun `没有聊过就不主动开口`() {
        val runner = read("src/main/java/com/dshwatch/service/FishProactiveRunner.kt")
        assertThat(runner).doesNotContain("c.engine.startNew(")
        assertThat(runner).contains("Role.USER")
    }

    // ---------------- 界面上的体现 ----------------

    @Test
    fun `两端都有启用卡片`() {
        val phone = read("src/main/java/com/dshwatch/ui/screens/fish/FishPhoneScreen.kt")
        assertThat(phone).contains("FishEnableCard(")
        val watch = read("src/main/java/com/dshwatch/ui/screens/fish/FishWatchScreen.kt")
        assertThat(watch).contains("FishEnablePanel(")
        // 闸必须在读数据之前：下面会开会话、补写动态，都是读盘与联网
        val gatePhone = phone.indexOf("if (!settings.fishEnabled) {")
        val effectPhone = phone.indexOf("LaunchedEffect(Unit) {")
        assertThat(gatePhone).isGreaterThan(0)
        assertThat(gatePhone).isLessThan(effectPhone)
    }

    @Test
    fun `设置页第一项就是养鱼开关`() {
        val settings = read("src/main/java/com/dshwatch/ui/screens/fish/FishSettingsScreen.kt")
        val title = settings.indexOf("title = \"养鱼\",")
        val persona = settings.indexOf("title = \"人设\",")
        assertThat(title).isGreaterThan(0)
        assertThat(persona).isGreaterThan(title)
        assertThat(settings).contains("fishEnabled = on")
    }

    /**
     * 两个入口必须落到同一个界面。
     *
     * 曾经的分叉：从「记录」点开走 `Screen.RoleplayChat`（经典玻璃那页，
     * 一条消息一整块），从顶栏切模式走 `Screen.Chat` → 养鱼页（M3E）。
     * 同一条对话换个入口长相全变，用户会以为是两个功能。
     */
    @Test
    fun `记录里点开的养鱼会话交给养鱼页`() {
        val roleplay = read("src/main/java/com/dshwatch/ui/screens/chat/RoleplayChatScreen.kt")
        assertThat(roleplay).contains("if (scene == Scenes.FISH)")
        val gate = roleplay.indexOf("if (scene == Scenes.FISH)")
        val target = roleplay.indexOf("FishWatchScreen(", gate)
        assertThat(target).isGreaterThan(gate)
    }

    @Test
    fun `顶栏切模式与记录入口共用养鱼页`() {
        // 手表：两条路最终都是 FishWatchScreen
        val entry = read("src/main/java/com/dshwatch/ui/screens/fish/FishScreen.kt")
        assertThat(entry).contains("FishWatchScreen(")
        // 手机：两条路最终都是 FishPhoneScreen
        val phoneRoot = read("src/main/java/com/dshwatch/ui/phone/PhoneRoot.kt")
        assertThat(phoneRoot).contains("FishPhoneScreen(")
        val phoneChat = read("src/main/java/com/dshwatch/ui/phone/PhoneRoleplayChat.kt")
        assertThat(phoneChat).contains("FishPhoneScreen(")
    }
}
