package com.dshwatch.ui.screens.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.community.CachedDetail
import com.dshwatch.data.community.CommunityCache
import com.dshwatch.data.community.CommunityComment
import com.dshwatch.data.community.CommunityDetail
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogRow
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.TextRow
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
 * 社区卡片详情。
 *
 * ## 信息用卡片装，不用裸文本
 *
 * 一张卡的设定动辄几千字（人物背景 + 说话方式 + 开场 + 世界书），
 * 以前是一段接一段的正文，滚起来像在读一篇没有小标题的长文。
 * 现在每一块都是独立的玻璃卡片：标题在卡上，正文在卡里，
 * 超过 [COLLAPSED_LINES] 行自动折起来，点「展开全文」再看。
 * 用户第一眼看到的是"这张卡有哪几块"，而不是"哇好长"。
 *
 * ## 评论就在这一页
 *
 * 评论区默认展开、放在点赞按钮下面，往下滑自动续下一批 ——
 * 以前点「评论 N 条」会跳走一页，看完还得退回来才能导入。
 * 回复只有一层（和网页端、服务端一致）：楼中楼在手表上缩进两层就没宽度了。
 *
 * ## 点赞与评分只能点一次
 *
 * 点下去先本地生效（按钮立刻变灰、写进缓存），再发请求确认。
 * 这样断网时也不会出现"点了没反应，再点还是 5 星"；
 * 服务端拒绝（已经评过）时，以服务端的分值回写。
 */
