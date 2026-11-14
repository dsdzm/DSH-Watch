package com.dshwatch.ui.screens.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import com.dshwatch.ui.wear.wearVerticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.R
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.Choices
import com.dshwatch.domain.LiveDelta
import com.dshwatch.domain.Panels
import com.dshwatch.domain.TokenEstimator
import com.dshwatch.domain.ApprovalDecision
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatLayouts
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
import com.dshwatch.ui.components.ChoiceButtons
import com.dshwatch.ui.components.ConfirmDialog
import com.dshwatch.ui.components.ContextBar
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.PanelCard
import com.dshwatch.ui.components.IconTap
import com.dshwatch.ui.components.CircleIconTap
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.ShareResult
import com.dshwatch.ui.components.shareFile
import com.dshwatch.ui.components.TokenBadge
import com.dshwatch.ui.components.RemoteApprovalDialog
import com.dshwatch.ui.components.ToolApprovalDialog
import com.dshwatch.ui.components.ToolCallCard
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.GlassDialogRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.markdown.MarkdownText
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.scene.ScenePickerDialog
import com.dshwatch.ui.screens.scene.sceneIcon
import com.dshwatch.ui.components.cornerSafe
import com.dshwatch.ui.theme.LocalDeviceProfile
import com.dshwatch.ui.theme.LocalPerf
import com.dshwatch.ui.theme.WatchDimens
import com.dshwatch.util.Fmt
import com.dshwatch.util.findActivity
import kotlinx.coroutines.launch
import java.io.File
import com.dshwatch.ui.theme.LocalDarkTheme
import com.dshwatch.ui.theme.LocalGlass
import androidx.compose.foundation.interaction.MutableInteractionSource

