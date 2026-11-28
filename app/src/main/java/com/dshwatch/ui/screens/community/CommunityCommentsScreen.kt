package com.dshwatch.ui.screens.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.community.CommunityComment
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 评论区。
 *
 * ## 为什么单独一页而不是塞在详情页底部
 *
 * 详情页已经有设定、开场、世界书一大串，评论再挤进去，用户要滚很久才能
 * 看到「导入」。这里进来就只有两件事：看别人说什么、说一句。
 *
 * ## 为什么不缓存
 *
 * 评论是这张页面上唯一"随时会变"的东西，缓存反而会让人以为自己发的
 * 没发出去。列表页/详情页那种几十分钟不变的数据才值得缓存。
 */
@Composable
fun CommunityCommentsScreen(
    container: AppContainer,
    nav: WatchNavigator,
    cardId: Long,
) {
    val scope = rememberCoroutineScope()
    val account by container.communityAccount.state.collectAsStateWithLifecycle()

    var items by remember { mutableStateOf<List<CommunityComment>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        if (reset) error = null
        val next = if (reset) 0 else page + 1
        runCatching { container.communityApi.comments(cardId, page = next) }
            .onSuccess { p ->
                items = if (reset) p.items else items + p.items
                hasMore = p.hasMore
                page = next
            }
            .onFailure { error = describeError(it) }
        loadedOnce = true
        loading = false
    }

    LaunchedEffect(cardId) { load(reset = true) }

    fun send() {
        val body = draft.trim()
        if (body.isEmpty() || posting) return
        posting = true
        notice = null
        scope.launch {
            runCatching { container.communityApi.postComment(container.communityAccount.token, cardId, body) }
                .onSuccess { c ->
                    // 直接插到最前面：服务端也是最新在前，等一次刷新只会让人以为没发出去
                    items = listOf(c) + items
                    draft = ""
                    notice = "发出去了"
                }
                .onFailure { notice = describeError(it) }
            posting = false
        }
    }

    WatchScaffold(
        title = "评论",
        onBack = { nav.pop() },
        subtitle = if (items.isNotEmpty()) "${items.size}${if (hasMore) "+" else ""} 条" else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (account.signedIn) {
                TextRow(
                    label = "说点什么",
                    value = draft,
                    onValueChange = { if (it.length <= 300) draft = it },
                    placeholder = "最多 300 字",
                    singleLine = false,
                )
                WatchButton(
                    text = if (posting) "发送中…" else "发送",
                    enabled = !posting && draft.isNotBlank(),
                    onClick = { send() },
                )
                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it == "发出去了") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                HintText("登录后才能评论")
                WatchGhostButton(text = "去登录", modifier = Modifier.fillMaxWidth()) {
                    nav.push(Screen.CommunityAuth)
                }
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                WatchGhostButton(text = "重试", modifier = Modifier.fillMaxWidth()) {
                    scope.launch { load(reset = true) }
                }
            }

            if (loading && items.isEmpty()) {
                HintText("正在取评论…")
            }
            if (loadedOnce && items.isEmpty() && error == null) {
                EmptyState(title = "还没有人评论", hint = "说第一句吧")
            }

            items.forEach { CommentRow(it) }

            if (hasMore) {
                WatchGhostButton(
                    text = if (loading) "加载中…" else "加载更多",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                ) { scope.launch { load(reset = false) } }
            }
            if (items.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                HintText("评论会公开显示，请别写联系方式")
            }
        }
    }
}

@Composable
private fun CommentRow(c: CommunityComment) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(16.dp), cornerRadius = 16.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "@" + c.author.ifBlank { "匿名" } + " · " + relativeTime(c.createdAt),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = c.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
