package com.dshwatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.dshwatch.domain.theme.MonetColors

/**
 * 莫奈色板 → Compose [ColorScheme]。
 *
 * 只做一次「字段对字段」的搬运：真正的计算在 [MonetColors]（纯 Kotlin，
 * 可离线单测）。分两层的原因是 Compose 的 `Color` / `ColorScheme` 在
 * 纯 JVM 单测里不好断言，而颜色数学恰恰是最需要钉住的部分。
 */
object MonetScheme {

    fun of(seed: Int, dark: Boolean): ColorScheme {
        val s = if (dark) MonetColors.dark(seed) else MonetColors.light(seed)
        return if (dark) dark(s) else light(s)
    }

    private fun dark(s: MonetColors.Scheme): ColorScheme = darkColorScheme(
        primary = Color(s.primary),
        onPrimary = Color(s.onPrimary),
        primaryContainer = Color(s.primaryContainer),
        onPrimaryContainer = Color(s.onPrimaryContainer),
        inversePrimary = Color(s.inversePrimary),
        secondary = Color(s.secondary),
        onSecondary = Color(s.onSecondary),
        secondaryContainer = Color(s.secondaryContainer),
        onSecondaryContainer = Color(s.onSecondaryContainer),
        tertiary = Color(s.tertiary),
        onTertiary = Color(s.onTertiary),
        tertiaryContainer = Color(s.tertiaryContainer),
        onTertiaryContainer = Color(s.onTertiaryContainer),
        error = Color(s.error),
        onError = Color(s.onError),
        errorContainer = Color(s.errorContainer),
        onErrorContainer = Color(s.onErrorContainer),
        background = Color(s.background),
        onBackground = Color(s.onBackground),
        surface = Color(s.surface),
        onSurface = Color(s.onSurface),
        surfaceVariant = Color(s.surfaceVariant),
        onSurfaceVariant = Color(s.onSurfaceVariant),
        surfaceContainerLowest = Color(s.surfaceContainerLowest),
        surfaceContainerLow = Color(s.surfaceContainerLow),
        surfaceContainer = Color(s.surfaceContainer),
        surfaceContainerHigh = Color(s.surfaceContainerHigh),
        surfaceContainerHighest = Color(s.surfaceContainerHighest),
        outline = Color(s.outline),
        outlineVariant = Color(s.outlineVariant),
        scrim = Color(s.scrim),
    )

    private fun light(s: MonetColors.Scheme): ColorScheme = lightColorScheme(
        primary = Color(s.primary),
        onPrimary = Color(s.onPrimary),
        primaryContainer = Color(s.primaryContainer),
        onPrimaryContainer = Color(s.onPrimaryContainer),
        inversePrimary = Color(s.inversePrimary),
        secondary = Color(s.secondary),
        onSecondary = Color(s.onSecondary),
        secondaryContainer = Color(s.secondaryContainer),
        onSecondaryContainer = Color(s.onSecondaryContainer),
        tertiary = Color(s.tertiary),
        onTertiary = Color(s.onTertiary),
        tertiaryContainer = Color(s.tertiaryContainer),
        onTertiaryContainer = Color(s.onTertiaryContainer),
        error = Color(s.error),
        onError = Color(s.onError),
        errorContainer = Color(s.errorContainer),
        onErrorContainer = Color(s.onErrorContainer),
        background = Color(s.background),
        onBackground = Color(s.onBackground),
        surface = Color(s.surface),
        onSurface = Color(s.onSurface),
        surfaceVariant = Color(s.surfaceVariant),
        onSurfaceVariant = Color(s.onSurfaceVariant),
        surfaceContainerLowest = Color(s.surfaceContainerLowest),
        surfaceContainerLow = Color(s.surfaceContainerLow),
        surfaceContainer = Color(s.surfaceContainer),
        surfaceContainerHigh = Color(s.surfaceContainerHigh),
        surfaceContainerHighest = Color(s.surfaceContainerHighest),
        outline = Color(s.outline),
        outlineVariant = Color(s.outlineVariant),
        scrim = Color(s.scrim),
    )
}

/**
 * 种子色。
 *
 * ## 为什么是 9 个
 *
 * 参考界面的调色盘就是 8 个彩色 + 1 个灰。灰不是凑数：它对应 4 的彩度，
 * 生成的是**近乎无彩**的一整套色板 —— 喜欢「纯黑白 + 一点点灰蓝」的人
 * 只有这一条路，否则莫奈取色永远带着明显色相。
 *
 * ## 为什么这些具体色号
 *
 * 不是随便挑的「好看的 9 个色」。种子色的彩度决定整屏的"艳"，明度只影响
 * 生成的 tone 偏移。所以每个种子都取在 `L* ≈ 50~55、C ≈ 40~55` 这一段 ——
 * 太低会生成一片灰，太高在深色主题里 80 tone 会被 sRGB 挤掉，都看不出色相。
 */
data class ThemeSeed(
    val id: String,
    val argb: Int,
    val labelZh: String,
    val labelEn: String,
) {
    val hex: String get() = String.format("#%06X", argb and 0xFFFFFF)
}

object ThemeSeeds {

    val all = listOf(
        ThemeSeed("crimson", 0xFFB3564F.toInt(), "砖红", "Brick"),
        ThemeSeed("ocean", 0xFF3E6FA8.toInt(), "海蓝", "Ocean"),
        ThemeSeed("forest", 0xFF3F7A5C.toInt(), "林绿", "Forest"),
        ThemeSeed("violet", 0xFF7A4A9E.toInt(), "暮紫", "Violet"),
        ThemeSeed("amber", 0xFFA87C3F.toInt(), "琥珀", "Amber"),
        ThemeSeed("rose", 0xFFA8416A.toInt(), "绯红", "Rose"),
        ThemeSeed("teal", 0xFF2F6B6B.toInt(), "墨青", "Teal"),
        ThemeSeed("indigo", 0xFF4A5B9E.toInt(), "靛蓝", "Indigo"),
        ThemeSeed("graphite", 0xFF6E6E76.toInt(), "石墨", "Graphite"),
    )

    /** 取不到壁纸、也没选种子时的兜底色相（蓝）。见 [MonetColors.palettes] */
    val fallback: ThemeSeed get() = all[1]

    fun byId(id: String): ThemeSeed = all.firstOrNull { it.id == id } ?: fallback

    /** 按存下来的 `#RRGGBB` 找种子；找不到（用户从别处导入）时造一个临时项 */
    fun byHex(hex: String): ThemeSeed? {
        val v = parseHex(hex) ?: return null
        return all.firstOrNull { it.argb == v }
            ?: ThemeSeed("custom", v, "自定义", "Custom")
    }

    /**
     * 解析 `#RRGGBB` / `RRGGBB`。
     *
     * 不用 `Color.parseColor`：它会抛异常，而这里的数据来自设置文件 ——
     * 一个手改坏的值不该让主题崩掉，应该是"回退到默认"。
     */
    fun parseHex(hex: String): Int? {
        val t = hex.trim().removePrefix("#")
        if (t.length != 6) return null
        val v = t.toLongOrNull(16) ?: return null
        return (0xFF000000 or v).toInt()
    }

    fun hexOf(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
}
