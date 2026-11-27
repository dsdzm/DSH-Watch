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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
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
 * 为什么默认不是真的高斯模糊：真模糊要在解码阶段算一次（见 [SoftBlur]），
 * 有几十毫秒的等待。默认画的是「模糊的观感」：多层低对比渐变 + 边缘光，
 * GPU 填充成本约等于几次纯色圆角矩形，比真模糊低一个数量级，
 * 且在任何版本上完全一致。
 *
 * 用户可以在外观设置里打开真模糊（[LocalGlassBlur]）：那是显式选择，
 * 代价由用户自己承担。
 */
val LocalGlass = staticCompositionLocalOf { false }

/**
 * 玻璃是否叠在**真高斯模糊**的背景上。
 *
 * 默认 false：真模糊要在解码阶段算一次，不该成为默认路径。
 * 用户在外观设置里打开后为 true，两件事同时发生：
 *
 *  1. 卡片按自己的屏幕坐标，从一张**重度模糊**的背景副本里采一块画在纱底下 ——
 *     这就是「毛玻璃」本身（见 [LocalFrostPhoto]）；
 *  2. [com.dshwatch.ui.components.glassSurface] 的本体略微变薄 —— 不然那层
 *     0.68 的黑会把糊掉的底重新压成一块灰板，「通透」看不出任何变化。
 *
 * ## 只糊背景图，不糊色晕
 *
 * 色晕是几团半径 1.25 倍屏宽的低频渐变，本身就比任何高斯核都平滑；把它
 * 再糊一遍，肉眼和像素都看不出差别，却要为此多算一遍全屏。所以只处理有
 * 真实细节的那一层（背景图）。没有背景图时，打开开关仍然会让玻璃变薄、
 * 颗粒变细 —— 只是不会有「照片化开」的效果。
 *
 * 注意它和 [LocalGlass] 是两件事：后者决定「卡片是不是玻璃」，
 * 前者只决定「玻璃底下糊不糊」。所以 M3E 材质下它永远是 false。
 */
val LocalGlassBlur = staticCompositionLocalOf { false }

/**
 * 卡片「压暗」档位，0~1，0.5 = 标准。
 *
 * 由设置里的滑块给，[com.dshwatch.ui.components.glassSurface] 读它决定
 * 本体那层纱有多厚。之所以走 CompositionLocal 而不是给每个调用点加参数：
 * 这是**全局观感**，不该由七十多个调用点各自表态。
 */
val LocalGlassDim = staticCompositionLocalOf { 0.5f }

/**
 * 卡片自己磨砂时取样的那张图 —— 背景图的**重度模糊**副本。
 *
 * ## 为什么卡片需要自己的一份，而不是复用背景层
 *
 * 关掉「背景模糊」之后，背景是**清晰**的照片；卡片如果只是半透明，
 * 用户看到的就是「照片原样从卡片里穿过去」—— 也就是「卡片像透明了」。
 * 真毛玻璃的行为正相反：背景清晰、玻璃底下糊。
 *
 * Compose 没有 backdrop-filter，卡片拿不到「我背后画了什么」，所以只能
 * 预先备一张糊得比背景更狠的小图（[FROST_READ_WIDTH] 宽），卡片按自己的
 * 屏幕坐标去**采样对应的一块**画进来（见 Glass.kt 的 drawFrostSlice）。
 *
 * 这样每张卡每帧只多一次 `drawImage`，没有离屏层、没有逐帧卷积 ——
 * 在 512MB 的表上这是唯一可行的做法。
 */
val LocalFrostPhoto = staticCompositionLocalOf<ImageBitmap?> { null }

/**
 * 背景层（照片）在屏幕上的绘制尺寸，px。
 *
 * 卡片要按它算出「满屏铺开的那张图，在我这个位置对应哪一块」。
 * 取的是窗口尺寸而不是布局实测值：差几个像素在重度模糊的图上完全看不出来，
 * 而省掉一次「先测量再重组」的启动开销。
 */
val LocalBackdropSize = staticCompositionLocalOf { IntSize.Zero }

/** 背景照片上的遮罩浓度，卡片采样时要补上同一层，否则卡片底下会亮一块 */
val LocalBackdropDim = staticCompositionLocalOf { 0f }

/**
 * 卡片背后那张磨砂图的平均亮度（0~1）。
 *
 * 玻璃卡片的压暗不能是一个定值：同一块 0.5 的黑纱，压在黄昏的照片上刚好，
 * 压在深夜的照片上就是「黑上加黑」，整块卡沉进背景里看不见形状 ——
 * 用户的原话是「背景颜色比较暗的情况下显得不是很透」。
 *
 * 所以这里把**底有多亮**量出来交给 [com.dshwatch.ui.components.glassSurface]：
 * 底暗就少压一点，让糊影透上来；底亮就补一点，白字才站得住。
 * 描边也用它反向补偿 —— 暗底上那条边要略亮一档才看得出形状。
 *
 * 默认 0.25 是深色壁纸的大致中位数：首帧还没有图时先按偏暗处理，
 * 不会出现「先亮一下再变暗」的跳变。
 */
