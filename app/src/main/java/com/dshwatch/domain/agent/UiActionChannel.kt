package com.dshwatch.domain.agent

/**
 * 工具与界面之间的动作通道。
 *
 * 和 [UserPromptChannel] 同样的理由：工具集先于界面构造，而有些动作
 * （打开某个页面、弹出文件预览）**只有界面能执行**。
 * 用一个可后置注册的通道接起来，而不是让下层反向持有上层。
 *
 * 这里的每个入口都允许为空 —— 单测、后台任务、界面还没起来的时候都可能没接上，
 * 工具侧要据此给出「现在做不到，你可以这样操作」的提示，而不是静默失败。
 */
class UiActionChannel {

    /** 打开工作空间里某个文件的预览，参数是工作空间内的相对路径 */
    @Volatile
    var previewFile: ((String) -> Unit)? = null

    /** 打开 App 内的页面，参数是页面 key（见下方常量） */
    @Volatile
    var openScreen: ((String) -> Unit)? = null

    companion object {
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_PROVIDERS = "providers"
        const val SCREEN_USAGE = "usage"
        const val SCREEN_WORKSPACE = "workspace"
        const val SCREEN_SESSIONS = "sessions"
        const val SCREEN_DIAGNOSTICS = "diagnostics"
        const val SCREEN_ABOUT = "about"
        const val SCREEN_EXEC = "exec"

        val allScreens = listOf(
            SCREEN_SETTINGS, SCREEN_PROVIDERS, SCREEN_USAGE,
            SCREEN_WORKSPACE, SCREEN_SESSIONS, SCREEN_DIAGNOSTICS, SCREEN_ABOUT, SCREEN_EXEC,
        )
    }
}
