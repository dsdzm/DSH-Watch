package com.dshwatch.ui.screens.sessions

import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Upload
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.files.UriStreams
import com.dshwatch.ui.screens.files.rememberFilePicking
import com.dshwatch.domain.ChatTranscript
import com.dshwatch.domain.TranscriptFile
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.domain.sessions.SessionGroups
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.ConfirmDialog
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.GlassDialogRow
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.IconTap
import com.dshwatch.ui.components.ShareResult
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.shareFile
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.store.StoredConversation
import com.dshwatch.util.Fmt
import com.dshwatch.ui.wear.WearList
import com.dshwatch.ui.wear.wearVerticalScroll
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.TextRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SessionsScreen(
    container: AppContainer,
    nav: WatchNavigator,
    /**
     * 只看某个角色卡 / 写作设定的会话。
     *
     * 从酒馆、写作页的「历史」按钮进来时传这两个参数：那时用户想找的是
     * 「和这个角色的记录」，全局列表里混着几十条编程会话，等于让他重新翻一遍。
     */
    filterScene: String = "",
    filterCardId: String = "",
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
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
    /*
     * 多选。手表上没有「拖框」，也不适合常驻复选框 —— 列表平时是拿来点开的。
     * 所以做成一个模式：从 ⋯ 菜单进去，选中后批量置顶 / 删除，完成后退出。
     */
    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var confirmBatchDelete by remember { mutableStateOf(false) }

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

    /*
     * 分组标题要写「酒馆 · 大肥鱼」。
     *
     * 手表上这一行字很贵（一屏就七八行），所以只在**真的分了卡**的时候才加，
     * 单卡的场景（编程、养鱼）标题只写模式名。
     */
    var cardNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        cardNames = runCatching {
            (container.roleCardStore.index(Scenes.TAVERN) + container.roleCardStore.index(Scenes.WRITING))
                .associate { it.id to it.displayName }
        }.getOrDefault(emptyMap())
    }
    val groups = remember(conversations, cardNames, filtered) {
        if (filtered) emptyList() else SessionGroups.build(conversations) { cardNames[it].orEmpty() }
    }

    WatchScaffold(
        title = if (filtered && cardTitle.isNotBlank()) cardTitle else s.sessionsTitle,
        onBack = { nav.pop() },
        actions = {
            if (selecting) {
                IconTap(Icons.Filled.Done, "完成") {
                    selecting = false
                    selectedIds.clear()
                }
            } else if (filtered && filterScene == Scenes.FISH) {
                // 养鱼只有一条鱼、一段话。多开一段就断了记忆与上下文，
                // 所以这里连入口都不给（理由同手机版记录页）
            } else IconTap(Icons.Filled.Add, s.newChat) {
                scope.launch {
                    // 过滤模式下「新建」必须落在同一张卡上，
                    // 否则从角色的历史页点新建，会得到一个编程场景的空会话
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
            }
        },
    ) {
        // 外层 WatchScaffold 已经给了横向留白，这里只补上下的呼吸空间，
        // 否则会叠成两层内边距，在 205dp 的表盘上白白浪费 20dp 宽度
        WearList(
            state = com.dshwatch.ui.wear.rememberKeptListState("sessions"),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /*
             * 空列表也要把「导入对话」摆出来 —— 这是最需要它的时刻：
             * 用户手里只有一段从别处整理的记录，还没有任何会话。
             * 以前这里直接 return@WatchScaffold，连入口都没有。
             */
            if (conversations.isEmpty()) {
                item(key = "__empty__") {
                    EmptyState(title = if (filtered) "还没有对话" else s.noSessions)
                }
            }
            if (selecting) {
                item(key = "__select_bar__") {
                    SelectionBar(
                        count = selectedIds.size,
                        total = conversations.size,
                        onSelectAll = {
                            if (selectedIds.size == conversations.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(conversations.map { it.id })
                            }
                        },
                        onPin = {
                            scope.launch {
                                selectedIds.forEach { id ->
                                    container.conversationStore.setPinned(id, true)
                                }
                                selectedIds.clear()
                                selecting = false
                                reloadKey++
                            }
                        },
                        onDelete = { confirmBatchDelete = true },
                    )
                }
            }
            if (groups.isEmpty()) {
                items(conversations.size, key = { conversations[it].id }) { index ->
                    ConversationItem(conversations[index], selecting, selectedIds, scope, container, nav, filtered, onMore = { menuTarget = it })
                }
            } else {
                groups.forEach { group ->
                    item(key = "h:${group.key}") { GroupLabel(group.title, group.count) }
                    items(group.count, key = { group.items[it].id }) { index ->
                        ConversationItem(group.items[index], selecting, selectedIds, scope, container, nav, filtered, onMore = { menuTarget = it })
                    }
                }
            }
            item(key = "__import__") {
                WatchGhostButton(
                    text = "导入 TXT",
                    icon = Icons.Outlined.Upload,
                    modifier = Modifier.padding(top = 4.dp),
                    onClick = { showImport = true },
                )
            }
        }
    }

    if (confirmBatchDelete) {
        ConfirmDialog(
            title = "删除 ${selectedIds.size} 个对话",
            text = "删除后无法恢复。正在生成的那条如果被选中，也会一起停掉。",
            confirmText = "删除",
            onConfirm = {
                confirmBatchDelete = false
                val doomed = selectedIds.toList()
                scope.launch {
                    doomed.forEach { id -> container.conversationStore.delete(id) }
                    selectedIds.clear()
                    selecting = false
                    reloadKey++
                }
            },
            onDismiss = { confirmBatchDelete = false },
        )
    }

    menuTarget?.let { conv ->
        GlassDialog(
            onDismissRequest = { menuTarget = null },
            title = conv.displayTitle,
            maxContentHeight = 280,
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MenuItem(s.renameSession) {
                        menuTarget = null
                        renameTarget = conv
                    }
                    MenuItem("导出对话") {
                        menuTarget = null
                        exportTarget = conv
                    }
                    MenuItem("多选") {
                        menuTarget = null
                        selecting = true
                        selectedIds.clear()
                        selectedIds.add(conv.id)
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
            buttons = { GlassDialogButton(s.cancel, onClick = { menuTarget = null }) },
        )
    }

    renameTarget?.let { conv ->
        var value by remember(conv.id) { mutableStateOf(conv.displayTitle) }
        GlassDialog(
            onDismissRequest = { renameTarget = null },
            title = s.renameSession,
            content = {
                TextRow(
                    label = "",
                    value = value,
                    onValueChange = { value = it },
                )
            },
            buttons = {
                GlassDialogButton(s.cancel, onClick = { renameTarget = null })
                GlassDialogButton(s.save, primary = true) {
                    val title = value.trim().ifBlank { "新对话" }
                    scope.launch {
                        container.conversationStore.rename(conv.id, title)
                        container.engine.refreshConversation()
                        reloadKey++
                    }
                    renameTarget = null
                }
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

/**
 * 一条会话 + 点击行为。
 *
 * 抽出来是因为分组之后同一个渲染要出现两次（不分组 / 分组内）。
 * 之前是内联在 `items {}` 里的，一分组就得复制一份完整的点击逻辑，
 * 两份迟早会分叉。
 */
@Composable
private fun ConversationItem(
    conv: Conversation,
    selecting: Boolean,
    selectedIds: MutableList<String>,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
    nav: WatchNavigator,
    filtered: Boolean,
    onMore: (Conversation) -> Unit,
) {
    ConversationRow(
        conversation = conv,
        selecting = selecting,
        selected = conv.id in selectedIds,
        onClick = {
            if (selecting) {
                if (!selectedIds.remove(conv.id)) selectedIds.add(conv.id)
            } else {
                scope.launch {
                    container.engine.open(conv.id)
                    /*
                     * 打开之后回到哪个页面，由会话自己的场景决定，
                     * 而不是由「从哪进来」决定 —— 在全局列表里点开一个
                     * 酒馆会话，以前会跳进编程对话页，那里没有角色名、
                     * 也没有记忆入口，看起来像内容丢了。
                     */
                    if (filtered ||
                        (conv.scene.isNotBlank() && conv.scene != Scenes.CODING)
                    ) {
                        nav.pop()
                        nav.push(Screen.RoleplayChat)
                    } else {
                        nav.toChat()
                    }
                }
            }
        },
        onMore = { onMore(conv) },
    )
}

/** 分组标题。一行小字，左边模式（· 角色），右边条数 */
@Composable
private fun GroupLabel(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onMore: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(26.dp),
                cornerRadius = 26.dp,
                onClick = withHaptic(onClick),
            )
            .padding(start = 14.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            androidx.compose.material3.Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
        }
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
        if (!selecting) {
            Box {
                IconTap(Icons.Filled.MoreVert, s.more, onClick = onMore)
            }
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
internal fun WorkDirDialog(
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

    GlassDialog(
        onDismissRequest = onDismiss,
        maxContentHeight = 300,
        title = s.sessionWorkDir,
        // 内容自带 verticalScroll，这里不能再套一层
        scrollable = false,
        content = {
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
        buttons = {
            GlassDialogButton(s.cancel, onClick = onDismiss)
            GlassDialogButton(s.save, primary = true) { onPicked(selected) }
        },
    )
}

@Composable
private fun DirRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(10.dp),
                cornerRadius = 10.dp,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                onClick = withHaptic(onClick),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
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

// ---------------- 导出 / 导入 ----------------

/**
 * 导出对话框。
 *
 * ## 为什么是「三种去向」而不是一个「导出」按钮
 *
 * 手表上没有通用的「保存到…」：剪贴板能直接粘到别处，txt 能落在工作空间里
 * 被文件管理器 / 数据线取走，分享是把文件递给手机。用户想要哪一样，
 * 取决于他接下来要干什么，猜不如让他选 —— 三条都是一行，一屏放得下。
 *
 * 文本在打开弹窗时才读盘：会话列表只缓存了 index，正文要单独 load。
 */
@Composable
internal fun ExportDialog(
    container: AppContainer,
    conversation: Conversation,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var body by remember(conversation.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(conversation.id) {
        val messages = container.conversationStore.load(conversation.id)?.messages.orEmpty()
        body = ChatTranscript.encode(messages)
    }

    // 摘要要重新解析一遍，别放在 content 里 —— 那里每次重组都会再跑一次
    val summary = remember(body) {
        body?.let { ChatTranscript.summary(ChatTranscript.parse(it)) }
    }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = "导出对话",
        maxContentHeight = 260,
        content = {
            val text = body
            when {
                text == null -> HintText("正在读取…")
                text.isBlank() -> HintText("这个会话还没有可导出的内容")
                else -> {
                    HintText(summary.orEmpty())
                    Spacer(Modifier.height(6.dp))
                    // 预览只给前几行：格式对不对，看一眼分隔线就够了
                    Text(
                        text = text.lineSequence().take(5).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassDialogRow(
                        title = "复制到剪贴板",
                        subtitle = "整段文本，粘到笔记 / 聊天里",
                        onClick = {
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制 ${text.length} 字", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                    )
                    GlassDialogRow(
                        title = "存为 txt 文件",
                        subtitle = "落在工作空间 exports/ 下",
                        onClick = {
                            scope.launch {
                                val f = withContext(Dispatchers.IO) {
                                    writeTranscriptFile(container, conversation, text)
                                }
                                Toast.makeText(
                                    context,
                                    if (f != null) "已存到 ${f.name}" else "保存失败：工作空间不可写",
                                    Toast.LENGTH_LONG,
                                ).show()
                                onDismiss()
                            }
                        },
                    )
                    GlassDialogRow(
                        title = "分享文件",
                        subtitle = "通过系统分享发到手机",
                        onClick = {
                            scope.launch {
                                val f = withContext(Dispatchers.IO) {
                                    writeTranscriptFile(container, conversation, text)
                                }
                                val result = shareFile(context, f)
                                if (result is ShareResult.Fail) {
                                    Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                                }
                                onDismiss()
                            }
                        },
                    )
                }
            }
        },
        buttons = { GlassDialogButton("关闭", onClick = onDismiss) },
    )
}

/**
 * 导入对话框 —— 读一个 **txt 文件**。
 *
 * ## 为什么不是「粘贴一段文本」
 *
 * 导出的是文件，导入就该收文件；在手表上把几千字粘进输入框既不现实，
 * 也没法一次带进一整篇稿子。所以主路径是选文件，粘贴只作为兜底留在下面
 * （临时想塞一段设定、或者手上没有文件管理器时用）。
 *
 * ## 两条取文件的通道
 *
 * 1. **系统文件选择器**（SAF）。不用任何存储权限，用户选哪个就只给哪个，
 *    手机 / 手表上「下载」目录里的 txt 都能拿到。
 * 2. **工作空间里的 txt**。这条更重要 —— 手表上很多机型没有 DocumentsUI，
 *    选择器根本唤不起来；而工作空间是我们自己的目录，一定能读，
 *    自己导出的会话文件也正好躺在 `exports/` 下，导出去再导回来就是一次往返验证。
 *
 * 选完立刻解析并显示条数：手表上改字很难，与其导完才发现格式不对，
 * 不如在按下「导入」之前就让人看见识别到了几条。
 */
@Composable
internal fun ImportDialog(
    container: AppContainer,
    scene: String,
    cardId: String,
    onImported: (id: String, scene: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }

    val turns = remember(text) { if (text.isBlank()) emptyList() else ChatTranscript.parse(text) }

    fun take(name: String, raw: String) {
        source = name
        text = raw
        notice = ""
    }

    // 工作空间里的 txt：按修改时间倒序，最近导出的排最上面
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            runCatching {
                container.workspaceStore.listFilesRecursive()
                    .filter { !it.isDir && TranscriptFile.looksLikeText(it.path) }
                    .sortedByDescending { it.updatedAt }
                    .take(TranscriptFile.MAX_LISTED)
                    .map { it.path to it.size }
            }.getOrDefault(emptyList())
        }
        scanned = true
    }

    fun loadWorkspace(path: String) {
        scope.launch {
            busy = true
            val raw = withContext(Dispatchers.IO) {
                container.workspaceStore.resolveForUi(path)?.let { TranscriptFile.read(it) }
            }
            busy = false
            if (raw == null) {
                notice = "读不了这个文件：超过 4MB，或者内容已损坏"
            } else {
                take(path.substringAfterLast('/'), raw)
            }
        }
    }

    /*
     * 挑 txt。
     *
     * 走 rememberFilePicking：设置里开着「用内置文件浏览器」就在应用内挑，
     * 关着才唤起系统那套（唤起失败会自动退回内置，不会崩）。
     * 读文件统一走 UriStreams —— 内置给的是 file://，直接开流比经
     * ContentResolver 稳。
     */
    val picking = rememberFilePicking()

    fun loadFrom(uri: Uri) {
        scope.launch {
            busy = true
            val loaded = withContext(Dispatchers.IO) {
                val raw = runCatching {
                    UriStreams.open(context, uri)?.let { TranscriptFile.read(it) }
                }.getOrNull()
                if (raw == null) null else
                    (UriStreams.displayName(context, uri) ?: "选中的文件") to raw
            }
            busy = false
            if (loaded == null) {
                notice = "读不了这个文件：超过 4MB，或者没有读取权限"
            } else {
                take(loaded.first, loaded.second)
            }
        }
    }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = "导入 TXT",
        maxContentHeight = 300,
        // 内容自己滚（下面有 wearVerticalScroll），这一层再滚会拿到无限高
        scrollable = false,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .wearVerticalScroll(rememberScrollState()),
            ) {
                HintText("选一个按导出格式整理的 txt，或者任意一段文字都可以导进来。")
                Spacer(Modifier.height(6.dp))

                GlassDialogRow(
                    title = if (busy) "正在读取…" else "选择 TXT 文件",
                    subtitle = "从系统文件里挑",
                    onClick = { if (!busy) picking.pick(mime = "*/*") { loadFrom(it) } },
                )

                if (files.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "工作空间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    files.forEach { (path, size) ->
                        val name = path.substringAfterLast('/')
                        GlassDialogRow(
                            title = name,
                            subtitle = humanSize(size),
                            selected = source == name,
                            onClick = { if (!busy) loadWorkspace(path) },
                        )
                    }
                } else if (scanned) {
                    Text(
                        "工作空间里还没有 txt；导出的会话会出现在这里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }

                Spacer(Modifier.height(4.dp))
                GlassDialogRow(
                    title = "粘贴文本",
                    subtitle = if (pasting) "点这里收起" else "没有文件时直接贴一段",
                    onClick = { pasting = !pasting },
                )

                if (pasting) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            source = ""
                            notice = ""
                        },
                        placeholder = {
                            Text(
                                "#===========================================#\n[USER]:\n……",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        minLines = 4,
                        maxLines = 8,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(6.dp))
                when {
                    notice.isNotBlank() -> Text(
                        notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    source.isNotBlank() && turns.isEmpty() -> Text(
                        "$source 里没识别到内容",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    source.isNotBlank() -> Text(
                        "$source · ${ChatTranscript.summary(turns)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    text.isNotBlank() -> Text(
                        "识别到 ${turns.size} 条 · ${ChatTranscript.titleOf(turns)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        buttons = {
            GlassDialogButton("取消", onClick = onDismiss)
            GlassDialogButton("导入", primary = true) {
                if (turns.isEmpty() || busy) return@GlassDialogButton
                scope.launch {
                    val id = createImportedConversation(container, turns, scene, cardId)
                    if (id != null) onImported(id, scene)
                }
            }
        },
    )
}

/** 文件大小，一行内说清楚就够 */
private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * 把解析出来的段落写成一条新会话，返回会话 id。
 *
 * 直接落盘再 `engine.open()`，而不是走 `engine.send()`：导入的内容是**历史**，
 * 发出去会触发一次真实的模型请求，白花钱还会给对话追加一条不该有的回复。
 * 存盘是最小动作 —— 引擎打开后看到的就是现成的记录。
 */
private suspend fun createImportedConversation(
    container: AppContainer,
    turns: List<ChatTranscript.Turn>,
    scene: String,
    cardId: String,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val provider = container.settings.value.activeProvider
        val conv = ConversationStore.newConversation(
            providerId = provider?.id.orEmpty(),
            model = provider?.defaultModel.orEmpty(),
            presetId = "",
            title = ChatTranscript.titleOf(turns),
        ).copy(scene = scene, roleCardId = cardId)

        val messages = turns.map { t ->
            ConversationStore.newMessage(
                role = t.role,
                content = t.text,
                model = provider?.defaultModel.orEmpty(),
                providerId = provider?.id.orEmpty(),
            )
        }
        container.conversationStore.save(StoredConversation(meta = conv, messages = messages))
        conv.id
    }.onFailure { com.dshwatch.core.Logx.e("Import", "导入会话失败", it) }.getOrNull()
}

/**
 * 导出文件写在 `工作空间/exports/` 下。
 *
 * 放工作空间而不是缓存：缓存会被系统清掉，而用户导出的东西是**要留住**的；
 * 工作空间页能列出来、能分享、能通过数据线取走。
 */
private fun writeTranscriptFile(
    container: AppContainer,
    conversation: Conversation,
    text: String,
): java.io.File? = runCatching {
    val dir = java.io.File(container.workspaceStore.rootDir(), "exports").apply { mkdirs() }
    val stamp = java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US)
        .format(java.util.Date())
    val file = java.io.File(dir, "${safeFileName(conversation.displayTitle)}-$stamp.txt")
    file.writeText(text)
    file
}.onFailure { com.dshwatch.core.Logx.e("Export", "导出文件写入失败", it) }.getOrNull()

/** 文件名去掉路径分隔符与控制字符，再截到 20 字 —— 标题是用户随手起的，什么都可能有 */
private fun safeFileName(raw: String): String {
    val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim()
    val short = if (cleaned.length > 20) cleaned.take(20) else cleaned
    return short.ifBlank { "对话" }
}

/**
 * 多选时的操作条。
 *
 * 放在列表**最上面**而不是底部：手表滚动位置常常停在中间，,
 * 底部的浮动条会盖住最后几条；而这一段跟列表一起滚，位置可预期。
 */
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "已选 $count / $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onSelectAll) {
                Text(
                    text = if (count == total) "取消全选" else "全选",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WatchGhostButton(
                text = "置顶",
                enabled = count > 0,
                modifier = Modifier.weight(1f),
                onClick = onPin,
            )
            WatchGhostButton(
                text = "删除",
                enabled = count > 0,
                modifier = Modifier.weight(1f),
                onClick = onDelete,
            )
        }
    }
}
