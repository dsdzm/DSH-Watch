package com.dshwatch.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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

/** 当前性能档，供组件决定是否播放动画 / 渲染多少内容 */
val LocalPerf = staticCompositionLocalOf { PerfProfiles.of(PerfMode.AUTO, com.dshwatch.domain.DeviceTier.MID) }

/**
 * 设备画像（屏幕尺寸、圆角安全边距等）。
 * 顶栏/底栏靠它做内收，避免内容被屏幕的物理 R 角切掉。
 */
val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfileDefaults.fallback }

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

    val topBarHeight: Dp = 38.dp
    val bottomBarHeight: Dp = 46.dp

    val touchMin: Dp = 40.dp
    val iconButton: Dp = 34.dp
    val buttonHeight: Dp = 38.dp

    // 圆角整体调大一档：手表屏幕小、信息密度高，圆一点更松弛，也和大 R 角屏幕更协调
    val cardRadius: Dp = 18.dp
    val bubbleRadius: Dp = 16.dp
    val chipHeight: Dp = 26.dp

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
    fontScale: Float = 0.92f,
    appLanguage: String = AppLanguage.SYSTEM,
    perf: PerfProfile = PerfProfiles.of(PerfMode.AUTO, com.dshwatch.domain.DeviceTier.MID),
    profile: DeviceProfile = DeviceProfileDefaults.fallback,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    // 主题预设 + 强调色：两者都变时才重算色板。
    // 动态取色优先级最高 —— 它本来就是「跟着壁纸走」，用户开了就该由系统决定。
    val colorScheme = remember(dark, dynamicColor, themePreset, accentColor) {
        val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        when {
            useDynamic && dark -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> ThemePresets.scheme(themePreset, accentColor, dark)
        }
    }

    val baseDensity = LocalDensity.current
    // 只缩放字号，不缩放 dp：dp 保持物理一致，避免按钮忽大忽小
    val scaledDensity = remember(baseDensity, fontScale) {
        Density(density = baseDensity.density, fontScale = baseDensity.fontScale * fontScale)
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
        LocalDeviceProfile provides profile,
        LocalStrings provides strings,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DshTypography,
            shapes = DshShapes,
            content = content,
        )
    }
}

val DshShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
)
