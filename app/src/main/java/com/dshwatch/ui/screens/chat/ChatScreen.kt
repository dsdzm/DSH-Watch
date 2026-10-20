package com.dshwatch.ui.screens.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.R
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.LiveDelta
import com.dshwatch.domain.TokenEstimator
import com.dshwatch.domain.ApprovalDecision
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.model.TokenUsage
import com.dshwatch.domain.model.ToolCallRecord
import com.dshwatch.domain.model.ToolStatus
import android.widget.Toast
import com.dshwatch.ui.components.AgentModeDialog
import com.dshwatch.ui.components.ArtifactCard
import com.dshwatch.ui.components.AskUserDialog
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.ConfirmDialog
import com.dshwatch.ui.components.ContextBar
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.IconTap
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.ShareResult
import com.dshwatch.ui.components.shareFile
import com.dshwatch.ui.components.TokenBadge
import com.dshwatch.ui.components.ToolApprovalDialog
import com.dshwatch.ui.components.ToolCallCard
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.markdown.MarkdownText
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.components.cornerSafe
import com.dshwatch.ui.theme.LocalPerf
import com.dshwatch.ui.theme.WatchDimens
import com.dshwatch.util.Fmt
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChatScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val perf = LocalPerf.current
    val engine = container.engine
    val scope = rememberCoroutineScope()

    val conversation by engine.conversation.collectAsStateWithLifecycle()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val streaming by engine.streaming.collectAsStateWithLifecycle()
    val banner by engine.banner.collectAsStateWithLifecycle()

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val activityView = androidx.compose.ui.platform.LocalView.current
    val listState = rememberLazyListState()
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentDialog by remember { mutableStateOf(false) }

    val approval by engine.approval.collectAsStateWithLifecycle()
    val pendingQuestion by engine.question.collectAsStateWithLifecycle()

    // 把「只有界面能做」的动作接上：open_screen / open_file 这两个工具靠它落地。
    // 用 DisposableEffect 而不是 LaunchedEffect —— 离开聊天页时必须摘掉，
    // 否则通道里会留着一个指向已销毁导航栈的 lambda。
    DisposableEffect(Unit) {
        container.uiActions.previewFile = { path -> nav.push(Screen.WorkspacePreview(path)) }
        container.uiActions.openScreen = { key -> openScreen(nav, key) }
        onDispose {
            container.uiActions.previewFile = null
            container.uiActions.openScreen = null
        }
    }

    // 本会话里是否成功构建过产物（build_project 工具有过 SUCCESS）
    val hasArtifact = remember(messages) {
        messages.any { msg ->
            msg.toolCalls.any { it.name == "build_project" && it.status == ToolStatus.SUCCESS }
        }
    }

    val provider = settings.activeProvider

    // 渲染分组：把同一个 Agent 回合里连续的助手消息（步 1、步 2…）视作**一条** DSH 回合，
    // 前端因此只出现「用户消息 + 一条总的工作流程卡片」，而不是每步各来一张卡。
    val visible = remember(messages, perf.maxRenderedMessages) {
        if (messages.size > perf.maxRenderedMessages) {
            messages.takeLast(perf.maxRenderedMessages)
        } else {
            messages
        }
    }
    val turns = remember(visible) { groupMessages(visible) }

    // 首次进入：自动打开最近一个会话
    LaunchedEffect(Unit) {
        if (engine.conversation.value == null) {
            container.conversationStore.index().firstOrNull()?.let { engine.open(it.id) }
        }
    }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }

    // 流式刷新节流后仍会频繁触发，这里用 scrollToItem（无动画）避免低端机掉帧
    LaunchedEffect(turns.size, live.text.length, streaming) {
        if (!settings.autoScroll) return@LaunchedEffect
        if (!atBottom) return@LaunchedEffect
        if (turns.isNotEmpty()) {
            runCatching { listState.scrollToItem(turns.lastIndex) }
        }
    }
    // 生成期间保持亮屏（可选）。手表抬手看进度时不用反复抬腕。
    LaunchedEffect(streaming, settings.keepScreenOn) {
        activityView.keepScreenOn = streaming && settings.keepScreenOn
    }

    val contextTokens = remember(messages, provider?.id) {
        var t = 0
        for (m in messages) {
            if (m.status == MessageStatus.STREAMING) continue
            t += TokenEstimator.estimatePair(m.role.wire, m.content)
        }
        t
    }

    WatchScaffold(
        title = conversation?.displayTitle ?: s.appName,
        // 根是主页，对话页是二级：返回即回主页
        onBack = { nav.pop() },
        actions = {
            // 首页已经承载了设置入口，这里只留聊天时最常用的两个动作：
            // 切会话、开新会话 —— 手表顶栏宽度经不起四个图标
            IconTap(Icons.Outlined.History, s.navSessions) { nav.push(Screen.Sessions) }
            IconTap(Icons.Outlined.Add, s.newChat) {
                scope.launch { engine.newConversation(settings) }
            }
        },
        bottom = {
            ChatBottomBar(
                settings = settings,
                streaming = streaming,
                onOpenInput = { nav.push(Screen.Input()) },
                onSend = { nav.push(Screen.Input()) },
                onStop = { engine.stop() },
                onModelClick = { showModelPicker = true },
            )
        },
    ) {
        // 顶部信息条：模型 + 上下文占用
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(
                    text = provider?.let { p ->
                        val model = conversation?.model?.ifBlank { p.defaultModel }.orEmpty()
                        if (model.isBlank()) p.name else model
                    } ?: "未配置",
                    selected = true,
                    onClick = { showModelPicker = true },
                )
                Spacer(Modifier.width(4.dp))
                Chip(
                    text = "Agent · ${settings.agentMode.label}",
                    selected = settings.agentMode != AgentMode.OFF,
                    onClick = { showAgentDialog = true },
                )
                // 只要这次会话里成功构建过产物，就常驻一个入口，省得用户去找
                if (hasArtifact) {
                    Spacer(Modifier.width(3.dp))
                    Chip(
                        text = s.navWorkspace,
                        selected = true,
                        onClick = { nav.push(Screen.Workspace) },
                    )
                }
                Spacer(Modifier.width(3.dp))
                if (streaming) {
                    Text(
                        text = s.generating,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (perf.enableAnimations) {
                // 上下文条只在动画档显示，省电档下省略这块重绘
                ContextBar(
                    used = contextTokens,
                    total = provider?.contextWindow ?: 0,
                )
                Spacer(Modifier.height(2.dp))
            }
        }

        banner?.let { b ->
            BannerCard(text = b.text, onDismiss = { engine.dismissBanner() })
            Spacer(Modifier.height(4.dp))
        }

        if (provider == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有配置供应商",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "直连自己的 API Key，或连接电脑上的 DSH 服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = s.addProvider,
                    icon = Icons.Filled.Add,
                    onClick = { nav.push(Screen.Providers) },
                )
            }
            return@WatchScaffold
        }

        if (messages.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (settings.showMascot) {
                    Image(
                        painter = painterResource(R.drawable.mascot),
                        contentDescription = null,
                        modifier = Modifier.size(112.dp),
                        alpha = 0.92f,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                EmptyState(title = s.chatEmptyTitle, hint = s.chatEmptyHint)
            }
            return@WatchScaffold
        }

        // 消息区右下角：往上翻看历史时一键回到最新。
        // 放在消息区的 Box 里，而不是 WatchScaffold 的 bottom 槽 —— 后者是输入框。
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = turns,
                    key = { it.key },
                    contentType = { it.messages.first().role },
                ) { turn ->
                    val msgs = turn.messages
                    val sizeOf: (String) -> Long = { path -> artifactSizeOf(container, path) }
                    val openArtifact: (String) -> Unit = { path -> nav.push(Screen.WorkspacePreview(path)) }
                    val exportArtifact: (String) -> Unit = { path ->
                        // 导出要么成功（系统弹出分享面板，本身就是反馈），
                        // 要么失败 —— 失败必须说出来。以前这里是一句 runCatching 吞掉，
                        // 用户点了半天「没反应」，根本不知道发生了什么。
                        val result = shareFile(context, container.workspaceStore.resolveForUi(path))
                        if (result is ShareResult.Fail) {
                            Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                        }
                    }
                    val copyText: (String) -> Unit = { text ->
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    }

                    if (msgs.size == 1) {
                        val message = msgs.first()
                        val isLive = live.messageId == message.id
                        MessageItem(
                            message = message,
                            live = if (isLive) live else null,
                            settings = settings,
                            onCopy = copyText,
                            onRegenerate = { engine.regenerate(message.id) },
                            onDelete = { pendingDelete = message },
                            sizeOf = sizeOf,
                            onOpenArtifact = openArtifact,
                            onExportArtifact = exportArtifact,
                        )
                    } else {
                        // 多步 Agent 回合：连续助手消息合成一条，只显示一条工作流程卡片
                        val isLive = live.messageId == msgs.last().id
                        AgentTurnItem(
                            messages = msgs,
                            live = if (isLive) live else null,
                            settings = settings,
                            onCopy = copyText,
                            onRegenerate = { engine.regenerate(msgs.last().id) },
                            onDelete = { pendingDelete = msgs.last() },
                            sizeOf = sizeOf,
                            onOpenArtifact = openArtifact,
                            onExportArtifact = exportArtifact,
                        )
                    }

                    if (settings.showTokenBadge) {
                        val usage = msgs.mapNotNull { it.usage }
                            .fold(TokenUsage.ZERO) { acc, u -> acc + u }
                        if (!usage.isEmpty) {
                            TokenBadgeBar(
                                usage = usage,
                                at = msgs.last().createdAt,
                                estimated = msgs.any { it.status == MessageStatus.STOPPED },
                            )
                        }
                    }
                }
                item(key = "__tail__") { Spacer(Modifier.height(6.dp)) }
            }

            if (!atBottom && turns.isNotEmpty()) {
                // 带底色的圆形按钮：纯图标直接浮在消息上会看不清
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 2.dp, bottom = 2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    IconTap(
                        icon = Icons.Filled.KeyboardArrowDown,
                        contentDescription = s.backToBottom,
                        tint = MaterialTheme.colorScheme.primary,
                    ) {
                        scope.launch {
                            runCatching { listState.animateScrollToItem(turns.lastIndex) }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = s.deleteMessage,
            text = Fmt.preview(target.content, 60),
            onConfirm = {
                scope.launch { container.engine.deleteMessage(target.id) }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    approval?.let { pending ->
        ToolApprovalDialog(
            pending = pending,
            onDecision = { decision: ApprovalDecision -> engine.resolveApproval(decision) },
        )
    }

    pendingQuestion?.let { q ->
        AskUserDialog(
            question = q,
            onAnswer = { engine.answerQuestion(it) },
            onSkip = { engine.skipQuestion() },
        )
    }

    if (showAgentDialog) {
        AgentModeDialog(
            current = settings.agentMode,
            onPickMode = { mode ->
                scope.launch {
                    container.settingsRepo.update { it.copy(agentMode = mode) }
                }
            },
            onDismiss = { showAgentDialog = false },
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = provider?.models.orEmpty(),
            current = conversation?.model.orEmpty(),
            onPick = { model ->
                showModelPicker = false
                scope.launch { engine.overrideModel(model) }
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun TokenBadgeBar(usage: TokenUsage, at: Long, estimated: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenBadge(usage = usage, estimated = estimated)
        Spacer(Modifier.weight(1f))
        Text(
            text = Fmt.clock(at),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun BannerCard(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = LocalStrings.current.close,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .tap(onClick = withHaptic(onDismiss))
                .padding(start = 8.dp),
        )
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    live: LiveDelta?,
    settings: AppSettings,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    sizeOf: (String) -> Long,
    onOpenArtifact: (String) -> Unit,
    onExportArtifact: (String) -> Unit,
) {
    val s = LocalStrings.current
    val body = live?.text ?: message.content
    val reasoning = live?.reasoning ?: message.reasoning
    val isUser = message.role == Role.USER
    val isStreaming = live != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 用户消息用主色容器、助手消息用中性容器 —— 不用左对齐/右对齐也能一眼分开，
            // 而且不浪费本来就窄的横向空间
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像比三个字母更容易一眼认出「这是谁在说话」，同时不占多少横向空间
            if (!isUser && settings.showMascot) {
                Image(
                    painter = painterResource(R.drawable.mascot_avatar),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = if (isUser) "我" else "DSH",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            if (message.stepIndex > 0) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "${s.stepLabel} ${message.stepIndex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
            if (isStreaming) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (reasoning.isNotBlank() && body.isBlank()) s.thinking else s.generating,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!isUser && message.status == MessageStatus.ERROR) {
                Text(
                    text = s.sendFailed,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!isUser && message.status == MessageStatus.STOPPED) {
                Text(
                    text = s.stopped,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(3.dp))

        // 思考与工具调用收进同一个「工作块」：默认折叠只显示当前进度，
        // 想看过程再点开。两者都没有就不渲染。
        if (!isUser && (message.toolCalls.isNotEmpty() || (settings.showReasoning && reasoning.isNotBlank()))) {
            AgentWorkBlock(
                blockKey = message.id,
                calls = message.toolCalls,
                reasoning = reasoning,
                isStreaming = isStreaming,
                showReasoning = settings.showReasoning,
                sizeOf = sizeOf,
                onOpenArtifact = onOpenArtifact,
                onExportArtifact = onExportArtifact,
            )
            Spacer(Modifier.height(4.dp))
        }

        if (body.isNotBlank()) {
            if (isUser) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                MarkdownText(text = body)
            }
        } else if (isStreaming) {
            Text(
                text = "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isUser && message.error.isNotBlank() && message.status == MessageStatus.ERROR) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // 操作行常显：复制 / 重试 / 删除不该藏在「先点一下消息」后面 ——
        // 那个交互既难发现、又很容易误触。只在生成结束后出现，避免流式期间每帧重排。
        if (!isStreaming && body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MsgAction(text = s.copy) { onCopy(body) }
                if (!isUser) {
                    MsgAction(text = s.regenerate) { onRegenerate() }
                    MsgAction(text = s.delete) { onDelete() }
                }
            }
        }
    }
}

/**
 * 多步 Agent 回合 —— 把同一个回合里连续的助手消息（步 1、步 2…）合并成**一条**
 * DSH 回合渲染。
 *
 * 用户要求：交付产物之前，前端只显示「用户的消息 + 一条总的工作流程卡片」。
 * 所以这里把各步的工具调用与思考链合并后交给一个 [AgentWorkBlock]，标题恒为
 * 「工作流程」，不再每一步各来一张卡；只有当终态那条消息给出了正文（交付物）
 * 时，才在卡片下面渲染回答正文。
 */
@Composable
private fun AgentTurnItem(
    messages: List<ChatMessage>,
    live: LiveDelta?,
    settings: AppSettings,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    sizeOf: (String) -> Long,
    onOpenArtifact: (String) -> Unit,
    onExportArtifact: (String) -> Unit,
) {
    val s = LocalStrings.current
    val last = messages.last()
    val isStreaming = live != null
    // 合并各步：顺序即执行顺序
    val calls = messages.flatMap { it.toolCalls }
    val reasoning = messages
        .mapNotNull { it.reasoning.takeIf { r -> r.isNotBlank() } }
        .joinToString("\n\n")
    // 交付物 = 终态（已落定、无待执行工具调用）那条消息的正文。
    // 进行中的中间步骤正文不显示 —— 交付前前端只留工作流程卡片。
    val answer = messages.lastOrNull {
        it.toolCalls.isEmpty() && it.content.isNotBlank() &&
            it.status != MessageStatus.STREAMING && it.status != MessageStatus.PENDING
    }?.content.orEmpty()
    val hasError = last.status == MessageStatus.ERROR

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (settings.showMascot) {
                Image(
                    painter = painterResource(R.drawable.mascot_avatar),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = "DSH",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (isStreaming) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (reasoning.isNotBlank() && answer.isBlank()) s.thinking else s.generating,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (hasError) {
                Text(
                    text = s.sendFailed,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (last.status == MessageStatus.STOPPED) {
                Text(
                    text = s.stopped,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(3.dp))

        AgentWorkBlock(
            blockKey = messages.first().id,
            calls = calls,
            reasoning = reasoning,
            isStreaming = isStreaming,
            showReasoning = settings.showReasoning,
            sizeOf = sizeOf,
            onOpenArtifact = onOpenArtifact,
            onExportArtifact = onExportArtifact,
        )

        if (answer.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            MarkdownText(text = answer)
        }

        if (hasError && last.error.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = last.error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!isStreaming && answer.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MsgAction(text = s.copy) { onCopy(answer) }
                MsgAction(text = s.regenerate) { onRegenerate() }
                MsgAction(text = s.delete) { onDelete() }
            }
        }
    }
}

/**
 * 把消息按「回合」分组：连续的助手消息（Agent 的多步）归为一组，
 * 其余（用户消息、单条助手消息）各自成组。这样 LazyColumn 里每个条目
 * 就是前端意义上的「一条消息」，多步 Agent 只会出现一条工作流程卡片。
 */
private fun groupMessages(msgs: List<ChatMessage>): List<MessageGroup> {
    val out = ArrayList<MessageGroup>()
    var i = 0
    while (i < msgs.size) {
        if (msgs[i].role != Role.ASSISTANT) {
            out.add(MessageGroup("s_${msgs[i].id}", listOf(msgs[i])))
            i++
            continue
        }
        val run = ArrayList<ChatMessage>()
        while (i < msgs.size && msgs[i].role == Role.ASSISTANT) {
            run.add(msgs[i])
            i++
        }
        out.add(MessageGroup("t_${run.first().id}", run))
    }
    return out
}

/** LazyColumn 的渲染单元：一个 key 对应「一条」前端可见的消息回合 */
private data class MessageGroup(val key: String, val messages: List<ChatMessage>)

/**
 * Agent 的「工作块」—— 一轮回答里全部思考与工具调用的统一容器。
 *
 * 设计约定：
 *  1. **一个块装全部** —— 思考与工具调用不再各自成卡、在回答上方堆成一摞，
 *     一轮 Agent 工作收进同一个可折叠区域（多步时由 [AgentTurnItem] 把各步的
 *     调用合并后一并交给它，前端只出现一条工作流程卡片）；
 *  2. **执行中展开、完成后折叠** —— 任务进行中默认展开，这一轮的所有操作与
 *     思考都在同一个工作流程里看得见；全部步骤落定后自动折叠成一行摘要
 *     「N 步 · 已完成/有失败」，想回看流程点标题栏即可展开。流式中折叠态
 *     的一行状态也会实时回答「AI 现在在做什么」：跑工具时是
 *     「正在：<工具> <摘要>」，纯思考时是「思考中」；
 *  3. **展开 = 完整流程** —— 思维链 + 每步工具卡片，顺序即执行顺序；
 *  4. **产物不藏折叠** —— 构建产物是这一轮的交付物，折叠态下也常显在块尾，
 *     点开即预览、右侧导出，不用先展开工作流。
 */
@Composable
private fun AgentWorkBlock(
    blockKey: Any,
    calls: List<ToolCallRecord>,
    reasoning: String,
    isStreaming: Boolean,
    showReasoning: Boolean,
    sizeOf: (String) -> Long,
    onOpenArtifact: (String) -> Unit,
    onExportArtifact: (String) -> Unit,
) {
    val s = LocalStrings.current
    // 任务完成前默认**展开**：这一轮的所有操作与思考都要能在同一个工作流程里
    // 看到（用户明确要求「模型在完成任务之前的所有操作和思考都放在一个工作流程里」）。
    // 完成后由下面的 LaunchedEffect 收回折叠，只留摘要。
    var expanded by remember(blockKey) { mutableStateOf(isStreaming) }
    // 任务完成后收回折叠：流式期间可以展开盯过程，落定后回到摘要态，
    // 想看完整流程用户自己点开 —— 折叠的一行摘要已经说了「几步 · 成没成」
    LaunchedEffect(blockKey, isStreaming) {
        if (!isStreaming) expanded = false
    }
    val running = calls.firstOrNull { !it.isSettled }
    val failedCount = calls.count { it.status == ToolStatus.FAILED || it.status == ToolStatus.DENIED }
    val title = if (calls.isEmpty()) s.reasoning else s.workflowTitle

    // 折叠态的一行状态：跑的时候说「正在做什么」，跑完了说「几步 · 成没成」
    val statusLine = when {
        isStreaming && running != null -> buildString {
            append(s.workflowNow)
            append("：")
            append(s.toolTitle(running.name))
            if (running.summary.isNotBlank()) {
                append(" · ")
                append(running.summary)
            }
        }
        isStreaming -> s.thinking
        calls.isEmpty() -> ""
        failedCount > 0 -> "${calls.size} ${s.stepLabel} · ${s.workflowFailed}"
        else -> "${calls.size} ${s.stepLabel} · ${s.workflowDone}"
    }
    val dotColor = when {
        isStreaming && running != null -> MaterialTheme.colorScheme.primary
        isStreaming -> MaterialTheme.colorScheme.tertiary
        failedCount > 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tap(onClick = withHaptic { expanded = !expanded }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态点：跑 = 主色、想 = 第三色、成 = 次色、败 = 错误色
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) s.workflowCollapse else s.workflowExpand,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
        if (statusLine.isNotBlank()) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
        }

        if (expanded) {
            // 展开 = 完整流程：思维链（若开启）+ 每一步工具卡片，顺序即执行顺序
            if (showReasoning && reasoning.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                ReasoningPanel(reasoning = reasoning, streaming = isStreaming)
            }
            calls.forEach { call ->
                Spacer(Modifier.height(4.dp))
                ToolCallCard(
                    call = call,
                    sizeBytes = if (call.artifact.isNotBlank()) sizeOf(call.artifact) else 0L,
                    onOpenArtifact = onOpenArtifact,
                    onExportArtifact = onExportArtifact,
                )
            }
        } else {
            // 折叠态常显产物卡片：交付物不该藏在「先展开工作流」后面
            calls.forEach { call ->
                if (call.status == ToolStatus.SUCCESS && call.artifact.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    ArtifactCard(
                        path = call.artifact,
                        sizeBytes = sizeOf(call.artifact),
                        onOpen = onOpenArtifact,
                        onExport = onExportArtifact,
                    )
                }
            }
        }
    }
}

/**
 * 思维链面板。
 *
 * 默认**完全折叠**成一行 —— 手表上「思考过程」多数时候不是用户此刻要看的，
 * 但它是「模型到底在想什么」的唯一线索，所以留一个显眼、好点的入口。
 * 流式期间自动展开（这时它正是最有信息量的内容），用户手动操作过就以用户为准。
 */
@Composable
private fun ReasoningPanel(reasoning: String, streaming: Boolean) {
    val s = LocalStrings.current
    var manual by remember { mutableStateOf<Boolean?>(null) }
    val expanded = manual ?: streaming

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f))
            .tap(onClick = withHaptic { manual = !expanded })
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (streaming) s.thinking else s.reasoning,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) s.collapseReasoning else s.expandReasoning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 消息操作按钮：小而可点，四个字以内 */
@Composable
private fun MsgAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .tap(onClick = withHaptic(onClick))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** 产物在磁盘上的真实大小；读不到返回 0（界面会自动省略这一项） */
private fun artifactSizeOf(container: AppContainer, path: String): Long =
    runCatching { File(container.workspaceStore.rootDir(), path).length() }.getOrDefault(0L)

@Composable
private fun ChatBottomBar(
    settings: AppSettings,
    streaming: Boolean,
    onOpenInput: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onModelClick: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .cornerSafe()
            .clip(RoundedCornerShape(WatchDimens.cardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .tap(onClick = withHaptic(onOpenInput)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = s.inputHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }

        if (streaming) {
            StopButton(onClick = onStop)
        } else {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .tap(onClick = withHaptic(onSend)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = s.send,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .tap(onClick = withHaptic(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onErrorContainer)
        )
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.switchModel, style = MaterialTheme.typography.titleSmall) },
        text = {
            if (models.isEmpty()) {
                Text(s.none, style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.height(180.dp)) {
                    items(models, key = { it }) { model ->
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (model == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .tap(onClick = withHaptic { onPick(model) })
                                .padding(vertical = 7.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s.cancel) }
        },
    )
}

/**
 * 页面 key → 导航目标。
 *
 * 单独抽出来是因为 [open_screen] 工具传进来的是**稳定的字符串 key**，
 * 而不是 `Screen` 对象 —— 工具层不应该知道界面的类型。
 * 这里做唯一一次映射，键错了就静默忽略（工具侧已经校验过一遍）。
 */
private fun openScreen(nav: WatchNavigator, key: String) {
    val target = when (key) {
        com.dshwatch.domain.agent.UiActionChannel.SCREEN_SETTINGS -> Screen.Settings
        com.dshwatch.domain.agent.UiActionChannel.SCREEN_EXEC -> Screen.ExecSettings
        com.dshwatch.domain.agent.UiActionChannel.SCREEN_PROVIDERS -> Screen.Providers
        com.dshwatch.domain.agent.UiActionChannel.SCREEN_USAGE -> Screen.Usage
        com.dshwatch.domain.agent.UiActionChannel.SCREEN_WORKSPACE -> Screen.Workspace
        com.dshwatch.domain.agent.UiActionChannel.SCREEN_SESSIONS -> Screen.Sessions
        com.dshwatch.domain.agent.UiActionChannel.SCREEN_DIAGNOSTICS -> Screen.Diagnostics
        com.dshwatch.domain.agent.UiActionChannel.SCREEN_ABOUT -> Screen.About
        else -> return
    }
    nav.push(target)
}
