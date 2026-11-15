package com.dshwatch.domain.theme

import com.dshwatch.ui.theme.ThemeSeeds
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Test

/**
 * 莫奈取色的性质测试。
 *
 * 这套算法没有"正确答案"可以逐像素比对（我们用的是 Lab 而不是 HCT，
 * 与官方实现必然有几个色相的偏移），所以测试钉的是**性质**：
 *
 *  · 色相不能跑偏（种子是红的，主色不该变成蓝的）；
 *  · tone 必须单调（90 比 40 亮，这是"深浅"的定义）；
 *  · 每套色板都必须可读（正文与背景的对比度过 WCAG AA）；
 *  · 越界颜色要被降彩度而不是改明度（否则整个色板的层级会塌）。
 *
 * 这几条恰好是最容易被"优化"弄坏的地方 —— 比如为了凑色域直接把 tone 挪一档，
 * 单看某个颜色没问题，整套色板就散了。
 */
class MonetColorsTest {

    // 固定色号而不是随机：失败时能直接复现
    private val red = 0xFFB3564F.toInt()
    private val blue = 0xFF3E6FA8.toInt()
    private val gray = 0xFF6E6E76.toInt()

    @Test
    fun `生成的色板与种子同色相`() {
        val p40 = MonetColors.palettes(red).primary.tone(40)
        // Lab 的色相在极低彩度下不稳定，这里在主色档（C≈48）上比，容差 12°
        assertThat(MonetColors.hueDistance(red, p40)).isLessThan(12.0)
    }

    @Test
    fun `tone 越大越亮`() {
        val p = MonetColors.palettes(blue)
        val tones = listOf(10, 30, 50, 70, 90, 100).map { MonetColors.argbToLch(p.primary.tone(it)).l }
        for (i in 1 until tones.size) {
            assertThat(tones[i]).isGreaterThan(tones[i - 1])
        }
    }

    @Test
    fun `深色色板的主色更亮、背景更暗`() {
        val lPrimary = MonetColors.argbToLch(MonetColors.light(blue).primary).l
        val dPrimary = MonetColors.argbToLch(MonetColors.dark(blue).primary).l
        assertThat(dPrimary).isGreaterThan(lPrimary + 20)

        val dBg = MonetColors.argbToLch(MonetColors.dark(blue).background).l
        val lBg = MonetColors.argbToLch(MonetColors.light(blue).background).l
        assertThat(dBg).isLessThan(15.0)
        assertThat(lBg).isGreaterThan(90.0)
    }

    @Test
    fun `正文与背景的对比度达到 AA`() {
        for (seed in listOf(red, blue, gray)) {
            MonetColors.light(seed).let {
                assertThat(contrast(it.surface, it.onSurface)).isAtLeast(4.5)
                assertThat(contrast(it.primary, it.onPrimary)).isAtLeast(4.5)
                assertThat(contrast(it.error, it.onError)).isAtLeast(4.5)
            }
            MonetColors.dark(seed).let {
                assertThat(contrast(it.surface, it.onSurface)).isAtLeast(4.5)
                assertThat(contrast(it.primary, it.onPrimary)).isAtLeast(4.5)
            }
        }
    }

    @Test
    fun `卡片层级是单调的`() {
        // 深色从 lowest 到 highest 逐档变亮 —— 反过来的话"高一层"的卡片
        // 会比底下的更黑，看起来像塌陷
        val dark = MonetColors.dark(red)
        val steps = listOf(
            dark.surfaceContainerLowest,
            dark.surfaceContainerLow,
            dark.surfaceContainer,
            dark.surfaceContainerHigh,
            dark.surfaceContainerHighest,
        ).map { MonetColors.argbToLch(it).l }
        for (i in 1 until steps.size) assertThat(steps[i]).isGreaterThan(steps[i - 1])

        val light = MonetColors.light(red)
        val lSteps = listOf(
            light.surfaceContainerLowest,
            light.surfaceContainerLow,
            light.surfaceContainer,
            light.surfaceContainerHigh,
            light.surfaceContainerHighest,
        ).map { MonetColors.argbToLch(it).l }
        for (i in 1 until lSteps.size) assertThat(lSteps[i]).isLessThan(lSteps[i - 1])
    }

