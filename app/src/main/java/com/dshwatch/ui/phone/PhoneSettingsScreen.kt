package com.dshwatch.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshwatch.BuildConfig
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.DeviceKinds
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.theme.ThemeSeeds
import com.dshwatch.ui.screens.settings.SettingsGroups
import kotlinx.coroutines.launch

/**
 * 手机版的设置页。
 *
 * ## 和手表那一页的关系
 *
 * 一级页是重写的：手表上是「一张张卡片纵向排」，手机上换成
 * **分组卡片 + 右侧当前值 + 箭头** 这一套 Material 3 的列表范式 ——
 * 同样十个入口，手机上能一眼看到每项现在是什么，不用点进去确认。
 *
 * 二级页**直接复用** `SettingsGroupScreen`（通过 [ScreenHost]）：那十个分组
 * 里的控件（开关、分段、滑块、对话框）已经是共用组件，重写一遍只会让
 * 两份逻辑慢慢分叉。它们的外观是手表的，但功能完整、返回正常。
 *
 * 这里多出来的两项是手机独有的：
 *
 *  - **设备类型**：引导页选过一次，这里能改回来 —— 判错的设备需要出口；
 *  - **明暗**：手机版的主题不跟手表的预设/强调色走，只跟明暗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { container.settingsRepo.update(block) }
    }

    // 手表版的外观分组不在这里出：手机有自己的一页（见 PhoneAppearanceScreen）
    val groups = SettingsGroups.all.filter { it != SettingsGroups.APPEARANCE }

    Column(modifier = Modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = { Text(s.navSettings, fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ---------------- 模型 ----------------
            item { SectionTitle("模型") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Cloud,
                    title = "供应商与模型",
                    summary = settings.activeProvider?.name ?: "未配置",
                    onClick = { nav.open(Screen.Providers) },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.PieChart,
                    title = "用量",
                    summary = "Token 与花费",
                    onClick = { nav.open(Screen.Usage) },
                )
            }

            // ---------------- 设置分组 ----------------
            item { SectionTitle("偏好") }
            /*
             * 外观不在这张列表里走共用分组页。
             *
             * `SettingsGroups.APPEARANCE` 那一页是为手表写的（界面体系 / 玻璃材质 /
             * 屏幕形状 / 整体缩放 / 看板娘），手机主题一项都不读。留在列表里，
             * 用户点进去会看到一屏「调了没反应」的开关 —— 所以由下面那张
             * 「界面」卡片单独入口，走手机自己的外观页。
             */
            items(groups.size) { index ->
                val id = groups[index]
                SettingsRow(
                    icon = groupIcon(id),
                    title = SettingsGroups.title(id, s),
                    summary = summaryOf(id, settings),
                    onClick = { nav.open(Screen.SettingsGroup(id)) },
                )
            }

            // ---------------- 手机版独有 ----------------
            item { SectionTitle("界面") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    title = "外观",
                    summary = appearanceSummary(settings),
                    onClick = { nav.open(Screen.SettingsGroup(SettingsGroups.APPEARANCE)) },
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        /*
                         * 设备类型留在一级页。
                         *
                         * 它决定的不是「颜色」而是**这一整套界面**，判错的设备
                         * （方表、平板）需要一条最短的回头路 —— 藏进二级页
                         * 就变成「得先找到外观，才知道界面能换」。
                         */
                        Text(
                            text = "设备类型",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "手表用圆屏界面，手机用底部导航界面。切换会立刻重排整个界面。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                DeviceKinds.AUTO to "自动",
                                DeviceKinds.WATCH to "手表",
                                DeviceKinds.PHONE to "手机",
                            ).forEach { (kind, label) ->
                                FilterChip(
                                    selected = DeviceKinds.of(settings.deviceKind) == kind,
                                    onClick = { update { it.copy(deviceKind = kind) } },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- 其他 ----------------
            item { SectionTitle("其他") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "关于",
                    summary = "v" + BuildConfig.VERSION_NAME,
                    onClick = { nav.open(Screen.About) },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.HelpOutline,
                    title = s.helpTitle,
                    summary = s.helpSubtitle,
                    onClick = { nav.open(Screen.Help) },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 与手表设置页同一套图标语义：图形先告诉用户「这是哪一类」 */
private fun groupIcon(id: String): ImageVector = when (id) {
    SettingsGroups.MODEL -> Icons.Outlined.Cloud
    SettingsGroups.ABILITY -> Icons.Outlined.AutoAwesome
    SettingsGroups.PLUGINS -> Icons.Outlined.Extension
    SettingsGroups.MCP -> Icons.Outlined.Hub
    SettingsGroups.BILI -> Icons.Outlined.Videocam
    SettingsGroups.CHAT -> Icons.Outlined.ChatBubbleOutline
    SettingsGroups.APPEARANCE -> Icons.Outlined.Palette
    SettingsGroups.FILES -> Icons.Outlined.FolderOpen
    SettingsGroups.RUNTIME -> Icons.Outlined.Memory
    else -> Icons.Outlined.Wifi
}

/** 外观那一行的摘要：明暗 + 颜色是从哪来的（取色 / 自己挑的 / 默认） */
private fun appearanceSummary(settings: AppSettings): String {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.DARK -> "深色"
        ThemeMode.LIGHT -> "浅色"
    }
    val color = when {
        settings.dynamicColor -> "跟随壁纸取色"
        settings.seedColor.isBlank() -> "默认配色"
        else -> ThemeSeeds.byHex(settings.seedColor)?.labelZh?.plus("配色") ?: "自定义配色"
    }
    return "$dark · $color"
}

/** 一级页的摘要。只挑手机用户最关心的那一项，多了反而看不见 */
private fun summaryOf(id: String, settings: AppSettings): String = when (id) {
    SettingsGroups.MODEL -> settings.activeProvider?.defaultModel ?: "未配置"
    SettingsGroups.ABILITY -> settings.agentMode.label
    SettingsGroups.PLUGINS -> {
        val on = settings.pluginsResolved(com.dshwatch.domain.plugin.PluginRegistry.defaultEnabled())
        val total = com.dshwatch.domain.plugin.PluginRegistry.allFor(settings).size
        if (on.isEmpty()) "全部关闭" else "${on.size}/$total 已开启"
    }
    SettingsGroups.MCP -> {
        val servers = settings.mcpServers
        if (servers.isEmpty()) "没有服务器" else "${servers.count { it.enabled && it.confirmed }}/${servers.size} 已启用"
    }
    SettingsGroups.BILI -> if (settings.biliEnabled) "已开启" else "已关闭"
    SettingsGroups.CHAT -> buildList {
        if (settings.showReasoning) add("思维链")
        if (settings.autoScroll) add("自动滚动")
    }.take(2).joinToString(" · ").ifBlank { "全部关闭" }
    SettingsGroups.APPEARANCE -> when (settings.themeMode) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.DARK -> "深色"
        ThemeMode.LIGHT -> "浅色"
    }
    SettingsGroups.FILES -> settings.workspaceLocation.label
    SettingsGroups.RUNTIME -> when (settings.perfMode) {
        com.dshwatch.domain.model.PerfMode.POWER_SAVE -> "省电"
        com.dshwatch.domain.model.PerfMode.BALANCED -> "均衡"
        com.dshwatch.domain.model.PerfMode.PERFORMANCE -> "性能"
        else -> "自动"
    }
    else -> ""
}
