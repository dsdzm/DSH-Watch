package com.dshwatch.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.data.history.ArchiveException
import com.dshwatch.data.history.DeepSeekExporter
import com.dshwatch.data.history.DeepSeekImporter
import com.dshwatch.ui.components.ConfirmDialog
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.i18n.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据管理：把历史对话搬进来、搬出去。
 *
 * ## 为什么值得单独一组
 *
 * 「导入历史」是换客户端时唯一的门槛。用户不会为了试一个新界面，把半年的
 * 对话丢在原来那个 App 里 —— 所以他根本不会试。这一页解决的就是这件事：
 * 从 DeepSeek 导出的 zip 直接读进来，反过来也能导出去（导出的文件在官方
 * demo 里能打开，不再只能进不能出）。
 *
 * ## 为什么导入是「追加」而不是「覆盖」
 *
 * 官方 demo 的导入会清空本地再写入 —— 它是个演示页面，本来就是空的，
 * 覆盖没有代价。这边不一样：用户可能已经聊了几百轮，一次误操作全没。
 * 所以导入只新增会话，同名不做去重（重复导入的代价是多几条记录，比丢数据轻）。
 */
@Composable
internal fun ColumnScope.DataGroup(container: AppContainer, s: AppStrings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var pendingExport by remember { mutableStateOf(false) }

    fun import(uri: Uri) {
        if (busy.isNotEmpty()) return
        scope.launch {
            busy = "正在读取归档…"
            note = ""
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw ArchiveException(ArchiveException.Code.NOT_ARCHIVE, "打不开这个文件")
                    input.use { DeepSeekImporter(context.filesDir, container.conversationStore).import(it) }
                }
            }
            busy = ""
            note = outcome.fold(
                onSuccess = { r ->
                    buildString {
                        append("已导入 ${r.sessions} 个对话、${r.messages} 条消息")
                        if (r.images > 0) append("，${r.images} 张图片")
                        if (r.skipped > 0) append("；${r.skipped} 个对话读不出来，已跳过")
                    }
                },
                onFailure = { e ->
                    (e as? ArchiveException)?.message ?: "导入失败：${e.message.orEmpty().take(60)}"
                },
            )
        }
    }

    fun export(uri: Uri) {
        scope.launch {
            busy = "正在打包…"
            note = ""
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("无法写入这个位置")
                    out.use { DeepSeekExporter(container.conversationStore).export(it) }
                }
            }
            busy = ""
            note = outcome.fold(
                onSuccess = { r ->
                    val mb = r.bytes / 1024.0 / 1024.0
                    buildString {
                        append("已导出 ${r.sessions} 个对话、${r.messages} 条消息")
                        if (r.images > 0) append("，${r.images} 张图片")
                        append("（%.1f MB）".format(mb))
                    }
                },
                onFailure = { "导出失败：${it.message.orEmpty().take(60)}" },
            )
        }
    }

    // 导入：不限 mime。手表与部分 ROM 的文件管理器不认 zip 的类型，
    // 限死 "application/zip" 会让列表变空，用户以为文件不在那儿
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingImport = it }
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let { export(it) }
    }

    SettingCard {
        SettingRow(
            title = if (s.isChinese) "导入 DeepSeek 历史" else "Import DeepSeek history",
            subtitle = if (s.isChinese) {
                "选官方导出的 deepseek_data-*.zip，整包读进来"
            } else {
                "Read a deepseek_data-*.zip exported from the official app"
            },
            trailing = "＋",
            showDivider = true,
            onClick = {
                if (busy.isEmpty()) {
                    runCatching { importPicker.launch(arrayOf("*/*")) }
                        .onFailure { note = "这台设备没有文件选择器" }
                }
            },
        )
        SettingRow(
            title = if (s.isChinese) "导出全部对话" else "Export all chats",
            subtitle = if (s.isChinese) {
                "打包成同一套格式，官方 demo 也能打开"
            } else {
                "Same archive format — the official demo can open it"
            },
            trailing = "↑",
            onClick = { if (busy.isEmpty()) pendingExport = true },
        )
    }

    if (busy.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = busy,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (note.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = if (s.isChinese) {
            "导入是追加，不会覆盖现有的对话。图片随归档一起带进来；" +
                "PDF、Word 这类文件在官方导出里本来就只有文件名，正文不在里面。"
        } else {
            "Import appends instead of replacing. Images come along; " +
                "PDF/Word files carry only their names in the official export."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    if (pendingImport != null) {
        ConfirmDialog(
            title = if (s.isChinese) "导入历史对话" else "Import history",
            text = if (s.isChinese) {
                "将把这份归档里的对话追加进来，现有的对话不受影响。对话多的时候要等一会儿。"
            } else {
                "Chats in this archive will be added. Existing chats stay untouched."
            },
            confirmText = if (s.isChinese) "导入" else "Import",
            destructive = false,
            onConfirm = {
                val uri = pendingImport
                pendingImport = null
                uri?.let { import(it) }
            },
            onDismiss = { pendingImport = null },
        )
    }

    if (pendingExport) {
        ConfirmDialog(
            title = if (s.isChinese) "导出全部对话" else "Export all chats",
            text = if (s.isChinese) {
                "会生成一个 zip，包含全部对话与本地图片。API Key 与设置不会写进去。"
            } else {
                "Creates a zip with all chats and local images. No API keys, no settings."
            },
            confirmText = if (s.isChinese) "选择位置" else "Choose",
            destructive = false,
            onConfirm = {
                pendingExport = false
                runCatching { exportPicker.launch(DeepSeekExporter.fileName()) }
                    .onFailure { note = "这台设备没有保存位置可选" }
            },
            onDismiss = { pendingExport = false },
        )
    }
}
