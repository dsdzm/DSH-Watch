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
    fun `模糊只在国内条件成立时启用`() {
        val body = code("ui/theme/Theme.kt")
        // 玻璃 + 用户开启 + 系统支持
        assertThat(body).contains("glass && glassBlur && Build.VERSION.SDK_INT >= MIN_SDK_GLASS_BLUR")
        // 可用下限是 minSdk（低版本走 RenderScript），实时路径的下限才是 31
        assertThat(body).contains("const val MIN_SDK_GLASS_BLUR = 26")
        assertThat(body).contains("const val MIN_SDK_LIVE_BLUR = 31")
        assertThat(body).contains("LocalGlassBlur provides blurBackdrop")
    }

    @Test
    fun `玻璃本体在真模糊下变薄`() {
        val body = code("ui/components/Glass.kt")
        assertThat(body).contains("LocalGlassBlur.current")
        // 深色普通卡片的黑底与白面都要让一档，否则糊了看不出来
        assertThat(body).contains("if (frosted && !elevated) 0.56f else 0.68f")
        assertThat(body).contains("if (frosted) 0.18f else 0.24f")
        // 弹窗不动：它的对比度是单独调过的
        assertThat(body).contains("elevated")
    }

    @Test
    fun `设置页开关只在玻璃材质下出现`() {
        val body = code("ui/screens/settings/SettingsGroupScreen.kt")
        assertThat(body).contains("glassBlur = v")
        assertThat(body).contains("Appearances.isGlass(settings.appearance)")
        // 低版本也有兼容路径，不能再画「不可用」挡掉
        assertThat(body).doesNotContain("需要 Android 12 及以上系统")
        assertThat(body).contains("MIN_SDK_LIVE_BLUR")
    }

    @Test
    fun `开关值真的传进主题`() {
        val body = code("MainActivity.kt")
        assertThat(body).contains("glassBlur = settings.glassBlur")
    }
}
