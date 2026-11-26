package com.dshwatch.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.model.Conversation
import java.util.Calendar

/**
 * 抽屉底部额外的一项。
 *
 * 目前只有养鱼用它放「记忆」：那一页的顶栏收成了和编程页一样的三段，
 * 原来挂在顶栏上的记忆入口需要有个新家 —— 放进抽屉底部，和「切换模式 / 全部记录」
 * 同一层级，用得到的时候才出现。
 */
data class PhoneDrawerExtra(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * 左侧历史记录抽屉。
 *
 * ## 为什么把原来那张底部菜单换成抽屉
 *
 * 1.24.0 的 `☰` 开的是一张底部弹窗：新建 / 切换模式 / 翻记录 / 上下文 / 供应商 /
 * 设置 / 帮助。问题是这个按钮的位置（左上角）在所有聊天应用里都叫「历史记录」——
 * 用户点它是想找上次那段对话，不是想再看一遍设置入口。菜单越堆越长，最常做的
 * 那件事反而藏在二级页里。
 *
 * 所以这里按聊天应用的通行形态重做：**一列会话，按时间分组，顶上搜索**。
 * 设置、切换模式这类低频动作收缩到最底部一行 —— 它们不是没有，是不该占主位。
 *
 * ## 为什么操作藏在长按里
 *
 * 每一行右侧都挂一个 `⋯` 会把「标题」压到两百来 dp，长标题全变省略号。
 * 而重命名 / 置顶 / 删除 / 看这条用了多少 token 都是**偶发动作**，
 * 长按的发现成本低于每行常驻一个图标 —— 这也和主流聊天应用一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhoneHistoryDrawer(
    conversations: List<Conversation>,
    currentId: String?,
    sceneLabel: String,
    modelLabel: String,
    onOpen: (Conversation) -> Unit,
    /** 新建一段。**养鱼传 null**：那是一条鱼的一辈子，多开一段就断了记忆 */
    onNewChat: (() -> Unit)? = null,
    onPickScene: () -> Unit,
    onSettings: () -> Unit,
    /** 去记录页整理（多选在那里做，抽屉里放不下两个模式） */
    onManage: () -> Unit,
    onRename: (Conversation) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onAbout: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    /** 某个模式专属的一项（养鱼放「记忆」）；null 时这一行不出现 */
    extra: PhoneDrawerExtra? = null,
    /** 同 [extra]，但可以有多个（养鱼：动态 + 记忆） */
    extras: List<PhoneDrawerExtra> = emptyList(),
) {
    var query by remember { mutableStateOf("") }

    val shown = remember(conversations, query) {
        val q = query.trim().lowercase()
        conversations
            .filter { query.isBlank() || it.displayTitle.lowercase().contains(q) || it.model.lowercase().contains(q) }
            .sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })
    }

    /*
     * 分组键一次算好，再 fold 成分段列表。
     *
     * 不用 `groupBy`：它给的是 Map，顺序由 LinkedHashMap 保证但语义上仍是哈希表 ——
     * 而这里的分段顺序（置顶 → 今天 → 昨天 → 7 天内 → 更早）是**业务顺序**，
     * 必须显式排出来，不能指望插入序。
     */
    val buckets = remember(shown) {
        val order = listOf("置顶", "今天", "昨天", "7 天内", "30 天内", "更早")
        val map = LinkedHashMap<String, MutableList<Conversation>>()
        shown.forEach { c ->
            val key = if (c.pinned) "置顶" else bucketOf(c.updatedAt)
            map.getOrPut(key) { mutableListOf() }.add(c)
        }
        order.filter { map.containsKey(it) }.map { it to map.getValue(it) }
    }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        modifier = Modifier.width(312.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Spacer(Modifier.height(12.dp))

            // 搜索：胶囊输入框，只留放大镜。会话内容没落索引，按标题和模型找
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                placeholder = {
                    Text(
                        text = "搜索对话内容…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            Spacer(Modifier.height(8.dp))

            if (onNewChat != null) {
                DrawerAction(
                    icon = Icons.Filled.Add,
                    title = "新对话",
                    onClick = onNewChat,
                )
            }
            DrawerAction(
                icon = Icons.Outlined.PushPin,
                title = sceneLabel,
                trailing = "切换 ›",
                onClick = onPickScene,
            )
            DrawerAction(
                icon = Icons.Outlined.ManageSearch,
                title = "全部记录",
                trailing = "整理 ›",
                onClick = onManage,
            )
            extras.forEach { item ->
                DrawerAction(
                    icon = item.icon,
                    title = item.title,
                    trailing = "›",
                    onClick = item.onClick,
                )
            }
            if (extra != null) {
                DrawerAction(
                    icon = extra.icon,
                    title = extra.title,
                    trailing = "›",
                    onClick = extra.onClick,
                )
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            if (buckets.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (query.isBlank()) "还没有对话" else "没有匹配的对话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    buckets.forEach { (label, list) ->
                        item(key = "h-$label") { BucketHeader(label) }
                        items(list, key = { it.id }) { conv ->
                            ConversationRow(
                                conversation = conv,
                                active = conv.id == currentId,
                                onClick = { onOpen(conv) },
                                onRename = { onRename(conv) },
                                onTogglePin = { onTogglePin(conv) },
                                onAbout = { onAbout(conv) },
                                onDelete = { onDelete(conv) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    onClick = onSettings,
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BucketHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    active: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onAbout: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (active) scheme.secondaryContainer else Color.Transparent,
                    RoundedCornerShape(14.dp),
                )
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) scheme.onSecondaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(if (conversation.pinned) "取消置顶" else "置顶") },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = { menu = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text("关于对话") },
                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                onClick = { menu = false; onAbout() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("删除", color = scheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = scheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun DrawerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    trailing: String = "",
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一条会话落在哪个时间段。
 *
 * 用 Calendar 而不是「24 小时 / 48 小时」这类算差值：用户说的「昨天」是
 * **自然日**。凌晨 1 点看 2 小时前（前一天 23 点）的会话，按差值会归到「今天」，
 * 而它明明是昨天的事。
 */
private fun bucketOf(ts: Long): String {
    if (ts <= 0L) return "更早"
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val today = now.clone() as Calendar
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    return when {
        sameDay(then, today) -> "今天"
        sameDay(then, yesterday) -> "昨天"
        now.timeInMillis - ts < 7L * 24 * 3600_000L -> "7 天内"
        now.timeInMillis - ts < 30L * 24 * 3600_000L -> "30 天内"
        else -> "更早"
    }
}
