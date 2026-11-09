package com.dshwatch.device

import com.dshwatch.domain.device.Bounds
import com.dshwatch.domain.device.DeviceControlHub
import com.dshwatch.domain.device.ScreenDigest
import com.dshwatch.domain.device.UiElement
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 读屏文本的格式化规则。
 *
 * 这几条断言对应的是「模型能不能看懂并正确点下去」，不是排版美观：
 * 编号必须连续、密码不能出现内容、列表条目要缩进、超预算要说明还剩多少。
 */
class ScreenDigestTest {

    private fun el(
        id: Int,
        role: String,
        label: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        focused: Boolean = false,
        scrollable: Boolean = false,
        inList: Boolean = false,
        top: Int = id * 40,
    ) = UiElement(
        id = id,
        role = role,
        label = label,
        bounds = Bounds(0, top, 200, top + 36),
        clickable = clickable,
        editable = editable,
        password = password,
        focused = focused,
        scrollable = scrollable,
        inList = inList,
    )

    @Test
    fun `header carries app and count`() {
        val text = ScreenDigest.format(
            appLabel = "微信",
            packageName = "com.tencent.mm",
            keyboardShown = false,
            elements = listOf(el(1, "按钮", "返回", clickable = true)),
        )
        assertThat(text.lines().first()).isEqualTo("微信 · 1 项")
        assertThat(text).contains("[1] 按钮 返回")
    }

    @Test
    fun `keyboard state is visible when shown`() {
        val text = ScreenDigest.format("设置", "com.android.settings", true, listOf(el(1, "按钮", "确定")))
        assertThat(text.lines().first()).contains("键盘弹出")
    }

    @Test
    fun `password content never reaches the model`() {
        val text = ScreenDigest.format(
            appLabel = "银行",
            packageName = "com.bank",
            keyboardShown = false,
            elements = listOf(el(1, "输入框", "hunter2", editable = true, password = true)),
        )
        assertThat(text).doesNotContain("hunter2")
        assertThat(text).contains("🔒")
    }

    @Test
    fun `list items are indented one level`() {
        val text = ScreenDigest.format(
            appLabel = "聊天",
            packageName = "com.x",
            keyboardShown = false,
            elements = listOf(
                el(1, "列表", "会话", scrollable = true),
                el(2, "按钮", "张三 · 你好", clickable = true, inList = true),
            ),
        )
        assertThat(text).contains("\n  [2] 按钮 张三 · 你好")
    }

    @Test
    fun `focused input is marked`() {
        val text = ScreenDigest.format(
            appLabel = "搜索",
            packageName = "com.x",
            keyboardShown = true,
            elements = listOf(el(1, "输入框", "搜点什么", editable = true, focused = true)),
        )
        assertThat(text).contains("已聚焦")
    }

    @Test
    fun `over budget is cut with a hint instead of silently dropped`() {
        val many = (1..60).map { el(it, "按钮", "第 $it 个按钮名字稍微长一点点", clickable = true) }
        val text = ScreenDigest.format("应用", "com.x", false, many, budget = 400)
        assertThat(text).contains("未显示")
        // 截断之后仍然要按顺序编号，不能跳号
        assertThat(text).contains("[1] ")
    }

    @Test
    fun `empty screen explains itself instead of returning blank`() {
        val text = ScreenDigest.format("桌面", "com.x", false, emptyList())
        assertThat(text).contains("没有可读的文字")
    }

    @Test
    fun `label is clipped to keep one element per line`() {
        val long = "很长的文字".repeat(20)
        val text = ScreenDigest.format(
            "应用", "com.x", false,
            listOf(el(1, "文字", long)),
        )
        assertThat(text).contains("…")
        assertThat(text).doesNotContain(long)
    }

