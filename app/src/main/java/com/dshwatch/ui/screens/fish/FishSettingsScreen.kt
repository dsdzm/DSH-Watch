package com.dshwatch.ui.screens.fish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.data.store.ProactivePlan
import com.dshwatch.domain.device.BatteryKeepAlive
import com.dshwatch.domain.fish.FishProactive
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.domain.roleplay.BuiltinCards
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.GlassDialogRow
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 养鱼设置。
 *
 * ## 为什么单独一页，而不是塞进「设置」
 *
 * 这一页的东西全都只对养鱼有意义：人设、她什么时候会主动说话、
 * 她记得什么、用哪家 API。放进全局设置会和编程的模型选择、酒馆的显示选项
 * 混成一片，用户改完一句人设还得在几十行里找「刚才那个在哪」。
 *
 * ## 为什么没有「保存」按钮
 *
 * 每一项都是立刻落盘的（`settingsRepo.update` / `roleCardStore.save`）。
 * 手表上退出的方式太多 —— 抬腕放下、来电、被系统回收 —— 攒着一起保存，
 * 丢的往往正是最后改的那一下。这也是全项目设置页的既有做法。
 */
@Composable
fun FishSettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var card by remember { mutableStateOf(BuiltinCards.feiyu()) }
    var plans by remember { mutableStateOf<List<ProactivePlan>>(emptyList()) }
    var showTimes by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }
    var apiProvider by remember { mutableStateOf<Provider?>(null) }
    var exempt by remember { mutableStateOf(BatteryKeepAlive.isExempt(context)) }
    /** 记忆里现在有多少条 —— 用户判断"她到底记不记得"最直接的一个数字 */
    var memCount by remember { mutableIntStateOf(0) }
    var memNote by remember { mutableStateOf("") }

    /*
     * 卡面每次进来都重读：用户可能刚在「人设」里改过并返回，
     * 这里显示的还是旧名字就会让人以为没保存上。
     */
    LaunchedEffect(Unit) {
        card = runCatching { container.roleCardStore.load(BuiltinCards.feiyu().id) }
            .getOrNull() ?: BuiltinCards.feiyu()
        plans = runCatching { container.proactiveStore.pending() }.getOrDefault(emptyList())
        exempt = BatteryKeepAlive.isExempt(context)
        memCount = runCatching { container.memoryStore.entries(card.id).size }.getOrDefault(0)
    }

    fun saveTimes(list: List<String>) {
        val normalized = FishProactive.normalizeList(list)
        scope.launch {
            container.settingsRepo.update { it.copy(fishProactiveTimes = normalized) }
        }
    }

    val times = settings.fishProactiveTimes
    val provider = settings.fishProviderId
        .takeIf { it.isNotBlank() }
        ?.let { id -> settings.providers.firstOrNull { it.id == id } }
    val apiLabel = when {
        provider == null -> "跟随当前供应商"
        settings.fishModel.isNotBlank() -> "${provider.name} · ${settings.fishModel}"
        else -> "${provider.name} · 默认模型"
    }

    /*
     * 她现在能不能自己改那一页。
     *
     * 依赖两件事：Agent 没被全局关掉，且用的直连（OpenAI 兼容）通道 ——
     * 两者任一不成立，`fish_skin` 根本不会下发。那时她说「改好了」是假的，
     * 用户退出重进才发现什么都没变。这一行就是为了让这句话不再骗人。
     */
    val restyleDisabled = settings.agentMode == AgentMode.OFF ||
        (provider ?: settings.activeProvider)?.type != ProviderType.OPENAI_COMPAT

    WatchScaffold(title = "养鱼设置", onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
        ) {
            /* ---------- 模式总开关 ---------- */
            /*
             * 放在最上面，而且只有这一项标题就叫「养鱼」。
             *
             * 它是唯一会**在用户不在场时联网**的模式，所以关闭入口必须一眼能看到 ——
             * 用户想"让她别动"时，第一眼看到的就是它。
             */
            SettingCard {
                SwitchRow(
                    title = "养鱼",
                    subtitle = if (settings.fishEnabled) {
                        "已开启：她会主动找你，也会每天写一条动态"
                    } else {
                        "已关闭：她不会主动说话，也不会在后台联网"
                    },
                    checked = settings.fishEnabled,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.settingsRepo.update { it.copy(fishEnabled = on) }
                        }
                    },
                )
            }

            /* ---------- 她是谁 ---------- */
            SettingCard(modifier = Modifier.padding(top = 8.dp)) {
                SettingRow(
                    title = "人设",
                    subtitle = "${card.displayName}的设定、说话方式、开场白",
                    trailing = "›",
                    showDivider = true,
                    onClick = { nav.push(Screen.CardEdit(Scenes.FISH, card.id)) },
                )
                SettingRow(
                    title = "她的样子",
                    subtitle = if (restyleDisabled) {
                        // 她改不了自己那一页时要说清楚为什么 —— 否则她会答应，然后什么也没发生
                        "名字、头像、背景（现在要先打开 Agent 她才能自己改）"
                    } else {
                        "名字、头像、背景、气泡颜色 —— 只改养鱼这一页"
                    },
                    trailing = "›",
                    showDivider = true,
                    onClick = { nav.push(Screen.FishSkin) },
                )
                SwitchRow(
                    title = "长期记忆",
                    subtitle = "聊完自动把要点记下来，下次还记得",
                    checked = card.longTermMemory,
                    onCheckedChange = { on ->
                        scope.launch {
                            card = runCatching {
                                container.roleCardStore.save(card.copy(longTermMemory = on))
                            }.getOrNull() ?: card
                        }
                    },
                )
                SettingRow(
                    title = "记忆内容",
                    subtitle = if (memCount == 0) {
                        "还什么都没记下 —— 点下面那个先整理一次"
                    } else {
                        "已记下 $memCount 条，以及正在忘掉的"
                    },
                    trailing = "›",
                    onClick = { nav.push(Screen.MemoryDoc(card.id)) },
                )
                SettingRow(
                    title = "立即整理一次",
                    subtitle = memNote.ifBlank { "把最近聊的压成几条她记得的事" },
                    trailing = "整理 ›",
                    onClick = {
                        if (memNote == "正在整理…") return@SettingRow
                        memNote = "正在整理…"
                        scope.launch {
                            /*
                             * try/finally 不是风格问题：`memNote = "正在整理…"`
                             * 一旦被异常卡在中间，这一页此后永远显示「正在整理」，
                             * 而且点不动 —— 用户看到的就是「记忆整理一直卡住」。
                             */
                            try {
                                /*
                                 * 打开要整理的那段对话。
                                 *
                                 * 引擎里通常没有会话（用户是从设置页进来的，这一页不是对话页），
                                 * 以前直接把空串传下去，`maybeCompress` 第一行就返回了 ——
                                 * 用户点了「立即整理」，什么也没发生，也没有任何提示。
                                 */
                                val conv = runCatching {
                                    container.engine.conversation.value
                                        ?.takeIf { Scenes.of(it.scene) == Scenes.FISH }
                                        ?: container.conversationStore
                                            .latestFor(Scenes.FISH, card.id)
                                }.getOrNull()

                                if (conv == null) {
                                    memNote = "你们还没聊过，没什么可整理的"
                                    return@launch
                                }
                                if (container.engine.conversation.value?.id != conv.id) {
                                    runCatching { container.engine.open(conv.id) }
                                }

                                /*
                                 * 走过去等的那条（compressNow），不再"发出去 + 等五秒猜一次"。
                                 *
                                 * 摘要是一次网络请求，旧写法只能 `delay(5000)` 再读一遍计数，
                                 * 网络慢一点就读到"0 条"，界面上却是「正在整理」——
                                 * 两件事叠在一起就成了"卡住"。现在它直接返回结果。
                                 */
                                // 优先用会话自己的模型：顶栏换过模型时，它和设置里的默认值
                                // 往往不是同一个，拿设置去打只会 404
                                val providerId = conv.providerId.ifBlank { settings.fishProviderId }
                                val model = conv.model.ifBlank { settings.fishModel }
                                val provider = settings.providers
                                    .firstOrNull { it.id == providerId }
                                    ?: settings.activeProvider
                                val res = container.memoryCompressor.compressNow(
                                    conversationId = conv.id,
                                    cardId = card.id,
                                    scene = Scenes.FISH,
                                    contextWindow = provider?.contextWindow ?: 0,
                                    providerId = providerId,
                                    model = model,
                                )
                                memCount = runCatching {
                                    container.memoryStore.entries(card.id).size
                                }.getOrDefault(memCount)
                                memNote = if (res.ok) {
                                    "已记下 $memCount 条（本次新增 ${res.added} 条）"
                                } else {
                                    res.message
                                }
                            } catch (e: Throwable) {
                                memNote = "整理失败：${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                // 上面每一条 return 都已经赋过值了，这里只兜底状态
                                if (memNote == "正在整理…") memNote = "这次没整理出内容"
                            }
                        }
                    },
                )
            }

            /* ---------- 她的动态 ---------- */
            SettingCard(modifier = Modifier.padding(top = 8.dp)) {
                SettingRow(
                    title = "她的动态",
                    subtitle = "每天一条，她自己写的碎碎念",
                    trailing = "›",
                    onClick = { nav.push(Screen.FishMoments) },
                )
            }

            /* ---------- 什么时候会主动说话 ---------- */
            SettingCard(modifier = Modifier.padding(top = 8.dp)) {
                SwitchRow(
                    title = "允许主动找你",
                    subtitle = when {
                        !settings.fishEnabled -> "需要先打开上面的「养鱼」"
                        settings.fishProactive -> "每天 ${times.joinToString("、")} 会自己开口"
                        else -> "关掉之后只有你说话她才回"
                    },
                    checked = settings.fishProactive,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.settingsRepo.update { it.copy(fishProactive = on) }
                        }
                    },
                )
                SettingRow(
                    title = "主动发言时间",
                    subtitle = if (times.isEmpty()) "还没有时间点" else times.joinToString("、"),
                    trailing = "改 ›",
                    showDivider = plans.isNotEmpty(),
                    onClick = { showTimes = true },
                )
                if (plans.isNotEmpty()) {
                    // 她自己约的那些：用户能看到、能取消 —— 不然"她突然找我"就变成了黑箱
                    SettingRow(
                        title = "她约好的",
                        subtitle = plans.joinToString("；") {
                            "${FishProactive.clockOf(it.triggerAt)} ${it.note}".trim()
                        },
                        trailing = "管理 ›",
                        onClick = { showTimes = true },
                    )
                }
            }

            /* ---------- 后台 ---------- */
            SettingCard(modifier = Modifier.padding(top = 8.dp)) {
                SwitchRow(
                    title = "允许完全后台",
                    subtitle = if (exempt) {
                        "已加入电池优化白名单，熄屏也能按时说话"
                    } else {
                        "未加入白名单：熄屏后可能被系统冻住，消息会晚到"
                    },
                    checked = exempt,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.settingsRepo.update { it.copy(fishKeepAlive = on) }
                        }
                        if (!exempt) {
                            // 打开：弹系统白名单请求。关掉时不主动撤销 ——
                            // 撤销要走系统设置，程序化地关别人的权限不礼貌也不好用
                            BatteryKeepAlive.request(context)
                        }
                    },
                )
                SettingRow(
                    title = "电池优化设置",
                    subtitle = "部分系统不认上面的弹窗，可以从这里自己找",
                    trailing = "›",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings
                                        .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }

            /* ---------- 用哪家模型 ---------- */
            SettingCard(modifier = Modifier.padding(top = 8.dp)) {
                SettingRow(
                    title = "默认使用 API",
                    subtitle = apiLabel,
                    trailing = "改 ›",
                    onClick = { showApi = true },
                )
            }

            Text(
                text = "主动消息由这台设备在本地生成，需要联网且模型可用。" +
                    "关掉「允许主动找你」不会影响你自己找她。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            /*
             * 思考泄漏的出口。
             *
             * 提示词能压住大多数模型，但有的模型（尤其带深度思考的）
             * 会把"要不要问一句""发了"这类盘算直接写进正文，且不带任何标签 ——
             * 那种输出在客户端无法和正常台词区分。与其让用户反复以为是 bug，
             * 不如在这里说清楚成因和唯一有效的做法。
             */
            Text(
                text = "如果她的气泡里冒出「要不要问一句」「发了」这种自言自语，" +
                    "是这家模型把思考过程写进了正文。换一个不强制深度思考的模型最有效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
    }

    /* ---------------- 时间列表 ---------------- */
    if (showTimes) {
        GlassDialog(
            onDismissRequest = { showTimes = false },
            title = "主动发言时间",
            content = {
                times.forEach { t ->
                    GlassDialogRow(
                        title = t,
                        subtitle = if (settings.fishProactive) "每天" else "（已关闭）",
                        onClick = {
                            showTimes = false
                            editing = t
                        },
                    )
                }
                if (plans.isNotEmpty()) {
                    plans.forEach { plan ->
                        GlassDialogRow(
                            title = "${FishProactive.clockOf(plan.triggerAt)} 她约的",
                            subtitle = plan.note.ifBlank { "到点会主动说话" },
                            onClick = {
                                scope.launch {
                                    runCatching { container.proactiveStore.remove(plan.id) }
                                    plans = runCatching { container.proactiveStore.pending() }
                                        .getOrDefault(emptyList())
                                }
                            },
                        )
                    }
                }
                GlassDialogRow(
                    title = "＋ 添加一个时间",
                    subtitle = "比如 09:30、15:00",
                    onClick = {
                        showTimes = false
                        adding = true
                    },
                )
            },
            buttons = {
                GlassDialogButton("关闭", onClick = { showTimes = false })
            },
        )
    }

    if (adding || editing != null) {
        val initial = editing ?: "09:00"
        FishTimeDialog(
            initial = initial,
            canDelete = editing != null,
            onDismiss = {
                adding = false
                editing = null
            },
            onDelete = {
                val target = editing
                adding = false
                editing = null
                if (target != null) saveTimes(times - target)
            },
            onSave = { value ->
                val target = editing
                adding = false
                editing = null
                saveTimes(if (target == null) times + value else times - target + value)
            },
        )
    }

    /* ---------------- 供应商 / 模型 ---------------- */
    if (showApi) {
        GlassDialog(
            onDismissRequest = { showApi = false },
            title = "默认使用 API",
            content = {
                GlassDialogRow(
                    title = "跟随当前供应商",
                    subtitle = "和编程、酒馆用同一家",
                    selected = settings.fishProviderId.isBlank(),
                    onClick = {
                        scope.launch {
                            container.settingsRepo.update {
                                it.copy(fishProviderId = "", fishModel = "")
                            }
                        }
                        showApi = false
                    },
                )
                settings.providers.forEach { p ->
                    GlassDialogRow(
                        title = p.name.ifBlank { p.id },
                        subtitle = p.defaultModel.ifBlank { "未选择模型" },
                        selected = settings.fishProviderId == p.id,
                        onClick = { apiProvider = p },
                    )
                }
            },
            buttons = { GlassDialogButton("关闭", onClick = { showApi = false }) },
        )
    }

    val pc = apiProvider
    if (pc != null) {
        GlassDialog(
            onDismissRequest = { apiProvider = null },
            title = pc.name.ifBlank { pc.id },
            content = {
                GlassDialogRow(
                    title = "供应商默认",
                    subtitle = pc.defaultModel.ifBlank { "使用该供应商的默认模型" },
                    selected = settings.fishProviderId == pc.id && settings.fishModel.isBlank(),
                    onClick = {
                        scope.launch {
                            container.settingsRepo.update {
                                it.copy(fishProviderId = pc.id, fishModel = "")
                            }
                        }
                        apiProvider = null
                        showApi = false
                    },
                )
                pc.models.forEach { m ->
                    GlassDialogRow(
                        title = m,
                        selected = settings.fishProviderId == pc.id && settings.fishModel == m,
                        onClick = {
                            scope.launch {
                                container.settingsRepo.update {
                                    it.copy(fishProviderId = pc.id, fishModel = m)
                                }
                            }
                            apiProvider = null
                            showApi = false
                        },
                    )
                }
            },
            buttons = { GlassDialogButton("返回", onClick = { apiProvider = null }) },
        )
    }
}

