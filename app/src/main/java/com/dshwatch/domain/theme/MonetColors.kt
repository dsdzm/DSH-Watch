package com.dshwatch.domain.theme

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 莫奈取色（Material You / Monet）的本地实现。
 *
 * ## 为什么不引依赖
 *
 * 官方实现是 `material-color-utilities`（HCT + CAM16），本项目全程 `--offline`
 * 构建，本地 Gradle 缓存里没有这个包。而 HCT 里真正被色板用到的只有两件事：
 *
 *   · **tone** —— 定义就是 CIE L*（0 黑、100 白）；
 *   · **chroma / hue** —— 决定这个颜色"有多艳、偏向哪"。
 *
 * 所以这里用 CIE Lab 的 LCh 直接做同一件事：L 就是 tone，C 是彩度，h 是色相。
 * 与 HCT 的差别在极端色（高彩度的黄绿）上色相会有几度偏移，肉眼在表盘上
 * 分辨不出来；换来的是零依赖、纯函数、可离线单测。
 *
 * ## 色板结构与 Material 3 一致
 *
 * 每个种子生成六条色调带（primary / secondary / tertiary / neutral /
 * neutralVariant / error），再按 M3 的 tone 映射取色。映射表是硬编码的 ——
 * 这是规范的一部分，不是可调参数：`primary40` 在浅色下当主色、`primary80`
 * 在深色下当主色，改了就"不像 Material"。
 *
 * ## 超出 sRGB 的处理
 *
 * Lab 的某些 (L, C, h) 组合在 sRGB 里不存在（纯青、纯洋红的极端明度）。
 * 这里的做法是**保 tone 与 hue、降 chroma**：二分到刚好落进色域为止。
 * 反过来（降亮度）会让整个色板明度错位，"深色主题的卡片比背景还暗"。
 */
object MonetColors {

    /** LCh：L = tone（0..100），C = 彩度，h = 色相角（度） */
    data class Lch(val l: Double, val c: Double, val h: Double)

    /**
     * 一条色调带：固定色相与彩度，按 tone 取色。
     *
     * 与 Material 的 `TonalPalette` 同名同义 —— 同一个 palette 的 40 与 90
     * 是"同一个颜色的深浅两档"，不是两种颜色。
     */
    class Palette(val hue: Double, val chroma: Double) {
        fun tone(tone: Int): Int = MonetColors.tone(hue, chroma, tone.toDouble())
    }

    data class Palettes(
        val primary: Palette,
        val secondary: Palette,
        val tertiary: Palette,
        val neutral: Palette,
        val neutralVariant: Palette,
        val error: Palette,
    )

    /**
     * 从种子色生成六条色调带。
     *
     * 彩度下限沿用 Material 的做法：`primary` 至少 48。原因是从壁纸取的种子
     * 往往是**灰调**（天空、水泥墙），直接用它当主色会让整个界面像没上色的线框。
     * 下限把"这一张图的色相"提出来，同时不让它淡到看不见。
     */
    fun palettes(seed: Int): Palettes {
        val lch = argbToLch(seed)
        val hue = if (lch.c < 1.0) DEFAULT_HUE else lch.h
        return Palettes(
            primary = Palette(hue, maxOf(lch.c, 48.0)),
            secondary = Palette(hue, 16.0),
            // 第三色偏 60°：M3 用它做"点缀色"，和主色同色系会完全分不出来
            tertiary = Palette((hue + 60.0) % 360.0, 24.0),
            neutral = Palette(hue, 4.0),
            neutralVariant = Palette(hue, 8.0),
            error = Palette(25.0, 84.0),
        )
    }

    /** 种子太灰时用的色相（蓝）。没有它，灰图会生成一整屏灰 */
    private const val DEFAULT_HUE = 220.0

    // ---------------------------------------------------------------------
    // 色板：浅色 / 深色
    // ---------------------------------------------------------------------

