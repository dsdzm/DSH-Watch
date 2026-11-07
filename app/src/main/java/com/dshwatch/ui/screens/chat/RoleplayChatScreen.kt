package com.dshwatch.ui.screens.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
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
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.components.IconTap
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.WatchSection
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.markdown.MarkdownText
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.scene.ScenePill
import com.dshwatch.util.Fmt
import com.dshwatch.ui.components.glassSurface
import kotlinx.coroutines.launch
import com.dshwatch.ui.theme.LocalGlass

/**
 * 角色扮演 / 写作的对话页。
 *
 * ## 为什么不复用 [ChatScreen] 加一堆 if
 *
 * ChatScreen 有 1500 行，承担的是「Agent 工作台」：工作流卡片、工具审批、
 * 产物、上下文条、B站快捷入口。而这里要的是**只有对话**：大字、说话人、
 * 一个输入条，别的都不要。往 ChatScreen 里加 `if (isRoleplay)` 会让那 1500 行
 * 里一半的分支都变成"可能不存在"，反而更容易出 bug。
 *
 * 两者共用的只有引擎（同一个 ChatEngine）与它暴露的 StateFlow，
 * 所以拆开是零成本的。
 */
@Composable
fun RoleplayChatScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val engine = container.engine
    val scope = rememberCoroutineScope()
    val conversation by engine.conversation.collectAsStateWithLifecycle()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val streaming by engine.streaming.collectAsStateWithLifecycle()
    val banner by engine.banner.collectAsStateWithLifecycle()

    val scene = Scenes.of(conversation?.scene)
    val isWriting = scene == Scenes.WRITING
    val listState = rememberLazyListState()

    var card by remember { mutableStateOf<RoleCard?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var memoryText by remember { mutableStateOf("") }

    val cardId = conversation?.roleCardId.orEmpty()
    LaunchedEffect(cardId) {
        card = if (cardId.isBlank()) null else runCatching { container.roleCardStore.load(cardId) }.getOrNull()
        memoryText = if (cardId.isBlank()) "" else container.memoryStore.read(cardId)
    }

    /*
     * 自动跟随到最后一条。
     *
     * 这里原来写的是 `scrollToItem(messages.lastIndex)` —— 把**最后一条消息的顶部**
     * 对齐到视口顶部。一条几百字的长回复，每来几个字就重新对齐一次，
     * 用户看到的永远是这段话的开头，新生成的字一直在屏幕下方看不见，
     * 观感就是「一生成就滚回页首」。
     *
     * 正确目标是列表末尾的 tail 项（index = messages.size）：它贴在最后，
     * 对齐它就等于滚到最底。再加一道 atBottom 守卫 —— 用户往上翻历史时
     * 不要把他拽回来。
     */
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    /*
     * 进页面先落到底，之后再按 atBottom 跟随。
     *
     * 从输入页退回来时这一页是**新组合**的：列表状态重建、滚动位置归零，
     * 而 atBottom 此刻通常已经是 false（列表停在最上面），跟随逻辑不会触发 ——
     * 用户发完一句话，看到的却是这篇稿子的第一段。
     * 所以第一次拿到消息时无条件滚到尾，把「跟随」留给后续的增量。
     */
    var landedAtTail by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, live.text.length, streaming) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!landedAtTail) {
            runCatching { listState.scrollToItem(messages.size) }
            landedAtTail = true
            return@LaunchedEffect
        }
        if (!atBottom) return@LaunchedEffect
        runCatching { listState.scrollToItem(messages.size) }
    }

    /*
     * 长期记忆的触发点。
     *
     * 放在「流式结束」这一刻，而不是每轮开头：压缩本身要发一次请求，
     * 放在开头会让用户等着；放在结尾则是"说完这句之后它在后台自己整理"。
     */
    LaunchedEffect(streaming, messages.size) {
        if (streaming || messages.isEmpty()) return@LaunchedEffect
        if (card?.longTermMemory != true || cardId.isBlank()) return@LaunchedEffect
        val provider = settings.activeProvider ?: return@LaunchedEffect
        val window = provider.contextWindow
        // 判定挪进 MemoryCompressor：它才知道"未压缩的那一段"从哪开始。
        // 这里过去是拿整个会话的 token 数去比，压缩过一次之后就再也不会触发。
        container.memoryCompressor.maybeCompress(
            conversationId = conversation?.id.orEmpty(),
            cardId = cardId,
            scene = scene,
            contextWindow = window,
        )
    }

    /*
     * 手表布局的顶栏弦宽只有约 128dp，三颗图标会把标题压成 0 宽。
     * 这里去掉「切回编程场景」那颗 —— 它是回根页才能用的跳转，不是高频操作；
     * 记忆和设置留下，两者都是「聊到一半就要用」的入口。
     */
    val watchLayout = com.dshwatch.domain.model.LayoutThemes
        .isWatchLayout(com.dshwatch.ui.theme.LocalLayoutTheme.current)
    WatchScaffold(
        title = card?.displayName ?: if (isWriting) "写作" else "酒馆",
        onBack = { nav.popToRoot() },
        actions = {
            IconTap(Icons.Outlined.Memory, "记忆") {
                scope.launch {
                    memoryText = if (cardId.isBlank()) "" else container.memoryStore.read(cardId)
                }
                showInfo = !showInfo
            }
            /*
             * 「切回编程场景」在手表布局里也要留：它原来因为「顶栏弦宽不够」
             * 被收掉，结果是用户进了酒馆/写作就找不到回去的路，只能翻设置。
             * 现在这一行是内容（骨架排在标题下），跟着消息一起滚，
             * 三颗合计约 130dp，206dp 的表盘放得下。
             */
            ScenePill(scene) {
                scope.launch {
                    container.settingsRepo.update { it.copy(useScene = Scenes.CODING) }
                }
                nav.popToRoot()
            }
            // 对话页也要能进设置：这一页是从角色列表 push 上来的，
            // 如果只在列表页放设置，用户聊到一半想改模型/外观就得先退出去。
            // 与 ChatScreen 顶栏保持同一组图标，避免"换个场景就找不到同一个功能"。
            IconTap(Icons.Outlined.Settings, LocalStrings.current.navSettings) {
                nav.push(Screen.Settings)
            }
        },
        fullBleed = true,
        // 消息区自带 LazyColumn，骨架不再套滚动
        scrollable = false,
        /*
         * 手表布局：输入变成下缘边键（Wraive 的做法），不再钉一条横贯底栏。
         * 底栏在最下面那一行只剩屏宽一半的弦，钉在那里必然被圆周切掉。
         */
        edgeAction = if (watchLayout) {
            {
                com.dshwatch.ui.wear.WearEdgeAction(
                    onClick = { if (streaming) engine.stop() else nav.push(Screen.Input()) },
                    danger = streaming,
                ) {
                    androidx.wear.compose.material3.Icon(
                        imageVector = if (streaming) Icons.Filled.Stop else Icons.Outlined.Keyboard,
                        contentDescription = if (streaming) "停止" else "输入",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            null
        },
        bottom = if (watchLayout) {
            null
        } else {
            {
                RoleplayInputBar(
                    streaming = streaming,
                    hint = if (isWriting) "接着写，或说说你想怎么改" else "说点什么…",
                    onOpenInput = { nav.push(Screen.Input()) },
                    onSend = { nav.push(Screen.Input()) },
                    onStop = { engine.stop() },
                )
            }
        },
    ) {
        banner?.let { b ->
            Text(
                text = b.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        if (showInfo) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                val shown = card
                if (shown != null) {
                    com.dshwatch.ui.screens.tavern.CardDetailSheet(card = shown, isWriting = isWriting)
                }
                Spacer(Modifier.height(8.dp))
                WatchGhostButton(text = "编辑设定", onClick = {
                    showInfo = false
                    nav.push(Screen.CardEdit(scene, cardId))
                })
                if (card?.longTermMemory == true) {
                    Spacer(Modifier.height(8.dp))
                    WatchGhostButton(text = "立即压缩记忆", onClick = {
                        scope.launch {
                            val provider = settings.activeProvider
                            container.memoryCompressor.maybeCompress(
                                conversationId = conversation?.id.orEmpty(),
                                cardId = cardId,
                                scene = scene,
                                contextWindow = provider?.contextWindow ?: 8000,
                                force = true,
                            )
                            memoryText = container.memoryStore.read(cardId)
                        }
                    })
                    Spacer(Modifier.height(6.dp))
                    // 预览只给前 300 字 —— 手表屏上再多也读不完。
                    // 要看全的就点进记忆文档，那里是完整可编辑的原文。
                    HintText(
                        memoryText.ifBlank { "还没有写进记忆的内容" }.take(300) +
                            if (memoryText.length > 300) "\n…（点下面「打开记忆文档」看全部）" else ""
                    )
                    Spacer(Modifier.height(6.dp))
                    WatchGhostButton(text = "打开记忆文档", onClick = {
                        showInfo = false
                        nav.push(Screen.MemoryDoc(cardId))
                    })
                }
                Spacer(Modifier.height(8.dp))
                WatchGhostButton(text = "返回对话", onClick = { showInfo = false })
            }
            return@WatchScaffold
        }

        if (messages.isEmpty()) {
            OpeningHint(
                card = card,
                isWriting = isWriting,
                onStart = {
                    /*
                     * 开场白已经显示在屏幕上了（见 OpeningHint），但**不能**当成一条
                     * 助手消息写进历史 —— 写进去模型会以为"我刚说完话又说一句"，
                     * 于是接着自说自话。
                     *
                     * 正确做法是把它作为模型可见的上下文：这里发一句极短的引导，
                     * 模型会顺着开场白往下接。写作场景则要求它直接续写正文。
                     * 这一句只在首次进入时发一次，之后对话正常进行。
                     */
                    val greet = card?.greeting.orEmpty().trim()
                    val kickoff = when {
                        isWriting && greet.isNotBlank() ->
                            "（以上是我已经写好的开头，请接着往下写正文，不要复述开头）"
                        isWriting -> "（从这一段开始写下去）"
                        greet.isNotBlank() -> "（我看着你，等你先开口）"
                        else -> "（你好）"
                    }
                    engine.send(kickoff)
                },
            )
            return@WatchScaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 4.dp,
                // 手表布局的下缘是输入边键，最后一条消息要躲开它
                bottom = if (watchLayout) 44.dp else 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                RoleplayMessage(
                    message = m,
                    liveText = if (live.messageId == m.id) live.text else null,
                    isWriting = isWriting,
                    speakerName = card?.displayName.orEmpty(),
                    onOpenHtml = { html -> nav.push(Screen.HtmlView(html)) },
                    onScrollBy = { d -> listState.dispatchRawDelta(d) },
                    canPick = index == messages.lastIndex && !streaming,
                    onChoose = { c ->
                        if (c.custom) nav.push(Screen.Input()) else engine.send(c.raw)
                    },
                )
            }
            item(key = "__tail__") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/**
 * 一条消息。
 *
 * 与 ChatScreen 的气泡比：没有工具卡片、没有思维链折叠、没有 token 角标、
 * 没有产物入口。用户明确要求这两个场景里"不要出现除了对话以外的东西"。
 */
@Composable
private fun RoleplayMessage(
    message: ChatMessage,
    liveText: String?,
    isWriting: Boolean,
    speakerName: String,
    onOpenHtml: (String) -> Unit,
    onScrollBy: (Float) -> Unit,
    canPick: Boolean,
    onChoose: (com.dshwatch.domain.Choices.Choice) -> Unit,
) {
    val isUser = message.role == Role.USER
    val text = liveText ?: message.content
    if (text.isBlank() && message.status != MessageStatus.STREAMING) return

    /*
     * 自定义界面：消息里带一份完整 HTML 文档时，正文之外多一张活的界面。
     *
     * 放在气泡**外面**、宽度撑满 —— 它不是"角色说的话"，是一块面板。
     * 塞进气泡会跟着气泡收窄，作者排好的表格和标签全被挤烂。
     */
    val html = remember(text, message.status) {
        if (message.status == MessageStatus.STREAMING) null
        else com.dshwatch.ui.html.HtmlBlocks.find(text)
    }
    val rawPlain = if (html != null) com.dshwatch.ui.html.HtmlBlocks.strip(text, html) else text
    // ```panel 围栏 → 原生面板；流式期间半截围栏没闭合，不必解析
    val panelRes = remember(rawPlain, message.status) {
        if (message.status == MessageStatus.STREAMING) {
            com.dshwatch.domain.Panels.Result(rawPlain, emptyList())
        } else {
            com.dshwatch.domain.Panels.extract(rawPlain)
        }
    }
    val afterPanel = if (panelRes.panels.isNotEmpty()) panelRes.body else rawPlain
    // 尾部 A/B/C 拆出来做按钮，正文只留前面
    val parsed = remember(afterPanel) { com.dshwatch.domain.Choices.parse(afterPanel) }
    val plain = if (parsed.hasChoices) parsed.body else afterPanel

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (isUser) "我" else speakerName.ifBlank { "对方" },
            style = MaterialTheme.typography.labelSmall,
            color = if (isUser) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
        )
        if (html != null) {
            // 直接渲染成界面，不是入口卡片：角色面板、状态条这类东西
            // 就是要在对话里一眼看到、随手点，多一跳全屏就失去了意义
            com.dshwatch.ui.html.InlineHtml(
                html = html.html,
                maxHeight = 260.dp,
                onOpenFull = onOpenHtml,
                onDrag = onScrollBy,
            )
            if (plain.isNotBlank()) Spacer(Modifier.height(6.dp))
        }
        if (plain.isNotBlank() || message.status == MessageStatus.STREAMING) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (isUser) 0.88f else 1f)
                    .clip(RoundedCornerShape(18.dp))
                    .then(
                        when {
                            isWriting -> Modifier
                            LocalGlass.current && isUser -> Modifier.glassSurface(
                                shape = RoundedCornerShape(18.dp), cornerRadius = 18.dp,
                            )
                            LocalGlass.current -> Modifier.glassSurface(
                                shape = RoundedCornerShape(18.dp),
                                cornerRadius = 18.dp,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            else -> Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                )
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                if (message.status == MessageStatus.STREAMING && text.isBlank()) {
                    Text(
                        text = "……",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 角色扮演里通常没有 Markdown，但写作场景会有分段与强调，
                    // 所以仍走同一个解析器；字号由 LocalTextSize 决定。
                    MarkdownText(text = plain)
                }
            }
        }
        }
        if (panelRes.panels.isNotEmpty()) {
            panelRes.panels.forEach { p ->
                Spacer(Modifier.height(7.dp))
                com.dshwatch.ui.components.PanelCard(panel = p)
            }
        }

        if (!isUser && parsed.hasChoices && message.status != MessageStatus.STREAMING) {
            Spacer(Modifier.height(7.dp))
            com.dshwatch.ui.components.ChoiceButtons(
                choices = parsed.choices,
                enabled = canPick,
                onPick = onChoose,
            )
        }

        if (message.status == MessageStatus.ERROR) {
            Text(
                text = message.error.ifBlank { "出错了" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 3.dp),
            )
        }
    }
}

/** 空会话：把开场白亮出来，给一个明确的「开始」按钮 */
@Composable
private fun OpeningHint(card: RoleCard?, isWriting: Boolean, onStart: () -> Unit) {
    val greet = card?.greeting.orEmpty().trim()
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        WatchSection {
            SectionHeader(if (isWriting) "开头" else "开场白")
            Text(
                text = greet.ifBlank { "（这张卡没有写开场白，直接说话即可）" },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        WatchButton(
            text = if (isWriting) "从这里接着写" else "开始对话",
            onClick = onStart,
        )
        Spacer(Modifier.height(6.dp))
        HintText(
            if (isWriting) "模型会接着上面的文字往下写；想调整方向就直接说"
            else "设定只在开始时生效，之后只带最近的对话（开了长期记忆则只带记忆摘要）"
        )
    }
}

/** 底栏：和编程页同尺寸，但去掉模型选择与 Agent 入口 */
@Composable
private fun RoleplayInputBar(
    streaming: Boolean,
    hint: String,
    onOpenInput: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(56.dp)
            .glassSurface(
                shape = RoundedCornerShape(20.dp),
                cornerRadius = 20.dp,
                tint = MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .glassSurface(
                    shape = RoundedCornerShape(14.dp),
                    cornerRadius = 14.dp,
                    tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = withHaptic(onOpenInput),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .glassSurface(
                    shape = RoundedCornerShape(14.dp),
                    cornerRadius = 14.dp,
                    tint = if (streaming) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    onClick = withHaptic(if (streaming) onStop else onSend),
                    solid = true,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (streaming) Icons.Outlined.Stop else Icons.Filled.Send,
                contentDescription = if (streaming) "停止" else "发送",
                tint = if (streaming) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
