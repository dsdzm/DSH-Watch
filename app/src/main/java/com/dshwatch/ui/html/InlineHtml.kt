package com.dshwatch.ui.html

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.components.withHaptic
import kotlin.math.abs

/**
 * 把列表的竖向滚动能力借给内嵌的 WebView。
 *
 * ## 为什么需要这个
 *
 * WebView 一旦拿到触摸事件就会自己吞掉，Compose 的 `LazyColumn` 再也收不到
 * 后续的 MOVE —— 手指按在界面上划，列表纹丝不动，看上去像卡死。
 * 而嵌套滚动桥接（`NestedScrollConnection`）在 `AndroidView` 里并不生效：
 * Compose 不会把 Android View 的触摸换算成 nested scroll。
 *
 * 所以反过来做：内嵌 WebView 的高度永远等于内容高度（自身没有可滚空间），
 * 竖向拖动在 [InlineHtml] 里被截下来，直接调 `listState.dispatchRawDelta()`。
 * 点击、长按这些「不移动」的手势照常交给 WebView，交互不受影响。
 */
val LocalListDrag = staticCompositionLocalOf<(Float) -> Unit> { {} }

/**
 * 消息流里直接渲染的 HTML 界面。
 *
 * 和全屏预览的区别只有两点：高度由内容决定，以及竖向拖动交给外层列表。
 * 其余（CSS 生效、JS 可交互、样式完全自定义）完全一样 —— 这正是需求要的
 * 「在对话里就是一张活的界面」，而不是一张「点开才看得到」的入口卡片。
 *
 * @param maxHeight 单条消息最多占多高。超出的部分允许 WebView 自己滚（并给出
 *                  全屏入口）—— 一条消息铺满整屏会把上下文彻底顶出去。
 */
