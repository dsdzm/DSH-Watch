package com.dshwatch.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.roundToInt

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
 * 为什么默认不是真的高斯模糊：真模糊要么是 API 31+ 的 RenderEffect（整屏一次，
 * 低端表上代价可观），要么是低版本上离线跑的 RenderScript（见 [RsBlur]，
 * 只能糊背景图、糊不了色晕）。两者都不该成为默认路径 —— 默认画的是
 * 「模糊的观感」：多层低对比渐变 + 边缘光，GPU 填充成本约等于几次纯色圆角矩形，
 * 比真模糊低一个数量级，且在任何版本上完全一致。
 *
 * 用户可以在外观设置里打开真模糊（[LocalGlassBlur]）：那是显式选择，
 * 代价由用户自己承担。
 */
val LocalGlass = staticCompositionLocalOf { false }

/**
 * 玻璃是否叠在**真高斯模糊**的背景上。
 *
 * 默认 false：整屏实时模糊在低端表上代价可观，不该成为默认路径。
 * 用户在外观设置里打开后为 true，两件事同时发生：
 *
 *  1. 背景层变糊 —— 具体走哪条路按系统版本分：
 *     · API 31+：背景层整体挂 `Modifier.blur`，照片和色晕一起实时糊；
 *     · API 26~30：背景图在解码时用 RenderScript 离线糊一次
 *       （[RsBlur]），之后每帧只画一张已经糊好的图，运行时零成本。
 *  2. [com.dshwatch.ui.components.glassSurface] 的本体略微变薄 —— 不然那层
 *     0.68 的黑会把糊掉的底重新压成一块灰板，「通透」看不出任何变化。
 *
 * ## 低版本只糊照片，是有意的
 *
 * 色晕是几团半径 1.25 倍屏宽的低频渐变，本身就比任何高斯核都平滑；把它
 * 再糊一遍，肉眼和像素都看不出差别，却要为此每帧多一张离屏位图。
 * 所以低版本只处理有真实细节的那一层（背景图）。没有背景图时，
 * 打开开关仍然会让玻璃变薄 —— 只是不会有「照片化开」的效果。
 *
 * 注意它和 [LocalGlass] 是两件事：后者决定「卡片是不是玻璃」，
 * 前者只决定「玻璃底下糊不糊」。所以 M3E 材质下它永远是 false。
 */
val LocalGlassBlur = staticCompositionLocalOf { false }

/**
 * 背景层的模糊半径。
 *
 * 22dp：比弹窗那层（18dp）略重。弹窗要「读出层次」，卡片底下只需要
 * 「看不出细节」；表屏只有 200dp 出头，半径再大就糊成一片色块，
 * 玻璃反而失去「底下有东西」的感觉。
 */
internal val GLASS_BLUR_RADIUS = 22.dp

/**
 * 真模糊的最低系统版本。
 *
 * 26 = 本项目 minSdk，也是 RenderScript 兼容路径可用的下限
 * （`ScriptIntrinsicBlur` 自 API 17 起就有）。所以这个判断在当前
 * minSdk 下恒为 true —— 保留它是因为它是「功能可用性」的单一出处：
 * 设置页据此决定画开关还是画说明行，主题据此决定要不要糊。
 */
internal const val MIN_SDK_GLASS_BLUR = 26

/**
 * 实时模糊的最低系统版本。
 *
 * `Modifier.blur` 走 RenderEffect，API 31 之前是**空操作** —— 不报错、
 * 不生效。低版本必须换 [RsBlur] 那条离线路径，否则用户会点开、
 * 看不到变化，以为功能坏了。
 */
internal const val MIN_SDK_LIVE_BLUR = 31

/**
 * 离线模糊时背景图的读取宽度。
 *
 * 256 是三个约束的交点：
 *
 *  - 够大：糊完再放大到全屏，不会露出块状像素（120px 的虚化读法就会）；
 *  - 够小：ARGB_8888 只有 256 KB，两张（进 / 出）也不到 1MB，
 *    在 512MB 的表上安全；
 *  - 半径合适：视觉上要等效于屏幕上的 22dp，而 22dp ≈ 屏宽的 5.3%
 *    （412dp 的表），256 × 5.3% ≈ 13.6，正好落在 25 的硬上限以内，
 *    不需要二次模糊。
 */
internal const val BLUR_READ_WIDTH = 256

