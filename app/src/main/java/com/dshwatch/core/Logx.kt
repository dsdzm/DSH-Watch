package com.dshwatch.core

import android.util.Log

/**
 * 统一日志出口。
 * release 构建下由 R8 移除 `Log.*` 调用（见 proguard-rules.pro），
 * 因此这里可以放心地在热路径（流式解析）里打日志。
 */
object Logx {

    var enabled: Boolean = false

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(TAG_PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(TAG_PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG_PREFIX + tag, msg) else Log.w(TAG_PREFIX + tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG_PREFIX + tag, msg) else Log.e(TAG_PREFIX + tag, msg, t)
    }

    private const val TAG_PREFIX = "DSHW/"
}
