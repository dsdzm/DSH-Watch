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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.community.CachedPage
import com.dshwatch.data.community.CommunityCache
import com.dshwatch.data.community.CommunityCard
import com.dshwatch.data.community.CommunityNotice
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 个人中心：消息 + 我的投稿。
 *
 * ## 为什么把「消息」放在这一页的最上面
 *
 * 「有人赞了你的评论」是一件**只有别人主动看你的卡时才会发生**的事，
 * 用户没有理由每天来刷。放在这里，进来第一眼就能看到有没有新消息；
 * 未读用颜色区分，点一下就算已读，不需要"消息中心"这种第二层结构。
 *
 * ## 我的投稿
 *
 * 这一页回答的是「我交上去的卡现在怎么样了」—— 以前投稿之后在 App 里
 * 完全看不到进展，用户只能猜、或者去网页上看。审核状态和拒绝理由
 * 必须能在手表上直接读到，不然「被拒」就等于石沉大海。
 */
@Composable
fun CommunityMineScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    val account by container.communityAccount.state.collectAsStateWithLifecycle()

    /*
     * 消息列表不缓存：它本来就是"有没有新东西"的答案，缓存一份旧的是负价值。
     * 每次进这一页拉一次，数据量很小（最多 50 条、每条一行）。
     */
    var notices by remember { mutableStateOf<List<CommunityNotice>>(emptyList()) }
    var unread by remember { mutableStateOf(0) }
    var noticesLoaded by remember { mutableStateOf(false) }
    var noticesError by remember { mutableStateOf<String?>(null) }

    fun loadNotices() {
        if (!account.signedIn) {
            noticesLoaded = true
            return
        }
        scope.launch {
            runCatching { container.communityApi.notices(container.communityAccount.token) }
                .onSuccess { p ->
                    notices = p.items
                    unread = p.unread
                    noticesError = null
                }
                .onFailure { noticesError = describeError(it) }
            noticesLoaded = true
        }
    }

    /*
     * 和社区列表共用一套缓存，只是 key 前缀不同。
     *
     * 这一页以前是「depth 一变就重拉」—— 从详情页退回来也要转一次圈，
     * 而那次返回 99% 什么都没变。现在 60 秒内直接用缓存，要最新的点「刷新」。
     */
    val cacheKey = CommunityCache.mineKey(account.username)
    val cached = remember(cacheKey) { container.communityCache.page(cacheKey) }
    var items by remember(cacheKey) { mutableStateOf(cached?.items.orEmpty()) }
    var loading by remember(cacheKey) { mutableStateOf(false) }
    var loadedOnce by remember(cacheKey) { mutableStateOf(cached != null) }
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    val depth = nav.depth

    suspend fun load(force: Boolean) {
        if (!account.signedIn) {
            loadedOnce = true
            return
        }
        val c = container.communityCache.page(cacheKey)
        if (!force && c != null && container.communityCache.isFresh(c.at, CommunityCache.MINE_TTL)) {
            return
        }
        if (c == null && items.isEmpty()) loading = true
        runCatching { container.communityApi.mine(container.communityAccount.token) }
            .onSuccess { list ->
                items = list
                error = null
                container.communityCache.putPage(
                    cacheKey,
                    CachedPage(at = System.currentTimeMillis(), hasMore = false, items = list, pages = 0),
                )
            }
            .onFailure { e ->
                if (c == null && items.isEmpty()) error = describeError(e)
            }
        loadedOnce = true
        loading = false
    }

    LaunchedEffect(cacheKey, depth, account.signedIn) {
        load(force = false)
        loadNotices()
    }

    WatchScaffold(
        title = "个人中心",
        onBack = { nav.pop() },
        subtitle = if (account.signedIn) account.username else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!account.signedIn) {
                EmptyState(title = "先登录", hint = "投稿需要账号，这样才知道卡是谁写的")
                WatchButton(text = "去登录", onClick = { nav.push(Screen.CommunityAuth) })
                return@Column
            }

            // ---------------- 消息 ----------------
            SectionHeader(if (unread > 0) "消息 · $unread 条未读" else "消息")

            noticesError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (noticesLoaded && notices.isEmpty() && noticesError == null) {
                HintText("还没有消息。有人赞你的评论时会出现在这里")
            }

            notices.forEach { n ->
                NoticeRow(
                    notice = n,
                    onClick = {
                        scope.launch {
                            runCatching {
                                container.communityApi.readNotices(container.communityAccount.token, n.id)
                            }.onSuccess { left ->
                                unread = left
                                notices = notices.map {
                                    if (it.id == n.id) it.copy(read = true) else it
                                }
                            }
                        }
                        if (n.cardId > 0) nav.push(Screen.CommunityCard(n.cardId))
                    },
                )
            }

            if (unread > 0) {
                WatchGhostButton(text = "全部标为已读", modifier = Modifier.fillMaxWidth()) {
                    scope.launch {
                        runCatching {
                            container.communityApi.readNotices(container.communityAccount.token)
                        }.onSuccess {
                            unread = 0
                            notices = notices.map { it.copy(read = true) }
                        }
                    }
                }
            }

            // ---------------- 我的投稿 ----------------
            SectionHeader("我的投稿")

            if (loading && items.isEmpty()) HintText("正在取你的投稿…")

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                WatchGhostButton(text = "重试", modifier = Modifier.fillMaxWidth()) {
                    scope.launch { load(force = true) }
                }
            }

            if (loadedOnce && !loading && items.isEmpty() && error == null) {
                EmptyState(
                    title = "还没投过稿",
                    hint = "编辑一张角色卡，拉到最下面点「分享到社区」",
                )
            }

            items.forEach { card ->
                MineRow(
                    card = card,
                    // 只有已上线的能点开看：其余状态在社区接口里查不到详情（404），
                    // 点进去只会得到一句「这张卡还没通过审核」，不如不给点
                    onClick = if (card.status == CommunityCard.STATUS_APPROVED) {
                        { nav.push(Screen.CommunityCard(card.id)) }
                    } else {
                        null
                    },
                )
            }

            if (items.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                WatchGhostButton(text = "刷新", modifier = Modifier.fillMaxWidth()) {
                    scope.launch {
                        load(force = true)
                        loadNotices()
                    }
                }
            }
        }
    }
}

