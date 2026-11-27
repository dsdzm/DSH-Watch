package com.dshwatch.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 模糊这条链路「一定生效」的接线。
 *
 * ## 为什么这个测试的关注点是「没有走什么」
 *
 * 这个功能连着两个版本「开关打开、画面不动」，日志里却一切正常 ——
 * 因为两条曾经的实现路径都会**安静地失败**：
 *
 * | 路径 | 失败方式 |
 * |---|---|
 * | `Modifier.blur` | API < 31 是空操作；31+ 上部分手表 ROM 的合成器不支持 `RenderEffect`，同样不报错 |
 * | `ScriptIntrinsicBlur` | 精简固件里 `RenderScript.create()` 直接抛，被 catch 之后退回**原图** |
 *
 * 两者共同点是「失败 = 原图」。所以这里钉的是：**不许再用它们**，
 * 以及换算出来的半径必须落在 [SoftBlur] 真正会处理的区间里。
 */
class BlurPipelineTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    @Test
    fun `主题与绘制层都不再依赖 RenderEffect`() {
        // 背景层曾经在这里挂 blur，是「开了没变化」的第一嫌疑人
        assertThat(code("ui/theme/Theme.kt")).doesNotContain("Modifier.blur(")
        // RenderScript 整套已经删除，不能有残留引用（那会重新引入静默失败）
        val background = code("ui/theme/CustomBackground.kt")
        assertThat(background).doesNotContain("RsBlur")
        assertThat(background).doesNotContain("RenderScript")
        assertThat(background).contains("SoftBlur.gaussian(bitmap, radius)")
    }

    @Test
    fun `糊失败不再退回原图，而是返回新位图`() {
        val body = code("ui/theme/CustomBackground.kt")
        // 只有「半径 <= 0」（= 用户关了模糊）才返回未处理的图；别的情况下必须糊过
        assertThat(body).contains("if (blurRadiusFraction <= 0f) return@withContext bitmap.asImageBitmap()")
        // 中间位图要回收：512×512×4 在 512MB 的表上不是小数
        assertThat(body).contains("if (blurred !== bitmap) bitmap.recycle()")
    }

    @Test
    fun `半径按图宽换算，落在 SoftBlur 真会处理的区间`() {
        /*
         * 半径 < 1 时 SoftBlur 直接原样返回 —— 那正是「滑块拉到 0 但开关开着」
         * 时最容易被误认为「功能坏了」的状态。所以下限必须够大。
         */
        for (i in 0..10) {
            val s = i / 10f
            assertThat(BG_BLUR_READ_WIDTH * bgRadiusFraction(s)).isGreaterThan(1f)
            assertThat(FROST_READ_WIDTH * glassRadiusFraction(s)).isGreaterThan(1f)
        }
        // 拉满也不能超过短边的 1/3（超过就成一块纯色，SoftBlur 会夹住）
        assertThat(glassRadiusFraction(1f)).isLessThan(0.34f)
        assertThat(bgRadiusFraction(1f)).isLessThan(0.34f)
    }

    @Test
    fun `读取宽度够大，糊完放大不会有块状边缘`() {
        // 120px 的"虚化读法"糊完放大是马赛克，不是高斯
        assertThat(BG_BLUR_READ_WIDTH).isAtLeast(320)
        assertThat(FROST_READ_WIDTH).isAtLeast(256)
        // 但也不能毫无节制：两张常驻图加起来要留在 4MB 以内
        assertThat(BG_BLUR_READ_WIDTH).isAtMost(512)
        assertThat(FROST_READ_WIDTH).isAtMost(512)
    }

    @Test
    fun `设置页三根滑块各自绑一个字段`() {
        val body = code("ui/screens/settings/SettingsGroupScreen.kt")
        assertThat(body).contains("blurStrength = v")
        assertThat(body).contains("glassBlurStrength = v")
        assertThat(body).contains("cardDim = v")
        // 两根模糊滑块必须能同时出现：用户要的是「背景虚一点、卡片毛一点」
        assertThat(body).contains("背景模糊强度")
        assertThat(body).contains("卡片模糊强度")
        // 有兼容路径之后，不该再用「不可用」挡掉
        assertThat(body).doesNotContain("需要 Android 12 及以上系统")
    }

    @Test
    fun `开关与滑块值真的传进主题`() {
        val body = code("MainActivity.kt")
        assertThat(body).contains("glassBlur = settings.glassBlur")
        assertThat(body).contains("backgroundBlur = settings.backgroundBlur")
        assertThat(body).contains("blurStrength = settings.blurStrength")
        assertThat(body).contains("glassBlurStrength = settings.glassBlurStrength")
        assertThat(body).contains("cardDim = settings.cardDim")
    }
}
