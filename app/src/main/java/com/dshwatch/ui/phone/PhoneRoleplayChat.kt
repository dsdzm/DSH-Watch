package com.dshwatch.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.ChoiceButtons
import com.dshwatch.ui.components.ContextUsageDialog
import com.dshwatch.ui.components.PanelCard
import com.dshwatch.ui.html.InlineHtml
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.markdown.MarkdownText
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.screens.fish.FishPhoneScreen
import kotlinx.coroutines.launch

/**
 * 角色对话 / 写作对话的手机版。
 *
 * ## 为什么不是「把 [com.dshwatch.ui.screens.chat.RoleplayChatScreen] 拉宽」
 *
 * 那是表盘上的一页：`WatchScaffold` 的顶栏只有「标题 + 一排小图标」，
 * 消息是「说话人一行 + 一整块玻璃」，输入要跳到另一个页面去打字。
 * 手机上这三件事都不成立 —— 顶栏要有返回、消息要分左右、输入要贴着键盘。
 *
 * ## 与「养鱼」页面的区别
 *
 * 养鱼是一对一聊天软件：左右气泡、头像、时间戳、一条回复拆成几个短气泡。
 * 这里要撑住的是**正文**：酒馆的角色台词可能几百字，写作就是一整篇稿子。
 * 所以酒馆是左右气泡（左角色右自己），写作则是整宽段落 —— 把一段小说塞进
 * 一个 260dp 宽的圆角块里，读起来像在看聊天记录，而不是在读稿。
 *
 * ## 养鱼会话也走这一页
 *
 * 从「记录」点开一条养鱼会话时，栈里压的是 `Screen.RoleplayChat`（会话页），
 * 而它的场景是 fish。这里直接转发给 [FishPhoneScreen] 并给它一颗返回键 ——
 * 否则用户会落回手表那套界面（那正是修复前的问题）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PhoneRoleplayChatScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
) {
    val imeVisible = WindowInsets.isImeVisible
    val engine = container.engine
    val scope = rememberCoroutineScope()

    val conversation by engine.conversation.collectAsStateWithLifecycle()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val streaming by engine.streaming.collectAsStateWithLifecycle()
    val banner by engine.banner.collectAsStateWithLifecycle()

    val scene = Scenes.of(conversation?.scene)

    /*
     * 养鱼会话：交给那一页，只补一颗返回键。
     *
     * 它是「一个人」的对话，不是「一张卡」的对话 —— 换成这一页渲染会把她的
     * 短句拆成长段、把节奏全丢掉。判断依据是**会话自己的场景**，不是设置里的
     * 当前模式：从记录里点开的是哪条，就该用哪套界面。
     */
    if (scene == Scenes.FISH) {
        FishPhoneScreen(
            container = container,
            settings = settings,
            nav = nav,
            onBack = { nav.pop() },
        )
        return
    }

    val current = conversation
    if (current == null) {
        // 会话被删掉 / 进程被回收后恢复到这里：给一条明确的路，而不是空白页
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            PhoneChatHeader(
                title = "对话",
                badge = "没有打开的会话",
                remote = false,
                modelLabel = phoneModelLabel(settings),
                onMenu = {},
                onNewChat = null,
                onModel = {},
                onBack = { nav.pop() },
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这条会话已经不在了", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val isWriting = scene == Scenes.WRITING
    val cardId = current.roleCardId.orEmpty()
    val provider = settings.activeProvider

    var card by remember { mutableStateOf<RoleCard?>(null) }
    var showContext by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    var showScene by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    // 选了「自己说」之后给一句提示：那颗按钮点完什么都不发生，用户会以为坏了
    var customHint by remember { mutableStateOf(false) }

    val ctxSnapshot by engine.context.collectAsStateWithLifecycle()
    val ctxInsight = ctxSnapshot?.takeIf { it.conversationId == current.id }?.insight

    LaunchedEffect(cardId) {
        card = if (cardId.isBlank()) null else runCatching { container.roleCardStore.load(cardId) }.getOrNull()
    }

    /*
     * 长期记忆：说完这一轮之后在后台整理。
     *
     * 放在「流式结束」这一刻，而不是每轮开头 —— 压缩本身要发一次请求，
     * 放开头会让用户等着；放结尾则是「说完这句之后它自己在后台整理」。
     */
    val longTerm = card?.longTermMemory == true
    LaunchedEffect(streaming, messages.size) {
        if (streaming || messages.isEmpty()) return@LaunchedEffect
        if (!longTerm || cardId.isBlank()) return@LaunchedEffect
        val p = settings.activeProvider ?: return@LaunchedEffect
        container.memoryCompressor.maybeCompress(
            conversationId = current.id,
            cardId = cardId,
            scene = scene,
            contextWindow = p.contextWindow,
            model = current.model,
        )
    }

    fun kickoff() {
        val greet = card?.greeting.orEmpty().trim()
        val prompt = when {
            isWriting && greet.isNotBlank() ->
                "（以上是我已经写好的开头，请接着往下写正文，不要复述开头）"
            isWriting -> "（从这一段开始写下去）"
            greet.isNotBlank() -> "（我看着你，等你先开口）"
            else -> "（你好）"
        }
        engine.send(prompt)
    }

    fun newChat() {
        scope.launch {
            engine.startNew(
                settings = settings.copy(agentMode = AgentMode.OFF),
                scene = scene,
                roleCardId = cardId,
                title = card?.displayName ?: current.displayTitle,
            )
            input = ""
        }
    }

    // ---------------- 滚动跟随 ----------------

    val listState = rememberLazyListState()
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(current.id) { landed = false }
    LaunchedEffect(messages.size, live.text.length, streaming) {
        if (messages.isEmpty()) return@LaunchedEffect
        val target = messages.size
        if (!landed) {
            runCatching { listState.scrollToItem(target) }
            landed = true
            return@LaunchedEffect
        }
        if (!atBottom) return@LaunchedEffect
        runCatching { listState.scrollToItem(target) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            /*
             * 顶栏自己补状态栏，这里只补底部。
             *
             * 键盘弹出时不补导航条：那时输入条要贴着键盘上沿，再叠一条
             * 导航条的高度会在键盘和输入框之间留一道空白。
             */
            .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding())
            .imePadding(),
    ) {
        PhoneChatHeader(
            title = card?.displayName ?: if (isWriting) "写作" else "酒馆",
            badge = "${Scenes.label(scene)} · ${messages.size} 条",
            remote = provider?.type == com.dshwatch.domain.model.ProviderType.DSH_SERVER,
            modelLabel = phoneModelLabel(settings, current.model),
            onMenu = {},
            // 点标题不新建：在对话页里误触一次代价是「刚才那段被换掉」，
            // 新建放到 ⋮ 菜单里，需要时多点一下
            onNewChat = null,
            onModel = { showModel = true },
            onBack = { nav.pop() },
            trailing = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重新开一段") },
                            leadingIcon = { Icon(Icons.Filled.Add, null) },
                            onClick = { menuOpen = false; newChat() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isWriting) "编辑设定" else "编辑角色卡") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = {
                                menuOpen = false
                                nav.open(Screen.CardEdit(scene, cardId))
                            },
                        )
                        if (longTerm) {
                            DropdownMenuItem(
                                text = { Text(if (isWriting) "它记得什么" else "她记得什么") },
                                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                onClick = {
                                    menuOpen = false
                                    nav.open(Screen.MemoryDoc(cardId))
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(LocalStrings.current.ctxTitle) },
                            leadingIcon = { Icon(Icons.Outlined.PieChart, null) },
                            onClick = { menuOpen = false; showContext = true },
                        )
                        DropdownMenuItem(
                            text = { Text("切换模式") },
                            leadingIcon = { Icon(Icons.Outlined.Translate, null) },
                            onClick = { menuOpen = false; showScene = true },
                        )
                        DropdownMenuItem(
                            text = { Text(LocalStrings.current.navSettings) },
                            leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                            onClick = { menuOpen = false; nav.open(Screen.Settings) },
                        )
                    }
                }
            },
        )

        if (messages.isEmpty()) {
            PhoneOpening(
                card = card,
                isWriting = isWriting,
                onStart = { kickoff() },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                    PhoneRoleplayMessage(
                        message = m,
                        liveText = if (live.messageId == m.id) live.text else null,
                        isWriting = isWriting,
                        speakerName = card?.displayName.orEmpty(),
                        canPick = index == messages.lastIndex && !streaming,
                        onChoose = { c ->
                            if (c.custom) {
                                // 自定义选项 = 想自己说：提示他往下看，输入框就在那里
                                customHint = true
                            } else {
                                engine.send(c.raw)
                            }
                        },
                        onOpenHtml = { html -> nav.open(Screen.HtmlView(html)) },
                        onScrollBy = { d -> listState.dispatchRawDelta(d) },
                    )
                }
                item(key = "__tail__") { Spacer(Modifier.height(4.dp)) }
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

        if (customHint && input.isBlank()) {
            Text(
                text = "想说什么，直接打在下面",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, bottom = 2.dp),
            )
        }

        PhoneComposer(
            input = input,
            onInput = {
                input = it
                if (it.isNotBlank()) customHint = false
            },
            streaming = streaming,
            busy = streaming,
            note = "",
            attachments = emptyList(),
            onRemove = {},
            onAttach = {},
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty() && engine.send(text)) input = ""
            },
            onStop = { engine.stop() },
            // 没有附件入口：这一页是「跟她说话」，不是发文件
            showAttach = false,
        )
    }

    // ---------------- 弹窗 ----------------

    if (showContext) {
        val snapshot = ctxInsight
        if (snapshot != null) {
            ContextUsageDialog(insight = snapshot, onDismiss = { showContext = false })
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showContext = false },
                title = { Text(LocalStrings.current.ctxTitle) },
                text = {
                    Text(
                        text = if (messages.isEmpty()) {
                            LocalStrings.current.ctxEmpty
                        } else {
                            "这条会话的上下文构成会在下一次发送时计算"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showContext = false }) { Text(LocalStrings.current.close) }
                },
            )
        }
    }

    if (showScene) {
        PhoneSceneSheet(
            current = scene,
            onPick = { id ->
                showScene = false
                scope.launch { container.settingsRepo.update { it.copy(useScene = id) } }
                /*
                 * 回到「对话」tab 的根，而不是只清栈。
                 *
                 * 这一页可能是从记录页压栈进来的：只 popToRoot 会停在记录 tab，
                 * 用户切了模式却看不到新模式 —— 所以顺带把 tab 也切过去。
                 */
                nav.select(PhoneTab.Chat)
            },
            onDismiss = { showScene = false },
        )
    }

    if (showModel) {
        PhoneModelSheet(
            providers = settings.providers,
            activeProviderId = settings.activeProviderId,
            currentModel = current.model.ifBlank { provider?.defaultModel.orEmpty() },
            onPickProvider = { p ->
                scope.launch { container.settingsRepo.update { it.copy(activeProviderId = p.id) } }
            },
            onPickModel = { model ->
                showModel = false
                scope.launch { engine.overrideModel(model) }
            },
            onPickEffort = { effort ->
                scope.launch {
                    container.settingsRepo.update { st ->
                        st.copy(
                            providers = st.providers.map {
                                if (it.id == st.activeProviderId) it.copy(reasoningEffort = effort) else it
                            },
                        )
                    }
                }
            },
            onManage = {
                showModel = false
                nav.open(Screen.Providers)
            },
            onDismiss = { showModel = false },
        )
    }
}

