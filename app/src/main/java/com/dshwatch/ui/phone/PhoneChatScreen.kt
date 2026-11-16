package com.dshwatch.ui.phone

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.attachment.AttachmentStore
import com.dshwatch.domain.Choices
import com.dshwatch.domain.LiveDelta
import com.dshwatch.domain.Panels
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.model.AttachmentKinds
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MediaRef
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.AskUserDialog
import com.dshwatch.ui.components.ChoiceButtons
import com.dshwatch.ui.components.PanelCard
import com.dshwatch.ui.components.RemoteApprovalDialog
import com.dshwatch.ui.components.ToolApprovalDialog
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.markdown.MarkdownText
import com.dshwatch.ui.media.MediaStrip
import com.dshwatch.ui.media.MediaViewer
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.screens.chat.AgentWorkBlock
import com.dshwatch.ui.screens.chat.artifactSizeOf
import com.dshwatch.ui.screens.chat.groupMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机版的对话页。
 *
 * ## 和手表版差在哪
 *
 * | | 手表 | 手机 |
 * |---|---|---|
 * | 骨架 | 圆屏/方屏各自算安全区 | 普通全宽，顶栏 + 列表 + 输入栏 |
 * | 气泡 | 全宽换底色（屏宽不够左右分栏） | 用户靠右、助手全宽 |
 * | 输入 | 进二级页，整屏一个输入框 | 常驻底部，边看边打 |
 * | 工具条 | 顶栏一排图标 | 标题 + 「新建 / 更多」两项 |
 *
 * 「用户靠右」这件事在手机上是**必须**的：411dp 宽的屏，右侧留 15% 就能
 * 一眼分出发言人，而手表的 200dp 上这么分会把两列都压到 80dp。
 * 所以手机版恢复聊天应用的标准形态，而不是把手机版当成「放大版手表」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneChatScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val engine = container.engine

    val conversation by engine.conversation.collectAsStateWithLifecycle()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val streaming by engine.streaming.collectAsStateWithLifecycle()
    val banner by engine.banner.collectAsStateWithLifecycle()
    val approval by engine.approval.collectAsStateWithLifecycle()
    val pendingQuestion by engine.question.collectAsStateWithLifecycle()
    val remoteApprovals by container.dshLink.approvals.collectAsStateWithLifecycle()

    val provider = settings.activeProvider
    val codingScene = Scenes.usesAgent(settings.useScene)
    val turns = remember(messages) { groupMessages(messages) }
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
    val attachments = remember { mutableStateListOf<Attachment>() }
    var attachBusy by remember { mutableStateOf(false) }
    var attachNote by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (engine.conversation.value == null) {
            container.conversationStore.index().firstOrNull()?.let { engine.open(it.id) }
        }
    }

    /*
     * 跟随最新消息。
     *
     * 用 `animateScrollToItem` 而不是 `scrollToItem`：手机上滚动是有惯性的，
     * 一帧跳到尾部的观感像「画面闪了一下」。流式期间每个增量都会触发这里，
     * 但 `turns.size` / 文本长度变化时才会重跑，节流交给 Compose 的跳过机制。
     */
    LaunchedEffect(turns.size, live?.text?.length, streaming) {
        if (!settings.autoScroll) return@LaunchedEffect
        if (turns.isEmpty()) return@LaunchedEffect
        runCatching { listState.animateScrollToItem(turns.lastIndex) }
    }

    fun submit() {
        val value = input.trim()
        if (value.isEmpty() && attachments.isEmpty()) return
        if (engine.send(value, attachments.filter { it.ok })) {
            input = ""
            attachments.clear()
            attachNote = ""
        } else {
            // 上一轮还在生成：内容留在框里，别把用户刚打的字吃掉
            attachNote = "上一轮还在生成，等它写完再发"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        if (attachments.size >= AttachmentKinds.MAX_FILES) {
            attachNote = "一次最多带 ${AttachmentKinds.MAX_FILES} 个文件"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            attachBusy = true
            val att = withContext(Dispatchers.IO) {
                AttachmentStore.import(context, context.filesDir, uri)
            }
            attachments.add(att)
            if (!att.ok) attachNote = "${att.name}：${att.error}"
            attachBusy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = conversation?.displayTitle?.ifBlank { s.appName } ?: s.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val model = conversation?.model?.ifBlank { provider?.defaultModel.orEmpty() }.orEmpty()
                    if (model.isNotBlank()) {
                        Text(
                            text = if (streaming) "$model · ${s.generating}" else model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = {
                    scope.launch { engine.newConversation(settings, scene = Scenes.CODING) }
                }) {
                    Icon(Icons.Filled.Add, contentDescription = s.newChat)
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = s.more)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(s.switchModel) },
                        onClick = { showMenu = false; showModelPicker = true },
                    )
                    DropdownMenuItem(
                        text = { Text(s.ctxTitle) },
                        onClick = { showMenu = false; showContext = true },
                    )
                    DropdownMenuItem(
                        text = { Text("清空这一轮上下文") },
                        onClick = { showMenu = false; scope.launch { engine.markContextCut() } },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(s.navSettings) },
                        onClick = { showMenu = false; nav.select(PhoneTab.Settings) },
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (provider == null) {
            NoProviderHint(onOpenProviders = { nav.open(Screen.Providers) })
            return@Column
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyChat(
                    codingScene = codingScene,
                    onSeed = { text -> engine.send(text) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(turns.size, key = { turns[it].key }) { index ->
                        val turn = turns[index]
                        val msgs = turn.messages
                        val last = msgs.last()
                        PhoneTurn(
                            messages = msgs,
                            live = if (live?.messageId == last.id) live else null,
                            settings = settings,
                            showWork = codingScene,
                            canPick = index == turns.lastIndex,
                            onChoose = { c ->
                                if (c.custom) {
                                    input = ""
                                } else {
                                    engine.send(c.raw)
                                }
                            },
                            onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                            onRegenerate = { engine.regenerate(last.id) },
                            onDelete = { pendingDelete = last },
                            container = container,
                            onOpenArtifact = { path -> nav.open(Screen.WorkspacePreview(path)) },
                            onOpenHtml = { html -> nav.open(Screen.HtmlView(html)) },
                            onScrollBy = { d -> listState.dispatchRawDelta(d) },
                        )
                    }
                }
            }
        }

        banner?.let { b ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = b.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { engine.dismissBanner() }) { Text("知道了") }
            }
        }

        PhoneComposer(
            input = input,
            onInput = { input = it },
            streaming = streaming,
            busy = attachBusy || streaming,
            note = attachNote,
            attachments = attachments,
            onRemove = { attachments.remove(it) },
            onPickFile = { runCatching { picker.launch(arrayOf("*/*")) } },
            onSend = { submit() },
            onStop = { engine.stop() },
        )
    }

    // ---------------- 弹窗 ----------------

    pendingDelete?.let { target ->
        androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(s.deleteMessage) },
                text = { Text(com.dshwatch.util.Fmt.preview(target.content, 120)) },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { engine.deleteMessage(target.id) }
                        pendingDelete = null
                    }) { Text(s.delete) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text(s.cancel) }
                },
            )
    }

    approval?.let { pending ->
        ToolApprovalDialog(
            pending = pending,
            onDecision = { decision -> engine.resolveApproval(decision) },
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

    if (showModelPicker) {
        PhoneModelPicker(
            models = provider?.models.orEmpty(),
            current = conversation?.model.orEmpty(),
            onPick = { model ->
                showModelPicker = false
                scope.launch { engine.overrideModel(model) }
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showContext) {
        val snapshot = engine.context.value?.takeIf { it.conversationId == conversation?.id }?.insight
        if (snapshot != null) {
            com.dshwatch.ui.components.ContextUsageDialog(
                insight = snapshot,
                onDismiss = { showContext = false },
            )
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showContext = false },
                title = { Text(s.ctxTitle) },
                text = { Text("这条会话的上下文构成会在下一次发送时计算") },
                confirmButton = {
                    TextButton(onClick = { showContext = false }) { Text(s.close) }
                },
            )
        }
    }
}

/** 没有供应商时整屏只剩一件事可做：去配一个 */
@Composable
private fun NoProviderHint(onOpenProviders: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有配置供应商",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "填一个 API Key 就能开始。内置通道有免费额度，不填也能先看看界面。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.FilledTonalButton(onClick = onOpenProviders) {
            Text("去配置")
        }
    }
}

