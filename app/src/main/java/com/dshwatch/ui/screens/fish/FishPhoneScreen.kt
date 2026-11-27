package com.dshwatch.ui.screens.fish

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.dshwatch.data.attachment.AttachmentStore
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.model.AttachmentKinds
import com.dshwatch.domain.model.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.dshwatch.core.AppContainer
import com.dshwatch.ui.screens.files.rememberFilePicking
import com.dshwatch.ui.theme.CustomBackground
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.BuiltinCards
import com.dshwatch.domain.roleplay.FishBubbles
import com.dshwatch.domain.roleplay.FishRhythm
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.service.FishMomentWriter
import com.dshwatch.ui.components.rememberCameraShot
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.phone.PhoneChatHeader
import com.dshwatch.ui.phone.PhoneComposer
import com.dshwatch.ui.phone.PhoneHistoryDrawer
import com.dshwatch.ui.phone.PhoneModelSheet
import com.dshwatch.ui.phone.PhoneNavigator
import com.dshwatch.ui.phone.PhoneTab
import com.dshwatch.ui.phone.phoneModelLabel
import com.dshwatch.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「养鱼」的手机版：和内置角色大肥鱼的一对一聊天，按聊天软件的样子做。
 *
 * ## 为什么单独一页，而不是复用酒馆的对话页
 *
 * 酒馆的对话页是"角色扮演阅读器"：大字、说话人名字、每条一大段、
 * 长对话靠滚动。它要撑住世界观、面板、分支选项。
 *
 * 而这一页要假装是聊天软件：左右气泡、圆形头像、时间戳、连发几条短消息。
 * 两种排版对同一条数据的处理方式完全不同（一条消息在酒馆里是一个段落，
 * 在这里是 1~3 个气泡），硬塞进一个 Composable 只会变成两组互斥分支。
 *
 * ## 手表不走这一页
 *
 * 圆表盘上放不下"左右两列 + 两个头像"：屏宽换算下来 220dp，分出两侧留白
 * 之后每列只剩 80dp。手表版是 [FishWatchScreen]（M3E 卡片 + 全宽气泡），
 * 分派在 [FishScreen]。
 *
 * ## 节奏是这一页的另一半功能
 *
 * 模型永远"一次说完"。直接铺到屏幕上就是打印机，再口语的内容也不像人。
 * 节奏由 [rememberFishReveal] 负责（读 → 想 → 打字 → 条间停顿），
 * 这里只管把它渲染出来。
 *
 * ## 打断
 *
 * 她还在发的时候用户可以继续说话。这时先停下当前生成（剩下的不说了），
 * 让已经打出来的部分立刻收尾，隔一拍再发新消息 —— 真人被打断就是这个反应：
 * 停住，听你说，然后改口。
 */
