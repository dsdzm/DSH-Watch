package com.dshwatch.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.PerfProfiles
import com.dshwatch.domain.bili.BiliClock
import com.dshwatch.domain.bili.BiliImages
import com.dshwatch.domain.bili.BiliMode
import com.dshwatch.domain.bili.BiliModes
import com.dshwatch.domain.bili.BiliTones
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
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.AppLanguage
import com.dshwatch.ui.i18n.AppStrings
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
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
    const val BILI = "bili"

    /** 顺序即一级页的显示顺序。插件、MCP、B站 紧跟「AI 能力」—— 它们扩展的都是能力 */
    val all = listOf(MODEL, ABILITY, PLUGINS, MCP, BILI, CHAT, APPEARANCE, FILES, RUNTIME, CONNECTION)

    fun title(id: String, s: AppStrings): String = when (id) {
        MODEL -> s.groupModel
        ABILITY -> s.groupAbility
        CHAT -> s.groupChat
        APPEARANCE -> s.groupAppearance
        FILES -> s.groupFiles
        RUNTIME -> s.groupRuntime
        PLUGINS -> s.groupPlugins
        MCP -> s.groupMcp
        BILI -> s.groupBili
        else -> s.groupConnection
    }
}

/** 设置项的统一写入口：各组不必各自持有 scope 与 repository */
internal typealias Update = ((AppSettings) -> AppSettings) -> Unit

/**
 * 逗号分隔的多选题切换（动态选题用）。
 *
 * 存字符串而不是 `Set`：设置整包是 JSON 落盘的，`Set` 会被序列化成数组，
 * 老版本读到不认识的类型有整包丢弃的风险；字符串最坏只是内容读不懂。
 * 写完按固定顺序重排，避免「同样的选择、不同的字符串」让设置 diff 变脏。
 */
