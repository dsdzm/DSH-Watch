package com.dshwatch.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从 Compose 拿到的 Context 里找出宿主 Activity。
 *
 * LocalContext 在部分场景下不是 Activity（ContextThemeWrapper、Wear 的
 * 表盘宿主、甚至 Application），直接强转会在真机上偶发 ClassCastException。
 * `moveTaskToBack` 这类方法只有 Activity 有，所以必须一路往上剥。
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
