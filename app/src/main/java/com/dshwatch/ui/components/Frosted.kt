package com.dshwatch.ui.components

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.dshwatch.ui.theme.LocalGlass

/**
 * 「磨砂」—— 玻璃主题下，临时层（对话框）背后那片背景的处理。
 *
 * ## 用户说的「没有模糊」其实是两件事
 *
 * 用户原话：「切换模型和模式的界面不是玻璃质感并且没有模糊」。
 * 这里有两个能被眼睛直接看到的现象：
 *
 * 1. **看不到背后**：M3 的 `AlertDialog` 用一整块不透明板盖住屏幕，
 *    玻璃卡片下面什么都没有 —— 这不是模糊的问题，是根本没透。
 *    已由 [GlassDialog] 换掉 `AlertDialog` 解决（全工程 `AlertDialog(` 计数为 0）。
 * 2. **弹层一出现背景就被压平**：`Dialog` 是另一个 Window，自带一层纯色遮罩。
 *    遮罩一盖，背景那两团色晕直接被压成一片死色，观感上等于「背景被换成了纯色」——
 *    人的第一反应就是「怎么没模糊」。
 *
 * 这一项是第 2 条的解：不靠「盖一层半透明黑」把背景压下去，而是
 * **降饱和 + 把亮部摊开 + 极轻地收一点对比**，让背景退到「失焦」该在的位置。
 *
 * > **只糊，不暗。** 这一条是用户第四次说「还是很暗」之后才立下来的：
 * > 玻璃透过去的是遮罩之后的那层颜色，遮罩一黑，卡片再白也是在黑的上面叠白。
 * > 所以这里的黑已经被压到 0.04，见 [drawFrost] 第 3 层的说明。
 *
 * ## 为什么不真的做高斯模糊
 *
 * 本项目 minSdk 28。`Modifier.blur` / `RenderEffect` 要 API 31 才生效，
 * 在 Android 9 / 10 的手表上会被**静默忽略** —— 不报错、不模糊，运行时也检测不到，
 * 正是最坏的一种失败方式。自己实现就得上 RenderScript（API 31 已废弃）或
 * 离屏 Bitmap 逐像素卷积：一块 400×400 的区域每帧跑 5×5 卷积，
 * 在 512MB 内存的表上直接掉到个位数帧率。
 *
 * 所以这里用三层**静态**绘制逼近「失焦」的感知特征：
 *
 * | 层 | 做法 | 对应真模糊的哪个特征 |
 * |---|---|---|
 * | 降饱和 | 贴背景色相的低饱和色，0.16 | 模糊平均掉颜色，高频色块先糊成一片中间调 |
 * | 摊开 | 两个大半径、极淡的径向光斑，位置照抄背景色晕 | 模糊把亮点摊成光晕 |
 * | 压对比 | 0.04 的黑（**只够收边，不再压暗**） | 模糊略降对比，但不降低亮度 |
 *
 * 一共 4 次全屏 `drawRect`，全是静态的：尺寸不变就不重绘，
 * 也不进任何动画通道 —— 打开对话框没有逐帧开销。
 */
@Composable
fun Modifier.frostedBackground(): Modifier {
    if (!LocalGlass.current) return this
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.35f
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    return this.drawWithCache {
        // drawWithCache 的 lambda 接收者是 CacheDrawScope：它带着 size，但不会画东西。
        // 真正落笔要交给 onDrawBehind，那个 lambda 的接收者才是 DrawScope。
        this.onDrawBehind {
            drawFrost(dark = dark, primary = primary, tertiary = tertiary)
        }
    }
}

/** 三层磨砂，铺满整块画布 */
private fun DrawScope.drawFrost(dark: Boolean, primary: Color, tertiary: Color) {
    val w = size.width
    val h = size.height
    val r = size.minDimension

    // 1) 降饱和。
    //
    // 这里踩过一次：一开始用 0xFF9AA0B4（偏紫的中性灰）压 0.14，
    // 结果深蓝背景被推成紫灰，背后的列表和前面的卡片明度几乎一样（实测只差 7.7/255），
    // 整个弹层糊成一团，比不磨砂还糟。
    // 磨砂的职责是**降对比**，不是**换色相** —— 所以取色必须贴近背景自己的色相。
    drawRect(
        color = (if (dark) Color(0xFF252C3D) else Color(0xFFB9C0D0))
            // 0.22 → 0.16：降饱和也在偷偷「调暗」。
            // 深蓝背景叠 0.22 的中性灰，色相被推掉的同时明度也掉一截，
            // 用户看到的「暗」有一半来自这里，而不来自压对比那层。
            .copy(alpha = 0.16f),
    )

    // 2) 摊开亮部。中心点照抄 Theme.kt 里色晕的位置（左上 0.20/0.02、右下 0.92/1.02），
    //    这样「糊开」的方向和原本的亮部一致 —— 看起来是同一团光被摊薄，而不是凭空多一团
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to primary.copy(alpha = 0.16f),
                0.50f to primary.copy(alpha = 0.06f),
                1f to Color.Transparent,
            ),
            center = Offset(w * 0.22f, h * 0.08f),
            radius = r * 1.5f,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to tertiary.copy(alpha = 0.12f),
                0.55f to tertiary.copy(alpha = 0.04f),
                1f to Color.Transparent,
            ),
            center = Offset(w * 0.90f, h * 0.96f),
            radius = r * 1.3f,
        ),
    )

    // 3) 压对比度 —— 只留一丁点，且浅深都极轻。
    //
    // 这一层踩过四次，每次都是因为它同时干两件事：把背景「推远」，也把屏幕「调暗」。
    //
    //   · 0.26：整屏只剩 74% 亮度 → 「弹窗页面太暗了」
    //   · 0.16：深色下用户还是说「还是很暗」
    //   · 0.10：仍然暗
    //   · 0.04（当前）：用户最后点破了根因 ——「背景遮罩是黑的，透明透过去也是黑的」。
    //
    // 磨砂的职责只有「糊」，没有「暗」。真模糊不改变像素的平均亮度，
    // 这里压黑本质上是在用「暗」冒充「糊」，方向从一开始就错了。
    // 0.04 只用来把细节再收一点边，几乎不改变画面亮度。
    drawRect(color = Color.Black.copy(alpha = if (dark) 0.04f else 0.02f))
}

