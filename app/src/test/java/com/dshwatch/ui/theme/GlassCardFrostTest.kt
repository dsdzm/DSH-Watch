package com.dshwatch.ui.theme

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 「卡片自己那块高斯模糊」＋两根调节滑块的接线。
 *
 * 用户的原话：**「卡片本身好像没有用高斯模糊，去掉背景模糊以后，卡片这些就好像透明了」**。
 *
 * 这不是参数没调好，是那条链路从来就不存在 —— 之前只有「把背景层整层糊掉」，
 * 卡片本身只是半透明。于是关掉背景模糊，卡片就退化成一块玻璃纸。
 * 现在卡片按自己的屏幕坐标，从一张**重度模糊**的背景副本里采一块画进纱底下
 * （见 Glass.kt 的 drawFrostSlice），所以：
 *
 *  · 背景清晰 + 卡片发毛 = 真毛玻璃的观感；
 *  · 两根滑块分别管「糊得多狠」与「盖得多实」，互不影响。
 *
 * 这些全是编译期发现不了的，只能钉在源码与换算公式上。
 */
class GlassCardFrostTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    // ---------------- 卡片自己糊 ----------------

    @Test
    fun `主题层为卡片准备一张重度模糊的背景副本`() {
        val theme = code("ui/theme/Theme.kt")
        // 专门的一张图，和背景层那张（backdrop）不是同一份：半径不同、读取宽度不同
        assertThat(theme).contains("val frostPhoto by produceState<ImageBitmap?>")
        assertThat(theme).contains("blurRadiusFraction = frostRadiusFraction(strength)")
        assertThat(theme).contains("readWidth = FROST_READ_WIDTH")
        // 只在「玻璃 + 磨砂」时解码：清玻璃要透出清晰背景，糊一张反而错
        assertThat(theme).contains("if (!glassFrost || customBackground.isBlank())")
        // 传给下面
        assertThat(theme).contains("LocalFrostPhoto provides frostPhoto")
        assertThat(theme).contains("LocalBackdropSize provides")
        assertThat(theme).contains("LocalBackdropDim provides")
    }

    @Test
    fun `卡片按自己的屏幕坐标采样，而不是整体糊一层`() {
        val body = code("ui/components/Glass.kt")
        // 取样：位置 + 根尺寸 + 遮罩
        assertThat(body).contains("drawFrostSlice(")
        assertThat(body).contains("positionInRoot()")
        // 用同一个铺满算法，否则卡片里的糊影会和周围照片错开
        assertThat(body).contains("coverRect(")
        // 对话框（elevated）不采样：它在另一个 Window 里，坐标对不上
        assertThat(body).contains("!elevated &&")
        // 低端档不采样
        assertThat(body).contains("perf.tier != DeviceTier.LOW")
    }

    @Test
    fun `取样层画在纱底下，不会盖住文字`() {
        val body = code("ui/components/Glass.kt")
        val slice = body.indexOf("drawFrostSlice(")
        val panel = body.indexOf("drawGlassPanel(")
        assertThat(slice).isGreaterThan(-1)
        assertThat(panel).isGreaterThan(-1)
        // drawBehind 的先后顺序就是绘制顺序：先糊影，再把面板画上去
        assertThat(slice).isLessThan(panel)
    }

    // ---------------- 两根滑块的换算 ----------------

    @Test
    fun `标准档必须落回上一版的观感`() {
        // 0.5 = 1.24.1 写死的 22dp / 5.3%。缺了这条，老用户一升级就会觉得「怎么变了」
        assertThat(glassBlurRadius(0.5f)).isEqualTo(22.dp)
        assertThat(blurRadiusFraction(0.5f)).isWithin(0.001f).of(BLUR_RADIUS_FRACTION)
    }

    @Test
    fun `模糊强度单调且不越界`() {
        var last = -1f
        for (i in 0..10) {
            val s = i / 10f
            val r = glassBlurRadius(s).value
            assertThat(r).isGreaterThan(last)
            last = r
        }
        // 拉满也不能糊到「整屏一个色块」：38dp 在 200dp 宽的表上已经是 19%
        assertThat(glassBlurRadius(1f).value).isAtMost(40f)
        assertThat(glassBlurRadius(0f).value).isAtLeast(4f)
    }

    @Test
    fun `卡片磨砂比背景层更狠，但不超过 RenderScript 的硬上限`() {
        // 磨砂的定义就是「比背景糊」——两个比例在同一强度下必须拉开
        for (i in 0..10) {
            val s = i / 10f
            assertThat(frostRadiusFraction(s)).isGreaterThan(blurRadiusFraction(s))
        }
        // ScriptIntrinsicBlur 半径 > 25 会抛异常（见 RsBlur），读取宽度 192
        assertThat(FROST_READ_WIDTH * frostRadiusFraction(1f)).isAtMost(25f)
        assertThat(BLUR_READ_WIDTH * blurRadiusFraction(1f)).isAtMost(25f)
        // 也不能小到看不出：至少是图宽的 5%
        assertThat(frostRadiusFraction(0f)).isAtLeast(0.04f)
    }

    // ---------------- 两根滑块的接线 ----------------

    @Test
    fun `设置页给出模糊强度与卡片压暗`() {
        val body = code("ui/screens/settings/SettingsGroupScreen.kt")
        assertThat(body).contains("blurStrength = v")
        assertThat(body).contains("cardDim = v")
        assertThat(body).contains("模糊强度")
        assertThat(body).contains("卡片压暗")
        // 只在玻璃 / 背景模糊真的生效时才占地方
        assertThat(body).contains("val blurActive =")
    }

    @Test
    fun `滑块值真的传进主题`() {
        val body = code("MainActivity.kt")
        assertThat(body).contains("blurStrength = settings.blurStrength")
        assertThat(body).contains("cardDim = settings.cardDim")
    }

    @Test
    fun `压暗档位在玻璃里真的被读`() {
        val body = code("ui/components/Glass.kt")
        assertThat(body).contains("val glassDim = LocalGlassDim.current")
        assertThat(body).contains("val k = 0.35f + 1.3f * dim")
        // 0.5 时系数必须是 1.0，否则标准档就不再是上一版的配比
        assertThat(0.35f + 1.3f * 0.5f).isWithin(0.0001f).of(1f)
    }

    // ---------------- 用户消息与思维链 ----------------

    @Test
    fun `用户消息也是玻璃`() {
        val body = code("ui/screens/chat/ChatScreen.kt")
        // 染色玻璃：玻璃主题下用户气泡不再是一块实色
        assertThat(body).contains("isUser && glass -> Color.Transparent")
        assertThat(body).contains("val bubbleTint")
        assertThat(body).contains("tintAlpha = 0.82f")
        // 染色玻璃上用 onPrimaryContainer：M3 保证过它压在 primaryContainer 上的对比度
        assertThat(body).contains("isUser && glass -> MaterialTheme.colorScheme.onPrimaryContainer")
    }

    @Test
    fun `思维链也是玻璃，但是薄一档`() {
        val body = code("ui/screens/chat/ChatScreen.kt")
        assertThat(body).contains("weight = 0.72f")
        // 圆角必须和工具卡一致，否则交替排列时会一段胖一段瘦
        assertThat(body).contains("val radius = if (com.dshwatch.ui.theme.isM3eUi) 20.dp else 12.dp")
    }
}
