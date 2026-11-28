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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.community.CachedDetail
import com.dshwatch.data.community.CommunityCache
import com.dshwatch.data.community.CommunityDetail
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogRow
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 社区卡片详情。
 *
 * 这一页的全部意义就是那颗「导入」按钮 —— 社区里的卡不能直接被聊，
 * 必须落成本地 `rolecards/xxx.json`，因为对话、编辑、记忆、世界书注入
 * 全都按本地 id 找文件。所以导入 = 下载全文 + `RoleCardStore.save`，
 * 和用户自己新建一张卡走的是同一条路，不引入任何"社区卡"的特殊状态。
 *
 * 下载计数只在**真的导入**时 +1：点开看一眼不算（服务端用 `&download=1`
 * 区分，这个参数只在这一处出现）。
 *
 * ## 首帧一定来自缓存
 *
 * 从列表点进来时，用户预期是"立刻打开"。详情 30 分钟内不变，缓存直接
 * 拿来显示，一个请求都不发；过期也是先显示这一份，后台悄悄换。
 */
@Composable
fun CommunityCardScreen(
    container: AppContainer,
    nav: WatchNavigator,
    id: Long,
) {
    val scope = rememberCoroutineScope()
    val signedIn by container.communityAccount.state.collectAsStateWithLifecycle()

    val cachedDetail = remember(id) { container.communityCache.detail(id) }
    var detail by remember(id) {
        mutableStateOf(cachedDetail?.let { CommunityDetail(it.meta, it.card, it.myScore, it.mine) })
    }
    var loading by remember(id) { mutableStateOf(cachedDetail == null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rateBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var importedId by remember { mutableStateOf<String?>(null) }
    var importedKind by remember { mutableStateOf(Scenes.TAVERN) }
    var showReport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    suspend fun fetch(force: Boolean) {
        val c = container.communityCache.detail(id)
        if (!force && c != null && container.communityCache.isFresh(c.at, CommunityCache.DETAIL_TTL)) {
            return
        }
        if (c == null) loading = true
        // 带 token：才知道我打了几分、这张卡是不是我投的
        runCatching {
            container.communityApi.detail(
                id = id,
                token = container.communityAccount.token.takeIf { signedIn.signedIn },
            )
        }.onSuccess { d ->
            detail = d
            container.communityCache.putDetail(
                id,
                CachedDetail(
                    at = System.currentTimeMillis(),
                    meta = d.meta,
                    card = d.card,
                    myScore = d.myScore,
                    mine = d.mine,
                ),
            )
            error = null
        }.onFailure { e ->
            // 有缓存就别用红字盖掉正文：用户至少还能看，还能导入
            if (c == null && detail == null) error = describeError(e)
        }
        loading = false
    }

    LaunchedEffect(id) { fetch(force = false) }

    val card = detail?.card
    val meta = detail?.meta

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

    fun rate(score: Int) {
        if (rateBusy) return
        if (!signedIn.signedIn) {
            notice = "登录后才能评分"
            return
        }
        rateBusy = true
        notice = null
        scope.launch {
            runCatching { container.communityApi.rate(container.communityAccount.token, id, score) }
                .onSuccess { r ->
                    detail = detail?.let {
                        it.copy(
                            meta = it.meta.copy(rating = r.rating, ratingCount = r.count),
                            myScore = r.myScore,
                        )
                    }
                    container.communityCache.patchDetail(id) {
                        it.copy(rating = r.rating, ratingCount = r.count)
                    }
                    notice = if (r.myScore == score) "已评 ${score} 星" else "已改评 ${score} 星"
                }
                .onFailure { notice = describeError(it) }
            rateBusy = false
        }
    }

    fun submitReport(reason: String) {
        notice = null
        scope.launch {
            runCatching {
                container.communityApi.report(container.communityAccount.token, id, reason)
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
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
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

            // 评分：五颗星，点哪颗就是几分（登录后）。手表上一行刚好放得下。
            if (meta != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val my = detail?.myScore ?: 0
                    (1..5).forEach { i ->
                        Text(
                            text = if (i <= my) "★" else "☆",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                            color = if (i <= my) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .tap(onClick = withHaptic { rate(i) })
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = if (meta.ratingCount > 0) {
                        "${meta.ratingCount} 人评分" + if ((detail?.myScore ?: 0) > 0) " · 你打了 ${detail?.myScore} 星" else ""
                    } else {
                        "还没人评分"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            card?.let { c ->
                if (c.fields.isNotEmpty()) {
                    SectionHeader(if (c.kind == Scenes.WRITING) "设定" else "角色设定")
                    c.fields.forEach { f ->
                        if (f.key.isNotBlank()) DetailLine(f.key, f.value)
                    }
                }
                if (c.persona.isNotBlank()) {
                    DetailLine(
                        if (c.kind == Scenes.WRITING) "世界与剧情" else "人物背景",
                        c.persona,
                    )
                }
                if (c.userPersona.isNotBlank()) DetailLine("你的身份", c.userPersona)
                if (c.style.isNotBlank()) {
                    DetailLine(if (c.kind == Scenes.WRITING) "文风" else "说话方式", c.style)
                }
                if (c.greeting.isNotBlank()) DetailLine("开场", c.greeting)
                if (c.entries.isNotEmpty()) {
                    DetailLine("世界书", c.entries.joinToString("、") { it.title }.take(120))
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

            // 评论：数量直接在按钮上，比藏在详情里更有存在感
            WatchGhostButton(
                text = "评论 " + (meta?.commentCount ?: 0) + " 条",
                modifier = Modifier.fillMaxWidth(),
            ) {
                nav.push(Screen.CommunityComments(id))
            }

            WatchGhostButton(
                text = "点赞",
                modifier = Modifier.fillMaxWidth(),
                enabled = signedIn.signedIn && !busy,
                onClick = {
                    scope.launch {
                        runCatching {
                            container.communityApi.like(container.communityAccount.token, id)
                        }.onSuccess { likes ->
                            detail = detail?.let { d -> d.copy(meta = d.meta.copy(likes = likes)) }
                            container.communityCache.patchDetail(id) { it.copy(likes = likes) }
                            notice = "已点赞"
                        }.onFailure { notice = describeError(it) }
                    }
                },
            )
            if (!signedIn.signedIn) {
                HintText("登录社区账号才能评分、评论、点赞")
            }

            notice?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (importedId != null || msg.startsWith("已")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
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
            if (signedIn.signedIn && detail?.mine != true) {
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
                            submitReport(r)
                        }
                    }
                }
            },
            buttons = {
                TextButton(onClick = { showReport = false }) { Text("取消") }
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
    "其他",
)

@Composable
private fun DetailLine(key: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
