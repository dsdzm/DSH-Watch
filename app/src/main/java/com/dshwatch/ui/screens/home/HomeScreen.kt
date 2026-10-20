package com.dshwatch.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.components.PageDots
import com.dshwatch.ui.components.PillItem
import com.dshwatch.ui.components.PillMenuPage
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import kotlinx.coroutines.launch

/**
 * 主页：纯黑底 + 胶囊菜单 + 底部页面指示点。
 *
 * 视觉对齐参考图（下载器菜单）：条目宽度随文字长短、左侧线性图标、
 * 全圆角深灰胶囊、页面间左右滑动、底部三点指示当前页。
 *
 * 三页的分法按使用频率而不是功能域：
 *  1. 常用 —— 新对话 / 历史记录 / 工作空间（每天都会点的）
 *  2. 模型 —— 供应商 / 用量 / 云端执行（配置与成本相关的）
 *  3. 其他 —— 设置 / 关于 / 连接诊断（低频）
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 3 })

    // 最近一次会话：有它就把「继续对话」放在第一项，进 App 一下就能接着干
    var recentId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        recentId = runCatching { container.conversationStore.index().firstOrNull()?.id }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            PillMenuPage(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                when (page) {
                    0 -> {
                        PillItem(
                            icon = Icons.Outlined.AddComment,
                            label = s.newChat,
                            onClick = {
                                scope.launch {
                                    container.engine.newConversation(settings)
                                    nav.push(Screen.Chat)
                                }
                            },
                        )
                        val recent = recentId
                        PillItem(
                            icon = Icons.Outlined.PlayArrow,
                            label = s.homeContinue,
                            enabled = recent != null,
                            onClick = {
                                if (recent == null) return@PillItem
                                scope.launch {
                                    container.engine.open(recent)
                                    nav.push(Screen.Chat)
                                }
                            },
                        )
                        PillItem(
                            icon = Icons.Outlined.History,
                            label = s.navSessions,
                            onClick = { nav.push(Screen.Sessions) },
                        )
                    }

                    1 -> {
                        PillItem(
                            icon = Icons.Outlined.FolderOpen,
                            label = s.workspaceTitle,
                            onClick = { nav.push(Screen.Workspace) },
                        )
                        PillItem(
                            icon = Icons.Outlined.Dns,
                            label = s.navProviders,
                            onClick = { nav.push(Screen.Providers) },
                        )
                        PillItem(
                            icon = Icons.Outlined.BarChart,
                            label = s.navUsage,
                            onClick = { nav.push(Screen.Usage) },
                        )
                        PillItem(
                            icon = Icons.Outlined.CloudQueue,
                            label = s.execScreenTitle,
                            onClick = { nav.push(Screen.ExecSettings) },
                        )
                    }

                    else -> {
                        PillItem(
                            icon = Icons.Outlined.Settings,
                            label = s.navSettings,
                            onClick = { nav.push(Screen.Settings) },
                        )
                        PillItem(
                            icon = Icons.Outlined.Info,
                            label = s.navAbout,
                            onClick = { nav.push(Screen.About) },
                        )
                        PillItem(
                            icon = Icons.Outlined.BugReport,
                            label = s.diagTitle,
                            onClick = { nav.push(Screen.Diagnostics) },
                        )
                    }
                }
            }
        }

        PageDots(
            count = 3,
            current = pager.currentPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 7.dp),
        )
    }
}
