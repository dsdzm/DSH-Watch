package com.dshwatch.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 布局主题的解析。
 *
 * 守的是「AUTO 必须选对」：选错了不会崩，但会把 320dp 的手机布局塞进 206dp 的表盘，
 * 或者反过来在圆表上用矩形骨架 —— 两种都是「看起来能用、实际被圆边切掉一角」，
 * 靠肉眼很难判断是配置错了还是布局坏了。
 */
class LayoutThemesTest {

    @Test
    fun `unknown id falls back to auto`() {
        assertThat(LayoutThemes.of("")).isEqualTo(LayoutThemes.AUTO)
        assertThat(LayoutThemes.of(null)).isEqualTo(LayoutThemes.AUTO)
        assertThat(LayoutThemes.of("tablet")).isEqualTo(LayoutThemes.AUTO)
        assertThat(LayoutThemes.of("WATCH")).isEqualTo(LayoutThemes.AUTO)
    }

    @Test
    fun `known ids survive`() {
        assertThat(LayoutThemes.of(LayoutThemes.WATCH)).isEqualTo(LayoutThemes.WATCH)
        assertThat(LayoutThemes.of(LayoutThemes.PHONE)).isEqualTo(LayoutThemes.PHONE)
        assertThat(LayoutThemes.of(LayoutThemes.AUTO)).isEqualTo(LayoutThemes.AUTO)
    }

    @Test
    fun `explicit choice wins over device`() {
        // 圆表上手动选手机布局：用户就是要那套 320dp 的排版
        assertThat(
            LayoutThemes.resolve(LayoutThemes.PHONE, isWatch = true, isRound = true, widthDp = 206),
        ).isEqualTo(LayoutThemes.PHONE)
        // 方屏上手动选手表布局
        assertThat(
            LayoutThemes.resolve(LayoutThemes.WATCH, isWatch = false, isRound = false, widthDp = 400),
        ).isEqualTo(LayoutThemes.WATCH)
    }

    @Test
    fun `auto picks watch on a watch device`() {
        assertThat(
            LayoutThemes.resolve(LayoutThemes.AUTO, isWatch = true, isRound = false, widthDp = 400),
        ).isEqualTo(LayoutThemes.WATCH)
    }

    @Test
    fun `auto picks watch on a round screen even without the feature flag`() {
        // 有些 ROM 不报 FEATURE_WATCH，圆屏是唯一可靠信号
        assertThat(
            LayoutThemes.resolve(LayoutThemes.AUTO, isWatch = false, isRound = true, widthDp = 330),
        ).isEqualTo(LayoutThemes.WATCH)
    }

    @Test
    fun `auto picks watch on a narrow panel`() {
        // 320dp 的设计在 220dp 上要压到 0.69×，那已经不是「紧凑」而是「看不清」了
        assertThat(
            LayoutThemes.resolve(LayoutThemes.AUTO, isWatch = false, isRound = false, widthDp = 220),
        ).isEqualTo(LayoutThemes.WATCH)
    }

    @Test
    fun `auto picks phone on a big square screen`() {
        assertThat(
            LayoutThemes.resolve(LayoutThemes.AUTO, isWatch = false, isRound = false, widthDp = 320),
        ).isEqualTo(LayoutThemes.PHONE)
        assertThat(
            LayoutThemes.resolve(LayoutThemes.AUTO, isWatch = false, isRound = false, widthDp = 411),
        ).isEqualTo(LayoutThemes.PHONE)
    }

    @Test
    fun `unknown width does not accidentally pick watch`() {
        // 首帧拿不到宽度时是 0：不能因为 0 < 240 就判成手表
        assertThat(
            LayoutThemes.resolve(LayoutThemes.AUTO, isWatch = false, isRound = false, widthDp = 0),
        ).isEqualTo(LayoutThemes.PHONE)
    }

    @Test
    fun `all list is stable and contains the three ids`() {
        assertThat(LayoutThemes.all)
            .containsExactly(LayoutThemes.AUTO, LayoutThemes.WATCH, LayoutThemes.PHONE)
            .inOrder()
    }
}
