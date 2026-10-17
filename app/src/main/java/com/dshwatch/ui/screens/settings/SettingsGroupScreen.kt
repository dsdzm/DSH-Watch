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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.PerfProfiles
import com.dshwatch.domain.plugin.PluginRegistry
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.domain.model.WorkspaceLocation
import com.dshwatch.ui.components.AgentModeDialog
import com.dshwatch.ui.components.SegmentedRow
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.StepperRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.AppStrings
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页的分组 id。
 *
 * 一级页（入口列表）与二级页（具体设置）共用这一份常量 ——
 * 两边各写一遍字符串是这类重构最常见的错源：改了一边忘了另一边，
 * 表现是「点进去是空的」，而且编译器不会提醒。
 */
internal object SettingsGroups {
    const val MODEL = "model"
    const val ABILITY = "ability"
    const val CHAT = "chat"
    const val APPEARANCE = "appearance"
    const val FILES = "files"
    const val RUNTIME = "runtime"
    const val CONNECTION = "connection"
    const val PLUGINS = "plugins"
    const val MCP = "mcp"

    /** 顺序即一级页的显示顺序。插件与 MCP 紧跟「AI 能力」—— 它们扩展的都是能力 */
    val all = listOf(MODEL, ABILITY, PLUGINS, MCP, CHAT, APPEARANCE, FILES, RUNTIME, CONNECTION)

    fun title(id: String, s: AppStrings): String = when (id) {
        MODEL -> s.groupModel
        ABILITY -> s.groupAbility
        CHAT -> s.groupChat
        APPEARANCE -> s.groupAppearance
        FILES -> s.groupFiles
        RUNTIME -> s.groupRuntime
        PLUGINS -> s.groupPlugins
        MCP -> s.groupMcp
        else -> s.groupConnection
    }
}

/** 设置项的统一写入口：各组不必各自持有 scope 与 repository */
internal typealias Update = ((AppSettings) -> AppSettings) -> Unit

/**
 * 某个分组的详情页。
 *
 * ## 为什么拆成二级
 *
 * 三十多项设置挤在一页时，分组标题只是"视觉分隔"，用户仍要靠滚动找东西；
 * 一级页给七个入口 + 当前状态摘要，找"主题在哪"从"滚一遍"变成"看一眼"。
 *
 * ## 为什么每组是一个独立 Composable
 *
 * 每组需要的东西不一样：外观要 `profile`、文件要算占用、AI 能力要工具清单与弹窗状态。
 * 如果都写在一个大函数里，这些状态会一直活着（即使不在当前分组）；
 * 拆开之后，**组内状态随页面销毁**，也不会把 `update()` 之类的逻辑复制七遍。
 */