@Composable
fun FishPhoneScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
    /**
     * 传了就给顶栏一颗返回键。
     *
     * 这一页平时是对话 tab 的根（没有上一级）。但从「记录」点开一条养鱼会话时
     * 它是压栈进来的二级页 —— 那时必须能退回记录列表，否则用户只能靠底部导航，
     * 而他刚点进来的地方（记录页）在另一个 tab 上。
     */
    onBack: (() -> Unit)? = null,
) {
    val engine = container.engine
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    /** 关闭态的模式选择弹窗 */
    var gatePicker by remember { mutableStateOf(false) }

    /*
     * 没开启就先问一次。
     *
     * 闸放在最前面（页面上什么都还没准备）：一旦往下走，LaunchedEffect 会去
     * 取最近一条会话、补写今天的动态 —— 那两件事都意味着读盘与联网。
     * 没同意过的功能不该因为"点进来看看"就产生任何请求。
     */
    if (!settings.fishEnabled) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .imePadding(),
        ) {
            PhoneChatHeader(
                title = "养鱼",
                badge = "还没开启",
                remote = false,
                /*
                 * 关闭态没有会话、也就没有模型可选 —— 这颗药丸原来是空实现，
                 * 点下去什么都不发生，看起来像顶栏坏了。改成「换模式」，
                 * 至少给这一屏留一条离开的路。
                 */
                modelLabel = "换模式",
                onMenu = { nav.select(PhoneTab.Sessions) },
                onNewChat = null,
                onModel = { gatePicker = true },
                onBack = onBack,
            )
            FishEnableCard(
                onEnable = {
                    scope.launch { container.settingsRepo.update { it.copy(fishEnabled = true) } }
                },
                onSwitchScene = { gatePicker = true },
            )
        }
        if (gatePicker) {
            com.dshwatch.ui.screens.scene.ScenePickerDialog(
                current = settings.useScene,
                onPick = { id ->
                    gatePicker = false
                    if (id == Scenes.of(settings.useScene)) return@ScenePickerDialog
                    // 先落盘：根页按 useScene 重新分发，顺序反了会闪一帧旧页面
                    scope.launch { container.settingsRepo.update { it.copy(useScene = id) } }
                },
                onDismiss = { gatePicker = false },
            )
        }
        return
    }

    val conversation by engine.conversation.collectAsStateWithLifecycle()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val streaming by engine.streaming.collectAsStateWithLifecycle()

    /*
     * 卡面从存储里读，而不是永远用内置那一份。
     *
     * 「长期记忆」的开关、改过的人设都落在 roleCardStore 里；这里如果只用
     * `BuiltinCards.feiyu()`，用户在设置里关掉记忆后聊天页仍然会去压缩 ——
     * 开关看起来生效了，实际没生效。读不到（首次安装、卡被删）时回退内置。
     */
    var card by remember { mutableStateOf(BuiltinCards.feiyu()) }
    /*
     * 外观。collectAsStateWithLifecycle 而不是"进页面读一次"：她在聊天里
     * 被要求改（fish_skin 工具）时，这一页必须当场变 —— 用户说完
     * 「背景换成深蓝」，眼睛是盯着这一页的。
     */
    val skin by container.fishSkin.skin.collectAsStateWithLifecycle()
    val avatarImage = rememberFishImage(skin.avatar, container.fishSkin)
    val backgroundImage = rememberFishImage(skin.background, container.fishSkin)
    var ready by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    /* 附件：和她聊天也能发照片、发文件 —— 她本来就"看得见"，只是以前界面没这个入口 */
    val attachments = remember { mutableStateListOf<Attachment>() }
    var attachNote by remember { mutableStateOf("") }
    var attachBusy by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var viewingMedia by remember { mutableStateOf<MediaRef?>(null) }

    /*
     * 顶栏那颗 ☰ 在四个模式里做同一件事：拉开历史记录。
     *
     * 养鱼虽然"只有一个对象"，但同一只鱼可以有很多段对话（用户点「重新开一段」
     * 就会多一条）。抽屉里只列 fish 场景的会话，别的模式的记录不混进来。
     */
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var history by remember { mutableStateOf<List<com.dshwatch.domain.model.Conversation>>(emptyList()) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var renameTarget by remember { mutableStateOf<com.dshwatch.domain.model.Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<com.dshwatch.domain.model.Conversation?>(null) }
    /** 「关掉养鱼」的二次确认 —— 这个开关以前只在设置页里，抽屉里点不到 */
    var confirmDisable by remember { mutableStateOf(false) }

    /** 读 → 想 → 打字 → 分条发的调度器，见 [rememberFishReveal] */
    val reveal = rememberFishReveal(messages, live)

    /*
     * 进来就接着上次聊。
     *
     * 养鱼是"一个人"，不是"一批会话"：用户不该在会话列表里挑今天跟哪条鱼聊。
     * 所以固定复用最近一条 fish 会话，只有第一次（或用户手动清空后）才新建。
     *
     * 例外：引擎里**已经**开着一条养鱼会话（从记录里点进来就是这样）。
     * 这时不能再去「取最近一条」—— 用户点的是某一天的对话，
     * 强制跳回最新那条等于把他选的那条换掉了。
     */
    LaunchedEffect(Unit) {
        // 先取卡面：下面的「长期记忆」开关、开场白都按它来
        card = runCatching { container.roleCardStore.load(BuiltinCards.feiyu().id) }
            .getOrNull() ?: BuiltinCards.feiyu()
        // 今天还没写过动态就补一条：素材是今天聊过的话，不依赖会话是否已经打开
        runCatching { FishMomentWriter.writeIfDue(context) }
        if (Scenes.of(engine.conversation.value?.scene) == Scenes.FISH) {
            ready = true
            return@LaunchedEffect
        }
        val latest = runCatching {
            container.conversationStore.latestFor(Scenes.FISH, card.id)
        }.getOrNull()
        if (latest != null) {
            runCatching { engine.open(latest.id) }
        } else {
            engine.startNew(settings, Scenes.FISH, card.id, card.displayName)
        }
        ready = true
    }

    /*
     * 跟随到底：以"末尾项"为目标，长回复生成时才不会一直盯着段首。
     */
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    var landed by remember { mutableStateOf(false) }
    // revealed 的值变化不会改变 size，单独取一个和当 key，否则气泡发出来不跟着滚
    val revealedTick = reveal.revealed.values.sum()
    LaunchedEffect(messages.size, reveal.typingId, revealedTick, live.text.length) {
        if (!ready) return@LaunchedEffect
        if (!landed) {
            runCatching { listState.scrollToItem(if (messages.isEmpty()) 0 else messages.size) }
            landed = true
            return@LaunchedEffect
        }
        if (!atBottom) return@LaunchedEffect
        runCatching { listState.scrollToItem(if (messages.isEmpty()) 0 else messages.size) }
    }

    /*
     * 长期记忆：说完这一轮之后在后台整理，和酒馆页一致。
     */
    LaunchedEffect(streaming, messages.size) {
        if (streaming || messages.isEmpty()) return@LaunchedEffect
        if (!card.longTermMemory) return@LaunchedEffect
        // 摘要走养鱼自己的那家 API —— 用户可能只给它配了供应商
        val provider = settings.providers.firstOrNull { it.id == settings.fishProviderId }
            ?: settings.activeProvider
            ?: return@LaunchedEffect
        container.memoryCompressor.maybeCompress(
            conversationId = conversation?.id.orEmpty(),
            cardId = card.id,
            scene = Scenes.FISH,
            contextWindow = provider.contextWindow,
            providerId = settings.fishProviderId,
            // 聊天用哪只模型，摘要就用哪只：两边不一致时记忆读起来像另一个人写的
            model = settings.fishModel,
        )
    }

    fun submit() {
        val text = input.trim()
        val files = attachments.filter { it.ok }
        if (text.isEmpty() && files.isEmpty()) return
        input = ""
        attachments.clear()
        attachNote = ""
        if (streaming) {
            /*
             * 她话没说完，用户插进来了。
             *
             * 先让她停下（已经生成的部分保留，剩下的不再说），再隔一拍发新消息 ——
             * 这一拍是"她停住听你说"的时间。模型收到新消息后会重新组织语言，
             * 于是自然出现「我想想」→「不对我说错了」→「好吧」这种改口。
             */
            reveal.interrupt()
            engine.stop()
            scope.launch {
                delay(320)
                if (!engine.send(text, files)) {
                    // 引擎还没完全收尾：再等一拍重试，仍失败就把文字还给输入框，别吞掉
                    delay(240)
                    if (!engine.send(text, files)) {
                        input = text
                        attachments.addAll(files)
                    }
                }
            }
        } else if (!engine.send(text, files)) {
            input = text
            attachments.addAll(files)
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

    /*
     * 附件：两条入口（相册 / 文件）都走 rememberFilePicking。
     *
     * 设置里开着「用内置文件浏览器」时，连「相册」也是应用内挑图 ——
     * 这台设备上未必有能响应图片选择的 Activity，而内置那条一定能用。
     */
    val picking = rememberFilePicking()

    /*
     * 拍照：和编程页、手表输入页共用 [rememberCameraShot]。
     * 三处各写一份时最容易分叉的是 FileProvider 的 authority ——
     * 写错一处就只在某一种设备上「点拍照没反应」。
     */
    val shoot = rememberCameraShot(
        context = context,
        onPicked = { uri -> addUri(uri) },
        onError = { attachNote = it },
    )

    // 抽屉一拉开就重读：刚聊完那一条的标题可能已经变了
    LaunchedEffect(historyVersion, drawerState.isOpen) {
        if (drawerState.isOpen || history.isEmpty()) {
            history = runCatching { container.conversationStore.index() }
                .getOrDefault(emptyList())
                .filter { Scenes.of(it.scene) == Scenes.FISH }
        }
    }

    val provider = settings.activeProvider

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            PhoneHistoryDrawer(
                conversations = history,
                currentId = conversation?.id,
                sceneLabel = "模式：${Scenes.label(Scenes.FISH)}",
                modelLabel = listOf(provider?.name.orEmpty(), phoneModelLabel(settings, conversation?.model.orEmpty()))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "未配置供应商" },
                onOpen = { conv ->
                    scope.launch {
                        drawerState.close()
                        runCatching { engine.open(conv.id) }
                    }
                },
                // 养鱼不提供「新对话」：记忆与上下文挂在同一条会话上，多开一段就断了
                onNewChat = null,
                onPickScene = { scope.launch { drawerState.close() ; showPicker = true } },
                onSettings = {
                    scope.launch { drawerState.close() }
                    // 抽屉里的「设置」是**养鱼自己的**设置（人设 / 主动发言 / 记忆 / API），
                    // 不是全局设置 —— 用户在这页点齿轮，想改的是这条鱼
                    nav.open(Screen.FishSettings)
                },
                onManage = {
                    scope.launch {
                        drawerState.close()
                        nav.select(PhoneTab.Sessions)
                    }
                },
                onRename = { conv ->
                    scope.launch { drawerState.close() }
                    renameTarget = conv
                },
                onTogglePin = { conv ->
                    scope.launch {
                        container.conversationStore.setPinned(conv.id, !conv.pinned)
                        historyVersion++
                    }
                },
                onAbout = { conv ->
                    scope.launch {
                        drawerState.close()
                        runCatching { engine.open(conv.id) }
                    }
                },
                onDelete = { conv -> deleteTarget = conv },
                // 顶栏收成三段之后，「记忆」从顶栏搬到这里；「动态」也放同一层
                extras = listOf(
                    com.dshwatch.ui.phone.PhoneDrawerExtra(
                        title = "关掉养鱼",
                        icon = androidx.compose.material.icons.Icons.Filled.Stop,
                        onClick = {
                            scope.launch { drawerState.close() }
                            confirmDisable = true
                        },
                    ),
                    com.dshwatch.ui.phone.PhoneDrawerExtra(
                        title = "她的样子",
                        icon = androidx.compose.material.icons.Icons.Outlined.Brush,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.open(Screen.FishSkin)
                        },
                    ),
                    com.dshwatch.ui.phone.PhoneDrawerExtra(
                        title = "她的动态",
                        icon = androidx.compose.material.icons.Icons.Outlined.Edit,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.open(Screen.FishMoments)
                        },
                    ),
                    com.dshwatch.ui.phone.PhoneDrawerExtra(
                        title = "她记得什么",
                        icon = androidx.compose.material.icons.Icons.Outlined.Memory,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.open(Screen.MemoryDoc(card.id))
                        },
                    ),
                ),
            )
        },
    ) {
        /*
         * 背景图铺在最底层，内容浮在上面。
         *
         * 铺在 Column 的 `.background()` 里是不行的 —— 那会把图盖住，
         * 或者反过来被不透明的底色盖住，取决于哪一行先写。
         */
        FishBackdrop(
            image = backgroundImage,
            dim = skin.backgroundDim,
            fallback = MaterialTheme.colorScheme.background,
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            /*
             * 顶栏换成和编程页同一颗：☰ + 标题/状态 + 模型药丸。
             *
             * 原来这里是「头像 + 名字 + 在线 + 切换/记忆/设置三颗图标」——
             * 那是聊天软件的样子，但四个模式并排时它和编程页顶上完全不是一回事。
             * 「记忆」现在收进抽屉底部以外的位置（见 ☰ 菜单里的会话列表），
             * 需要单独管理时从设置进。
             */
            PhoneChatHeader(
                title = skin.displayNameOr(card.displayName),
                badge = if (reveal.typingId != null) "正在输入…" else "在线",
                avatar = if (skin.showAvatar) {
                    { FishAvatar(image = avatarImage, size = skin.avatarSize.dp) }
                } else {
                    null
                },
                remote = provider?.type == com.dshwatch.domain.model.ProviderType.DSH_SERVER,
                modelLabel = phoneModelLabel(settings, conversation?.model.orEmpty()),
                onMenu = { scope.launch { drawerState.open() } },
                // 同上：顶栏也不留「+」
                onNewChat = null,
                onModel = { showModel = true },
                onBack = onBack,
            )

        if (!ready || conversation?.scene != Scenes.FISH) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在接通…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            return@Column
        }

        val hasHerVoice = reveal.hasVoice(messages)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            /*
             * 开场白。
             *
             * 只活在界面上，不进历史 —— 进了历史模型会以为自己刚说完，
             * 于是接着自说自话。等她真的出了字，这颗气泡让位给真实回复。
             */
            if (!hasHerVoice) {
                item(key = "__greeting__") {
                    FishBubbleRow(
                    text = skin.greeting.takeIf { it.isNotBlank() } ?: card.greeting,
                    mine = false,
                    showAvatar = true,
                    skin = skin,
                    avatar = avatarImage,
                )
                }
            }

            itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                val prev = messages.getOrNull(index - 1)
                val gap = if (prev != null) m.createdAt - prev.createdAt else Long.MAX_VALUE
                val text = if (live.messageId == m.id) live.text else m.content
                val mine = m.role == Role.USER
                val bubbles = remember(m.id, text) { FishBubbles.split(text) }
                val silent = !mine && FishRhythm.isSilent(text)
                val count = reveal.count(m.id, bubbles.size)

                Column(modifier = Modifier.fillMaxWidth()) {
                    if (gap >= TIME_GAP_MS) PhoneTimeDivider(Fmt.clock(m.createdAt))
                    if (silent) {
                        // 她看见了，但这次不接
                        PhoneTimeDivider("已读")
                    }
                    /*
                     * 附件在气泡上方：图和文件名是"我发了什么"，正文才是"我说了什么"。
                     * 顺序反过来，用户滑到这一条会先读到字、再发现下面还挂着一张图。
                     */
                    if (m.attachments.isNotEmpty()) {
                        FishAttachmentNotes(
                            attachments = m.attachments,
                            mine = mine,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    val media = remember(m.id, m.attachments, m.media) {
                        userMedia(m.attachments, m.media)
                    }
                    if (media.isNotEmpty()) {
                        FishMediaStrip(
                            media = media,
                            mine = mine,
                            onOpen = { viewingMedia = it },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    bubbles.take(count).forEachIndexed { i, b ->
                        FishBubbleRow(
                            text = b,
                            mine = mine,
                            showAvatar = i == 0 && !sameSpeaker(prev, m, gap),
                            gapAbove = if (i == 0) 10.dp else 4.dp,
                            skin = skin,
                            avatar = avatarImage,
                        )
                    }
                }
            }

            // 她正在打字（读和想的那两段刻意什么都不显示）
            if (reveal.typingId != null) {
                item(key = "__typing__") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (skin.showAvatar) {
                            FishAvatar(image = avatarImage, size = skin.avatarSize.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        PhoneTypingBubble(theirs = skin.theirsOrNull())
                    }
                }
            }

            item(key = "__tail__") { Spacer(Modifier.height(6.dp)) }
        }

        /*
         * 输入条用和编程页同一颗胶囊。
         *
         * 两处不同：
         *  - `showAttach = true`：可以给她发照片、发文件（她本来就看得见）；
         *  - `streamingSend = true`：她话没说完时按下去是**插话**，不是停止。
         *    编程页生成中那颗键是停止（半截答案通常没用），而对话里插一句完全正常。
         */
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
            onStop = { reveal.interrupt(); engine.stop() },
            onCamera = shoot,
            // 养鱼页以前关掉了 ＋：那时没有附件通路。现在有了就得露出入口，
            // 否则用户永远发不出照片 —— 而"给她看张照片"是这类聊天最自然的动作之一
            showAttach = true,
            streamingSend = true,
        )
        }
        }
    }

    /*
     * 场景切换放在这里，而不是只留在设置里：
     * 养鱼是对话 tab 的整屏，如果切出去必须先进设置，用户会以为"进了鱼塘就出不来"。
     * 切完 popToRoot，PhoneRoot 会按新的 useScene 重新分发。
     *
     * 用手机自己的底部弹层（`PhoneSceneSheet`），不再套手表那张 `ScenePickerDialog` ——
     * 四个模式的切换入口必须长得一样，否则从酒馆点开是三行全宽、从养鱼点开是玻璃框。
     */
    if (showPicker) {
        com.dshwatch.ui.phone.PhoneSceneSheet(
            current = Scenes.FISH,
            onPick = { id ->
                showPicker = false
                scope.launch { container.settingsRepo.update { it.copy(useScene = id) } }
                // 压栈进来时（从记录点开）也要真正回到对话 tab，否则切完停在记录页
                nav.select(PhoneTab.Chat)
            },
            onDismiss = { showPicker = false },
        )
    }

    if (showModel) {
        PhoneModelSheet(
            providers = settings.providers,
            activeProviderId = settings.activeProviderId,
            currentModel = conversation?.model?.ifBlank { provider?.defaultModel.orEmpty() }.orEmpty(),
            onPickProvider = { p ->
                scope.launch {
                    container.settingsRepo.update { it.copy(activeProviderId = p.id) }
                    /*
                     * 会话里记的是上一家的模型名。不清掉的话，顶栏显示的名字
                     * 不属于这一家，而请求会按「不属于当前供应商」退回默认值 ——
                     * 又变回「表面和实际不一致」。
                     */
                    val convModel = engine.conversation.value?.model.orEmpty()
                    if (convModel.isNotBlank() && convModel !in p.models) {
                        engine.overrideModel(p.defaultModel.ifBlank { p.models.firstOrNull().orEmpty() })
                    }
                }
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

    renameTarget?.let { target ->
        com.dshwatch.ui.phone.RenameConversationDialog(
            current = target.displayTitle,
            onSave = { title ->
                scope.launch {
                    container.conversationStore.rename(target.id, title)
                    renameTarget = null
                    historyVersion++
                }
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        com.dshwatch.ui.phone.DeleteConversationDialog(
            onConfirm = {
                scope.launch {
                    container.conversationStore.delete(target.id)
                    deleteTarget = null
                    historyVersion++
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (confirmDisable) {
        com.dshwatch.ui.phone.PhoneConfirmDialog(
            title = "关掉养鱼？",
            message = "关掉之后她不会在固定时间主动找你，也不会再写动态，后台不再联网。" +
                "聊过的内容和记忆都还在，想再开就回到这一页。",
            confirmText = "关掉",
            onConfirm = {
                confirmDisable = false
                scope.launch { container.settingsRepo.update { it.copy(fishEnabled = false) } }
            },
            onDismiss = { confirmDisable = false },
        )
    }

    if (showAttach) {
        com.dshwatch.ui.phone.PhoneAttachSheet(
            onCamera = { showAttach = false; shoot() },
            onGallery = {
                showAttach = false
                picking.pick(
                    mime = "image/*",
                    filter = { CustomBackground.looksLikeImage(it) },
                ) { uri -> addUri(uri) }
            },
            onFile = {
                showAttach = false
                picking.pick(mime = "*/*") { uri -> addUri(uri) }
            },
            onDismiss = { showAttach = false },
        )
    }

    viewingMedia?.let { ref ->
        com.dshwatch.ui.media.MediaViewer(ref = ref, onClose = { viewingMedia = null })
    }
}

@Composable
private fun FishBubbleRow(
    text: String,
    mine: Boolean,
    showAvatar: Boolean,
    gapAbove: Dp = 10.dp,
    /** 这一页的外观。每颗气泡都从它取色与字号 —— 改完立刻全页统一 */
    skin: com.dshwatch.domain.fish.FishSkin = com.dshwatch.domain.fish.FishSkin(),
    avatar: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    val scheme = MaterialTheme.colorScheme
    // 我这一侧的颜色：没自定义就用聊天软件那颗蓝
    val mineBg = skin.accentOrNull() ?: BubbleMine
    /*
     * 白字不是永远对。
     *
     * 用户可以把主色调成浅黄或米色，白字在那种底色上基本看不见 ——
     * 而"看不见自己发的字"会被当成整页坏掉。按亮度换黑字即可，
     * 不需要用户理解对比度。
     */
    val mineText = if (mineBg.luminance() > 0.62f) Color(0xFF1A1A1A) else Color.White
    val theirsBg = skin.theirsOrNull() ?: scheme.surfaceContainerHighest
    val avatarSize = skin.avatarSize.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = gapAbove),
            verticalAlignment = Alignment.Top,
        ) {
            if (!mine) {
                if (showAvatar && skin.showAvatar) {
                    FishAvatar(image = avatar, size = avatarSize)
                } else {
                    Spacer(Modifier.width(avatarSize))
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }

            Surface(
                shape = if (mine) {
                    RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                } else {
                    RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                },
                color = if (mine) mineBg else theirsBg,
                modifier = Modifier.widthIn(max = 260.dp),
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = skin.textSize.sp,
                    // 行高跟着字号走，固定 21sp 时大字号会挤成一团
                    lineHeight = (skin.textSize * 1.4f).sp,
                    color = if (mine) mineText else scheme.onSurface,
                )
            }

            if (mine) {
                Spacer(Modifier.width(8.dp))
                if (showAvatar) PhoneAvatar(mine = true) else Spacer(Modifier.width(36.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 聊天软件里自己那侧的气泡蓝。固定色而不是主题色：换主题时这一页也要像聊天软件 */
private val BubbleMine = Color(0xFF12B7F5)

@Composable
private fun PhoneAvatar(mine: Boolean, size: Dp = 36.dp) {
    val brush = if (mine) {
        Brush.linearGradient(listOf(Color(0xFF8FB4FF), Color(0xFF4A7DFF)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF6EC6F5), Color(0xFF8B6BF0)))
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (mine) "我" else "🐟",
            fontSize = if (mine) 12.sp else (size.value * 0.46f).sp,
            color = Color.White,
        )
    }
}

@Composable
private fun PhoneTimeDivider(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

/** "对方正在输入"的三个点 */
@Composable
private fun PhoneTypingBubble(theirs: Color? = null) {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "typing")
    Surface(
        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        color = theirs ?: scheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 620, delayMillis = i * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurfaceVariant.copy(alpha = alpha)),
                )
            }
        }
    }
}
