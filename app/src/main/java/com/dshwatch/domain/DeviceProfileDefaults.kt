package com.dshwatch.domain

/**
 * 兜底画像。
 * CompositionLocal 需要一个非空默认值，而真实值由 AppContainer 注入；
 * 在没有真实设备信息时（预览、单测）用这组保守参数。
 */
object DeviceProfileDefaults {
    val fallback = DeviceProfile(
        tier = DeviceTier.MID,
        screenWidthPx = 410,
        screenHeightPx = 502,
        screenWidthDp = 205,
        screenHeightDp = 251,
        densityDpi = 320,
        cornerInsetDp = 16,
        autoCornerInsetDp = 16,
        totalMemMb = 1024,
        cores = 4,
        isWatch = true,
        sdkInt = 28,
        compact = true,
        squarish = true,
    )
}
