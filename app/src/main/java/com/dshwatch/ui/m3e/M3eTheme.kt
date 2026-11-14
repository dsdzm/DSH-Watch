package com.dshwatch.ui.m3e

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.material3.Shapes as WearShapes

/**
 * M3E 界面的主题层。
 *
 * ## 为什么是「两套 MaterialTheme 叠着」
 *
 * 页面里写的是 `MaterialTheme.colorScheme.onSurface` —— 那是 `androidx.compose.material3`
 * 的。M3E 的**控件**（Card、Button、SwitchButton…）读的却是
 * `androidx.wear.compose.material3.MaterialTheme` 的色板。两个是不同的类，
 * 谁也替不了谁。
 *
 * 所以 M3E 模式下同时提供两层：
 *
 * ```
 * androidx.compose.material3.MaterialTheme      ← 页面取色、取字号用
 *   └─ androidx.wear.compose.material3.MaterialTheme  ← 控件取色用
 * ```
 *
 * 两层色板**从同一份 [ColorScheme] 映射**，色值严格一致。这样同一页里
 * 「一行自己画的小字」和「一张 M3E 卡片」不会出现两种深浅的白。
 *
 * ## 只映射 29 个 token，不做“猜”
 *
 * Wear 的色板比手机版少几个槽位（没有 `surface`、没有 `surfaceVariant`，
 * 取而代之的是 `surfaceContainerLow/Container/High` 三档）。映射关系按语义：
 *
 * | 手机 | Wear | 用在哪 |
 * |---|---|---|
 * | `surface` | `surfaceContainerLow` | 页面底、普通卡片 |
 * | `surfaceContainer` | `surfaceContainer` | 浮起来的卡片 |
 * | `surfaceContainerHigh` | `surfaceContainerHigh` | 对话框、按钮底 |
 * | `surfaceVariant` | `onSurfaceVariant` 的底 | 见下 |
 *
 * `primaryDim` / `secondaryDim` / `tertiaryDim` 是 Wear 独有的「暗强调色」，
 * 用于按下态与次级强调。用主色往背景方向压 22% 得到 —— 比直接调 alpha 好，
 * 因为 alpha 会让它和背后的东西混色，在深色底上几乎看不见。
 */
internal fun wearColorScheme(phone: ColorScheme): androidx.wear.compose.material3.ColorScheme {
    val bg = phone.background.takeIf { it.alpha > 0.05f } ?: phone.surface
    return androidx.wear.compose.material3.ColorScheme(
        primary = phone.primary,
        primaryDim = phone.primary.dimTowards(bg),
        primaryContainer = phone.primaryContainer,
        onPrimary = phone.onPrimary,
        onPrimaryContainer = phone.onPrimaryContainer,
        secondary = phone.secondary,
        secondaryDim = phone.secondary.dimTowards(bg),
        secondaryContainer = phone.secondaryContainer,
        onSecondary = phone.onSecondary,
        onSecondaryContainer = phone.onSecondaryContainer,
        tertiary = phone.tertiary,
        tertiaryDim = phone.tertiary.dimTowards(bg),
        tertiaryContainer = phone.tertiaryContainer,
        onTertiary = phone.onTertiary,
        onTertiaryContainer = phone.onTertiaryContainer,
        surfaceContainerLow = phone.surface,
        surfaceContainer = phone.surfaceContainer,
        surfaceContainerHigh = phone.surfaceContainerHigh,
        onSurface = phone.onSurface,
        onSurfaceVariant = phone.onSurfaceVariant,
        outline = phone.outline,
        outlineVariant = phone.outlineVariant,
        background = bg,
        onBackground = phone.onBackground,
        error = phone.error,
        errorDim = phone.error.dimTowards(bg),
        errorContainer = phone.errorContainer,
        onError = phone.onError,
        onErrorContainer = phone.onErrorContainer,
    )
}

/** 往背景色压 22%，得到 Wear 的「暗强调色」 */
private fun Color.dimTowards(background: Color): Color = Color(
    red = red * 0.78f + background.red * 0.22f,
    green = green * 0.78f + background.green * 0.22f,
    blue = blue * 0.78f + background.blue * 0.22f,
    alpha = 1f,
)

