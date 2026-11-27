package com.dshwatch.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 正文色必须由主题给，不能落到 Compose 的默认黑。
 *
 * ## 这条约束守的是什么
 *
 * `Text` 不传 `color` 时读 `LocalContentColor`，而它的默认值是 `Color.Black`。
 * 整个 App 没有用 `Surface` 当页面底（玻璃与 Wear 都是自绘底色），所以没有
 * 任何一层把它换成 `onSurface` —— 结果就是浅色下「看起来正常」（黑字本来就对），
 * 深色下所有没写颜色的字都留在黑色上。用户报的「深色模式里字体颜色没变、
 * 所有界面体系都有」正是这一条，涉及四百多处 `Text(text = …, style = …)`。
 *
 * 逐个补 `color =` 既补不完，下次新增页面还会再漏，所以改在主题根部给一次。
 * 这几条断言就是钉住那个「一次」：根部提供给删掉、或者有人把弹窗正文改回
 * 不取色，测试都会红。
 */
class ThemeContentColorTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    @Test
    fun `手表主题在根部提供正文色`() {
        val body = code("ui/theme/Theme.kt")
        // 只认「用当前色板的 onSurface」，不许写成固定色
        assertThat(body).contains("LocalContentColor provides colorScheme.onSurface")
    }

    @Test
    fun `手机主题同样提供正文色`() {
        val body = code("ui/phone/PhoneTheme.kt")
        assertThat(body).contains("LocalContentColor provides scheme.onSurface")
    }

    @Test
    fun `确认弹窗的正文显式取色`() {
        val body = code("ui/components/Common.kt")
        val confirm = body.substringAfter("fun ConfirmDialog(").substringBefore("@Composable")
        /*
         * 这一处是用户截图里最直观的那一块：「关掉养鱼？」的说明文字在深色下
         * 仍是黑的。根部已经兜住了默认值，这里再按层级降到 onSurfaceVariant ——
         * 标题一级、说明二级，深浅两种主题都不会反过来。
         */
        assertThat(confirm).contains("MaterialTheme.colorScheme.onSurfaceVariant")
    }

    @Test
    fun `她的动态页面文字取主题色`() {
        val body = code("ui/screens/fish/FishMomentsScreen.kt")
        // 正文气泡与空态标题：两处都曾经只有 style、没有 color
        assertThat(body).contains("color = MaterialTheme.colorScheme.onSurface")
    }
}