@Composable
fun CommunityCardScreen(
    container: AppContainer,
    nav: WatchNavigator,
    id: Long,
) {
    val scope = rememberCoroutineScope()
    val account by container.communityAccount.state.collectAsStateWithLifecycle()

    val cachedDetail = remember(id) { container.communityCache.detail(id) }
    var detail by remember(id) {
        mutableStateOf(
            cachedDetail?.let {
                CommunityDetail(it.meta, it.card, it.myScore, it.liked, it.mine)
            }
        )
    }
    var loading by remember(id) { mutableStateOf(cachedDetail == null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rateBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var importedId by remember { mutableStateOf<String?>(null) }
    var importedKind by remember { mutableStateOf(Scenes.TAVERN) }
    var showReport by remember { mutableStateOf(false) }
    var reportOther by remember { mutableStateOf(false) }
    var reportText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    // ---------------- 评论 ----------------
    var comments by remember(id) { mutableStateOf<List<CommunityComment>>(emptyList()) }
    var commentsPage by remember(id) { mutableStateOf(-1) }
    var commentsHasMore by remember(id) { mutableStateOf(false) }
    var commentsLoading by remember(id) { mutableStateOf(false) }
    var commentsLoaded by remember(id) { mutableStateOf(false) }
    var commentsError by remember(id) { mutableStateOf<String?>(null) }
    var draft by remember(id) { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    /** 非空时表示正在回复这条主评论 */
    var replyTo by remember { mutableStateOf<CommunityComment?>(null) }

    val scroll = rememberScrollState()

    fun persist(d: CommunityDetail) {
        scope.launch {
            container.communityCache.putDetail(
                id,
                CachedDetail(
                    at = System.currentTimeMillis(),
                    meta = d.meta,
                    card = d.card,
                    myScore = d.myScore,
                    liked = d.liked,
                    mine = d.mine,
                ),
            )
        }
    }

    suspend fun fetch(force: Boolean) {
        val c = container.communityCache.detail(id)
        if (!force && c != null && container.communityCache.isFresh(c.at, CommunityCache.DETAIL_TTL)) {
            return
        }
        if (c == null) loading = true
        // 带 token：才知道我打了几分、赞没赞过、这张卡是不是我投的
        runCatching {
            container.communityApi.detail(
                id = id,
                token = container.communityAccount.token.takeIf { account.signedIn },
            )
        }.onSuccess { d ->
            detail = d
            persist(d)
            error = null
        }.onFailure { e ->
            // 有缓存就别用红字盖掉正文：用户至少还能看，还能导入
            if (c == null && detail == null) error = describeError(e)
        }
        loading = false
    }

    suspend fun loadComments(reset: Boolean) {
        if (commentsLoading) return
        if (!reset && !commentsHasMore) return
        commentsLoading = true
        if (reset) {
            commentsError = null
        }
        val next = if (reset) 0 else commentsPage + 1
        runCatching {
            container.communityApi.comments(
                cardId = id,
                page = next,
                token = container.communityAccount.token.takeIf { account.signedIn },
            )
        }.onSuccess { p ->
            comments = if (reset) p.items else comments + p.items
            commentsHasMore = p.hasMore
            commentsPage = next
            commentsLoaded = true
        }.onFailure { e ->
            if (comments.isEmpty()) commentsError = describeError(e)
            commentsLoaded = true
        }
        commentsLoading = false
    }

    LaunchedEffect(id) {
        fetch(force = false)
        loadComments(reset = true)
    }

    // 滑到底自动续一批：不用按钮，也不做无限预加载（只在真的到底时才发请求）
    LaunchedEffect(scroll.value, scroll.maxValue, commentsHasMore, commentsLoading) {
        if (commentsHasMore && !commentsLoading && scroll.maxValue > 0 &&
            scroll.value >= scroll.maxValue - 160
        ) {
            loadComments(reset = false)
        }
    }

    val card = detail?.card
    val meta = detail?.meta
    val myScore = detail?.myScore ?: 0
    val liked = detail?.liked == true

    fun import() {
        val d = detail ?: return
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            runCatching {
                // 重新拉一次（带 download=1）：一是让计数落在"真的导入"这个动作上，
                // 二是避免用户开着详情页很久、作者中途改过稿，导进来的却是旧内容
                val fresh = container.communityApi.detail(id, download = true)
                val name = fresh.card.displayName
                // 同名卡已存在就加个后缀：`RoleCardStore.save` 按 id 存，
                // 允许同名，但列表里两张一模一样的卡会让人分不清哪个是刚导的
                val taken = runCatching { container.roleCardStore.index(fresh.card.kind) }
                    .getOrDefault(emptyList())
                    .any { it.name == name }
                container.roleCardStore.save(
                    fresh.card.copy(
                        id = "",
                        name = if (taken) "$name（社区）" else name,
                        builtin = false,
                        createdAt = 0L,
                    )
                )
            }.onSuccess { saved ->
                importedId = saved.id
                importedKind = saved.kind
                notice = "已导入：「${saved.displayName}」"
                // 服务端下载数已经 +1，本地缓存也跟着 +1：不然退回社区列表
                // 看到还是旧数字，像是刚才那次导入没算上
                container.communityCache.patchDetail(id) { it.copy(downloads = it.downloads + 1) }
            }.onFailure { e ->
                notice = describeError(e)
            }
            busy = false
        }
    }

    /**
     * 评分：本地先落，再等服务端。
     *
     * 本地这一步不是"乐观更新"的装饰 —— 用户点完就该看到星星亮起、按钮变灰。
     * 请求失败也不回滚（用户确实表达了意图），只在下面提示"已记在本机"。
     * 服务端说"已经评过"时以服务端为准，避免本地和服务端长期不一致。
     */
    fun rate(score: Int) {
        val d = detail ?: return
        if (!account.signedIn) {
            notice = "登录后才能评分"
            return
        }
        if (d.myScore > 0) {
            notice = "已评过 ${d.myScore} 星，评分不能修改"
            return
        }
        if (rateBusy) return
        rateBusy = true
        notice = null
        val optimistic = d.copy(myScore = score)
        detail = optimistic
        persist(optimistic)
        scope.launch {
            runCatching { container.communityApi.rate(container.communityAccount.token, id, score) }
                .onSuccess { r ->
                    val next = detail?.copy(
                        meta = (detail?.meta ?: d.meta).copy(rating = r.rating, ratingCount = r.count),
                        myScore = r.myScore,
                    ) ?: return@onSuccess
                    detail = next
                    persist(next)
                    notice = "已评 $score 星，不能修改"
                }
                .onFailure { e ->
                    val msg = e.message.orEmpty()
                    if (msg.contains("已经打过")) {
                        // 服务端已经有分：拉一次详情，以服务端为准
                        runCatching { container.communityApi.detail(id, token = container.communityAccount.token) }
                            .onSuccess { d2 -> detail = d2; persist(d2) }
                        notice = "你已经评过分了，评分不能修改"
                    } else {
                        notice = describeError(e) + "（评分已记在本机）"
                    }
                }
            rateBusy = false
        }
    }

    fun like() {
        val d = detail ?: return
        if (!account.signedIn) {
            notice = "登录后才能点赞"
            return
        }
        if (d.liked) {
            notice = "已经点过赞了"
            return
        }
        val optimistic = d.copy(liked = true, meta = d.meta.copy(likes = d.meta.likes + 1))
        detail = optimistic
        persist(optimistic)
        notice = "已点赞"
        scope.launch {
            runCatching { container.communityApi.like(container.communityAccount.token, id) }
                .onSuccess { likes ->
                    val next = detail?.copy(meta = (detail?.meta ?: d.meta).copy(likes = likes))
                    if (next != null) {
                        detail = next
                        persist(next)
                    }
                }
                .onFailure {
                    // 网络失败留着本地状态：服务端下次读详情时会给出真值
                    notice = "点赞已记在本机，联网后会自动同步"
                }
        }
    }

    fun toggleCommentLike(c: CommunityComment) {
        if (!account.signedIn) {
            notice = "登录后才能点赞"
            return
        }
        if (c.liked) return
        comments = bumpLike(comments, c.id)
        scope.launch {
            runCatching { container.communityApi.likeComment(container.communityAccount.token, c.id) }
                .onSuccess { likes -> comments = setLikes(comments, c.id, likes) }
                .onFailure { notice = describeError(it) }
        }
    }

    fun sendComment() {
        val body = draft.trim()
        if (body.isEmpty() || posting) return
        if (!account.signedIn) {
            notice = "登录后才能评论"
            nav.push(Screen.CommunityAuth)
            return
        }
        val parent = replyTo
        posting = true
        notice = null
        scope.launch {
            runCatching {
                container.communityApi.postComment(
                    token = container.communityAccount.token,
                    cardId = id,
                    body = body,
                    parentId = parent?.id ?: 0L,
                )
            }.onSuccess { c ->
                comments = if (parent == null) {
                    listOf(c) + comments
                } else {
                    comments.map {
                        if (it.id == parent.id) it.copy(replies = it.replies + c) else it
                    }
                }
                draft = ""
                replyTo = null
                notice = if (parent == null) "发出去了" else "回复成功"
                detail = detail?.let { d ->
                    d.copy(meta = d.meta.copy(commentCount = d.meta.commentCount + 1))
                }
                container.communityCache.patchDetail(id) {
                    it.copy(commentCount = it.commentCount + 1)
                }
            }.onFailure { notice = describeError(it) }
            posting = false
        }
    }

    fun submitReport(reason: String, detailText: String) {
        notice = null
        scope.launch {
            runCatching {
                container.communityApi.report(container.communityAccount.token, id, reason, detailText)
            }.onSuccess {
                notice = "已收到，管理员会尽快看"
            }.onFailure { notice = describeError(it) }
        }
    }

    fun deleteOwn() {
        scope.launch {
            runCatching {
                container.communityApi.deleteOwn(container.communityAccount.token, id)
            }.onSuccess {
                // 内容变了，社区列表里也该没有这张卡了：整体作废最省事
                container.communityCache.clear()
                nav.pop()
            }.onFailure { notice = describeError(it) }
        }
    }

    WatchScaffold(
        title = meta?.name?.ifBlank { "社区卡片" } ?: "社区卡片",
        onBack = { nav.pop() },
        subtitle = meta?.let { "@" + it.author.ifBlank { "匿名" } + " · " + relativeTime(it.createdAt) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(scroll)
                /*
                 * 顶部多留一条：顶栏下面紧贴正文时，第一行会像"切掉了一块"。
                 * 8dp 在手表上正好是一行呼吸位，再多就浪费一屏。
                 */
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (loading) {
                HintText("正在打开…")
                return@Column
            }
            error?.let {
                EmptyState(title = "打不开这张卡", hint = it)
                WatchGhostButton(text = "重试", modifier = Modifier.fillMaxWidth()) {
                    scope.launch {
                        loading = true
                        error = null
                        fetch(force = true)
                    }
                }
                return@Column
            }

            meta?.let { m ->
                if (m.tagline.isNotBlank()) {
                    Text(
                        text = m.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = listOf(
                        if (m.kind == Scenes.WRITING) "写作设定" else "酒馆角色",
                        if (m.ratingCount > 0) "★" + String.format("%.1f", m.rating) else "未评分",
                        "↓" + m.downloads,
                        "♥" + m.likes,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // 评分：五颗星。点过一次就锁住 —— 星星不再响应，下面的说明文字跟着变。
            if (meta != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (1..5).forEach { i ->
                        Text(
                            text = if (i <= myScore) "★" else "☆",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                            color = if (i <= myScore) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .then(
                                    if (myScore == 0) Modifier.tap(onClick = withHaptic { rate(i) })
                                    else Modifier
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = when {
                        myScore > 0 -> "${meta.ratingCount} 人评分 · 你打了 $myScore 星（不能修改）"
                        meta.ratingCount > 0 -> "${meta.ratingCount} 人评分 · 点星星打分，只能打一次"
                        else -> "还没人评分 · 点星星打分，只能打一次"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            // 设定：一块一张卡，长的自动折起来
            card?.let { c ->
                sectionsOf(c).forEach { s ->
                    InfoCard(title = s.title, body = s.body)
                }
            }

            Spacer(Modifier.height(6.dp))

            if (importedId != null) {
                WatchButton(
                    text = if (importedKind == Scenes.WRITING) "去写作看看" else "去酒馆看看",
                    icon = Icons.Filled.Check,
                    onClick = {
                        // 直接把这一页换成目标列表，而不是先退到根再压一层：
                        // 详情页此时已经没有用了，换成列表后返回键回到社区，
                        // 用户还能接着看别的卡
                        nav.replaceTop(
                            if (importedKind == Scenes.WRITING) Screen.WritingHome
                            else Screen.TavernHome
                        )
                    },
                )
            } else {
                WatchButton(
                    text = if (busy) "正在导入…" else "导入到我的酒馆",
                    icon = Icons.Filled.Download,
                    enabled = !busy && card != null,
                    onClick = { import() },
                )
            }

            // 点赞：点过就变成「已点赞」并置灰，不能取消
            WatchGhostButton(
                text = when {
                    liked -> "已点赞 ♥ " + (meta?.likes ?: 0)
                    else -> "点赞 ♥ " + (meta?.likes ?: 0)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !liked && account.signedIn,
                onClick = { like() },
            )

            // ---------------- 评论区（默认展开） ----------------
            SectionHeader("评论 " + (meta?.commentCount ?: comments.size))

            if (account.signedIn) {
                if (replyTo != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "回复 @${replyTo!!.author}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.tap {
                                replyTo = null
                                draft = ""
                            },
                        )
                    }
                }
                TextRow(
                    label = if (replyTo == null) "说点什么" else "回复内容",
                    value = draft,
                    onValueChange = { if (it.length <= 300) draft = it },
                    placeholder = "最多 300 字",
                    singleLine = false,
                )
                WatchGhostButton(
                    text = when {
                        posting -> "发送中…"
                        replyTo != null -> "发送回复"
                        else -> "发送评论"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !posting && draft.isNotBlank(),
                    onClick = { sendComment() },
                )
            } else {
                HintText("登录后才能评论、回复、点赞")
                WatchGhostButton(text = "去登录", modifier = Modifier.fillMaxWidth()) {
                    nav.push(Screen.CommunityAuth)
                }
            }

            notice?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (msg.startsWith("已") || msg == "发出去了" || msg == "回复成功") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            commentsError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                WatchGhostButton(text = "重试评论", modifier = Modifier.fillMaxWidth()) {
                    scope.launch { loadComments(reset = true) }
                }
            }

            if (!commentsLoaded && commentsLoading) {
                HintText("正在取评论…")
            }
            if (commentsLoaded && comments.isEmpty() && commentsError == null) {
                HintText("还没有人评论，说第一句吧")
            }

            comments.forEach { c ->
                CommentBlock(
                    comment = c,
                    onLike = { toggleCommentLike(c) },
                    onReply = {
                        replyTo = c
                        draft = ""
                    },
                )
            }

            when {
                commentsLoading && comments.isNotEmpty() -> HintText("正在取更多评论…")
                commentsHasMore -> WatchGhostButton(
                    text = "加载更多评论",
                    modifier = Modifier.fillMaxWidth(),
                ) { scope.launch { loadComments(reset = false) } }
            }

            if (comments.isNotEmpty()) {
                HintText("评论会公开显示，请别写联系方式")
            }

            // 作者本人的两个入口放最下面：不常用，但需要时得找得到
            if (detail?.mine == true) {
                WatchGhostButton(
                    text = "撤回投稿",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { confirmDelete = true },
                )
            }
            if (account.signedIn && detail?.mine != true) {
                WatchGhostButton(
                    text = "举报",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showReport = true },
                )
            }

            HintText("导入后就是一张普通角色卡，可以改、可以删")
        }
    }

    if (showReport) {
        GlassDialog(
            onDismissRequest = { showReport = false },
            title = "举报这张卡",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    REPORT_REASONS.forEach { r ->
                        GlassDialogRow(title = r) {
                            showReport = false
                            if (r == REPORT_OTHER) {
                                // 「其他」要先写一句是什么问题，否则管理员只能看到四个字
                                reportText = ""
                                reportOther = true
                            } else {
                                submitReport(r, "")
                            }
                        }
                    }
                }
            },
            buttons = {
                TextButton(onClick = { showReport = false }) { Text("取消") }
            },
        )
    }

    if (reportOther) {
        GlassDialog(
            onDismissRequest = { reportOther = false },
            title = "说说是什么问题",
            content = {
                TextRow(
                    label = "补充说明",
                    value = reportText,
                    onValueChange = { if (it.length <= 200) reportText = it },
                    placeholder = "比如「角色设定里夹了广告链接」",
                    singleLine = false,
                )
            },
            buttons = {
                TextButton(onClick = { reportOther = false }) { Text("取消") }
                TextButton(
                    onClick = {
                        val text = reportText.trim()
                        if (text.isEmpty()) return@TextButton
                        reportOther = false
                        submitReport(REPORT_OTHER, text)
                    },
                ) {
                    Text(if (reportText.isBlank()) "请先填写" else "提交")
                }
            },
        )
    }

    if (confirmDelete) {
        GlassDialog(
            onDismissRequest = { confirmDelete = false },
            title = "撤回投稿？",
            content = {
                Text(
                    text = "「${meta?.name.orEmpty()}」会从社区消失，别人就下载不到了。"
                        + "你自己本地的这张卡不受影响。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            buttons = {
                TextButton(onClick = { confirmDelete = false }) { Text("再想想") }
                TextButton(
                    onClick = {
                        confirmDelete = false
                        deleteOwn()
                    },
                ) {
                    Text("撤回", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

/** 举报理由。服务端只校验非空和长度，枚举放在客户端是为了后台好归类 */
private val REPORT_REASONS = listOf(
    "内容不适",
    "色情低俗",
    "违法违规",
    "抄袭搬运",
    "垃圾广告",
    REPORT_OTHER,
)

private const val REPORT_OTHER = "其他"

/** 折叠时最多显示几行正文 */
private const val COLLAPSED_LINES = 5

/** 一张卡的展示分块：标题 + 正文 */
private data class InfoSection(val title: String, val body: String)

private fun sectionsOf(c: RoleCard): List<InfoSection> {
    val out = mutableListOf<InfoSection>()
    c.fields.forEach { f ->
        if (f.key.isNotBlank() || f.value.isNotBlank()) {
            out += InfoSection(f.key.ifBlank { "补充设定" }, f.value)
        }
    }
    if (c.persona.isNotBlank()) {
        out += InfoSection(if (c.kind == Scenes.WRITING) "世界与剧情" else "人物背景", c.persona)
    }
    if (c.userPersona.isNotBlank()) out += InfoSection("你的身份", c.userPersona)
    if (c.style.isNotBlank()) {
        out += InfoSection(if (c.kind == Scenes.WRITING) "文风" else "说话方式", c.style)
    }
    if (c.greeting.isNotBlank()) out += InfoSection("开场", c.greeting)
    c.entries.forEach { e ->
        if (e.content.isNotBlank()) {
            val head = e.title.ifBlank { "未命名" }
            val kw = e.keywords.joinToString("、")
            out += InfoSection(
                "世界书 · " + head,
                if (kw.isBlank()) e.content else "关键词：$kw\n\n" + e.content,
            )
        }
    }
    return out
}

/**
 * 一块设定。
 *
 * 超过 [COLLAPSED_LINES] 行才出现「展开全文」—— 用 `hasVisualOverflow`
 * 判断，而不是按字数猜：同样 80 个字，一行标点和一行中文差一倍高度。
 */
@Composable
private fun InfoCard(title: String, body: String) {
    var expanded by remember(title, body) { mutableStateOf(false) }
    var overflow by remember(title, body) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(16.dp), cornerRadius = 16.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { r ->
                if (!expanded && r.hasVisualOverflow) overflow = true
            },
        )
        if (overflow || expanded) {
            Text(
                text = if (expanded) "收起" else "展开全文",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.tap { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun CommentBlock(
    comment: CommunityComment,
    onLike: () -> Unit,
    onReply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(16.dp), cornerRadius = 16.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CommentBody(comment, onLike = onLike, onReply = onReply)
        comment.replies.forEach { r ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 4.dp),
            ) {
                Text(
                    text = "↳",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    CommentBody(r, onLike = onLike, onReply = onReply, compact = true)
                }
            }
        }
    }
}

@Composable
private fun CommentBody(
    c: CommunityComment,
    onLike: () -> Unit,
    onReply: () -> Unit,
    compact: Boolean = false,
) {
    Text(
        text = "@" + c.author.ifBlank { "匿名" } + " · " + relativeTime(c.createdAt),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.outline,
    )
    Text(
        text = c.body,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = if (compact) 11.sp else 12.sp),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "♥ " + c.likes,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (c.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .then(if (c.liked) Modifier else Modifier.tap(onClick = withHaptic(onLike)))
                .padding(vertical = 2.dp),
        )
        Text(
            text = "回复",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .tap(onClick = withHaptic(onReply))
                .padding(vertical = 2.dp),
        )
    }
}

/** 把某条评论的赞数改成服务端返回的值（回复里也找） */
private fun setLikes(list: List<CommunityComment>, id: Long, likes: Int): List<CommunityComment> =
    list.map { c ->
        when {
            c.id == id -> c.copy(likes = likes, liked = true)
            else -> c.copy(replies = setLikes(c.replies, id, likes))
        }
    }

/** 本地先 +1（主评论或回复都可能） */
private fun bumpLike(list: List<CommunityComment>, id: Long): List<CommunityComment> =
    list.map { c ->
        when {
            c.id == id -> c.copy(likes = c.likes + 1, liked = true)
            else -> c.copy(replies = bumpLike(c.replies, id))
        }
    }