/**
 * M3E 排版。
 *
 * 相对 [com.dshwatch.ui.theme.DshTypography] 整体上调一档、字重加半级 ——
 * 这是 Expressive 的核心观感：**同一个界面里字少一点、大一点、稳一点**。
 *
 * 具体差异：
 *
 * | 档位 | 经典 | M3E | 依据 |
 * |---|---|---|---|
 * | bodyMedium | 12sp | 14sp | 正文可读性；M3E 每屏内容本来就少一档 |
 * | bodySmall | 11sp | 12sp | 说明文字 |
 * | labelSmall | 11sp | 11sp | 不动：它已经是最小可读档，再大就挤 |
 * | titleSmall | 12sp | 14sp | 顶栏标题 |
 * | titleMedium | 13sp | 15sp | 卡片标题 |
 *
 * 为什么不再往上加：内容区宽度还是那 220dp 左右（圆的弦长没变），
 * 字号再大一档，一行就只能放六七个汉字，长句会碎成一片。
 */
internal val M3eTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 23.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 17.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

/**
 * M3E 形状。整体比经典更圆：Expressive 的视觉签名就是「几乎全圆」。
 *
 * 手表上这件事更明显：24dp 圆角在 220dp 宽的卡片上只占 1/9，
 * 看起来还是「圆角矩形」；28~30dp 才真的像一颗胶囊。
 */
internal val M3eShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * 给 M3E 控件用的 Wear 形状。Wear 只有五档，语义与手机版一一对应。
 *
 * 注意这里的 `extraLarge` 给到 30dp 是有意的：Wear 的 `ButtonGroup`
 * 会把相邻按钮的外侧角做成这一档，内侧角保持小圆角 —— 组内看起来是
 * 一条连续的胶囊，组外是独立的圆角，这正是 Expressive 的按钮组形态。
 */
internal val M3eWearShapes = WearShapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * 把 M3E 的 Wear 主题套在内容外面。
 *
 * `MotionScheme.expressive()` 不是装饰：它决定按钮展开、形状变换、列表项
 * 淡入用的是哪条曲线。用 `standard()` 的话，M3E 组件看起来会「迟一拍」——
 * 形状是对的，动效是旧的。
 */
@Composable
internal fun M3eWearTheme(phone: ColorScheme, content: @Composable () -> Unit) {
    val scheme = androidx.compose.runtime.remember(phone) { wearColorScheme(phone) }
    WearMaterialTheme(
        colorScheme = scheme,
        typography = WearTypography,
        shapes = M3eWearShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/**
 * Wear 的默认排版。
 *
 * 它和手机版那套 [M3eTypography] 是**两套独立的字阶**：M3E 组件（Card 的标题、
 * Button 的 label、TimeText）读的是这一套，页面里自己写的 `Text` 读的是手机那套。
 * 两者都必须是最终值，所以这里只放默认值、不做二次缩放 ——
 * 组件自己已经按手表尺寸调过了。
 *
 * 提成顶层常量是因为它是不可变的：每次重组 new 一个 22 项的 Typography
 * 对表上的 GC 来说是白白的压力。
 */
private val WearTypography = androidx.wear.compose.material3.Typography()

/**
 * 手机 MaterialTheme 与 Wear M3E 主题的合体。
 *
 * 存在的唯一理由是**不用把原主题实现整段重排**：`DshWatchTheme` 里那一大段
 * `MaterialTheme(colorScheme = …, typography = …, shapes = …) { … }` 只要把开头
 * 换成这个函数，括号一个都不用动。少改一行，就少一次「漏了个括号导致整个
 * 主题塌掉」。
 */
@Composable
internal fun M3eMaterialTheme(
    enabled: Boolean,
    phone: ColorScheme,
    typography: Typography,
    shapes: androidx.compose.material3.Shapes,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = phone,
        typography = typography,
        shapes = shapes,
    ) {
        if (enabled) {
            M3eWearTheme(phone, content)
        } else {
            content()
        }
    }
}
