package com.dshwatch.ui.screens.settings

import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * ## 只有一个「选择图片」
 *
 * 曾经给过两个入口（系统相册 / 内置浏览器）。那是上一版的临时办法 ——
 * 为了让华为手表不闪退，多加了一条路，结果同一件事有两个按钮，用户还得
 * 自己判断点哪个。现在选哪条路由设置里的「用内置文件浏览器」统一决定
 * （见 [com.dshwatch.ui.screens.files.rememberFilePicking]），这里只需要一个按钮。
 *
 * ## 为什么选完图不立刻关掉弹窗
 *
 * 图导入完，用户十有八九还要调遮罩浓度 —— 默认的 0.45 在不同照片上观感差别很大
 * （雪景偏亮、夜景偏暗）。选完就关，等于让他再进来一次。
 *
 * ## 处理期间禁止关闭
 *
 * 解码一张照片在手表上要几百毫秒到一秒。这段时间里如果用户点了外面，
 * 协程还在跑、设置还在改，而用户以为「没选成功又点了一次」——
 * 最后两张图都写进磁盘，设置指向后一张。所以 busy 时把 dismiss 挡掉。
 *
 * ## 为什么浏览器由调用方打开
 *
 * 本弹窗是一个独立 Window 的 `Dialog`，层级高于 Activity 的内容。内置浏览器
 * 挂在 Activity 内容里（见 [com.dshwatch.ui.screens.files.FileBrowserHost]）——
 * 它会被弹窗整块挡住。所以顺序必须是「先收起弹窗，再开浏览器，选完把文件
 * 递回来重新打开弹窗」（[picked] / [onPickedConsumed]）。
 */
@Composable
internal fun BackgroundDialog(
    settings: AppSettings,
    update: Update,
    onDismiss: () -> Unit,
    /** 挑中的图。本弹窗消费完会调 [onPickedConsumed] 清空 */
    picked: Uri? = null,
    onPickedConsumed: () -> Unit = {},
    /** 收起弹窗并唤出选择器。由调用方实现（见文件头最后一段） */
    onOpenBrowser: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val has = settings.customBackground.isNotBlank()

    /**
     * 把一张图导入进来。
     *
     * 系统选择器给 `content://`，内置浏览器给 `file://` —— 两条都收敛到这一个
     * 函数，后面的「写盘 / 换设置 / 删旧图」只有一份。
     */
    fun importFrom(uri: Uri) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            note = null
            val name = runCatching { CustomBackground.import(context, uri) }.getOrNull()
            busy = false
            if (name == null) {
                error = "这张图读不出来，换一张试试（HEIC 之外的常见格式都支持）"
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

    // 选完把文件递回来：弹窗重新出现，这里直接把导入跑起来
    LaunchedEffect(picked) {
        if (picked != null) {
            importFrom(picked)
            onPickedConsumed()
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
                subtitle = if (settings.builtinFilePicker) {
                    "在应用内列出图片，不需要系统文件管理器"
                } else {
                    "打开系统相册 / 文件管理器"
                },
                onClick = onOpenBrowser,
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