    @Test
    fun `fingerprint changes when the element moves`() {
        val a = el(1, "按钮", "发送", clickable = true, top = 100)
        val b = el(1, "按钮", "发送", clickable = true, top = 140)
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint())
    }

    @Test
    fun `fingerprint is stable for the same element`() {
        val a = el(3, "按钮", "发送", clickable = true, top = 100)
        val b = el(7, "按钮", "发送", clickable = true, top = 100)
        // 编号变了但元素没变 —— 这正是「重新读屏后编号漂移」时能找回目标的前提
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint())
    }

    @Test
    fun `bounds intersection keeps the on-screen part`() {
        val screen = Bounds(0, 0, 300, 300)
        val half = Bounds(-40, 260, 100, 340)
        val clipped = half.intersect(screen)
        assertThat(clipped.left).isEqualTo(0)
        assertThat(clipped.bottom).isEqualTo(300)
    }

    @Test
    fun `off-screen element is not visible`() {
        assertThat(ScreenDigest.visible(Bounds(0, 500, 100, 540), 300, 300)).isFalse()
        assertThat(ScreenDigest.visible(Bounds(0, 100, 100, 140), 300, 300)).isTrue()
    }
}

/** 停止闸门：用户点停止之后，下一次工具调用必须立刻放弃。 */
class DeviceControlHubTest {

    @Test
    fun `stop blocks the next tool call`() {
        DeviceControlHub.clearStop()
        assertThat(DeviceControlHub.abortIfStopped()).isNull()

        DeviceControlHub.requestStop("用户点了停止")
        assertThat(DeviceControlHub.abortIfStopped()).contains("用户已停止")
    }

    @Test
    fun `a new task clears the previous stop`() {
        DeviceControlHub.requestStop("上一轮")
        DeviceControlHub.begin("新一轮")
        // 上一轮的停止标志不能把新任务立刻掐掉 —— 否则用户点过一次停止，
        // 之后所有操控请求都会被拒，只能重启 App。
        assertThat(DeviceControlHub.abortIfStopped()).isNull()
    }

    @Test
    fun `stop is idempotent`() {
        DeviceControlHub.clearStop()
        var count = 0
        DeviceControlHub.onStopRequested = { count++ }
        DeviceControlHub.requestStop("a")
        DeviceControlHub.requestStop("b")
        assertThat(count).isEqualTo(1)
        DeviceControlHub.onStopRequested = null
        DeviceControlHub.end()
    }

    @Test
    fun `stale snapshot is detected`() {
        val snap = DeviceControlHub.Snapshot(
            packageName = "com.x",
            appLabel = "X",
            elements = emptyList(),
            text = "",
            at = System.currentTimeMillis() - 31_000,
        )
        assertThat(snap.stale()).isTrue()
    }
}

/**
 * 受保护页面：AI 不能给自己开权限、装应用、关别人的授权。
 *
 * 这几条断言守的是提示词之外的那道硬边界 —— 模型可以在提示里被说服，
 * 但说服不了这里的 `contains`。
 */
class DeviceGuardTest {

    @Test
    fun `settings pages are blocked`() {
        assertThat(com.dshwatch.domain.device.DeviceGuard.isBlocked("com.android.settings")).isTrue()
        assertThat(com.dshwatch.domain.device.DeviceGuard.isBlocked("com.miui.settings")).isTrue()
    }

    @Test
    fun `installers and permission controllers are blocked`() {
        assertThat(com.dshwatch.domain.device.DeviceGuard.isBlocked("com.android.packageinstaller")).isTrue()
        assertThat(
            com.dshwatch.domain.device.DeviceGuard.isBlocked("com.android.permissioncontroller"),
        ).isTrue()
    }

    @Test
    fun `system ui is blocked because quick settings bypass everything`() {
        assertThat(com.dshwatch.domain.device.DeviceGuard.isBlocked("com.android.systemui")).isTrue()
    }

    @Test
    fun `normal apps are allowed`() {
        assertThat(com.dshwatch.domain.device.DeviceGuard.isBlocked("com.tencent.mm")).isFalse()
        assertThat(com.dshwatch.domain.device.DeviceGuard.isBlocked(null)).isFalse()
        // 名字里带 settings 的普通应用不该被误伤
        assertThat(com.dshwatch.domain.device.DeviceGuard.isBlocked("com.example.settingsdemo")).isFalse()
    }

    @Test
    fun `refusal tells the model what to do next`() {
        val msg = com.dshwatch.domain.device.DeviceGuard.refusal("com.android.settings")
        assertThat(msg).contains("device_handoff")
        assertThat(msg).contains("系统设置")
    }
}
