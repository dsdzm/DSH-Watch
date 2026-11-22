package com.dshwatch.ui.phone

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.MediaRef
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.domain.model.ReasoningEffort
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.model.ToolStatus
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.ArtifactCard
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
import com.dshwatch.ui.screens.chat.workStepsOf
import com.dshwatch.ui.screens.chat.artifactSizeOf
import com.dshwatch.ui.screens.chat.groupMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * | 历史 | 独立一页 | 左侧抽屉，随时拉开（长按会话出操作） |
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
    var showAttach by remember { mutableStateOf(false) }
    var showSceneSheet by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
    // 编辑：改完直接重跑这一轮，后面的回答按「提问变了」作废
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val attachments = remember { mutableStateListOf<Attachment>() }
    var attachBusy by remember { mutableStateOf(false) }
    var attachNote by remember { mutableStateOf("") }

    // 历史抽屉
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var history by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var infoTarget by remember { mutableStateOf<Conversation?>(null) }

    /*
     * 会话列表在**抽屉打开时**才读。
     *
     * `index()` 要扫一遍 conversations 目录并解每条的头信息，放在首帧或每次重组
     * 都会白花几毫秒；抽屉一拉就重读，既省掉这份开销，也保证「刚发完一条、
     * 标题自动变了」能在下一次拉开时看到。
     */
    LaunchedEffect(historyVersion, drawerState.isOpen) {
        if (drawerState.isOpen || history.isEmpty()) {
            history = runCatching { container.conversationStore.index() }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) {
        if (engine.conversation.value == null) {
            /*
             * 只自动续上**编程场景**的会话。
             *
             * 会话列表按时间倒序，第一条很可能是昨晚的酒馆剧情。把那条塞进
             * 这一页，用户看到的就是「一段没有角色的散文」—— 而且他以为是
             * 对话记录坏了。酒馆会话有自己的渲染页，从历史里点进去才对。
             */
            container.conversationStore.index()
                .firstOrNull { Scenes.of(it.scene) == Scenes.CODING }
                ?.let { engine.open(it.id) }
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

    /*
     * 顶栏那颗模型药丸的文案：模型名 + 思考强度。
     *
     * 强度必须出现在这里，不能只藏在模型弹窗里 —— 用户调成「高」之后，
     * 看到的每一条回复都更慢更长，需要有个地方能解释「为什么今天这么慢」。
     */
    val modelName = conversation?.model?.ifBlank { provider?.defaultModel.orEmpty() }.orEmpty()
    val effortLabel = when (provider?.reasoningEffort ?: ReasoningEffort.DEFAULT) {
        ReasoningEffort.LOW -> "低"
        ReasoningEffort.MEDIUM -> "中"
        ReasoningEffort.HIGH -> "高"
        else -> ""
    }
    val modelLabel = listOf(modelName.ifBlank { s.switchModel }, effortLabel)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    /*
     * 换场景：只落盘 `useScene`，然后把栈收回根。
     *
     * 不在手机版里直接 push 酒馆首页 —— 根屏本来就按 `useScene` 分发
     * （见 `PhoneRoot`），push 上去会变成「同一页叠了两层」，
     * 返回键按一次毫无变化，第二次直接退出 App。
     */
    fun switchScene(id: String) {
        if (id != Scenes.of(settings.useScene)) {
            scope.launch { container.settingsRepo.update { it.copy(useScene = id) } }
        }
        nav.popToRoot()
    }

    fun submit() {
        val value = input.trim()
        if (value.isEmpty() && attachments.isEmpty()) return
        if (engine.send(value, attachments.filter { it.ok })) {
            input = ""
            attachments.clear()
            attachNote = ""
            historyVersion++
        } else {
            // 上一轮还在生成：内容留在框里，别把用户刚打的字吃掉
            attachNote = "上一轮还在生成，等它写完再发"
        }
    }

    /** 三个入口（拍照 / 相册 / 文件）拿回来的 uri 都走这里 */
    fun addUri(uri: Uri) {
        if (attachments.size >= AttachmentKinds.MAX_FILES) {
            attachNote = "一次最多带 ${AttachmentKinds.MAX_FILES} 个文件"
            return
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

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addUri(uri)
    }

    /*
     * 相册走系统照片选择器（Android 13 起是进程外的那一个）。
     *
     * 好处是**不申请读存储权限**：用户选哪张，我们只拿到那一张的 uri。
     * 老系统上这个 contract 会自动退化成 `ACTION_OPEN_DOCUMENT`，
     * 所以低版本也能用，不需要 `if (SDK >= 33)` 分支。
     */
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addUri(uri)
    }

    /*
     * 拍照：把相机的输出写到缓存目录，再用 FileProvider 换一个可写的 uri。
     *
     * 不用 `TakePicturePreview` —— 那个只回一张缩略图（一百多 dp 的位图），
     * 发给模型等于把照片糊成马赛克。也不用自己申请 CAMERA 权限：
     * `ACTION_IMAGE_CAPTURE` 由相机 App 拍，本进程一行权限都不用加。
     */
    val cameraDir = remember { File(context.cacheDir, "camera").apply { mkdirs() } }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null) {
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }.getOrNull()
            if (uri != null) addUri(uri) else attachNote = "照片没存下来，换个方式再试"
        }
    }

    fun shoot() {
        val file = File(cameraDir, "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull()
        if (uri == null) {
            attachNote = "这台设备没有可用的相机"
            return
        }
        pendingPhoto = file
        val ok = runCatching { takePicture.launch(uri) }.isSuccess
        if (!ok) {
            pendingPhoto = null
            attachNote = "这台设备没有相机应用"
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            PhoneHistoryDrawer(
                conversations = history,
                currentId = conversation?.id,
                sceneLabel = "模式：${Scenes.label(settings.useScene)}",
                modelLabel = listOf(provider?.name.orEmpty(), modelName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "未配置供应商" },
                onOpen = { conv ->
                    scope.launch {
                        drawerState.close()
                        engine.open(conv.id)
                        /*
                         * 酒馆 / 写作的会话必须交给角色扮演页渲染：它们带角色卡、
                         * 开场白、剧情开关，手机对话页不认识这些，硬塞进去等于
                         * 把一段对话剥掉上下文展示。
                         */
                        if (Scenes.of(conv.scene) != Scenes.CODING) nav.open(Screen.RoleplayChat)
                    }
                },
                onNewChat = {
                    scope.launch {
                        drawerState.close()
                        engine.newConversation(settings, scene = Scenes.of(settings.useScene))
                        historyVersion++
                    }
                },
                onPickScene = {
                    scope.launch {
                        drawerState.close()
                        showSceneSheet = true
                    }
                },
                onSettings = {
                    scope.launch {
                        drawerState.close()
                        nav.select(PhoneTab.Settings)
                    }
                },
                onManage = {
                    scope.launch {
                        drawerState.close()
                        nav.select(PhoneTab.Sessions)
                    }
                },
                onRename = { conv ->
                    scope.launch { drawerState.close() }
                    renameText = conv.displayTitle
                    renameTarget = conv
                },
                onTogglePin = { conv ->
                    scope.launch {
                        container.conversationStore.setPinned(conv.id, !conv.pinned)
                        historyVersion++
                    }
                },
                onAbout = { conv -> infoTarget = conv },
                onDelete = { conv -> deleteTarget = conv },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding(),
        ) {
            PhoneChatHeader(
                title = conversation?.displayTitle?.ifBlank { "新对话" } ?: "新对话",
                badge = if (provider?.type == ProviderType.DSH_SERVER) "远程" else "本机",
                remote = provider?.type == ProviderType.DSH_SERVER,
                modelLabel = modelLabel,
                onMenu = { scope.launch { drawerState.open() } },
                onNewChat = {
                    scope.launch {
                        engine.newConversation(settings, scene = Scenes.of(settings.useScene))
                        historyVersion++
                    }
                },
                onModel = { showModelPicker = true },
            )

            if (provider == null) {
                NoProviderHint(onOpenProviders = { nav.open(Screen.Providers) })
                return@Column
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    GreetingHome(
                        agentMode = settings.agentMode,
                        scene = settings.useScene,
                        showMascot = settings.showMascot,
                        onPickMode = { showModeSheet = true },
                        onPickScene = { showSceneSheet = true },
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
                                onContinue = { engine.continueGeneration(last.id) },
                                onEdit = { editing = last },
                                onSwitchVersion = { i -> engine.switchVersion(last.id, i) },
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
                onAttach = { showAttach = true },
                onSend = { submit() },
                onStop = { engine.stop() },
            )
        }
    }

    // ---------------- 弹窗 ----------------

    if (showAttach) {
        PhoneAttachSheet(
            onCamera = { showAttach = false; shoot() },
            onGallery = {
                showAttach = false
                runCatching {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            onFile = { showAttach = false; runCatching { pickFile.launch(arrayOf("*/*")) } },
            onDismiss = { showAttach = false },
        )
    }

    infoTarget?.let { conv ->
        ConversationInfoDialog(
            conversation = conv,
            isCurrent = conv.id == conversation?.id,
            onOpenContext = {
                infoTarget = null
                showContext = true
            },
            onCutContext = {
                infoTarget = null
                scope.launch { engine.markContextCut() }
            },
            onDismiss = { infoTarget = null },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            container.conversationStore.rename(target.id, renameText.trim())
                            renameTarget = null
                            historyVersion++
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(s.cancel) }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条对话？") },
            text = { Text("「${target.displayTitle}」里的消息会一起删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { container.conversationStore.delete(target.id) }
                        deleteTarget = null
                        historyVersion++
                    }
                }) { Text(s.delete) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(s.cancel) }
            },
        )
    }

    editing?.let { target ->
        PhoneEditDialog(
            original = target.content,
            onDismiss = { editing = null },
            onConfirm = { text ->
                engine.editMessage(target.id, text)
                editing = null
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
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

    if (showSceneSheet) {
        PhoneSceneSheet(
            current = settings.useScene,
            onPick = { id ->
                showSceneSheet = false
                switchScene(id)
            },
            onDismiss = { showSceneSheet = false },
        )
    }

    if (showModeSheet) {
        PhoneModeSheet(
            current = settings.agentMode,
            onPick = { mode ->
                showModeSheet = false
                scope.launch { container.settingsRepo.update { it.copy(agentMode = mode) } }
            },
            onDismiss = { showModeSheet = false },
        )
    }

    if (showModelPicker) {
        PhoneModelSheet(
            providers = settings.providers,
            activeProviderId = settings.activeProviderId,
            currentModel = conversation?.model?.ifBlank { provider?.defaultModel.orEmpty() }.orEmpty(),
            onPickProvider = { p ->
                scope.launch { container.settingsRepo.update { it.copy(activeProviderId = p.id) } }
            },
            onPickModel = { model ->
                showModelPicker = false
                scope.launch { engine.overrideModel(model) }
            },
            onPickEffort = { effort ->
                scope.launch {
                    container.settingsRepo.update { st ->
                        // 强度是**供应商**上的字段（同一个模型在两条通道上限速不同），
                        // 所以改的是 providers 里那一项，不是全局设置。
                        val list = st.providers.map {
                            if (it.id == st.activeProviderId) it.copy(reasoningEffort = effort) else it
                        }
                        st.copy(providers = list)
                    }
                }
            },
            onManage = { showModelPicker = false; nav.open(Screen.Providers) },
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
            AlertDialog(
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

/**
 * 「关于对话」。
 *
 * 从抽屉里长按一条会话打开。放的是**这条对话自己的账**：多少条消息、
 * 累计用了多少 token、什么模型、什么时候建的 —— 也就是被点名的那个
 * 「关于对话 token」。
 *
 * 上下文占用（这一轮会带多少进窗口）只能算**当前打开的**那条：
 * 它依赖引擎里的会话状态，翻了别人那条就算不出来。所以那两个按钮只在
 * 当前会话上出现，其余情况给一句说明，而不是摆一个按下去没反应的键。
 */
@Composable
private fun ConversationInfoDialog(
    conversation: Conversation,
    isCurrent: Boolean,
    onOpenContext: () -> Unit,
    onCutContext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val usage = conversation.usage
    val rows = buildList {
        add("消息" to "${conversation.messageCount} 条")
        add("模型" to conversation.model.ifBlank { "默认模型" })
        add("输入 token" to usage.prompt.toString())
        add("输出 token" to usage.completion.toString())
        if (usage.reasoning > 0) add("思考 token" to usage.reasoning.toString())
        add("合计" to usage.total.toString())
        add("场景" to Scenes.label(conversation.scene))
        add("更新于" to dateLabel(conversation.updatedAt))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = conversation.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = k,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 76.dp),
                        )
                        Text(
                            text = v,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (isCurrent) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onOpenContext) { Text("上下文占用") }
                        TextButton(onClick = onCutContext) { Text("清空这轮上下文") }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "上下文占用只能看当前打开的那条对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun dateLabel(ts: Long): String {
    if (ts <= 0L) return "—"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(ts))
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
    onContinue: () -> Unit,
    onEdit: () -> Unit,
    onSwitchVersion: (Int) -> Unit,
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

    /*
     * 这一轮真正交付的文件。
     *
     * 同一个路径可能被多次写入（先建后改），按路径去重；只认成功的调用，
     * 失败的那次写在 `artifact` 里的路径往往根本没生成。
     */
    val artifacts = remember(allCalls) {
        allCalls
            .mapNotNull { call ->
                call.artifact.takeIf { call.status == ToolStatus.SUCCESS && it.isNotBlank() }
            }
            .distinct()
    }

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
                TextButton(onClick = onEdit) { Text("编辑", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onDelete) { Text(s.delete, style = MaterialTheme.typography.labelSmall) }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showsWork) {
            AgentWorkBlock(
                blockKey = first.id,
                steps = workStepsOf(messages, liveId = live?.messageId, liveReasoning = reasoning),
                isStreaming = isStreaming,
                showReasoning = settings.showReasoning,
                sizeOf = { path -> artifactSizeOf(container, path) },
                onOpenArtifact = onOpenArtifact,
                onExportArtifact = { },
                // 产物挪到正文下面，见下面的 DeliveryList
                showArtifacts = false,
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

        /*
         * 交付的文件。
         *
         * 放在正文（总结）之后，而不是夹在工作流程卡片里 —— 用户读完回答，
         * 下一步就是「东西在哪、点开看看」，位置应该顺着阅读方向往下。
         */
        if (!isStreaming && artifacts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            DeliveryList(
                paths = artifacts,
                sizeOf = { path -> artifactSizeOf(container, path) },
                onOpen = onOpenArtifact,
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

        if (!isStreaming && last.hasVersions) {
            Spacer(Modifier.height(6.dp))
            PhoneVersionBar(
                index = last.versionIndex,
                total = last.versions.size,
                onPick = onSwitchVersion,
            )
        }

        if (!isStreaming && bodyText.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onCopy(body) }) { Text(s.copy, style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onRegenerate) { Text(s.regenerate, style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onContinue) { Text("继续写", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onDelete) { Text(s.delete, style = MaterialTheme.typography.labelSmall) }
            }
        }

        viewingMedia?.let { ref ->
            MediaViewer(ref = ref, onClose = { viewingMedia = null })
        }
    }
}

/**
 * 交付文件列表。
 *
 * 一轮任务可能产出好几个文件（表格 + 图 + 代码），逐个平铺而不是塞进一个
 * 可折叠的「全部产物」—— 手机上多一次点击才能确认「东西在哪」不值得。
 */
@Composable
private fun DeliveryList(
    paths: List<String>,
    sizeOf: (String) -> Long,
    onOpen: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "文件产物 · ${paths.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        paths.forEach { path ->
            ArtifactCard(
                path = path,
                sizeBytes = sizeOf(path),
                onOpen = onOpen,
                onExport = null,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/**
 * 版本切换条。
 *
 * 只在真有多版时出现。放在操作行的上面而不是塞进操作行：它是「你现在看的是哪一版」
 * 的状态，不是又一次操作 —— 和下面那排「复制 / 重生成 / 删除」混在一起会被忽略。
 */
@Composable
private fun PhoneVersionBar(index: Int, total: Int, onPick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = scheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onPick(index - 1) },
                enabled = index > 0,
                modifier = Modifier.size(width = 44.dp, height = 34.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("‹", style = MaterialTheme.typography.titleMedium) }
            Text(
                text = "${index + 1} / $total",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TextButton(
                onClick = { onPick(index + 1) },
                enabled = index < total - 1,
                modifier = Modifier.size(width = 44.dp, height = 34.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("›", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/**
 * 编辑已发出的提问。
 *
 * 用整屏对话框而不是就地编辑：就地编辑要处理「输入框撑开、列表跟着跳、
 * 键盘遮住这条消息」三件事，而编辑本身是个低频动作 —— 弹出来改完就走更省事。
 *
 * 底下那句警告必须留：改提问 = 这条之后的回答全部作废，用户按下去之前
 * 得知道自己在丢东西。
 */
@Composable
private fun PhoneEditDialog(
    original: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑这条提问") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "改完会重新回答；这条之后的对话会被清掉。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text != original,
            ) { Text("重新回答") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
