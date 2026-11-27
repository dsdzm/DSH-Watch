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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Logx
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.domain.model.ReasoningEffort
import com.dshwatch.domain.roleplay.CardPlugin
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.domain.roleplay.isFishCard
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.screens.writing.ImportDialog
import com.dshwatch.ui.screens.writing.parseLore
import kotlinx.coroutines.launch

/**
 * 手机版酒馆 / 写作的入口页。
 *
 * ## 为什么要另写一份，而不是继续套手表的 `TavernScreen`
 *
 * 以前这两个模式在手机上直接复用表盘那把 `WatchScaffold` + 玻璃卡片：
 * 顶栏是「文字标题 + 场景胶囊 + 齿轮」，卡片是圆角玻璃，主按钮是 40dp 的小方条。
 * 那是**为 200dp 圆屏做的比例**，摊到 411dp 的手机上就是「一切都很小、很空」。
 * 而且它和编程页放在一起时，顶上那一排长得完全不一样 —— 用户会觉得这是两个 App。
 *
 * 现在四个模式共用同一套骨架：
 *
 * ```
 * ┌ 圆键(☰) ┬ 标题 / 副标题 ────────┬ 模型药丸 ˅ ┐   顶栏 PhoneChatHeader
 * ├ 内容（列表 或 消息） ───────────────────────┤
 * └ 输入条（有输入的页面才有） ──────────────────┘
 *      底部：对话 / 会话 / 设置
 * ```
 *
 * 共用的是**外壳**，内容各按场景组织：酒馆是角色卡，写作是设定卡。
 */
@Composable
fun PhoneTavernScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
) {
    PhoneRoleList(
        container = container,
        settings = settings,
        nav = nav,
        scene = Scenes.TAVERN,
        title = "酒馆",
        mainAction = "开始对话",
        createLabel = "新建角色卡",
        emptyTitle = "还没有角色",
        emptyHint = "新建一个：名字、性格、开场白，三行就能聊起来",
        showImport = false,
    )
}

@Composable
fun PhoneWritingScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
) {
    PhoneRoleList(
        container = container,
        settings = settings,
        nav = nav,
        scene = Scenes.WRITING,
        title = "写作",
        mainAction = "继续写",
        createLabel = "新建作品设定",
        emptyTitle = "还没有作品",
        emptyHint = "建一个设定：世界、人物、文风，然后直接开写",
        showImport = true,
    )
}

/**
 * 两种场景共用的列表页。
 *
 * 差异只有四处（标题、主按钮文案、是否有导入、空状态），所以按参数收拢；
 * 分成两份复制的话，改一次「卡片长什么样」要改两个地方，很快就走形。
 */
