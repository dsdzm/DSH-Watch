package com.dshwatch.ui.phone

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.staticCompositionLocalOf
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.PerfProfile
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.domain.model.UiStyles
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.i18n.stringsFor
import com.dshwatch.ui.theme.LocalDarkTheme
import com.dshwatch.ui.theme.LocalDeviceProfile
import com.dshwatch.ui.theme.LocalGlass
import com.dshwatch.ui.theme.LocalPerf
import com.dshwatch.ui.theme.LocalUiScale
import com.dshwatch.ui.theme.LocalUiScaleRecommended
import com.dshwatch.ui.theme.LocalUiStyle
import com.dshwatch.ui.theme.SeedCache
import com.dshwatch.ui.theme.SeedSource
import com.dshwatch.ui.theme.ThemeSeeds

/**
 * 是不是手机那套界面。
 *
 * 只有一个地方需要问：外观设置页。它同时服务两套界面（同一个 `Screen.SettingsGroup`），
 * 而手表专有的那几项（界面体系、玻璃材质、屏幕形状、整体缩放、看板娘）
 * 在手机上摆出来就是**调了没反应**的开关 —— 手机主题根本不读它们。
 *
 * 用 CompositionLocal 而不是往每个页面加参数，理由同 [LocalUiStyle]：
 * 读它的地方只有一处，写它的地方也只有一处。
 */
val LocalPhoneUi = staticCompositionLocalOf { false }

/**
 * 手机界面的主题。
 *
 * ## 和手表主题是什么关系：颜色共享，骨架无关
 *
 * 手表那套 `DshWatchTheme` 的核心是「在 200dp 的圆里塞内容」：界面缩放、
 * 圆角安全边距、玻璃材质、M3E 与经典两套组件 —— 每一项都是为小尺寸和圆屏
 * 推导出来的。把它套到 411dp 宽的手机上，得到的是「一个被放大的手表」。
 *
 * 所以手机版**不读**这些：`uiScale` / `screenShape` / `uiStyle` / `appearance` /
 * `customBackground`。字号阶梯、圆角刻度、页面骨架全部按 Material 3 标准来。
 *
 * ## 但颜色必须读
 *
 * 明暗、跟随壁纸取色、种子色是用户对「这个 App 长什么样」的**全局偏好**，
 * 不是手表专有物。之前手机主题只认「系统动态取色 + 写死的 baseline」，
 * 于是设置里的种子色和低版本上的取色在这套界面上**一点效果都没有** ——
 * 点一下颜色，屏幕纹丝不动。现在三级回落（见 [PhonePalette]）：
 *
 * ```
 * 跟随壁纸（默认开） → 种子色（用户挑的） → M3 baseline
 * ```
 *
 * 种子在 IO 线程算，首帧不等它：初值给缓存，算完再切换。读一张壁纸缩略图
 * 在低端机上要几十毫秒，卡在首帧上就是「点开图标白一下」。
 */
@Composable
fun PhoneTheme(
    settings: AppSettings,
    perf: PerfProfile,
    profile: DeviceProfile,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val context = LocalContext.current

    /*
     * 壁纸种子。
     *
     * key 里带 customBackground：用户换了自己的背景图，种子要跟着重算 ——
     * 手机版的取色和手表共用 [SeedSource]，顺序也一致（自定义背景优先于壁纸）。
     */
    val wallpaperSeed by produceState<Int?>(
        initialValue = SeedCache.remembered(settings.customBackground),
        settings.dynamicColor,
        settings.customBackground,
    ) {
        if (!settings.dynamicColor) return@produceState
        val r = SeedSource.resolve(context, settings.customBackground)
        val seed = r.seed ?: ThemeSeeds.fallback.argb
        SeedCache.put(settings.customBackground, seed, r.source)
        value = seed
    }

    val explicitSeed = remember(settings.seedColor) { ThemeSeeds.parseHex(settings.seedColor) }

    /*
     * 「跟随壁纸」开着时用算出来的种子（取不到就用兜底色相 —— 总比界面颜色
     * 突然跳回 baseline 紫好理解）；关着时用用户挑的；两个都没有才落回 baseline。
     */
    val seed = when {
        settings.dynamicColor -> wallpaperSeed ?: ThemeSeeds.fallback.argb
        else -> explicitSeed
    }

    val scheme = remember(dark, seed) { PhonePalette.of(dark, seed) }

    /*
     * 界面缩放对手机版固定为 1。
     *
     * 手表的 uiScale 是「屏幕太窄、必须整体缩」的补偿；手机不需要，
     * 而且用户如果为了手表调过 0.6×，手机上再套一遍就是「所有东西都变小」。
     * 但 LocalUiScale 仍然要提供 —— 若干共用的对话框组件会读它。
     */
    val configuration = LocalConfiguration.current
    val locales = remember(configuration.locales) {
        val list = configuration.locales
        (0 until list.size()).map { list[it] }
    }
    val strings = remember(settings.appLanguage, locales) {
        stringsFor(settings.appLanguage, locales)
    }

    CompositionLocalProvider(
        LocalPerf provides perf,
        LocalDeviceProfile provides profile,
        LocalUiScale provides 1f,
        LocalUiScaleRecommended provides 1f,
        LocalStrings provides strings,
        // 手机版不提供「玻璃」与「M3E」：这两项是手表界面的材质与组件体系，
        // 手机上它们只会在少数共用组件里生效，让界面半玻璃半纯色，比不做更糟
        LocalGlass provides false,
        // 挑文件的开关与材质无关，手机版照常提供
        com.dshwatch.ui.theme.LocalBuiltinFilePicker provides settings.builtinFilePicker,
        LocalDarkTheme provides dark,
        LocalUiStyle provides UiStyles.CLASSIC,
        LocalPhoneUi provides true,
        // 同手表主题：`Text` 的默认色是 `LocalContentColor`，而它是黑。
        // 不在这里换成 onSurface，深色模式下没写 color 的字就一直是黑的。
        LocalContentColor provides scheme.onSurface,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PhoneTypography,
            shapes = PhoneShapes,
            content = content,
        )
    }
}

/** 手机版字号：Compose 的默认阶梯（Roboto 15 档），中文在这个尺寸下刚好 */
private val PhoneTypography: Typography = Typography()

/** 圆角用默认阶梯：4 / 8 / 12 / 16 / 28 —— 这是 M3 的标准刻度，不需要改 */
private val PhoneShapes: Shapes = Shapes()
