package com.dshwatch

import com.google.common.truth.Truth.assertThat
import com.dshwatch.ui.compareVersion
import com.dshwatch.ui.shouldShowPythonNotice
import org.junit.Test

/**
 * 版本号比较。
 *
 * 这里最容易埋的雷是用字符串比大小：`"1.9" > "1.12"` 在字符串世界成立，
 * 在版本世界里反了。一旦搞错，从 1.9 升到 1.12 的用户就看不到
 * 「本地 Python 已移除」的说明，然后在 shell 里敲 python 一脸问号。
 */
class VersionNoticeTest {

    @Test
    fun `numeric comparison not string comparison`() {
        assertThat(compareVersion("1.9", "1.12")).isLessThan(0)
        assertThat(compareVersion("1.12", "1.12")).isEqualTo(0)
        assertThat(compareVersion("1.13", "1.12")).isGreaterThan(0)
        assertThat(compareVersion("2.0", "1.99")).isGreaterThan(0)
    }

    @Test
    fun `missing segments count as zero`() {
        assertThat(compareVersion("1", "1.0.0")).isEqualTo(0)
        assertThat(compareVersion("1.1", "1")).isGreaterThan(0)
    }

    @Test
    fun `unknown history shows the notice once`() {
        // 从更老的版本升上来：记录里没有版本号
        assertThat(shouldShowPythonNotice("", "1.13")).isTrue()
        assertThat(shouldShowPythonNotice("1.9", "1.13")).isTrue()
    }

    @Test
    fun `already on or past the change does not nag`() {
        assertThat(shouldShowPythonNotice("1.12", "1.13")).isFalse()
        assertThat(shouldShowPythonNotice("1.13", "1.13")).isFalse()
    }

    @Test
    fun `blank current version never shows`() {
        // 取不到 versionName 时不该弹一个用户无法关闭的卡片
        assertThat(shouldShowPythonNotice("", "")).isFalse()
    }
}