private fun toggleTopic(current: String, key: String): String {
    val picked = current.split(',').map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    if (!picked.remove(key)) picked.add(key)
    val order = listOf("life", "roast", "master")
    return picked.sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
        .joinToString(",")
}

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
                .wearVerticalScroll(rememberScrollState())
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
                SettingsGroups.BILI -> BiliGroup(settings, update, container, nav, s)
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
        // 语言单独给一个显式开关：跟随系统在部分手表上会判成英文，
        // 用户至少要有一条「我自己选」的路，而不是只能忍着。
        Text(
            text = s.language,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        SegmentedRow(
            options = listOf(s.langSystem, s.langZh, s.langEn),
            selectedIndex = when (settings.appLanguage) {
                AppLanguage.ZH -> 1
                AppLanguage.EN -> 2
                else -> 0
            },
            onSelect = { index ->
                update {
                    it.copy(
                        appLanguage = when (index) {
                            1 -> AppLanguage.ZH
                            2 -> AppLanguage.EN
                            else -> AppLanguage.SYSTEM
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

// ---------------------------------------------------------------------------
// 9. 刷 B站
// ---------------------------------------------------------------------------

/**
 * 刷 B站的分组页。
 *
 * 这一页只放「给不给能力」和「用什么语气」，不教模型怎么刷 ——
 * 流程写在 `BiliTools.StatusTool` 的返回里，那是它真正需要的时候才读的东西，
 * 而设置页每一行都要占用户一块小屏幕。
 */
@Composable
private fun BiliGroup(
    settings: AppSettings,
    update: Update,
    container: AppContainer,
    nav: WatchNavigator,
    s: AppStrings,
) {
    var editingKeywords by remember { mutableStateOf(false) }
    var editingCookie by remember { mutableStateOf(false) }
    var editingTone by remember { mutableStateOf(false) }
    var editingToneText by remember { mutableStateOf(false) }
    var keywordDraft by remember { mutableStateOf("") }
    var cookieDraft by remember { mutableStateOf("") }
    var toneDraft by remember { mutableStateOf("") }
    var cookieStamp by remember { mutableStateOf(0) }
    var todayCount by remember { mutableStateOf(-1) }
    var editingMode by remember { mutableStateOf(false) }
    var editingAt by remember { mutableStateOf(false) }
    var editingImage by remember { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf(false) }
    var atDraft by remember { mutableStateOf("") }
    var imageNote by remember { mutableStateOf("") }
    var editingImageText by remember { mutableStateOf(false) }
    var imageTextDraft by remember { mutableStateOf("") }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 选相册图：GetContent 比 PhotoPicker 兼容性更好 —— Wear 上不一定有 PhotoPicker，
    // 但几乎都有 DocumentsUI，实在没有会回 null，我们提示一句就好。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { BiliImages.saveLocal(context, uri) }
            imageNote = if (ok) "" else s.biliImagePickFailed
        }
    }

    // 日报是按天落的文件，条数直接数「### BV」行 —— 和工具里的配额口径必须一致，
    // 否则会出现「设置页说还有 2 条、模型却说配额用完了」这种对不上的情况。
    LaunchedEffect(settings.biliEnabled) {
        todayCount = withContext(Dispatchers.IO) {
            val f = File(container.workspaceStore.rootDir(), "bili/" + todayKey() + ".md")
            if (!f.exists()) 0 else f.readLines().count { it.startsWith("### BV") }
        }
    }
    val cookieMask = remember(cookieStamp, settings.biliUserName) {
        container.keyStore.maskedBiliCookie()
    }
    val tone = remember(settings.biliTone, settings.biliToneCustom) { BiliTones.resolve(settings) }

    SettingCard {
        SwitchRow(
            title = s.biliEnable,
            subtitle = s.biliEnableHint,
            checked = settings.biliEnabled,
            onCheckedChange = { v -> update { it.copy(biliEnabled = v) } },
        )
        SettingRow(
            title = s.biliMode,
            subtitle = when (BiliModes.of(settings)) {
                BiliMode.SUMMARY -> s.biliModeSummaryHint
                BiliMode.COMMENT -> s.biliModeCommentHint
                BiliMode.DM -> s.biliModeDmHint
            },
            trailing = "›",
            showDivider = true,
            onClick = { editingMode = true },
        )
        SwitchRow(
            title = s.biliNotifyDone,
            subtitle = s.biliNotifyDoneHint,
            checked = settings.biliNotifyDone,
            onCheckedChange = { v -> update { it.copy(biliNotifyDone = v) } },
        )
    }

    Spacer(Modifier.height(6.dp))
    SettingCard {
        StepperRow(
            title = s.biliQuota,
            subtitle = s.biliQuotaHint,
            value = if (settings.biliDailyQuota <= 0) "∞" else "${settings.biliDailyQuota} 条",
            onDecrease = {
                update { it.copy(biliDailyQuota = (it.biliDailyQuota - 1).coerceIn(0, 20)) }
            },
            onIncrease = {
                update { it.copy(biliDailyQuota = (it.biliDailyQuota + 1).coerceIn(0, 20)) }
            },
        )
        SettingRow(
            title = s.biliKeywords,
            subtitle = settings.biliKeywords.ifBlank { s.biliKeywordsHint },
            trailing = "✎",
            showDivider = true,
            onClick = {
                keywordDraft = settings.biliKeywords
                editingKeywords = true
            },
        )
        SettingRow(
            title = s.biliTone,
            subtitle = tone.name,
            trailing = "›",
            showDivider = true,
            onClick = { editingTone = true },
        )
        SettingRow(
            // 扫码登录与手填 Cookie 是同一个凭证的两种进法：
            // 能扫码就不该逼用户去翻开发者工具，但代理/公司网环境扫码也可能连不上，
            // 所以手填这条永远留着。
            title = s.biliLogin,
            subtitle = when {
                settings.biliUserName.isNotBlank() -> "已登录 · ${settings.biliUserName}"
                cookieMask.isNotBlank() -> cookieMask
                else -> s.biliCookieUnset
            },
            trailing = "›",
            showDivider = true,
            onClick = { nav.push(Screen.BiliLogin) },
        )
        SettingRow(
            title = s.biliCookie,
            subtitle = cookieMask.ifBlank { s.biliCookieUnset },
            trailing = "✎",
            onClick = {
                cookieDraft = container.keyStore.getBiliCookie().orEmpty()
                editingCookie = true
            },
        )
    }

    Spacer(Modifier.height(6.dp))
    SettingCard {
        StepperRow(
            title = s.biliCommentQuota,
            subtitle = s.biliCommentQuotaHint,
            value = if (settings.biliCommentQuota <= 0) "∞" else "${settings.biliCommentQuota} 条",
            onDecrease = {
                update { it.copy(biliCommentQuota = (it.biliCommentQuota - 1).coerceIn(0, 20)) }
            },
            onIncrease = {
                update { it.copy(biliCommentQuota = (it.biliCommentQuota + 1).coerceIn(0, 20)) }
            },
        )
        SettingRow(
            title = s.biliAtUid,
            subtitle = settings.biliAtUid.ifBlank { s.biliAtUidHint },
            trailing = "✎",
            showDivider = true,
            onClick = {
                atDraft = settings.biliAtUid
                editingAt = true
            },
        )
        SettingRow(
            // 配图选择放在这里而不是单独一页：只有三档，进去看一眼就知道选哪个
            title = s.biliImage,
            subtitle = when (settings.biliImage) {
                "pack" -> s.biliImagePack
                "card" -> s.biliImageCard + settings.biliImageText.takeIf { it.isNotBlank() }
                    ?.let { "「$it」" }.orEmpty()
                "local" -> s.biliImageLocal
                else -> s.biliImageNone
            } + if (imageNote.isNotBlank()) " · $imageNote" else "",
            trailing = "›",
            showDivider = true,
            onClick = { editingImage = true },
        )
        if (settings.biliImage == "card") {
            SettingRow(
                title = s.biliImageText,
                subtitle = settings.biliImageText.ifBlank { s.biliImageTextHint },
                trailing = "✎",
                showDivider = true,
                onClick = {
                    imageTextDraft = settings.biliImageText
                    editingImageText = true
                },
            )
        }
        // 动态和评论共用同一套配图设置：多一个图片选择器只会让设置页更长，
        // 而且用户大概率希望「配图」是一致的。
        SwitchRow(
            title = s.biliDyn,
            subtitle = s.biliDynHint,
            checked = settings.biliDynEnabled,
            onCheckedChange = { v -> update { it.copy(biliDynEnabled = v) } },
        )
        if (settings.biliDynEnabled) {
            StepperRow(
                title = s.biliDynQuota,
                subtitle = s.biliDynQuotaHint,
                value = if (settings.biliDynQuota <= 0) "∞" else "${settings.biliDynQuota} 条",
                onDecrease = {
                    update { it.copy(biliDynQuota = (it.biliDynQuota - 1).coerceIn(0, 10)) }
                },
                onIncrease = {
                    update { it.copy(biliDynQuota = (it.biliDynQuota + 1).coerceIn(0, 10)) }
                },
            )
            // 动态写什么：三种视角可多选。默认全开 —— 一条像人的动态本来
            // 就是「今天干了啥 + 顺手吐槽 + 想到谁」混在一起，不是三选一。
            SettingRow(
                title = s.biliDynTopicTitle,
                subtitle = s.biliDynTopicHint,
                showDivider = true,
            )
            SwitchRow(
                title = s.biliDynTopicLife,
                subtitle = s.biliDynTopicLifeHint,
                checked = settings.biliDynTopics.contains("life"),
                onCheckedChange = {
                    update { st -> st.copy(biliDynTopics = toggleTopic(st.biliDynTopics, "life")) }
                },
            )
            SwitchRow(
                title = s.biliDynTopicRoast,
                subtitle = s.biliDynTopicRoastHint,
                checked = settings.biliDynTopics.contains("roast"),
                onCheckedChange = {
                    update { st -> st.copy(biliDynTopics = toggleTopic(st.biliDynTopics, "roast")) }
                },
            )
            SwitchRow(
                title = s.biliDynTopicMaster,
                subtitle = s.biliDynTopicMasterHint,
                checked = settings.biliDynTopics.contains("master"),
                onCheckedChange = {
                    update { st -> st.copy(biliDynTopics = toggleTopic(st.biliDynTopics, "master")) }
                },
            )
        }

        // 点赞投币：唯一会消耗账号资源的动作，所以单独一行说清楚
        SwitchRow(
            title = s.biliCoin,
            subtitle = s.biliCoinHint,
            checked = settings.biliCoinEnabled,
            onCheckedChange = { v -> update { it.copy(biliCoinEnabled = v) } },
        )
        if (settings.biliCoinEnabled) {
            SettingRow(
                title = s.biliCoinQuota,
                subtitle = s.biliCoinQuotaHint,
                trailing = "1 枚 / 天",
                showDivider = true,
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    SettingCard {
        SwitchRow(
            title = s.biliAutoRun,
            subtitle = s.biliAutoRunHint,
            checked = settings.biliAutoRun,
            onCheckedChange = { v -> update { it.copy(biliAutoRun = v) } },
        )
        SettingRow(
            title = s.biliAutoTime,
            subtitle = s.biliAutoTimeHint,
            trailing = BiliClock.hhmm(settings.biliAutoHour, settings.biliAutoMinute),
            showDivider = true,
            onClick = { editingTime = true },
        )
    }

    Spacer(Modifier.height(6.dp))
    SettingCard {
        SettingRow(
            title = s.biliOpenDigest,
            subtitle = when {
                todayCount < 0 -> "…"
                settings.biliDailyQuota <= 0 -> "今天已记 $todayCount 条（不限量）"
                else -> "今天已记 $todayCount / ${settings.biliDailyQuota} 条"
            },
            trailing = "›",
            onClick = { nav.push(Screen.Workspace) },
        )
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = s.biliHint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    if (editingKeywords) {
        SimpleEditDialog(
            title = s.biliKeywords,
            value = keywordDraft,
            placeholder = "DeepSeek, 机械键盘",
            onChange = { keywordDraft = it },
            onConfirm = {
                update { it.copy(biliKeywords = keywordDraft.trim()) }
                editingKeywords = false
            },
            onDismiss = { editingKeywords = false },
        )
    }

    if (editingCookie) {
        SimpleEditDialog(
            title = s.biliCookie,
            value = cookieDraft,
            placeholder = "SESSDATA=...; bili_jct=...",
            onChange = { cookieDraft = it },
            onConfirm = {
                container.keyStore.putBiliCookie(cookieDraft.trim())
                cookieStamp++
                editingCookie = false
            },
            onDismiss = { editingCookie = false },
        )
    }

    if (editingMode) {
        val options = listOf(
            Triple(BiliMode.SUMMARY.id, s.biliModeSummary, s.biliModeSummaryHint),
            Triple(BiliMode.COMMENT.id, s.biliModeComment, s.biliModeCommentHint),
            Triple(BiliMode.DM.id, s.biliModeDm, s.biliModeDmHint),
        )
        val current = BiliModes.of(settings).id
        AlertDialog(
            onDismissRequest = { editingMode = false },
            title = { Text(text = s.biliMode, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    options.forEach { (id, label, hint) ->
                        val picked = current == id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // 同时写一个新字段与旧字段：旧字段留着是为了让
                                    // 「降级回 1.9」时行为不至于反过来（开着变没开）
                                    update {
                                        it.copy(
                                            biliMode = id,
                                            biliCommentEnabled = id == BiliMode.COMMENT.id,
                                        )
                                    }
                                    editingMode = false
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (picked) "●" else "○",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (picked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 18.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingMode = false }) { Text(s.cancel) }
            },
        )
    }

    if (editingImage) {
        val options = listOf(
            Triple("pack", s.biliImagePack, s.biliImagePackHint),
            Triple("card", s.biliImageCard, s.biliImageCardHint),
            Triple("local", s.biliImageLocal, s.biliImageLocalHint),
            Triple("none", s.biliImageNone, s.biliImageNoneHint),
        )
        AlertDialog(
            onDismissRequest = { editingImage = false },
            title = { Text(text = s.biliImage, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    options.forEach { (id, label, hint) ->
                        val picked = settings.biliImage == id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // 打上「用户主动选过」标记，升级迁移就不会再覆盖这个选择
                                    update { it.copy(biliImage = id, biliImageTouched = true) }
                                    editingImage = false
                                    // 有些手表 ROM 没装 DocumentsUI，launch 会直接抛异常；
                                    // 那种情况下退回「摸鱼卡」，而不是让设置页崩掉
                                    if (id == "local") {
                                        runCatching { picker.launch("image/*") }
                                            .onFailure {
                                                update { st -> st.copy(biliImage = "card") }
                                                imageNote = s.biliImagePickFailed
                                            }
                                    }
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (picked) "●" else "○",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (picked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 18.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingImage = false }) { Text(s.cancel) }
            },
        )
    }

    if (editingTime) {
        var hour by remember { mutableStateOf(settings.biliAutoHour) }
        var minute by remember { mutableStateOf(settings.biliAutoMinute) }
        AlertDialog(
            onDismissRequest = { editingTime = false },
            title = { Text(text = s.biliAutoTime, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = BiliClock.hhmm(hour, minute),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextButton(onClick = { hour = (hour + 23) % 24 }) { Text("−") }
                        Text(text = "${hour} 点", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { hour = (hour + 1) % 24 }) { Text("+") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(0, 15, 30, 45).forEach { m ->
                            val picked = minute == m
                            TextButton(onClick = { minute = m }) {
                                Text(
                                    text = "%02d".format(m),
                                    color = if (picked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    Text(
                        text = s.biliAutoTimeTip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    update { it.copy(biliAutoHour = hour, biliAutoMinute = minute) }
                    editingTime = false
                }) { Text(s.save) }
            },
            dismissButton = {
                TextButton(onClick = { editingTime = false }) { Text(s.cancel) }
            },
        )
    }

    if (editingImageText) {
        SimpleEditDialog(
            title = s.biliImageText,
            value = imageTextDraft,
            placeholder = "今天也在白吃白喝",
            onChange = { imageTextDraft = it },
            onConfirm = {
                update { it.copy(biliImageText = imageTextDraft.trim()) }
                editingImageText = false
            },
            onDismiss = { editingImageText = false },
        )
    }

    if (editingAt) {
        SimpleEditDialog(
            title = s.biliAtUid,
            value = atDraft,
            placeholder = "例如 12345678",
            onChange = { atDraft = it.filter { ch -> ch.isDigit() } },
            onConfirm = {
                update { it.copy(biliAtUid = atDraft.trim()) }
                editingAt = false
            },
            onDismiss = { editingAt = false },
        )
    }

    if (editingTone) {
        val options = listOf(
            "fish" to s.biliToneFish,
            "naughty" to s.biliToneNaughty,
            "serious" to s.biliToneSerious,
            "deadpan" to s.biliToneDeadpan,
            "custom" to s.biliToneCustom,
        )
        AlertDialog(
            onDismissRequest = { editingTone = false },
            title = { Text(text = s.biliTone, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    options.forEach { (id, label) ->
                        val picked = settings.biliTone == id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    update { it.copy(biliTone = id) }
                                    editingTone = false
                                    if (id == "custom") {
                                        toneDraft = settings.biliToneCustom
                                        editingToneText = true
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (picked) "●" else "○",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (picked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingTone = false }) { Text(s.cancel) }
            },
        )
    }

    if (editingToneText) {
        SimpleEditDialog(
            title = s.biliToneCustom,
            value = toneDraft,
            placeholder = "像一个刚下班的程序员，随口吐槽两句",
            onChange = { toneDraft = it },
            onConfirm = {
                update { it.copy(biliTone = "custom", biliToneCustom = toneDraft.trim()) }
                editingToneText = false
            },
            onDismiss = { editingToneText = false },
        )
    }
}

/** 手表上唯一的多行输入场景（关键词 / Cookie），做成一个弹窗就够 */
@Composable
private fun SimpleEditDialog(
    title: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextRow(
                    label = title,
                    value = value,
                    onValueChange = onChange,
                    placeholder = placeholder,
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(s.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancel) }
        },
    )
}

private fun todayKey(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

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