val LocalBackdropLuma = staticCompositionLocalOf { 0.25f }

/**
 * 设置里的「用内置文件浏览器」。
 *
 * 挑文件的入口有九处（附件、背景、插件、归档、皮肤、配图……），它们分散在
 * 各种层级的 Composable 里，有的连 settings 都拿不到。把这个开关放进
 * CompositionLocal，`rememberFilePicking()` 就能就地读，不必给每层函数
 * 加一个透传参数 —— 那种改法只要漏一层就编不过，加新入口时又要再改一遍。
 *
 * 默认 true：`ACTION_OPEN_DOCUMENT` 依赖设备上真的有文件管理器，
 * 一部分手表 ROM 把它裁掉了，出厂默认走内置更不容易「点了没反应」。
 */
val LocalBuiltinFilePicker = staticCompositionLocalOf { true }

/**
 * 背景模糊的读取宽度。
 *
 * 384 ≈ 屏宽的 0.93 倍，铺满时几乎 1:1，糊多少像素在屏幕上就是多少像素 ——
 * 这是「读多大」和「糊多准」唯一能不打架的尺寸。
 *
 * 读得太小（120）只会得到一团马赛克；读得太大（1080）在手表上要多算三四倍
 * 像素（[SoftBlur] 的耗时与像素数成正比）却看不出区别。
 */
internal const val BG_BLUR_READ_WIDTH = 384

/**
 * 背景模糊在 0.5 档时的半径，占**图宽**的比例。
 *
 * 0.055 × 384 ≈ 21px，摊到满屏约等于屏宽的 5.5% —— 这个量级刚好把照片的
 * 细节（发丝、文字、纹理）化开，同时保住「这是同一张图」的构图。
 * 再重就成了一块色斑，用户会说「背景图没了」。
 */
internal const val BG_RADIUS_FRACTION = 0.055f

/**
 * 背景模糊强度（0~1）→ 半径比例。
 *
 * 0 → 0.0165（几乎不糊，但和「关闭」仍然有别），1 → 0.132（图宽 13%，
 * 只剩大块明暗）。中间是线性：滑块的每一格都对应肉眼可分辨的差异。
 */
internal fun bgRadiusFraction(strength: Float): Float =
    (BG_RADIUS_FRACTION * (0.30f + 1.40f * strength.coerceIn(0f, 1f)))
        .coerceIn(0.008f, 0.14f)

/**
 * 卡片磨砂取样图的读取宽度。
 *
 * 比背景层（512）小：卡片里那块玻璃本来就要「糊到没有细节」，320 糊完再放大
 * 已经足够，内存也只要 400KB —— 几十张卡共用同一张，不随卡片数增长。
 */
internal const val FROST_READ_WIDTH = 320

/**
 * 卡片磨砂强度（0~1）→ 半径比例。
 *
 * **比背景重得多，这是磨砂玻璃的定义**：同一档位下卡片总比背景糊，
 * 背景只是「虚一点」，卡片才是「毛」的。0.5 档是 0.185（约 3.4 倍）。
 *
 * ## 下限从 0.07 收到 0.012
 *
 * 0.07 × 320 ≈ 22px，铺满屏幕后等效 80px 的模糊 —— 滑块拉到最左，
 * 卡片仍然是一团糊影。用户要的是「调到最低能看清底下是什么」：
 * 0.012 × 320 ≈ 4px，只剩一层柔光，照片的轮廓、明暗都在。
 *
 * 曲线用 `s^0.62`：低段掉得快（0.2 档就已经很清），高段还能保住
 * 0.28（图宽 28%，一块纯色柔光）。这样滑块前 1/3 是「清晰 → 轻磨砂」，
 * 后 1/3 才是「重磨砂」，比线性更好用。
 *
 * 0.5 档刻意落回 0.185 附近 —— 默认观感不变，老用户升级不会觉得「怎么变了」。
 */
internal fun glassRadiusFraction(strength: Float): Float {
    val s = strength.coerceIn(0f, 1f)
    return (0.012f + 0.268f * s.pow(0.62f)).coerceIn(0f, 0.34f)
}

