package com.dshwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.core.CrashLog
import com.dshwatch.service.StreamingService
import com.dshwatch.ui.AppRoot
import com.dshwatch.ui.nav.rememberWatchNavigator
import com.dshwatch.ui.theme.DshWatchTheme
import com.dshwatch.ui.wear.CircleGeometry
import com.dshwatch.ui.wear.LocalCircle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /*
         * 「界面来了」—— 和 `Application.onCreate` 里的 beginLaunch 配对。
         *
         * 没有这一笔，闹钟 / 前台服务 / 更新检查把进程拉起来的那几次都会被
         * 当成"用户打开过但没走完"，于是下次开 App 弹一张"上次没能启动完成"。
         * 判据见 CrashLog.MarkerVerdict.BACKGROUND。
         */
        CrashLog.markUiAttached(this)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        val container = AppContainer.of(this)
        // 判定设备类型只用得到 resources 与 packageManager，持 applicationContext
        // 比在每次重组里读 Activity 干净
        val appContext = applicationContext

        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            val settingsReady by container.settingsReady.collectAsStateWithLifecycle()
            val perf by container.perf.collectAsStateWithLifecycle()
            val profile by container.profile.collectAsStateWithLifecycle()

            /*
             * 界面缩放要在主题之外算好：主题内部只知道「缩放多少」，
             * 不知道设备多宽；而按宽度推自动值需要 profile。
             *
             * 用 profile.screenWidthDp（系统密度下的 dp 宽）而不是缩放后的值，
             * 否则自动值会随着自身结果反复变化、来回震荡。
             */
            val uiScale = com.dshwatch.domain.UiScale.resolve(
                value = settings.uiScale,
                autoEnabled = settings.uiScaleAuto,
                widthDp = profile.screenWidthDp,
            )

            /*
             * 设备类型决定用哪一整套界面。
             *
             * 判定收在 [com.dshwatch.domain.model.DeviceKinds.isWatchDevice] 里：
             * `uiMode` 与「系统清单声明 watch」两个信号取或。只认 uiMode 的时候，
             * 装进安卓手表兼容层的用户会一路被判成手机 —— 升级完发现界面变了，
             * 而他从没动过设置。
             *
             * 用户可以在引导页和设置里覆盖检测结果：方形表和「装在平板上」的设备
             * 检测不出来，而判错的代价是「整个界面根本不是他要的那套」。
             *
             * 两条分支**不共用任何主题代码**：手机版有自己的色板、字号与骨架，
             * 手表那套的界面缩放、圆角安全区、玻璃材质、主题预设一律不参与。
             */
            val watchDevice = com.dshwatch.domain.model.DeviceKinds.isWatchDevice(appContext)
            val phoneUi = com.dshwatch.domain.model.DeviceKinds.isPhone(settings.deviceKind, watchDevice)

            if (phoneUi) {
                com.dshwatch.ui.phone.PhoneTheme(
                    settings = settings,
                    perf = perf,
                    profile = profile,
                ) {
                    if (settingsReady) {
                        LaunchMarker()
                        if (!settings.onboardingDone) {
                            com.dshwatch.ui.phone.PhoneOnboarding(
                                container = container,
                                settings = settings,
                            )
                        } else {
                            com.dshwatch.ui.phone.PhoneRoot(
                                container = container,
                                settings = settings,
                            )
                        }
                    }
                }
                return@setContent
            }

            DshWatchTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                themePreset = settings.themePreset,
                accentColor = settings.accentColor,
                seedColor = settings.seedColor,
                appearance = settings.appearance,
                uiStyle = settings.uiStyle,
                customBackground = settings.customBackground,
                customBackgroundDim = settings.customBackgroundDim,
                customBackgroundFrost = settings.customBackgroundFrost,
                uiScale = uiScale,
                appLanguage = settings.appLanguage,
                perf = perf,
                profile = profile,
            ) {
                // 设置没读出来之前什么都不画：否则 onboardingDone 的默认 false
                // 会让老用户每次启动都闪一帧引导页。
                if (settingsReady) {
                    LaunchMarker()
                    /*
                     * 圆屏几何在这里算一次，往下整棵树共享。
                     *
                     * 放在这一层而不是每个页面各算一遍：屏幕尺寸只有一个，
                     * 而几乎所有圆屏组件（骨架、径向菜单、贴弧气泡）都要用它。
                     * 方屏时传 null，组件据此走矩形分支 —— 而不是给一个「假圆」
                     * 让方屏白白丢掉两个角。
                     */
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val circle = if (profile.isRound) {
                            CircleGeometry(maxWidth, maxHeight)
                        } else {
                            null
                        }
                        CompositionLocalProvider(LocalCircle provides circle) {
                            AppRoot(
                                container = container,
                                settings = settings,
                                nav = rememberWatchNavigator(),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 启动完成的判定点。
     *
     * 为什么放在「能画出内容」这一层，而不是 AppRoot 的第一帧：AppRoot 开头有一段
     * `if (!onboardingDone) { 引导页; return }` —— 新用户装完先看到的是引导页，
     * 压根走不到那里。他中途退出（很常见），标记留着，下次启动就报
     * 「上次没能启动完成」。这个假阳性最坏的地方在于：用户报的是「装上就闪退」，
     * 而我们看到的也是「上次没走完」—— 两条错的信息撞在一起，会把排查带偏。
     *
     * 收尾交给**进程级**协程（`finishLaunchAsync`），不在这里 `withContext(IO)`：
     * 这个 LaunchedEffect 的生命周期跟着 Activity 走，Activity 一旦被销毁重建，
     * 协程即被取消，「抹掉启动标记」那一步永远不执行，用户每开一次就被
     * 「上次没能启动完成」拦一次。实测设备 20 分钟重建了 5 次。
     *
     * 用 markFirstFrame 而不是 mark：重建后再跑一次时，报告里不该出现两条
     * 一样的「首帧组合完成」。
     */
    @Composable
    private fun LaunchMarker() {
        val perf = com.dshwatch.ui.theme.LocalPerf.current
        LaunchedEffect(Unit) {
            CrashLog.markFirstFrame()
            CrashLog.finishLaunchAsync(applicationContext, perf.tier.name)
        }
    }

    /**
     * 界面回到前台时补一次被挡掉的保活。
     *
     * Android 12 起后台不允许起前台服务。如果用户在「发完消息」和
     * 「引擎真正开始流式」之间息了屏，那次保活请求会被挡下 —— 生成在跑，
     * 通知栏却什么都没有。这里补上。
     *
     * 放在 onResume 而不是 onCreate：界面被系统回收后重建时，onResume 每次都会走。
     */
    override fun onResume() {
        super.onResume()
        runCatching { StreamingService.resumePending(this) }
    }

    /**
     * Android 13（API 33）起通知是运行时权限，不申请就等于没有。
     *
     * 这个 App 的前台服务通知和「任务跑完了」提醒都靠通知栏：
     * 手表上用户判断「它还在跑 / 它跑完了」几乎只靠这一条。
     * 之前清单里声明了 POST_NOTIFICATIONS，但全项目没有任何一处申请，
     * 结果在 API 33+ 设备上前台服务通知一直被系统静默拦掉。
     */
    @Suppress("DEPRECATION")
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
    }

    private companion object {
        const val REQUEST_NOTIFY = 0x4455
    }
}
