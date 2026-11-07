package com.dshwatch.domain.model

/**
 * 布局主题：同一套业务页面，两种排版。
 *
 * ## 为什么要有两个
 *
 * 这套界面原本是**一套 320dp 设计**，靠 `uiScale` 缩放 `Density` 把整块布局
 * 压进任意宽度的屏幕。在 206dp 的高 dpi 小表上，0.64× 让它"刚好放下"，
 * 但用户一旦把滑块拉大，320dp 的设计就变成 368dp，而屏只有 206dp ——
 * 内容必然顶出圆周被切。
 *
 * 根子在于「缩放」和「重排」是两件事：
 *  - 手机/大屏：屏幕够宽，按 320dp 铺开，缩放只是微调 → [PHONE]
 *  - 手表：屏幕只有 190~230dp，规范要求的是**内容少而大、逐项随位置缩放淡出**，
 *    不是把手机布局等比缩小 → [WATCH]
 *
 * 所以这里分成两个正交主题，而不是继续在一条缩放轴上打补丁。
 *
 * ## 和 [ScreenShapes] 的区别
 *
 * `ScreenShapes` 描述的是**物理屏幕形状**（我这台表是不是圆的），
 * 是设备事实，会影响几何计算；`LayoutThemes` 描述的是**我要用哪套排版**，
 * 是用户偏好。两者可以组合：圆屏 + 手机布局（屏幕是事实，排版是选择）。
 */
object LayoutThemes {

    /** 跟随设备：见 [resolve] */
    const val AUTO = "auto"

    /** 手表排版：按 Wear OS Material 3 规范重排，不依赖 320dp 基准 */
    const val WATCH = "watch"

    /** 手机/大屏排版：就是 1.15.22 及以前那套 */
    const val PHONE = "phone"

    val all = listOf(AUTO, WATCH, PHONE)

    /**
     * 归一化。未知取值（含老存档的空串、将来删掉的 id）一律落回 [AUTO]。
     *
     * 存 String 而不是枚举，和 `ScreenShapes` / `ChatLayouts` / `Appearances`
     * 保持同一套约定：枚举改名会让老用户整包设置读不回来。
     */
    fun of(id: String?): String = when (id) {
        WATCH, PHONE, AUTO -> id
        else -> AUTO
    }

    /**
     * 解析成实际生效的布局（只会返回 [WATCH] 或 [PHONE]）。
     *
     * [AUTO] 的判据按顺序短路，全部基于**未缩放**的原始画像：
     *
     * | 顺序 | 条件 | 结果 | 理由 |
     * |---|---|---|---|
     * | 1 | 系统声明是手表 | WATCH | `FEATURE_WATCH` 是最强信号 |
     * | 2 | 圆屏 | WATCH | 圆屏用矩形骨架必然切角（顶栏两端） |
     * | 3 | 宽度 < 240dp | WATCH | 320dp 的设计在 240dp 下要压到 0.75×，已经不像手表了 |
     * | 4 | 其余 | PHONE | 方表大屏 / 手机 / 平板 / 桌面模拟器 |
     *
     * 入参用 4 个原始类型而不是 `DeviceProfile`：纯函数能在 JVM 单测里跑矩阵，
     * 也避免 domain.model 反向依赖 domain 顶层的设备画像。
     */
    fun resolve(choice: String, isWatch: Boolean, isRound: Boolean, widthDp: Int): String =
        when (of(choice)) {
            WATCH -> WATCH
            PHONE -> PHONE
            else -> when {
                isWatch -> WATCH
                isRound -> WATCH
                widthDp in 1..239 -> WATCH
                else -> PHONE
            }
        }

    /** 是否手表排版。调用点统一用它，不要各自 `== WATCH` 后再取反 */
    fun isWatchLayout(resolved: String): Boolean = resolved == WATCH
}
