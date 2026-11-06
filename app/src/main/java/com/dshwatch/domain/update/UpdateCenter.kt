package com.dshwatch.domain.update

import android.content.Context
import com.dshwatch.core.Logx
import com.dshwatch.data.settings.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 更新流程的全部界面状态。页面只读它，不自己记状态 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Failed(val reason: String) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState

    /** progress 为 -1 表示服务端没给长度，只能显示「正在下载」 */
    data class Downloading(val progress: Float) : UpdateState
    data class Ready(val file: File) : UpdateState
}

/**
 * 更新流程的状态中心。
 *
 * 为什么要有这一层而不是让页面自己调 [UpdateChecker]：
 *  1. 检查可能在「关于页」发起，结果要在「更新页」显示 —— 得有个跨页面存活的地方；
 *  2. 下载要能在用户退出页面后继续（appScope 里跑），回来还能看到进度；
 *  3. 自动检查是启动时触发的，那时还没有任何页面。
 *
 * 所有网络与写盘都在 [UpdateChecker] 的 IO 上下文里，这里只负责状态流转，
 * 因此不需要再切线程 —— [StateFlow] 本身是线程安全的。
 */
class UpdateCenter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 防止连点「检查更新」/「下载」把请求叠起来 */
    private val checking = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)

    /**
     * 启动时的静默检查：24 小时最多一次，且**不打扰用户**。
     *
     * 只在找到新版本时把状态改成 [UpdateState.Available]，关于页那行会显示
     * 「有新版本 x.y.z」；查完没新版本就保持 Idle，不去写 UpToDate ——
     * 用户没问过的事，没必要在界面上给个结论。
     */
    fun autoCheckIfDue() {
        scope.launch {
            val current = settings.current()
            if (!current.autoCheckUpdate) return@launch
            val now = System.currentTimeMillis()
            if (now - current.lastUpdateCheckAt < CHECK_INTERVAL_MS) return@launch

            // 先记时间再发请求：失败也照样计入节流，否则网络一直不通时会
            // 每次启动都请求一遍，白白耗电
            settings.update { it.copy(lastUpdateCheckAt = now) }

            if (!checking.compareAndSet(false, true)) return@launch
            val result = UpdateChecker.check()
            checking.set(false)

            if (result is UpdateResult.Available) {
                // 用户可能已经手动点开了更新页，别把正在看的状态覆盖掉
                if (_state.value is UpdateState.Idle) {
                    _state.value = UpdateState.Available(result.manifest)
                }
            } else if (result is UpdateResult.Failed) {
                Logx.w(TAG, "自动检查失败：${result.reason}")
            }
        }
    }

    /** 用户主动点「检查更新」。不看节流，也不受上次结果影响 */
    fun checkNow() {
        // 下载中途重查会把进度条顶掉，用户看到的是「检查中」然后回到起点
        if (_state.value is UpdateState.Downloading) return
        if (!checking.compareAndSet(false, true)) return
        scope.launch {
            _state.value = UpdateState.Checking
            when (val result = UpdateChecker.check()) {
                is UpdateResult.Available -> _state.value = UpdateState.Available(result.manifest)
                UpdateResult.UpToDate -> {
                    // 已经是最新，缓存里的旧包没用了
                    UpdateInstall.clearDownload(context)
                    _state.value = UpdateState.UpToDate
                }

                is UpdateResult.Failed -> _state.value = UpdateState.Failed(result.reason)
            }
            // 手动查过也算一次：更新页那行「上次检查」要跟着动，
            // 否则用户刚查完，回到页面还写着"三天前"
            markChecked()
            checking.set(false)
        }
    }

    /**
     * 运行期间的定期复查。
     *
     * 只在启动时查一次是不够的：手表上的 App 常常一挂就是好几天，
     * 期间新版本发出来了，用户却要等到手杀进程再打开才知道。
     * 这里每 [PERIODIC_MS] 看一眼，真正的频率仍由
     * [autoCheckIfDue] 里的 24 小时节流决定 —— 醒着只是"有机会查"，
     * 不是"每 6 小时必查一次"。
     */
    fun startPeriodicCheck() {
        scope.launch {
            while (true) {
                delay(PERIODIC_MS)
                autoCheckIfDue()
            }
        }
    }

    /** 记一次检查时间。写盘失败不影响流程 */
    private suspend fun markChecked() {
        val now = System.currentTimeMillis()
        runCatching { settings.update { it.copy(lastUpdateCheckAt = now) } }
    }

    /** 下载当前待更新的包。重复点击无效 */
    fun download() {
        val available = _state.value as? UpdateState.Available ?: return
        if (!downloading.compareAndSet(false, true)) return
        scope.launch {
            _state.value = UpdateState.Downloading(0f)
            var lastPercent = -1
            val result = UpdateChecker.download(context, available.manifest) { progress ->
                /*
                 * 进度来自 64KB 一次的读循环，8MB 会回调一百多次。
                 * 只在整数百分比变化时更新，避免把主线程拖进无意义的重组。
                 */
                val percent = if (progress < 0f) -1 else (progress * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    _state.value = UpdateState.Downloading(progress)
                }
            }
            _state.value = when (result) {
                is DownloadResult.Done -> UpdateState.Ready(result.file)
                is DownloadResult.Failed -> UpdateState.Failed(result.reason)
            }
            downloading.set(false)
        }
    }

    /**
     * 调起系统安装器。
     *
     * @return false 表示没有「安装未知应用」权限，调用方应引导授权
     *   （[UpdateInstall.canInstall] / [UpdateInstall.openPermissionSettings]）。
     */
    fun install(): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        if (!UpdateInstall.canInstall(context)) return false
        /*
         * 成功后**不删文件、不重置状态**。
         *
         * 系统安装器起来之后用户还可能取消（尤其是第一次要选「允许安装」），
         * 这时把 8MB 的包删掉，他再点「安装」就得重新下载一遍。
         * 真正的清理放在「检查发现已是最新」和下次下载覆盖时。
         */
        return UpdateInstall.install(context, ready.file).isSuccess
    }

    /** 页面重新进入时，把「已下载好」的文件认回来（进程内缓存，重启后自然失效） */
    fun restoreFromCache() {
        if (_state.value !is UpdateState.Idle) return
        val dir = File(context.cacheDir, "apk")
        /*
         * 只认比当前版本更新的包。
         *
         * 缓存目录可能留着上次没装成功的旧包（或者用户用别的方式升级过），
         * 直接认成 Ready 会让人在「已经是最新」的设备上点「安装」装回旧版本 ——
         * 系统会拒绝降级，而用户完全不知道为什么。
         */
        val candidate = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?.filter { file ->
                val code = file.name.removeSuffix(".apk").substringAfterLast('-').toIntOrNull()
                code != null && code > com.dshwatch.BuildConfig.VERSION_CODE
            }
            ?.maxByOrNull { it.lastModified() }
            ?: return
        _state.value = UpdateState.Ready(candidate)
    }

    private companion object {
        const val TAG = "Update"

        /** 自动检查的间隔。一天一次，够用又不至于让手表一直联网 */
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** 运行期间多久看一眼"是不是该复查了"。真正的节流在 CHECK_INTERVAL_MS */
        const val PERIODIC_MS = 6L * 60 * 60 * 1000
    }
}