@Composable
fun InlineHtml(
    html: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 330.dp,
    onOpenFull: (String) -> Unit,
    onDrag: (Float) -> Unit = LocalListDrag.current,
    /** 面板里的选项被点中时回调，参数是要作为用户回复发出的整段文本 */
    onPick: (String) -> Unit = {},
    /** 只有最新一条消息的选项可点，历史面板一律置灰 */
    pickable: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val fg = HtmlShell.hex(scheme.onSurface.toArgb())
    val accent = HtmlShell.hex(scheme.primary.toArgb())
    val doc = remember(html, fg, accent, pickable) {
        HtmlShell.wrap(html, fg, accent, inline = true, pickable = pickable)
    }

    val density = LocalDensity.current
    val maxPx = with(density) { maxHeight.roundToPx() }
    val seedPx = with(density) { 96.dp.roundToPx() }

    var measuredPx by remember(html) { mutableIntStateOf(0) }
    var failed by remember(html) { mutableStateOf(false) }
    // 700ms 内的重复 pick 直接丢弃：一次 pick 会真的发一条消息
    val pickGuard = remember(html) { longArrayOf(0L) }

    // 还没量到真实高度时先占个位，避免「先塌成一条再撑开」的跳动
    val shownPx = when {
        measuredPx <= 0 -> seedPx
        measuredPx > maxPx -> maxPx
        else -> measuredPx
    }
    // 内容比上限还高：这时 WebView 内部可滚，拖动就不再转发给列表
    val innerScroll = measuredPx > maxPx

    if (failed) {
        HtmlUnavailable(modifier)
        return
    }

    Box(modifier = modifier.fillMaxWidth()) {
        key(html) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { shownPx.toDp() })
                    .clip(RoundedCornerShape(14.dp)),
                factory = { ctx ->
                    runCatching {
                        @SuppressLint("SetJavaScriptEnabled")
                        WebView(ctx).apply {
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = false
                            settings.displayZoomControls = false
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            overScrollMode = View.OVER_SCROLL_NEVER
                            isLongClickable = false

                            addJavascriptInterface(
                                HeightBridge(
                                    onHeight = { h ->
                                        // JS 回调在 WebView 的私有线程上，回到主线程再写 state
                                        post { measuredPx = h }
                                    },
                                    onPick = { text ->
                                        post {
                                            // 手表的触点在 40dp 左右，双击很容易发生。
                                            // JS 侧已经禁用了同组按钮，这里再挡一道：
                                            // 一次 pick 会真的发一条消息，重复就是两条一样的回复。
                                            val now = android.os.SystemClock.elapsedRealtime()
                                            if (now - pickGuard[0] > 700L) {
                                                pickGuard[0] = now
                                                onPick(text)
                                            }
                                        }
                                    },
                                ),
                                "dshWatch",
                            )

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    // 兜底：脚本被内容里的 CSP 拦掉时，至少量一次
                                    view.postDelayed({
                                        view.evaluateJavascript(
                                            "(function(){try{return Math.max(" +
                                                "document.documentElement.scrollHeight," +
                                                "(document.body?document.body.scrollHeight:0));}catch(e){return 0;}})()",
                                        ) { raw ->
                                            val h = raw?.trim()?.trim('"')?.toIntOrNull() ?: 0
                                            if (h > 0 && measuredPx <= 0) measuredPx = h
                                        }
                                    }, 80)
                                }
                            }

                            loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
                        }.also { wv ->
                            attachDragForwarding(wv, { innerScroll }, { onDrag(it) })
                        }
                    }.getOrElse {
                        failed = true
                        View(ctx)
                    }
                },
                onRelease = { view ->
                    (view as? WebView)?.let {
                        runCatching {
                            it.removeJavascriptInterface("dshWatch")
                            it.stopLoading()
                            it.loadUrl("about:blank")
                            it.destroy()
                        }
                    }
                },
            )
        }

        // 内容超过上限才有全屏入口。常驻会压住作者精心排的界面。
        if (innerScroll) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .glassSurface(
                        shape = CircleShape,
                        cornerRadius = 14.dp,
                        tint = scheme.surface,
                        onClick = withHaptic { onOpenFull(html) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInFull,
                    contentDescription = "全屏",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/**
 * 与界面之间的最小通道。
 *
 * 只有两个方法：报高度、点选项。没有求值、没有读文件、没有拿 Context 的口子 ——
 * 页面内容是模型或用户写的，这条边界必须窄。
 */
private class HeightBridge(
    private val onHeight: (Int) -> Unit,
    private val onPick: (String) -> Unit,
) {
    @JavascriptInterface
    fun resize(h: Int) {
        if (h > 0) onHeight(h)
    }

    @JavascriptInterface
    fun pick(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isNotEmpty() && t.length <= 500) onPick(t)
    }
}

/**
 * 竖向拖动转交外层列表。
 *
 * 判定顺序很关键：先让 WebView 收下 DOWN（否则它不会进入"按下"状态，点击就废了），
 * MOVE 超过 touchSlop 且以竖向为主时才截下来转发。横向滑动保留给 WebView ——
 * 有些界面用横滑切标签页。
 */
private fun attachDragForwarding(
    wv: WebView,
    innerScroll: () -> Boolean,
    onDrag: (Float) -> Unit,
) {
    val slop = ViewConfiguration.get(wv.context).scaledTouchSlop
    var downY = 0f
    var downX = 0f
    var lastY = 0f
    var dragging = false

    wv.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                downX = e.x
                lastY = e.y
                dragging = false
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = lastY - e.y
                lastY = e.y
                if (!dragging) {
                    val vy = abs(e.y - downY)
                    val vx = abs(e.x - downX)
                    if (vy > slop && vy > vx) dragging = true
                }
                if (dragging && !innerScroll()) {
                    onDrag(dy)
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                false
            }

            else -> false
        }
    }
}

@Composable
private fun HtmlUnavailable(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(14.dp),
                cornerRadius = 14.dp,
                tint = MaterialTheme.colorScheme.surface,
            )
            .padding(12.dp),
    ) {
        androidx.compose.material3.Text(
            text = "这台设备没有可用的 WebView，无法渲染这段界面",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