/**
 * 改一个钟点。
 *
 * 手表上没有系统时间选择器（就算有，圆屏上也难按），所以自己做两个
 * 上下步进：小时按 1 走，分钟按 5 走。分钟不做成可输 1 位 —— 主动发言
 * 不需要精确到个位数分钟，5 分钟一档反而更容易选中。
 */
@Composable
private fun FishTimeDialog(
    initial: String,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String) -> Unit,
) {
    val start = FishProactive.parseMinutes(initial) ?: 9 * 60
    var hour by remember(initial) { mutableIntStateOf(start / 60) }
    var minute by remember(initial) { mutableIntStateOf((start % 60) / 5 * 5) }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = if (canDelete) "改时间" else "添加时间",
        content = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Stepper(
                    value = "%02d".format(hour),
                    onMinus = { hour = (hour + 23) % 24 },
                    onPlus = { hour = (hour + 1) % 24 },
                )
                Text(
                    text = ":",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Stepper(
                    value = "%02d".format(minute),
                    onMinus = { minute = (minute + 55) % 60 },
                    onPlus = { minute = (minute + 5) % 60 },
                )
            }
            Text(
                text = "每天 ${"%02d".format(hour)}:${"%02d".format(minute)} 会主动开口",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        buttons = {
            GlassDialogButton(
                "保存",
                onClick = { onSave("%02d:%02d".format(hour, minute)) },
            )
            if (canDelete) GlassDialogButton("删除这个时间", onClick = onDelete)
            GlassDialogButton("取消", onClick = onDismiss)
        },
    )
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus) {
            Icon(Icons.Filled.Remove, contentDescription = "减", modifier = Modifier.size(20.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(38.dp),
        )
        IconButton(onClick = onPlus) {
            Icon(Icons.Filled.Add, contentDescription = "加", modifier = Modifier.size(20.dp))
        }
    }
}
