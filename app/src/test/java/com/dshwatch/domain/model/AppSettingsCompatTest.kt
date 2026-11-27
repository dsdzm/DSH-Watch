package com.dshwatch.domain.model

import com.dshwatch.core.Jsonx
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 设置项的向后兼容。
 *
 * 每加一个字段，用户手机里那份 JSON 就少一行。`kotlinx.serialization` 在有默认值时
 * 会补上默认值 —— 但这件事**只能靠测试兜**：把某个新字段的默认值删掉、或者写成
 * 可空类型，编译照样过，老用户一升级就是「设置项全部变成 null / 全部重置」。
 *
 * 这里用一份「老版本」的 JSON（只有几个基础字段）验证：缺字段时落到默认值，
 * 而不是抛异常、也不是把整份设置丢掉。
 */
class AppSettingsCompatTest {

    @Test
    fun `老版本设置缺字段时用默认值`() {
        // 模拟 1.24.1 写下的设置：没有 glassBlur
        val old = """{"appearance":"glass","themePreset":"glass","uiStyle":"classic"}"""
        val s = Jsonx.lenient.decodeFromString(AppSettings.serializer(), old)

        assertThat(s.appearance).isEqualTo(Appearances.GLASS)
        assertThat(s.glassBlur).isFalse()
        // 背景模糊是后加的：缺字段时落默认值（开）——老版本开玻璃模糊就是整层糊，
        // 默认关掉反而会让升级后的观感突然变锐
        assertThat(s.backgroundBlur).isTrue()
        // 其它默认值也应当还在（缺字段不是"整份重置"）
        assertThat(s.fishEnabled).isFalse()
        assertThat(s.uiStyle).isEqualTo(UiStyles.CLASSIC)
    }

    @Test
    fun `高斯模糊开关能往返序列化`() {
        val on = AppSettings(glassBlur = true)
        val text = Jsonx.lenient.encodeToString(AppSettings.serializer(), on)
        val back = Jsonx.lenient.decodeFromString(AppSettings.serializer(), text)
        assertThat(back.glassBlur).isTrue()
        assertThat(back).isEqualTo(on)
    }

    @Test
    fun `背景模糊开关能往返序列化`() {
        val off = AppSettings(backgroundBlur = false)
        val text = Jsonx.lenient.encodeToString(AppSettings.serializer(), off)
        val back = Jsonx.lenient.decodeFromString(AppSettings.serializer(), text)
        assertThat(back.backgroundBlur).isFalse()
        assertThat(back).isEqualTo(off)
    }

    @Test
    fun `默认关闭`() {
        // 默认必须是关：真模糊只在 API 31+ 生效，作为默认值会让低版本用户
        // 看到「开关是开的、界面毫无变化」，反而像坏了
        assertThat(AppSettings().glassBlur).isFalse()
    }

    @Test
    fun `两根新滑块的老用户默认值落在标准档`() {
        // 0.5 = 上一版写死的配比。缺字段时落到 0.5 才不会「升级后卡片突然变透明」
        assertThat(AppSettings().blurStrength).isEqualTo(0.5f)
        assertThat(AppSettings().cardDim).isEqualTo(0.5f)

        val old = """{"appearance":"glass"}"""
        val s = Jsonx.lenient.decodeFromString(AppSettings.serializer(), old)
        assertThat(s.blurStrength).isEqualTo(0.5f)
        assertThat(s.cardDim).isEqualTo(0.5f)
    }

    @Test
    fun `滑块值能往返序列化`() {
        val s = AppSettings(blurStrength = 0.8f, cardDim = 0.2f)
        val text = Jsonx.lenient.encodeToString(AppSettings.serializer(), s)
        val back = Jsonx.lenient.decodeFromString(AppSettings.serializer(), text)
        assertThat(back.blurStrength).isEqualTo(0.8f)
        assertThat(back.cardDim).isEqualTo(0.2f)
        assertThat(back).isEqualTo(s)
    }
}
