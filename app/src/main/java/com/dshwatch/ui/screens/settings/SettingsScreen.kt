package com.dshwatch.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.PerfProfiles
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.domain.model.WorkspaceLocation
import com.dshwatch.ui.components.AgentModeDialog
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SegmentedRow
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.StepperRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    profile: DeviceProfile,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var storageText by remember { mutableStateOf("…") }
    var showAgentDialog by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    val tools = container.toolRegistry.collectAsStateWithLifecycle().value.tools

    // 工作空间：内部路径固定，外部可能为 null（部分 ROM 没有外部私有目录）
    val externalWorkspacePath = remember { container.workspacePaths().second }
    // 位置切换后 rootPath 会变，所以把当前选择也作为 key
    val workspacePath = remember(settings.workspaceLocation) { container.workspaceStore.rootPath() }
    LaunchedEffect(Unit) {
        storageText = withContext(Dispatchers.IO) { humanSize(container.appFilesDir()) }
    }

    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { container.settingsRepo.update(block) }
    }

    val perf = PerfProfiles.of(settings.perfMode, profile.tier)

    WatchScaffold(title = s.settingsTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            // ---------------- 入口 ----------------
            SectionHeader("快捷入口")
            SettingCard {
                SettingRow(
                    title = s.providersTitle,
                    subtitle = settings.activeProvider?.name ?: "未配置",
                    trailing = "›",
                    showDivider = true,
                    onClick = { nav.push(Screen.Providers) },
                )
                SettingRow(
                    title = s.usageTitle,
                    subtitle = "查看 token 消耗与估算花费",
                    trailing = "›",
                    showDivider = true,
                    onClick = { nav.push(Screen.Usage) },
                )
                SettingRow(
                    title = s.pairTitle,
                    subtitle = "连接电脑上的 DSH",
                    trailing = "›",
                    showDivider = true,
                    onClick = { nav.push(Screen.Pair) },
                )
                SettingRow(
                    title = s.navWorkspace,
                    subtitle = "AI 生成的文件与构建产物",
                    trailing = "›",
                    showDivider = true,
                    onClick = { nav.push(Screen.Workspace) },
                )
                SettingRow(
                    title = s.navDiagnostics,
                    subtitle = "连不上时先跑这个，能看到每一步的原始报错",
                    trailing = "›",
                    onClick = { nav.push(Screen.Diagnostics) },
                )
            }

            // ---------------- 外观 ----------------
            SectionHeader(s.groupAppearance)
            SettingCard {
                Text(
                    text = s.theme,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
                SegmentedRow(
                    options = listOf(s.themeSystem, s.themeDark, s.themeLight),
                    selectedIndex = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> 0
                        ThemeMode.DARK -> 1
                        ThemeMode.LIGHT -> 2
                    },
                    onSelect = { index ->
                        update {
                            it.copy(
                                themeMode = when (index) {
                                    0 -> ThemeMode.SYSTEM
                                    1 -> ThemeMode.DARK
                                    else -> ThemeMode.LIGHT
                                }
                            )
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    title = s.dynamicColor,
                    subtitle = s.dynamicColorHint,
                    checked = settings.dynamicColor,
                    onCheckedChange = { v -> update { it.copy(dynamicColor = v) } },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = s.fontSize,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                SegmentedRow(
                    options = listOf(s.fontSmall, s.fontNormal, s.fontLarge),
                    selectedIndex = when {
                        settings.fontScale < 0.96f -> 0
                        settings.fontScale > 1.06f -> 2
                        else -> 1
                    },
                    onSelect = { index ->
                        update {
                            it.copy(
                                fontScale = when (index) {
                                    0 -> 0.85f
                                    2 -> 1.12f
                                    else -> 0.92f
                                }
                            )
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                // 屏幕 R 角系统不给半径，只能估算 + 手动微调。
                // 这是唯一能解决「内容被圆角切掉」的旋钮。
                StepperRow(
                    title = s.cornerInset,
                    subtitle = s.screenInfo + "：" + profile.screenWidthPx + "×" + profile.screenHeightPx,
                    value = if (settings.cornerInsetDp < 0) {
                        s.cornerInsetAuto + "(" + profile.autoCornerInsetDp + ")"
                    } else {
                        settings.cornerInsetDp.toString() + "dp"
                    },
                    onDecrease = {
                        val cur = if (settings.cornerInsetDp < 0) profile.autoCornerInsetDp else settings.cornerInsetDp
                        update { it.copy(cornerInsetDp = (cur - 4).coerceIn(0, 32)) }
                    },
                    onIncrease = {
                        val cur = if (settings.cornerInsetDp < 0) profile.autoCornerInsetDp else settings.cornerInsetDp
                        update { it.copy(cornerInsetDp = (cur + 4).coerceIn(0, 32)) }
                    },
                )
                if (settings.cornerInsetDp >= 0) {
                    SettingRow(
                        title = s.reset,
                        trailing = s.cornerInsetAuto,
                        onClick = { update { it.copy(cornerInsetDp = -1) } },
                    )
                }
                SettingRow(
                    title = s.showMascot,
                    subtitle = s.mascotHint,
                    trailing = if (settings.showMascot) "✓" else "—",
                    showDivider = true,
                    onClick = { update { it.copy(showMascot = !it.showMascot) } },
                )
                Spacer(Modifier.height(4.dp))
            }

            // ---------------- AI Agent ----------------
            SectionHeader(s.groupAgent)
            SettingCard {
                SettingRow(
                    title = s.agentMode,
                    subtitle = settings.agentMode.desc,
                    trailing = settings.agentMode.label,
                    showDivider = true,
                    onClick = { showAgentDialog = true },
                )
                StepperRow(
                    title = s.agentMaxSteps,
                    subtitle = "单次任务最多跑几轮工具",
                    value = settings.maxAgentSteps.toString(),
                    onDecrease = {
                        update { it.copy(maxAgentSteps = (settings.maxAgentSteps - 2).coerceAtLeast(1)) }
                    },
                    onIncrease = {
                        update { it.copy(maxAgentSteps = (settings.maxAgentSteps + 2).coerceAtMost(20)) }
                    },
                )
                SettingRow(
                    title = s.agentTools,
                    subtitle = s.agentToolsHint,
                    trailing = "${tools.size} ›",
                    showDivider = showTools,
                    onClick = { showTools = !showTools },
                )
                if (showTools) {
                    tools.forEach { tool ->
                        Text(
                            text = "· ${tool.name}（${tool.safety.label}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (settings.autoApprovedTools.isNotEmpty()) {
                    SettingRow(
                        title = s.agentClearApprovals,
                        subtitle = settings.autoApprovedTools.joinToString(", "),
                        onClick = { update { it.copy(autoApprovedTools = emptySet()) } },
                    )
                }
            }

            // ---------------- 工作空间 ----------------
            SectionHeader(s.navWorkspace)
            SettingCard {
                SettingRow(
                    title = s.workspaceLocation,
                    subtitle = if (
                        settings.workspaceLocation == WorkspaceLocation.EXTERNAL &&
                        externalWorkspacePath == null
                    ) {
                        s.workspaceExternalFallback
                    } else {
                        settings.workspaceLocation.desc
                    },
                    trailing = settings.workspaceLocation.label + " ⇄",
                    showDivider = true,
                    onClick = {
                        // 两档互斥，直接切换；切换后 AppContainer 会把已有文件搬过去
                        val next = if (settings.workspaceLocation == WorkspaceLocation.INTERNAL) {
                            WorkspaceLocation.EXTERNAL
                        } else {
                            WorkspaceLocation.INTERNAL
                        }
                        update { it.copy(workspaceLocation = next) }
                    },
                )
                SettingRow(
                    title = s.workspaceRootPath,
                    subtitle = workspacePath,
                    trailing = "›",
                    onClick = { nav.push(Screen.Workspace) },
                )
            }

            // ---------------- 性能 ----------------
            SectionHeader(s.groupPerformance)
            SettingCard {
                Text(
                    text = s.perfMode,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
                SegmentedRow(
                    options = listOf(s.perfAuto, s.perfPowerSave, s.perfBalanced, s.perfPerformance),
                    selectedIndex = when (settings.perfMode) {
                        PerfMode.AUTO -> 0
                        PerfMode.POWER_SAVE -> 1
                        PerfMode.BALANCED -> 2
                        PerfMode.PERFORMANCE -> 3
                    },
                    onSelect = { index ->
                        update {
                            it.copy(
                                perfMode = when (index) {
                                    0 -> PerfMode.AUTO
                                    1 -> PerfMode.POWER_SAVE
                                    2 -> PerfMode.BALANCED
                                    else -> PerfMode.PERFORMANCE
                                }
                            )
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                SettingRow(
                    title = s.deviceTier,
                    subtitle = profile.cores.toString() + " 核 · " + (profile.totalMemMb / 1024) + " GB 内存",
                    trailing = when (profile.tier) {
                        DeviceTier.LOW -> s.tierLow
                        DeviceTier.MID -> s.tierMid
                        DeviceTier.HIGH -> s.tierHigh
                    },
                    showDivider = true,
                )
                StepperRow(
                    title = s.maxRenderedMessages,
                    subtitle = s.followProfile,
                    value = if (settings.maxRenderedMessagesOverride > 0) {
                        settings.maxRenderedMessagesOverride.toString()
                    } else {
                        "${perf.maxRenderedMessages}(A)"
                    },
                    onDecrease = {
                        update {
                            val current = if (it.maxRenderedMessagesOverride > 0) {
                                it.maxRenderedMessagesOverride
                            } else {
                                perf.maxRenderedMessages
                            }
                            it.copy(maxRenderedMessagesOverride = (current - 20).coerceAtLeast(20))
                        }
                    },
                    onIncrease = {
                        update {
                            val current = if (it.maxRenderedMessagesOverride > 0) {
                                it.maxRenderedMessagesOverride
                            } else {
                                perf.maxRenderedMessages
                            }
                            it.copy(maxRenderedMessagesOverride = (current + 20).coerceAtMost(600))
                        }
                    },
                )
                StepperRow(
                    title = s.streamThrottle,
                    subtitle = s.followProfile,
                    value = "${perf.streamThrottleMs}ms",
                    onDecrease = {
                        update {
                            val current = if (it.streamThrottleOverrideMs > 0) {
                                it.streamThrottleOverrideMs
                            } else {
                                perf.streamThrottleMs.toInt()
                            }
                            it.copy(streamThrottleOverrideMs = (current - 30).coerceAtLeast(40))
                        }
                    },
                    onIncrease = {
                        update {
                            val current = if (it.streamThrottleOverrideMs > 0) {
                                it.streamThrottleOverrideMs
                            } else {
                                perf.streamThrottleMs.toInt()
                            }
                            it.copy(streamThrottleOverrideMs = (current + 30).coerceAtMost(600))
                        }
                    },
                )
                if (settings.maxRenderedMessagesOverride > 0 || settings.streamThrottleOverrideMs > 0) {
                    SettingRow(
                        title = s.reset,
                        onClick = {
                            update {
                                it.copy(
                                    maxRenderedMessagesOverride = 0,
                                    streamThrottleOverrideMs = 0,
                                )
                            }
                        },
                    )
                }
            }

            // ---------------- 行为 ----------------
            SectionHeader(s.groupBehavior)
            SettingCard {
                SwitchRow(
                    title = s.foregroundService,
                    subtitle = s.foregroundServiceHint,
                    checked = settings.foregroundService,
                    onCheckedChange = { v -> update { it.copy(foregroundService = v) } },
                )
                SwitchRow(
                    title = s.keepScreenOn,
                    checked = settings.keepScreenOn,
                    onCheckedChange = { v -> update { it.copy(keepScreenOn = v) } },
                )
                SwitchRow(
                    title = s.autoScroll,
                    checked = settings.autoScroll,
                    onCheckedChange = { v -> update { it.copy(autoScroll = v) } },
                )
                SwitchRow(
                    title = s.showReasoning,
                    checked = settings.showReasoning,
                    onCheckedChange = { v -> update { it.copy(showReasoning = v) } },
                )
                SwitchRow(
                    title = s.showTokenBadge,
                    checked = settings.showTokenBadge,
                    onCheckedChange = { v -> update { it.copy(showTokenBadge = v) } },
                )
                SwitchRow(
                    title = s.haptics,
                    checked = settings.haptics,
                    onCheckedChange = { v -> update { it.copy(haptics = v) } },
                )
            }

            // ---------------- 数据 ----------------
            SectionHeader(s.groupData)
            SettingCard {
                SettingRow(title = s.storageUsed, trailing = storageText, showDivider = true)
                SettingRow(
                    title = s.aboutTitle,
                    trailing = "›",
                    onClick = { nav.push(Screen.About) },
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "当前生效：渲染 ${perf.maxRenderedMessages} 条 · 节流 ${perf.streamThrottleMs}ms · 代码着色 ${if (perf.enableCodeHighlight) "开" else "关"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showAgentDialog) {
        AgentModeDialog(
            current = settings.agentMode,
            maxSteps = settings.maxAgentSteps,
            onPickMode = { mode -> update { it.copy(agentMode = mode) } },
            onStepsChange = { steps -> update { it.copy(maxAgentSteps = steps) } },
            onDismiss = { showAgentDialog = false },
        )
    }
}

private fun humanSize(dir: File): String {
    if (!dir.exists()) return "0 KB"
    var total = 0L
    dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
    val kb = total / 1024.0
    return when {
        kb < 1024 -> "${kb.toInt()} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
    }
}