@Composable
private fun PhoneRoleList(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
    scene: String,
    title: String,
    mainAction: String,
    createLabel: String,
    emptyTitle: String,
    emptyHint: String,
    showImport: Boolean,
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var cards by remember(scene) { mutableStateOf<List<RoleCard>>(emptyList()) }
    var loaded by remember(scene) { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showSceneSheet by remember { mutableStateOf(false) }
    var importTarget by remember { mutableStateOf<RoleCard?>(null) }

    var history by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    /*
     * 随导航深度重读：从编辑页删掉一张卡、或新建一张回来，列表要立刻变。
     * 只依赖 `scene` 的话，pop 回来看到的还是旧列表。
     */
    val depth = nav.watchStack.depth
    LaunchedEffect(scene, depth) {
        cards = runCatching { container.roleCardStore.index(scene) }
            .onFailure { Logx.e("PhoneScene", "读取卡片失败 scene=$scene", it) }
            .getOrDefault(emptyList())
            // 大肥鱼是养鱼场景的固定角色，不属于酒馆的「可挑选列表」
            .filterNot { it.isFishCard }
        loaded = true
    }

    // 抽屉一拉开就重读：刚聊完那一条的标题可能已经变了
    LaunchedEffect(historyVersion, drawerState.isOpen) {
        if (drawerState.isOpen || history.isEmpty()) {
            history = runCatching { container.conversationStore.index() }
                .getOrDefault(emptyList())
                .filter { Scenes.of(it.scene) == scene }
        }
    }

    /*
     * 主按钮：有记录就接着上次，没有才新建。
     *
     * 和手表版同一套语义 —— 昨天写了一晚上的东西，今天点「继续写」不该得到一张白纸。
     */
    fun startOrResume(card: RoleCard, forceNew: Boolean) {
        scope.launch {
            container.settingsRepo.update {
                if (scene == Scenes.WRITING) it.copy(lastWritingCardId = card.id)
                else it.copy(lastTavernCardId = card.id)
            }
            val latest = if (forceNew) null else container.conversationStore.latestFor(scene, card.id)
            if (latest != null) {
                container.engine.open(latest.id)
            } else {
                container.engine.startNew(
                    settings = settings.copy(agentMode = AgentMode.OFF),
                    scene = scene,
                    roleCardId = card.id,
                    title = card.displayName,
                )
            }
            nav.open(Screen.RoleplayChat)
        }
    }

    val provider = settings.activeProvider
    val openConversation by container.engine.conversation.collectAsStateWithLifecycle()
    val activeModel = openConversation
        ?.takeIf { Scenes.of(it.scene) == scene }
        ?.model
        .orEmpty()
    val modelLabel = phoneModelLabel(settings, activeModel)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            PhoneHistoryDrawer(
                conversations = history,
                currentId = null,
                sceneLabel = "模式：${Scenes.label(scene)}",
                modelLabel = listOf(provider?.name.orEmpty(), provider?.defaultModel.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "未配置供应商" },
                onOpen = { conv ->
                    scope.launch {
                        drawerState.close()
                        container.engine.open(conv.id)
                        nav.open(Screen.RoleplayChat)
                    }
                },
                onNewChat = {
                    scope.launch {
                        drawerState.close()
                        nav.open(Screen.CardEdit(scene, ""))
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
                    renameTarget = conv
                },
                onTogglePin = { conv ->
                    scope.launch {
                        container.conversationStore.setPinned(conv.id, !conv.pinned)
                        historyVersion++
                    }
                },
                // 详情页是手表那张「这条用了多少 token」的卡片；手机上先进会话，
                // 需要细节时右上角菜单里还有入口
                onAbout = { conv ->
                    scope.launch {
                        drawerState.close()
                        container.engine.open(conv.id)
                        nav.open(Screen.RoleplayChat)
                    }
                },
                onDelete = { conv -> deleteTarget = conv },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            PhoneChatHeader(
                title = title,
                badge = if (loaded) "${cards.size} 个${if (scene == Scenes.TAVERN) "角色" else "设定"}" else "读取中",
                remote = provider?.type == ProviderType.DSH_SERVER,
                modelLabel = modelLabel,
                onMenu = { scope.launch { drawerState.open() } },
                // 列表页不靠点标题新建：卡片列表底部就有「新建」，省一层误触
                onNewChat = null,
                onModel = { showModelPicker = true },
            )

            if (!loaded) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("读取中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            if (cards.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = emptyTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = emptyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { nav.open(Screen.CardEdit(scene, "")) },
                            shape = RoundedCornerShape(24.dp),
                        ) { Text(createLabel) }
                    }
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards, key = { it.id }) { card ->
                    PhoneRoleCard(
                        card = card,
                        mainAction = mainAction,
                        onMain = { startOrResume(card, false) },
                        onNew = { startOrResume(card, true) },
                        onHistory = { nav.open(Screen.SceneSessions(scene, card.id)) },
                        onEdit = { nav.open(Screen.CardEdit(scene, card.id)) },
                        onImport = if (showImport) ({ importTarget = card }) else null,
                    )
                }
                item(key = "__create__") {
                    OutlinedButton(
                        onClick = { nav.open(Screen.CardEdit(scene, "")) },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(createLabel)
                    }
                }
                item(key = "__tail__") { Spacer(Modifier.height(6.dp)) }
            }
        }
    }

    // ---------------- 弹窗 ----------------

    if (showSceneSheet) {
        PhoneSceneSheet(
            current = settings.useScene,
            onPick = { id ->
                showSceneSheet = false
                scope.launch { container.settingsRepo.update { it.copy(useScene = id) } }
                // 根屏按 useScene 分发，回根即换页 —— 不再 push 一层
                nav.popToRoot()
            },
            onDismiss = { showSceneSheet = false },
        )
    }

    if (showModelPicker) {
        PhoneModelSheet(
            providers = settings.providers,
            activeProviderId = settings.activeProviderId,
            currentModel = activeModel.ifBlank { provider?.defaultModel.orEmpty() },
            onPickProvider = { p ->
                scope.launch { container.settingsRepo.update { it.copy(activeProviderId = p.id) } }
            },
            onPickModel = { model ->
                showModelPicker = false
                scope.launch {
                    /*
                     * 列表页通常**没有**活动会话（用户还没点「开始对话」）。
                     * 这时 `overrideModel` 会因为 `conversation == null` 直接返回，
                     * 用户选完模型再看顶栏还是旧的 —— 所以分两种情况：
                     * 已经有本场景的会话就覆盖它，否则改供应商默认值，
                     * 让接下来新建的会话用它。
                     */
                    val open = container.engine.conversation.value
                    if (open != null && Scenes.of(open.scene) == scene) {
                        container.engine.overrideModel(model)
                    } else {
                        container.settingsRepo.update { st ->
                            st.copy(
                                providers = st.providers.map {
                                    if (it.id == st.activeProviderId) it.copy(defaultModel = model) else it
                                },
                            )
                        }
                    }
                }
            },
            onPickEffort = { effort ->
                scope.launch {
                    container.settingsRepo.update { st ->
                        val list = st.providers.map {
                            if (it.id == st.activeProviderId) it.copy(reasoningEffort = effort) else it
                        }
                        st.copy(providers = list)
                    }
                }
            },
            onManage = {
                showModelPicker = false
                nav.open(Screen.Providers)
            },
            onDismiss = { showModelPicker = false },
        )
    }

    importTarget?.let { target ->
        ImportDialog(
            onDismiss = { importTarget = null },
            onImportWorld = { text ->
                scope.launch {
                    val entries = parseLore(text)
                    container.roleCardStore.save(target.copy(entries = target.entries + entries))
                    cards = container.roleCardStore.index(scene)
                    importTarget = null
                }
            },
            onImportPlugin = { name, text ->
                scope.launch {
                    container.roleCardStore.save(
                        target.copy(plugins = target.plugins + CardPlugin(name = name, prompt = text))
                    )
                    cards = container.roleCardStore.index(scene)
                    importTarget = null
                }
            },
        )
    }

    renameTarget?.let { target ->
        RenameConversationDialog(
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
        DeleteConversationDialog(
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
}

/**
 * 重命名会话。
 *
 * 抽屉里的长按菜单在各页都要用（编程 / 酒馆 / 写作 / 养鱼），
 * 所以收在这里而不是各页复制一份 —— 四份同款对话框，改文案时必然漏掉两份。
 */
@Composable
internal fun RenameConversationDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text.trim()) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun DeleteConversationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这段对话？") },
        text = { Text("删掉之后拿不回来。角色卡本身不会受影响。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 通用二次确认。
 *
 * 只用来拦「关掉一个正在联网的功能」这种事 —— 这类操作不是删除，
 * 但用户点下去之前应该知道会发生什么、以及不会发生什么。
 */
@Composable
internal fun PhoneConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 一张卡片。
 *
 * 结构照编程页空状态那几条「起点」：底色 `surfaceContainerLow`、圆角 20、
 * 左边一颗圆形图标、中间两行字。主按钮用 Material3 的 `Button`，
 * 和编程页那颗发送键同一套形状语言。
 */
@Composable
private fun PhoneRoleCard(
    card: RoleCard,
    mainAction: String,
    onMain: () -> Unit,
    onNew: () -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onImport: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(scheme.primary.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = card.displayName.take(1),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (card.tagline.isNotBlank()) {
                        Text(
                            text = card.tagline,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "编辑",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onMain,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(mainAction)
                }
                FilledTonalIconButton(
                    onClick = onNew,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新开一个对话", modifier = Modifier.size(20.dp))
                }
                FilledTonalIconButton(
                    onClick = onHistory,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Outlined.History, contentDescription = "历史记录", modifier = Modifier.size(20.dp))
                }
            }

            if (onImport != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导入世界书 / 插件")
                }
            }
        }
    }
}

/**
 * 顶栏那颗模型药丸的文案：模型名 + 思考强度，与编程页同一规则。
 *
 * [conversationModel] 是当前会话覆盖过的模型（见 `ChatEngine.overrideModel`）。
 * 列表页在用户刚从一个对话退回来时，那条会话仍是 active 的 —— 顶栏必须显示
 * 它实际在用的那个模型，否则用户会看到「我明明换过，怎么又变回去了」。
 */
internal fun phoneModelLabel(settings: AppSettings, conversationModel: String = ""): String {
    val provider = settings.activeProvider ?: return "未配置模型"
    val effort = when (provider.reasoningEffort) {
        ReasoningEffort.LOW -> "低"
        ReasoningEffort.MEDIUM -> "中"
        ReasoningEffort.HIGH -> "高"
        else -> ""
    }
    val model = conversationModel.ifBlank { provider.defaultModel }
    return listOf(model, effort).filter { it.isNotBlank() }.joinToString(" · ")
}
