package com.dshwatch.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.dshwatch.core.Logx
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.GlassDialogRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.WatchSlider
import com.dshwatch.ui.theme.CustomBackground
import kotlinx.coroutines.launch
import java.io.File

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
 *
 * ## 两条选图通路，**都必须有**
 *
 * | 入口 | 走什么 |
 * |---|---|
 * | 从相册选 | `ACTION_OPEN_DOCUMENT`，能直接翻系统相册 / 文件管理器 |
 * | 从文件里挑 | 应用内置的 [com.dshwatch.ui.screens.files.FileBrowserPanel] |
 *
 * 第二条不是锦上添花：一部分手表 ROM（华为手表是典型）**把文件管理器整个裁掉**，
 * 没有任何 Activity 响应 `ACTION_OPEN_DOCUMENT`。`ActivityResultLauncher.launch()`
 * 此时抛 `ActivityNotFoundException`，而 Compose 的 launcher 不会替你接住 ——
 * 用户点「选择图片」，App 直接闪退。附件与插件导入早就为这个坑做了兜底
 * （见 `DeviceFiles` 的注释），只有这里漏了，所以出现「点了就崩」。
 *
 * 现在 `launch()` 包在 `runCatching` 里：失败时**不是**弹一句「不支持」，
 * 而是直接切到内置浏览器 —— 用户的目标是选一张图，不是知道哪条路不通。
 *
 * ## 为什么内置浏览器画在外层
 *
 * 它是整屏面板（`WatchScaffold` + 不透明底），而本弹窗是一个独立 Window 的
 * `Dialog`。塞进 Dialog 的内容区只会被 `maxContentHeight` 裁成一条；即使不裁，
 * Dialog 的窗口也在面板之上，面板会被整个挡住。所以由调用方在页面层托管：
 * 打开面板前先收起弹窗，选完再把文件递回来（[picked]）。
 */
@Composable
internal fun BackgroundDialog(
    settings: AppSettings,
    update: Update,
    onDismiss: () -> Unit,
    /** 内置浏览器选中的文件。本弹窗消费完会调 [onPickedConsumed] 清空 */
    picked: File? = null,
    onPickedConsumed: () -> Unit = {},
    /** 收起弹窗、打开内置浏览器。由调用方实现（见文件头最后一段） */
    onOpenBrowser: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val has = settings.customBackground.isNotBlank()

    /**
     * 把一张图导入进来。系统选择器给 URI，内置浏览器给 File ——
     * 两条都收敛到这一个函数，后面的「写盘 / 换设置 / 删旧图」只有一份。
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    // 内置浏览器选完把文件递回来：弹窗会重新出现，这里直接把导入跑起来
    LaunchedEffect(picked) {
        if (picked != null) {
            importFrom(Uri.fromFile(picked))
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
                subtitle = "调用系统相册 / 文件管理器",
                onClick = {
                    /*
                     * **必须接住**：没有 App 响应 ACTION_OPEN_DOCUMENT 时
                     * `launch()` 抛 ActivityNotFoundException，未捕获就是闪退。
                     * 失败不解释、直接给内置浏览器 —— 用户要的是选到图。
                     */
                    val ok = runCatching { picker.launch(arrayOf("image/*")) }
                        .onFailure { Logx.w("Background", "无系统选择器：${it.message}") }
                        .isSuccess
                    if (!ok) onOpenBrowser()
                },
            )
            GlassDialogRow(
                title = "从文件里挑",
                subtitle = "内置浏览器，系统没有相册也能用",
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
