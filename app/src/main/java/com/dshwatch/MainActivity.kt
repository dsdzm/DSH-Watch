package com.dshwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
import com.dshwatch.service.QqService
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
        handleFishIntent(intent, container)
        // 判定设备类型只用得到 resources 与 packageManager，持 applicationContext
        // 比在每次重组里读 Activity 干净
        val appContext = applicationContext

        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            val settingsReady by container.settingsReady.collectAsStateWithLifecycle()
            val perf by container.perf.collectAsStateWithLifecycle()
            val profile by container.profile.collectAsStateWithLifecycle()

            /*
             * QQ 桥接的启停跟着开关走。
             *
             * 设置页里已经直接启停了；这里再挂一次是为了「进程被系统杀掉后
             * 用户重新打开 App」这一路 —— 那次启动不会经过设置页，而
             * START_STICKY 只在服务自己被杀时有效，整个进程一起没掉时
             * 没人替我们拉起它。
             *
             * 等 settingsReady 之后再判：初始值是内存默认（enabled=false），
             * 直接拿它判定会在每次冷启动时先 stop 一次，把正在跑的服务掐掉。
             */
            LaunchedEffect(settingsReady, settings.qq.enabled) {
                if (!settingsReady) return@LaunchedEffect
                if (settings.qq.enabled) {
                    com.dshwatch.service.QqService.start(appContext)
                } else {
                    com.dshwatch.service.QqService.stop(appContext)
                }
            }

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
                glassBlur = settings.glassBlur,
                backgroundBlur = settings.backgroundBlur,
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
     * 点「她来找你」的通知进来时，落到养鱼对话。
     *
     * 只改 `useScene` 这一个默认落点，不直接改导航栈：栈是界面层的事，
     * Activity 拿不到它，硬塞一个"打开某某页"的指令就得给整个路由加一层
     * 一次性事件。而养鱼页本身会接着最近一条会话，落点对了页面自然对。
     */
    private fun handleFishIntent(intent: android.content.Intent?, container: AppContainer) {
        if (intent?.getBooleanExtra(
                com.dshwatch.service.FishNotifications.EXTRA_OPEN_FISH,
                false,
            ) != true
        ) {
            return
        }
        // 同一个 intent 被复用（singleTask）时不要重复触发
        intent.removeExtra(com.dshwatch.service.FishNotifications.EXTRA_OPEN_FISH)
        lifecycleScope.launch {
            container.settingsRepo.update {
                it.copy(useScene = com.dshwatch.domain.roleplay.Scenes.FISH)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFishIntent(intent, AppContainer.of(this))
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
        /*
         * 「人正看着屏幕」。
         *
         * 只有一个用途：主动消息到点时如果用户就在这一页，气泡会自己长出来，
         * 不必再震一下。用 Activity 的生命周期而不是进程全局的，是因为
         * 这个 App 只有一个界面 —— 多引一个 lifecycle-process 只为一行布尔值不划算。
         */
        com.dshwatch.service.AppVisibility.visible = true
        runCatching { StreamingService.resumePending(this) }
        /*
         * QQ 桥接同理，但它多一个坑：服务是 START_STICKY，
         * 被系统杀掉后重启时进程是新的，桥接对象也是新的 —— 不在这里补一次，
         * 用户会看到通知栏写着「QQ 已连接」，实际一条消息都不进来。
         * 只在开着的时候补，关掉的用户不该看到任何东西。
         */
        runCatching {
            if (AppContainer.of(this).settings.value.qq.enabled) QqService.start(this)
        }
    }

    override fun onPause() {
        com.dshwatch.service.AppVisibility.visible = false
        super.onPause()
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
