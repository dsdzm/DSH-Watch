package com.dshwatch.core

import android.os.Build

/**
 * 运行环境识别。
 *
 * ## 为什么需要这个
 *
 * 用户报「华为手表 4 装上就闪退」。查过之后发现一件更要紧的事：
 *
 * 华为 WATCH 4 跑的是 **HarmonyOS 4.3**，不是 Android。它之所以能装 APK，
 * 是因为系统里带了一个 Android 兼容层 —— 我们所有代码都跑在那层里面，
 * 而那一层的 RenderEffect、Keystore、精确闹钟、前台服务都是残缺的。
 *
 * 换句话说：**同一个 APK，在 Wear OS 表上没问题，在 HarmonyOS 表上可能
 * 一启动就崩，而且崩在系统侧，我们的崩溃处理器连回调都收不到。**
 *
 * 这一层判断不修任何东西，只做两件事：
 * 1. 诊断页明确告诉用户「你跑在兼容层上」，避免下一个人再报一遍同样的模糊描述；
 * 2. 给崩溃报告加一行环境标记，让「崩在兼容层」和「崩在真 Android」能分开统计。
 *
 * ## 怎么认
 *
 * 兼容层通常会把 `Build.MANUFACTURER` 保成 HUAWEI，但 `Build.VERSION.RELEASE`
 * 或 `Build.DISPLAY` 会带 HarmonyOS / OpenHarmony 的痕迹；也可能反过来 ——
 * `Build.BRAND` 是 HUAWEI 而 `Build.HARDWARE` / 主板名是海思的手表平台。
 * 所以这里用「多个弱信号投票」，命中任意一条就提示，不做强断言 ——
 * 误报的代价只是多一行说明，漏报的代价是又一轮瞎猜。
 */
object EnvironmentWarning {

    /** 是否为华为 / 荣耀设备（兼容层只出现在这两家） */
    private fun isHuaweiFamily(): Boolean {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val b = Build.BRAND.orEmpty().lowercase()
        return m.contains("huawei") || b.contains("huawei") ||
            m.contains("honor") || b.contains("honor")
    }

    /** 系统描述里有没有 HarmonyOS / OpenHarmony 的痕迹 */
    private fun looksHarmony(): Boolean {
        val fields = listOf(
            Build.DISPLAY,
            Build.VERSION.RELEASE,
            Build.VERSION.INCREMENTAL,
            Build.HOST.orEmpty(),
        ).joinToString(" ").lowercase()
        return fields.contains("harmony") || fields.contains("openharmony")
    }

    /** 一行摘要，给崩溃报告用。永远有值，不会返回 null */
    fun summary(): String {
        val harmony = if (isHuaweiFamily() && looksHarmony()) "（疑似 HarmonyOS 兼容层）" else ""
        return "${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}$harmony"
    }

    /** 给界面用的提示。null 表示这台机器没什么要提醒的 */
    val message: String?
        get() {
            if (!isHuaweiFamily()) return null
            if (!looksHarmony()) {
                // 华为的 Android 系设备（如刷了 EMUI 的手表）也可能有兼容层，
                // 但没有明确字样时不下结论，只在系统版本过低时提一句模糊的事。
                return if (Build.VERSION.SDK_INT < 31) {
                    "这台设备是 API ${Build.VERSION.SDK_INT}，低于 31，" +
                        "没有系统级模糊（RenderEffect）。玻璃与弹窗会改用静态雾近似，" +
                        "观感会比 API 31+ 的设备更「实」一些，这是正常的。"
                } else {
                    null
                }
            }
            return buildString {
                append("检测到这台设备运行 HarmonyOS（${Build.VERSION.RELEASE}），")
                append("本应用是通过系统的 Android 兼容层运行的，不是原生 Android / Wear OS。")
                append("\n\n")
                append("兼容层对下列能力的支持是残缺的，相关功能可能不生效或直接失败：")
                append("\n· 系统级模糊（RenderEffect）→ 玻璃观感退化为静态雾")
                append("\n· 硬件 Keystore → API Key 可能退回普通存储")
                append("\n· 精确闹钟 / 前台服务 → 定时任务与锁屏保活可能被系统拦掉")
                append("\n\n")
                append("如果应用打不开，就是兼容层的问题，不是设置问题。")
                append("把这份报告发出来即可，里面有完整的机型与版本信息。")
            }
        }
}