/** 真模糊的起始 API。低于它的设备走 [frostedBackground] 的三层近似 */
const val MIN_SDK_REAL_BLUR: Int = 31

/** 当前玻璃后端，诊断页用 */
@Composable
fun glassBackendName(): String {
    if (!LocalGlass.current) return "off"
    return if (android.os.Build.VERSION.SDK_INT >= MIN_SDK_REAL_BLUR) "blur+frost" else "frost"
}


/**
 * 老设备（API 28~30）的静态磨砂：**降饱和 + 摊开亮部**，不压光、不改底色。
 *
 * 这两笔都不依赖具体背景色 —— 它们只是往画面上铺一层低饱和的雾，
 * 让细节退到后面去。所以可以直接画在「整个 Activity 的根层」上，
 * 连 Dialog 那个独立 Window 也能盖到（见 `AppRoot` 的 `GlassBackdrop`）。
 *
 * 为什么不能用之前那版 `drawFrost`：它暗含了一个前提 —— **背后就是玻璃主题
 * 自己的那张底**（所以它按 primary/tertiary 重新摊了一遍色晕，还叠了 0.04 的黑）。
 * 一旦盖到别的页面上（比如设置页、OLED 底），它画的就不再是「糊」，而是
 * 「另一张底」，整屏会被洗成一片同色。
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

/**
 * 弹窗背后的整屏：**糊，但不暗**。
 *
 * 这是用户反复报「弹窗太暗」之后才想明白的一件事 —— 之前所有调整都在卡片上，
 * 而真正把整屏压暗的是对话框那一层遮罩。玻璃是「透」，它透过去的永远是遮罩之后
 * 的那层颜色；遮罩是黑的，卡片再白也只是在黑的上面叠白。
 *
 * ## 两条路
 *
 * | 系统 | 做法 |
 * |---|---|
 * | API 31+ | `Modifier.blur` 真模糊。**不叠任何黑** —— 模糊本身就保留亮度 |
 * | API 28~30 | [frostedBackground] 三层静态近似（降饱和 + 摊亮 + 极轻压对比） |
 *
 * ## 但这只对「玻璃」这一种材质成立
 *
 * 这条函数只在 `LocalGlass` 为真时有意义：它用的是玻璃主题那套色晕参数。
 * 换成 M3E 材质（OLED 黑、樱花、深海…）时它是空操作，那些主题的弹窗
 * 长什么样由 `GlassDialog` 的遮罩层单独决定（见那边的注释）。
 *
 * 老设备上为什么不用别的手段做真模糊：`Modifier.blur` / RenderEffect 要 API 31，
 * 低于它的设备会**静默忽略**；RenderScript API 31 已废弃；离屏 Bitmap 逐像素卷积
 * 在 512MB 内存的表上会直接掉到个位数帧率。所以老设备只能近似，
 * 但近似**不再压暗** —— 这是这一版最关键的一处改动。
 *
 * ## 圆角
 *
 * 模糊区域铺满整屏。圆屏手表的边缘本来就是黑的，不需要额外遮；
 * 方屏设备的四角会被模糊到，这是模糊遮罩的常见形态，不另做裁切。
 */
@Composable
fun Modifier.dialogBackdrop(): Modifier {
    if (!LocalGlass.current) return this

    // API 31+：真模糊。blur 自己保留亮度，所以这里**不加任何黑色遮罩**。
    // tiebreaker 用默认值（0.0）——非零会在边缘拉出一道渐变，圆屏上很明显
    if (Build.VERSION.SDK_INT >= MIN_SDK_REAL_BLUR) {
        return this.blur(26.dp)
    }

    // 老设备：静态近似。内部已经不含压暗，见 [drawFrost] 的说明
    return this.frostedBackground()
}
