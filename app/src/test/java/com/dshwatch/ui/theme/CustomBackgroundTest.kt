package com.dshwatch.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自定义背景：铺满算法 + 接线。
 *
 * 铺满（cover）这件事看着简单，错法却都很具体：
 *
 *  - 算成「完整显示」（contain）→ 上下或左右留出黑边，圆屏上就是两道弧形缺口；
 *  - 只放大不裁切、从左上角开始画 → 主体被挤到一边，脸只剩半张；
 *  - 宽高各自拉伸 → 人像被压扁。
 *
 * 这里用纯函数 [coverRect] 把「两边都不小于屏幕」和「多余部分均分」钉死。
 */
class CustomBackgroundTest {

    private val root = File("src/main/java/com/dshwatch")

    private fun src(path: String) = File(root, path).readText()

    @Test
    fun `横图铺满：宽度撑满，高度溢出并居中裁`() {
        val r = coverRect(imageWidth = 2000, imageHeight = 1000, canvasWidth = 412f, canvasHeight = 502f)!!
        // 高度是瓶颈：502 / 1000 = 0.502 才是最大的那个比例
        assertEquals(502, r.height)
        assertEquals(1004, r.width)
        // 左右各裁掉 (1004 - 412) / 2 = 296
        assertEquals(-296, r.left)
        assertEquals(0, r.top)
    }

    @Test
    fun `竖图铺满：高度撑满，宽度溢出并居中裁`() {
        val r = coverRect(imageWidth = 1000, imageHeight = 2000, canvasWidth = 412f, canvasHeight = 502f)!!
        assertEquals(412, r.width)
        assertEquals(824, r.height)
        assertEquals(0, r.left)
        assertEquals(-161, r.top)
    }

    @Test
    fun `任何比例都不会露出底色`() {
        // 这一条是「留黑边」的通用防线：不针对某一张图，而是穷举比例
        val sizes = listOf(
            1 to 1, 16 to 9, 9 to 16, 3 to 4, 4 to 3, 100 to 1, 1 to 100,
        )
        sizes.forEach { (iw, ih) ->
            val r = coverRect(iw * 10, ih * 10, 412f, 502f)!!
            assertTrue(
                "${iw}:${ih} 的图铺不满：${r.width}x${r.height} 应 ≥ 412x502",
                r.width >= 412 && r.height >= 502,
            )
            // 溢出的部分必须均分 —— 只往一边裁会让主体跑出画面
            assertTrue(
                "${iw}:${ih} 的图没有居中裁：left=${r.left} top=${r.top}",
                r.left <= 0 && r.top <= 0,
            )
        }
    }

    @Test
    fun `比例刚好一致时不多裁一个像素`() {
        val r = coverRect(824, 1004, 412f, 502f)!!
        assertEquals(412, r.width)
        assertEquals(502, r.height)
        assertEquals(0, r.left)
        assertEquals(0, r.top)
    }

    @Test
    fun `尺寸非法时返回 null 而不是画出一个零宽矩形`() {
        // 解码失败时 Bitmap 尺寸会是 0；直接拿去算除零会得到 NaN，再转 Int 就是 0，
        // 最后表现成「背景一片黑」而不是「没有背景」
        assertNull(coverRect(0, 100, 412f, 502f))
        assertNull(coverRect(100, 0, 412f, 502f))
        assertNull(coverRect(100, 100, 0f, 502f))
        assertNull(coverRect(100, 100, 412f, 0f))
    }

    @Test
    fun `有背景图时把页面底色让出来`() {
        /*
         * 各个页面都会铺一层 `colorScheme.background` 当纸。这张纸不透明的话，
         * 画在它下面的照片一点都露不出来 —— 用户看到的是「设了背景但什么都没变」。
         */
        val theme = src("ui/theme/Theme.kt")
        assertTrue(
            "Theme.kt 里应当有「有背景图时把 background 换成透明」的逻辑",
            theme.contains("baseScheme.copy(background = Color.Transparent)"),
        )
        assertTrue(
            "DshWatchTheme 必须真的去解码背景图",
            theme.contains("CustomBackground.load("),
        )
        assertTrue(
            "背景图要画在主题这一层（AppRoot 外面），不能只画在某一页",
            theme.contains("drawBackdropPhoto("),
        )
        assertTrue(
            "MainActivity 要把设置里的背景传进主题",
            src("MainActivity.kt").contains("customBackground = settings.customBackground"),
        )
    }

    @Test
    fun `默认没有背景图，且遮罩不能盖死照片`() {
        val settings = src("domain/model/Provider.kt")
        assertTrue(
            "customBackground 默认必须为空 —— 默认带一张图会让升级用户莫名其妙",
            settings.contains("val customBackground: String = \"\""),
        )
        // 1.0 的遮罩等于把照片完全盖掉，那还不如不放
        assertTrue(
            "遮罩上限必须小于 1",
            MAX_BACKDROP_DIM < 1f,
        )
    }
}
