package com.dshwatch.ui.screens.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆屏聊天页「鱼眼」已移除的静态防线。
 *
 * 用户 1.15.7 反馈：圆形屏幕专属的环形布局下，聊天页鱼眼效果让对话框
 * 显示异常、文字抽搐，建议直接移除鱼眼特效。
 *
 * 根因是鱼眼同时干了这两件事：
 *
 *   · `padding(horizontal = extra)` 随 `layoutInfo.visibleItemsInfo` 每帧变化，
 *     每一项被反复重新测量，文本换行位置逐帧跳；
 *   · `graphicsLayer { scaleX / scaleY }` 把已栅格化的文字再缩放，看着在抖。
 *
 * 这两条都不会抛异常，只会「看着不对」，所以得靠源码结构守着别再长回来。
 */
class ChatRoundRuleTest {

    private val root = File("src/main/java/com/dshwatch")

    private fun code(path: String): String {
        val f = File(root, path)
        assertTrue("找不到 $path", f.isFile)
        // 注释里会写「以前是怎么实现的」，扫源码前必须剥掉，否则必然假阳性
        return f.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    @Test
    fun `聊天页不许再有逐项鱼眼几何`() {
        val chat = code("ui/screens/chat/ChatScreen.kt")
        listOf(
            "roundRow" to "逐项变换函数",
            "roundShift" to "每帧计算的偏移表",
            "scaleX = scale" to "缩放文字",
            "alpha = 1f - 0.35f" to "按位置淡出",
            "sqrt(1f - t * t)" to "弦长内收公式",
        ).forEach { (token, what) ->
            assertTrue("不该再有$what（$token）", !chat.contains(token))
        }
        assertTrue(
            "消息项不该再按滚动位置动态改 padding",
            !chat.contains("padding(horizontal = extra)"),
        )
    }

    @Test
    fun `圆屏的其它适配要保留`() {
        /*
         * 移除的是「逐项动态几何」，不是整个圆屏适配。
         * 安全区、气泡宽度、滚动弧线都还得在，否则贴边内容会被圆角切掉。
         */
        val chat = code("ui/screens/chat/ChatScreen.kt")
        assertTrue("圆屏滚动弧线要保留", chat.contains("RoundScrollArc("))
        assertTrue("气泡仍按圆屏收窄", chat.contains("isRound") && chat.contains("0.86f"))
        assertTrue("列表位置仍按圆屏分档", chat.contains("round"))

        val common = code("ui/components/Common.kt")
        // 形状不再由参数传进来，改成读 LocalDeviceProfile —— 断言跟着改成实际实现
        assertTrue(
            "圆屏安全区由 cornerSafe 负责",
            common.contains("fun Modifier.cornerSafe(") && common.contains("LocalDeviceProfile"),
        )
    }
}
