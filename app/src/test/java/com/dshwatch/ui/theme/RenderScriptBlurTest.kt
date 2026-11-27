package com.dshwatch.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 低版本（API 26~30）的高斯模糊兼容路径。
 *
 * RenderScript 本身在这台机器上跑不起来（单元测试没有 Android 运行时），
 * 能钉住的只有**接线与降级规则**，而这恰好是最容易错的部分：
 *
 *  - 半径超过 25：`ScriptIntrinsicBlur.setRadius` 直接抛异常，而它跑在
 *    「画背景」这条路上 —— 抛出去就是整个界面白屏；
 *  - 忘记把位图转 ARGB_8888：JPEG 解码在部分机型给 RGB_565，
 *    喂给 U8_4 是未定义行为，可能花屏而不是报错；
 *  - 上下文不缓存：每次进设置页重开一次，冷启动多几十毫秒；
 *  - 低版本仍然走 `Modifier.blur`：那是空操作，用户开了开关看不到任何变化 ——
 *    正是这个功能最初被限制在 Android 12 的原因。
 */
class RenderScriptBlurTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    @Test
    fun `半径不超过内建核的硬上限`() {
        val body = code("ui/theme/RsBlur.kt")
        assertThat(body).contains("ScriptIntrinsicBlur.create(")
        assertThat(body).contains("const val MAX_RADIUS = 25f")
        assertThat(body).contains("coerceIn(0.1f, MAX_RADIUS)")
    }

    @Test
    fun `模糊参数换算不会越界`() {
        /*
         * 半径 = 读取宽度 × 比例。两个常量任何一个往上调，都可能越过 25 ——
         * 那时 ScriptIntrinsicBlur 会抛 IllegalArgumentException。
         * 这里按实际公式算一遍，而不是各看各的。
         */
        val radius = BLUR_READ_WIDTH * BLUR_RADIUS_FRACTION
        assertThat(radius).isLessThan(25f)
        // 也不能小到没效果：等效于屏宽 5% 上下才是「看不出细节」
        assertThat(radius).isGreaterThan(8f)
        // 256 已经够糊，再大只是白占内存（ARGB_8888 每 MB 一档）
        assertThat(BLUR_READ_WIDTH).isAtMost(384)
    }

    @Test
    fun `失败只降级不抛异常`() {
        val body = code("ui/theme/RsBlur.kt")
        // 驱动缺失的 ROM 上 create 会抛 RSRuntimeException，甚至 UnsatisfiedLinkError
        assertThat(body).contains("runCatching {")
        assertThat(body).contains(".getOrNull()")
        // 返回 null 由调用方决定怎么办，别在这里抛
        assertThat(body).doesNotContain("throw ")
    }

    @Test
    fun `位图先转 ARGB_8888`() {
        val body = code("ui/theme/RsBlur.kt")
        assertThat(body).contains("Bitmap.Config.ARGB_8888")
        // 输入不是 8888 时复制一份，且用完回收那份复制品（不能回收调用方的图）
        assertThat(body).contains("if (input !== src) input.recycle()")
        // Allocation 必须显式释放，否则每次换背景漏一块 native 内存
        assertThat(body).contains("inAlloc.destroy()")
        assertThat(body).contains("outAlloc.destroy()")
    }

    @Test
    fun `上下文复用且不持有 Activity`() {
        val body = code("ui/theme/RsBlur.kt")
        // 创建一次 10~40ms，比模糊本身还贵
        assertThat(body).contains("@Volatile")
        assertThat(body).contains("synchronized(lock)")
        // 传 Activity 就是一次泄漏
        assertThat(body).contains("context.applicationContext")
    }

    @Test
    fun `低版本走离线模糊，31 以上才用 Modifier_blur`() {
        val theme = code("ui/theme/Theme.kt")
        // 两条路径按版本分，实时那条有独立常量
        assertThat(theme).contains("val liveBlur = blurBackdrop && Build.VERSION.SDK_INT >= MIN_SDK_LIVE_BLUR")
        assertThat(theme).contains("val photoBlur = blurBackdrop && !liveBlur")
        // Modifier.blur 只在 liveBlur 分支里挂
        assertThat(theme).contains("if (liveBlur) {")
        // 低版本把半径交给解码阶段（半径随「模糊强度」滑块走，见 GlassCardFrostTest）
        assertThat(theme).contains("blurRadiusFraction = if (photoBlur) blurRadiusFraction(strength) else 0f")
        // 开关变化要重新解码（糊过的像素已经不在手里，重画没用）
        assertThat(theme).contains("photoBlur,")
    }

    @Test
    fun `解码阶段糊图，糊失败还有原图`() {
        val body = code("ui/theme/CustomBackground.kt")
        // 要糊时读得比虚化路径更细：120px 糊完只剩色块
        assertThat(body).contains("blurRadiusFraction > 0f -> BLUR_READ_WIDTH")
        assertThat(body).contains("RsBlur.blur(context, bitmap, radius)")
        // 失败路径：返回未模糊的那张，而不是空白
        assertThat(body).contains("bitmap.asImageBitmap()\n    }")
        // 糊成功要回收中间位图，256KB×2 在 512MB 的表上不是小数
        assertThat(body).contains("blurred ->")
        assertThat(body).contains("bitmap.recycle()")
    }

    @Test
    fun `设置页在低版本也给开关`() {
        val body = code("ui/screens/settings/SettingsGroupScreen.kt")
        // 有兼容路径之后，不该再用「不可用」挡掉
        assertThat(body).doesNotContain("需要 Android 12 及以上系统")
        // 开关还在，只是文案不再按版本分叉
        assertThat(body).contains("backgroundBlur = v")
        assertThat(body).contains("磨砂玻璃")
    }
}
