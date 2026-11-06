package com.dshwatch.domain

import kotlin.math.roundToInt

/**
 * 界面整体缩放。
 *
 * ## 为什么需要它
 *
 * 这个 App 的界面是按 **320dp 宽** 的手表设计的：卡片内边距、图标尺寸、
 * 顶栏高度全是一组写死的 dp 值。而 dp 只在「系统密度设置合理」时才等于
 * 稳定的物理尺寸。
 *
 * 问题出在高 dpi 的小屏设备上：屏幕只有 412×502 px，系统却按 320dpi 报告，
 * 于是可用宽度只有 `412 / 2 = 206dp`。同一套 320dp 的界面被塞进 206dp，
 * 结果就是字号、间距、卡片一起等比放大，最后溢出（截图里「导入世界书 / …」
 * 被截断就是这个）。
 *
 * 之前的 `fontScale` 只改字号（`Density.fontScale`），**不动 dp**，
 * 所以调小字号之后卡片和间距仍然是那么大 —— 用户反馈「调了没用」正是这个原因。
 *
 * 这里改为缩放 `Density.density` 本身：dp 与 sp 一起变，布局才真的变小。
 *
 * ## 方向约定
 *
 * 值越大，界面越大。`density = 基准密度 × scale`：
 *  - scale = 0.64 → 1dp 只占 0.64 倍的像素 → 同屏能放下更多 dp → 界面变小
 *  - scale = 1.00 → 原样
 *
 * ## 为什么不直接改系统密度
 *
 * 那需要 `WRITE_SETTINGS` 或 adb，手表上两个都不可行。`LocalDensity`
 * 只影响本 App 的 composition，进程内生效、退出即还原，是唯一可用的手段。
 */
object UiScale {

    /**
     * 设计基准宽度。
     *
     * 取 320dp 是因为这套界面最早就在 320dp 的表盘上定的尺寸；表盘再宽
     * （329dp、360dp）时界面略显宽松无妨，窄了才会溢出。
     */
    const val DESIGN_WIDTH_DP = 320f

    /**
     * 下限。
     *
     * 206dp 的屏幕需要 0.64 才刚好放下。再小下去正文会低于 10sp 的物理尺寸，
     * 在手表上已经不是「小」而是「看不清」，所以卡在 0.62。
     */
    const val MIN = 0.62f

    /**
     * 上限。
     *
     * 放到 1.15 以上时，320dp 的界面在大表盘上会开始出现横向裁切，
     * 用户很难自己意识到是「放大过头」，所以不给更大的档。
     */
    const val MAX = 1.15f

    /** 手动滑块的步长。0.05 一档，和实际能看出的差别基本对齐 */
    const val STEP = 0.05f

    /**
     * 按屏幕宽度推算「刚好放下 320dp 界面」的缩放值。
     *
     * 206dp → 0.64（缩小 36%，可用宽度回到 322dp）
     * 320dp → 1.00
     * 329dp → 1.03
     */
    fun auto(widthDp: Int): Float {
        if (widthDp <= 0) return 1f
        return (widthDp / DESIGN_WIDTH_DP).coerceIn(MIN, MAX)
    }

    /**
     * 这台设备的**推荐值**：刚好放下 320dp 界面的那个缩放（就是 [auto]）。
     *
     * 设置页拿它当参照 —— 超过它就会开始有内容超出圆周，低于它则界面偏小。
     * 注意这只是参照，不是硬上限：手表的可用宽度差异很大，用户宁可裁一点
     * 也要把字放大是合理选择，App 不该替他决定。
     */
    fun recommended(widthDp: Int): Float = auto(widthDp)

    /**
     * 解析最终生效的缩放值。
     *
     * @param value 用户手动设定的值（[autoEnabled] 为 true 时忽略）
     * @param autoEnabled 是否跟随本机自动
     * @param widthDp 设备当前可用宽度（dp）
     */
    fun resolve(value: Float, autoEnabled: Boolean, widthDp: Int): Float {
        if (autoEnabled) return auto(widthDp)
        // 存档里可能是旧版本写进去的 0 或 NaN，兜到本机推荐值而不是让整屏消失
        if (!value.isFinite() || value <= 0f) return auto(widthDp)
        return value.coerceIn(MIN, MAX)
    }

    /**
     * 按缩放后的密度重算设备画像。
     *
     * `Density` 一变，同一个 px 宽度对应的 dp 就变了。设备画像里的
     * `screenWidthDp` / `screenHeightDp` 若还留着旧值，圆屏的弦长、
     * 列表的预加载条数都会按错误的宽度计算 —— 表现是内容左右不对称、
     * 或者在圆屏上被切掉一角。
     *
     * 圆角内收也要反向补偿：物理圆角半径是**固定的**（由屏幕硬件决定），
     * dp 变小之后，同样的物理距离需要更大的 dp 值才等效。
     */
    fun rescale(profile: DeviceProfile, scale: Float): DeviceProfile {
        if (!scale.isFinite() || scale <= 0f) return profile
        if (kotlin.math.abs(scale - 1f) < 0.001f) return profile

        val baseDensity = if (profile.screenWidthDp > 0) {
            profile.screenWidthPx.toFloat() / profile.screenWidthDp
        } else {
            0f
        }
        if (baseDensity <= 0.01f) return profile

        val newDensity = baseDensity * scale
        return profile.copy(
            screenWidthDp = (profile.screenWidthPx / newDensity).roundToInt(),
            screenHeightDp = (profile.screenHeightPx / newDensity).roundToInt(),
            cornerInsetDp = compensateInset(profile.cornerInsetDp, scale),
            autoCornerInsetDp = compensateInset(profile.autoCornerInsetDp, scale),
        )
    }

    private fun compensateInset(dp: Int, scale: Float): Int {
        // 0 和负数表示「没设」，乘除都没有意义
        if (dp <= 0) return dp
        return (dp / scale).roundToInt().coerceAtLeast(1)
    }
}