/** 一条消息：谁赞了你的哪条评论。未读用主题色标出来，点一下进那张卡 */
@Composable
private fun NoticeRow(notice: CommunityNotice, onClick: () -> Unit) {
    val color = if (notice.read) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(16.dp), cornerRadius = 16.dp)
            .tap(onClick = withHaptic(onClick))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = when (notice.kind) {
                "clike" -> "@" + notice.actor.ifBlank { "有人" } + " 赞了你的评论"
                else -> "@" + notice.actor.ifBlank { "有人" } + " 有新的互动"
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = color,
        )
        if (notice.body.isNotBlank()) {
            Text(
                text = "「" + notice.body + "」",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = (if (notice.read) "" else "● ") + relativeTime(notice.createdAt),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (notice.read) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MineRow(card: CommunityCard, onClick: (() -> Unit)?) {
    val statusColor = when (card.status) {
        CommunityCard.STATUS_APPROVED -> MaterialTheme.colorScheme.primary
        CommunityCard.STATUS_REJECTED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(18.dp), cornerRadius = 18.dp)
            .then(if (onClick != null) Modifier.tap(onClick = withHaptic(onClick)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = card.name.ifBlank { "未命名" },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOf(
                CommunityCard.statusLabel(card.status),
                if (card.kind == Scenes.WRITING) "写作" else "酒馆",
                relativeTime(card.createdAt),
            ).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = statusColor,
        )
        if (card.reason.isNotBlank()) {
            Text(
                text = "理由：" + card.reason,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
