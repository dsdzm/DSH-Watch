package com.dshwatch.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.DeviceProfileDefaults
import com.dshwatch.domain.PerfProfile
import com.dshwatch.domain.PerfProfiles
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.ui.i18n.AppLanguage
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.i18n.stringsFor

/** 当前性能档，供组件决定是否播放动画 / 渲染多少内容 */
val LocalPerf = staticCompositionLocalOf { PerfProfiles.of(PerfMode.AUTO, com.dshwatch.domain.DeviceTier.MID) }

/**
 * 是否启用「质感玻璃」视觉。
 *
 * 玻璃不是一套独立的组件，而是**同一批组件换一种绘制方式**：
 * [com.dshwatch.ui.components.glassSurface] 在 true 时走多层渐变自绘，
 * 在 false 时退化成一句 `background(color)`。所以：
 *  - 关掉它零成本：没有额外 draw、没有 pointer 追踪、没有动画；
 *  - 打开它整机的胶囊、卡片、按钮一起变，不用逐个页面改。
 *
 * 为什么不是真的高斯模糊：本项目 minSdk 28，而 `Modifier.blur` / RenderEffect
 * 要 API 31 才生效，在 Android 9 的手表上会被静默忽略。RenderScript 早已废弃、
 * 且逐帧模糊一块 200×120dp 的区域在 512MB 内存的表上会直接掉帧。
 * 所以这里画的是「模糊的观感」——多层低对比渐变 + 边缘光，GPU 填充成本约等于
 * 几次纯色圆角矩形，比真模糊低一个数量级。
 */
val LocalGlass = staticCompositionLocalOf { false }

/**
 * 设备画像（屏幕尺寸、圆角安全边距等）。
 * 顶栏/底栏靠它做内收，避免内容被屏幕的物理 R 角切掉。
 */
val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfileDefaults.fallback }

/**
 * 手表的尺寸常量。
 * 目标屏宽按 200dp 设计（1.4~1.8 寸方表常见换算值），
 * 所有点击区域 ≥ 40dp，兼顾手指而不是笔尖。
 */
/**
 * 手表尺寸常量。
 * 目标屏宽按 200~235dp 设计（410×502 px 在 2x 密度下约 205×251dp）。
 * 注意 [cornerSafeHorizontal] 不是一个固定值，它由 [DeviceProfile] 动态给出。
 */
object WatchDimens {
    val screenPadding: Dp = 8.dp
    val gapXs: Dp = 3.dp
    val gapS: Dp = 5.dp
    val gapM: Dp = 8.dp
    val gapL: Dp = 12.dp

    /*
     * v1.13 整体放大一档。
     *
     * 起因是用户反馈「按键、文字都太小，不适合手表操作」。原来 34dp 的图标按钮
     * 和 40dp 的行高，在 410×502 的屏上大致相当于手机上的 32dp —— 手指按下去
     * 要瞄，转表冠时焦点框也几乎看不见。
     *
     * 尺寸按 Wear OS Material 3 的建议值对齐（按钮 48dp、列表项 48dp、图标 24dp）。
     * 代价是每屏能放的内容少了一行左右，对"看一眼就走"的手表场景反而更合适。
     */
    val topBarHeight: Dp = 48.dp
    val bottomBarHeight: Dp = 56.dp

    /** Material 3 建议的最小可点区域。所有可点元素都不低于它 */
    val touchMin: Dp = 48.dp
    val iconButton: Dp = 44.dp
    /** 主按钮高度：够大、但仍能在圆屏底栏里留出左右切角空间 */
    val buttonHeight: Dp = 48.dp

    // 圆角整体调大一档：手表屏幕小、信息密度高，圆一点更松弛，也和大 R 角屏幕更协调
    val cardRadius: Dp = 20.dp
    val bubbleRadius: Dp = 16.dp
    val chipHeight: Dp = 32.dp

    val hairline: Dp = 1.dp

    /**
     * 顶栏/底栏的纵向内边距。
     * 这一项是解决 R 角切内容的关键：几何上，内容离屏幕顶边越远，
     * 需要的横向内收就越小（见 DeviceProfile 的推导）。
     */
    val barVerticalPadding: Dp = 6.dp
}

