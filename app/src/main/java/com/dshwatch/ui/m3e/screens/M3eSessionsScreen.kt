package com.dshwatch.ui.m3e.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme as W3
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.ContextUsageDialog
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.m3e.M3eConfirmDialog
import com.dshwatch.ui.m3e.M3eDialog
import com.dshwatch.ui.m3e.M3eDialogAction
import com.dshwatch.ui.m3e.M3eDialogRow
import com.dshwatch.ui.m3e.M3eEmptyState
import com.dshwatch.ui.m3e.M3eGhostButton
import com.dshwatch.ui.m3e.M3eIconTap
import com.dshwatch.ui.m3e.M3eList
import com.dshwatch.ui.m3e.M3eScaffold
import com.dshwatch.ui.m3e.rememberM3eListState
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.sessions.ExportDialog
import com.dshwatch.ui.screens.sessions.ImportDialog
import com.dshwatch.ui.screens.sessions.WorkDirDialog
import com.dshwatch.util.Fmt
import kotlinx.coroutines.launch

/**
 * M3E 的会话列表页 —— 独立实现。
 *
 * ## 每一行是一张官方 Card，不是"一行文字加个底"
 *
 * 经典页的 `ConversationRow` 是一个 `Row` + `glassSurface` 手绘的圆角背景，
 * 里面再摆标题、副标题和一颗更多按钮。M3E 这版直接是 `Card(onClick = ...)`：
 *
 * - 点击时整块卡片**形变**（`SurfaceTransformation`），这是官方组件的语言
 * - 涟漪、焦点、无障碍语义、表冠悬停高亮全部由组件负责
 * - 卡片本身带 26dp 圆角与容器色，不需要再手绘背景
 *
 * 「更多」那颗按钮仍然必须有 —— 长按在 Wear 上要 500ms，而列表项的长按
 * 又常被系统的滑动手势抢走。所以卡片里嵌一颗 32dp 的 `IconButton`：
 * 它自己吃掉点击事件，不会连带触发卡片的"打开会话"。
 *
 * ## 副行只保留两件事
 *
 * 经典页的副行是「时间 · 条数 · 目录 · token」四段拼接，在 200dp 宽里
 * 会被截成「3 小时前 · 12 条…」。M3E 这版只留**时间 + 条数**：
 * 目录和 token 是点进去之后才关心的，列表上它们只是噪音。
 */
