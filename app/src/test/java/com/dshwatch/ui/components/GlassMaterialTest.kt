package com.dshwatch.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 玻璃的「物理感」：高光描边 + 顶面光泽 + 磨砂颗粒。
 *
 * 这三笔都只在真机上看得出对不对，而它们又极易在重构里被「顺手简化」掉：
 *
 *  - 描边从 1dp 改回 0.6dp：1.5x 屏上只剩 0.9px，抗锯齿一稀释就看不见了；
 *  - 描边从对角线改回上下渐变：四个角一样亮，看着像「描了一圈边」而不是「受光」；
 *  - 颗粒忘了加 `if (frosted)`：清玻璃也被打毛，通透感自相矛盾；
 *  - Brush 从 remember 里挪进 drawBehind：每帧新建 Shader，滚动就掉帧。
 *
 * 所以钉在源码上，而不是靠肉眼回归。
 */
class GlassMaterialTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    @Test
    fun `高光描边是 1dp，上沿亮两侧暗`() {
        val body = code("ui/components/Glass.kt")
        // 0.6dp 在 1.5x 屏上不到 1px，被抗锯齿吃掉一半就是「有边但看不见」
        assertThat(body).contains("val width = 1.dp.toPx()")
        // 竖直渐变：任何宽高比下都是「顶边亮、侧边有厚度」
        assertThat(body).contains("val edgeBrush = remember(dark, elevated, frosted)")
        assertThat(body).contains("Brush.verticalGradient(")
        // 对角线在 350×60 的设置行上退化成「左亮右暗」，不是受光
        assertThat(body).doesNotContain("val edgeBrush = remember(dark, elevated, frosted) {\n        Brush.linearGradient(")
    }

    @Test
    fun `磨砂玻璃的边更亮，清玻璃保持克制`() {
        val body = code("ui/components/Glass.kt")
        // frosted 分支必须先于 dark/elevated 命中，否则磨砂的边拿不到高值
        assertThat(body).contains("frosted && dark -> 0.78f")
        assertThat(body).contains("frosted -> 0.86f")
    }

    @Test
    fun `顶面光泽只在上沿四分之一`() {
        val body = code("ui/components/Glass.kt")
        assertThat(body).contains("val glossBrush = remember(dark, solid, frosted)")
        // 只占顶部 26%，剩下全是透明 —— 整卡渐变会把玻璃画成「渐变卡片」
        assertThat(body).contains("0.26f to Color.White.copy(alpha = 0f)")
        // 实心按钮给得更重：不透明主色没有透光可依赖
        assertThat(body).contains("solid -> 0.18f")
    }

    @Test
    fun `颗粒只在磨砂时铺`() {
        val body = code("ui/components/Glass.kt")
        assertThat(body).contains("if (frosted) {")
        assertThat(body).contains("GlassGrain.brush")
        // 画在本体之上、描边之下
        val grain = body.indexOf("GlassGrain.brush")
        val edge = body.indexOf("drawEdge(corner, w, h, alpha, edgeBrush)")
        assertThat(grain).isLessThan(edge)
    }

    @Test
    fun `Brush 在组合期缓存，不在每帧构造`() {
        val body = code("ui/components/Glass.kt")
        // 三个 Brush 都挂在 remember 上，key 覆盖所有影响颜色的开关
        assertThat(body).contains("remember(dark, elevated, frosted)")
        assertThat(body).contains("remember(dark, solid, frosted)")
        // drawBehind 里不能出现 Brush 的构造函数
        val drawBlock = body.substringAfter(".drawBehind {").substringBefore("if (onClick != null)")
        assertThat(drawBlock).doesNotContain("Brush.linearGradient(")
        assertThat(drawBlock).doesNotContain("Brush.verticalGradient(")
        assertThat(drawBlock).doesNotContain("Brush.radialGradient(")
    }

    @Test
    fun `颗粒图固定种子且平铺`() {
        val body = code("ui/components/GlassGrain.kt")
        // 换种子 = 每次启动颗粒都不一样，用户会觉得界面在闪
        assertThat(body).contains("Random(20261127L)")
        assertThat(body).contains("TileMode.Repeated")
        // 颗粒量级：最大 12/255 ≈ 4.7%，再大人眼就读成噪点而不是磨砂
        assertThat(body).contains("const val MAX_ALPHA = 12")
        // 惰性：不进玻璃主题的用户一次都不建这张位图
        assertThat(body).contains("by lazy")
    }
}
