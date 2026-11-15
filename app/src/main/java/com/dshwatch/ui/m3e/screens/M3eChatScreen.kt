package com.dshwatch.ui.m3e.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.MaterialTheme as W3
import com.dshwatch.core.AppContainer
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.domain.ApprovalDecision
import com.dshwatch.domain.Choices
import com.dshwatch.domain.LiveDelta
import com.dshwatch.domain.Panels
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.AgentModeDialog
import com.dshwatch.ui.components.AskUserDialog
import com.dshwatch.ui.components.ChoiceButtons
import com.dshwatch.ui.components.ContextUsageDialog
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.PanelCard
import com.dshwatch.ui.components.RemoteApprovalDialog
import com.dshwatch.ui.components.TokenBadge
import com.dshwatch.ui.components.ToolApprovalDialog
import com.dshwatch.ui.components.shareFile
import com.dshwatch.ui.components.ShareResult
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.m3e.M3eChip
import com.dshwatch.ui.m3e.M3eConfirmDialog
import com.dshwatch.ui.m3e.M3eDialog
import com.dshwatch.ui.m3e.M3eDialogRow
import com.dshwatch.ui.m3e.M3eEmptyState
import com.dshwatch.ui.m3e.M3eFab
import com.dshwatch.ui.m3e.M3eIconTap
import com.dshwatch.ui.m3e.M3eList
import com.dshwatch.ui.m3e.M3eScaffold
import com.dshwatch.ui.m3e.rememberM3eListState
import com.dshwatch.ui.markdown.MarkdownText
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.chat.AgentWorkBlock
import com.dshwatch.ui.screens.chat.artifactSizeOf
import com.dshwatch.ui.screens.chat.groupMessages
import com.dshwatch.ui.screens.chat.openScreen
import com.dshwatch.ui.screens.chat.rememberTokensPerSecond
import com.dshwatch.ui.screens.scene.ScenePickerDialog
import com.dshwatch.ui.screens.scene.sceneIcon
import com.dshwatch.ui.theme.isM3eUi
import com.dshwatch.util.Fmt
import com.dshwatch.util.findActivity
import kotlinx.coroutines.launch

/**
 * M3E 的对话页 —— **独立实现**，不复用经典页的骨架与气泡。
 *
 * ## 结构对照
 *
 * | | 经典 | M3E |
 * |---|---|---|
 * | 容器 | `WatchScaffold` + `LazyColumn` | `M3eScaffold` + `TransformingLazyColumn` |
 * | 气泡 | 手绘圆角背景 + 角色标签行 | 官方 `Card`，标签并入卡内首行 |
 * | 用户色 | 固定蓝灰 / `secondaryContainer` | `primaryContainer`（跟着种子色走） |
 * | 底栏 | 圆屏浮 FAB / 方屏整条输入框 | 一律浮 FAB |
 * | 顶栏 | 四颗图标 + 标题 | 五颗图标一排 + 标题一行（stacked） |
 *
 * ## 为什么用户消息不再左右分栏
 *
 * 手机上「我靠右、AI 靠左」靠的是两侧 40dp 的留白做对称。手表屏宽 200dp，
 * 分出左右各 20dp 之后，两列都只剩 80dp —— 一行只放得下四个汉字。
 * Wear 的官方做法是**全宽卡片 + 换底色**：用户消息用 `primaryContainer`，
 * 助手用默认容器色，靠色相区分说话人，宽度一点都不浪费。
 *
 * ## 为什么保留经典页的几个弹窗
 *
 * 审批（`ToolApprovalDialog`）、提问（`AskUserDialog`）、模型选择这几处，
 * 内部逻辑与「任务是否可继续」强绑定；重画一遍只是把同样的状态机再写一次，
 * 出错代价却是一次误授权。它们本身已经走 Wear 的 `AlertDialog`，
 * 在 M3E 下外观是一致的 —— 所以这里直接复用。
 */
