package com.dshwatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 强调色。
 *
 * 只覆盖「主色」一组（primary / container），背景与正文层级由主题预设决定 ——
 * 两者正交，所以 6 套预设 × 6 种强调色 = 36 种组合，而不需要写 36 套完整色板。
 */
data class Accent(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkContainer: Color,
    val darkOnContainer: Color,
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
) {
    /** 引导页/设置页的小色卡 */
    val swatchDark: Color get() = darkPrimary
    val swatchLight: Color get() = lightPrimary
}

object Accents {
    val all = listOf(
        Accent(
            "blue", "深海蓝", "Deep blue",
            Color(0xFFD6E3FF), Color(0xFF101418), Color(0xFF2C3A52), Color(0xFFDCE6F8),
            Color(0xFF3B56D6), Color(0xFFFFFFFF), Color(0xFFDCE1FF), Color(0xFF001551),
        ),
        Accent(
            "teal", "青碧", "Teal",
            Color(0xFF7FD8CE), Color(0xFF00201C), Color(0xFF0C3B35), Color(0xFFC3F0EA),
            Color(0xFF00796B), Color(0xFFFFFFFF), Color(0xFFCCF2EC), Color(0xFF00211D),
        ),
        Accent(
            "violet", "暮紫", "Violet",
            Color(0xFFD6C6FF), Color(0xFF16003E), Color(0xFF33205C), Color(0xFFE8DDFF),
            Color(0xFF6B4BC4), Color(0xFFFFFFFF), Color(0xFFE9DDFF), Color(0xFF22005C),
        ),
        Accent(
            "amber", "琥珀", "Amber",
            Color(0xFFFFD48A), Color(0xFF2C1600), Color(0xFF4A3006), Color(0xFFFFE7B8),
            Color(0xFFA86200), Color(0xFFFFFFFF), Color(0xFFFFE0B2), Color(0xFF2E1500),
        ),
        Accent(
            "rose", "玫瑰", "Rose",
            Color(0xFFFFB4C9), Color(0xFF3A0018), Color(0xFF5C1730), Color(0xFFFFD9E3),
            Color(0xFFB3134E), Color(0xFFFFFFFF), Color(0xFFFFD9E4), Color(0xFF3F0018),
        ),
        Accent(
            "lime", "青柠", "Lime",
            Color(0xFFC8E88A), Color(0xFF1E2A00), Color(0xFF344709), Color(0xFFE2F7B5),
            Color(0xFF4F6B00), Color(0xFFFFFFFF), Color(0xFFE3F5B5), Color(0xFF172000),
        ),
    )

    fun of(id: String): Accent = all.firstOrNull { it.id == id } ?: all.first()

    /** 把强调色叠到预设色板上 */
    fun apply(base: ColorScheme, dark: Boolean, accent: Accent): ColorScheme = if (dark) {
        base.copy(
            primary = accent.darkPrimary,
            onPrimary = accent.darkOnPrimary,
            primaryContainer = accent.darkContainer,
            onPrimaryContainer = accent.darkOnContainer,
            inversePrimary = accent.lightPrimary,
        )
    } else {
        base.copy(
            primary = accent.lightPrimary,
            onPrimary = accent.lightOnPrimary,
            primaryContainer = accent.lightContainer,
            onPrimaryContainer = accent.lightOnContainer,
            inversePrimary = accent.darkPrimary,
        )
    }
}

/** 一套主题预设只描述「背景与层级」，主色交给 [Accents] */
data class ThemePreset(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val dark: ColorScheme,
    val light: ColorScheme,
)

private fun presetDark(
    bg: Color,
    low: Color,
    container: Color,
    high: Color,
    highest: Color,
    onBg: Color,
    variant: Color,
    onVariant: Color,
    outline: Color,
): ColorScheme = darkColorScheme(
    background = bg,
    onBackground = onBg,
    surface = bg,
    onSurface = onBg,
    surfaceVariant = variant,
    onSurfaceVariant = onVariant,
    surfaceContainerLowest = bg,
    surfaceContainerLow = low,
    surfaceContainer = container,
    surfaceContainerHigh = high,
    surfaceContainerHighest = highest,
    outline = outline,
    outlineVariant = high,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xCC000000),
)

private fun presetLight(
    bg: Color,
    low: Color,
    container: Color,
    high: Color,
    highest: Color,
    onBg: Color,
    variant: Color,
    onVariant: Color,
    outline: Color,
): ColorScheme = lightColorScheme(
    background = bg,
    onBackground = onBg,
    surface = bg,
    onSurface = onBg,
    surfaceVariant = variant,
    onSurfaceVariant = onVariant,
    surfaceContainerLowest = bg,
    surfaceContainerLow = low,
    surfaceContainer = container,
    surfaceContainerHigh = high,
    surfaceContainerHighest = highest,
    outline = outline,
    outlineVariant = variant,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0x99000000),
)

// ---- 预设：OLED 黑 / 深海 / 森林（其余在文件后半）----

private val Oled = ThemePreset(
    id = "oled", labelZh = "OLED 黑", labelEn = "OLED black",
    dark = presetDark(
        bg = Color(0xFF000000), low = Color(0xFF141414), container = Color(0xFF1F1F1F),
        high = Color(0xFF2A2A2A), highest = Color(0xFF343434), onBg = Color(0xFFF2F2F2),
        variant = Color(0xFF2A2A2A), onVariant = Color(0xFFC4C4C4), outline = Color(0xFF7A7A7A),
    ),
    light = presetLight(
        bg = Color(0xFFFFFFFF), low = Color(0xFFF7F8FB), container = Color(0xFFEDEFF5),
        high = Color(0xFFE0E3EB), highest = Color(0xFFD3D7E0), onBg = Color(0xFF121821),
        variant = Color(0xFFE0E3EB), onVariant = Color(0xFF444C58), outline = Color(0xFF757D8A),
    ),
)