@Composable
fun M3eSessionsScreen(
    container: AppContainer,
    nav: WatchNavigator,
    filterScene: String = "",
    filterCardId: String = "",
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val listState = rememberM3eListState()
    val filtered = filterScene.isNotBlank()

    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var menuTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var dirTarget by remember { mutableStateOf<Conversation?>(null) }
    var exportTarget by remember { mutableStateOf<Conversation?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var cardTitle by remember { mutableStateOf("") }

    LaunchedEffect(reloadKey, filterScene, filterCardId) {
        conversations = if (filtered) {
            container.conversationStore.listFor(filterScene, filterCardId)
        } else {
            container.conversationStore.index()
        }
        if (filtered && cardTitle.isBlank()) {
            cardTitle = runCatching { container.roleCardStore.load(filterCardId)?.displayName }
                .getOrNull().orEmpty()
        }
    }

    M3eScaffold(
        title = if (filtered && cardTitle.isNotBlank()) cardTitle else s.sessionsTitle,
        onBack = { nav.pop() },
        // 列表自己带横向内边距（TransformingLazyColumn 的 6dp），骨架不再叠加
        horizontalInset = 0.dp,
        actions = {
            M3eIconTap(icon = Icons.Filled.Add, contentDescription = s.newChat, onClick = {
                scope.launch {
                    if (filtered) {
                        container.engine.startNew(
                            settings = container.settings.value.copy(
                                agentMode = com.dshwatch.domain.model.AgentMode.OFF,
                            ),
                            scene = filterScene,
                            roleCardId = filterCardId,
                            title = cardTitle,
                        )
                        nav.pop()
                        nav.push(Screen.RoleplayChat)
                    } else {
                        container.engine.newConversation(container.settings.value)
                        nav.toChat()
                    }
                }
            })
        },
    ) {
        M3eList(state = listState, modifier = Modifier.weight(1f)) {
            if (conversations.isEmpty()) {
                item(key = "__empty__") {
                    M3eEmptyState(
                        title = if (filtered) "还没有对话" else s.noSessions,
                        hint = "发第一条消息，或者从下面导入一段 TXT",
                    )
                }
            }

            items(conversations.size, key = { conversations[it].id }) { index ->
                val conv = conversations[index]
                SessionCard(
                    conversation = conv,
                    onClick = {
                        scope.launch {
                            container.engine.open(conv.id)
                            // 打开之后去哪由会话自己的场景决定，不看「从哪进来」：
                            // 全局列表里点开一个酒馆会话，跳到编程对话页会没有角色名、
                            // 也没有记忆入口，看起来像内容丢了。
                            if (filtered ||
                                (conv.scene.isNotBlank() && conv.scene != Scenes.CODING)
                            ) {
                                nav.pop()
                                nav.push(Screen.RoleplayChat)
                            } else {
                                nav.toChat()
                            }
                        }
                    },
                    onMore = { menuTarget = conv },
                )
            }

            item(key = "__import__") {
                M3eGhostButton(
                    text = "导入 TXT",
                    icon = Icons.Outlined.Upload,
                    onClick = { showImport = true },
                )
            }

            item(key = "__tail__") { Spacer(Modifier.height(6.dp)) }
        }
    }

    menuTarget?.let { conv ->
        M3eDialog(
            onDismissRequest = { menuTarget = null },
            title = conv.displayTitle,
            content = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                M3eDialogRow(s.renameSession) {
                    menuTarget = null
                    renameTarget = conv
                }
                M3eDialogRow("导出对话") {
                    menuTarget = null
                    exportTarget = conv
                }
                M3eDialogRow(s.sessionWorkDir) {
                    menuTarget = null
                    dirTarget = conv
                }
                M3eDialogRow(if (conv.pinned) s.unpin else s.pin) {
                    menuTarget = null
                    scope.launch {
                        container.conversationStore.setPinned(conv.id, !conv.pinned)
                        reloadKey++
                    }
                }
                M3eDialogRow(s.deleteSession) {
                    menuTarget = null
                    deleteTarget = conv
                }
            }
            },
            buttons = {
                M3eDialogAction(s.cancel, primary = false) { menuTarget = null }
            },
        )
    }

    renameTarget?.let { conv ->
        var value by remember(conv.id) { mutableStateOf(conv.displayTitle) }
        M3eDialog(
            onDismissRequest = { renameTarget = null },
            title = s.renameSession,
            /*
             * 表上唯一的文本输入在这一处。
             *
             * 用 `OutlinedTextField` 而不是继续自绘输入行：系统键盘弹出后
             * 输入框会被顶到圆心以上，官方 TextField 自己带光标可见性处理，
             * 自绘的不会 —— 光标跑到可视区外时用户完全不知道自己在打什么。
             */
            content = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = W3.typography.bodySmall,
                )
            },
            buttons = {
                M3eDialogAction(s.save, primary = true) {
                    val title = value.trim().ifBlank { "新对话" }
                    scope.launch {
                        container.conversationStore.rename(conv.id, title)
                        container.engine.refreshConversation()
                        reloadKey++
                    }
                    renameTarget = null
                }
                M3eDialogAction(s.cancel, primary = false) { renameTarget = null }
            },
        )
    }

    dirTarget?.let { conv ->
        WorkDirDialog(
            container = container,
            conversation = conv,
            onPicked = { newDir ->
                scope.launch {
                    container.conversationStore.updateMeta(conv.id) { it.copy(workDir = newDir) }
                    container.engine.refreshConversation()
                    conversations = container.conversationStore.index()
                }
                dirTarget = null
            },
            onDismiss = { dirTarget = null },
        )
    }

    exportTarget?.let { conv ->
        ExportDialog(
            container = container,
            conversation = conv,
            onDismiss = { exportTarget = null },
        )
    }

    if (showImport) {
        ImportDialog(
            container = container,
            scene = if (filtered) filterScene else Scenes.CODING,
            cardId = if (filtered) filterCardId else "",
            onImported = { id, scene ->
                showImport = false
                reloadKey++
                scope.launch {
                    container.engine.open(id)
                    if (filtered || scene != Scenes.CODING) {
                        nav.pop()
                        nav.push(Screen.RoleplayChat)
                    } else {
                        nav.toChat()
                    }
                }
            },
            onDismiss = { showImport = false },
        )
    }

    deleteTarget?.let { conv ->
        M3eConfirmDialog(
            title = s.deleteSession,
            text = s.deleteSessionConfirm,
            onConfirm = {
                scope.launch {
                    container.engine.deleteConversation(conv.id)
                    conversations = container.conversationStore.index()
                }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * 一条会话。
 *
 * 结构：卡片 → Row(内容列 + 更多按钮)。
 * 内容列两行：标题（带置顶星标）、副行（相对时间 · 条数）。
 */
@Composable
private fun SessionCard(
    conversation: Conversation,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val s = LocalStrings.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp,
            end = 2.dp,
            top = 7.dp,
            bottom = 7.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Text(
                            text = "★",
                            style = W3.typography.labelSmall,
                            color = W3.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = conversation.displayTitle,
                        style = W3.typography.bodyMedium,
                        color = W3.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = Fmt.relative(conversation.updatedAt) +
                        " · " + conversation.messageCount + " " + s.messagesCount,
                    style = W3.typography.labelSmall,
                    color = W3.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            /*
             * 更多按钮不用 M3eIconTap（44dp 命中区）：卡片内容高只有 56dp 左右，
             * 44dp 的方形命中区会把卡片撑高，还会把标题挤到左边一小条。
             * 32dp 是「能点中、又不撑高」的折中，图标仍是 20dp。
             */
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = s.more,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
