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
import com.dshwatch.data.community.CommunityDetail
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
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
 */
@Composable
fun CommunityCardScreen(
    container: AppContainer,
    nav: WatchNavigator,
    id: Long,
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<CommunityDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var importedId by remember { mutableStateOf<String?>(null) }
    var importedKind by remember { mutableStateOf(Scenes.TAVERN) }
    val signedIn by container.communityAccount.state.collectAsStateWithLifecycle()

    LaunchedEffect(id) {
        loading = true
        error = null
        runCatching { container.communityApi.detail(id) }
            .onSuccess { detail = it }
            .onFailure { error = describeError(it) }
        loading = false
    }

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
                val saved = container.roleCardStore.save(
                    fresh.card.copy(
                        id = "",
                        name = if (taken) "$name（社区）" else name,
                        builtin = false,
                        createdAt = 0L,
                    )
                )
                saved
            }.onSuccess { saved ->
                importedId = saved.id
                importedKind = saved.kind
                notice = "已导入：「${saved.displayName}」"
            }.onFailure { e ->
                notice = describeError(e)
            }
            busy = false
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
                        runCatching { container.communityApi.detail(id) }
                            .onSuccess { d -> detail = d }
                            .onFailure { e -> error = describeError(e) }
                        loading = false
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
                        "↓" + m.downloads,
                        "♥" + m.likes,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.outline,
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
                        // 先退到根再压一页：用户下一步一定是想聊这张卡，
                        // 如果只 pop，他会落在社区列表里，还得自己找一遍
                        nav.popToRoot()
                        nav.push(
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
                            notice = "已点赞"
                        }.onFailure { notice = describeError(it) }
                    }
                },
            )
            if (!signedIn.signedIn) {
                HintText("登录社区账号才能点赞")
            }
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (importedId != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            HintText("导入后就是一张普通角色卡，可以改、可以删")
        }
    }
}

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
