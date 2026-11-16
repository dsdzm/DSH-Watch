package com.dshwatch.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 设备类型的解析。
 *
 * 这一层看起来只有三个字符串，但它决定了**整个 App 用哪套界面** ——
 * 一旦 `resolve` 把「手机」判成「手表」，用户在手机上看到的就是圆屏布局，
 * 而且他自己很难描述这件事（他只会说「界面是坏的」）。
 */
class DeviceKindsTest {

    @Test
    fun `空串与未知值都回落到自动`() {
        assertThat(DeviceKinds.of("")).isEqualTo(DeviceKinds.AUTO)
        assertThat(DeviceKinds.of("tablet")).isEqualTo(DeviceKinds.AUTO)
        assertThat(DeviceKinds.of("PHONE")).isEqualTo(DeviceKinds.AUTO) // 大小写不做兼容，宁可回落
    }

    @Test
    fun `自动按检测结果走`() {
        assertThat(DeviceKinds.resolve(DeviceKinds.AUTO, isWatchUi = true)).isEqualTo(DeviceKinds.WATCH)
        assertThat(DeviceKinds.resolve(DeviceKinds.AUTO, isWatchUi = false)).isEqualTo(DeviceKinds.PHONE)
    }

    @Test
    fun `显式选择压过检测`() {
        // 方形表、平板、以及「手表上想看手机界面」的人都走这条
        assertThat(DeviceKinds.resolve(DeviceKinds.PHONE, isWatchUi = true)).isEqualTo(DeviceKinds.PHONE)
        assertThat(DeviceKinds.resolve(DeviceKinds.WATCH, isWatchUi = false)).isEqualTo(DeviceKinds.WATCH)
    }

    @Test
    fun `resolve 不会返回自动`() {
        for (choice in listOf(DeviceKinds.AUTO, DeviceKinds.WATCH, DeviceKinds.PHONE, "", "x")) {
            for (watch in listOf(true, false)) {
                val out = DeviceKinds.resolve(choice, watch)
                assertThat(out).isAnyOf(DeviceKinds.WATCH, DeviceKinds.PHONE)
            }
        }
    }

    @Test
    fun `isPhone 与 resolve 一致`() {
        assertThat(DeviceKinds.isPhone(DeviceKinds.AUTO, isWatchUi = false)).isTrue()
        assertThat(DeviceKinds.isPhone(DeviceKinds.AUTO, isWatchUi = true)).isFalse()
        assertThat(DeviceKinds.isPhone(DeviceKinds.PHONE, isWatchUi = true)).isTrue()
    }

    /**
     * 手表设置里必须有出口。
     *
     * 1.23.9 只在手机版设置页与引导页放了这个开关，于是已经在用手表界面的用户
     * **永远切不到手机布局** —— 引导页走过了，设置里没有，只剩清数据重装。
     * 这条测试盯的是「出口存在」，不是「选得对」。
     */
    @Test
    fun `手表设置页能切设备类型`() {
        val file = java.io.File(
            "src/main/java/com/dshwatch/ui/screens/settings/SettingsGroupScreen.kt"
        )
        assertThat(file.isFile).isTrue()
        val body = file.readText()
        assertThat(body).contains("设备类型")
        assertThat(body).contains("DeviceKinds.PHONE")
        assertThat(body).contains("DeviceKinds.WATCH")
    }
}
