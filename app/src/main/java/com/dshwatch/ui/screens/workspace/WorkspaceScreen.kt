package com.dshwatch.ui.screens.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dshwatch.core.AppContainer
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.agent.build.ProjectBuilder
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.canPreview
import com.dshwatch.ui.components.isHtml
import com.dshwatch.ui.components.ShareResult
import com.dshwatch.ui.components.shareFile
import com.dshwatch.ui.components.ConfirmDialog
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.IconTap
import com.dshwatch.ui.components.SegmentedRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.cornerSafe
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.theme.MonoSmall
import com.dshwatch.util.Fmt
import kotlinx.coroutines.launch
import java.io.File

/**
 * 本地工作空间。
 *
 * agent 写出来的文件都落在这里（应用私有目录 `files/workspace`）。这个页面负责：
 * 浏览 → 预览（HTML/图片走 WebView）→ 导出（FileProvider 分享给别的 App）→ 删除。
 *
 * 预览用 WebView 是**按需创建、离开即销毁**的。聊天页刻意不用 WebView
 * （那会常驻 30–60MB），但「看一眼自己刚生成的页面」没有别的替代方案，
 * 所以放在独立页面里一次性使用。
 */
@Composable
fun WorkspaceScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = container.workspaceStore

    var entries by remember { mutableStateOf<List<WorkspaceStore.Entry>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(0) }
    var totalBytes by remember { mutableStateOf(0L) }
    var building by remember { mutableStateOf(false) }
    var buildMessage by remember { mutableStateOf<String?>(null) }
    var buildOk by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<WorkspaceStore.Entry?>(null) }
    var showClear by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        entries = store.listFilesRecursive()
        totalBytes = store.totalBytes()
    }

    val visible = remember(entries, filter) {
        when (filter) {
            1 -> entries.filter { isArtifactPath(it.path) }
            2 -> entries.filterNot { isArtifactPath(it.path) }
            else -> entries
        }
    }

    fun runBuild() {
        scope.launch {
            building = true
            buildMessage = null
            // 优先在**当前会话的工作目录**里找入口：每个会话一个目录，
            // 拿根目录的 index.html 去构建会把别的会话的产物当成自己的。
            // 会话目录里没有才退回根目录 —— 兼容升级前就存在的老文件。
            val (entry, base) = pickEntry(entries, container.engine.conversation.value?.workDir.orEmpty())
            val report = ProjectBuilder.build(store, entry = entry, baseDir = base)
            buildOk = report.ok
            buildMessage = report.toToolText().lineSequence().filter { it.isNotBlank() }
                .take(7).joinToString("\n")
            building = false
            reload++
        }
    }

    WatchScaffold(
        title = s.workspaceTitle,
        onBack = { nav.pop() },
        actions = {
            IconTap(
                Icons.Filled.PlayArrow,
                s.workspaceBuild,
                enabled = !building && entries.any {
                    it.path.endsWith(".html") && !it.path.startsWith("dist/")
                },
                onClick = { runBuild() },
            )
            IconTap(Icons.Filled.Refresh, s.refresh2) { reload++ }
        },
        bottom = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .cornerSafe()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${visible.size} ${s.workspaceFiles} · ${totalBytes / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                )
                if (entries.isNotEmpty()) {
                    Chip(text = s.clear, onClick = { showClear = true })
                    Spacer(Modifier.width(6.dp))
                }
            }
        },
    ) {
        SegmentedRow(
            options = listOf(s.workspaceFilterAll, s.workspaceArtifacts, s.workspaceSource),
            selectedIndex = filter,
            onSelect = { filter = it },
        )
        Spacer(Modifier.height(4.dp))

        if (building) {
            Text(
                text = s.loading,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
        }
        buildMessage?.let { msg ->
            Text(
                text = msg,
                style = MonoSmall,
                color = if (buildOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(6.dp),
            )
            Spacer(Modifier.height(4.dp))
        }

        if (visible.isEmpty()) {
            EmptyState(
                title = s.workspaceEmpty,
                // 把根目录的绝对路径显示出来，用户想用文件管理器/电脑去翻文件时用得上
                hint = s.workspaceHint + "\n\n" + store.rootPath(),
            )
            return@WatchScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(visible, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    isArtifact = isArtifactPath(entry.path),
                    onOpen = { nav.push(Screen.WorkspacePreview(entry.path)) },
                    onShare = { shareOrToast(context, store.resolveForUi(entry.path), s.shareFailed) },
                    onDelete = { deleteTarget = entry },
                )
            }
        }
    }

    deleteTarget?.let { entry ->
        ConfirmDialog(
            title = s.delete,
            text = "${entry.path}\n${s.workspaceDeleteConfirm}",
            onConfirm = {
                scope.launch {
                    store.delete(entry.path)
                    reload++
                }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (showClear) {
        ConfirmDialog(
            title = s.clear,
            text = s.workspaceDeleteConfirm,
            onConfirm = {
                showClear = false
                scope.launch {
                    store.list().forEach { store.delete(it.path) }
                    reload++
                }
            },
            onDismiss = { showClear = false },
        )
    }
}

@Composable
private fun FileRow(
    entry: WorkspaceStore.Entry,
    isArtifact: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isArtifact) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .tap(onClick = withHaptic(onOpen))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = entry.path,
            style = MonoSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${entry.size} B · ${Fmt.relative(entry.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (canPreview(entry.path)) {
                Chip(text = s.workspacePreview, onClick = onOpen)
                Spacer(Modifier.width(4.dp))
            }
            Chip(text = s.workspaceShare, onClick = onShare)
            Spacer(Modifier.width(4.dp))
            Chip(text = s.delete, onClick = onDelete)
        }
    }
}
/**
 * 文件预览页。
 * HTML / 图片交给 WebView；其它文本类型用等宽文本直接展示。
 */
@Composable
fun WorkspacePreviewScreen(
    container: AppContainer,
    path: String,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val store = container.workspaceStore
    val file = remember(path) { store.resolveForUi(path) }

    var textContent by remember(path) { mutableStateOf<String?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }

    val mediaPreview = canPreview(path)

    LaunchedEffect(path) {
        if (file == null || !file.exists()) {
            loadError = "文件不存在或路径非法：$path"
            return@LaunchedEffect
        }
        if (!mediaPreview) {
            textContent = store.read(path, maxChars = 60_000) ?: "（读取失败）"
        }
    }

    WatchScaffold(
        title = path.substringAfterLast('/').take(22),
        onBack = { nav.pop() },
        actions = {
            IconTap(Icons.Filled.Share, s.workspaceShare) { shareOrToast(context, file, s.shareFailed) }
        },
    ) {
        when {
            loadError != null -> EmptyState(title = loadError!!)

            mediaPreview -> HtmlPreview(
                url = "file://" + (file?.absolutePath.orEmpty()),
                modifier = Modifier.fillMaxSize(),
            )

            textContent != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = textContent.orEmpty(),
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            else -> EmptyState(title = s.workspaceNoPreview, hint = path)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreview(url: String, modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = WebViewClient()
                loadUrl(url)
                webView = this
            }
        },
        update = { view -> if (view.url != url) view.loadUrl(url) },
    )

    // 离开页面就把 WebView 彻底销毁，避免它在手表上常驻占内存
    DisposableEffect(url) {
        onDispose {
            runCatching {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
            }
            webView = null
        }
    }
}

/**
 * 导出的统一入口。
 * 以前这里是 `runCatching { shareFile(...) }` —— 失败被静默吞掉，
 * 用户点了导出什么都没发生，也没法判断是「没应用能接」还是「文件根本不在可导出目录里」。
 */
private fun shareOrToast(context: android.content.Context, file: java.io.File?, failPrefix: String) {
    val result = shareFile(context, file)
    if (result is ShareResult.Fail) {
        Toast.makeText(context, "$failPrefix：${result.reason}", Toast.LENGTH_LONG).show()
    }
}

/** 产物路径判断。产物在 `dist/` 下，而会话目录让它是 `progress/xxx/dist/...` */
private fun isArtifactPath(path: String): Boolean =
    path.startsWith("dist/") || path.contains("/dist/")

/**
 * 挑构建入口。
 *
 * @return (入口路径, 基准目录)。入口路径是**相对基准目录**的 —— 这正是
 *   [ProjectBuilder.build] 期望的格式，也是模型在工具调用里使用的格式。
 */
private fun pickEntry(
    entries: List<WorkspaceStore.Entry>,
    sessionDir: String,
): Pair<String, String> {
    val base = sessionDir.trim().trim('/')
    if (base.isNotEmpty()) {
        val prefix = "$base/"
        val inSession = entries.filter { it.path.startsWith(prefix) }
            .filterNot { isArtifactPath(it.path.removePrefix(prefix)) }
        inSession.firstOrNull { it.path == prefix + "index.html" }
            ?.let { return "index.html" to base }
        inSession.firstOrNull { it.path.endsWith(".html", ignoreCase = true) }
            ?.let { return it.path.removePrefix(prefix) to base }
    }

    // 会话目录里还没有东西：退回工作空间根目录（老数据都在这里）
    entries.firstOrNull { it.path == "index.html" }?.let { return "index.html" to "" }
    entries.firstOrNull { it.path.endsWith(".html", ignoreCase = true) && !isArtifactPath(it.path) }
        ?.let { return it.path to "" }
    return "index.html" to base
}