@Composable
fun SettingsGroupScreen(
    groupId: String,
    container: AppContainer,
    settings: AppSettings,
    profile: DeviceProfile,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val update: Update = { block ->
        scope.launch { container.settingsRepo.update(block) }
    }

    WatchScaffold(title = SettingsGroups.title(groupId, s), onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
        ) {
            when (groupId) {
                SettingsGroups.MODEL -> ModelGroup(settings, nav, s)
                SettingsGroups.ABILITY -> AbilityGroup(settings, update, container, nav, s)
                SettingsGroups.CHAT -> ChatGroup(settings, update, s)
                SettingsGroups.APPEARANCE -> AppearanceGroup(settings, update, profile, s)
                SettingsGroups.FILES -> FilesGroup(settings, update, container, nav, s)
                SettingsGroups.RUNTIME -> RuntimeGroup(settings, update, profile, s)
                SettingsGroups.PLUGINS -> PluginsGroup(settings, update, s)
                SettingsGroups.MCP -> McpGroup(settings, update, container, s)
                else -> ConnectionGroup(settings, nav, s)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 1. 模型
// ---------------------------------------------------------------------------

@Composable
private fun ModelGroup(settings: AppSettings, nav: WatchNavigator, s: AppStrings) {
    SettingCard {
        SettingRow(
            title = s.providersTitle,
            subtitle = settings.activeProvider?.name ?: s.entryProviderUnset,
            trailing = "›",
            showDivider = true,
            onClick = { nav.push(Screen.Providers) },
        )
        SettingRow(
            title = s.usageTitle,
            subtitle = s.entryUsageDesc,
            trailing = "›",
            onClick = { nav.push(Screen.Usage) },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "这里决定「用哪个模型、花多少钱」。换个供应商后，对话页顶部也会跟着变。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// 2. AI 能力
// ---------------------------------------------------------------------------

@Composable
private fun AbilityGroup(
    settings: AppSettings,
    update: Update,
    container: AppContainer,
    nav: WatchNavigator,
    s: AppStrings,
) {
    var showAgentDialog by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    val tools = container.toolRegistry.collectAsStateWithLifecycle().value.tools

    val execSummary = if (settings.localExecEnabled || settings.cloudBuildEnabled) {
        "已开启：" + listOfNotNull(
            "本地执行".takeIf { settings.localExecEnabled },
            "云端编译".takeIf { settings.cloudBuildEnabled },
        ).joinToString("、")
    } else {
        s.execSectionDesc
    }

    SettingCard {
        SettingRow(
            title = s.agentMode,
            subtitle = settings.agentMode.desc,
            trailing = settings.agentMode.label,
            showDivider = true,
            onClick = { showAgentDialog = true },
        )
        SettingRow(
            title = s.agentMaxSteps,
            subtitle = s.entryAgentStepsDesc,
            trailing = "∞",
            showDivider = true,
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
                showDivider = true,
                onClick = { update { it.copy(autoApprovedTools = emptySet()) } },
            )
        }
        // 执行与编译：本地跑脚本、云端跑构建 —— 是「能力」，跟着 Agent 走
        SettingRow(
            title = s.execSectionTitle,
            subtitle = execSummary,
            trailing = "›",
            onClick = { nav.push(Screen.ExecSettings) },
        )
    }

    if (showAgentDialog) {
        AgentModeDialog(
            current = settings.agentMode,
            onPickMode = { mode -> update { it.copy(agentMode = mode) } },
            onDismiss = { showAgentDialog = false },
        )
    }
}

// ---------------------------------------------------------------------------
// 3. 对话
// ---------------------------------------------------------------------------

@Composable
private fun ChatGroup(settings: AppSettings, update: Update, s: AppStrings) {
    SettingCard {
        SwitchRow(
            title = s.showReasoning,
            checked = settings.showReasoning,
            onCheckedChange = { v -> update { it.copy(showReasoning = v) } },
        )
        SwitchRow(
            title = s.autoScroll,
            checked = settings.autoScroll,
            onCheckedChange = { v -> update { it.copy(autoScroll = v) } },
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
}

// ---------------------------------------------------------------------------
// 4. 外观
// ---------------------------------------------------------------------------

@Composable
private fun AppearanceGroup(
    settings: AppSettings,
    update: Update,
    profile: DeviceProfile,
    s: AppStrings,
) {
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
                showDivider = true,
                onClick = { update { it.copy(cornerInsetDp = -1) } },
            )
        }
        SettingRow(
            title = s.showMascot,
            subtitle = s.mascotHint,
            trailing = if (settings.showMascot) "✓" else "—",
            onClick = { update { it.copy(showMascot = !it.showMascot) } },
        )
        Spacer(Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------
// 5. 文件与存储
// ---------------------------------------------------------------------------

@Composable
private fun FilesGroup(
    settings: AppSettings,
    update: Update,
    container: AppContainer,
    nav: WatchNavigator,
    s: AppStrings,
) {
    var storageText by remember { mutableStateOf("…") }
    val externalWorkspacePath = remember { container.workspacePaths().second }
    val workspacePath = remember(settings.workspaceLocation) { container.workspaceStore.rootPath() }
    LaunchedEffect(Unit) {
        storageText = withContext(Dispatchers.IO) { humanSize(container.appFilesDir()) }
    }

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
            showDivider = true,
            onClick = { nav.push(Screen.Workspace) },
        )
        SettingRow(
            title = s.storageUsed,
            subtitle = s.entryStorageDesc,
            trailing = storageText,
        )
    }
}

// ---------------------------------------------------------------------------
// 6. 性能与运行
// ---------------------------------------------------------------------------

@Composable
private fun RuntimeGroup(
    settings: AppSettings,
    update: Update,
    profile: DeviceProfile,
    s: AppStrings,
) {
    val perf = PerfProfiles.of(settings.perfMode, profile.tier)

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
                showDivider = true,
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
        Spacer(Modifier.height(4.dp))
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
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = "当前生效：渲染 ${perf.maxRenderedMessages} 条 · 节流 ${perf.streamThrottleMs}ms · " +
            "代码着色 ${if (perf.enableCodeHighlight) "开" else "关"}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// 7. 插件
// ---------------------------------------------------------------------------

/**
 * 插件开关。
 *
 * 每个开关下面都写清"它到底改了模型的什么" —— 这一页真正的风险不是点错，
 * 而是**开了却不知道开了什么**，之后发现"模型怎么突然话少了/数据变样了"，
 * 却联想不到这里。
 */
@Composable
private fun PluginsGroup(settings: AppSettings, update: Update, s: AppStrings) {
    val enabled = settings.pluginsResolved(PluginRegistry.defaultEnabled())

    SettingCard {
        PluginRegistry.ALL.forEach { plugin ->
            SwitchRow(
                title = s.pluginTitle(plugin.id),
                subtitle = s.pluginDesc(plugin.id),
                checked = plugin.id in enabled,
                onCheckedChange = { on ->
                    val next = if (on) enabled + plugin.id else enabled - plugin.id
                    update { it.copy(enabledPlugins = next, pluginsCustomized = true) }
                },
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = s.pluginsHint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    // 「恢复默认」只在用户动过开关之后才出现 —— 没动过时它是个无意义的按钮
    if (settings.pluginsCustomized) {
        Spacer(Modifier.height(6.dp))
        SettingCard {
            SettingRow(
                title = "恢复默认开关",
                subtitle = "开启「结果裁剪」与「极简回复」，其余关闭",
                trailing = "↺",
                onClick = {
                    update { it.copy(pluginsCustomized = false, enabledPlugins = emptySet()) }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 8. 连接与关于
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionGroup(settings: AppSettings, nav: WatchNavigator, s: AppStrings) {
    SettingCard {
        SettingRow(
            title = s.pairTitle,
            subtitle = s.entryPairDesc,
            trailing = "›",
            showDivider = true,
            onClick = { nav.push(Screen.Pair) },
        )
        SettingRow(
            title = s.navDiagnostics,
            subtitle = s.entryDiagnosticsDesc,
            trailing = "›",
            showDivider = true,
            onClick = { nav.push(Screen.Diagnostics) },
        )
        SettingRow(
            title = s.aboutTitle,
            subtitle = s.entryAboutDesc,
            trailing = "›",
            onClick = { nav.push(Screen.About) },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "当前模型：" + (settings.activeProvider?.name ?: s.entryProviderUnset),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
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
