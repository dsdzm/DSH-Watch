package com.dshwatch.ui.screens.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.community.CachedPage
import com.dshwatch.data.community.CommunityApi
import com.dshwatch.data.community.CommunityCache
import com.dshwatch.data.community.CommunityCard
import com.dshwatch.data.community.CommunityException
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.IconTap
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
 * 社区：别人投稿、通过审核的角色卡 / 写作设定。
 *
 * ## 为什么在酒馆下面而不是设置里
 *
 * 用户"想找个新角色聊聊"的时候人在酒馆。放到设置里，等于让他先想到
 * 「有没有社区」再去翻 —— 大多数人是不会翻的。
 *
 * ## 分页为什么是手动「加载更多」
 *
 * 手表上滚动到底再自动拉下一页，很难判断用户是真的到底了还是手指抖了一下；
 * 而且社区主机在境外，一次多拉 20 条比来回三次更省时间。给一颗明确的按钮，
 * 用户知道自己在等什么。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommunityScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    val account by container.communityAccount.state.collectAsStateWithLifecycle()

    /** null = 全部；否则是 tavern / writing */
    var kind by remember { mutableStateOf<String?>(null) }
    var hot by remember { mutableStateOf(false) }

    val sort = if (hot) CommunityApi.SORT_HOT else CommunityApi.SORT_NEW
    val key = CommunityCache.listKey(kind, sort)

    /*
     * 每个筛选组合各自一套状态，初值来自缓存。
     *
     * `remember(key)`：切到「写作」再切回「全部」，回到的是「全部」上次那一份，
     * 而不是从空列表重新拉。内存里的缓存读是 O(1)，所以这一帧就是完整列表，
     * 中间不会闪一下"正在取卡"。
     */
    val cached = remember(key) { container.communityCache.page(key) }
    var items by remember(key) { mutableStateOf(cached?.items.orEmpty()) }
    var hasMore by remember(key) { mutableStateOf(cached?.hasMore ?: false) }
    /** 已加载到第几页（0 起）；-1 表示一页都没拉过 */
    var pages by remember(key) { mutableStateOf(cached?.pages ?: -1) }
    var loading by remember(key) { mutableStateOf(false) }
    var loadedOnce by remember(key) { mutableStateOf(cached != null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var updatedAt by remember(key) { mutableStateOf(cached?.at ?: 0L) }

    /*
     * 请求代次。
     *
     * 以前这里是「`if (loading) return` 丢掉并发请求」，后果很具体：
     * 列表还在加载时用户点了「写作」筛选 —— 第二次请求被丢掉，
     * 屏幕上留下的却是上一次「全部」的结果，而 Chip 已经显示"写作"选中。
     * 现在每次请求都发，回来时比对代次，过期的直接扔。
     */
    var generation by remember { mutableIntStateOf(0) }

    /**
     * @param silent 后台刷新：不显示"正在取卡"、失败也不弹红字（屏幕上已有内容，
     *   把用户好不容易看到的东西换成错误提示没有意义）
     */
    suspend fun load(reset: Boolean, silent: Boolean = false) {
        val mine = ++generation
        if (!silent) loading = true
        if (reset) error = null
        val next = if (reset) 0 else pages + 1
        val result = runCatching {
            container.communityApi.list(
                page = next,
                kind = kind,
                sort = sort,
            )
        }
        // 过期响应：已经有更新的请求在路上（或已经回来），这一份的 items 是旧筛选的
        if (mine != generation) return
        result.onSuccess { p ->
            val merged = if (reset) p.items else items + p.items
            items = merged
            hasMore = p.hasMore
            pages = next
            updatedAt = System.currentTimeMillis()
            // 落盘：下次冷启动、或者用户切回这个筛选，直接用这份
            container.communityCache.putPage(
                key,
                CachedPage(at = updatedAt, hasMore = p.hasMore, items = merged, pages = next),
            )
        }.onFailure { e ->
            if (!silent || items.isEmpty()) error = e.message ?: "加载失败"
        }
        // 首次失败也要把 loadedOnce 立起来，否则界面永远停在"加载中"
        loadedOnce = true
        loading = false
    }

    LaunchedEffect(key) {
        // 缓存还新鲜就一个请求都不发 —— 社区审核是人工的，五分钟内不会有新卡
        val fresh = cached != null && container.communityCache.isFresh(cached.at, CommunityCache.LIST_TTL)
        if (fresh) return@LaunchedEffect
        // 有过期缓存：先让屏幕上的旧内容留着，后台悄悄换新的
        load(reset = true, silent = cached != null)
    }

    WatchScaffold(
        title = "社区",
        onBack = { nav.pop() },
        subtitle = if (account.signedIn) account.username else null,
        actions = {
            IconTap(Icons.Outlined.CloudUpload, "我的投稿") {
                if (account.signedIn) nav.push(Screen.CommunityMine) else nav.push(Screen.CommunityAuth)
            }
            IconTap(Icons.Outlined.AccountCircle, "社区账号") {
                nav.push(Screen.CommunityAuth)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /*
             * 筛选条用 FlowRow 而不是 Row。
             *
             * 圆屏顶部可用宽度只有一百七十多 dp，四个 Chip 加上间距刚好在边界上，
             * 换一套字号 / 换 M3E 主题就会把最后一个裁掉一半。让它自己换行，
             * 多占的那一行高度比"看不见最热按钮"划算得多。
             */
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("全部", selected = kind == null) { kind = null }
                Chip("酒馆", selected = kind == Scenes.TAVERN) { kind = Scenes.TAVERN }
                Chip("写作", selected = kind == Scenes.WRITING) { kind = Scenes.WRITING }
                Chip(if (hot) "最热" else "最新", selected = hot) { hot = !hot }
            }

            error?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                WatchGhostButton(text = "重试", modifier = Modifier.fillMaxWidth()) {
                    scope.launch { load(reset = true) }
                }
            }

            if (loading && items.isEmpty()) {
                HintText("正在从社区取卡…")
            }
            if (loadedOnce && items.isEmpty() && error == null) {
                EmptyState(
                    title = "社区还没有卡",
                    hint = "你可以在角色卡的编辑页把它分享上去",
                )
            }

            items.forEach { card ->
                CommunityRow(card = card, onClick = {
                    nav.push(Screen.CommunityCard(card.id))
                })
            }

            if (hasMore) {
                WatchGhostButton(
                    text = if (loading) "加载中…" else "加载更多",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                ) { scope.launch { load(reset = false) } }
            }
            if (items.isNotEmpty()) {
                HintText("点一张卡看全文，喜欢就导入到自己的酒馆")
                // 缓存是"先给你看，再偷偷换"，得让用户知道屏幕上这份是什么时候的，
                // 否则会以为社区就这几张卡。要最新的有下面这颗按钮。
                Text(
                    text = "上次更新 · " + relativeTime(updatedAt / 1000).ifBlank { "刚刚" },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                WatchGhostButton(
                    text = if (loading) "正在刷新…" else "刷新列表",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                ) { scope.launch { load(reset = true) } }
            }
        }
    }
}

@Composable
private fun CommunityRow(card: CommunityCard, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(20.dp), cornerRadius = 20.dp)
            .tap(onClick = withHaptic(onClick))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = card.name.take(1),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.name.ifBlank { "未命名" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.tagline.isNotBlank()) {
                    Text(
                        text = card.tagline,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = listOf(
                if (card.kind == Scenes.WRITING) "写作" else "酒馆",
                "@" + card.author.ifBlank { "匿名" },
                "↓" + card.downloads,
                "♥" + card.likes,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
    }
}

/**
 * 相对时间。
 *
 * 不直接显示日期：社区里最新的卡几乎都是"这两天"，写"3 小时前"比
 * "2026-11-28 09:12"更好判断新旧；超过一周才退回日期。
 */
internal fun relativeTime(seconds: Long): String {
    if (seconds <= 0) return ""
    val now = System.currentTimeMillis() / 1000
    val d = now - seconds
    return when {
        d < 60 -> "刚刚"
        d < 3600 -> "${d / 60} 分钟前"
        d < 86400 -> "${d / 3600} 小时前"
        d < 86400 * 7 -> "${d / 86400} 天前"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = seconds * 1000 }
            val y = cal.get(java.util.Calendar.YEAR)
            val m = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            if (y == thisYear) "$m 月 $day 日" else "$y-$m-$day"
        }
    }
}

/** 统一把异常翻成一句话；服务端的错误本来就是给用户看的，原样透传 */
internal fun describeError(e: Throwable): String =
    (e as? CommunityException)?.message ?: (e.message ?: "出错了，稍后再试")
