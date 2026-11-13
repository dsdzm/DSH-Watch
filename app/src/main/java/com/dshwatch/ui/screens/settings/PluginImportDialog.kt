package com.dshwatch.ui.screens.settings

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.plugin.PluginArchive
import com.dshwatch.domain.plugin.PluginImporter
import com.dshwatch.domain.plugin.PluginLoader
import com.dshwatch.domain.plugin.PluginManifest
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.GlassDialogRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 导入一份插件。
 *
 * ## 四条路，按「省事程度」排
 *
 * | 入口 | 适用场景 |
 * |---|---|
 * | 选择文件 | 手上有 `.tgz` / `.json`，文件管理器翻得到 |
 * | 工作空间 | 手表 ROM 没有文件管理器（一部分 Wear 就是这样），包已经放进工作空间 |
 * | 剪贴板 | 在手机上看中一份 JSON，复制过来粘一下 |
 * | 链接 | 从聊天记录里复制一条 raw 地址 |
 *
 * ## 为什么必须有「工作空间」这条
 *
 * `ACTION_OPEN_DOCUMENT` 依赖系统里装着一个文件管理器。一部分手表 ROM 把它
 * 整个删了 —— 用户点「选择文件」什么都不发生，看起来就是按钮坏了。
 * 附件功能踩过这个坑（见 [com.dshwatch.domain.files.DeviceFiles] 的注释），
 * 这里从一开始就给一条不依赖第三方 App 的备选：直接列工作空间里的文件。
 *
 * ## 解析失败不关窗
 *
 * 导入失败最常见的原因是「拿错了东西」（网页而不是 raw、npm 包而不是声明）。
 * 关掉窗口、只在设置页闪一行，用户就得从头再来；留着窗口把原因写在下面，
 * 他能就地重试。
 */
@Composable
fun PluginImportDialog(
    workspace: WorkspaceStore,
    onDismiss: () -> Unit,
    onImported: (PluginManifest) -> Unit,
) {
    var mode by remember { mutableStateOf(Mode.MENU) }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<WorkspaceStore.Entry>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val clip = remember { readClip(context) }

    /** 统一处理一次解析结果：成功就交出去并关窗，失败就地显示 */
    fun accept(result: PluginLoader.Parsed) {
        when (result) {
            is PluginLoader.Parsed.Err -> error = result.message
            is PluginLoader.Parsed.Ok -> {
                onImported(result.manifest)
                onDismiss()
            }
        }
    }

    /**
     * 把一堆字节交给解析器。
     *
     * 文件名的用途只有一个：出错时告诉用户「是哪个文件不对」。
     * 解析本身按内容判断，不看扩展名 —— 把 .json 改名成 .tgz 是很常见的操作。
     */
    fun absorb(bytes: ByteArray, name: String) {
        scope.launch {
            busy = true
            error = null
            val r = withContext(Dispatchers.IO) {
                runCatching { PluginArchive.parse(bytes, name) }
                    .getOrElse { t -> PluginLoader.Parsed.Err("读取失败：${t.message.orEmpty().take(80)}") }
            }
            busy = false
            accept(r)
        }
    }

    fun absorb(uri: Uri) {
        scope.launch {
            busy = true
            error = null
            val name = displayName(context, uri) ?: "选中的文件"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                        readAtMost(it, PluginArchive.MAX_BYTES)
                    } ?: return@runCatching PluginLoader.Parsed.Err("打不开这个文件，换一个再试。")
                    PluginArchive.parse(bytes, name)
                }.getOrElse { t ->
                    PluginLoader.Parsed.Err("读取失败：${t.message.orEmpty().take(80)}")
                }
            }
            busy = false
            accept(r)
        }
    }

    /*
     * 系统文件选择器。
     *
     * mime 给「任意类型」：`.tgz` 在不同 ROM 上被标成 application/gzip、
     * application/x-compressed-tar、application/octet-stream 的都有，
     * 限死任何一个类别都会让文件变灰选不中。真正的过滤在解析阶段做。
     */
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { absorb(it) }
    }

    LaunchedEffect(mode) {
        if (mode == Mode.WORKSPACE && files == null) {
            files = runCatching { workspace.list("") }.getOrDefault(emptyList())
                .filter { isPluginFile(it.path) }
                .sortedBy { it.path }
        }
    }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = when (mode) {
            Mode.MENU -> "导入插件"
            Mode.URL -> "从链接导入"
            Mode.WORKSPACE -> "工作空间里的插件"
        },
        maxContentHeight = 220,
        buttons = {
            if (mode != Mode.MENU) {
                GlassDialogButton(label = "返回") {
                    mode = Mode.MENU
                    error = null
                }
                Spacer(Modifier.width(6.dp))
            }
            if (mode == Mode.MENU || mode == Mode.WORKSPACE) {
                GlassDialogButton(label = "取消", onClick = onDismiss)
            } else {
                GlassDialogButton(
                    label = if (busy) "导入中…" else "导入",
                    primary = true,
                ) {
                    if (busy) return@GlassDialogButton
                    busy = true
                    error = null
                    scope.launch {
                        val r = PluginImporter.fromUrl(url)
                        busy = false
                        accept(r)
                    }
                }
            }
        },
        content = {
            when (mode) {
                Mode.MENU -> {
                    GlassDialogRow(
                        title = "选择文件",
                        subtitle = ".tgz / .tar.gz / .json",
                        enabled = !busy,
                        onClick = {
                            error = null
                            runCatching { picker.launch(arrayOf("*/*")) }.onFailure {
                                error = "这台手表没有可用的文件管理器，改用「工作空间」。"
                            }
                        },
                    )
                    GlassDialogRow(
                        title = "从工作空间选",
                        subtitle = "把包放进工作空间，在这里点开",
                        enabled = !busy,
                        onClick = {
                            files = null
                            mode = Mode.WORKSPACE
                            error = null
                        },
                    )
                    GlassDialogRow(
                        title = "从剪贴板粘贴",
                        subtitle = when {
                            clip == null -> "剪贴板里没有文本"
                            clip.length > 200 -> "${clip.take(60)}…（${clip.length} 字符）"
                            else -> clip.replace('\n', ' ').take(60)
                        },
                        enabled = clip != null && !busy,
                        onClick = { clip?.let { accept(PluginLoader.parse(it, "剪贴板")) } },
                    )
                    GlassDialogRow(
                        title = "从链接导入",
                        subtitle = "gist 或仓库的 raw 地址",
                        enabled = !busy,
                        onClick = {
                            mode = Mode.URL
                            error = null
                        },
                    )
                }

                Mode.WORKSPACE -> {
                    val list = files
                    when {
                        list == null -> Text(
                            text = "正在读取…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        list.isEmpty() -> Text(
                            text = "工作空间里没有 .json / .tgz。用「文件」页把包传进来，" +
                                "或者回到上一步用「选择文件」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        else -> list.take(30).forEach { e ->
                            GlassDialogRow(
                                title = e.path.substringAfterLast('/'),
                                subtitle = humanSize(e.size),
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        error = null
                                        val r = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val f = File(workspace.rootDir(), e.path)
                                                val bytes = f.inputStream().use {
                                                    readAtMost(it, PluginArchive.MAX_BYTES)
                                                }
                                                PluginArchive.parse(bytes, e.path.substringAfterLast('/'))
                                            }.getOrElse { t ->
                                                PluginLoader.Parsed.Err("读取失败：${t.message.orEmpty().take(80)}")
                                            }
                                        }
                                        busy = false
                                        accept(r)
                                    }
                                },
                            )
                        }
                    }
                }

                Mode.URL -> {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("https://…", style = MaterialTheme.typography.labelSmall)
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.15f),
                        ),
                    )
                    Text(
                        text = "要 raw 地址：打开页面点「Raw」，再复制地址栏。" +
                            "复制的是网页地址的话，拉回来是一整页 HTML。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                        .padding(8.dp),
                )
            }
        },
    )
}