    /**
     * 一套完整的 M3 色板（值为 ARGB）。
     *
     * 字段与 `ColorScheme` 一一对应，放这里是为了**能单测**：
     * 断言"深色主色比浅色主色亮"、"背景几乎无色"这类性质，
     * 不需要拉起 Android。
     */
    data class Scheme(
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val inversePrimary: Int,
        val secondary: Int,
        val onSecondary: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiary: Int,
        val onTertiary: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
        val error: Int,
        val onError: Int,
        val errorContainer: Int,
        val onErrorContainer: Int,
        val background: Int,
        val onBackground: Int,
        val surface: Int,
        val onSurface: Int,
        val surfaceVariant: Int,
        val onSurfaceVariant: Int,
        val surfaceContainerLowest: Int,
        val surfaceContainerLow: Int,
        val surfaceContainer: Int,
        val surfaceContainerHigh: Int,
        val surfaceContainerHighest: Int,
        val outline: Int,
        val outlineVariant: Int,
        val scrim: Int,
    )

    fun light(seed: Int): Scheme = build(palettes(seed), dark = false)

    fun dark(seed: Int): Scheme = build(palettes(seed), dark = true)

    fun build(p: Palettes, dark: Boolean): Scheme {
        val n = p.neutral
        val nv = p.neutralVariant
        return if (dark) {
            Scheme(
                primary = p.primary.tone(80),
                onPrimary = p.primary.tone(20),
                primaryContainer = p.primary.tone(30),
                onPrimaryContainer = p.primary.tone(90),
                inversePrimary = p.primary.tone(40),
                secondary = p.secondary.tone(80),
                onSecondary = p.secondary.tone(20),
                secondaryContainer = p.secondary.tone(30),
                onSecondaryContainer = p.secondary.tone(90),
                tertiary = p.tertiary.tone(80),
                onTertiary = p.tertiary.tone(20),
                tertiaryContainer = p.tertiary.tone(30),
                onTertiaryContainer = p.tertiary.tone(90),
                error = p.error.tone(80),
                onError = p.error.tone(20),
                errorContainer = p.error.tone(30),
                onErrorContainer = p.error.tone(90),
                // 背景/卡片：从 4 到 22 的五档。
                // 曾经想用纯 0（真黑）当 lowest，但那样"卡片比背景亮"的层级关系
                // 会在 OLED 上变成一个发光方块 —— 深色主题的第一档必须离纯黑有点距离
                background = n.tone(6),
                onBackground = n.tone(90),
                surface = n.tone(6),
                onSurface = n.tone(90),
                surfaceVariant = nv.tone(30),
                onSurfaceVariant = nv.tone(80),
                surfaceContainerLowest = n.tone(4),
                surfaceContainerLow = n.tone(10),
                surfaceContainer = n.tone(12),
                surfaceContainerHigh = n.tone(17),
                surfaceContainerHighest = n.tone(22),
                outline = nv.tone(60),
                outlineVariant = nv.tone(30),
                scrim = 0xCC000000.toInt(),
            )
        } else {
            Scheme(
                primary = p.primary.tone(40),
                onPrimary = p.primary.tone(100),
                primaryContainer = p.primary.tone(90),
                onPrimaryContainer = p.primary.tone(10),
                inversePrimary = p.primary.tone(80),
                secondary = p.secondary.tone(40),
                onSecondary = p.secondary.tone(100),
                secondaryContainer = p.secondary.tone(90),
                onSecondaryContainer = p.secondary.tone(10),
                tertiary = p.tertiary.tone(40),
                onTertiary = p.tertiary.tone(100),
                tertiaryContainer = p.tertiary.tone(90),
                onTertiaryContainer = p.tertiary.tone(10),
                error = p.error.tone(40),
                onError = p.error.tone(100),
                errorContainer = p.error.tone(90),
                onErrorContainer = p.error.tone(10),
                background = n.tone(99),
                onBackground = n.tone(10),
                surface = n.tone(99),
                onSurface = n.tone(10),
                surfaceVariant = nv.tone(90),
                onSurfaceVariant = nv.tone(30),
                surfaceContainerLowest = n.tone(100),
                surfaceContainerLow = n.tone(96),
                surfaceContainer = n.tone(94),
                surfaceContainerHigh = n.tone(92),
                surfaceContainerHighest = n.tone(90),
                outline = nv.tone(50),
                outlineVariant = nv.tone(80),
                scrim = 0x99000000.toInt(),
            )
        }
    }

