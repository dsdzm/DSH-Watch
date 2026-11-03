package com.dshwatch.core

import android.util.Log

/**
 * 统一日志出口。
 *
 * ## 为什么 release 也要留一部分
 *
 * 以前这里所有方法都受 `enabled` 控制，release 下 `enabled` 恒为 false，
 * 再加上 ProGuard 的 `-assumenosideeffects`，release 包**一条日志都不打**。
 * 代价在排查崩溃时立刻显现：用户说「点进某个页面就崩」，我们手里除了
 * 「屡次停止运行」四个字什么都没有 —— 没有堆栈，只能靠猜，猜错一次就是一轮返工。
 *
 * 现在分两档：
 * - [d] / [i]：调试用，release 依然被 R8 移除，不进包、无开销
 * - [w] / [e]：**发布版也保留**。它们只在出错路径上调用，正常使用不产生任何输出
 *
 * 保留的条目同时进一个环形缓冲，崩溃时交给 [CrashLog] 抄进报告 ——
 * 崩溃前最后几条 warning 往往就是根因。
 */
object Logx {

    /** 调试档开关。release 恒为 false */
    var enabled: Boolean = false

    /** 最近若干条 warn/error。新的在末尾 */
    private const val RING = 60
    private val ring = ArrayDeque<String>(RING)

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(TAG_PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(TAG_PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        remember("W", tag, msg, t)
        if (t == null) Log.w(TAG_PREFIX + tag, msg) else Log.w(TAG_PREFIX + tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        remember("E", tag, msg, t)
        if (t == null) Log.e(TAG_PREFIX + tag, msg) else Log.e(TAG_PREFIX + tag, msg, t)
    }

    /** 不走 Logcat 也记一笔，给崩溃报告用 */
    fun remember(msg: String) {
        synchronized(ring) {
            ring.addLast(msg)
            while (ring.size > RING) ring.removeFirst()
        }
    }

    private fun remember(level: String, tag: String, msg: String, t: Throwable?) {
        val tail = t?.let { " · ${it.javaClass.simpleName}: ${it.message.orEmpty().take(180)}" }.orEmpty()
        remember("$level/$tag $msg$tail")
    }

    /** 最近发生过的警告与错误，新的在最后 */
    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    private const val TAG_PREFIX = "DSHW/"
}
