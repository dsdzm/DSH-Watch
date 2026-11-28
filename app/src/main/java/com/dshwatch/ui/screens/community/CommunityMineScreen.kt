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
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.HintText
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
 * 我的投稿。
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

    LaunchedEffect(cacheKey, depth, account.signedIn) { load(force = false) }

    WatchScaffold(
        title = "我的投稿",
        onBack = { nav.pop() },
        subtitle = if (account.signedIn) account.username else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!account.signedIn) {
                EmptyState(title = "先登录", hint = "投稿需要账号，这样才知道卡是谁写的")
                WatchButton(text = "去登录", onClick = { nav.push(Screen.CommunityAuth) })
                return@Column
            }

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
                    scope.launch { load(force = true) }
                }
            }
        }
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
