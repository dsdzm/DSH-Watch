package com.dshwatch.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 界面缩放的取值与设备档案重算。
 *
 * 守两件事：
 *  1) 自动值必须让「可用宽度不足 320dp」的设备收到能放下的程度 ——
 *     上一版只缩放字号、不缩放密度，206dp 的屏上整套卡片仍然溢出；
 *  2) 缩放之后设备档案里的 dp 宽度必须跟着重算，否则圆屏弦长按旧宽度算，
 *     内容会偏出圆周。
 */
class UiScaleTest {

    private val wide = DeviceProfileDefaults.fallback.copy(
        screenWidthPx = 412,
        screenHeightPx = 502,
        screenWidthDp = 206,
        screenHeightDp = 251,
        densityDpi = 320,
    )

    @Test
    fun `narrow high dpi screen is scaled down enough to fit 320dp`() {
        val s = UiScale.auto(206)
        // 206 / 320 ≈ 0.64
        assertThat(s).isWithin(0.01f).of(0.644f)

        // 缩放后的可用宽度要真的回到 320dp 附近，这是「放得下」的判据
        val scaled = UiScale.rescale(wide, s)
        assertThat(scaled.screenWidthDp).isAtLeast(315)
        assertThat(scaled.screenWidthDp).isAtMost(325)
    }

    @Test
    fun `design width maps to exactly one`() {
        assertThat(UiScale.auto(320)).isWithin(0.001f).of(1f)
    }

    @Test
    fun `wider screen is only slightly enlarged`() {
        // 329dp 的圆屏（412×502 @200dpi）原本观感正常，不应被改动太多
        assertThat(UiScale.auto(329)).isWithin(0.01f).of(1.03f)
    }

    @Test
    fun `absurd widths are clamped`() {
        assertThat(UiScale.auto(80)).isEqualTo(UiScale.MIN)
        assertThat(UiScale.auto(4000)).isEqualTo(UiScale.MAX)
    }

    @Test
    fun `unknown width falls back to standard`() {
        assertThat(UiScale.auto(0)).isEqualTo(1f)
        assertThat(UiScale.auto(-1)).isEqualTo(1f)
    }

    @Test
    fun `auto mode ignores the stored manual value`() {
        val v = UiScale.resolve(value = 1.15f, autoEnabled = true, widthDp = 206)
        assertThat(v).isWithin(0.01f).of(0.644f)
    }

    @Test
    fun `manual mode keeps the user value`() {
        val v = UiScale.resolve(value = 0.85f, autoEnabled = false, widthDp = 206)
        assertThat(v).isWithin(0.001f).of(0.85f)
    }

    @Test
    fun `broken stored value falls back to standard instead of blank screen`() {
        // 0 / NaN / 负数都可能来自旧版存档或手改配置文件；density 乘 0 会让整屏消失
        assertThat(UiScale.resolve(0f, autoEnabled = false, widthDp = 320)).isEqualTo(1f)
        assertThat(UiScale.resolve(Float.NaN, autoEnabled = false, widthDp = 320)).isEqualTo(1f)
        assertThat(UiScale.resolve(-3f, autoEnabled = false, widthDp = 320)).isEqualTo(1f)
    }

    @Test
    fun `manual value is clamped to the slider range`() {
        assertThat(UiScale.resolve(5f, false, 320)).isEqualTo(UiScale.MAX)
        assertThat(UiScale.resolve(0.1f, false, 320)).isEqualTo(UiScale.MIN)
    }

    @Test
    fun `recommended value is the fit value, not the hard limit`() {
        // 推荐值用于设置页提示「超过它会开始有内容贴边」，
        // 但它不是上限 —— 手动仍可拖到 MAX（用户宁可裁一点也要放大）
        assertThat(UiScale.recommended(206)).isWithin(0.01f).of(0.644f)
        assertThat(UiScale.resolve(1.15f, false, 206)).isWithin(0.001f).of(1.15f)
    }

    @Test
    fun `scale is a single monotonic axis`() {
        // v1.15.22 把字号并进了 uiScale，整块界面只剩这一条缩放路径。
        // 这里守「单调」：缩放值越大，缩放后的可用 dp 越少（内容越挤）。
        // 以前字号是另一条平行的轴，两条同时放大时这里的关系会被打破 ——
        // 表现就是字撑破卡片、内容顶出圆周。
        val fitted = UiScale.rescale(wide, UiScale.auto(206)).screenWidthDp
        val bigger = UiScale.rescale(wide, 0.80f).screenWidthDp
        assertThat(bigger).isLessThan(fitted)
        assertThat(fitted).isAtLeast(315)
    }

    @Test
    fun `corner inset grows in dp so its physical size stays put`() {
        val scaled = UiScale.rescale(wide, 0.64f)
        // 16dp @ scale 0.64 → 25dp，物理像素不变，圆角才避得开
        assertThat(scaled.cornerInsetDp).isEqualTo(25)
    }

    @Test
    fun `scale of one leaves the profile untouched`() {
        assertThat(UiScale.rescale(wide, 1f)).isSameInstanceAs(wide)
    }
}
