package com.dshwatch.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 「高斯模糊」这条链路的接线。
 *
 * 这一项特别容易「接线断了但看起来正常」：
 *
 *  - 设置页的开关改了字段，主题没读 → 点了没反应；
 *  - 主题读了，但模糊加在**和内容同一层**的 Box 上 → 整页连文字一起糊；
 *  - 忘了按 API 版本兜底 → Android 11 的表上 `Modifier.blur` 静默无效，
 *    用户以为坏了；
 *  - 玻璃本体还按「背景清晰」调的那套 0.68 黑去压 → 糊了也看不出来。
 *
 * 四条都是编译期发现不了的，所以钉在源码上。
 */
class GlassBlurTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    @Test
    fun `主题层把背景单独一层，模糊只作用于它`() {
        val body = code("ui/theme/Theme.kt")
        // 真有 blur
        assertThat(body).contains("Modifier.blur(")
        // 只糊背景层：blur 挂在 drawBehind 那一层上
        assertThat(body).contains("GLASS_BLUR_RADIUS")
        // 背景与内容分成两个节点 —— 同一个 Box 里两者一起糊
        assertThat(body).contains("Box(modifier = Modifier.fillMaxSize()) {")
    }

    @Test
    fun `背景模糊与玻璃磨砂是两条独立的线`() {
        val body = code("ui/theme/Theme.kt")
        // 背景层糊不糊只由 backgroundBlur 决定，与材质无关
        assertThat(body).contains("val blurBackdrop = backgroundBlur && Build.VERSION.SDK_INT >= MIN_SDK_GLASS_BLUR")
        // 玻璃磨砂要同时满足「玻璃材质 + 用户打开」
        assertThat(body).contains("val glassFrost = glass && glassBlur && Build.VERSION.SDK_INT >= MIN_SDK_GLASS_BLUR")
        // 卡片的磨砂观感跟着 glassFrost 走，不跟背景层
        assertThat(body).contains("LocalGlassBlur provides glassFrost")
        // 可用下限是 minSdk（低版本走 RenderScript），实时路径的下限才是 31
        assertThat(body).contains("const val MIN_SDK_GLASS_BLUR = 26")
        assertThat(body).contains("const val MIN_SDK_LIVE_BLUR = 31")
    }

    @Test
    fun `深色玻璃不把底压成灰板`() {
        val body = code("ui/components/Glass.kt")
        assertThat(body).contains("LocalGlassBlur.current")
        /*
         * 0.68 的黑 + 0.24 的白 = 83% 不透明：底被压平、正文只剩 3.1:1。
         * 现在深色是「一层压暗 + 一层薄白」，两者都不许回到老值。
         */
        assertThat(body).contains("elevated -> 0.74f")
        assertThat(body).contains("else -> 0.55f")
        assertThat(body).contains("drawRoundRect(Color.Black, cornerRadius = r, alpha = scrim * alpha)")
        assertThat(body).doesNotContain("0.68f else")
        assertThat(body).doesNotContain("if (frosted) 0.18f else 0.24f")
    }

    @Test
    fun `设置页两个开关各管一件事`() {
        val body = code("ui/screens/settings/SettingsGroupScreen.kt")
        assertThat(body).contains("backgroundBlur = v")
        assertThat(body).contains("glassBlur = v")
        // 磨砂只在玻璃材质下出现；背景模糊只要有背景图就给
        assertThat(body).contains("Appearances.isGlass(settings.appearance)")
        assertThat(body).contains("settings.customBackground.isNotBlank()")
        // 低版本也有兼容路径，不能再画「不可用」挡掉
        assertThat(body).doesNotContain("需要 Android 12 及以上系统")
    }

    @Test
    fun `开关值真的传进主题`() {
        val body = code("MainActivity.kt")
        assertThat(body).contains("glassBlur = settings.glassBlur")
        assertThat(body).contains("backgroundBlur = settings.backgroundBlur")
    }
}
