package com.dshwatch.domain.device

/**
 * 不许 AI 动手的页面。
 *
 * ## 为什么必须有这一层
 *
 * 提示词不是安全边界。模型只要在系统设置里点两下，就能：
 *  - 把「安装未知应用」打开，然后装上任意 APK；
 *  - 给别的应用授权短信、通讯录、无障碍；
 *  - 关掉本应用的无障碍服务（或者反过来，给别的应用开）。
 *
 * 这些操作在**用户自己**手里都是合理的，但由一句「忽略之前的指令」驱动时，
 * 后果不可逆。所以这一层是硬编码的拒绝，不依赖模型是否听话。
 *
 * ## 只拦「动手」，不拦「打开」
 *
 * `device_open` 打开设置页是允许的（用户可能就是说「帮我打开蓝牙设置」），
 * 但打开之后不许点。用户看到页面，自己决定怎么改 —— 这正是我们要的边界。
 *
 * ## 为什么用包名而不是界面文字
 *
 * 界面文字随 ROM 与语言变化，靠关键词匹配会漏。包名是稳定的：
 * 设置、安装器、权限控制器就那么几个（含各家的 OEM 版本）。
 */
object DeviceGuard {

    /** 这些包里的任何点击 / 输入都不做。 */
    val blocked: Set<String> = setOf(
        // AOSP 与常见 OEM 设置
        "com.android.settings", "com.miui.settings", "com.coloros.settings",
        "com.oplus.settings", "com.vivo.settings", "com.huawei.settings",
        "com.hihonor.settings", "com.samsung.android.settings", "com.oneplus.settings",
        // 安装器
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.miui.packageinstaller", "com.samsung.android.packageinstaller",
        "com.oplus.packageinstaller", "com.coloros.packageinstaller",
        // 权限控制器与 OEM 安全中心
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.lbe.security.miui", "com.miui.securitycenter", "com.miui.securityadd",
        "com.coloros.securitypermission", "com.oplus.securitypermission",
        "com.coloros.safecenter", "com.oplus.safecenter",
        "com.iqoo.secure", "com.vivo.permissionmanager", "com.vivo.safecenter",
        "com.huawei.systemmanager", "com.hihonor.systemmanager",
        // 系统 UI（可以下拉快捷开关，等于绕过上面所有限制）
        "com.android.systemui",
    )

    fun isBlocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return blocked.contains(packageName)
    }

    /** 拒绝时给模型看的话。要说明「为什么」以及「接下来怎么办」，否则它会重试。 */
    fun refusal(packageName: String?): String =
        "「${packageName.orEmpty()}」是系统设置 / 权限 / 安装类页面，不能由 AI 操作 —— " +
            "否则模型可以给自己开权限、装应用或关掉别人的授权，而且不可逆。" +
            "请用 device_handoff 把设备交给用户自己改。"
}