    // ---------------------------------------------------------------------
    // 色彩空间
    // ---------------------------------------------------------------------

    /** 固定 tone 与 hue，取 sRGB 色域内能达到的最大彩度 */
    fun tone(hueDeg: Double, chroma: Double, tone: Double): Int {
        if (tone <= 0.0) return 0xFF000000.toInt()
        if (tone >= 100.0) return 0xFFFFFFFF.toInt()
        // 先试满彩度；不行再二分。二分的上界是"半路也能接受"的那一档
        lchToArgb(Lch(tone, chroma, hueDeg))?.let { return it }
        var lo = 0.0
        var hi = chroma
        repeat(12) {
            val mid = (lo + hi) / 2.0
            if (lchToArgb(Lch(tone, mid, hueDeg)) != null) lo = mid else hi = mid
        }
        return lchToArgb(Lch(tone, lo, hueDeg)) ?: grayOfTone(tone)
    }

    fun argbOf(color: Int): Int = color

    fun argbToLch(argb: Int): Lch {
        val r = linear((argb shr 16 and 0xFF) / 255.0)
        val g = linear((argb shr 8 and 0xFF) / 255.0)
        val b = linear((argb and 0xFF) / 255.0)

        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

        val fx = pivot(x)
        val fy = pivot(y)
        val fz = pivot(z)

        val l = 116.0 * fy - 16.0
        val a = 500.0 * (fx - fy)
        val bb = 200.0 * (fy - fz)
        val c = sqrt(a * a + bb * bb)
        var h = Math.toDegrees(atan2(bb, a))
        if (h < 0) h += 360.0
        return Lch(l.coerceIn(0.0, 100.0), c, h)
    }

