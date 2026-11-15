package com.dshwatch.ui.screens.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import com.dshwatch.BuildConfig
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.update.SaveState
import com.dshwatch.domain.update.UpdateInstall
import com.dshwatch.domain.update.UpdateChannel
import com.dshwatch.domain.update.UpdateState
import com.dshwatch.domain.update.UpdateStore
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.KeyValueRow
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import java.util.Locale

/**
 * 更新页。
 *
 * 为什么是独立页而不是对话框：更新说明动辄七八条，205dp 的圆表盘上塞进对话框
 * 只能靠内滚动，而对话框本身又套在滚动列表上，两层手势会打架。独立页有完整的
 * 垂直空间，滚起来也就是普通的列表滚动。
 *
 * 页面上永远先给「当前版本」—— 用户点进来第一个想知道的是「我现在是什么版本、值不值得更」。
 */
@Composable
fun UpdateScreen(container: AppContainer, nav: WatchNavigator) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val center = container.updateCenter

    val state by center.state.collectAsStateWithLifecycle()
    val save by center.saveState.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    var noSettingsPage by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }

    /*
     * 存到公共「下载」目录需要写权限，但**只在 Android 9 及以下**：Android 10 起
     * 走 MediaStore 不需要权限。所以这里不会在大多数设备上弹窗，只有老设备会。
     *
     * 无论用户点允许还是拒绝都继续保存 —— 拒绝时 [UpdateStore] 会退到应用外部目录，
     * 那个位置 `adb pull` 照样能取到。让「拒绝权限」等于「功能不可用」是最糟的设计。
     */
    val writePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> center.saveToLocal() }

    /*
     * 进页面自动查一次，但只在「什么都没查过」时：
     * 用户刚在关于页看到「有新版本」点进来，状态已经是 Available，
     * 再查一次既慢又会把已有结果闪成「正在检查」。
     */
    LaunchedEffect(Unit) {
        center.restoreFromCache()
        if (center.state.value is UpdateState.Idle) center.checkNow()
    }

    fun startInstall() {
        localError = ""
        // 每次实时查：用户可能刚在系统设置里关掉授权
        if (!UpdateInstall.canInstall(context)) {
            noSettingsPage = !UpdateInstall.openPermissionSettings(context)
            return
        }
        if (!center.install()) {
            localError = s.updateInstallFailed
        }
    }

    fun startSave() {
        localError = ""
        if (UpdateStore.needsWritePermission(context)) {
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        center.saveToLocal()
    }

    WatchScaffold(title = s.updateTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            SettingCard {
                KeyValueRow(s.updateCurrent, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                // 「上次检查」写在版本下面：用户点进来最常见的两个问题就是
                // 「我是什么版本」和「上次到底查过没有」。
                KeyValueRow(s.updateLastCheck, lastCheckText(settings.lastUpdateCheckAt))
                // 通道写在页面上：用户切到测试版之后，过几天会忘记自己收的是哪条线，
                // 而「为什么我的版本号比别人的高」这个问题只能在这里找到答案
                KeyValueRow(
                    s.updateChannel,
                    if (UpdateChannel.of(settings.updateChannel) == UpdateChannel.BETA) {
                        s.updateChannelBeta
                    } else {
                        s.updateChannelStable
                    },
                )
                (state as? UpdateState.Available)?.let { available ->
                    KeyValueRow(
                        s.updateLatest,
                        "${available.manifest.versionName} (${available.manifest.versionCode})",
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            when (val now = state) {
                UpdateState.Idle, UpdateState.Checking -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = s.updateChecking,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                UpdateState.UpToDate -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = s.updateUpToDate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(text = s.updateCheck) { center.checkNow() }
                }

                is UpdateState.Failed -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = now.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(text = s.updateRetry) { center.checkNow() }
                }

                is UpdateState.Available -> {
                    val manifest = now.manifest
                    Spacer(Modifier.height(4.dp))
                    SectionHeader(s.updateNotes)
                    SettingCard {
                        if (manifest.notes.isEmpty()) {
                            Text(
                                text = "—",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            manifest.notes.forEach { note ->
                                Text(
                                    text = "· $note",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                        if (manifest.size > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = humanSize(manifest.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(text = s.updateDownloadInstall) { center.download() }

                    if (noSettingsPage) {
                        Spacer(Modifier.height(8.dp))
                        PermissionHint(
                            text = s.updateNoSettings,
                            command = UpdateInstall.adbCommand(context.packageName),
                            copiedLabel = s.updateCopied,
                            onCopy = { cmd ->
                                clipboard.setText(AnnotatedString(cmd))
                            },
                        )
                    } else if (!UpdateInstall.canInstall(context)) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = s.updateNeedPermission,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                is UpdateState.Downloading -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (now.progress < 0f) {
                            s.updateDownloading
                        } else {
                            "${s.updateDownloading} ${(now.progress * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (now.progress < 0f) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { now.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is UpdateState.Ready -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = s.updateReady,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    PrimaryButton(text = s.updateInstall) { startInstall() }

                    /*
                     * 「保存安装包」永远排在「安装」下面。
                     *
                     * 它不是给正常人用的第二个选项 —— 手表上「安装未知应用」这个设置页
                     * 经常根本不存在，那时「安装」按钮会一路走到「无法调起系统安装器」，
                     * 用户手里什么都没有。这条退路必须一直在，且一步就能点到。
                     */
                    Spacer(Modifier.height(6.dp))
                    GhostButton(text = s.updateSaveLocal) { startSave() }

                    when (val sv = save) {
                        SaveState.Saving -> {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = s.updateSaving,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is SaveState.Failed -> {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = s.updateSaveFailed + sv.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        is SaveState.Saved -> {
                            Spacer(Modifier.height(8.dp))
                            SavedPanel(sv)
                        }

                        SaveState.Idle -> Unit
                    }

                    if (noSettingsPage) {
                        Spacer(Modifier.height(8.dp))
                        PermissionHint(
                            text = s.updateNoSettings,
                            command = UpdateInstall.adbCommand(context.packageName),
                            copiedLabel = s.updateCopied,
                            onCopy = { cmd -> clipboard.setText(AnnotatedString(cmd)) },
                        )
                    }
                }
            }

            if (localError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = localError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 只在「没有待办」的三种状态下给重查入口；Available / Ready 下
            // 再查一次会把下载或安装按钮直接顶掉
            if (state is UpdateState.Idle || state is UpdateState.UpToDate || state is UpdateState.Failed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = s.updateCheck,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tap(onClick = withHaptic { center.checkNow() })
                        .padding(vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * 「这台设备没有设置页」时的兜底：给一条能复制的 adb 命令。
 *
 * 手表上没法执行命令，但用户会拿手机/电脑连 adb —— 让他能一键复制粘贴，
 * 比让他照着屏幕一个字一个字敲现实得多。
 */
@Composable
private fun PermissionHint(
    text: String,
    command: String,
    copiedLabel: String,
    onCopy: (String) -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    SettingCard {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = command,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .tap(
                        onClick = {
                            onCopy(command)
                            copied = true
                        },
                    ),
            )
            Text(
                text = if (copied) copiedLabel else "⧉",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 「已保存到本地」：一条路径 + 一条能直接粘贴的命令。
 *
 * 为什么不弹个 Toast 说「保存成功」就完事：用户真正的下一步是**把包弄去装**，
 * 而这一步发生在手表之外的设备上。所以这里必须把「东西在哪」「怎么用」写全 ——
 * 路径可点即复制，命令也可点即复制，中间不需要任何记忆。
 */
@Composable
private fun SavedPanel(saved: SaveState.Saved) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val store = UpdateStore.Saved(saved.file, saved.place)

    var copied by remember { mutableStateOf(false) }
    SettingCard {
        Text(
            text = s.updateSavedTo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = store.path,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .tap(
                        onClick = {
                            clipboard.setText(AnnotatedString(store.path))
                            copied = true
                        },
                    ),
            )
            Text(
                text = if (copied) s.updateCopied else "⧉",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    val install = UpdateStore.adbInstallCommand(store)
    PermissionHint(
        text = if (install != null) s.updateInstallByAdb else s.updatePullByAdb,
        command = install ?: UpdateStore.adbPullCommand(store),
        copiedLabel = s.updateCopied,
        onCopy = { clipboard.setText(AnnotatedString(it)) },
    )
}

/** 包大小，一行说完 */
private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * 上次检查的时间。
 *
 * 不写"3 小时前"这种相对时间：手表上的显示要能对照系统时间核对，
 * 而且相对时间需要翻译（中文「3 小时前」/英文「3 hours ago」），
 * 一个日期串两种语言都能读。
 */
private fun lastCheckText(at: Long): String =
    if (at <= 0L) "—"
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))