/**
 * 一张图有多亮（0~1），按 24×24 的稀疏采样估。
 *
 * 只用来决定「这块底该压多黑」，不需要精确：整张图几十万个像素扫一遍
 * 在手表上要十几毫秒，而 24 行 × 24 点的误差早就小于人眼对明暗的分辨。
 *
 * `getPixels` 一次取一整行再挑列，比逐点调用少 24 倍的 JNI 往返。
 * 非 Android 位图（单测里的 `ImageBitmap` 实现）直接给回退值 —— 这个值
 * 只影响观感，不该让主题因为「量不出来」而崩。
 */
internal suspend fun ImageBitmap.averageLuminanceOr(fallback: Float): Float =
    withContext(Dispatchers.Default) {
        runCatching {
            val bitmap = asAndroidBitmap()
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return@runCatching fallback
            val rows = 24.coerceAtMost(h)
            val cols = 24.coerceAtMost(w)
            val row = IntArray(w)
            var sum = 0.0
            var n = 0
            for (i in 0 until rows) {
                val y = (i * (h - 1) / (rows - 1).coerceAtLeast(1))
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (j in 0 until cols) {
                    val x = j * (w - 1) / (cols - 1).coerceAtLeast(1)
                    val p = row[x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    // Rec.601 的权重：手表上的照片多是暖色，用 709 会把黄昏压得过暗
                    sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                    n++
                }
            }
            if (n == 0) fallback else (sum / n).toFloat().coerceIn(0f, 1f)
        }.getOrDefault(fallback)
    }

/**
 * 模糊功能的最低系统版本。
 *
 * 26 = 本项目 minSdk。现在模糊走纯 Kotlin（[SoftBlur]），没有任何系统 API 依赖，
 * 所以这个判断恒为 true —— 保留它是「功能可用性」的单一出处：设置页据此决定
 * 画开关还是画说明行。
 */
internal const val MIN_SDK_GLASS_BLUR = 26

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
     * 背景层（照片与色晕）是否做真高斯模糊。
     *
     * 与 [glassBlur] 是**两件事**：
     *
     *  · 这一项是「壁纸本身柔不柔」，任何材质都生效（M3E 的不透明卡片下也有背景）；
     *  · [glassBlur] 是「卡片这块玻璃磨不磨砂」—— 奶白、颗粒、更亮的描边。
     *
     * 上一版把两者绑在同一根线上（开玻璃模糊就整层糊掉），于是想「壁纸清晰的
     * 清玻璃」根本调不出来。拆开之后：关掉这一项、只开玻璃磨砂，得到的是
     * 一张清晰的壁纸配一块奶玻璃。
     */
    backgroundBlur: Boolean = true,
    /**
     * 卡片这块玻璃是否磨砂。
     *
     * 只在「材质 = 玻璃」时生效。它决定的是**卡片底下那块背景糊不糊**：
     * 卡片按自己的屏幕坐标，从一张重度模糊的副本里采一块画在纱底下
     * （见 [LocalFrostPhoto]）。所以「壁纸清晰、玻璃发毛」是成立的。
     */
    glassBlur: Boolean = false,
    /**
     * 设置里的「用内置文件浏览器」。
     *
     * 只做一件事：通过 [LocalBuiltinFilePicker] 告诉 [com.dshwatch.ui.screens.files.rememberFilePicking]
     * 该走哪条路。不影响任何绘制。
     */
    builtinFilePicker: Boolean = true,
    /**
     * 背景模糊强度，0~1，0.5 = 标准。
     *
     * 只作用于背景层，见 [com.dshwatch.domain.model.AppSettings.blurStrength]。
     * 卡片那一条是 [glassBlurStrength] —— 两者分开，才能「背景只是虚一点、
     * 卡片彻底毛掉」。
     */
    blurStrength: Float = 0.5f,
    /**
     * 卡片玻璃的模糊强度，0~1，0.5 = 标准。
     *
     * 见 [com.dshwatch.domain.model.AppSettings.glassBlurStrength]。
     */
    glassBlurStrength: Float = 0.5f,
    /**
     * 卡片压暗档位，0~1，0.5 = 标准。
     *
     * 只在玻璃材质下有意义。见 [com.dshwatch.domain.model.AppSettings.cardDim]。
     */
    cardDim: Float = 0.5f,
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
     * 三条互相独立的轴，全部由用户给：
     *
     *  · 背景糊不糊 → [backgroundBlur]，与材质无关；
     *  · 背景糊多狠 → [blurStrength]；
     *  · 玻璃本身糊多狠 → [glassBlurStrength]（只在玻璃材质 + 磨砂开启时有意义）。
     *
     * 前两条与第三条是**两张不同的图**：背景层用 512px 副本，卡片用 320px、
     * 糊得更重的副本。这样「背景只是虚一点、卡片彻底毛掉」的层次才成立 ——
     * 如果两者共用一张，卡片和背景永远一样糊，玻璃就没了厚度。
     *
     * 实现只有一条路径：[SoftBlur]（纯 Kotlin 盒式高斯）在解码阶段算一次。
     * 曾经用过 `Modifier.blur`（API 31+）与 RenderScript（26~30）两条"更快"的路，
     * 但它们在真机上都会**静默退回原图** —— 用户连着两个版本报「开了没变化」，
     * 根子就在这里。功能宁可慢几十毫秒，也不能靠设备运气。
     */
    val glassFrost = glass && glassBlur
    val blurBackdrop = backgroundBlur
    // 滑块取值先夹一遍：存档里可能是 1.7 或者手工改坏的负数
    val bgStrength = blurStrength.coerceIn(0f, 1f)
    val glassStrength = glassBlurStrength.coerceIn(0f, 1f)
    val dim = cardDim.coerceIn(0f, 1f)

    /*
     * 背景图在**主题这一层**解码，而不是每页各读一遍。
     *
     * produceState 的初值是 null：首帧先按原来的纯色/色晕画，读到图之后自动重组。
     * 这样冷启动不会因为「等一张照片」卡住首帧 —— 手表上首帧快慢的感知特别明显。
     *
     * key 里必须带「糊不糊」和「糊多狠」：
     *  · 带 frost：切换虚化要重新解码（读的采样率不同），不是重新画一遍；
     *  · 带 blurBackdrop / bgStrength：糊过的像素已经不在手里了，只重画没用。
     */
    val backdrop by produceState<ImageBitmap?>(
        null,
        customBackground,
        customBackgroundFrost,
        blurBackdrop,
        bgStrength,
    ) {
        value = if (customBackground.isBlank()) {
            null
        } else {
            CustomBackground.load(
                context = context,
                name = customBackground,
                frost = customBackgroundFrost,
                blurRadiusFraction = if (blurBackdrop) bgRadiusFraction(bgStrength) else 0f,
                readWidth = if (blurBackdrop) BG_BLUR_READ_WIDTH else 0,
            )
        }
    }

    /*
     * 卡片磨砂的取样图。
     *
     * 这是「卡片自己糊」的唯一来源：它比背景层糊得更重，卡片按自己的屏幕坐标
     * 采一块画进玻璃底下（见 [LocalFrostPhoto]）。
     *
     * 只在「玻璃 + 磨砂」时才解码 —— 清玻璃本来就要透出清晰背景，糊一张反而错。
     * 与 backgroundBlur 无关：这正是「背景清晰、玻璃发毛」能成立的原因。
     */
    val frostPhoto by produceState<ImageBitmap?>(
        null,
        customBackground,
        glassFrost,
        glassStrength,
    ) {
        value = if (!glassFrost || customBackground.isBlank()) {
            null
        } else {
            CustomBackground.load(
                context = context,
                name = customBackground,
                frost = true,
                blurRadiusFraction = glassRadiusFraction(glassStrength),
                readWidth = FROST_READ_WIDTH,
            )
        }
    }

    /*
     * 这张磨砂图有多亮 —— 卡片压暗与描边的联动依据（见 [LocalBackdropLuma]）。
     *
     * 量的是**糊过之后**的那张：卡片实际看到的就是它，拿原图算会在
     * 「照片一半黑一半白」时得出一个谁都不像的平均值。
     *
     * 稀疏采样，几十微秒级，放在后台线程；量不出来时给 0.25 这个偏暗的
     * 默认值 —— 深色玻璃预设是最常见的使用场景，宁可一开始压得保守一点。
     */
    val lumaPhoto = frostPhoto ?: backdrop
    val frostLuma by produceState(0.25f, lumaPhoto) {
        value = lumaPhoto?.averageLuminanceOr(0.25f) ?: 0.25f
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
        LocalGlassBlur provides glassFrost,
        LocalGlassDim provides dim,
        LocalFrostPhoto provides frostPhoto,
        LocalBackdropLuma provides frostLuma,
        LocalBuiltinFilePicker provides builtinFilePicker,
        LocalBackdropDim provides customBackgroundDim.coerceIn(0f, MAX_BACKDROP_DIM),
        LocalBackdropSize provides remember(scaledProfile) {
            IntSize(scaledProfile.screenWidthPx, scaledProfile.screenHeightPx)
        },
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
                     * 这一层**不挂任何实时模糊**：糊发生在解码阶段（见 backdrop），
                     * 画出来的已经是一张糊好的图。`Modifier.blur` 曾经用在这里，
                     * 但它在部分手表 ROM 上会被静默忽略 —— 开关打开、画面不动。
                     */
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
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
