package com.dshwatch.domain.agent.exec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Semaphore

/**
 * 全局执行闸门：**同一时刻只允许一个脚本在跑**。
 *
 * 为什么必须限 1：手表是 4 核小 CPU，而脚本是模型写的、可能失控。
 * 三个脚本同时 `while(true)` 会让设备直接卡死到只能长按重启 ——
 * 那时候用户连"停止"按钮都点不动。
 *
 * 更现实的一个原因：Python 超时后解释器可能仍在后台跑（见 ReflectionPythonEngine），
 * 如果放新的执行进来，失控的脚本会叠加，越滚越多。所以超时后闸门会**保持占用**
 * 直到那段脚本自己结束 —— 宁可拒绝新任务，也不让设备进入不可控状态。
 */
class ExecGuard {

    private val semaphore = Semaphore(1)
    private val _busy = MutableStateFlow(false)

    /** 是否有执行占用中（含超时后仍在后台跑的），供界面显示 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 拿到执行权返回 true；已有执行在跑则返回 false（调用方应直接拒绝并提示） */
    fun tryAcquire(): Boolean {
        val acquired = semaphore.tryAcquire()
        if (acquired) _busy.value = true
        return acquired
    }

    /** 执行结束（含异常路径）必须调用 */
    fun release() {
        _busy.value = false
        semaphore.release()
    }

    /**
     * 用户手动强制释放。
     *
     * 存在的理由：Python 超时后解释器可能仍在跑，闸门就一直被占着，
     * 用户会看到"本地执行被占用"却无法恢复。这时需要一个明确的逃生口 ——
     * 由用户在设置页确认后调用，而不是我们自己猜。
     *
     * 只在确实被占用时释放一次，避免信号量额度越积越多（那会让并发限制失效）。
     */
    fun forceRelease() {
        if (semaphore.availablePermits() == 0) semaphore.release()
        _busy.value = false
    }

    companion object {
        /** 应用级单例；测试里用 `ExecGuard()` 各造各的，避免互相干扰 */
        val shared = ExecGuard()
    }
}
