package com.dshwatch.domain.device

/**
 * 不许 AI 动手的页面。
 *
 * ## 为什么必须有这一层
 *
 * 提示词不是安全边界。模型只要在权限页里点两下，就能给别的应用授权短信、通讯录、
 * 无障碍，或者在安装器里确认装上一个 APK。这些操作在用户自己手里都合理，
 * 但由一句「忽略之前的指令」驱动时，后果不可逆。所以这一层是硬编码的拒绝，
 * 不依赖模型是否听话。
 *
 * ## 系统设置本身**不拦**
 *
 * 早先版本把设置页也拦了（只读不点），代价是「帮我开个勿扰」「把亮度调低」
 * 这类最常见的请求全做不了 —— 用户要的正是让 AI 去改设置。现在设置页可以操作。
 *
 * 代价要写清楚：设置里存在通向权限与安装的入口（特殊应用访问、默认应用、
 * 安装未知应用）。它们多数会跳到下面的权限控制器 / 安装器（仍被拦），
 * 但「安装未知应用」这类开关就在设置包内，拦不住。这是用户明确要求的取舍。
 *
 * ## 仍然硬拦的三类
 *
 * 权限控制器与各家安全中心、安装器、SystemUI（下拉快捷开关等于绕过一切）。
 *
 * ## 为什么用包名而不是界面文字
 *
 * 界面文字随 ROM 与语言变化，靠关键词匹配会漏。包名是稳定的。
 */
object DeviceGuard {

    /** 这些包里的任何点击 / 输入都不做。 */
    val blocked: Set<String> = setOf(
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
        "「${packageName.orEmpty()}」是权限 / 安装 / 系统界面，不能由 AI 操作 —— " +
            "这类页面点错不可逆（给自己开权限、装应用、关掉别人的授权）。" +
            "请用 device_handoff 把设备交给用户自己改。"
}