@Composable
private fun EmptyChat(codingScene: Boolean, onSeed: (String) -> Unit) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (codingScene) s.chatEmptyTitle else s.roleplayEmptyTitle,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (codingScene) s.chatEmptyHint else s.roleplayEmptyHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            "帮我写一个能跑的网页小工具",
            "把这个仓库的结构讲清楚",
            "查一下今天的天气，用一句话说",
        ).forEach { seed ->
            Surface(
                onClick = { onSeed(seed) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    text = seed,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * 输入栏。
 *
 * 常驻底部、多行、回车发送（`ImeAction.Send`）。生成中把右侧按钮换成「停止」——
 * 位置不变，拇指不用找。
 */
@Composable
private fun PhoneComposer(
    input: String,
    onInput: (String) -> Unit,
    streaming: Boolean,
    busy: Boolean,
    note: String,
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val s = LocalStrings.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 18.dp, top = 8.dp, end = 18.dp),
                )
            }
            if (attachments.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    attachments.forEach { att ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "📄 ${att.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (att.ok) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onRemove(att) }) {
                                Text("移除", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(s.inputHint) },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = { if (streaming) onStop() else onSend() },
                    enabled = streaming || input.isNotBlank() || attachments.isNotEmpty(),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (streaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (streaming) s.stop else s.send,
                    )
                }
            }
        }
    }
}

