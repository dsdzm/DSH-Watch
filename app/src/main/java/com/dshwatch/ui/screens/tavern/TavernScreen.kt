package com.dshwatch.ui.screens.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.scene.ScenePill
import com.dshwatch.ui.screens.scene.ScenePickerDialog
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 酒馆根界面：角色卡列表。
 *
 * 布局刻意做得很轻：一屏两三张卡、每张卡一行名字 + 一行简介 + 一个「开始」按钮。
 * 用户在这个界面要做的事只有一件 —— 选个人开始聊。所以清单、设置、工具
 * 一概不出现在这里（设计上"重点功能偏向调教模型和交流"）。
 */
@Composable
fun TavernScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<RoleCard>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cards = runCatching { container.roleCardStore.index(Scenes.TAVERN) }.getOrDefault(emptyList())
        loaded = true
    }

    WatchScaffold(
        title = "酒馆",
        actions = {
            ScenePill(Scenes.TAVERN) { showPicker = true }
        },
    ) {
        if (loaded && cards.isEmpty()) {
            EmptyState(
                title = "还没有角色",
                hint = "新建一个：名字、性格、开场白，三行就能聊起来",
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cards.forEach { card ->
                TavernCardRow(
                    card = card,
                    onStart = {
                        scope.launch {
                            // 记住上次用过的卡：下次进来直接续上，省一次选择
                            container.settingsRepo.update { it.copy(lastTavernCardId = card.id) }
                            container.engine.startNew(
                                settings = settings.copy(agentMode = com.dshwatch.domain.model.AgentMode.OFF),
                                scene = Scenes.TAVERN,
                                roleCardId = card.id,
                                title = card.displayName,
                            )
                            nav.push(Screen.RoleplayChat)
                        }
                    },
                    onEdit = { nav.push(Screen.CardEdit(Scenes.TAVERN, card.id)) },
                )
            }

            Spacer(Modifier.height(2.dp))
            WatchGhostButton(
                text = "新建角色卡",
                icon = Icons.Filled.Add,
                onClick = { nav.push(Screen.CardEdit(Scenes.TAVERN, "")) },
            )
        }
    }

    if (showPicker) {
        ScenePickerDialog(
            current = Scenes.TAVERN,
            onPick = { id ->
                showPicker = false
                scope.launch {
                    container.settingsRepo.update { it.copy(useScene = id) }
                }
                when (id) {
                    Scenes.WRITING -> { nav.popToRoot(); nav.push(Screen.WritingHome) }
                    Scenes.CODING -> nav.popToRoot()
                    else -> Unit
                }
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun TavernCardRow(
    card: RoleCard,
    onStart: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像是「名字第一个字」的圆形色块：比空白圆点有辨识度，又不需要图片资源
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = card.displayName.take(1),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
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
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .tap(onClick = withHaptic(onEdit))
                    .padding(7.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchButton(
                text = "开始对话",
                icon = Icons.Filled.ArrowForward,
                modifier = Modifier.weight(1f),
                onClick = onStart,
            )
        }
    }
}

/**
 * 角色卡详情：只读的"档案"页 + 记忆入口。
 *
 * 编辑是另一个页面（[CardEditScreen]），这里只负责"看"和"看它记得什么"。
 * 分开的理由：手表上表单和阅读的排版要求完全不同，混在一页会两边都不好用。
 */
@Composable
fun CardDetailSheet(
    card: RoleCard,
    isWriting: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wearVerticalScroll(rememberScrollState()),
    ) {
        SectionHeader(if (isWriting) "设定" else "角色设定")
        card.fields.forEach { f ->
            if (f.key.isNotBlank()) DetailLine(f.key, f.value)
        }
        if (card.persona.isNotBlank()) {
            DetailLine(if (isWriting) "世界与剧情" else "人物背景", card.persona)
        }
        if (card.userPersona.isNotBlank()) DetailLine("你的身份", card.userPersona)
        if (card.style.isNotBlank()) DetailLine(if (isWriting) "文风" else "说话方式", card.style)
        if (card.greeting.isNotBlank()) DetailLine("开场", card.greeting)
        if (card.entries.isNotEmpty()) DetailLine("世界书", "${card.entries.size} 条")
        if (card.plugins.isNotEmpty()) DetailLine("提示插件", "${card.plugins.size} 条")
    }
}

@Composable
private fun DetailLine(key: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
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

/** 记忆文档的只读预览（编辑走「工作空间」里的那个 md 文件） */
@Composable
fun MemorySheet(
    content: String,
    onClear: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        if (content.isBlank()) {
            EmptyState(title = "还没有记忆", hint = "聊得多了、或手动压缩一次之后就会写在这里")
        } else {
            Column(
                modifier = Modifier
                    .height(220.dp)
                    .wearVerticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WatchGhostButton(text = "编辑", modifier = Modifier.weight(1f), onClick = onEdit)
                WatchGhostButton(text = "清空", modifier = Modifier.weight(1f), onClick = onClear)
            }
        }
    }
}

/** 卡片列表里的"记忆"图标按钮（供详情页复用） */
@Composable
fun MemoryIconButton(onClick: () -> Unit) {
    Icon(
        imageVector = Icons.Outlined.Psychology,
        contentDescription = "记忆",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .tap(onClick = withHaptic(onClick))
            .padding(7.dp),
    )
}
