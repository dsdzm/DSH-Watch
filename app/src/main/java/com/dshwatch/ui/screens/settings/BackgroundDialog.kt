package com.dshwatch.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.GlassDialogRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.WatchSlider
import com.dshwatch.ui.theme.CustomBackground
import kotlinx.coroutines.launch

/**
 * 自定义背景的入口。
 *
 * ## 为什么选完图不立刻关掉弹窗
 *
 * 图导入完，用户十有八九还要调遮罩浓度 —— 默认的 0.45 在不同照片上观感差别很大
 * （雪景偏亮、夜景偏暗）。选完就关，等于让他再进来一次。
 *
 * ## 处理期间禁止关闭
 *
 * 解码一张照片在手表上要几百毫秒到一秒。这段时间里如果用户点了外面，
 * 协程还在跑、设置还在改，而用户以为「没选成功又点了一次」—— 最后两张图
 * 都写进磁盘，设置指向后一张。所以 busy 时把 dismiss 挡掉，只留一句「正在处理」。
 */
@Composable
internal fun BackgroundDialog(
    settings: AppSettings,
    update: Update,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val has = settings.customBackground.isNotBlank()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            note = null
            val name = runCatching { CustomBackground.import(context, uri) }.getOrNull()
            busy = false
            if (name == null) {
                error = "这张图读不出来，换一张试试（HEIF 之外的常见格式都支持）"
                return@launch
            }
            /*
             * 只删「上一张」，不做整目录清理。
             *
             * `update {}` 是扔进另一个协程里异步写盘的，这里拿不到完成时机。
             * 如果此时扫目录删掉「除新文件之外的一切」，万一那次写入失败，
             * 设置还指着刚被删掉的旧文件 —— 背景直接变空白。
             * 精确删旧的那一张，最坏情况只是留一个孤儿文件，不影响显示。
             */
            val old = settings.customBackground
            update { it.copy(customBackground = name) }
            CustomBackground.delete(context, old)
            note = "已更新。下面的遮罩浓度可以随时再调。"
        }
    }

    GlassDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "自定义背景",
        buttons = {
            GlassDialogButton(label = "关闭", primary = true, onClick = onDismiss)
        },
        content = {
            if (busy) {
                Text(
                    text = "正在处理图片…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                return@GlassDialog
            }

            GlassDialogRow(
                title = if (has) "更换图片" else "选择图片",
                subtitle = "从相册或文件里选一张",
                onClick = { picker.launch(arrayOf("image/*")) },
            )
            if (has) {
                GlassDialogRow(
                    title = "移除背景",
                    subtitle = "回到纯色底",
                    onClick = {
                        val old = settings.customBackground
                        update { it.copy(customBackground = "") }
                        CustomBackground.delete(context, old)
                        onDismiss()
                    },
                )
            }

            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (has) {
                Spacer(Modifier.height(6.dp))
                /*
                 * 遮罩浓度。
                 *
                 * 上限 0.9 而不是 1.0：1.0 等于把照片完全盖掉，那还不如不放。
                 * 下限 0 是留给「我就要原图，看不清也认了」的人 —— 这是他自己的选择。
                 */
                WatchSlider(
                    value = settings.customBackgroundDim.coerceIn(0f, 0.9f),
                    onChange = { v -> update { it.copy(customBackgroundDim = v) } },
                    valueRange = 0f..0.9f,
                    label = "背景遮罩",
                    valueText = String.format(java.util.Locale.US, "%.0f%%", settings.customBackgroundDim * 100),
                )
                SwitchRow(
                    title = "虚化背景",
                    subtitle = "读得粗再放大，文字更容易看清",
                    checked = settings.customBackgroundFrost,
                    onCheckedChange = { v -> update { it.copy(customBackgroundFrost = v) } },
                )
            }
        },
    )
}
