package com.dshwatch.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.Conversation
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.ConfirmDialog
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.IconTap
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.util.Fmt
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var menuTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var dirTarget by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(reloadKey) {
        conversations = container.conversationStore.index()
    }

    WatchScaffold(
        title = s.sessionsTitle,
        onBack = { nav.pop() },
        actions = {
            IconTap(Icons.Filled.Add, s.newChat) {
                scope.launch {
                    container.engine.newConversation(container.settings.value)
                    nav.toChat()
                }
            }
        },
    ) {
        if (conversations.isEmpty()) {
            EmptyState(title = s.noSessions)
            return@WatchScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(conversations, key = { it.id }) { conv ->
                ConversationRow(
                    conversation = conv,
                    onClick = {
                        scope.launch {
                            container.engine.open(conv.id)
                            nav.toChat()
                        }
                    },
                    onMore = { menuTarget = conv },
                )
            }
        }
    }

    menuTarget?.let { conv ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = {
                Text(
                    text = conv.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column {
                    MenuItem(s.renameSession) {
                        menuTarget = null
                        renameTarget = conv
                    }
                    MenuItem(s.sessionWorkDir) {
                        menuTarget = null
                        dirTarget = conv
                    }
                    MenuItem(if (conv.pinned) s.unpin else s.pin) {
                        menuTarget = null
                        scope.launch {
                            container.conversationStore.setPinned(conv.id, !conv.pinned)
                            reloadKey++
                        }
                    }
                    MenuItem(s.deleteSession, danger = true) {
                        menuTarget = null
                        deleteTarget = conv
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuTarget = null }) { Text(s.cancel) }
            },
        )
    }

    renameTarget?.let { conv ->
        var value by remember(conv.id) { mutableStateOf(conv.displayTitle) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(s.renameSession, style = MaterialTheme.typography.titleSmall) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = value.trim().ifBlank { "新对话" }
                    scope.launch {
                        container.conversationStore.rename(conv.id, title)
                        container.engine.refreshConversation()
                        reloadKey++
                    }
                    renameTarget = null
                }) { Text(s.save) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(s.cancel) }
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
                    // 改的如果是当前打开的会话，引擎里的那份也要跟着换，
                    // 否则本轮到下一次发送之间还会往旧目录写。
                    container.engine.refreshConversation()
                    conversations = container.conversationStore.index()
                }
                dirTarget = null
            },
            onDismiss = { dirTarget = null },
        )
    }

    deleteTarget?.let { conv ->
        ConfirmDialog(
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

@Composable
private fun MenuItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .tap(onClick = withHaptic(onClick))
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .tap(onClick = withHaptic(onClick))
            .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.pinned) {
                    Text(
                        text = "★",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = conversation.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(Fmt.relative(conversation.updatedAt))
                    append(" · ")
                    append(conversation.messageCount).append(" ").append(s.messagesCount)
                    conversation.workDir.substringAfterLast('/').takeIf { it.isNotBlank() }?.let {
                        append(" · 📁 ").append(it)
                    }
                    if (conversation.usage.total > 0) {
                        append(" · ").append(Fmt.tokens(conversation.usage.total)).append(" ")
                        append(s.tokensLabel)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconTap(Icons.Filled.MoreVert, s.more, onClick = onMore)
        }
    }
}

/**
 * 「这个会话的文件放哪」。
 *
 * 默认每个会话一个独立目录（`progress/<id 前缀>`），所以这里**不需要用户操作也能正常工作** ——
 * 提供这个界面是为了两种情况：
 *  1. 用户想把某个会话的产物挪到自己指定的目录（比如按项目归类）；
 *  2. 用户想复用已有目录（继续在别的会话建过的项目上接着做）。
 *
 * 因此默认值直接给出「新建文件夹」，而不是让用户先想名字再看到候选。
 */
@Composable
private fun WorkDirDialog(
    container: AppContainer,
    conversation: Conversation,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val rootDir = ConversationStore.WORKSPACE_ROOT_DIR
    var dirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var selected by remember(conversation.id) { mutableStateOf(conversation.workDir) }

    LaunchedEffect(conversation.id) {
        dirs = container.workspaceStore.listDirs(rootDir)
    }

    fun sanitize(raw: String): String = raw.trim()
        .replace('\\', '-')
        .replace('/', '-')
        .replace("..", "")
        .filter { !it.isISOControl() }
        .take(40)
        .trim('-', ' ', '.')

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.sessionWorkDir, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 250.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = s.sessionWorkDirHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "当前：" + conversation.workDir.ifBlank { "（工作空间根目录）" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))

                // ---- 新建 ----
                Text(
                    text = s.workDirNew,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall,
                    placeholder = {
                        Text(s.workDirNewPlaceholder, style = MaterialTheme.typography.labelSmall)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Chip(
                    text = s.workDirNew,
                    selected = newName.isNotBlank() && selected == "$rootDir/" + sanitize(newName),
                    onClick = {
                        val name = sanitize(newName)
                        if (name.isNotEmpty()) selected = "$rootDir/$name"
                    },
                )

                Spacer(Modifier.height(10.dp))

                // ---- 已有 ----
                Text(
                    text = s.workDirPick,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                if (dirs.isEmpty()) {
                    Text(
                        text = s.workDirEmpty,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    dirs.forEach { name ->
                        val value = "$rootDir/$name"
                        DirRow(
                            text = name,
                            selected = selected == value,
                            onClick = { selected = value },
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))
                DirRow(
                    text = s.workDirUseRoot,
                    selected = selected.isBlank(),
                    onClick = { selected = "" },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPicked(selected) }) { Text(s.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancel) }
        },
    )
}

@Composable
private fun DirRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
