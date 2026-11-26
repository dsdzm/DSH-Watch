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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
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
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.domain.sessions.SessionGroups
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
    /**
     * 只看某张卡 / 某个写作设定的会话。
     *
     * 从角色卡上的「历史」进来时传这两个值：那时用户想找的是「和这个角色的记录」，
     * 全局列表里混着几十条别的会话，等于让他重新翻一遍。
     */
    filterScene: String = "",
    filterCardId: String = "",
    /** 传了就在顶栏左侧放返回箭头（这一页是压栈进来的二级页） */
    onBack: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val filtered = filterScene.isNotBlank()

    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var cardTitle by remember { mutableStateOf("") }

    /*
     * 多选。
     *
     * 手机上「整理记录」几乎从来不是一条一条来的 —— 上周那批调试会话、
     * 导入进来重复的那几十条，用户想的是「把这几条一起删了」。
     * 一条条点 ⋯ 再点删除，删十条要二十几次点击，中间必错。
     *
     * 长按进入（和微信、Telegram 一致），点一下切换选中；顶部换成计数，
     * 底部浮出操作条。退出用左上角的 ✕，不占返回键。
     */
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey, filterScene, filterCardId) {
        conversations = runCatching {
            if (filtered) {
                container.conversationStore.listFor(filterScene, filterCardId)
            } else {
                container.conversationStore.index()
            }
        }.getOrDefault(emptyList())
        if (filtered && cardTitle.isBlank()) {
            cardTitle = runCatching { container.roleCardStore.load(filterCardId)?.displayName }
                .getOrNull().orEmpty()
        }
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

    /*
     * 分组标题要写「酒馆 · 大肥鱼」，所以得知道卡叫什么。
     *
     * 一次读两种卡：酒馆和写作都可能出现，而分组是同一屏里的事，
     * 分两次读只是多一次磁盘往返。卡被删掉时查不到，标题退化成「未命名角色」。
     */
    var cardNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        cardNames = runCatching {
            (container.roleCardStore.index(Scenes.TAVERN) + container.roleCardStore.index(Scenes.WRITING))
                .associate { it.id to it.displayName }
        }.getOrDefault(emptyMap())
    }

    val groups = remember(shown, cardNames, filtered) {
        if (filtered) emptyList() else SessionGroups.build(shown) { cardNames[it].orEmpty() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = {
                if (selecting) {
                    Column {
                        Text("已选 ${selected.size} 条", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "点一条切换选中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column {
                        Text(
                            text = if (filtered && cardTitle.isNotBlank()) cardTitle else s.sessionsTitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (filtered) {
                                "${Scenes.label(filterScene)} · ${conversations.size} 条记录"
                            } else {
                                "${conversations.size} 条记录"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                if (selecting) {
                    IconButton(onClick = {
                        selecting = false
                        selected.clear()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "退出多选")
                    }
                } else if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = {
                if (selecting) {
                    IconButton(onClick = {
                        if (selected.size == shown.size) {
                            selected.clear()
                        } else {
                            selected.clear()
                            selected.addAll(shown.map { it.id })
                        }
                    }) {
                        Icon(
                            Icons.Outlined.SelectAll,
                            contentDescription = if (selected.size == shown.size) "取消全选" else "全选",
                        )
                    }
                } else if (filtered && filterScene == Scenes.FISH) {
                    /*
                     * 养鱼没有「新建」。
                     *
                     * 那是一条鱼的一辈子：记忆、上下文、记忆文档全都挂在**同一个**
                     * conversationId 上。多开一段，用户会看到同一条鱼既不记得刚才，
                     * 也不记得昨天 —— 割裂感全从这里来。按钮直接不出现，
                     * 比按下去再提示「不支持」干净。
                     */
                } else {
                    IconButton(onClick = {
                        scope.launch {
                            /*
                             * 过滤模式下「新建」必须落在同一张卡上 —— 否则从角色的
                             * 历史页点新建，会得到一个编程场景的空会话。
                             */
                            if (filtered) {
                                container.engine.startNew(
                                    settings = settings.copy(
                                        agentMode = com.dshwatch.domain.model.AgentMode.OFF,
                                    ),
                                    scene = filterScene,
                                    roleCardId = filterCardId,
                                    title = cardTitle,
                                )
                                nav.open(Screen.RoleplayChat)
                            } else {
                                container.engine.newConversation(settings)
                                nav.select(PhoneTab.Chat)
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = s.newChat)
                    }
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
            val render: @Composable (Conversation) -> Unit = { conv ->
                ConversationCard(
                    conversation = conv,
                    selecting = selecting,
                    selected = conv.id in selected,
                    onClick = {
                        if (selecting) {
                            if (!selected.remove(conv.id)) selected.add(conv.id)
                        } else {
                            scope.launch {
                                val scene = Scenes.of(conv.scene)
                                container.engine.open(conv.id)
                                /*
                                 * 打开一条会话，等于把「当前模式」也切过去。
                                 *
                                 * 不切的话：设置里停在养鱼，从记录点开一条酒馆会话，
                                 * 会话是酒馆的、模式还是养鱼 —— 退出去再进对话 tab
                                 * 又是一条鱼，用户会觉得"点了没反应"。
                                 */
                                if (scene != Scenes.of(settings.useScene)) {
                                    container.settingsRepo.update { it.copy(useScene = scene) }
                                }
                                /*
                                 * 酒馆 / 写作 / 养鱼的会话交给会话页（它按会话场景再分派），
                                 * 编程才回对话 tab —— 那一页就是编程界面本身。
                                 */
                                if (scene == Scenes.CODING) {
                                    nav.select(PhoneTab.Chat)
                                } else {
                                    nav.open(Screen.RoleplayChat)
                                }
                            }
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
                    onLongPress = {
                        selecting = true
                        if (conv.id !in selected) selected.add(conv.id)
                    },
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                /*
                 * 分组只对「全部记录」生效。
                 *
                 * 从某张卡的历史压栈进来时，列表里本来就只有这一张卡、这一个模式，
                 * 再加一行标题等于把仅有的纵向空间花在重复信息上。
                 */
                if (groups.isEmpty()) {
                    items(shown, key = { it.id }) { conv -> render(conv) }
                } else {
                    groups.forEach { group ->
                        item(key = "h:${group.key}") {
                            SessionGroupHeader(
                                title = group.title,
                                count = group.count,
                                top = group !== groups.first(),
                            )
                        }
                        items(group.items, key = { it.id }) { conv -> render(conv) }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }

        if (selecting) {
            SelectionActions(
                count = selected.size,
                onPin = {
                    scope.launch {
                        selected.toList().forEach { id ->
                            container.conversationStore.setPinned(id, true)
                        }
                        selected.clear()
                        selecting = false
                        reloadKey++
                    }
                },
                onUnpin = {
                    scope.launch {
                        selected.toList().forEach { id ->
                            container.conversationStore.setPinned(id, false)
                        }
                        selected.clear()
                        selecting = false
                        reloadKey++
                    }
                },
                onDelete = { confirmDelete = true },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selected.size} 条记录？") },
            text = { Text("里边的消息会一起删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val doomed = selected.toList()
                    scope.launch {
                        doomed.forEach { id -> runCatching { container.conversationStore.delete(id) } }
                        selected.clear()
                        selecting = false
                        reloadKey++
                    }
                }) { Text(s.delete) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(s.cancel) }
            },
        )
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

/**
 * 分组标题。
 *
 * 用「模式 · 角色」一行写清楚，右边跟条数 —— 条数不是装饰：用户判断
 * 「这条卡是不是该清理了」就看这个数。
 *
 * 第一组上面不留白（顶栏已经给了间距），后面的组各留一段，
 * 否则整页会像一串连续的卡片，分不出组界。
 */
@Composable
private fun SessionGroupHeader(title: String, count: Int, top: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = if (top) 12.dp else 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    selecting: Boolean = false,
    selected: Boolean = false,
    onLongPress: () -> Unit = {},
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
                .combinedClickable(
                    onClick = onClick,
                    // 选择模式下长按不再弹菜单：这时用户是在挑东西，不是想操作一条
                    onLongClick = { if (selecting) onClick() else onLongPress() },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Icon(
                    imageVector = if (selected) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
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
            if (!selecting) IconButton(onClick = { menu = true }) {
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

/**
 * 多选时的底部操作条。
 *
 * 浮在列表上方而不是塞进列表：手机上用户刚点完的那几条可能在任意位置，
 * 底部固定条始终在拇指够得到的地方。
 */
@Composable
private fun SelectionActions(
    count: Int,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SelectionAction("置顶", count > 0, onPin)
            SelectionAction("取消置顶", count > 0, onUnpin)
            SelectionAction("删除", count > 0, onDelete, danger = true)
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}