@Composable
fun ChatScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val perf = LocalPerf.current
    val profile = LocalDeviceProfile.current
    val round = profile.isRound
    /*
     * 圆屏的内边距由骨架统一给。
     *
     * 上一版这里自己算 `safePad = 0.30d`（99dp）去躲开上下两条弧形 dock，
     * 结果对话被挤成中间一条 200dp 的窄带，上下各留一大片空白 ——
     * 用户的原话是「对话框上下内容消失」。
     *
     * 现在骨架回到内接矩形：内容区 221×227dp 整个落在圆内，
     * 纵向居中、四角不越界，列表既不会被圆周切掉，也不需要任何躲闪的内边距。
     */
    val sidePad = 0.dp
    val safePad = 0.dp
    val chatLayout = ChatLayouts.of(settings.chatLayout)
    val engine = container.engine
    val scope = rememberCoroutineScope()

    /*
     * 场景裁剪。
     *
     * 编程场景是"干活"：要看到思维链、工具调用、Token 账、Agent 档位，这些信息都有用。
     * 酒馆/写作是"聊天"：用户只想知道对方说了什么。思维链和工具卡在这里不是信息，
     * 是噪音 —— 角色说着话突然弹一段"思考中：我应该保持人设"，戏就断了。
     *
     * ChatScreen 是全 App 唯一一处编程场景的入口（酒馆/写作走 RoleplayChatScreen），
     * 所以这里只按 useScene 一个开关收敛，不用管会话自己带什么 scene。
     */
    val codingScene = Scenes.usesAgent(settings.useScene)

    val conversation by engine.conversation.collectAsStateWithLifecycle()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val streaming by engine.streaming.collectAsStateWithLifecycle()
    val banner by engine.banner.collectAsStateWithLifecycle()

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val activityView = androidx.compose.ui.platform.LocalView.current
    val listState = rememberLazyListState()
    // 表冠滚动：Wear 上不用手指、直接转表冠翻历史是最自然的手势。
    // 焦点必须先给到列表，否则表冠事件会被别的可聚焦元素吃掉。
    val rotaryFocus = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    LaunchedEffect(Unit) { runCatching { rotaryFocus.requestFocus() } }
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentDialog by remember { mutableStateOf(false) }
    var showScenePicker by remember { mutableStateOf(false) }

    val approval by engine.approval.collectAsStateWithLifecycle()
    val pendingQuestion by engine.question.collectAsStateWithLifecycle()

    // 电脑上的审批：远端协议只回答「这一次」，所以不放进 engine 的那套状态机
    val remoteApprovals by container.dshLink.approvals.collectAsStateWithLifecycle()

    // 把「只有界面能做」的动作接上：open_screen / open_file 这两个工具靠它落地。
    // 用 DisposableEffect 而不是 LaunchedEffect —— 离开聊天页时必须摘掉，
    // 否则通道里会留着一个指向已销毁导航栈的 lambda。
    DisposableEffect(Unit) {
        container.uiActions.previewFile = { path -> nav.push(Screen.WorkspacePreview(path)) }
        container.uiActions.openScreen = { key -> openScreen(nav, key) }
        // 设备操控：AI 去操作别的 App 时，把对话页收进后台。
        // 不然用户看不到「屏幕上在发生什么」，模型读到的也永远是自己。
        container.uiActions.minimize = {
            val activity = context.findActivity()
            activity?.moveTaskToBack(true)
        }
        onDispose {
            container.uiActions.previewFile = null
            container.uiActions.openScreen = null
            container.uiActions.minimize = null
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

    /*
     * 圆屏「鱼眼」已整套移除（1.15.7）。
     *
     * 原先这里用 `derivedStateOf` 读 `layoutInfo.visibleItemsInfo`，把每一项离
     * 屏幕中心的偏移归一化成 t，再交给 `roundRow` 改 padding / scale / alpha。
     * 问题出在两个地方，合起来就是用户说的「对话框显示异常、文字抽搐」：
     *
     *   · `padding(horizontal = extra)` 每帧随滚动变化 → 每一项被反复重新测量，
     *     文本可用宽度不停变，换行位置逐帧跳；
     *   · `graphicsLayer { scaleX/scaleY }` 把已经栅格化的文字再缩放，
     *     滚动时字号看起来在抖，窄气泡尤其明显。
     *
     * 圆屏的安全区由 `WatchScaffold.cornerSafe()` 和气泡自身的 `isRound` 宽度
     * 负责，不需要再对每一项做动态几何变换。
     */

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
            // +1 是那个 __info__ 头项：列表里第 0 项已经不是第一条消息了
            runCatching { listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) }
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
        // 对话页是根：没有返回，靠右滑去设置
        onBack = null,
        actions = {
            /*
             * 顶栏四颗：历史 / 新建 / 切换模式 / 设置。
             *
             * 圆屏用 32dp 的 CircleIconTap，方屏用 40dp 的 IconTap —— 圆屏顶栏那一行
             * 的可用弦宽只有约 170dp，四颗 40dp 会把标题挤没。
             *
             * 顶栏自身**不带任何背景板**：它直接落在页面的 surface 上，
             * 只有图标和标题，不和内容争地盘。
             */
            val tap: @Composable (androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit) -> Unit =
                { icon, desc, onClick ->
                    if (round) {
                        CircleIconTap(icon = icon, contentDescription = desc, onClick = onClick)
                    } else {
                        IconTap(icon = icon, contentDescription = desc, onClick = onClick)
                    }
                }
            tap(Icons.Outlined.History, s.navSessions) { nav.push(Screen.Sessions) }
            if (codingScene) {
                tap(Icons.Outlined.Add, s.newChat) {
                    // 显式写死 scene：默认值 "" 在引擎里等同 coding，
                    // 但会话一旦被标成 tavern 再回来新建，就得靠这个字段把场景掰回来。
                    scope.launch { engine.newConversation(settings, scene = Scenes.CODING) }
                }
                tap(sceneIcon(settings.useScene), "切换模式") { showScenePicker = true }
            }
            tap(Icons.Outlined.Settings, s.navSettings) { nav.push(Screen.Settings) }
        },
        // 圆屏的底栏是 46dp 圆 FAB，浮在内容上，不占竖向空间
        bottomOverlay = round,
        bottom = {
            ChatBottomBar(
                settings = settings,
                streaming = streaming,
                round = round,
                onOpenInput = { nav.push(Screen.Input()) },
                onSend = { nav.push(Screen.Input()) },
                onStop = { engine.stop() },
                onModelClick = { showModelPicker = true },
            )
        },
    ) {
        if (provider == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = sidePad, vertical = safePad),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有配置供应商",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                /*
                 * 圆屏不显示这句引导。
                 *
                 * 内容区在圆屏上只剩 83dp 高（顶栏 70、FAB 48），
                 * 标题 + 两行说明 + 按钮要 100dp —— 按钮会被挤到底部边缘外面，
                 * 用户看着按钮在那里，按下去没反应。宁可少一句解释。
                 */
                if (!round) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "直连自己的 API Key，或连接电脑上的 DSH 服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(if (round) 8.dp else 10.dp))
                PrimaryButton(
                    text = s.addProvider,
                    icon = Icons.Filled.Add,
                    onClick = { nav.push(Screen.Providers) },
                )
            }
            return@WatchScaffold
        }

        if (messages.isEmpty()) {
            /*
             * 圆屏空状态就是原来这一套（吉祥物 + 一句提示 + B站入口），
             * 但内容区只有 221×227dp：吉祥物 112 + 两行说明 + 两颗胶囊会超。
             * 给圆屏加一层纵向滚动兜底 —— 不改变外观，只在装不下时能翻。
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sidePad, vertical = safePad)
                    .then(
                        if (round) Modifier.wearVerticalScroll(rememberScrollState()) else Modifier
                    ),
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
                EmptyState(
                    title = if (codingScene) s.chatEmptyTitle else s.roleplayEmptyTitle,
                    hint = if (codingScene) s.chatEmptyHint else s.roleplayEmptyHint,
                )
                // 手表上打字是最贵的一步操作。刷 B站 是个固定流程，
                // 给它一个一句话就能触发的入口，比让用户敲「帮我刷一遍B站」现实得多。
                if (settings.biliEnabled && codingScene) {
                    val loggedIn = remember(settings.biliUserName) {
                        container.keyStore.getBiliCookie().orEmpty().isNotBlank()
                    }
                    Spacer(Modifier.height(10.dp))
                    Chip(
                        text = "刷一轮 B站",
                        onClick = {
                            engine.send(
                                "刷一轮 B站：按我关注的关键词挑几条真正值得看的，读字幕判断，" +
                                    "把挑中的写进今天的日报"
                            )
                        },
                    )
                    // 「有人回我吗」是刷B站的另一半：只发不看等于自言自语。
                    // 没登录时这个入口没有意义（看不到任何通知），所以直接不显示。
                    if (loggedIn) {
                        Spacer(Modifier.height(8.dp))
                        Chip(
                            text = "看看谁回了我",
                            onClick = {
                                engine.send(
                                    "看看谁回复了我的评论：挑值得接话的，用我平时的语气回一条，" +
                                        "先给我看预览"
                                )
                            },
                        )
                    }
                }
            }
            return@WatchScaffold
        }

        // 消息区右下角：往上翻看历史时一键回到最新。
        // 放在消息区的 Box 里，而不是 WatchScaffold 的 bottom 槽 —— 后者是输入框。
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .rotaryScrollable(rotaryBehavior, rotaryFocus)
                    .focusRequester(rotaryFocus)
                    .focusable(),
                /*
                 * 底部留 44dp：圆屏的输入按钮是**浮层**，压在内容区最后 19dp 上。
                 * 不留这段 padding，最新一条消息的「复制 / 重新生成」那排按钮
                 * 正好被 46dp 的圆按钮压住 —— 看得见，点不到。
                 */
                contentPadding = PaddingValues(
                    top = 2.dp,
                    bottom = if (round) 40.dp else 2.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
            /*
             * 模型胶囊 / 上下文条 / 提示条都作为**列表第一项**。
             *
             * 它们原来钉在消息区上方，占掉 40dp 的固定高度 —— 圆屏上这 40dp
             * 是从正文里硬挖的。改成列表项之后，用户往下翻消息时它会自己滚走，
             * 想改模型再翻回顶部即可。
             */
            item(key = "__info__") {
                Column(modifier = Modifier.fillMaxWidth()) {
                // 顶部信息条：模型 + 上下文占用
                // 非编程场景只留一行"生成中"提示，模型名/Agent 档/上下文条全部收掉 ——
                // 圆屏顶栏本来就只有三行位置，塞满就别指望用户读到正文。
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!codingScene) {
                            Text(
                                text = conversation?.displayTitle ?: s.appName,
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (streaming) {
                                Text(
                                    text = s.generating,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                        if (codingScene) {
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
                    }
                    if (perf.enableAnimations && codingScene) {
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
                }
            }
                itemsIndexed(
                    items = turns,
                    key = { _, turn -> turn.key },
                    contentType = { _, turn -> turn.messages.first().role },
                ) { turnIndex, turn ->
                    val msgs = turn.messages
                    // 只有最后一个回合的选项能点：历史里的 A/B/C 点下去就是一条过期回复
                    val isLastTurn = turnIndex == turns.lastIndex
                    val chooseHandler: (Choices.Choice) -> Unit = { c ->
                        if (c.custom) nav.push(Screen.Input()) else engine.send(c.raw)
                    }
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

                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (msgs.size == 1) {
                            val message = msgs.first()
                            val isLive = live.messageId == message.id
                            MessageItem(
                                message = message,
                                live = if (isLive) live else null,
                                settings = settings,
                                layout = chatLayout,
                                isRound = round,
                                onCopy = copyText,
                                onRegenerate = { engine.regenerate(message.id) },
                                onDelete = { pendingDelete = message },
                                sizeOf = sizeOf,
                                onOpenArtifact = openArtifact,
                                onExportArtifact = exportArtifact,
                                onOpenHtml = { html -> nav.push(Screen.HtmlView(html)) },
                                onScrollBy = { d -> listState.dispatchRawDelta(d) },
                                canPick = isLastTurn,
                                onChoose = chooseHandler,
                                showWork = codingScene,
                            )
                        } else {
                            // 多步 Agent 回合：连续助手消息合成一条，只显示一条工作流程卡片
                            val isLive = live.messageId == msgs.last().id
                            AgentTurnItem(
                                messages = msgs,
                                live = if (isLive) live else null,
                                settings = settings,
                                layout = chatLayout,
                                isRound = round,
                                onCopy = copyText,
                                onRegenerate = { engine.regenerate(msgs.last().id) },
                                onDelete = { pendingDelete = msgs.last() },
                                sizeOf = sizeOf,
                                onOpenArtifact = openArtifact,
                                onExportArtifact = exportArtifact,
                                onOpenHtml = { html -> nav.push(Screen.HtmlView(html)) },
                                onScrollBy = { d -> listState.dispatchRawDelta(d) },
                                canPick = isLastTurn,
                                onChoose = chooseHandler,
                                showWork = codingScene,
                            )
                        }

                        if (settings.showTokenBadge && codingScene) {
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
                }
                item(key = "__tail__") { Spacer(Modifier.height(6.dp)) }
            }

            // 圆屏专属：右侧贴边的滚动弧线 + 当前位置圆点。
            // 圆屏没有「上下边界」这条视觉线索，滚动位置只能靠这条弧来读。
            if (round && turns.isNotEmpty()) {
                RoundScrollArc(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            if (!atBottom && turns.isNotEmpty()) {
                // 带底色的圆形按钮：纯图标直接浮在消息上会看不清
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // 圆屏底部有一个 46dp 的圆 FAB：这个按钮要浮到它上面去
                        .padding(end = 2.dp, bottom = if (round) 46.dp else 2.dp)
                        .glassSurface(shape = CircleShape),
                ) {
                    IconTap(
                        icon = Icons.Filled.KeyboardArrowDown,
                        contentDescription = s.backToBottom,
                        tint = MaterialTheme.colorScheme.primary,
                    ) {
                        scope.launch {
                            runCatching {
                                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                            }
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

    remoteApprovals.firstOrNull()?.let { pending ->
        RemoteApprovalDialog(
            pending = pending,
            onDecision = { allow ->
                scope.launch {
                    runCatching { container.dshLink.respondApproval(pending.eventId, allow) }
                }
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

    if (showScenePicker) {
        ScenePickerDialog(
            current = settings.useScene,
            onPick = { id ->
                showScenePicker = false
                if (id == Scenes.of(settings.useScene)) return@ScenePickerDialog
                // 先落盘再跳转：useScene 决定 ChatScreen 与 RoleplayChatScreen 各自渲染哪一套界面，
                // 顺序反了会出现"已经跳到酒馆列表、但列表里还是编程场景的标题"的一帧错位。
                scope.launch { container.settingsRepo.update { it.copy(useScene = id) } }
                when (id) {
                    Scenes.TAVERN -> { nav.popToRoot(); nav.push(Screen.TavernHome) }
                    Scenes.WRITING -> { nav.popToRoot(); nav.push(Screen.WritingHome) }
                    // 编程就在当前页，popToRoot 即可 —— 若之前停在某个深层页面，
                    // 这一下也顺手把栈收干净。
                    else -> nav.popToRoot()
                }
            },
            onDismiss = { showScenePicker = false },
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

/**
 * 消息气泡的公共骨架：角色标签在**气泡上方**，气泡本身只装内容。
 *
 * 参考图的结构：`我` / `DSH` 是独立的一行小字，气泡是纯粹的圆角容器。
 * 用户气泡略窄、偏蓝灰；助手气泡满宽、中性深灰 —— 这两种颜色已经足够
 * 区分说话人，不需要左右对齐（手表屏幕经不起左右留白）。
 */
@Composable
private fun BubbleScaffold(
    isUser: Boolean,
    label: String,
    showMascot: Boolean,
    layout: String,
    isRound: Boolean,
    headerTrailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    /*
     * 用户气泡的底色必须跟着主题走。
     *
     * 原来写死 `0xFF4A5670`（深蓝灰）+ `0xFFF2F2F2`（近白字），在深色主题下是对的，
     * 但浅色主题里正文用的是 `colorScheme.onSurface`（深色字），深底深字直接糊成一片 ——
     * 也就是「用户发的话看不清」。
     *
     * 判断明暗**问主题**，不再看 `background` 的亮度。
     *
     * 旧写法是 `background.luminance() > 0.5`，理由是「Material3 没有现成的
     * isLight()」。但玻璃预设的 background 是透明的，而 luminance() 只看 RGB ——
     * 透明色恒为 0，于是浅色模式下这条判断永远是「深色」，气泡就一直用深底白字，
     * 和同一屏里深色的正文对不上。明暗是主题的属性，`LocalDarkTheme` 直接给。
     */
    val light = !LocalDarkTheme.current
    val glass = LocalGlass.current
    val bubbleColor = when {
        isUser && light -> MaterialTheme.colorScheme.secondaryContainer
        isUser -> Color(0xFF4A5670)
        // 玻璃主题下 AI 气泡不填色：交给 glassSurface 的半透明白，
        // 填 surfaceContainerHigh 会把底下的色晕整块盖掉，气泡就成了深灰砖
        glass -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        isUser && light -> MaterialTheme.colorScheme.onSecondaryContainer
        isUser -> Color(0xFFF2F2F2)
        else -> MaterialTheme.colorScheme.onSurface
    }
    when (layout) {
        /*
         * 极简：去掉所有底色，只靠一条细分隔线划分回合。
         * 小屏上这是「同样的内容多读两行」的最直接办法。
         */
        ChatLayouts.MINIMAL -> Column(modifier = Modifier.fillMaxWidth()) {
            BubbleHeader(isUser, label, showMascot, headerTrailing)
            Column(
                modifier = Modifier
                    .fillMaxWidth(if (isUser) 0.9f else 1f)
                    .padding(start = if (isUser) 6.dp else 0.dp),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                ) { content() }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            )
        }

        /*
         * 卡片：每条一张独立卡片，左侧一根 3dp 的强调竖条 + 标签。
         * 信息分层最清楚，代价是比气泡多占一点纵向空间。
         */
        ChatLayouts.CARD -> Column(modifier = Modifier.fillMaxWidth()) {
            BubbleHeader(isUser, label, showMascot, headerTrailing)
            Row(
                modifier = Modifier
                    .fillMaxWidth(if (isUser) (if (isRound) 0.92f else 0.94f) else 1f)
                    .then(
                        if (glass && bubbleColor == Color.Transparent) {
                            Modifier.glassSurface(shape = RoundedCornerShape(16.dp), cornerRadius = 16.dp)
                        } else {
                            Modifier.clip(RoundedCornerShape(16.dp)).background(bubbleColor)
                        },
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .heightIn(min = 28.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (isUser) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary,
                        ),
                )
                Column(
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 9.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides textColor) {
                        content()
                    }
                }
            }
        }

        else -> Column(modifier = Modifier.fillMaxWidth()) {
            BubbleHeader(isUser, label, showMascot, headerTrailing)
            Column(
                modifier = Modifier
                    .fillMaxWidth(
                        if (isUser) {
                            if (isRound) 0.86f else 0.94f
                        } else {
                            1f
                        }
                    )
                    .then(
                        if (glass && bubbleColor == Color.Transparent) {
                            Modifier.glassSurface(
                                shape = RoundedCornerShape(if (isRound) 22.dp else 26.dp),
                                cornerRadius = if (isRound) 22.dp else 26.dp,
                            )
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(if (isRound) 22.dp else 26.dp))
                                .background(bubbleColor)
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides textColor) {
                    content()
                }
            }
        }
    }
}

/** 气泡上方的角色行：头像 + 名字 + 右侧状态（流式中 / 出错…） */
@Composable
private fun BubbleHeader(
    isUser: Boolean,
    label: String,
    showMascot: Boolean,
    trailing: @Composable RowScope.() -> Unit,
    centered: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (centered) 0.dp else 4.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        if (!isUser && showMascot) {
            Image(
                painter = painterResource(R.drawable.mascot_avatar),
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.outline,
        )
        trailing()
    }
}

/**
 * 圆屏右侧的滚动弧线：一条贴着屏幕右缘的细弧 + 当前位置的圆点。
 *
 * 圆屏没有「上下边界」这条视觉线索，用户不知道自己是在中间还是快到头了。
 * 方屏靠列表两端露出的边缘就能判断，圆屏需要这条弧来补上。
 */
@Composable
private fun RoundScrollArc(
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total <= 1) return
    val first = info.visibleItemsInfo.firstOrNull() ?: return
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    val scrollable = total - (last.index - first.index) - 1
    val progress = if (scrollable <= 0) {
        0f
    } else {
        (first.index.toFloat() / scrollable.toFloat()).coerceIn(0f, 1f)
    }
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val thumb = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)

    Canvas(
        modifier = modifier
            .fillMaxHeight()
            .width(10.dp)
            .padding(vertical = 26.dp),
    ) {
        val r = size.width / 2f
        val x = size.width / 2f
        // 轨道：从上到下的一条 2dp 细线
        drawLine(
            color = track,
            start = androidx.compose.ui.geometry.Offset(x, r),
            end = androidx.compose.ui.geometry.Offset(x, size.height - r),
            strokeWidth = 2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // 位置点：跟着滚动进度上下走
        val dotY = (r + (size.height - 2 * r) * progress).coerceIn(r, size.height - r)
        drawCircle(color = thumb, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, dotY))
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    live: LiveDelta?,
    settings: AppSettings,
    layout: String,
    isRound: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    sizeOf: (String) -> Long,
    onOpenArtifact: (String) -> Unit,
    onExportArtifact: (String) -> Unit,
    onOpenHtml: (String) -> Unit,
    // 内嵌 HTML 界面里的竖向拖动要转给列表，否则手指在界面上划不动
    onScrollBy: (Float) -> Unit,
    /** 这条消息是不是最新一条 —— 只有最新的选项可以点 */
    canPick: Boolean,
    onChoose: (Choices.Choice) -> Unit,
    // 编程场景才画思维链/工具卡。酒馆/写作场景下这些都是噪音。
    showWork: Boolean = true,
) {
    val s = LocalStrings.current
    val body = live?.text ?: message.content
    val reasoning = live?.reasoning ?: message.reasoning
    val isUser = message.role == Role.USER
    val isStreaming = live != null
    // 查看器挂在消息内部：退出就是关一个 Dialog，不会把导航栈搞乱
    var viewingMedia by remember(message.id) {
        mutableStateOf<com.dshwatch.domain.model.MediaRef?>(null)
    }

    /*
     * 自定义界面：整段 HTML 文档不当作正文渲染。
     *
     * 手机上可以内联一个 WebView，手表上不行 —— 221dp 宽的页面缩进去没法看，
     * 而且 WebView 会和列表抢滚动。所以正文里只留一张入口卡片，点开是全屏。
     */
    // 流式期间不扫 HTML：每个字都要跑一次正则，手表上这直接就是掉帧。
    // 生成结束后再判断一次，卡片出现得晚半秒，但滚动是顺的。
    val htmlDoc = remember(body, isStreaming) {
        if (isStreaming) null else com.dshwatch.ui.html.HtmlBlocks.find(body)
    }
    val rawText = if (htmlDoc != null) com.dshwatch.ui.html.HtmlBlocks.strip(body, htmlDoc) else body
    // ```panel 围栏抽成原生面板；流式期间半截围栏还没闭合，解析也没有意义
    val panelRes = remember(rawText, isStreaming) {
        if (isStreaming) Panels.Result(rawText, emptyList()) else Panels.extract(rawText)
    }
    val afterPanel = if (panelRes.panels.isNotEmpty()) panelRes.body else rawText
    // 尾部若是 A/B/C 选项，拆出来单独渲染成按钮，正文只留前面那段
    val parsed = remember(afterPanel) { Choices.parse(afterPanel) }
    val bodyText = if (parsed.hasChoices) parsed.body else afterPanel

    BubbleScaffold(
        isUser = isUser,
        label = if (isUser) "我" else "DSH",
        showMascot = settings.showMascot,
        layout = layout,
        isRound = isRound,
        headerTrailing = {
            if (message.stepIndex > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${s.stepLabel} ${message.stepIndex}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (isStreaming) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (reasoning.isNotBlank() && body.isBlank()) s.thinking else s.generating,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!isUser && message.status == MessageStatus.ERROR) {
                Text(
                    text = s.sendFailed,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!isUser && message.status == MessageStatus.STOPPED) {
                Text(
                    text = s.stopped,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
    ) {
        // 思考与工具调用收进同一个「工作块」：执行中展开、完成后折叠成摘要
        if (showWork && !isUser &&
            (message.toolCalls.isNotEmpty() || (settings.showReasoning && reasoning.isNotBlank()))
        ) {
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
            Spacer(Modifier.height(6.dp))
        }

        /*
         * 用户上传的文件：列表形式放在正文上方。
         *
         * 不做成可点开的查看器 —— 手表上打开一份 60k 字的文档没有任何意义，
         * 用户要确认的是「我到底传了哪几个文件、模型有没有读到内容」，
         * 名字 + 字符数就把这两件事说完了。
         */
        if (message.attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                message.attachments.forEach { att ->
                    Text(
                        text = "📄 ${att.name} · ${att.summary()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (att.ok) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (bodyText.isNotBlank() || message.media.isNotEmpty()) Spacer(Modifier.height(6.dp))
        }

        /*
         * 媒体放在正文之前。
         *
         * 出图 / 出视频的回复，正文通常只有一句「画好了」甚至完全为空 ——
         * 用户要的是那张图。把图塞在文字下面，等于每次都要先划过一行废话。
         */
        if (message.media.isNotEmpty()) {
            com.dshwatch.ui.media.MediaStrip(
                media = message.media,
                onOpen = { viewingMedia = it },
            )
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
            if (isUser) {
                /*
                 * 节选复制：长按气泡里的文字会弹出系统选择手柄，可以只拖一段复制，
                 * 不必整条搬走。「复制」按钮保留整条复制的快捷方式 ——
                 * 手表上长按拖动本来就不轻松，两种入口各有用处。
                 *
                 * 颜色用 LocalContentColor 而不是写死 onSurface：气泡底色已经
                 * 随主题变化（浅色模式是 secondaryContainer），文字颜色必须跟着
                 * 气泡走，否则就是「深底深字」。
                 */
                SelectionContainer {
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current,
                    )
                }
            } else {
                SelectionContainer {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        MarkdownText(text = bodyText)
                    }
                }
            }
        } else if (isStreaming) {
            Text(
                text = "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (panelRes.panels.isNotEmpty()) {
            panelRes.panels.forEach { p ->
                Spacer(Modifier.height(7.dp))
                PanelCard(panel = p)
            }
        }

        if (!isUser && parsed.hasChoices && !isStreaming) {
            Spacer(Modifier.height(8.dp))
            ChoiceButtons(
                choices = parsed.choices,
                enabled = canPick,
                onPick = onChoose,
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

        // 操作行常显：复制 / 重试 / 删除不该藏在「先点一下消息」后面。
        // 纯媒体消息（出图 / 出视频）没有正文，但「重新生成」恰恰是它最常用的操作。
        if (!isStreaming && (body.isNotBlank() || message.hasMedia)) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (body.isNotBlank()) MsgAction(text = s.copy) { onCopy(body) }
                if (!isUser) {
                    MsgAction(text = s.regenerate) { onRegenerate() }
                    MsgAction(text = s.delete) { onDelete() }
                }
            }
        }

        viewingMedia?.let { ref ->
            com.dshwatch.ui.media.MediaViewer(ref = ref, onClose = { viewingMedia = null })
        }
    }
}

@Composable
private fun AgentTurnItem(
    messages: List<ChatMessage>,
    live: LiveDelta?,
    settings: AppSettings,
    layout: String,
    isRound: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    sizeOf: (String) -> Long,
    onOpenArtifact: (String) -> Unit,
    onExportArtifact: (String) -> Unit,
    onOpenHtml: (String) -> Unit,
    onScrollBy: (Float) -> Unit,
    canPick: Boolean,
    onChoose: (Choices.Choice) -> Unit,
    showWork: Boolean = true,
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

    BubbleScaffold(
        isUser = false,
        label = "DSH",
        showMascot = settings.showMascot,
        layout = layout,
        isRound = isRound,
        headerTrailing = {
            if (isStreaming) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (reasoning.isNotBlank() && answer.isBlank()) s.thinking else s.generating,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (hasError) {
                Text(
                    text = s.sendFailed,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (last.status == MessageStatus.STOPPED) {
                Text(
                    text = s.stopped,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
    ) {
        if (showWork) {
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
        } else {
            // 非编程场景：多步回合也只把最后那段正文当回答，
            // 中间步骤的"我先看看……"不展示 —— 那是过程，不是结果。
            val plain = messages.lastOrNull {
                it.content.isNotBlank() && it.status != MessageStatus.PENDING
            }?.content.orEmpty()
            if (plain.isNotBlank()) MarkdownText(text = plain)
        }

        // 面板与选项都从正文里剥出去，各自由专门控件渲染
        val answerPanels = remember(answer, isStreaming) {
            if (isStreaming) Panels.Result(answer, emptyList()) else Panels.extract(answer)
        }
        val answerNoPanel = if (answerPanels.panels.isNotEmpty()) answerPanels.body else answer
        val answerParsed = remember(answerNoPanel) { Choices.parse(answerNoPanel) }
        val answerBody = if (answerParsed.hasChoices) answerParsed.body else answerNoPanel

        if (answerBody.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            val htmlDoc = remember(answerBody) { com.dshwatch.ui.html.HtmlBlocks.find(answerBody) }
            if (htmlDoc != null) {
                com.dshwatch.ui.html.InlineHtml(
                    html = htmlDoc.html,
                    onOpenFull = onOpenHtml,
                    onDrag = onScrollBy,
                )
                val rest = com.dshwatch.ui.html.HtmlBlocks.strip(answerBody, htmlDoc)
                if (rest.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    MarkdownText(text = rest)
                }
            } else {
                MarkdownText(text = answerBody)
            }
        }

        if (answerPanels.panels.isNotEmpty()) {
            answerPanels.panels.forEach { p ->
                Spacer(Modifier.height(7.dp))
                PanelCard(panel = p)
            }
        }

        if (answerParsed.hasChoices && !isStreaming) {
            Spacer(Modifier.height(8.dp))
            ChoiceButtons(
                choices = answerParsed.choices,
                enabled = canPick,
                onPick = onChoose,
            )
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
            Spacer(Modifier.height(8.dp))
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
            .glassSurface(shape = RoundedCornerShape(10.dp), cornerRadius = 10.dp)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 卡片本身不可点，行可点 —— 所以 feedback 只能来自 tap()，
                // 这里不能 quiet
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
            .glassSurface(
                shape = RoundedCornerShape(9.dp),
                cornerRadius = 9.dp,
                onClick = withHaptic(onClick),
            )
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
    round: Boolean,
    onOpenInput: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onModelClick: () -> Unit,
) {
    val s = LocalStrings.current
    val sendSrc = remember { MutableInteractionSource() }

    /*
     * 圆屏底栏 = 一个圆 FAB。
     *
     * 横贯的底栏在圆屏上根本不成立：底栏下沿那一行的弦宽只有 70~100dp，
     * 而底栏要做 190dp 宽 —— 两端必然被圆啃掉，看起来就是「输入框被斜着切了」。
     * 1.15.7 的修补（左右各收 10dp）治不了本，因为问题出在**高度方向**：
     * 越往下弦宽越小，收左右完全没用。
     *
     * 收成一个 46dp 的圆，在任何一行都放得下。代价是少了「一句话预览」，
     * 但手表上输入框本来就只是「点一下进输入页」的跳板，预览没有价值。
     */
    if (round) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 只留 2dp：这一行下面就是圆周了，多留一 dp 都是从内容区偷的高度。
                // 内容区在圆屏上本来就只剩 83dp（顶栏 74、FAB 区 48），
                // 之前留 10dp 等于又切掉 8dp —— 一屏刚好少显示半条消息。
                .padding(bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .glassSurface(
                        shape = CircleShape,
                        cornerRadius = 23.dp,
                        tint = if (streaming) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        solid = true,
                        onClick = withHaptic { if (streaming) onStop() else onSend() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (streaming) Icons.Filled.Stop else Icons.Outlined.Keyboard,
                    contentDescription = if (streaming) s.stop else s.inputHint,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        return
    }

    // 圆屏底部两侧是空的：左右再收 10dp，避免两个下角被圆切掉
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .cornerSafe()
            .glassSurface(
                shape = RoundedCornerShape(WatchDimens.cardRadius),
                cornerRadius = WatchDimens.cardRadius,
                tint = MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .glassSurface(
                    shape = RoundedCornerShape(12.dp),
                    cornerRadius = 12.dp,
                    tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = withHaptic(onOpenInput),
                ),
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
                    // 发送键：玻璃负责画与形变，事件交给 tap(quiet)。
                    // 主按钮用 solid —— 反馈光强度自动减半（见 drawGlassPanel）
                    .glassSurface(
                        shape = RoundedCornerShape(12.dp),
                        cornerRadius = 12.dp,
                        tint = MaterialTheme.colorScheme.primary,
                        interaction = sendSrc,
                        solid = true,
                    )
                    .tap(quiet = true, interaction = sendSrc, onClick = withHaptic(onSend)),
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
    GlassDialog(
        onDismissRequest = onDismiss,
        title = s.switchModel,
        maxContentHeight = 260,
        content = {
            if (models.isEmpty()) {
                Text(s.none, style = MaterialTheme.typography.bodySmall)
            } else {
                models.forEach { model ->
                    GlassDialogRow(
                        title = model,
                        selected = model == current,
                        onClick = { onPick(model) },
                    )
                }
            }
        },
        buttons = { GlassDialogButton(s.cancel, onClick = onDismiss) },
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
