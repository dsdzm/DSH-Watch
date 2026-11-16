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
     * 这台设备是不是手表 —— 全 App 唯一的判定入口。
     *
     * 两个信号取「或」：
     *  - `uiMode` 报 `UI_MODE_TYPE_WATCH`：Wear OS 的标准做法；
     *  - 系统声明 `android.hardware.type.watch`：写在设备清单里，改不了。
     *
     * **只信前者的后果是真实的**：装进安卓手表 / 手环兼容层里的 App、少数国内
     * 手表 ROM，`uiMode` 报的是 NORMAL。此时「自动」会一路判成手机，界面上
     * 表现为「升级完手表布局没了」—— 而用户从没改过任何设置，也不知道该改哪。
     *
     * 两个信号都不会把手机误报成手表，所以取「或」是安全的。
     */
    fun isWatchDevice(context: android.content.Context): Boolean {
        val mask = android.content.res.Configuration.UI_MODE_TYPE_MASK
        val type = context.resources.configuration.uiMode and mask
        val byUiMode = type == android.content.res.Configuration.UI_MODE_TYPE_WATCH
        val byFeature = runCatching {
            context.packageManager.hasSystemFeature(WATCH_FEATURE)
        }.getOrDefault(false)
        return byUiMode || byFeature
    }

    private const val WATCH_FEATURE = "android.hardware.type.watch"

    /**
     * 老存档该钉成什么。
     *
     * 只有「从没被问过」+「当前是自动」才需要钉，其余一律返回 null（不动）。
     *
     * 为什么要在存档层钉一次，而不是每次启动按检测重算：检测本身有失败的情况
     * （见 [isWatchDevice]），而 `auto` 意味着「这一次判错，整套界面就换掉」。
     * 从 1.23.4 升上来的用户全都没有这个字段 —— 他们那会儿只有手表界面，
     * 升上来却可能被判成手机，这是最不该发生的一种「升级把我东西改了」。
     *
     * 用户在设置里主动选「自动」时会置 [com.dshwatch.domain.model.AppSettings.deviceKindChosen]，
     * 这条就不再生效 —— 他要重算，就重算。
     */
    fun pinnedForLegacy(choice: String, chosen: Boolean, watchDevice: Boolean): String? =
        if (!chosen && of(choice) == AUTO) detect(watchDevice) else null

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