    /** @return ARGB；落不进 sRGB 时返回 null（调用方降彩度） */
    fun lchToArgb(lch: Lch): Int? {
        val rad = Math.toRadians(lch.h)
        val a = lch.c * cos(rad)
        val b = lch.c * sin(rad)

        val fy = (lch.l + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0

        val x = 0.95047 * unpivot(fx)
        val y = unpivot(fy)
        val z = 1.08883 * unpivot(fz)

        val rl = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val gl = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z

        val r = delinear(rl)
        val g = delinear(gl)
        val bi = delinear(bl)
        // 容差 0.0015：正好落在边界上的颜色会被 round 到 0 或 255，
        // 卡得太死会让大量本该可用的色被当成越界
        if (r < -0.0015 || r > 1.0015) return null
        if (g < -0.0015 || g > 1.0015) return null
        if (bi < -0.0015 || bi > 1.0015) return null

        val ri = (r * 255).toInt().coerceIn(0, 255)
        val gi = (g * 255).toInt().coerceIn(0, 255)
        val bri = (bi * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bri
    }

    /** 无彩色的某档灰。色域边界上所有彩度都失败时用它兜底 */
    private fun grayOfTone(tone: Double): Int {
        // L* → 相对亮度 Y → sRGB 灰
        val y = if (tone > 8.0) {
            ((tone + 16.0) / 116.0).pow(3.0)
        } else {
            tone / 903.3
        }
        val v = (delinear(y) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun linear(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun delinear(v: Double): Double =
        if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055

    private fun pivot(t: Double): Double =
        if (t > 216.0 / 24389.0) t.pow(1.0 / 3.0) else (24389.0 / 27.0 * t + 16.0) / 116.0

    private fun unpivot(t: Double): Double {
        val t3 = t * t * t
        return if (t3 > 216.0 / 24389.0) t3 else (116.0 * t - 16.0) / (24389.0 / 27.0)
    }

    // ---------------------------------------------------------------------
    // 种子
    // ---------------------------------------------------------------------

    /**
     * 从一堆像素里挑出种子色。
     *
     * 不做真正的 Median Cut：手表上取色是一次性的，但**在低端机上要够快**。
     * 这里按色相分 24 档、按明度分 3 档累加，取权重最高那一格的平均色。
     *
     * 评分有意偏向「中等明度 + 有彩度」：
     *   · 纯黑 / 纯白的像素权重打到 0 —— 几乎每张图都有一大片，它们不代表"主色"；
     *   · 彩度高的像素加权 —— 一张灰墙加一盆绿植，用户心里的主色是绿植；
     *   · 亮度中段加权 —— 深色图里那唯一一点亮色才是视觉重心。
     *
     * @param pixels ARGB 数组（不需要 alpha，忽略之）
     * @return ARGB 种子；全图无彩色时返回 null，由调用方退回默认种子
     */
    fun seedFromPixels(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val bins = HashMap<Int, Bin>()

        for (argb in pixels) {
            val alpha = (argb ushr 24) and 0xFF
            if (alpha < 128) continue
            val lch = argbToLch(argb)
            // 太暗 / 太亮 / 几乎无彩：不参与竞争
            if (lch.l < 12.0 || lch.l > 95.0) continue
            if (lch.c < 8.0) continue

            val hueBin = (lch.h / 15.0).toInt().coerceIn(0, 23)
            val lBin = when {
                lch.l < 40.0 -> 0
                lch.l < 70.0 -> 1
                else -> 2
            }
            val key = hueBin * 3 + lBin
            val w = 1.0 + lch.c / 50.0 + if (lBin == 1) 0.5 else 0.0
            val bin = bins.getOrPut(key) { Bin() }
            bin.sumC += lch.c * w
            bin.sumL += lch.l * w
            bin.sumS += lch.c * sin(Math.toRadians(lch.h)) * w
            bin.sumCo += lch.c * cos(Math.toRadians(lch.h)) * w
            bin.weight += w
        }

        val best = bins.values.maxByOrNull { it.weight } ?: return null
        if (best.weight <= 0.0) return null

        // 用加权平均的 (a, b) 反推色相，避免 0°/360° 分界处平均成 180°
        val a = best.sumCo / best.weight
        val b = best.sumS / best.weight
        var hue = Math.toDegrees(atan2(b, a))
        if (hue < 0) hue += 360.0
        val chroma = sqrt(a * a + b * b)
        val l = best.sumL / best.weight
        return lchToArgb(Lch(l, chroma, hue)) ?: null
    }

    private class Bin {
        var sumC = 0.0
        var sumL = 0.0
        var sumS = 0.0
        var sumCo = 0.0
        var weight = 0.0
    }

    // ---------------------------------------------------------------------
    // 与旧版「强调色」的对齐
    // ---------------------------------------------------------------------

    /**
     * 把一个旧强调色的主色当成种子。
     *
     * 升级上来的用户存的是 `accentColor = "blue"` 这种 id，不是种子色。
     * 迁移时用它算出一套 Monet 色板，观感不会突变太多（同一色相）、
     * 但比原来那 6 套手调色板更成体系。
     */
    fun seedOfLegacyPrimary(argb: Int): Int {
        val lch = argbToLch(argb)
        return lchToArgb(Lch(50.0, maxOf(lch.c, 48.0), lch.h)) ?: argb
    }

    /** 调试用：两个色板差别多大（色相角差，度） */
    fun hueDistance(a: Int, b: Int): Double {
        val d = abs(argbToLch(a).h - argbToLch(b).h)
        return if (d > 180) 360 - d else d
    }
}