/**
 * 一个回合。
 *
 * 用户消息走**右对齐气泡**，助手消息**全宽不套气泡** —— 这是手机聊天应用的
 * 通行做法：助手回复常常是一整页带标题、列表、代码的内容，套进气泡里
 * 会让每一段都窄掉 20%，而气泡本来是为了区分「谁在说」，不是装长文。
 */
@Composable
private fun PhoneTurn(
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
    onOpenHtml: (String) -> Unit,
    onScrollBy: (Float) -> Unit,
) {
    val s = LocalStrings.current
    val first = messages.first()
    val last = messages.last()
    val single = messages.size == 1
    val isUser = first.role == Role.USER
    val isStreaming = live != null
    val body = live?.text ?: last.content
    val reasoning = live?.reasoning ?: last.reasoning

    val allCalls = if (showWork) messages.flatMap { it.toolCalls } else emptyList()
    val showsWork = showWork && !isUser &&
        (allCalls.isNotEmpty() || (settings.showReasoning && reasoning.isNotBlank()))

    // 三层解析与手表版同序：HTML → panel → 尾部选项
    val htmlDoc = remember(body, isStreaming) {
        if (isStreaming) null else com.dshwatch.ui.html.HtmlBlocks.findRenderable(body)
    }
    val rawText = if (htmlDoc != null) com.dshwatch.ui.html.HtmlBlocks.strip(body, htmlDoc) else body
    val panelRes = remember(rawText, isStreaming) {
        if (isStreaming) Panels.Result(rawText, emptyList()) else Panels.extract(rawText)
    }
    val afterPanel = if (panelRes.panels.isNotEmpty()) panelRes.body else rawText
    val parsed = remember(afterPanel) { Choices.parse(afterPanel) }
    val bodyText = if (parsed.hasChoices) parsed.body else afterPanel
    var viewingMedia by remember(last.id) { mutableStateOf<MediaRef?>(null) }

    if (isUser) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (first.attachments.isNotEmpty()) {
                        first.attachments.forEach { att ->
                            Text(
                                text = "📄 ${att.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (att.ok) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (bodyText.isNotBlank()) Spacer(Modifier.height(6.dp))
                    }
                    if (bodyText.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = bodyText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = { onCopy(body) }) { Text(s.copy, style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onDelete) { Text(s.delete, style = MaterialTheme.typography.labelSmall) }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showsWork) {
            AgentWorkBlock(
                blockKey = first.id,
                calls = allCalls,
                reasoning = reasoning,
                isStreaming = isStreaming,
                showReasoning = settings.showReasoning,
                sizeOf = { path -> artifactSizeOf(container, path) },
                onOpenArtifact = onOpenArtifact,
                onExportArtifact = { },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (!single && last.stepIndex > 0) {
            Text(
                text = "第 ${last.stepIndex} 步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (last.media.isNotEmpty()) {
            MediaStrip(media = last.media, onOpen = { viewingMedia = it })
            if (bodyText.isNotBlank()) Spacer(Modifier.height(8.dp))
        }

        if (htmlDoc != null) {
            com.dshwatch.ui.html.InlineHtml(
                html = htmlDoc.html,
                onOpenFull = onOpenHtml,
                onDrag = onScrollBy,
                maxHeight = 420.dp,
            )
            if (bodyText.isNotBlank()) Spacer(Modifier.height(8.dp))
        }

        if (bodyText.isNotBlank()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                MarkdownText(text = bodyText)
            }
        } else if (isStreaming) {
            Text(
                text = if (reasoning.isNotBlank()) s.thinking else s.generating,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        panelRes.panels.forEach { p ->
            Spacer(Modifier.height(8.dp))
            PanelCard(panel = p)
        }

        if (parsed.hasChoices && !isStreaming) {
            Spacer(Modifier.height(8.dp))
            ChoiceButtons(choices = parsed.choices, enabled = canPick, onPick = onChoose)
        }

        when {
            !single -> Unit
            last.status == MessageStatus.ERROR -> Text(
                text = last.error.ifBlank { s.sendFailed },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            last.status == MessageStatus.STOPPED -> Text(
                text = s.stopped,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (!isStreaming && bodyText.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { onCopy(body) }) { Text(s.copy, style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onRegenerate) { Text(s.regenerate, style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onDelete) { Text(s.delete, style = MaterialTheme.typography.labelSmall) }
            }
        }

        viewingMedia?.let { ref ->
            MediaViewer(ref = ref, onClose = { viewingMedia = null })
        }
    }
}

/** 模型列表：手机上就是一张居中的单选列表，比手表的横向翻页好点得多 */
@Composable
private fun PhoneModelPicker(
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.switchModel) },
        text = {
            if (models.isEmpty()) {
                Text(s.none, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn {
                    items(models.size) { i ->
                        val model = models[i]
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = model,
                                    fontWeight = if (model == current) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = { onPick(model) },
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
