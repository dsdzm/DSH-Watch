package com.dshwatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 强调色。
 *
 * 只覆盖「主色」一组（primary / container），背景与正文层级由主题预设决定 ——
 * 两者正交，所以 7 套预设 × 6 种强调色 = 42 种组合，而不需要写 42 套完整色板。
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

// ---- 预设：质感玻璃 / OLED 黑 / 深海 ----

/**
 * 质感玻璃。
 *
 * 色板本身要**足够暗但不死黑**：玻璃卡片是半透明的，底下必须有东西可透。
 * 纯 0x000000 的底会让半透明层叠出来仍然是一块灰砖，看不到层次；
 * 所以这里在近黑里掺了一点点蓝（0x06070A），层级之间也确实拉开了距离。
 *
 * 这套预设真正的观感来自 [com.dshwatch.ui.components.glassSurface] 的多层自绘，
 * 色板只负责「底色与文字层级」这两件事。
 */
private val GlassPreset = ThemePreset(
    id = "glass", labelZh = "质感玻璃", labelEn = "Glass",
    /*
     * 背景设为**全透明**，这是玻璃能成立的前提。
     *
     * 各页面的根布局都会 `background(colorScheme.background)`；只要它不透明，
     * 就会盖掉 DshWatchTheme 画在最底下的那两团色晕 —— 玻璃卡片下面什么都没有，
     * 叠三层还是纯黑，跟实心灰块的唯一区别就只剩描边。
     *
     * 透明之后，页面背景这一层等于「让位」，真正的不透明底由 DshWatchTheme
     * 用一个 Box 连同色晕一起画（surface 保持近黑，组件的默认表面色不受影响）。
     * 这个做法依赖 Window 背景是黑色（themes.xml 里已设），否则会闪一帧白。
     */
    dark = presetDark(
        bg = Color.Transparent, low = Color(0xFF181D29), container = Color(0xFF1F2534),
        high = Color(0xFF2A3142), highest = Color(0xFF363E52), onBg = Color(0xFFF3F5F9),
        variant = Color(0xFF363E52), onVariant = Color(0xFFBFC6D4), outline = Color(0xFF7C8698),
    ).copy(surface = Color(0xFF101420)),
    light = presetLight(
        bg = Color.Transparent, low = Color(0xFFF1F4FA), container = Color(0xFFF7F9FD),
        high = Color(0xFFFFFFFF), highest = Color(0xFFFFFFFF), onBg = Color(0xFF0E131B),
        variant = Color(0xFFE2E7F0), onVariant = Color(0xFF475061), outline = Color(0xFF7A8494),
    ).copy(surface = Color(0xFFE9EDF4)),
)

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
    /**
     * 「质感玻璃」预设的 id。
     *
     * 它**仍然是一个正常的主题预设**，和 OLED / 深海 / 樱花并列。
     * 曾经我把它从这里删掉、当成独立的材质，那是错的 —— 用户明确说过：
     * 「玻璃主题的背景怎么换了？不要换，不然纯白色背景无法体现出玻璃的透明」。
     * 玻璃的底就该是它原来那套（近黑 / 近白的浅色底）：
     * 透过去的颜色越亮，半透明的白才越像玻璃；底一黑，玻璃就成灰板。
     */
    const val GLASS_ID = "glass"

    const val OLED_ID = "oled"

    /**
     * 全部预设，玻璃在**第一位**（它是默认预设）。
     *
     * 预设负责「底是什么颜色」，外观风格负责「卡片透不透」—— 两个维度正交，
     * 所以「玻璃预设 + 玻璃材质」和「玻璃预设 + M3E 卡片」都是合法组合。
     */
    val all = listOf(GlassPreset, Oled, Ocean, Forest, Sakura, Sunset, Graphite)

    fun of(id: String): ThemePreset =
        all.firstOrNull { it.id == id } ?: GlassPreset

    @Deprecated("用 Appearances.isGlass(settings.appearance)")
    fun isGlass(id: String): Boolean = id == GLASS_ID

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
        // 玻璃预设的 background 是透明的（见 GlassPreset），色卡上要换成 surface，
        // 否则第一个圆点直接消失，看着像少了一个颜色
        val bg = if (s.background.alpha < 0.05f) s.surface else s.background
        return listOf(bg, s.primary, s.tertiary)
    }
}
