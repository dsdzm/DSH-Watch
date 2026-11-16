package com.dshwatch.ui.html

import android.webkit.WebView

/**
 * 这台设备到底有没有可用的 WebView。
 *
 * ## 为什么要在构造之前就问
 *
 * 少数精简 ROM 把 framework 里的 WebView 实现整个剔掉了 —— 华为手表的
 * GLL-AL00 就是这样：`service list` 里没有 webviewupdate，`/system/lib` 只有
 * `libwebviewchromium_loader.so` 和 `libwebviewchromium_plat_support.so`，
 * 缺 `libwebviewchromium.so`。
 *
 * 这种情况下**构造函数本身**就抛异常，不是加载失败、不是白屏：
 *
 * ```
 * java.lang.UnsupportedOperationException
 *     at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:242)
 *     at android.webkit.WebView.<init>(WebView.java:426)
 * ```
 *
 * 用 `runCatching { WebView(ctx) }` 当然能兜住，代价是每渲染一条带 HTML 的消息
 * 就抛一次、抛在组合期、抛在只有 512MB 内存的手表上。所以先在门外问一句，
 * 把答案记下来：不可用时所有入口直接走降级分支，连实例都不建。
 *
 * ## 为什么结果要缓存
 *
 * `getCurrentWebViewPackage()` 内部就是 `WebViewFactory.getProvider()`，
 * 每次调用都要走一遍失败路径。而实现一旦缺失，在这次进程生命周期内不可能
 * 又冒出来 —— 只问一次既是性能，也是唯一说得通的语义。
 */
object WebViewSupport {

    /** null = 还没问过。检测本身会抛异常，所以只能做一次。 */
    @Volatile
    private var known: Boolean? = null

    /**
     * 有没有可用的 WebView 实现。
     *
     * 组合期同步调用是安全的：命中缓存后就是一次字段读取；首次检测也只是
     * 一次（注定失败的）provider 查找，没有 IO、没有磁盘扫描。
     */
    fun isAvailable(): Boolean {
        known?.let { return it }
        return synchronized(this) {
            known ?: detect().also { known = it }
        }
    }

    /**
     * 真正的探测。
     *
     * 用 `getCurrentWebViewPackage()` 而不是反射 `WebViewFactory`：前者是公开 API
     * （26+，本工程 minSdk 28），内部同样走 `getProvider()`，拿不到实现就抛 ——
     * 这正是我们要问的那个问题。它返回 null 也说明没有启用的实现。
     */
    private fun detect(): Boolean = runCatching {
        WebView.getCurrentWebViewPackage() != null
    }.getOrDefault(false)
}