private val Ocean = ThemePreset(
    id = "ocean", labelZh = "深海", labelEn = "Ocean",
    dark = presetDark(
        bg = Color(0xFF001018), low = Color(0xFF04161F), container = Color(0xFF0A2029),
        high = Color(0xFF102A35), highest = Color(0xFF193745), onBg = Color(0xFFE7F4FA),
        variant = Color(0xFF102A35), onVariant = Color(0xFFB9CFD8), outline = Color(0xFF6E8A96),
    ),
    light = presetLight(
        bg = Color(0xFFF5FBFF), low = Color(0xFFEBF6FC), container = Color(0xFFE1F1F9),
        high = Color(0xFFD5EAF5), highest = Color(0xFFC6E0EE), onBg = Color(0xFF0A1E26),
        variant = Color(0xFFD5EAF5), onVariant = Color(0xFF3D5866), outline = Color(0xFF6C8794),
    ),
)

private val Sakura = ThemePreset(
    id = "sakura", labelZh = "樱花", labelEn = "Sakura",
    dark = presetDark(
        bg = Color(0xFF140A0D), low = Color(0xFF1D1014), container = Color(0xFF27161B),
        high = Color(0xFF331D23), highest = Color(0xFF40262D), onBg = Color(0xFFF8E9ED),
        variant = Color(0xFF331D23), onVariant = Color(0xFFD9B9C3), outline = Color(0xFF9B7480),
    ),
    light = presetLight(
        bg = Color(0xFFFFFAFB), low = Color(0xFFFDF0F3), container = Color(0xFFFAE6EB),
        high = Color(0xFFF6DBE3), highest = Color(0xFFF0CED9), onBg = Color(0xFF241016),
        variant = Color(0xFFF6DBE3), onVariant = Color(0xFF6E4A55), outline = Color(0xFF987681),
    ),
)

private val Sunset = ThemePreset(
    id = "sunset", labelZh = "落日", labelEn = "Sunset",
    dark = presetDark(
        bg = Color(0xFF140A05), low = Color(0xFF1E1007), container = Color(0xFF29160B),
        high = Color(0xFF361D10), highest = Color(0xFF442617), onBg = Color(0xFFFCEDE0),
        variant = Color(0xFF361D10), onVariant = Color(0xFFDDC0A8), outline = Color(0xFFA17F63),
    ),
    light = presetLight(
        bg = Color(0xFFFFFBF7), low = Color(0xFFFDF3EA), container = Color(0xFFFAEADB),
        high = Color(0xFFF6E0CC), highest = Color(0xFFF0D3BA), onBg = Color(0xFF241308),
        variant = Color(0xFFF6E0CC), onVariant = Color(0xFF6B5140), outline = Color(0xFF96775F),
    ),
)

private val Graphite = ThemePreset(
    id = "graphite", labelZh = "石墨", labelEn = "Graphite",
    dark = presetDark(
        bg = Color(0xFF0B0B0C), low = Color(0xFF121214), container = Color(0xFF1A1A1D),
        high = Color(0xFF232327), highest = Color(0xFF2E2E33), onBg = Color(0xFFF0F0F2),
        variant = Color(0xFF232327), onVariant = Color(0xFFC6C6CC), outline = Color(0xFF8A8A92),
    ),
    light = presetLight(
        bg = Color(0xFFFAFAFB), low = Color(0xFFF1F1F3), container = Color(0xFFE7E7EA),
        high = Color(0xFFDCDCE0), highest = Color(0xFFCFCFD4), onBg = Color(0xFF121214),
        variant = Color(0xFFDCDCE0), onVariant = Color(0xFF55555C), outline = Color(0xFF7A7A82),
    ),
)

private val Forest = ThemePreset(
    id = "forest", labelZh = "森林", labelEn = "Forest",
    dark = presetDark(
        bg = Color(0xFF041007), low = Color(0xFF08190D), container = Color(0xFF0E2414),
        high = Color(0xFF15301C), highest = Color(0xFF1D3E26), onBg = Color(0xFFE6F4E8),
        variant = Color(0xFF15301C), onVariant = Color(0xFFB9D3BE), outline = Color(0xFF6E8E76),
    ),
    light = presetLight(
        bg = Color(0xFFF6FBF6), low = Color(0xFFECF6ED), container = Color(0xFFE2F1E4),
        high = Color(0xFFD6EAD9), highest = Color(0xFFC8DFCC), onBg = Color(0xFF102016),
        variant = Color(0xFFD6EAD9), onVariant = Color(0xFF45604C), outline = Color(0xFF718977),
    ),
)

object ThemePresets {
    /** 顺序即引导页与设置页的显示顺序 */
    val all = listOf(Oled, Ocean, Forest, Sakura, Sunset, Graphite)

    fun of(id: String): ThemePreset = all.firstOrNull { it.id == id } ?: all.first()

    /**
     * 最终色板 = 预设的明暗层级 + 强调色的主色组。
     *
     * @param dark 已经由 ThemeMode / 系统明暗解析过，不在这里判断
     */
    fun scheme(presetId: String, accentId: String, dark: Boolean): ColorScheme {
        val preset = of(presetId)
        val base = if (dark) preset.dark else preset.light
        return Accents.apply(base, dark, Accents.of(accentId))
    }

    /** 主题卡上的三色预览（背景 / 主色 / 正文） */
    fun swatch(presetId: String, accentId: String, dark: Boolean): List<Color> {
        val s = scheme(presetId, accentId, dark)
        return listOf(s.background, s.primary, s.tertiary)
    }
}