    @Test
    fun `所有颜色都不透明`() {
        for (scheme in listOf(MonetColors.light(red), MonetColors.dark(red))) {
            val all = listOf(
                scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
                scheme.secondary, scheme.onSecondary, scheme.tertiary, scheme.onTertiary,
                scheme.error, scheme.onError, scheme.background, scheme.onBackground,
                scheme.surface, scheme.onSurface, scheme.surfaceVariant, scheme.onSurfaceVariant,
                scheme.surfaceContainerLowest, scheme.surfaceContainerHighest,
                scheme.outline, scheme.outlineVariant,
            )
            for (argb in all) {
                // scrim 不在这个列表里（它本来就带 alpha），其余必须是 FF
                assertThat((argb ushr 24) and 0xFF).isEqualTo(0xFF)
            }
        }
    }

    @Test
    fun `越界的极端彩度只降彩度不改明度`() {
        // 纯青绿在 sRGB 里唱不上去：L=80、C=120 必然越界。
        // 结果必须仍在那个 tone 上（L* 差 < 3），只是变淡了
        val argb = MonetColors.tone(hueDeg = 180.0, chroma = 120.0, tone = 80.0)
        val lch = MonetColors.argbToLch(argb)
        assertThat(abs(lch.l - 80.0)).isLessThan(3.0)
        assertThat(lch.c).isLessThan(120.0)
    }

    @Test
    fun `灰度种子不会生成彩色色板`() {
        val scheme = MonetColors.light(gray)
        // 中性带的彩度是 4，给一点误差
        assertThat(MonetColors.argbToLch(scheme.surface).c).isLessThan(8.0)
        // 但主色仍然要有颜色 —— 否则和"没上色"没区别
        assertThat(MonetColors.argbToLch(scheme.primary).c).isGreaterThan(20.0)
    }

    @Test
    fun `从像素里挑种子`() {
        // 一半灰一半红：种子应该是红
        val pixels = IntArray(1000) { i -> if (i % 2 == 0) 0xFF808080.toInt() else red }
        val seed = MonetColors.seedFromPixels(pixels)
        assertThat(seed).isNotNull()
        assertThat(MonetColors.hueDistance(seed!!, red)).isLessThan(25.0)

        // 全灰：没有可用的种子
        assertThat(MonetColors.seedFromPixels(IntArray(100) { 0xFF808080.toInt() })).isNull()
        // 全黑：同上
        assertThat(MonetColors.seedFromPixels(IntArray(100) { 0xFF000000.toInt() })).isNull()
        // 空数组：不崩
        assertThat(MonetColors.seedFromPixels(IntArray(0))).isNull()
    }

    @Test
    fun `透明像素不参与取色`() {
        // 全透明的红：可见区域其实是空的，不能拿 alpha=0 的像素当选票
        assertThat(MonetColors.seedFromPixels(IntArray(100) { 0x00B3564F })).isNull()
    }

    @Test
    fun `旧强调色能迁移成同色相的种子`() {
        val legacy = 0xFF3B56D6.toInt() // 旧的「深海蓝」
        val seed = MonetColors.seedOfLegacyPrimary(legacy)
        assertThat(MonetColors.hueDistance(seed, legacy)).isLessThan(15.0)
    }

    @Test
    fun `十六进制解析容错`() {
        assertThat(ThemeSeeds.parseHex("#B3564F")).isEqualTo(0xFFB3564F.toInt())
        assertThat(ThemeSeeds.parseHex("b3564f")).isEqualTo(0xFFB3564F.toInt())
        assertThat(ThemeSeeds.parseHex("")).isNull()
        assertThat(ThemeSeeds.parseHex("#GGGGGG")).isNull()
        assertThat(ThemeSeeds.parseHex("#12345")).isNull()
    }

    // -----------------------------------------------------------------

    /** WCAG 对比度。用 L* 反推相对亮度，避免再写一遍矩阵 */
    private fun contrast(a: Int, b: Int): Double {
        val y1 = luminance(a)
        val y2 = luminance(b)
        return (maxOf(y1, y2) + 0.05) / (minOf(y1, y2) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        val l = MonetColors.argbToLch(argb).l
        // L* → Y（CIE 定义）
        return if (l > 8.0) ((l + 16.0) / 116.0).pow(3.0) else l / 903.3
    }
}
