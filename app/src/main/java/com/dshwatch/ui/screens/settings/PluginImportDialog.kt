package com.dshwatch.ui.screens.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.domain.plugin.PluginImporter
import com.dshwatch.domain.plugin.PluginLoader
import com.dshwatch.domain.plugin.PluginManifest
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.GlassDialogRow
import kotlinx.coroutines.launch

/**
 * 导入一份插件清单。
 *
 * ## 为什么是「剪贴板优先」
 *
 * 手表没有能舒服敲 JSON 的输入法，但「在手机上看中一份插件 → 复制 → 手表粘一下」
 * 是可行的。链接输入放在第二项，因为手敲一条 URL 在 200dp 宽的表盘上是酷刑 ——
 * 它真正的用途是「从聊天记录里复制一条链接再粘过来」，而不是逐字符输入。
 *
 * ## 解析失败不关窗
 *
 * 导入失败最常见的原因是「复制错了东西」（复制到网页而不是 Raw）。
 * 这时候把窗口关掉、只在设置页闪一行提示，用户就得从头再来一遍；
 * 留着窗口并把原因写在下面，他能就地重试。
 */
@Composable
fun PluginImportDialog(
    onDismiss: () -> Unit,
    onImported: (PluginManifest) -> Unit,
) {
    var mode by remember { mutableStateOf(Mode.MENU) }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

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

    GlassDialog(
        onDismissRequest = onDismiss,
        title = if (mode == Mode.MENU) "导入插件" else "从链接导入",
        maxContentHeight = 200,
        buttons = {
            if (mode == Mode.URL) {
                GlassDialogButton(label = "返回") {
                    mode = Mode.MENU
                    error = null
                }
                Spacer(Modifier.width(6.dp))
            }
            if (mode == Mode.MENU) {
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
                    title = "从剪贴板粘贴",
                    subtitle = when {
                        clip == null -> "剪贴板里没有文本"
                        clip.length > 200 -> "${clip.take(60)}…（${clip.length} 字符）"
                        else -> clip.replace('\n', ' ').take(60)
                    },
                    enabled = clip != null,
                    onClick = { clip?.let { accept(PluginLoader.parse(it, "剪贴板")) } },
                )
                GlassDialogRow(
                    title = "从链接导入",
                    subtitle = "gist 或仓库的 raw 地址",
                    onClick = {
                        mode = Mode.URL
                        error = null
                    },
                )
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
                    text = "在手机上复制链接后，长按输入框粘贴 —— 别逐字符敲。",
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

private enum class Mode { MENU, URL }

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
