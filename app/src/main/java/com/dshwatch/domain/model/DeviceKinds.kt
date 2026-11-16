package com.dshwatch.domain.model

/**
 * 这台设备用哪一套界面。
 *
 * ## 为什么需要一个「选择」而不只是检测
 *
 * 检测本身很可靠：`uiMode` 里带着 `UI_MODE_TYPE_WATCH` 的才是手表，
 * 其余按手机处理。但有两类设备检测会判错：
 *
 *  - **方形表**（部分 Wear 设备、以及手表上的「显示模式」）；
 *  - **把 App 装到平板 / 折叠屏上**的用户，他想看的是手机布局，
 *    而不是被塞进 200dp 的圆屏里。
 *
 * 所以引导页问一次，**默认值取检测结果**：绝大多数用户直接点「下一步」就对了，
 * 只有判错的人才需要动手改。设置里也能改（改完重启界面生效）。
 *
 * ## 为什么存字符串而不是枚举
 *
 * 设置是一份要能跨版本读写的 JSON。枚举多一个分支时，老存档会反序列化失败 ——
 * 这是这个项目里已经踩过的坑（见 AppSettings.updateChannel 的注释）。
 */
object DeviceKinds {

    /** 跟随检测 */
    const val AUTO = "auto"

    /** 手表：圆屏 / 小屏，走经典或 M3E 两套手表界面 */
    const val WATCH = "watch"

    /** 手机：Material 3 大屏界面，底部导航 + 顶栏 */
    const val PHONE = "phone"

    /** 认不出的值（老存档、手改坏了）一律回落到自动 */
    fun of(raw: String): String = when (raw) {
        WATCH, PHONE -> raw
        else -> AUTO
    }

    fun detect(isWatchUi: Boolean): String = if (isWatchUi) WATCH else PHONE

    /**
     * 最终生效的设备类型。
     *
     * 返回的一定是 [WATCH] 或 [PHONE] —— [AUTO] 在这一步就被消解掉，
     * 调用方不需要再处理三态。
     */
    fun resolve(choice: String, isWatchUi: Boolean): String =
        of(choice).let { if (it == AUTO) detect(isWatchUi) else it }

    fun isPhone(choice: String, isWatchUi: Boolean): Boolean = resolve(choice, isWatchUi) == PHONE
}
