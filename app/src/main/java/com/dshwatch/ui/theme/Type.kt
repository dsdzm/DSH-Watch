package com.dshwatch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 手表排版。
 *
 * v1.13 之前整体偏小：bodyMedium 12sp 被当成正文用，而 SettingsGroupScreen 里
 * 又把 bodyMedium 覆盖成 15sp 当卡片标题 —— 同一个名字两种字号，一处放大一处不放大，
 * 界面才会显得「有的地方小得看不清、有的地方又刚好」。
 *
 * 这一版只动**最小的那一档**（labelSmall 9sp → 11sp）：它承担的是提示、说明、
 * 状态行这类"看得见但不用细读"的文案，9sp 在圆屏边缘基本是糊的。
 * 其余字号保持不动 —— bodyMedium 已被多处按 15sp 使用，整体上调会把已经排好的
 * 卡片挤爆，收益远小于风险。
 */
val DshTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
)

val MonoMedium = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/**
 * 手表布局（Wear OS Material 3）的排版。
 *
 * ## 为什么不直接用 Wear 库的 `Typography`
 *
 * `androidx.wear.compose.material3.Typography` 的槽位叫 `title1/title2/body1/label1`，
 * 和 `androidx.compose.material3.Typography` 的 `titleSmall/bodyMedium/labelSmall`
 * **是两个不同的类**。全项目 300+ 处 `MaterialTheme.typography.xxx` 用的是手机槽位名，
 * 换类等于改 300 个调用点。
 *
 * 所以这里做的是「用手机的槽位名，填 Wear 的字号值」。对应关系：
 *
 * | 手机槽位 | Wear 槽位 | 值 |
 * |---|---|---|
 * | displaySmall | displaySmall | 30/36 |
 * | headlineSmall | titleLarge | 24/28 |
 * | titleLarge | titleMedium | 20/24 |
 * | titleMedium | titleSmall | 18/22 |
 * | titleSmall | titleSmall 的下一档 | 16/20 |
 * | bodyLarge | bodyLarge | 16/20 |
 * | bodyMedium | bodyMedium | 14/18 |
 * | bodySmall | bodySmall | 12/16 |
 * | labelLarge | labelLarge | 14/18 |
 * | labelMedium | labelMedium | 12/16 |
 * | labelSmall | labelSmall | 10/14 |
 *
 * ## 代价与兜底
 *
 * 正文 12→14、卡片标题 12→16，会让一批「固定高度 + 单行文本」的容器开始裁字。
 * 布局侧的兜底是：手表骨架把内容区定为圆内固定带、页面自己滚动，所以多出来的
 * 行数只会变成滚动距离，不会溢出屏幕。真正要盯的是写死 `height()` 的按钮和行，
 * 见 `WearDimens`。
 */
val WearTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)