/**
 * 移除导入的插件。
 *
 * 目录扫描来的插件删文件就没了，导入的没有对应文件 —— 没有这个入口，
 * 它们只能靠清空设置才删得掉。
 *
 * 不做二次确认：这个动作的代价是「重新粘一次」，而手表上连点两次确认
 * 比删错了更烦人。
 */
@Composable
fun PluginRemoveDialog(
    manifests: List<PluginManifest>,
    onDismiss: () -> Unit,
    onRemove: (PluginManifest) -> Unit,
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "移除已导入的插件",
        buttons = { GlassDialogButton(label = "关闭", onClick = onDismiss) },
        content = {
        if (manifests.isEmpty()) {
            Text(
                text = "没有导入的插件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        manifests.forEach { m ->
            GlassDialogRow(
                title = m.name.ifBlank { m.id },
                subtitle = m.id,
                onClick = { onRemove(m) },
            )
        }
        },
    )
}

private enum class Mode { MENU, URL, WORKSPACE }

/** 工作空间列表里只显示可能是插件的文件 */
private fun isPluginFile(path: String): Boolean {
    val p = path.lowercase()
    return p.endsWith(".json") || p.endsWith(".tgz") || p.endsWith(".tar.gz")
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/**
 * 读一个流，最多 [limit] 字节。
 *
 * 超限直接抛异常而不是截断：截断后的 gzip 一定解不开，用户会拿到
 * 「文件损坏」这种指向错误方向的说法。
 */
private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw IllegalArgumentException("文件超过 ${limit / 1024 / 1024} MB")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** SAF 给的文件名，只用于报错时指名道姓 */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

/**
 * 读剪贴板。
 *
 * 用系统 API 而不是 Compose 的 `LocalClipboardManager`：后者的包名和类型
 * 在 1.6 → 1.7 之间换过一次（`androidx.compose.ui.platform.ClipboardManager`
 * 移到了 `androidx.compose.ui.platform.Clipboard`），而这个项目要在
 * 多个 Compose 版本上都能编过。系统 API 十年没变过。
 */
private fun readClip(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}.getOrNull()
