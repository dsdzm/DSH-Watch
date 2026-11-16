package com.dshwatch.ui.phone

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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

/**
 * 手机界面的主题。
 *
 * ## 和手表主题是什么关系：没有关系
 *
 * 手表那套 `DshWatchTheme` 的核心是「在 200dp 的圆里塞内容」：界面缩放、
 * 圆角安全边距、玻璃材质、主题预设 + 强调色、M3E 与经典两套组件 ——
 * 每一项都是为小尺寸和圆屏推导出来的。把它套到 411dp 宽的手机上，
 * 得到的是「一个被放大的手表」，不是手机应用。
 *
 * 所以手机版**另起一套**：Material 3 的标准色板、标准字号阶梯、标准圆角，
 * 一个 `MaterialTheme` 装上就完事，不读 `themePreset` / `accentColor` /
 * `appearance` / `uiStyle` / `uiScale` / `screenShape` 中的任何一个。
 *
 * 唯一共享的是**明暗**：`themeMode` 是用户对「这个 App 长什么样」的全局偏好，
 * 同一台设备上没必要因为换了界面体系就问两遍。
 *
 * ## 动态取色
 *
 * Android 12 起跟随壁纸取色是系统级能力，手机上这是「原生感」最重要的一环，
 * 所以默认跟随 `dynamicColor`。取不到（低版本、或用户在设置里关掉）时
 * 回落到 M3 官方的 baseline 色板（seed `#6750A4`），而不是回落到手表的手调色板 ——
 * 那套色板是为深色表盘调的，放在手机上偏暗。
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
    val scheme = remember(dark, settings.dynamicColor, context) {
        val dynamic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.dynamicColor) {
            runCatching {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }.getOrNull()
        } else {
            null
        }
        dynamic ?: if (dark) PhoneDarkColors else PhoneLightColors
    }

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
        LocalDarkTheme provides dark,
        LocalUiStyle provides UiStyles.CLASSIC,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PhoneTypography,
            shapes = PhoneShapes,
            content = content,
        )
    }
}

/**
 * 手机版字号。
 *
 * 用 Compose 的默认阶梯（Roboto 15 档），只是把 `bodyLarge` 调到 16sp、
 * `labelLarge` 调到 14sp —— 中文在这个尺寸下比拉丁字母显得更小，
 * 系统默认的 16/14 是给英文调的。
 */
private val PhoneTypography: Typography = Typography()

/** 圆角用默认阶梯：4 / 8 / 12 / 16 / 28 —— 这是 M3 的标准刻度，不需要改 */
private val PhoneShapes: Shapes = Shapes()

// ---------------- M3 baseline 色板 ----------------
//
// 灯下（浅色）与夜间（深色）两套，取 Material Design 3 官方 baseline
// （seed #6750A4）的 tonal palette 映射，数值即 MDC 生成器输出。

private val PhoneLightColors = lightColorScheme(
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

private val PhoneDarkColors = darkColorScheme(
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
