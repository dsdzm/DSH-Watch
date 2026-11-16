package com.dshwatch.ui.phone

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.dshwatch.ui.theme.MonetScheme

/**
 * 手机界面的色板来源。
 *
 * ## 三级回落，只有一个入口
 *
 * ```
 * 有种子（壁纸 / 用户挑的颜色） → 莫奈生成整套色板
 * 没种子                       → M3 baseline（seed #6750A4）
 * ```
 *
 * 为什么手机版也用 [MonetScheme] 而不是系统的 `dynamicLightColorScheme`：
 *
 *  1. **两套界面同色**。手表那套早就在用 [MonetScheme]，手机上换成系统那份，
 *     同一台设备同一个开关会得到两种相近但不同的紫 —— 用户看不出哪边是对的。
 *  2. **低版本也有取色**。系统动态取色要 API 31；这个 App 的 minSdk 是 28，
 *     `SeedSource` 从壁纸缩略图里算种子这条路在所有版本上都成立。
 *  3. **种子色与取色共用一套算法**。用户点了「暮紫」，得到的就是壁纸是紫色
 *     时那份色板 —— 心理模型一致：种子是同一个东西，只是来源不同。
 *
 * 之前手机主题只有「系统动态取色 + 写死的 M3 baseline」两条路：设置里的
 * 种子色（以及低版本上的「跟随壁纸」）在这套界面上**完全没有效果** ——
 * 点完颜色什么都没变，用户只能理解成坏了。
 */
internal object PhonePalette {

    /**
     * @param dark 已经由 [com.dshwatch.domain.model.ThemeMode] 解析过
     * @param seed 种子色 ARGB；null = 没取到壁纸也没挑颜色
     */
    fun of(dark: Boolean, seed: Int?): ColorScheme = when {
        seed != null -> MonetScheme.of(seed, dark)
        dark -> PhoneDarkColors
        else -> PhoneLightColors
    }
}

// ---------------- M3 baseline 色板 ----------------
//
// 灯下（浅色）与夜间（深色）两套，取 Material Design 3 官方 baseline
// （seed #6750A4）的 tonal palette 映射，数值即 MDC 生成器输出。
//
// 只在「没有种子」时用到：第一次装、或者用户把取色关了又没挑颜色。

internal val PhoneLightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceTint = Color(0xFF6750A4),
    inverseSurface = Color(0xFF322F35),
    inverseOnSurface = Color(0xFFF5EFF7),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFEF7FF),
    surfaceDim = Color(0xFFDED8E1),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

internal val PhoneDarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    inversePrimary = Color(0xFF6750A4),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceTint = Color(0xFFD0BCFF),
    inverseSurface = Color(0xFFE6E0E9),
    inverseOnSurface = Color(0xFF322F35),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3B383E),
    surfaceDim = Color(0xFF141218),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainerLowest = Color(0xFF0F0D13),
)
