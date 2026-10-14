package com.dshwatch.domain

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import androidx.annotation.VisibleForTesting
import android.view.WindowManager

/**
 * 设备画像。
 *
 * 为什么要单独做一层：手表屏幕的**物理圆角（R 角）系统不会通过 API 告诉你**。
 * DisplayCutout 只报告刘海/挖孔，圆角是硬件遮罩，App 拿不到半径。
 * 于是只能：按屏幕尺寸估算一个安全边距 + 允许用户在设置里手动微调。
 *
 * 几何依据（很重要，决定了为什么是「先加纵向内边距，再加一点点横向」）：
 *  设圆角半径为 R，内容盒从 y=0 开始。位于 (x, y) 的点被遮住的判定是
 *      (x-R)² + (y-R)² > R²  且  x<R 且 y<R
 *  最坏情况是 y=0 那一行，此时需要 x ≥ R —— 也就是说如果内容真的贴着屏幕最顶边，
 *  横向要留出整整一个 R 才不会被切。
 *  但如果内容上边距留了 p（y 从 p 开始），则需要的横向内边距降为
 *      R - sqrt(R² - (R-p)²)
 *  R=25dp、p=6dp 时只需 8.8dp。
 *
 * 所以正确做法是：**先给顶栏/底栏留 6dp 纵向内边距，再给一个较小的横向内边距**，
 * 而不是粗暴地把整条栏横向缩进一个 R（那样在 205dp 宽的表盘上会白白浪费 1/4 宽度）。
 */
data class DeviceProfile(
    val tier: DeviceTier,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val densityDpi: Int,
    /** 最终生效的圆角安全边距（dp） */
    val cornerInsetDp: Int,
    /** 自动估算值，用于界面上显示「自动(14dp)」 */
    val autoCornerInsetDp: Int,
    val totalMemMb: Int,
    val cores: Int,
    val isWatch: Boolean,
    val sdkInt: Int,
    /** 屏幕很窄，需要进一步压缩组件尺寸 */
    val compact: Boolean,
    /** 方形/矩形表盘（宽高比接近） */
    val squarish: Boolean,
) {
    val summary: String
        get() = "${screenWidthPx}×${screenHeightPx} @${densityDpi}dpi · ${screenWidthDp}×${screenHeightDp}dp"
}

object DeviceProfiler {

    fun detect(context: Context, overrideInsetDp: Int = -1): DeviceProfile {
        val metrics = screenMetrics(context)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val cores = Runtime.getRuntime().availableProcessors()
        val totalMemMb = memoryInfo(am)
        val isWatch = context.packageManager.hasSystemFeature("android.hardware.type.watch")
        val lowRam = am?.isLowRamDevice ?: false

        val tier = PerfDetector.classify(
            isLowRamDevice = lowRam,
            totalMemMb = totalMemMb,
            cores = cores,
        )

        val autoInset = estimateCornerInsetDp(metrics.widthDp)
        val inset = if (overrideInsetDp >= 0) overrideInsetDp else autoInset

        val ratio = if (metrics.heightPx == 0) 1f else metrics.widthPx.toFloat() / metrics.heightPx

        return DeviceProfile(
            tier = tier,
            screenWidthPx = metrics.widthPx,
            screenHeightPx = metrics.heightPx,
            screenWidthDp = metrics.widthDp,
            screenHeightDp = metrics.heightDp,
            densityDpi = metrics.densityDpi,
            cornerInsetDp = inset,
            autoCornerInsetDp = autoInset,
            totalMemMb = totalMemMb,
            cores = cores,
            isWatch = isWatch,
            sdkInt = Build.VERSION.SDK_INT,
            compact = metrics.widthDp in 1..220,
            squarish = ratio > 0.8f,
        )
    }

    /**
     * 圆角安全边距估算。
     *
     * 这类方形手表的 R 角通常在屏宽的 8%~14% 之间。取 8% 作为横向内边距的基准，
     * 并夹在 [8, 20]；实际生效时还会配合顶栏/底栏的 6dp 纵向内边距一起用。
     */
    @VisibleForTesting
    fun estimateCornerInsetDp(widthDp: Int): Int {
        if (widthDp <= 0) return 12
        val raw = (widthDp * 0.08f).toInt()
        return raw.coerceIn(8, 20)
    }

    private data class Metrics(
        val widthPx: Int,
        val heightPx: Int,
        val widthDp: Int,
        val heightDp: Int,
        val densityDpi: Int,
    )

    private fun screenMetrics(context: Context): Metrics {
        val dm = DisplayMetrics()
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
        }.onFailure {
            context.resources.displayMetrics.let { src ->
                dm.widthPixels = src.widthPixels
                dm.heightPixels = src.heightPixels
                dm.densityDpi = src.densityDpi
                dm.density = src.density
                dm.scaledDensity = src.scaledDensity
            }
        }
        val density = if (dm.density > 0f) dm.density else 1f
        return Metrics(
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
            widthDp = (dm.widthPixels / density).toInt(),
            heightDp = (dm.heightPixels / density).toInt(),
            densityDpi = dm.densityDpi,
        )
    }

    private fun memoryInfo(am: ActivityManager?): Int {
        if (am == null) return 1024
        return runCatching {
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                .totalMem
                .let { (it / (1024 * 1024)).toInt() }
        }.getOrDefault(1024)
    }

    /** 是否处于横屏（用于极少数横屏手表） */
    @Suppress("unused")
    fun isLandscape(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}
