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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshwatch.BuildConfig
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.plugin.PluginRegistry
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.AppStrings
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator

/**
 * 设置主页（一级）。
 *
 * ## 为什么要分二级
 *
 * 三十多项设置挤在一页时，「分组标题」只是视觉分隔 —— 用户仍然要靠滚动找东西。
 * 一级页把七组压成七个入口，每个入口带一句**当前状态摘要**：
 * 「主题」那行直接写"深色"，就不用点进去确认。
 *
 * 找设置的动作从「滚一遍」变成「看一眼 → 点进去」。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    @Suppress("UNUSED_PARAMETER") profile: DeviceProfile,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current

    WatchScaffold(title = s.settingsTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
        ) {
            SettingCard {
                SettingsGroups.all.forEachIndexed { index, id ->
                    SettingRow(
                        title = SettingsGroups.title(id, s),
                        subtitle = summaryOf(id, settings, s),
                        trailing = "›",
                        showDivider = index != SettingsGroups.all.lastIndex,
                        onClick = { nav.push(Screen.SettingsGroup(id)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "点任意一组进去调整。这里显示的是每一项当前的状态。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * 每个分组入口右侧的摘要。
 *
 * 只放**最能代表当前状态的一个值**，不是把组内所有设置罗列一遍 ——
 * 摘要的作用是"不用点进去也知道现在是什么样"，太长反而没人看。
 */
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
