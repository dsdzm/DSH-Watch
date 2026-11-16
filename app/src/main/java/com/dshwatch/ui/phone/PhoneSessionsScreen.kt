package com.dshwatch.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Conversation
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手机版的记录页。
 *
 * 手表上这一页是「只有卡片、只能一条条点」—— 屏幕小到没有空间做搜索，
 * 也不能长按出菜单（长按在 Wear 上是另一个手势）。手机上这两件事都是刚需：
 * 会话一多，找上周那个会话只能靠搜；删/置顶只能靠长按或右侧菜单。
 *
 * 因此这里是**重写**的，不是把手表的 `WearList` 换个宽度。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhoneSessionsScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(reloadKey) {
        conversations = runCatching { container.conversationStore.index() }.getOrDefault(emptyList())
    }

    val shown = remember(conversations, query) {
        if (query.isBlank()) {
            conversations
        } else {
            val q = query.trim().lowercase()
            conversations.filter {
                it.displayTitle.lowercase().contains(q) || it.model.lowercase().contains(q)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = {
                Column {
                    Text(s.sessionsTitle, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${conversations.size} 条记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = {
                    scope.launch {
                        container.engine.newConversation(settings)
                        nav.select(PhoneTab.Chat)
                    }
                }) {
                    Icon(Icons.Filled.Add, contentDescription = s.newChat)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            placeholder = { Text("搜索标题或模型") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )

        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isBlank()) s.noSessions else "没有匹配的记录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.id }) { conv ->
                    ConversationCard(
                        conversation = conv,
                        onClick = {
                            scope.launch {
                                container.engine.open(conv.id)
                                nav.select(PhoneTab.Chat)
                            }
                        },
                        onRename = {
                            renameTarget = conv
                            renameText = conv.displayTitle
                        },
                        onTogglePin = {
                            scope.launch {
                                container.conversationStore.setPinned(conv.id, !conv.pinned)
                                reloadKey++
                            }
                        },
                        onDelete = { deleteTarget = conv },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            container.conversationStore.rename(target.id, renameText.trim())
                            renameTarget = null
                            reloadKey++
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(s.cancel) }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条记录？") },
            text = { Text("「${target.displayTitle}」里的消息会一起删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { container.conversationStore.delete(target.id) }
                        deleteTarget = null
                        reloadKey++
                    }
                }) { Text(s.delete) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(s.cancel) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = conversation.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = listOf(
                        timeLabel(conversation.updatedAt),
                        "${conversation.messageCount} 条",
                        conversation.model.ifBlank { "默认模型" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = { menu = false; onRename() },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (conversation.pinned) "取消置顶" else "置顶") },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                    onClick = { menu = false; onTogglePin() },
                )
                HorizontalDivider()
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

private fun timeLabel(ts: Long): String {
    if (ts <= 0L) return "—"
    val now = System.currentTimeMillis()
    val fmt = when {
        now - ts < 24 * 3600_000L -> SimpleDateFormat("HH:mm", Locale.getDefault())
        now - ts < 365L * 24 * 3600_000L -> SimpleDateFormat("MM-dd", Locale.getDefault())
        else -> SimpleDateFormat("yyyy-MM", Locale.getDefault())
    }
    return fmt.format(Date(ts))
}
