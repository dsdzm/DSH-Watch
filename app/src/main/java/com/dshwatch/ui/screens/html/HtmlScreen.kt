package com.dshwatch.ui.screens.html

import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.html.HtmlShell
import com.dshwatch.ui.nav.WatchNavigator

/**
 * 自定义界面（HTML + CSS）的全屏预览。
 *
 * ## 为什么用 WebView，而不是自己解析一遍 HTML
 *
 * 需求是「让用户和 AI 自己决定这一屏长什么样」。CSS 的子集边界极其模糊
 * （flex、grid、transform、伪元素、媒体查询……），用 Compose 复刻一套
 * 永远只能说「这个属性还不支持」，那就失去了"自定义"的意义。
 * WebView 是设备上唯一能保证「写什么就长什么样」的渲染器。
 *
 * ## 安全边界
 *
 * - 允许 JS：折叠面板、切换卡片这类交互是自定义界面的常见需求；
 * - 允许网络：界面可能引用 CDN 字体或图片；
 * - **禁止读本地文件**：`allowFileAccess = false` / `allowContentAccess = false`，
 *   否则一段 `<img src="file:///data/data/...">` 就能把应用私有目录读出去。
 *
 * 内容是模型或用户自己写的，运行在应用进程内 —— 这条边界是必须的。
 */
@Composable
fun HtmlScreen(nav: WatchNavigator, html: String) {
    val fg = HtmlShell.hex(MaterialTheme.colorScheme.onSurface.toArgb())
    val accent = HtmlShell.hex(MaterialTheme.colorScheme.primary.toArgb())
    val doc = remember(html, fg, accent) {
        HtmlShell.wrap(html, fg, accent, inline = false)
    }

    WatchScaffold(title = "界面", onBack = { nav.pop() }) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // 少数精简系统镜像没装 WebView；那时退化成一段提示，不要连累整个页面
                    runCatching {
                        WebView(ctx).apply {
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = false
                            isVerticalScrollBarEnabled = false
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
                        }
                    }.getOrElse {
                        TextView(ctx).apply {
                            text = "这台设备没有可用的 WebView，无法渲染自定义界面"
                            setTextColor(AndroidColor.LTGRAY)
                            setPadding(24, 48, 24, 24)
                        } as View
                    }
                },
                onRelease = { view ->
                    (view as? WebView)?.let {
                        runCatching {
                            it.loadUrl("about:blank")
                            it.destroy()
                        }
                    }
                },
            )
        }
    }
}