@Composable
fun M3eChatScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val engine = container.engine
    val scope = rememberCoroutineScope()
    val listState = rememberM3eListState()
    val context = LocalContext.current
    val activityView = LocalView.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val conversation by engine.conversation.collectAsStateWithLifecycle()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val streaming by engine.streaming.collectAsStateWithLifecycle()
    val banner by engine.banner.collectAsStateWithLifecycle()
    val ctxSnapshot by engine.context.collectAsStateWithLifecycle()
    val approval by engine.approval.collectAsStateWithLifecycle()
    val pendingQuestion by engine.question.collectAsStateWithLifecycle()
    val remoteApprovals by container.dshLink.approvals.collectAsStateWithLifecycle()
    val tps = rememberTokensPerSecond(engine, streaming)

    val provider = settings.activeProvider
    val codingScene = Scenes.usesAgent(settings.useScene)
    val ctxInsight = ctxSnapshot?.takeIf { it.conversationId == conversation?.id }?.insight

    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentDialog by remember { mutableStateOf(false) }
    var showScenePicker by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }

    val turns = remember(messages) { groupMessages(messages) }

    // 首次进入：自动打开最近一个会话
    LaunchedEffect(Unit) {
        if (engine.conversation.value == null) {
            container.conversationStore.index().firstOrNull()?.let { engine.open(it.id) }
        }
    }

    // 把「只有界面能做」的动作接上：open_screen / open_file / minimize
    DisposableEffect(Unit) {
        container.uiActions.previewFile = { path -> nav.push(Screen.WorkspacePreview(path)) }
        container.uiActions.openScreen = { key -> openScreen(nav, key) }
        container.uiActions.minimize = { context.findActivity()?.moveTaskToBack(true) }
        onDispose {
            container.uiActions.previewFile = null
            container.uiActions.openScreen = null
            container.uiActions.minimize = null
        }
    }

    // 生成期间保持亮屏
    LaunchedEffect(streaming, settings.keepScreenOn) {
        activityView.keepScreenOn = streaming && settings.keepScreenOn
    }

    /*
     * 跟随最新一条消息。
     *
     * `TransformingLazyColumnState.scrollToItem` 会把目标项放到**锚点**
     * （屏幕圆心），不是列表底部。所以这里滚的是最后那个 56dp 的占位项 ——
     * 它落到圆心时，最新一条消息正好在圆心上方那半屏里，既滚到了底，
     * 又不会被浮在底部的 FAB 压住。
     *
     * 只在 `autoScroll` 打开时跟随：用户正在往回翻历史的时候自动跳回底部，
     * 是比"没跟上"更让人恼火的事。
     */
    LaunchedEffect(turns.size, live?.text?.length, streaming) {
        if (!settings.autoScroll) return@LaunchedEffect
        if (turns.isEmpty()) return@LaunchedEffect
        runCatching { listState.scrollToItem(turns.size + 1) }
    }

    val hasArtifact = remember(messages) {
        messages.any { msg -> msg.toolCalls.any { it.name == "build_project" && it.status == com.dshwatch.domain.model.ToolStatus.SUCCESS } }
    }

    M3eScaffold(
        // 空会话不显示标题：空态自己已经说了「开始一段 vibe coding」，
        // 再加一行「新对话」是同义重复，而手表一屏只有 200dp 的内容高度。
        title = if (messages.isEmpty()) "" else conversation?.displayTitle ?: s.appName,
        stackedBar = true,
        onBack = null,
        actions = {
            val dense = LocalDeviceRound()
            val diameter = if (dense) 36.dp else 44.dp
            val iconSize = if (dense) 24.dp else 26.dp
            val tap: @Composable (androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit) -> Unit =
                { icon, desc, onClick ->
                    M3eIconTap(
                        icon = icon,
                        contentDescription = desc,
                        onClick = onClick,
                        diameter = diameter,
                        iconSize = iconSize,
                    )
                }
            tap(Icons.Outlined.History, s.navSessions) { nav.push(Screen.Sessions) }
            if (codingScene) {
                tap(Icons.Outlined.Add, s.newChat) {
                    scope.launch { engine.newConversation(settings, scene = Scenes.CODING) }
                }
                tap(sceneIcon(settings.useScene), "切换模式") { showScenePicker = true }
            }
            tap(Icons.Outlined.PieChart, s.ctxTitle) { showContext = true }
            tap(Icons.Outlined.Settings, s.navSettings) { nav.push(Screen.Settings) }
        },
        bottomOverlay = true,
        bottom = {
            /*
             * 底部只有一颗按钮，语义随状态变。
             *
             * 生成中变成「停止」而不是「输入」：这一屏上用户最可能要做的
             * 就是叫停 —— 尤其在表上等了几十秒之后。保持同一位置，
             * 拇指已经在那儿了。
             */
            if (streaming) {
                M3eFab(
                    icon = Icons.Filled.Stop,
                    contentDescription = s.stop,
                    danger = true,
                    onClick = { engine.stop() },
                )
            } else {
                M3eFab(
                    icon = Icons.Outlined.Keyboard,
                    contentDescription = s.inputHint,
                    onClick = { nav.push(Screen.Input()) },
                )
            }
        },
    ) {
        if (provider == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "还没有配置供应商",
                    style = W3.typography.titleSmall,
                    color = W3.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                M3eFab(
                    icon = Icons.Filled.Add,
                    contentDescription = s.addProvider,
                    onClick = { nav.push(Screen.Providers) },
                )
            }
            return@M3eScaffold
        }

        if (messages.isEmpty()) {
            M3eList(state = listState, modifier = Modifier.weight(1f)) {
                item(key = "__empty__") {
                    M3eEmptyState(
                        title = if (codingScene) s.chatEmptyTitle else s.roleplayEmptyTitle,
                        hint = if (codingScene) s.chatEmptyHint else s.roleplayEmptyHint,
                    )
                }
                if (settings.biliEnabled && codingScene) {
                    item(key = "__bili__") {
                        M3eChip(
                            text = "刷一轮 B站",
                            onClick = {
                                engine.send(
                                    "刷一轮 B站：按我关注的关键词挑几条真正值得看的，读字幕判断，" +
                                        "把挑中的写进今天的日报"
                                )
                            },
                        )
                    }
                }
            }
            return@M3eScaffold
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            M3eList(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 28.dp),
            ) {
                /*
                 * 顶部信息条：模型与 Agent 两张 Chip。
                 *
                 * 作为列表第一项而不是钉在顶上 —— 钉住时它要占掉 30dp 固定高度，
                 * 而手表正文只有 180dp 左右。滚走之后想看再翻回顶部即可。
                 */
                item(key = "__info__") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            M3eChip(
                                text = provider.let { p ->
                                    val model = conversation?.model?.ifBlank { p.defaultModel }.orEmpty()
                                    if (model.isBlank()) p.name else model
                                },
                                selected = true,
                                onClick = { showModelPicker = true },
                            )
                            if (codingScene) {
                                M3eChip(
                                    text = settings.agentMode.label,
                                    selected = settings.agentMode != AgentMode.OFF,
                                    onClick = { showAgentDialog = true },
                                )
                            }
                        }
                        if (streaming) {
                            Text(
                                text = if (tps <= 0) s.generating else "$tps t/s",
                                style = W3.typography.labelSmall,
                                color = W3.colorScheme.secondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        banner?.let { b ->
                            Spacer(Modifier.height(4.dp))
                            BannerStrip(text = b.text, onDismiss = { engine.dismissBanner() })
                        }
                        if (hasArtifact) {
                            Spacer(Modifier.height(4.dp))
                            M3eChip(
                                text = s.navWorkspace,
                                selected = true,
                                onClick = { nav.push(Screen.Workspace) },
                            )
                        }
                    }
                }

                items(turns.size, key = { turns[it].key }) { index ->
                    val turn = turns[index]
                    val isLast = index == turns.lastIndex
                    val msgs = turn.messages
                    val last = msgs.last()
                    val isLive = live.messageId == last.id
                    M3eTurn(
                        messages = msgs,
                        live = if (isLive) live else null,
                        settings = settings,
                        showWork = codingScene,
                        canPick = isLast,
                        onChoose = { c -> if (c.custom) nav.push(Screen.Input()) else engine.send(c.raw) },
                        onCopy = { text ->
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                        },
                        onRegenerate = { engine.regenerate(last.id) },
                        onDelete = { pendingDelete = last },
                        container = container,
                        onOpenArtifact = { path -> nav.push(Screen.WorkspacePreview(path)) },
                        onExportArtifact = { path ->
                            val result = shareFile(context, container.workspaceStore.resolveForUi(path))
                            if (result is ShareResult.Fail) {
                                android.widget.Toast.makeText(context, result.reason, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onOpenHtml = { html -> nav.push(Screen.HtmlView(html)) },
                        onScrollBy = { d -> listState.dispatchRawDelta(d) },
                    )
                }

                item(key = "__tail__") { Spacer(Modifier.height(56.dp)) }
            }
        }
    }

    // ---------------- 弹窗 ----------------

    pendingDelete?.let { target ->
        M3eConfirmDialog(
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

    remoteApprovals.firstOrNull()?.let { pending ->
        RemoteApprovalDialog(
            pending = pending,
            onDecision = { allow ->
                scope.launch { runCatching { container.dshLink.respondApproval(pending.eventId, allow) } }
            },
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
                scope.launch { container.settingsRepo.update { it.copy(agentMode = mode) } }
            },
            onDismiss = { showAgentDialog = false },
        )
    }

    if (showModelPicker) {
        M3eDialog(
            onDismissRequest = { showModelPicker = false },
            title = s.switchModel,
            content = {
                if (provider?.models.isNullOrEmpty()) {
                    Text(s.none, style = W3.typography.bodySmall)
                } else {
                    provider?.models.orEmpty().take(12).forEach { model ->
                        M3eDialogRow(
                            title = model,
                            selected = model == conversation?.model,
                            onClick = {
                                showModelPicker = false
                                scope.launch { engine.overrideModel(model) }
                            },
                        )
                    }
                }
            },
            buttons = { M3eDialogButton(s.cancel) { showModelPicker = false } },
        )
    }

    if (showScenePicker) {
        ScenePickerDialog(
            current = settings.useScene,
            onPick = { id ->
                showScenePicker = false
                if (id == Scenes.of(settings.useScene)) return@ScenePickerDialog
                scope.launch { container.settingsRepo.update { it.copy(useScene = id) } }
                when (id) {
                    Scenes.TAVERN -> { nav.popToRoot(); nav.push(Screen.TavernHome) }
                    Scenes.WRITING -> { nav.popToRoot(); nav.push(Screen.WritingHome) }
                    else -> nav.popToRoot()
                }
            },
            onDismiss = { showScenePicker = false },
        )
    }

    if (showContext) {
        val snapshot = ctxInsight
        if (snapshot != null) {
            ContextUsageDialog(insight = snapshot, onDismiss = { showContext = false })
        } else {
            GlassDialog(
                onDismissRequest = { showContext = false },
                title = s.ctxTitle,
                content = {
                    Text(
                        text = if (messages.isEmpty()) {
                            s.ctxEmpty
                        } else {
                            "这条会话的上下文构成会在下一次发送时计算"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                buttons = { GlassDialogButton(s.close, onClick = { showContext = false }) },
            )
        }
    }
}

/**
 * 一个回合：可能是一条消息，也可能是 Agent 的多步合并。
 *
 * 多步时只画一张卡：连续助手消息在 `groupMessages` 里已经合并，
 * 这里再把各步的工具调用拼起来交给同一个工作块 —— 前端因此只出现
 * 「一条回答 + 一个工作流程」，而不是每步一张卡。
 */
@Composable
private fun M3eTurn(
    messages: List<ChatMessage>,
    live: LiveDelta?,
    settings: AppSettings,
    showWork: Boolean,
    canPick: Boolean,
    onChoose: (Choices.Choice) -> Unit,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    container: AppContainer,
    onOpenArtifact: (String) -> Unit,
    onExportArtifact: (String) -> Unit,
    onOpenHtml: (String) -> Unit,
    onScrollBy: (Float) -> Unit,
) {
    val last = messages.last()
    val first = messages.first()
    val single = messages.size == 1
    val isUser = first.role == Role.USER
    val isStreaming = live != null
    val body = live?.text ?: last.content
    val reasoning = live?.reasoning ?: last.reasoning

    // 编程场景才画思维链 / 工具卡；酒馆里那些是噪音
    val allCalls = if (showWork) messages.flatMap { it.toolCalls } else emptyList()
    val showsWork = showWork && !isUser && (allCalls.isNotEmpty() || (settings.showReasoning && reasoning.isNotBlank()))

    /*
     * 正文的三层解析，顺序不能变。
     *
     * 1. **整段 HTML 文档**：不当作正文渲染（221dp 宽里缩一个网页没法看），
     *    只留一张入口卡，点开全屏。流式期间不扫 —— 每个字跑一次正则，
     *    在表上直接就是掉帧。
     * 2. **```panel 围栏**：抽成原生面板。流式期间半截围栏还没闭合，解析没有意义。
     * 3. **尾部 A/B/C 选项**：拆成按钮，正文只留前面那段。
     *
     * 三层都做完之后的才是"正文"。
     */
    val htmlDoc = remember(body, isStreaming) {
        if (isStreaming) null else com.dshwatch.ui.html.HtmlBlocks.find(body)
    }
    val rawText = if (htmlDoc != null) com.dshwatch.ui.html.HtmlBlocks.strip(body, htmlDoc) else body
    val panelRes = remember(rawText, isStreaming) {
        if (isStreaming) Panels.Result(rawText, emptyList()) else Panels.extract(rawText)
    }
    val afterPanel = if (panelRes.panels.isNotEmpty()) panelRes.body else rawText
    val parsed = remember(afterPanel) { Choices.parse(afterPanel) }
    val bodyText = if (parsed.hasChoices) parsed.body else afterPanel
    var viewingMedia by remember(last.id) {
        mutableStateOf<com.dshwatch.domain.model.MediaRef?>(null)
    }

    M3eMessageCard(
        isUser = isUser,
        label = if (isUser) "我" else "DSH",
        streaming = isStreaming,
        streamingLabel = if (reasoning.isNotBlank() && body.isBlank()) LocalStrings.current.thinking else LocalStrings.current.generating,
        stepIndex = if (single) last.stepIndex else 0,
        errorText = when {
            !single -> ""
            last.status == MessageStatus.ERROR -> last.error.ifBlank { LocalStrings.current.sendFailed }
            last.status == MessageStatus.STOPPED -> LocalStrings.current.stopped
            else -> ""
        },
    ) {
        if (showsWork) {
            AgentWorkBlock(
                blockKey = messages.first().id,
                calls = allCalls,
                reasoning = reasoning,
                isStreaming = isStreaming,
                showReasoning = settings.showReasoning,
                sizeOf = { path -> artifactSizeOf(container, path) },
                onOpenArtifact = onOpenArtifact,
                onExportArtifact = onExportArtifact,
            )
            Spacer(Modifier.height(6.dp))
        }

        if (first.attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                first.attachments.forEach { att ->
                    Text(
                        text = "📄 ${att.name} · ${att.summary()}",
                        style = W3.typography.labelSmall,
                        color = if (att.ok) W3.colorScheme.primary else W3.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (bodyText.isNotBlank()) Spacer(Modifier.height(6.dp))
        }

        if (last.media.isNotEmpty()) {
            com.dshwatch.ui.media.MediaStrip(media = last.media, onOpen = { viewingMedia = it })
            if (bodyText.isNotBlank()) Spacer(Modifier.height(6.dp))
        }

        if (htmlDoc != null) {
            com.dshwatch.ui.html.InlineHtml(
                html = htmlDoc.html,
                onOpenFull = onOpenHtml,
                onDrag = onScrollBy,
            )
            if (bodyText.isNotBlank()) Spacer(Modifier.height(6.dp))
        }

        if (bodyText.isNotBlank()) {
            SelectionContainer {
                if (isUser) {
                    Text(
                        text = bodyText,
                        style = W3.typography.bodyMedium,
                        color = LocalContentColor.current,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth()) { MarkdownText(text = bodyText) }
                }
            }
        } else if (isStreaming) {
            Text(text = "…", style = W3.typography.bodyMedium, color = W3.colorScheme.onSurfaceVariant)
        }

        panelRes.panels.forEach { p ->
            Spacer(Modifier.height(6.dp))
            PanelCard(panel = p)
        }

        if (!isUser && parsed.hasChoices && !isStreaming) {
            Spacer(Modifier.height(6.dp))
            ChoiceButtons(choices = parsed.choices, enabled = canPick, onPick = onChoose)
        }

        if (!isStreaming && bodyText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                M3eCardAction(LocalStrings.current.copy) { onCopy(body) }
                if (!isUser) {
                    M3eCardAction(LocalStrings.current.regenerate) { onRegenerate() }
                    M3eCardAction(LocalStrings.current.delete) { onDelete() }
                }
            }
        }

        viewingMedia?.let { ref ->
            com.dshwatch.ui.media.MediaViewer(ref = ref, onClose = { viewingMedia = null })
        }
    }
}

/**
 * 一条消息的卡片。
 *
 * 说话人靠**容器色**区分，不靠左右对齐 —— 手表屏宽经不起两侧留白。
 * 用户消息用 `primaryContainer`（跟着种子色走），助手用默认容器色。
 */
@Composable
private fun M3eMessageCard(
    isUser: Boolean,
    label: String,
    streaming: Boolean,
    streamingLabel: String,
    stepIndex: Int,
    errorText: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    /*
     * 容器用 StaticSurface 而不是 `Card`。
     *
     * Wear 的 `Card` **只有带 `onClick` 的重载** —— 传一个空 lambda 虽然能编译，
     * 但会给整块卡片挂上涟漪与「可点」语义，读屏会念成按钮。消息卡不是按钮。
     *
     * 视觉上与 Card 完全一致：同样的 24dp 圆角、同样的容器色阶，只是不带交互。
     */
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (isUser) W3.colorScheme.primaryContainer else W3.colorScheme.surfaceContainerHigh
            )
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = W3.typography.labelSmall,
                color = W3.colorScheme.onSurfaceVariant,
            )
            if (stepIndex > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "第 $stepIndex 步",
                    style = W3.typography.labelSmall,
                    color = W3.colorScheme.outline,
                )
            }
            if (streaming) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = streamingLabel,
                    style = W3.typography.labelSmall,
                    color = W3.colorScheme.secondary,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        /*
         * 正文色跟着卡片底色走，而不是写死 `onSurface`。
         *
         * 用户消息的底是 `primaryContainer`，正文若还用 `onSurface`，
         * 换一个深色种子就会变成「深底深字」—— 这一条在经典页踩过一次。
         */
        CompositionLocalProvider(
            LocalContentColor provides if (isUser) {
                W3.colorScheme.onPrimaryContainer
            } else {
                W3.colorScheme.onSurface
            },
        ) {
            // 再包一层 Column 是为了拿到 ColumnScope：content 的 receiver 是它，
            // 而 CompositionLocalProvider 的槽没有 receiver。
            Column { content() }
        }
        if (errorText.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = errorText,
                style = W3.typography.labelSmall,
                color = W3.colorScheme.error,
            )
        }
    }
}

/** 卡片内的小动作：一行文字，48dp 命中区由 TextButton 给 */
@Composable
private fun M3eCardAction(text: String, onClick: () -> Unit) {
    androidx.wear.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Text(text = text, style = W3.typography.labelSmall, maxLines = 1)
    }
}

/** 错误提示条：一条窄卡片，右侧「关闭」 */
@Composable
private fun BannerStrip(text: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(W3.colorScheme.errorContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = W3.typography.labelSmall,
                color = W3.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            M3eCardAction(LocalStrings.current.close, onDismiss)
        }
    }
}

/**
 * 当前屏幕是不是圆屏。
 *
 * 五颗动作在圆屏上超过弦宽，要降到 36dp；方屏一直是 44dp。
 * 这里单独取一次而不是各页各自判断，避免同一排图标在不同页面尺寸不一。
 */
@Composable
private fun LocalDeviceRound(): Boolean =
    com.dshwatch.ui.theme.LocalDeviceProfile.current.isRound

/** 对话框底部的取消，统一 48dp 高度 */
@Composable
private fun M3eDialogButton(text: String, onClick: () -> Unit) {
    androidx.wear.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = text, style = W3.typography.labelLarge, maxLines = 1)
    }
}
