package com.dshwatch.ui.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Typography as WearTypography
import com.dshwatch.ui.theme.WearTypography as PhoneWatchTypography

/**
 * 手表布局的外壳：Wear M3 主题 + `AppScaffold`。
 *
 * ## 为什么必须有这一层
 *
 * `androidx.wear.compose.material3` 的组件（Button / Card / ListHeader / ScreenScaffold /
 * TimeText）读的是**它自己那套** CompositionLocal（`wear.LocalColorScheme` /
 * `wear.LocalTypography` / `wear.LocalContentColor`），和手机 Material3 的
 * `LocalColorScheme` 是两个互不相干的 Local。
 *
 * 外层 `DshWatchTheme` 只提供了手机那套。如果不在这里再包一层 Wear 主题，
 * 手表组件会退回 Wear 库内置的默认配色（一套与 App 无关的紫灰），
 * 页面里的文字却仍是 App 配色 —— 一眼就能看出两块拼贴。
 *
 * ## 颜色怎么来
 *
 * 不另配一套色板：把手机 `ColorScheme` 的同名字段一一映射过去。
 * Wear 的 `ColorScheme` 没有 `surface`，只有 `surfaceContainer*` 三档，
 * 分别接手机的 surfaceContainerLow / Container / High。这样主题预设、
 * 动态取色、强调色全部照旧生效，手表布局只是换了一套组件。
 *
 * ## 为什么把 `AppScaffold` 放在这里而不是每页一个
 *
 * `AppScaffold` 持有 `ScaffoldState`，它管两件事：背景底色，以及**页面级的时间**
 * （`TimeText` 沿圆顶弧线绘制，进页淡入、退页淡出）。一页一个的话，
 * 每次跳页时间都会重播一遍淡入，看起来像闪了一下。
 */
@Composable
fun WearAppFrame(content: @Composable () -> Unit) {
    val phone = androidx.compose.material3.MaterialTheme.colorScheme
    val colors = remember(phone) {
        ColorScheme(
            primary = phone.primary,
            primaryDim = phone.primaryContainer,
            primaryContainer = phone.primaryContainer,
            onPrimary = phone.onPrimary,
            onPrimaryContainer = phone.onPrimaryContainer,
            secondary = phone.secondary,
            secondaryDim = phone.secondaryContainer,
            secondaryContainer = phone.secondaryContainer,
            onSecondary = phone.onSecondary,
            onSecondaryContainer = phone.onSecondaryContainer,
            tertiary = phone.tertiary,
            tertiaryDim = phone.tertiaryContainer,
            tertiaryContainer = phone.tertiaryContainer,
            onTertiary = phone.onTertiary,
            onTertiaryContainer = phone.onTertiaryContainer,
            surfaceContainerLow = phone.surfaceContainerLow,
            surfaceContainer = phone.surfaceContainer,
            surfaceContainerHigh = phone.surfaceContainerHigh,
            onSurface = phone.onSurface,
            onSurfaceVariant = phone.onSurfaceVariant,
            outline = phone.outline,
            outlineVariant = phone.outlineVariant,
            background = phone.background,
            onBackground = phone.onBackground,
            error = phone.error,
            errorDim = phone.errorContainer,
            errorContainer = phone.errorContainer,
            onError = phone.onError,
            onErrorContainer = phone.onErrorContainer,
        )
    }
    WearMaterialTheme(colorScheme = colors, typography = wearType()) {
        AppScaffold {
            content()
        }
    }
}

/**
 * 手机排版表 → Wear 排版表。
 *
 * Wear 的槽位比手机少几个（没有 displayMedium/headlineSmall），但多出
 * bodyExtraSmall 和 numeral*。这里只搬页面真正用到的那些，值取
 * [PhoneWatchTypography]（手表布局已经在用的那套 320dp→Wear 字号），
 * 两套布局的字号因此永远一致，不会出现「切了布局字突然变大」。
 */
@Composable
private fun wearType(): WearTypography {
    val t = PhoneWatchTypography
    return WearTypography(
        displaySmall = t.displaySmall,
        titleLarge = t.titleLarge,
        titleMedium = t.titleMedium,
        titleSmall = t.titleSmall,
        bodyLarge = t.bodyLarge,
        bodyMedium = t.bodyMedium,
        bodySmall = t.bodySmall,
        labelLarge = t.labelLarge,
        labelMedium = t.labelMedium,
        labelSmall = t.labelSmall,
        bodyExtraSmall = t.bodySmall.copy(fontSize = t.bodySmall.fontSize * 0.85f),
        numeralSmall = TextStyle(fontSize = t.labelSmall.fontSize),
    )
}