@Composable
fun DshWatchTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = true,
    themePreset: String = "oled",
    accentColor: String = "blue",
    /**
     * 外观风格（材质）：[com.dshwatch.domain.model.Appearances.M3E] 或 `GLASS`。
     *
     * 以前「玻璃」是 `themePreset = "glass"`，和颜色预设混在一个维度里 ——
     * 那样既选不出「深海 + 玻璃」，也会让「玻璃」看起来像一种配色。
     */
    appearance: String = com.dshwatch.domain.model.Appearances.M3E,
    fontScale: Float = 1.0f,
    appLanguage: String = AppLanguage.SYSTEM,
    perf: PerfProfile = PerfProfiles.of(PerfMode.AUTO, com.dshwatch.domain.DeviceTier.MID),
    profile: DeviceProfile = DeviceProfileDefaults.fallback,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    // 主题预设 + 强调色：两者都变时才重算色板。
    // 动态取色优先级最高 —— 它本来就是「跟着壁纸走」，用户开了就该由系统决定。
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // **玻璃不改底色。**
    //
    // 这里曾经强制套了一层 OLED 近黑底，理由是「玻璃要有不透明的 surface 兜底」——
    // 用户一句话就否了：「玻璃主题的背景怎么换了？不要换，不然纯白色背景无法体现出玻璃的透明」。
    //
    // 他说得对：玻璃是「透」，透出来的就是底下这张纸的颜色。纸被换成黑的，
    // 半透明的白叠上去只剩灰，透明感无从谈起。浅色底上的玻璃才是真玻璃。
    // 所以色板照用户选的预设走，玻璃只决定「卡片怎么画」。
    val colorScheme = remember(dark, useDynamic, themePreset, accentColor) {
        when {
            useDynamic && dark -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> ThemePresets.scheme(themePreset, accentColor, dark)
        }
    }

    // 玻璃背景需要一块**可透的底**：预设里 bg 若是透明（玻璃预设的默认做法，
    // 让位给最底层的色晕），就得在这里补一块不透明的 surface；
    // 若是实色（OLED、樱花…），直接用它自己的背景色，一像素都不改。
    val glassBase = colorScheme.background

    // 外观风格与色板分层之后，「是不是玻璃」只由 appearance 决定，
    // 预设只管颜色 —— 于是「玻璃 + 樱花」「玻璃 + 深海」都是合法组合。
    //
    // 动态取色例外：它整块替换色板，色晕的主副色失去依据，
    // 玻璃会变成「一块没有底色的灰玻璃」，所以此时强制退回 M3E。
    val glass = com.dshwatch.domain.model.Appearances.isGlass(appearance) && !useDynamic

    // 浅色底判定要在 drawBehind 之外先取好：DrawScope 里读不到 composition
    val lightBase = remember(colorScheme) { colorScheme.background.luminance() > 0.35f }

    val baseDensity = LocalDensity.current
    // 只缩放字号，不缩放 dp：dp 保持物理一致，避免按钮忽大忽小
    val scaledDensity = remember(baseDensity, fontScale) {
        Density(density = baseDensity.density, fontScale = baseDensity.fontScale * fontScale)
    }

    // 语言要跟着「设置值」和「配置里的 locale」一起变：
    // 只用 remember {} 会让用户在设置里切换语言后界面不刷新。
    val configuration = LocalConfiguration.current
    val locales = remember(configuration.locales) {
        val list = configuration.locales
        (0 until list.size()).map { list[it] }
    }
    val strings = remember(appLanguage, locales) { stringsFor(appLanguage, locales) }

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalPerf provides perf,
        LocalDeviceProfile provides profile,
        LocalStrings provides strings,
        LocalGlass provides glass,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DshTypography,
            shapes = DshShapes,
        ) {
            if (glass) {
                // 玻璃主题的「底」：**用预设自己的底色**，再叠两团极淡的色晕。
                //
                // 曾经这里写死 `drawRect(colorScheme.surface)`（近黑），
                // 于是不管用户选哪个预设，玻璃底永远是黑的 —— 这就是用户说的
                // 「玻璃主题的背景怎么换了」。现在底色来自预设：
                //   · 玻璃预设 → bg 是 Transparent，用 surface 兜底 + 色晕（原样）
                //   · 白 / 浅色预设 → 就是那块白底，色晕极淡，玻璃在上面才看得出透明
                //
                // 色晕在**浅色底上要大幅收力**：白底上叠 0.46 的主色会变成一片色块，
                // 那不是「环境色」，那是「换了张壁纸」。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            // 底：预设自己的颜色。透明的（玻璃预设）用 surface 兜底 ——
                            // 一个像素都不额外加黑，这正是「不要换背景」的要求
                            drawRect(
                                if (colorScheme.background.alpha < 0.05f) colorScheme.surface
                                else colorScheme.background,
                            )
                            val w = size.width
                            val h = size.height
                            val r = size.minDimension
                            // 色晕强度分两档。深色底：0.46 / 0.28（原来那套，不动）；
                            // 浅色底：0.10 / 0.07 —— 白底上叠主色是**染色**，不是环境光，
                            // 力度稍大就变成「换了一整张壁纸」，而不是「玻璃透出一点色」
                            val k = if (lightBase) 0.22f else 1f
                            drawRect(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to colorScheme.primary.copy(alpha = 0.46f * k),
                                        0.42f to colorScheme.primary.copy(alpha = 0.16f * k),
                                        1f to Color.Transparent,
                                    ),
                                    center = Offset(w * 0.20f, h * 0.02f),
                                    radius = r * 1.25f,
                                ),
                            )
                            drawRect(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to colorScheme.tertiary.copy(alpha = 0.28f * k),
                                        0.55f to colorScheme.tertiary.copy(alpha = 0.08f * k),
                                        1f to Color.Transparent,
                                    ),
                                    center = Offset(w * 0.92f, h * 1.02f),
                                    radius = r * 1.05f,
                                ),
                            )
                            // 中段压暗：只在深色底上做景深。
                            // 浅色底压黑等于把用户选的白色背景又搅浑 —— 正是这次要修的病
                            if (!lightBase) {
                                drawRect(
                                    brush = Brush.radialGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Transparent,
                                            0.55f to Color.Black.copy(alpha = 0.10f),
                                            1f to Color.Black.copy(alpha = 0.30f),
                                        ),
                                        center = Offset(w * 0.5f, h * 0.46f),
                                        radius = r * 1.15f,
                                    ),
                                )
                            }
                        },
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

val DshShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
)
