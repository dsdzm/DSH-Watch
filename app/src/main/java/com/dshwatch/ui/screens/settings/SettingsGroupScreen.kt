package com.dshwatch.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.dshwatch.domain.plugin.PluginLoader
import com.dshwatch.domain.plugin.PluginManifest
import com.dshwatch.domain.plugin.PluginRegistry
import com.dshwatch.domain.model.Appearances
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatLayouts
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.model.ScreenShapes
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.domain.model.WorkspaceLocation
import com.dshwatch.domain.update.UpdateChannel
import com.dshwatch.ui.components.AgentModeDialog
import com.dshwatch.ui.components.SegmentedRow
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.theme.LocalUiScale
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.StepperRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.WatchSlider
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.i18n.AppLanguage
import com.dshwatch.ui.i18n.AppStrings
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.domain.update.UpdateState
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.theme.Accents
import com.dshwatch.ui.theme.ThemePresets
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
                SettingsGroups.PLUGINS -> PluginsGroup(settings, update, container, s)
                SettingsGroups.MCP -> McpGroup(settings, update, container, s)
                SettingsGroups.BILI -> BiliGroup(settings, update, container, nav, s)
                else -> ConnectionGroup(settings, update, container, nav, s)
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
        // 设备操控：和上面两项同一层——都是「AI 能把事情做到哪一步」的开关。
        // 放在这里而不是「连接」组，是因为它不涉及网络，只涉及权限。
        SettingRow(
            title = if (s.isChinese) "设备操控" else "Device control",
            subtitle = when {
                settings.deviceControlEnabled -> if (s.isChinese) "已开启，可读屏并操作" else "On"
                else -> if (s.isChinese) "关闭（需要系统无障碍权限）" else "Off"
            },
            trailing = "›",
            onClick = { nav.push(Screen.DeviceControl) },
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
    var editingPreset by remember { mutableStateOf(false) }
    var editingAppearance by remember { mutableStateOf(false) }
    var editingUiStyle by remember { mutableStateOf(false) }
    var editingAccent by remember { mutableStateOf(false) }
    var editingLayout by remember { mutableStateOf(false) }
    var editingBackground by remember { mutableStateOf(false) }
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    // 系统检测值单独取一次：AUTO 时需要它，而 profile.isRound 已经被用户选择覆盖过了
    val configuration = LocalConfiguration.current
    val detectedRound = remember(configuration) {
        runCatching { configuration.isScreenRound }.getOrDefault(false)
    }
    val layouts = ChatLayouts.available(profile.isRound)

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
        Spacer(Modifier.height(2.dp))
        /*
         * 界面体系排在所有外观项之前。
         *
         * 它是「用哪一套组件搭页面」——骨架、卡片、按钮、开关全换。
         * 材质和配色是在这套骨架**之上**调的，所以顺序必须在前：
         * 先选界面，再选材质，用户不会觉得「调完颜色界面又变了」。
         */
        SettingRow(
            title = "界面体系",
            subtitle = if (com.dshwatch.domain.model.UiStyles.isM3e(settings.uiStyle)) {
                "M3E · Wear Material 3 Expressive 原生组件"
            } else {
                "经典 · 自绘圆屏骨架，每屏多放一行"
            },
            trailing = if (com.dshwatch.domain.model.UiStyles.isM3e(settings.uiStyle)) "M3E ›" else "经典 ›",
            showDivider = true,
            onClick = { editingUiStyle = true },
        )
        // 外观风格排在配色之前：先定材质（纯色 / 玻璃），再在它上面选颜色。
        // 反过来会出现「挑完樱花，再问要不要玻璃」，像是把刚选的颜色弄丢了。
        SettingRow(
            title = "外观风格",
            subtitle = if (Appearances.isGlass(settings.appearance)) {
                "质感玻璃 · 卡片半透明，能透出底色"
            } else {
                "M3E 纯色 · 卡片不透明"
            },
            trailing = if (Appearances.isGlass(settings.appearance)) "玻璃 ›" else "M3E ›",
            showDivider = true,
            onClick = { editingAppearance = true },
        )
        // 预设与强调色正交：预设定底色层级，强调色定主色。
        // 选任一项都会关掉动态取色 —— 不然用户挑的颜色会被系统色直接盖掉。
        SettingRow(
            title = s.themePreset,
            subtitle = s.themePresetHint,
            trailing = s.themePresetLabel(settings.themePreset) + " ›",
            showDivider = true,
            onClick = { editingPreset = true },
        )
        SettingRow(
            title = s.accentColor,
            trailing = s.accentLabel(settings.accentColor) + " ›",
            showDivider = true,
            onClick = { editingAccent = true },
        )
        /*
         * 自定义背景放在配色之后。
         *
         * 它是「在已经选好的颜色之上，再垫一张自己的图」，属于最后一层 ——
         * 放在明暗/预设之前，用户会先看到「背景」再看到「配色」，
         * 心理顺序上像是两件冲突的事，而实际上它们是叠加的。
         */
        SettingRow(
            title = "自定义背景",
            subtitle = if (settings.customBackground.isNotBlank()) {
                "已设置 · 遮罩 " + String.format(
                    java.util.Locale.US,
                    "%.0f%%",
                    settings.customBackgroundDim * 100,
                )
            } else {
                "用自己的照片当底，玻璃会透出它"
            },
            trailing = if (settings.customBackground.isNotBlank()) "已设置 ›" else "未设置 ›",
            onClick = { editingBackground = true },
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            title = s.dynamicColor,
            subtitle = s.dynamicColorHint,
            checked = settings.dynamicColor,
            onCheckedChange = { v -> update { it.copy(dynamicColor = v) } },
        )
        Spacer(Modifier.height(2.dp))
        /*
         * 界面大小：**唯一**的缩放轴，缩放的是密度，dp 与 sp 一起变。
         *
         * 以前这里还有第二个「字号」滑块（只缩 fontScale、不动 dp），
         * 两者在文字上相乘，调完这个再调那个就会把字撑出卡片、内容顶到
         * 圆周外 —— 见文件下方那段说明。现在只剩一条轴，比例恒定。
         */
        val effectiveScale = LocalUiScale.current
        val scaleAuto = settings.uiScaleAuto
        // 本机推荐值（按未缩放的屏幕宽度算）：超过它会开始有内容贴近圆周
        val recommended = com.dshwatch.ui.theme.LocalUiScaleRecommended.current
        WatchSlider(
            value = (if (scaleAuto) effectiveScale else settings.uiScale)
                .coerceIn(com.dshwatch.domain.UiScale.MIN, com.dshwatch.domain.UiScale.MAX),
            onChange = { v -> update { it.copy(uiScale = v, uiScaleAuto = false) } },
            valueRange = com.dshwatch.domain.UiScale.MIN..com.dshwatch.domain.UiScale.MAX,
            steps = 10,
            label = s.uiScale,
            valueText = if (scaleAuto) {
                "自动 " + String.format(java.util.Locale.US, "%.2f×", effectiveScale)
            } else {
                String.format(java.util.Locale.US, "%.2f×", effectiveScale)
            },
        )
        if (!scaleAuto && effectiveScale > recommended + 0.02f) {
            // 只提醒、不拦：宁可裁一点也要把字放大是用户的选择，
            // 但得让他知道「这不是 App 坏了」，而是已经越过本机能放下的尺寸
            Text(
                text = s.uiScaleOver(String.format(java.util.Locale.US, "%.2f×", recommended)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp, end = 2.dp),
            )
        }
        if (!scaleAuto) {
            // 手动模式下给一条回到自适应的捷径：拖到极端之后很难靠滑块找准推荐值
            SettingRow(
                title = s.uiScaleReset,
                subtitle = s.uiScaleHint,
                showDivider = false,
                onClick = {
                    update { it.copy(uiScaleAuto = true) }
                },
            )
        }
        SwitchRow(
            title = s.uiScaleAuto,
            subtitle = s.uiScaleHint,
            checked = scaleAuto,
            onCheckedChange = { v ->
                // 关掉自动时把当前生效值落到手动值上，滑块才不会跳回 1.0
                update { it.copy(uiScaleAuto = v, uiScale = if (v) it.uiScale else effectiveScale) }
            },
        )
        Spacer(Modifier.height(2.dp))
        /*
         * 这里原来还有一个「字号」滑块，v1.15.22 起删掉了。
         *
         * 原因是它和上面这个「界面大小」在文字上**相乘**：界面大小缩 density
         * （dp 与 sp 同比），字号只缩 fontScale（不动 dp）。两者一起调时，
         * 字号相对卡片的比例被放大成 `uiScale × fontScale`，字撑出卡片、
         * 内容顶到圆周外被切 —— 用户只能描述成「两个一起调就出问题」。
         *
         * 删掉之后只剩一条缩放轴，比例恒定，数学上不可能再出现字比卡片大。
         * 老存档里的 fontScale 由 SettingsRepository.migrate 折进 uiScale。
         */
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
        Spacer(Modifier.height(4.dp))
    }

    Spacer(Modifier.height(6.dp))
    SettingCard {
        // 屏幕形状：圆屏和方屏的可用宽度完全不同，选错会让界面白收或切字。
        Text(
            text = s.screenShape,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        SegmentedRow(
            options = listOf(s.shapeAuto, s.shapeRound, s.shapeSquare),
            selectedIndex = when (ScreenShapes.of(settings.screenShape)) {
                ScreenShapes.ROUND -> 1
                ScreenShapes.SQUARE -> 2
                else -> 0
            },
            onSelect = { index ->
                val next = when (index) {
                    1 -> ScreenShapes.ROUND
                    2 -> ScreenShapes.SQUARE
                    else -> ScreenShapes.AUTO
                }
                update { st ->
                    st.copy(screenShape = next)
                }
            },
        )
        Spacer(Modifier.height(2.dp))
        SettingRow(
            title = s.chatLayout,
            subtitle = s.chatLayoutHint(settings.chatLayout),
            trailing = s.chatLayoutLabel(settings.chatLayout) + " ›",
            showDivider = true,
            onClick = { editingLayout = true },
        )
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
        // 开关就该长成开关。以前这里是 SettingRow + "✓/—" 文本，
        // 跟同一页其它 SwitchRow 完全不一个东西，用户得先分辨"这行能不能点"。
        SwitchRow(
            title = s.showMascot,
            subtitle = s.mascotHint,
            checked = settings.showMascot,
            onCheckedChange = { v -> update { it.copy(showMascot = v) } },
        )
        Spacer(Modifier.height(4.dp))
    }

    Spacer(Modifier.height(6.dp))
    SettingCard {
        SettingRow(
            title = s.rerunOnboarding,
            subtitle = s.rerunOnboardingHint,
            trailing = "↻",
            onClick = { update { it.copy(onboardingDone = false) } },
        )
    }

    if (editingUiStyle) {
        GlassDialog(
            onDismissRequest = { editingUiStyle = false },
            title = "界面体系",
            maxContentHeight = 220,
            content = {
                PickRow(
                    title = "经典 · 自绘圆屏骨架",
                    selected = !com.dshwatch.domain.model.UiStyles.isM3e(settings.uiStyle),
                    onClick = {
                        update { it.copy(uiStyle = com.dshwatch.domain.model.UiStyles.CLASSIC) }
                        editingUiStyle = false
                    },
                )
                PickRow(
                    title = "M3E · Wear Material 3 Expressive",
                    selected = com.dshwatch.domain.model.UiStyles.isM3e(settings.uiStyle),
                    onClick = {
                        update { it.copy(uiStyle = com.dshwatch.domain.model.UiStyles.M3E) }
                        editingUiStyle = false
                    },
                )
            },
            buttons = { GlassDialogButton(s.close, onClick = { editingUiStyle = false }) },
        )
    }

    if (editingAppearance) {
        GlassDialog(
            onDismissRequest = { editingAppearance = false },
            title = "外观风格",
            maxContentHeight = 220,
            content = {
                PickRow(
                    title = "M3E 纯色 · 卡片不透明",
                    selected = !Appearances.isGlass(settings.appearance),
                    onClick = {
                        update { it.copy(appearance = Appearances.M3E) }
                        editingAppearance = false
                    },
                )
                PickRow(
                    title = "质感玻璃 · 半透明，透出底色",
                    selected = Appearances.isGlass(settings.appearance),
                    onClick = {
                        // 选玻璃时关掉动态取色：动态色板会把底色晕替换掉，
                        // 玻璃会变成一块没有底色的灰片（见 DshWatchTheme）
                        update { it.copy(appearance = Appearances.GLASS, dynamicColor = false) }
                        editingAppearance = false
                    },
                )
            },
            buttons = { GlassDialogButton(s.close, onClick = { editingAppearance = false }) },
        )
    }

    if (editingPreset) {
        GlassDialog(
            onDismissRequest = { editingPreset = false },
            title = s.themePreset,
            maxContentHeight = 260,
            // 内容自带 verticalScroll，这里不能再套一层
            scrollable = false,
            content = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ThemePresets.all.forEach { preset ->
                        PickRow(
                            title = s.themePresetLabel(preset.id),
                            selected = preset.id == settings.themePreset,
                            onClick = {
                                update { it.copy(themePreset = preset.id, dynamicColor = false) }
                                editingPreset = false
                            },
                            leading = {
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    ThemePresets.swatch(preset.id, settings.accentColor, dark)
                                        .forEach { c ->
                                            Box(
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .background(c, CircleShape),
                                            )
                                        }
                                }
                            },
                        )
                    }
                }
            },
            buttons = { GlassDialogButton(s.close, onClick = { editingPreset = false }) },
        )
    }

    if (editingAccent) {
        GlassDialog(
            onDismissRequest = { editingAccent = false },
            title = s.accentColor,
            maxContentHeight = 260,
            // 内容自带 verticalScroll，这里不能再套一层
            scrollable = false,
            content = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Accents.all.forEach { accent ->
                        PickRow(
                            title = s.accentLabel(accent.id),
                            selected = accent.id == settings.accentColor,
                            onClick = {
                                update { it.copy(accentColor = accent.id, dynamicColor = false) }
                                editingAccent = false
                            },
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            if (dark) accent.darkPrimary else accent.lightPrimary,
                                            CircleShape,
                                        ),
                                )
                            },
                        )
                    }
                }
            },
            buttons = { GlassDialogButton(s.close, onClick = { editingAccent = false }) },
        )
    }

    if (editingBackground) {
        BackgroundDialog(
            settings = settings,
            update = update,
            onDismiss = { editingBackground = false },
        )
    }

    if (editingLayout) {
        GlassDialog(
            onDismissRequest = { editingLayout = false },
            title = s.chatLayout,
            maxContentHeight = 260,
            content = {
                Column {
                    layouts.forEach { id ->
                        PickRow(
                            title = s.chatLayoutLabel(id),
                            hint = s.chatLayoutHint(id),
                            selected = ChatLayouts.of(settings.chatLayout) == id,
                            onClick = {
                                update { it.copy(chatLayout = id) }
                                editingLayout = false
                            },
                        )
                    }
                }
            },
            buttons = { GlassDialogButton(s.close, onClick = { editingLayout = false }) },
        )
    }
}

