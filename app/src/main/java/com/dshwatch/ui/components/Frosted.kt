package com.dshwatch.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.dshwatch.ui.theme.LocalGlass

/**
 * 老设备（API < 31）的背景「静态雾」，以及玻璃后端常量。
 *
 * ## 1.15.7：这个文件瘦了一大圈
 *
 * 原先这里有 `frostedBackground()` 和 `dialogBackdrop()`，是给 **Dialog 自己的
 * 遮罩层**画磨砂用的。那套做法有两个问题，一起暴露在圆形手表 + 玻璃主题上：
 *
 * 1. `Dialog` 在另一个 Window，糊不到背后那页 Activity —— 方向从一开始就错了。
 * 2. API 31+ 时 `dialogBackdrop()` 直接给遮罩根 Box 挂 `blur(26.dp)`，
 *    而卡片是这个 Box 的子树。`Modifier.blur` 会作用于**整棵子树**，
 *    于是标题、正文、按钮一起被糊掉 —— 用户看到的就是「只显示弹窗模糊的背景，
 *    没有内容」。200dp 宽的圆屏上 26dp 尤其致命。
 *
 * 现在弹窗窗口里**不再挂任何模糊**：背景模糊统一由 [GlassBackdropHost]
 * 在宿主页面层做（弹窗通过 `LocalBackdropDepth` 通知它）。Dialog 那一层只负责
 * 接点击和非玻璃主题的压黑，见 GlassDialog.kt。
 *
 * 所以这个文件只保留两样东西：
 *
 * - [MIN_SDK_REAL_BLUR]：真模糊可用的 API 门槛，Backdrop.kt / WindowBlur 要用。
 * - [drawFrostVeil]：API < 31 时 [GlassBackdropHost] 盖在**页面层**上的静态雾。
 *   它画在 Activity 的根层，不画在 Dialog 里，也就不会碰到弹窗内容。
 */

/** 真模糊的起始 API。低于它的设备走 [drawFrostVeil] 的静态近似 */
const val MIN_SDK_REAL_BLUR: Int = 31

/** 当前玻璃后端，诊断页用 */
@Composable
fun glassBackendName(): String {
    if (!LocalGlass.current) return "off"
    return if (Build.VERSION.SDK_INT >= MIN_SDK_REAL_BLUR) "backdrop-blur" else "backdrop-frost"
}

/**
 * 老设备（API 28~30）的静态磨砂：**降饱和 + 摊开亮部**，不压光、不改底色。
 *
 * 这两笔都不依赖具体背景色 —— 它们只是往画面上铺一层低饱和的雾，
 * 让细节退到后面去。所以可以直接画在「整个 Activity 的根层」上。
 *
 * 为什么不能用之前那版 `drawFrost`：它暗含了一个前提 —— **背后就是玻璃主题
 * 自己的那张底**（所以它按 primary/tertiary 重新摊了一遍色晕，还叠了 0.04 的黑）。
 * 一旦盖到别的页面上（比如设置页、OLED 底），它画的就不再是「糊」，
 * 而是「另一张底」，整屏会被洗成一片同色。
 */
fun DrawScope.drawFrostVeil(light: Boolean) {
    // 1) 降饱和：取背景的互补中性色。0.16 —— 再多就开始「换色相」，而不是「降对比」
    drawRect(
        color = (if (light) Color(0xFFB9C0D0) else Color(0xFF252C3D)).copy(alpha = 0.16f),
    )
    // 2) 摊开：两个大半径极淡的亮部，位置照抄背景色晕（左上 / 右下）。
    //    这一笔才是「糊」的观感来源 —— 亮部被摊薄，边缘就软了
    val w = size.width
    val h = size.height
    val r = size.minDimension
    val k = if (light) 1.4f else 1f
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.10f * k),
                0.55f to Color.White.copy(alpha = 0.03f * k),
                1f to Color.Transparent,
            ),
            center = Offset(w * 0.22f, h * 0.08f),
            radius = r * 1.6f,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.Black.copy(alpha = 0.06f),
                0.6f to Color.Black.copy(alpha = 0.02f),
                1f to Color.Transparent,
            ),
            center = Offset(w * 0.9f, h * 0.96f),
            radius = r * 1.4f,
        ),
    )
}
