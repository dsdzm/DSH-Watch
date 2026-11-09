package com.dshwatch.domain.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * 设备操控的运行时状态中枢。
 *
 * 三样东西要连在一起，但它们互相不该知道对方：
 *
 *  - **无障碍服务**（[com.dshwatch.service.DeviceA11yService]）只在系统连上时存在，
 *    随用户开关系统设置而来去；
 *  - **工具层**（`DeviceControlTools`）是单例，进程一起来就有，但服务可能还没连；
 *  - **引擎**（ChatEngine）负责真正「停下来」，它不该反过来依赖工具集。
 *
 * 这里用手写注册表把它们接起来 —— 和项目里 B 站 Cookie、UiActionChannel 的做法一致，
 * 不引 DI 框架。
 *
 * ## 停止是「拉闸」而不是「取消协程」
 *
 * 悬浮窗上的停止按钮属于无障碍服务进程内的 View，拿不到那次对话的 `Job`。
 * 所以它只做一件事：把 [stopRequested] 置位并通知 [onStopRequested]。
 * 工具每次执行前检查这个标志，引擎那边则由 AppContainer 接到 `engine.stop()`。
 * 两层都做，是因为模型可能在两步工具调用之间正好停住 —— 只靠一处会有窗口期。
 */
object DeviceControlHub {

    /**
     * 悬浮条的抽象。
     *
     * 中枢与工具层只需要「显示 / 更新 / 隐藏」，不该知道它是 View 还是 Compose。
     * 真正的实现在 `service/DeviceOverlay`，由无障碍服务在连上时注册。
     */
    interface OverlayPort {
        fun show()
        fun hide()
        fun update(intent: String, appLabel: String?)
    }

    /** 一次读屏的结果 + 读的时间。动作前拿它做「屏幕是否还是那一屏」的核对。 */
    data class Snapshot(
        val packageName: String?,
        val appLabel: String?,
        val elements: List<UiElement>,
        val text: String,
        val at: Long = System.currentTimeMillis(),
    ) {
        fun byId(id: Int): UiElement? = elements.firstOrNull { it.id == id }

        /** 超过这个时间就认为屏幕可能已经变了，动作前强制重读。 */
        fun stale(now: Long = System.currentTimeMillis()): Boolean = now - at > 30_000
    }

    /** 供界面观察的状态（设置页显示「已连接 / 未开启」）。 */
    data class State(
        val connected: Boolean = false,
        val running: Boolean = false,
        val intent: String = "",
        val appLabel: String? = null,
    )

    private var serviceRef: WeakReference<Any>? = null
    private var overlayRef: WeakReference<OverlayPort>? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 最近一次读屏结果。动作层用它核对编号指向的元素有没有变。 */
    @Volatile
    var lastScreen: Snapshot? = null
        private set

    @Volatile
    private var stopped = false

    /**
     * 用户点了停止之后要做什么。由 AppContainer 在构造引擎后接上（`engine.stop()`）。
     * 允许为 null：单测与后台任务里没有引擎可停。
     */
    @Volatile
    var onStopRequested: ((String) -> Unit)? = null

    // ---------------- 服务注册 ----------------

    fun attachService(service: Any) {
        serviceRef = WeakReference(service)
        _state.value = _state.value.copy(connected = true)
    }

    fun detachService(service: Any) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            lastScreen = null
            _state.value = _state.value.copy(connected = false, running = false, intent = "")
        }
    }

    fun attachOverlay(overlay: OverlayPort) {
        overlayRef = WeakReference(overlay)
    }

    fun detachOverlay(overlay: OverlayPort) {
        if (overlayRef?.get() === overlay) overlayRef = null
    }

    fun overlay(): OverlayPort? = overlayRef?.get()

    /** 无障碍服务实例（`DeviceA11yService`），未连接时为 null。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> service(): T? = serviceRef?.get() as? T

    fun isConnected(): Boolean = serviceRef?.get() != null

    // ---------------- 任务生命周期 ----------------

    fun publish(snapshot: Snapshot) {
        lastScreen = snapshot
    }

    /**
     * 一次「操控任务」开始：显示悬浮条，清掉上一轮的停止标志。
     *
     * 刻意不在这里把 App 退到后台 —— 那是界面的职责（`moveTaskToBack`），
     * 而这一层拿不到 Activity。
     */
    fun begin(intent: String) {
        stopped = false
        _state.value = _state.value.copy(running = true, intent = intent)
        overlay()?.show()
        overlay()?.update(intent, _state.value.appLabel)
    }

    /** 更新悬浮条上显示的那句话。每执行一个动作调一次。 */
    fun note(intent: String, appLabel: String? = _state.value.appLabel) {
        _state.value = _state.value.copy(intent = intent, appLabel = appLabel)
        overlay()?.update(intent, appLabel)
    }

    fun end() {
        stopped = false
        lastScreen = null
        _state.value = _state.value.copy(running = false, intent = "", appLabel = null)
        overlay()?.hide()
    }

    // ---------------- 停止 ----------------

    /** 用户按了停止（或屏幕关闭、服务被关掉）。幂等。 */
    fun requestStop(reason: String) {
        if (stopped) return
        stopped = true
        _state.value = _state.value.copy(running = false, intent = reason)
        onStopRequested?.invoke(reason)
    }

    fun isStopped(): Boolean = stopped

    /** 任务开始时清一次，避免上一次的停止标志把新任务立刻掐掉。 */
    fun clearStop() {
        stopped = false
    }

    /**
     * 工具执行前的闸门。
     *
     * 返回非 null 表示已经被叫停，调用方直接把这句话当失败结果返回 ——
     * 模型看到「用户已停止」就不会再试下一步。
     */
    fun abortIfStopped(): String? =
        if (stopped) "用户已停止操作，不要再继续。" else null
}