/**
 * 离屏模糊的半径，占图宽的比例。
 *
 * 用比例而不是 dp：背景图读进来是 256px，屏幕是 400 多 dp，两者之间
 * 差一个缩放。按比例算，模糊强度在任何屏幕、任何读取宽度下都保持
 * 「屏宽的 5.3%」，和 31+ 上 [GLASS_BLUR_RADIUS] 的观感对齐。
 */
internal const val BLUR_RADIUS_FRACTION = 0.053f


/**
 * 当前主题是不是深色。
 *
 * 为什么不能像以前那样用 `colorScheme.background.luminance() < 0.35` 现算：
 * **玻璃预设的 background 是全透明的**（见 ThemePresets，透明是玻璃能透出底色
 * 的前提），而 `luminance()` 只看 RGB、不看 alpha —— `Color.Transparent` 的
 * 亮度是 0，于是「浅色主题 + 玻璃」也会被算成深色。
 *
 * 后果不是「稍微偏暗」，是整条链路集体走错分支：
 *
 *   · 弹窗本体走深色分支（还叠了 1.22.3 加的 0.68 黑底）→ 浅色模式下弹窗是黑的
 *   · 页面底色走深色分支 → 浅色模式下叠一层压暗渐变
 *   · 用户气泡 / 雾面遮罩的明暗判断同样跟着错
 *
 * 用户看到的就是「浅色模式怎么也变黑了」。明暗是**主题的属性**，不该从
 * 一个可能透明的颜色里反推，所以由 [DshWatchTheme] 直接告诉下面。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 设备画像（屏幕尺寸、圆角安全边距等）。
 * 顶栏/底栏靠它做内收，避免内容被屏幕的物理 R 角切掉。
 */
val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfileDefaults.fallback }

/**
 * 当前生效的界面缩放值（1.0 = 标准）。
 *
 * 设置页需要显示「自动 · 0.64×」这样的文案，而自动值由根组合算好后
 * 才能确定；放在这里就不用每个页面各自再算一遍 [com.dshwatch.domain.UiScale.auto]。
 */
val LocalUiScale = staticCompositionLocalOf { 1f }

/**
 * 本机的推荐缩放值（按未缩放的屏幕宽度算出的自动值）。
 *
 * 设置页拿它当参照：界面大小超过它之后，内容会开始贴近甚至超出圆周。
 * 不做成硬上限 —— 宁可裁一点也要把字放大是用户的正当选择，
 * App 只负责把「推荐值是多少」说清楚。
 *
 * 放在这里而不是让页面自己算：页面里能拿到的 `LocalDeviceProfile` 已经是
 * 缩放后的（宽 322dp 而不是 206dp），拿它反推会算出 1.00 这种错值。
 */
