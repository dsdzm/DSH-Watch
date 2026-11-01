package com.dshwatch.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.BuildConfig
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.bili.BiliMode
import com.dshwatch.domain.bili.BiliModes
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.domain.plugin.PluginRegistry
import com.dshwatch.ui.components.WatchTopBar
import com.dshwatch.ui.components.WearCardRow
import com.dshwatch.ui.i18n.AppStrings
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.WearList

/**
 * 设置页（横滑的第二页）。
 *
 * 视觉对齐参考图：居中标题 + 一张张独立大卡片（左侧线性图标、中间标题与
 * 当前状态摘要、右侧进入箭头），列表可上下滚动，底部有分页指示点。
 *
 * ## 为什么分二级
 *
 * 三十多项设置挤在一页时，「分组标题」只是视觉分隔 —— 用户仍然要靠滚动找东西。
 * 一级页把九组压成九个入口，每个入口带一句**当前状态摘要**：
 * 「主题」那行直接写"深色"，就不用点进去确认。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    @Suppress("UNUSED_PARAMETER") profile: DeviceProfile,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current

    // 设置现在是对话页的二级页（不再靠右滑进入），所以顶部要有返回。
    // 之前作为「第二页」时不需要返回键，因为横滑本身就回去了。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WatchTopBar(title = s.navSettings, onBack = { nav.pop() })
        WearList(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(SettingsGroups.all.size) { index ->
                val id = SettingsGroups.all[index]
                WearCardRow(
                    icon = groupIcon(id),
                    title = SettingsGroups.title(id, s),
                    subtitle = summaryOf(id, settings, s),
                    trailing = "›",
                    onClick = { nav.push(Screen.SettingsGroup(id)) },
                )
            }
        }
    }
}

/** 每组一个线性图标：与参考图一致，靠图形先认出「这是哪一类」 */
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
    SettingsGroups.QQ -> Icons.Outlined.ChatBubbleOutline
    else -> Icons.Outlined.Wifi
}

private fun summaryOf(id: String, settings: AppSettings, s: AppStrings): String = when (id) {
    SettingsGroups.MODEL -> settings.activeProvider?.name ?: s.entryProviderUnset

    SettingsGroups.ABILITY -> {
        val exec = when {
            settings.localExecEnabled && settings.cloudBuildEnabled -> "本地执行 + 云端编译"
            settings.localExecEnabled -> "本地执行"
            settings.cloudBuildEnabled -> "云端编译"
            else -> ""
        }
        listOf(settings.agentMode.label, exec).filter { it.isNotBlank() }.joinToString(" · ")
    }

    SettingsGroups.PLUGINS -> {
        val on = settings.pluginsResolved(PluginRegistry.defaultEnabled())
        if (on.isEmpty()) "全部关闭" else "${on.size}/${PluginRegistry.ALL.size} 已开启"
    }

    SettingsGroups.MCP -> {
        val servers = settings.mcpServers
        val on = servers.count { it.enabled && it.confirmed }
        if (servers.isEmpty()) s.mcpEmpty else "$on/${servers.size} 已启用"
    }

    SettingsGroups.BILI -> when {
        !settings.biliEnabled -> "已关闭"
        else -> buildString {
            // 摘要先说「会不会对外说话」，再说配额 —— 用户最关心的是这个
            append(
                when (BiliModes.of(settings)) {
                    BiliMode.SUMMARY -> "只总结"
                    BiliMode.COMMENT -> "发评论"
                    BiliMode.DM -> "发私信"
                }
            )
            append(" · ")
            append(if (settings.biliDailyQuota <= 0) "不限量" else "每天 ${settings.biliDailyQuota} 条")
            if (settings.biliDynEnabled) append(" · 发动态")
            if (settings.biliImage != "none") append(" · 带图")
            if (settings.biliAutoRun) append(" · 自动")
        }
    }

    SettingsGroups.QQ -> when {
        !settings.qqEnabled -> "已关闭"
        settings.qqAppId.isBlank() -> "缺 AppID"
        settings.qqAllowOpenids.isBlank() -> "白名单为空"
        else -> if (settings.qqSandbox) "沙箱 · 群 @" else "正式 · 群 @"
    }

    SettingsGroups.CHAT -> buildList {
        if (settings.showReasoning) add(s.showReasoning)
        if (settings.autoScroll) add(s.autoScroll)
        if (settings.showTokenBadge) add(s.showTokenBadge)
        if (settings.haptics) add(s.haptics)
    }.take(2).joinToString(" · ").ifBlank { "全部关闭" }

    SettingsGroups.APPEARANCE -> when (settings.themeMode) {
        ThemeMode.SYSTEM -> s.themeSystem
        ThemeMode.DARK -> s.themeDark
        ThemeMode.LIGHT -> s.themeLight
    }

    SettingsGroups.FILES -> settings.workspaceLocation.label

    SettingsGroups.RUNTIME -> when (settings.perfMode) {
        PerfMode.AUTO -> s.perfAuto
        PerfMode.POWER_SAVE -> s.perfPowerSave
        PerfMode.BALANCED -> s.perfBalanced
        PerfMode.PERFORMANCE -> s.perfPerformance
    }

    else -> "v" + BuildConfig.VERSION_NAME
}
