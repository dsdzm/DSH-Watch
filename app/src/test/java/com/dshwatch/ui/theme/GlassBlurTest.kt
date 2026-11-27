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
    fun `背景层不再挂实时模糊，糊发生在解码阶段`() {
        val body = code("ui/theme/Theme.kt")
        /*
         * `Modifier.blur` 在部分手表 ROM 上会被**静默忽略**：开关打开、画面不动，
         * 而且没有任何报错。用户连着两个版本报「没变化」，根子就在这里。
         * 现在改成解码时算一次（SoftBlur），运行时只画一张糊好的图 —— 一定生效。
         */
        assertThat(body).doesNotContain("Modifier.blur(")
        assertThat(body).contains("blurRadiusFraction = if (blurBackdrop) bgRadiusFraction(bgStrength) else 0f")
        // 背景与内容仍是两个节点：卡片采样要求「背景只有这一层」
        assertThat(body).contains("Box(modifier = Modifier.fillMaxSize()) {")
    }

    @Test
    fun `背景模糊与玻璃磨砂是两条独立的线`() {
        val body = code("ui/theme/Theme.kt")
        // 背景层糊不糊只由 backgroundBlur 决定，与材质无关
        assertThat(body).contains("val blurBackdrop = backgroundBlur")
        // 玻璃磨砂要同时满足「玻璃材质 + 用户打开」
        assertThat(body).contains("val glassFrost = glass && glassBlur")
        // 卡片的磨砂观感跟着 glassFrost 走，不跟背景层
        assertThat(body).contains("LocalGlassBlur provides glassFrost")
        // 两条轴的强度互不相干：背景那根只在 backdrop 里，卡片那根只在 frostPhoto 里
        assertThat(body).contains("bgRadiusFraction(bgStrength)")
        assertThat(body).contains("glassRadiusFraction(glassStrength)")
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