/**
 * 一条消息。
 *
 * 与手表那版比：说话人从「头顶一行小字」变成「气泡的属性」（写作场景干脆不写），
 * 左右分栏取代了整宽块，其余（自定义 HTML 面板、原生面板、A/B/C 选项、
 * 出错提示）保持一致 —— 那几样是**内容**，换个屏幕不该消失。
 */
@Composable
private fun PhoneRoleplayMessage(
    message: ChatMessage,
    liveText: String?,
    isWriting: Boolean,
    speakerName: String,
    canPick: Boolean,
    onChoose: (com.dshwatch.domain.Choices.Choice) -> Unit,
    onOpenHtml: (String) -> Unit,
    onScrollBy: (Float) -> Unit,
) {
    val isUser = message.role == Role.USER
    val text = liveText ?: message.content
    if (text.isBlank() && message.status != MessageStatus.STREAMING) return

    val html = remember(text, message.status) {
        if (message.status == MessageStatus.STREAMING) null
        else com.dshwatch.ui.html.HtmlBlocks.findRenderable(text)
    }
    val rawPlain = if (html != null) com.dshwatch.ui.html.HtmlBlocks.strip(text, html) else text
    val panelRes = remember(rawPlain, message.status) {
        if (message.status == MessageStatus.STREAMING) {
            com.dshwatch.domain.Panels.Result(rawPlain, emptyList())
        } else {
            com.dshwatch.domain.Panels.extract(rawPlain)
        }
    }
    val afterPanel = if (panelRes.panels.isNotEmpty()) panelRes.body else rawPlain
    val parsed = remember(afterPanel) { com.dshwatch.domain.Choices.parse(afterPanel) }
    val plain = if (parsed.hasChoices) parsed.body else afterPanel

    Column(modifier = Modifier.fillMaxWidth()) {
        if (html != null) {
            InlineHtml(
                html = html.html,
                maxHeight = 300.dp,
                onOpenFull = onOpenHtml,
                onDrag = onScrollBy,
            )
            if (plain.isNotBlank()) Spacer(Modifier.height(6.dp))
        }

        if (plain.isNotBlank() || message.status == MessageStatus.STREAMING) {
            val scheme = MaterialTheme.colorScheme
            // 写作的正文整宽（那是在读稿），酒馆与自己的话按气泡对齐
            val fullWidth = isWriting && !isUser
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(if (fullWidth) 1f else 0.86f),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    if (!isUser && !isWriting) {
                        Text(
                            text = speakerName.ifBlank { "对方" },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
                        )
                    }
                    Surface(
                        shape = when {
                            fullWidth -> RoundedCornerShape(18.dp)
                            isUser -> RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
                            else -> RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
                        },
                        color = when {
                            fullWidth -> scheme.surfaceContainerLow
                            isUser -> scheme.secondaryContainer
                            else -> scheme.surfaceContainerHigh
                        },
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                            if (message.status == MessageStatus.STREAMING && text.isBlank()) {
                                Text(
                                    text = "……",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurfaceVariant,
                                )
                            } else {
                                MarkdownText(text = plain)
                            }
                        }
                    }
                }
            }
        }

        if (panelRes.panels.isNotEmpty()) {
            panelRes.panels.forEach { p ->
                Spacer(Modifier.height(7.dp))
                PanelCard(panel = p)
            }
        }

        if (!isUser && parsed.hasChoices && message.status != MessageStatus.STREAMING) {
            Spacer(Modifier.height(7.dp))
            ChoiceButtons(choices = parsed.choices, enabled = canPick, onPick = onChoose)
        }

        if (message.status == MessageStatus.ERROR) {
            Text(
                text = message.error.ifBlank { "出错了" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            )
        }
    }
}

/**
 * 还没说话时：把开场白摆出来，给一个明确的按钮。
 *
 * 开场白只显示、不写进历史 —— 写进去模型会以为自己刚说完，
 * 于是接着自说自话。点按钮时发的是一句极短的引导，让它顺着开口往下接。
 */
@Composable
private fun PhoneOpening(
    card: RoleCard?,
    isWriting: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = if (isWriting) "开头" else "开场白",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = card?.greeting.orEmpty().trim().ifBlank {
                        if (isWriting) "（这份设定没有写开头，直接说你想写什么）" else "（这张卡没有写开场白，直接说话即可）"
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = scheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            onClick = onStart,
            shape = RoundedCornerShape(24.dp),
            color = scheme.primary,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isWriting) "从这里接着写" else "开始对话",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (isWriting) {
                "模型会接着上面的文字往下写；想调整方向就直接说"
            } else {
                "设定只在开始时生效，之后只带最近的对话（开了长期记忆则只带记忆摘要）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
