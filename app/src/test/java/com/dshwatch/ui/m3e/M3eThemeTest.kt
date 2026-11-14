package com.dshwatch.ui.m3e

import androidx.compose.material3.darkColorScheme
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Typography as WearTypography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.dshwatch.domain.model.UiStyles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M3E 主题层的守卫。
 *
 * 这里测的不是「颜色好不好看」，而是几条**错了会很难发现**的不变量：
 * 两套 MaterialTheme 的色板必须同源，否则同一屏里自己画的字和 M3E 卡片的字
 * 会呈现两种白；切换界面体系不能让页面底色变化，否则用户会以为是换了主题。
 */
class M3eThemeTest {

    @Test
    fun `界面体系的未知取值一律落回经典`() {
        // 老存档里没有这个字段（空串）、或者手改坏了，都不能让界面画不出来
        assertThat(UiStyles.of("")).isEqualTo(UiStyles.CLASSIC)
        assertThat(UiStyles.of("m3e")).isEqualTo(UiStyles.M3E)
        assertThat(UiStyles.of("m3expressive")).isEqualTo(UiStyles.CLASSIC)
        assertThat(UiStyles.of("M3E")).isEqualTo(UiStyles.CLASSIC)
        assertThat(UiStyles.isM3e(UiStyles.M3E)).isTrue()
        assertThat(UiStyles.isM3e(UiStyles.CLASSIC)).isFalse()
    }

    @Test
    fun `Wear 色板与手机色板同源`() {
        val phone = darkColorScheme(
            primary = Color(0xFF7AB8FF),
            onPrimary = Color(0xFF00203F),
            primaryContainer = Color(0xFF1B3B5C),
            onPrimaryContainer = Color(0xFFCFE5FF),
            secondary = Color(0xFFB7C8DA),
            tertiary = Color(0xFFFFB4AB),
            background = Color(0xFF0E1116),
            onBackground = Color(0xFFE2E6EC),
            surface = Color(0xFF12161C),
            surfaceContainer = Color(0xFF171C23),
            surfaceContainerHigh = Color(0xFF1D232B),
            onSurface = Color(0xFFE2E6EC),
            onSurfaceVariant = Color(0xFFA8B2BF),
            outline = Color(0xFF6E7885),
            outlineVariant = Color(0xFF3A424C),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )

        val wear = wearColorScheme(phone)

        // 主色 / 正文色 / 背景：这三条只要有一条不一致，界面上就会看出色差
        assertThat(wear.primary).isEqualTo(phone.primary)
        assertThat(wear.onSurface).isEqualTo(phone.onSurface)
        assertThat(wear.onSurfaceVariant).isEqualTo(phone.onSurfaceVariant)
        assertThat(wear.background).isEqualTo(phone.background)
        assertThat(wear.error).isEqualTo(phone.error)

        // Wear 只有三档 surface，手机那边的名字不同，映射必须落在同一层
        assertThat(wear.surfaceContainerLow).isEqualTo(phone.surface)
        assertThat(wear.surfaceContainer).isEqualTo(phone.surfaceContainer)
        assertThat(wear.surfaceContainerHigh).isEqualTo(phone.surfaceContainerHigh)
    }

    @Test
    fun `透明背景不会让 Wear 色板也变透明`() {
        // 有自定义背景图时手机会把 background 换成 Transparent，
        // 好让照片透上来。Wear 的 background 是 AppScaffold 画的**实色底**，
        // 跟着变透明就等于把整页底色弄没了 —— 必须退回 surface。
        val phone = darkColorScheme(
            background = Color.Transparent,
            surface = Color(0xFF12161C),
        )
        val wear = wearColorScheme(phone)
        assertThat(wear.background).isEqualTo(phone.surface)
        assertThat(wear.background.alpha).isEqualTo(1f)
    }

    @Test
    fun `暗强调色不是纯黑`() {
        // primaryDim 用来画按下态。如果直接拿 primary 乘 alpha，深色底上会糊成一片黑，
        // 看起来像是"按钮坏了"。这里守的是"它必须还能看出主色"。
        val phone = lightColorScheme(primary = Color(0xFF2F5C8A), background = Color(0xFFFDFCFF))
        val wear = wearColorScheme(phone)
        val dim = wear.primaryDim
        assertThat(dim.alpha).isEqualTo(1f)
        assertThat(dim).isNotEqualTo(Color.Black)
        assertThat(dim.blue).isGreaterThan(dim.red)
    }

    @Test
    fun `按钮尺寸仍然是规范值`() {
        /*
         * 这条测试看着像在抄文档，其实是**升级哨兵**。
         *
         * M3eControls 里所有按钮都写死 `ButtonDefaults.Height` / `IconSize`，
         * 一旦 Wear Compose 改版把 52 改成 56、26 改成 24，界面上不会报错，
         * 只会「莫名变挤」或「图标显得小」—— 那种问题极难反查。
         * 这里把当前依赖的三个数钉住：库一升级，测试先红。
         *
         * 20sp 是按钮标签的字号（labelLarge），不是正文：手表上的按钮字
         * 必须比手机上大一号，用 14sp 会让整屏看起来像缩略图。
         */
        assertThat(ButtonDefaults.Height.value).isEqualTo(52f)
        assertThat(ButtonDefaults.IconSize.value).isEqualTo(26f)
        assertThat(ButtonDefaults.CompactButtonHeight.value).isEqualTo(48f)
        assertThat(WearTypography().labelLarge?.fontSize?.value).isEqualTo(20f)
        assertThat(WearTypography().labelMedium?.fontSize?.value).isEqualTo(15f)
    }
}
