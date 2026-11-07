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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.core.CrashLog
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
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        val container = AppContainer.of(this)

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

            DshWatchTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                themePreset = settings.themePreset,
                accentColor = settings.accentColor,
                appearance = settings.appearance,
                uiScale = uiScale,
                appLanguage = settings.appLanguage,
                perf = perf,
                profile = profile,
            ) {
                // 设置没读出来之前什么都不画：否则 onboardingDone 的默认 false
                // 会让老用户每次启动都闪一帧引导页。
                if (settingsReady) {
                    /*
                     * 启动完成的判定点就放在这里，**不再放 AppRoot 里**。
                     *
                     * 原先写在 AppRoot 的第一帧，但 AppRoot 开头有一段
                     * `if (!onboardingDone) { 引导页; return }` —— 新用户装完
                     * 先看到的是 4 步引导，压根走不到那行 `endLaunch`。
                     * 于是他中途退出（很常见），标记留着，下次启动就报
                     * 「上次没能启动完成」。
                     *
                     * 这个假阳性最坏的地方在于：用户报的是「装上就闪退」，
                     * 而我们看到的也是「上次没走完」—— 两条错的信息撞在一起，
                     * 会直接把排查带偏。所以判定点必须前移到「只要能画出内容」。
                     *
                     * 写文件是同步 IO，挪到 IO 线程：手表上主线程一卡就是
                     * 掉帧，没必要为了写几 KB 的文本赌这一下。
                     */
                    LaunchedEffect(Unit) {
                        // 用 markFirstFrame 而不是 mark：这个 LaunchedEffect 会在
                        // Activity 被系统回收重建后再跑一次，mark 会把第二次也写成
                        // 「首帧组合完成」，报告里就出现两条一样的时间，看起来像启动花了
                        // 两次那么久。markFirstFrame 只在第一次定格启动耗时，
                        // 之后写「界面重建」。
                        CrashLog.markFirstFrame()
                        withContext(Dispatchers.IO) {
                            // 顺序：先写体检（它要读"上次是否崩过"），再抹标记
                            CrashLog.writeStartupSnapshot(this@MainActivity, perf.tier.name)
                            CrashLog.endLaunch(this@MainActivity)
                        }
                    }
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
