package com.dshwatch.ui.m3e.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.dshwatch.BuildConfig
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.bili.BiliMode
import com.dshwatch.domain.bili.BiliModes
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.domain.plugin.PluginRegistry
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.m3e.M3eCardRow
import com.dshwatch.ui.m3e.M3eList
import com.dshwatch.ui.m3e.M3eScaffold
import com.dshwatch.ui.m3e.M3eSectionHeader
import com.dshwatch.ui.m3e.rememberM3eListState
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.settings.SettingsGroups

/**
 * M3E 的设置一级页 —— **独立实现，不复用经典页面**。
 *
 * ## 和经典页的差别不是配色，是结构
 *
 * 经典页是 `WatchScaffold + WearList`：一个自定义顶栏 + 一个逐项绘制、
 * 靠 `WearCardRow` 拼出卡片感的列表。M3E 这版换成了官方的
 * `TransformingLazyColumn`（见 [M3eList]）：
 *
 * | | 经典 | M3E |
 * |---|---|---|
 * | 滚动容器 | `LazyColumn` / `WearList` | `TransformingLazyColumn` |
 * | 贴边效果 | 无（或按项算 padding，会抖） | 官方绘制期变换：缩小 + 淡出 + 收窄 |
 * | 分组 | 卡片内分隔线 | 独立小标题 + 卡片之间留缝 |
 * | 图标 | 24dp 线性图标嵌在行首 | 同左，但图标色降到 `onSurfaceVariant` |
 *
 * ## 分组标题为什么重新加了回来
 *
 * 上一版把所有分组拍平成十张卡，靠「间距稍微大一点」暗示分组 ——
 * 结果是十张卡看起来完全同级，用户得靠读每张卡的副标题去猜哪几张是一类。
 * 十项在手表上正好是「两屏半」，翻起来没有地标。
 *
 * 现在按用途切成两段：**配置**（模型 → 连接）与**支持**（帮助）。
 * 段标题用主色小字，成本是两行高度，换来的是滚动时有个可数的位置。
 */
@Composable
fun M3eSettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val listState = rememberM3eListState()

    M3eScaffold(
        title = s.navSettings,
        onBack = { nav.pop() },
        horizontalInset = 0.dp,
    ) {
        M3eList(state = listState, modifier = Modifier.weight(1f)) {
            item(key = "__hdr_config__") { M3eSectionHeader("配置") }

            items(SettingsGroups.all.size, key = { SettingsGroups.all[it] }) { index ->
                val id = SettingsGroups.all[index]
                M3eCardRow(
                    icon = m3eGroupIcon(id),
                    title = SettingsGroups.title(id, s),
                    subtitle = m3eSummaryOf(id, settings, s),
                    onClick = { nav.push(Screen.SettingsGroup(id)) },
                )
            }

            item(key = "__hdr_support__") { M3eSectionHeader("支持") }

            item(key = "__help__") {
                M3eCardRow(
                    icon = Icons.Outlined.HelpOutline,
                    title = s.helpTitle,
                    subtitle = s.helpSubtitle,
                    onClick = { nav.push(Screen.Help) },
                )
            }

            /*
             * 版本号作为列表最后一项，而不是钉在页脚。
             *
             * 钉页脚时它会永久占掉底部 14dp，而这个数字一年只看一次；
             * 作为列表项则只在翻到底时出现，顺手还能滚出屏幕。
             */
            item(key = "__version__") {
                com.dshwatch.ui.m3e.M3eCaption("DSH Watch v" + BuildConfig.VERSION_NAME)
            }
        }
    }
}

/**
 * 每组的当前状态摘要。
 *
 * 与经典页的 `summaryOf` 是两份，不是抄漏了 —— **两边的宽度不一样**：
 * 经典页的摘要能占满一张 200dp 的卡片，M3E 的卡片右边还要给箭头留位置，
 * 实际可读宽度只有 150dp 左右，大约 9 个汉字。
 *
 * 所以这里统一做一次「压到最要紧的一件事」：
 * 模型组报模型名、外观组报明暗、运行组报档位 —— 而不是罗列四个开关的状态。
 * 想看全的话副标题本来就只是个提示，点进去才是详情。
 */
private fun m3eSummaryOf(id: String, settings: AppSettings, s: com.dshwatch.ui.i18n.AppStrings): String =
    when (id) {
        SettingsGroups.MODEL -> settings.activeProvider?.name ?: s.entryProviderUnset

        SettingsGroups.ABILITY -> {
            val exec = when {
                settings.localExecEnabled && settings.cloudBuildEnabled -> "本地 + 云端"
                settings.localExecEnabled -> "本地执行"
                settings.cloudBuildEnabled -> "云端编译"
                else -> ""
            }
            listOf(settings.agentMode.label, exec).filter { it.isNotBlank() }.joinToString(" · ")
        }

        SettingsGroups.PLUGINS -> {
            val on = settings.pluginsResolved(PluginRegistry.defaultEnabled())
            val total = PluginRegistry.allFor(settings).size
            if (on.isEmpty()) "全部关闭" else "${on.size}/$total 已开启"
        }

        SettingsGroups.MCP -> {
            val servers = settings.mcpServers
            val on = servers.count { it.enabled && it.confirmed }
            if (servers.isEmpty()) s.mcpEmpty else "$on/${servers.size} 已启用"
        }

        SettingsGroups.BILI -> when {
            !settings.biliEnabled -> "已关闭"
            else -> buildString {
                append(
                    when (BiliModes.of(settings)) {
                        BiliMode.SUMMARY -> "只总结"
                        BiliMode.COMMENT -> "发评论"
                        BiliMode.DM -> "发私信"
                    }
                )
                append(" · ")
                append(if (settings.biliDailyQuota <= 0) "不限量" else "每天 ${settings.biliDailyQuota} 条")
            }
        }

        SettingsGroups.CHAT -> buildList {
            if (settings.showReasoning) add(s.showReasoning)
            if (settings.autoScroll) add(s.autoScroll)
            if (settings.showTokenBadge) add(s.showTokenBadge)
            if (settings.haptics) add(s.haptics)
        }.take(1).joinToString("").ifBlank { "全部关闭" }

        SettingsGroups.APPEARANCE -> when {
            // 取色在生效时，明暗不是用户选的那个 —— 说出来，否则点进去会困惑
            settings.dynamicColor || settings.seedColor.isNotBlank() -> "跟随壁纸"
            else -> when (settings.themeMode) {
                ThemeMode.SYSTEM -> s.themeSystem
                ThemeMode.DARK -> s.themeDark
                ThemeMode.LIGHT -> s.themeLight
            }
        }

        SettingsGroups.FILES -> settings.workspaceLocation.label

        SettingsGroups.RUNTIME -> when (settings.perfMode) {
            PerfMode.AUTO -> s.perfAuto
            PerfMode.POWER_SAVE -> s.perfPowerSave
            PerfMode.BALANCED -> s.perfBalanced
            PerfMode.PERFORMANCE -> s.perfPerformance
        }

        else -> "连接与配对"
    }

/**
 * 每组一个线性图标。
 *
 * **不是从经典页搬过来的那一份**。经典页要的是「一眼认出是哪一类」，
 * 所以用最写实的物体（摄像机、插头、文件夹）；M3E 的卡片里图标只有 24dp、
 * 颜色压到 `onSurfaceVariant`，写实的物体在这个尺寸上会糊成一团墨点 ——
 * 这里换成轮廓更简洁的一组。
 */
internal fun m3eGroupIcon(id: String): ImageVector = when (id) {
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