val LocalUiScaleRecommended = staticCompositionLocalOf { 1f }

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
     * 莫奈取色的种子色（`#RRGGBB`）。空串 = 未选，走「预设 + 强调色」那套手调色板。
     *
     * 只在 [dynamicColor] 关闭时生效 —— 开着时种子由壁纸/背景图现场算出。
     */
    seedColor: String = "",
    /**
     * 外观风格（材质）：[com.dshwatch.domain.model.Appearances.M3E] 或 `GLASS`。
     *
     * 以前「玻璃」是 `themePreset = "glass"`，和颜色预设混在一个维度里 ——
     * 那样既选不出「深海 + 玻璃」，也会让「玻璃」看起来像一种配色。
     */
    appearance: String = com.dshwatch.domain.model.Appearances.M3E,
    /**
     * 界面体系：经典 / M3E（见 [com.dshwatch.domain.model.UiStyles]）。
     *
     * 与 [appearance] 是两个维度：appearance 决定**材质**（纯色/玻璃），
     * 这一项决定**用哪套组件搭页面**。
     */
    uiStyle: String = com.dshwatch.domain.model.UiStyles.CLASSIC,
    /**
     * 自定义背景图的文件名（见 [CustomBackground]）。空串 = 不用背景图。
     *
     * 背景图是**整个 App 的底**，不是某一页的装饰：玻璃主题下卡片透出来的
     * 就是它，M3E 主题下它从顶栏、空白处露出来。所以画在主题这一层。
     */
    customBackground: String = "",
    /** 背景图上那层遮罩的浓度。太淡看不清字，太浓等于没有图 */
    customBackgroundDim: Float = 0.45f,
    /** 背景是否虚化（读得粗 + 放大，见 [CustomBackground]） */
    customBackgroundFrost: Boolean = true,
    /**
     * 玻璃底是否叠真高斯模糊。
     *
     * 只在「材质 = 玻璃」时生效。31+ 走实时 RenderEffect，26~30 走
     * RenderScript 离线模糊背景图 —— 见 [LocalGlassBlur] 与 [RsBlur]。
     * 低于 [MIN_SDK_GLASS_BLUR]（当前等于 minSdk）时降级成原来的观感玻璃。
     */
    glassBlur: Boolean = false,
    /**
     * 界面整体缩放，1.0 = 标准。
     *
     * **这是唯一的缩放轴。** v1.15.21 及以前这里还并行一个 `fontScale`：
     * 缩放改 `Density.density`（dp 与 sp 一起缩），字号改 `Density.fontScale`
     * （只缩字、不动布局）。两者相乘之后，字号相对卡片的比例变成
     * `uiScale × fontScale`，字号被撑破、内容顶到圆周外 —— 用户看到的是
     * 「调完缩放再调字号就坏」。单独调任一个都正常，所以很难复现。
     *
     * 现在只留这一条：缩放密度本身，文字、卡片、间距、图标按同一比例走。
     * 比例恒定 ⇒ 数学上不可能出现「字比卡片大」。
     */
    uiScale: Float = 1f,
    appLanguage: String = AppLanguage.SYSTEM,
    perf: PerfProfile = PerfProfiles.of(PerfMode.AUTO, com.dshwatch.domain.DeviceTier.MID),
    profile: DeviceProfile = DeviceProfileDefaults.fallback,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 未知取值（含老存档空串）一律落回经典 —— 宁可少一个选项，也不要画不出来
    val uiStyleNow = com.dshwatch.domain.model.UiStyles.of(uiStyle)
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    // 主题预设 + 强调色：两者都变时才重算色板。
    //
    // 莫奈取色（v1.23.5 起）优先于这两者：它本来就是一整套色板，
    // 一旦生效，「预设 + 强调色」就无从叠加 —— 所以只在没有种子时才走老路。
    val explicitSeed = remember(seedColor) { ThemeSeeds.parseHex(seedColor) }

    /*
     * 跟随壁纸取色：种子在 IO 线程算，**首帧不等它**。
     *
     * 初值先给 null（= 用回退色相），算完再切换 —— 冷启动不会因为读一张图
     * 卡住第一帧。缓存放在 [SeedCache]，同一张背景图不会每次重组都重算。
     */
    val extractedSeed by produceState<Int?>(
        initialValue = SeedCache.remembered(customBackground),
        customBackground,
        dynamicColor,
    ) {
        if (!dynamicColor) return@produceState
        val r = SeedSource.resolve(context, customBackground)
        val seed = r.seed ?: ThemeSeeds.fallback.argb
        SeedCache.put(customBackground, seed, r.source)
        value = seed
    }

    /*
     * 最终种子。
     *
     * 「跟随壁纸」开着时用算出来的种子（哪怕没取到图也用回退色相 —— 总比
     * 界面颜色突然回到手调色板好理解）；关着时用用户挑的种子；
     * 两个都没有，才落回「预设 + 强调色」。
     */
    val monetSeed: Int? = when {
        dynamicColor -> extractedSeed ?: ThemeSeeds.fallback.argb
        explicitSeed != null -> explicitSeed
        else -> null
    }

    val baseScheme = remember(dark, monetSeed, themePreset, accentColor) {
        if (monetSeed != null) {
            MonetScheme.of(monetSeed, dark)
        } else {
            ThemePresets.scheme(themePreset, accentColor, dark)
        }
    }

    // 玻璃与莫奈取色互斥（见下方注释），预设色板仍然需要知道这条
    val useDynamic = monetSeed != null

    // 玻璃背景需要一块**可透的底**：预设里 bg 若是透明（玻璃预设的默认做法，
    // 让位给最底层的色晕），就得在这里补一块不透明的 surface；
    // 若是实色（OLED、樱花…），直接用它自己的背景色，一像素都不改。

    // 外观风格与色板分层之后，「是不是玻璃」只由 appearance 决定，
    // 预设只管颜色 —— 于是「玻璃 + 樱花」「玻璃 + 深海」都是合法组合。
    //
    // 动态取色例外：它整块替换色板，色晕的主副色失去依据，
    // 玻璃会变成「一块没有底色的灰玻璃」，所以此时强制退回 M3E。
    val glass = com.dshwatch.domain.model.Appearances.isGlass(appearance) && !useDynamic

    /*
     * 真模糊只在「玻璃 + 用户打开 + 系统支持」三者同时成立时生效。
     *
     * 少了任何一条都退回原来的观感玻璃，而不是「开了但什么都没发生」。
     *
     * 两条实现路径按版本分：
     *  · 31+：实时 RenderEffect，背景层整层挂 `Modifier.blur`，照片与色晕一起糊；
     *  · 26~30：`Modifier.blur` 是空操作，改用 RenderScript 在解码阶段把
     *    背景图糊一次（[RsBlur]）。色晕不处理 —— 它对低频渐变本就无感。
     */
    val blurBackdrop = glass && glassBlur && Build.VERSION.SDK_INT >= MIN_SDK_GLASS_BLUR
    val liveBlur = blurBackdrop && Build.VERSION.SDK_INT >= MIN_SDK_LIVE_BLUR
    val photoBlur = blurBackdrop && !liveBlur

    /*
     * 背景图在**主题这一层**解码，而不是每页各读一遍。
     *
     * produceState 的初值是 null：首帧先按原来的纯色/色晕画，读到图之后自动重组。
     * 这样冷启动不会因为「等一张照片」卡住首帧 —— 手表上首帧快慢的感知特别明显。
     *
     * key 里带 frost：切换虚化要重新解码（读的采样率不同），不是重新画一遍。
     * 带 photoBlur：低版本上开关一开，图要重新读一遍并糊掉 —— 只重画是没用的，
     * 糊过的像素已经不在手里了。
     */
    val backdrop by produceState<ImageBitmap?>(
        null,
        customBackground,
        customBackgroundFrost,
        photoBlur,
    ) {
        value = if (customBackground.isBlank()) {
            null
        } else {
            CustomBackground.load(
                context = context,
                name = customBackground,
                frost = customBackgroundFrost,
                blurRadiusFraction = if (photoBlur) BLUR_RADIUS_FRACTION else 0f,
            )
        }
    }

    /*
     * 有背景图时，把 `background` 换成**透明**。
     *
     * 各个页面（WatchScaffold 等）都会铺一层 `colorScheme.background` 当纸。
     * 那张纸不透明的话，画在它下面的照片一点都露不出来 —— 用户看到的是
     * 「设了背景但什么都没变」，只会以为功能坏了。
     *
     * 透明之后，页面自己的底色让位，照片从下面透上来；卡片仍然是 surface，
     * 该实的地方照样实。玻璃预设的 background 本来就是透明的，这一改对它没有影响。
     */
    val colorScheme = remember(baseScheme, backdrop) {
        if (backdrop != null && baseScheme.background.alpha > 0.05f) {
            baseScheme.copy(background = Color.Transparent)
        } else {
            baseScheme
        }
    }

    // 玻璃背景需要一块**可透的底**：预设里 bg 若是透明（玻璃预设的默认做法，
    // 让位给最底层的色晕），就得在这里补一块不透明的 surface；
    // 若是实色（OLED、樱花…），直接用它自己的背景色，一像素都不改。
    val glassBase = colorScheme.background

    /*
     * 浅色底判定直接取主题的明暗，不再看 background 的亮度。
     *
     * `Color.Transparent.luminance()` 是 0，而玻璃预设的 background 正是透明的 ——
     * 于是「浅色 + 玻璃」会被判成深色底：色晕按 1.0 的力度铺（白底上就是一片
     * 色块），中间还会压一道 0.10~0.30 的黑。浅色模式整个被压暗，根子在这里。
     */
    val lightBase = !dark

    val baseDensity = LocalDensity.current
    /*
     * 缩放密度本身，而不是只缩放字号。
     *
     * 只改 `fontScale` 时，字变小了、卡片和间距却不变 —— 界面看起来
     * 「字挤在一起、框还是那么大」，在高 dpi 小屏上仍然溢出。
     * 改 `density` 则让所有 dp 一起缩，布局才真的收得住。
     */
    val scaledDensity = remember(baseDensity, uiScale) {
        /*
         * 只乘 density，**不要再乘 fontScale**。
         *
         * sp 转 px 的公式是 `sp × density × fontScale`：density 一变，字号
         * 已经跟着等比变了。以前这里又乘了一次用户字号，等于把字号放大
         * 两遍 —— 1.15 缩放 × 1.30 字号时，字实际是 1.5 倍而卡片只有
         * 1.15 倍，文字必然溢出容器、顶到圆周外。
         *
         * 保留系统 fontScale（`baseDensity.fontScale`），那是无障碍设置，
         * 属于用户的全局偏好，不该被 App 覆盖掉。
         */
        Density(
            density = baseDensity.density * uiScale,
            fontScale = baseDensity.fontScale,
        )
    }

    // 密度变了，同一 px 宽度对应的 dp 也变了：profile 里的宽度要与新密度一致，
    // 否则圆屏弦长、列表预加载都会按旧宽度算（详见 UiScale.rescale）
    val scaledProfile = remember(profile, uiScale) {
        com.dshwatch.domain.UiScale.rescale(profile, uiScale)
    }

    // 推荐值要用**缩放前**的宽度算：profile 是 MainActivity 传进来的原始画像
    val uiScaleRecommended = remember(profile) {
        com.dshwatch.domain.UiScale.recommended(profile.screenWidthDp)
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
        LocalDeviceProfile provides scaledProfile,
        LocalUiScale provides uiScale,
        LocalUiScaleRecommended provides uiScaleRecommended,
        /*
         * 正文色必须在这里给一次，不能指望每个 `Text` 自己写。
         *
         * Compose 里 `Text` 不传 `color` 时读的是 `LocalContentColor`，而这个
         * 局部值的**默认实现是黑色**（`Color.Black`），不是主题色。整个 App
         * 没有用 `Surface` 包页面（玻璃 / Wear 都是自绘底色），于是没有任何
         * 一层把它换成 `onSurface` —— 浅色下看不出来（黑字本来就对），
         * 深色下这些字就留在黑色上：用户看到的是「深色模式里字体颜色没变」。
         *
         * 这类漏写在 UI 里有四百多处（`Text(text = …, style = …)`），逐个补
         * `color =` 既补不完、也会在下次新增页面时再漏。在根部按 Material 的
         * 语义给一次（正文色 = onSurface），所有未指定的文字随主题走。
         */
        LocalContentColor provides colorScheme.onSurface,
        LocalStrings provides strings,
        LocalGlass provides glass,
        LocalGlassBlur provides blurBackdrop,
        LocalDarkTheme provides dark,
        LocalUiStyle provides uiStyleNow,
    ) {
        com.dshwatch.ui.m3e.M3eMaterialTheme(
            enabled = uiStyleNow == com.dshwatch.domain.model.UiStyles.M3E,
            phone = colorScheme,
            typography = if (uiStyleNow == com.dshwatch.domain.model.UiStyles.M3E) {
                com.dshwatch.ui.m3e.M3eTypography
            } else {
                DshTypography
            },
            shapes = if (uiStyleNow == com.dshwatch.domain.model.UiStyles.M3E) {
                com.dshwatch.ui.m3e.M3eShapes
            } else {
                DshShapes
            },
        ) {
            // 有背景图时这一层也必须存在 —— M3E 主题下照片得有个地方画
            val photo = backdrop
            if (glass || photo != null) {
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
                Box(modifier = Modifier.fillMaxSize()) {
                    /*
                     * 背景层单独成一个节点，**不再和内容共用一层**。
                     *
                     * 「玻璃卡片底高斯模糊」只能这么做：Compose 没有 backdrop-filter，
                     * 卡片拿不到"它背后已经画了什么"。但能确定的是——卡片背后永远
                     * 只有这一层（照片或色晕），所以把这层糊掉，玻璃透过去的就是模糊的底。
                     *
                     * 以前背景和内容在同一个 Box 里（drawBehind + content），
                     * 想糊背景就必然连内容一起糊，所以这个开关只能靠重构成两层来实现。
                     *
                     * 31+ 在这一层上实时糊；低版本的图在解码时就糊好了（见 backdrop），
                     * 这里什么都不用做。
                     */
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (liveBlur) {
                                    // Unbounded：默认的 Rectangle 会在四边做一圈渐隐，
                                    // 满屏铺开时就是一圈「暗边」，看着像屏幕漏光。
                                    // 半径按屏宽的一小段取，和弹窗的 18dp 同一量级。
                                    Modifier.blur(
                                        radius = GLASS_BLUR_RADIUS,
                                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .drawBehind {
                            /*
                             * 有照片就只画照片 + 遮罩，画完直接返回。
                             *
                             * 不能「画完照片再叠色晕」：那两团色晕是给纯色底准备的
                             * 环境光，叠在照片上等于给用户的图蒙一层滤镜 ——
                             * 他要的是自己的图，不是「被 App 染过色的图」。
                             */
                            if (photo != null) {
                                drawBackdropPhoto(photo, dark, customBackgroundDim)
                                return@drawBehind
                            }
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
                    )
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

/**
 * 把背景图铺满（等比裁切），再叠一层遮罩。
 *
 * ## 为什么是「铺满 + 居中裁」而不是「完整显示」
 *
 * 背景图的比例和屏幕几乎不可能一致。完整显示会留出黑边，那两块边在圆屏上
 * 就是两道弧形缺口，比不放图还难看。裁切最多切掉边缘，主体仍在中间。
 *
 * ## 遮罩为什么必须存在
 *
 * 照片的明暗是随机的：一张雪景和一张夜景，同一套界面文字在上面必然有一种读不清。
 * 遮罩把「背景的亮度范围」压到可预期的区间内 —— 深色主题压黑、浅色主题压白，
 * 浓度由用户调（[customBackgroundDim]）。这也是参考应用的做法：
 * 毛玻璃卡片下面那层背景，永远是被压过一档的。
 */
private fun DrawScope.drawBackdropPhoto(photo: ImageBitmap, dark: Boolean, dim: Float) {
    val dst = coverRect(photo.width, photo.height, size.width, size.height) ?: return
    drawImage(
        image = photo,
        dstOffset = IntOffset(dst.left, dst.top),
        dstSize = IntSize(dst.width, dst.height),
        // Low 就是双线性：虚化路径下正好把 120px 的图放软，锐利路径下也够用
        filterQuality = FilterQuality.Low,
    )
    drawRect(
        color = if (dark) Color.Black else Color.White,
        alpha = dim.coerceIn(0f, MAX_BACKDROP_DIM),
    )
}

/** 遮罩浓度的上限。1.0 等于把照片完全盖掉，那还不如不放 */
internal const val MAX_BACKDROP_DIM = 0.9f

/** 目标矩形。抽成纯函数是为了能离线验证「铺满 / 居中 / 不留边」 */
internal data class CoverRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * 等比铺满（cover）的计算。
 *
 * 两条硬要求：
 *  1. **两边都必须 ≥ 屏幕**，否则某一侧会露出底色，圆屏上就是一道弧形缺口；
 *  2. 多出来的部分**均分到两侧**（居中裁），而不是全裁一边 ——
 *     照片的主体通常在中线附近。
 *
 * 返回 null 表示图或画布尺寸非法（解码失败时会是 0），调用方直接跳过绘制。
 */
internal fun coverRect(
    imageWidth: Int,
    imageHeight: Int,
    canvasWidth: Float,
    canvasHeight: Float,
): CoverRect? {
    if (imageWidth <= 0 || imageHeight <= 0) return null
    if (canvasWidth <= 0f || canvasHeight <= 0f) return null
    val scale = maxOf(canvasWidth / imageWidth, canvasHeight / imageHeight)
    /*
     * 用 roundToInt 而不是 toInt。
     *
     * `502f / 1000f * 1000` 在 float 里是 501.99997 —— 截断成 501，正好比画布矮 1px。
     * 那 1px 就是一条贯穿屏幕的底色缝，圆屏上还能看出弧线。
     * 「最小边刚好等于画布」是最常见的情况（比例一致、或原图就是屏幕尺寸），
     * 所以这个坑不该靠运气。
     */
    val w = (imageWidth * scale).roundToInt().coerceAtLeast(1)
    val h = (imageHeight * scale).roundToInt().coerceAtLeast(1)
    // 用负数偏移表达「裁掉多少」：left 为负表示左边被切掉了一部分
    return CoverRect(
        left = ((canvasWidth - w) / 2f).roundToInt(),
        top = ((canvasHeight - h) / 2f).roundToInt(),
        width = w,
        height = h,
    )
}