/** 单选行：圆点 + 标题（可带说明）+ 勾 */
@Composable
private fun PickRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    if (com.dshwatch.ui.theme.isM3eUi && leading == null) {
        com.dshwatch.ui.m3e.M3eRadioRow(
            title = title,
            selected = selected,
            onClick = onClick,
            hint = hint,
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leading != null) {
            leading()
        } else {
            Text(
                text = if (selected) "●" else "○",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
            showDivider = true,
        )
        /*
         * 内置文件浏览器开关。
         *
         * 默认开：`ACTION_OPEN_DOCUMENT` 依赖设备上存在文件管理器，一部分手表 ROM
         * 把它删了，点「＋文件」会静默失败。关掉就退回系统选择器 ——
         * 有文件管理器的设备上，系统那套能翻到网盘、第三方存储等更多地方。
         */
        SwitchRow(
            title = if (s.isChinese) "用内置文件浏览器" else "Built-in file browser",
            subtitle = if (s.isChinese) {
                "选附件时不依赖系统文件管理器，直接列表浏览；关掉则用系统选择器"
            } else {
                "List files in-app instead of relying on a system file manager"
            },
            checked = settings.builtinFilePicker,
            onCheckedChange = { on -> update { it.copy(builtinFilePicker = on) } },
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
 * 导入成功后那句提示里的影响面。
 *
 * 用户粘完一份清单，最想知道的是「它动了什么」。只说「已导入」等于
 * 让他自己去翻，而清单原文可能有一屏那么长。
 */
private fun impactOf(m: PluginManifest): String {
    val t = m.tools
    val parts = buildList {
        if (t != null) {
            if (t.hide.isNotEmpty()) add("禁用 ${t.hide.size} 个工具")
            if (t.only.isNotEmpty()) add("只保留 ${t.only.size} 个工具")
            if (t.confirm.isNotEmpty()) add("${t.confirm.size} 个改为每次确认")
        }
    }
    if (parts.isEmpty()) return if (m.prompt != null) "（提示词插件）" else ""
    return "：" + parts.joinToString("、")
}

/**
 * 插件开关。
 *
 * 每个开关下面都写清"它到底改了模型的什么" —— 这一页真正的风险不是点错，
 * 而是**开了却不知道开了什么**，之后发现"模型怎么突然话少了/数据变样了"，
 * 却联想不到这里。
 */
@Composable
private fun PluginsGroup(
    settings: AppSettings,
    update: Update,
    container: AppContainer,
    s: AppStrings,
) {
    val scope = rememberCoroutineScope()
    val enabled = settings.pluginsResolved(PluginRegistry.defaultEnabled())
    val all = PluginRegistry.allFor(settings)
    var notice by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    SettingCard {
        all.forEach { plugin ->
            SwitchRow(
                title = plugin.displayName ?: s.pluginTitle(plugin.id),
                subtitle = plugin.displayDesc ?: s.pluginDesc(plugin.id),
                checked = plugin.id in enabled,
                onCheckedChange = { on ->
                    val next = if (on) enabled + plugin.id else enabled - plugin.id
                    update { it.copy(enabledPlugins = next, pluginsCustomized = true) }
                },
            )
        }
    }

    /*
     * 声明式插件从工作空间的 plugins/ 目录读。
     *
     * 不做文件监听：手表上「改完立刻生效」的收益，抵不过一个常驻 watch 的耗电。
     * 用户改完清单回来点一下，是这条链路上唯一需要记住的操作。
     */
    Spacer(Modifier.height(6.dp))
    SettingCard {
        SettingRow(
            title = "导入插件",
            subtitle = notice ?: "从剪贴板或链接装一份清单",
            trailing = "＋",
            showDivider = true,
            onClick = { importing = true },
        )
        SettingRow(
            title = if (scanning) "正在扫描…" else "重新扫描插件目录",
            subtitle = "把 JSON 放进工作空间的 plugins/ 目录，点这里加载",
            trailing = "↻",
            onClick = {
                if (!scanning) {
                    scanning = true
                    scope.launch {
                        val scan = PluginLoader.scan(container.workspaceStore)
                        update { it.copy(pluginManifests = scan.manifests) }
                        notice = when {
                            scan.errors.isNotEmpty() -> scan.errors.first()
                            scan.manifests.isEmpty() -> "plugins/ 里没有可用的清单"
                            else -> "已加载 ${scan.manifests.size} 个插件，开关在上面"
                        }
                        scanning = false
                    }
                }
            },
        )
        // 导入的插件在文件系统里没有对应文件，删不掉就只能清空设置
        if (settings.importedPlugins.isNotEmpty()) {
            SettingRow(
                title = "移除导入的插件",
                subtitle = "已导入 ${settings.importedPlugins.size} 个",
                trailing = "－",
                showDivider = true,
                onClick = { removing = true },
            )
        }
    }

    if (importing) {
        PluginImportDialog(
            workspace = container.workspaceStore,
            onDismiss = { importing = false },
            onImported = { m ->
                if (settings.importedPlugins.size >= PluginRegistry.MAX_MANIFESTS &&
                    settings.importedPlugins.none { it.id == m.id }
                ) {
                    notice = "最多导入 ${PluginRegistry.MAX_MANIFESTS} 个，先移除一个"
                    return@PluginImportDialog
                }
                update { cur ->
                    cur.copy(
                        importedPlugins = cur.importedPlugins.filterNot { it.id == m.id } + m,
                    )
                }
                notice = "已导入「${m.name}」${impactOf(m)}，开关在上面（默认关闭）"
            },
        )
    }

    if (removing) {
        PluginRemoveDialog(
            manifests = settings.importedPlugins,
            onDismiss = { removing = false },
            onRemove = { m ->
                update { cur -> cur.copy(importedPlugins = cur.importedPlugins.filterNot { it.id == m.id }) }
                // 开关状态也要一起清：留着 id 在 enabledPlugins 里，导入同 id 的新版本会「自动开着」
                update { cur -> cur.copy(enabledPlugins = cur.enabledPlugins - m.id) }
                notice = "已移除「${m.name}」"
                if (settings.importedPlugins.size <= 1) removing = false
            },
        )
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
private fun ConnectionGroup(
    settings: AppSettings,
    update: Update,
    container: AppContainer,
    nav: WatchNavigator,
    s: AppStrings,
) {
    // 检查更新挪到这里之后，这一行要跟着自动检查的结果变
    val updateState by container.updateCenter.state.collectAsStateWithLifecycle()
    SettingCard {
        SwitchRow(
            title = s.autoCheckUpdate,
            checked = settings.autoCheckUpdate,
            onCheckedChange = { v -> update { it.copy(autoCheckUpdate = v) } },
        )
        Spacer(Modifier.height(6.dp))
        /*
         * 更新通道。
         *
         * 放在「自动检查」下面而不是更新页里：这两件事是同一个决定的两半 ——
         * 要不要查、查哪个。分到两个页面，用户会以为「通道」是某种高级设置，
         * 而它其实就是「我愿不愿意试新东西」。
         */
        Text(
            text = s.updateChannel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        )
        SegmentedRow(
            options = listOf(s.updateChannelStable, s.updateChannelBeta),
            selectedIndex = if (UpdateChannel.of(settings.updateChannel) == UpdateChannel.BETA) 1 else 0,
            onSelect = { index ->
                val next = if (index == 1) UpdateChannel.BETA else UpdateChannel.STABLE
                update { it.copy(updateChannel = next.id) }
                // 切完立刻按新通道查一次：否则用户看到的还是上一条通道的结果，
                // 会以为「切了没反应」
                container.updateCenter.checkNow(next)
            },
        )
        if (UpdateChannel.of(settings.updateChannel) == UpdateChannel.BETA) {
            Text(
                text = "测试版可能不稳定，遇到问题请切回稳定版并反馈",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 3.dp, end = 2.dp),
            )
        }
        /*
         * 「检查更新」紧跟在开关下面。
         *
         * 这两个是同一件事的两面：一个决定「要不要偷偷查」，一个决定「现在查一下」。
         * 以前检查更新在关于页、开关在这里，用户想手动查一次得先想清楚它在哪一页 ——
         * 而他通常是在设置里刚关掉自动检查、又想立刻确认一次有没有新版。
         */
        SettingRow(
            title = s.updateCheck,
            subtitle = when (val u = updateState) {
                is UpdateState.Available -> s.updateNewVersion
                UpdateState.UpToDate -> s.updateUpToDate
                UpdateState.Checking -> s.updateChecking
                else -> null
            },
            trailing = when (val u = updateState) {
                is UpdateState.Available -> u.manifest.versionName
                else -> "›"
            },
            showDivider = true,
            onClick = { nav.push(Screen.Update) },
        )
        SettingRow(
            title = s.remoteTitle,
            subtitle = s.entryRemoteDesc,
            trailing = "›",
            showDivider = true,
            onClick = { nav.push(Screen.Remote) },
        )
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
    var editingFavFolder by remember { mutableStateOf(false) }
    var folderDraft by remember { mutableStateOf("") }
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

        // 点赞：不动账号里的任何资源，只发一个公开信号，所以只给一个总开关
        SwitchRow(
            title = s.biliLike,
            subtitle = s.biliLikeHint,
            checked = settings.biliLikeEnabled,
            onCheckedChange = { v -> update { it.copy(biliLikeEnabled = v) } },
        )
        // 投币与收藏：都会改动账号状态（硬币不可逆 / 收藏夹是用户自己的地盘），
        // 所以和点赞分开，默认关
        SwitchRow(
            title = s.biliCoin,
            subtitle = s.biliCoinHint,
            checked = settings.biliCoinEnabled,
            onCheckedChange = { v -> update { it.copy(biliCoinEnabled = v) } },
        )
        SwitchRow(
            title = s.biliFav,
            subtitle = s.biliFavHint,
            checked = settings.biliFavEnabled,
            onCheckedChange = { v -> update { it.copy(biliFavEnabled = v) } },
        )
        if (settings.biliFavEnabled) {
            SettingRow(
                title = s.biliFavFolder,
                subtitle = settings.biliFavFolderId.ifBlank { s.biliFavFolderHint },
                trailing = "✎",
                onClick = {
                    folderDraft = settings.biliFavFolderId
                    editingFavFolder = true
                },
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

    if (editingFavFolder) {
        SimpleEditDialog(
            title = s.biliFavFolder,
            value = folderDraft,
            // 收藏夹 id 是一串数字，从「我的收藏夹」地址栏里能看到；
            // 留空是最稳的选择，所以提示语直接告诉用户这一步可以跳过
            placeholder = s.biliFavFolderHint,
            onChange = { folderDraft = it },
            onConfirm = {
                update { it.copy(biliFavFolderId = folderDraft.trim().filter { c -> c.isDigit() }) }
                editingFavFolder = false
            },
            onDismiss = { editingFavFolder = false },
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
        GlassDialog(
            onDismissRequest = { editingMode = false },
            title = s.biliMode,
            maxContentHeight = 280,
            content = {
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
            buttons = {
                GlassDialogButton(s.cancel, onClick = { editingMode = false })
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
        GlassDialog(
            onDismissRequest = { editingImage = false },
            title = s.biliImage,
            maxContentHeight = 280,
            content = {
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
            buttons = {
                GlassDialogButton(s.cancel, onClick = { editingImage = false })
            },
        )
    }

    if (editingTime) {
        var hour by remember { mutableStateOf(settings.biliAutoHour) }
        var minute by remember { mutableStateOf(settings.biliAutoMinute) }
        GlassDialog(
            onDismissRequest = { editingTime = false },
            title = s.biliAutoTime,
            maxContentHeight = 280,
            content = {
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
            buttons = {
                GlassDialogButton(s.cancel, onClick = { editingTime = false })
                GlassDialogButton(s.save, primary = true, onClick = {
                    update { it.copy(biliAutoHour = hour, biliAutoMinute = minute) }
                    editingTime = false
                })
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
        GlassDialog(
            onDismissRequest = { editingTone = false },
            title = s.biliTone,
            maxContentHeight = 280,
            content = {
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
            buttons = {
                GlassDialogButton(s.cancel, onClick = { editingTone = false })
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
    GlassDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            TextRow(
                label = title,
                value = value,
                onValueChange = onChange,
                placeholder = placeholder,
                singleLine = false,
            )
        },
        buttons = {
            GlassDialogButton(s.cancel, onClick = onDismiss)
            GlassDialogButton(s.save, primary = true, onClick = onConfirm)
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
