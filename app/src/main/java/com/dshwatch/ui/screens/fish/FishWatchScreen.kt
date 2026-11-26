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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme as W3
import androidx.wear.compose.material3.Text
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.LiveDelta
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.BuiltinCards
import com.dshwatch.domain.roleplay.FishBubbles
import com.dshwatch.domain.roleplay.FishRhythm
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.m3e.M3eDialog
import com.dshwatch.ui.m3e.M3eDialogRow
import com.dshwatch.ui.m3e.M3eFab
import com.dshwatch.ui.m3e.M3eConfirmDialog
import com.dshwatch.ui.m3e.M3eGhostButton
import com.dshwatch.ui.m3e.M3eIconTap
import com.dshwatch.ui.m3e.M3eList
import com.dshwatch.ui.m3e.M3eScaffold
import com.dshwatch.ui.m3e.M3eWearTheme
import com.dshwatch.ui.m3e.rememberM3eListState
import com.dshwatch.service.FishMomentWriter
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.LocalCircle
import com.dshwatch.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 养鱼 · 手表版。
 *
 * 这一页是**独立实现**，不引用手机版（`FishPhoneScreen` / `FishCard` /
 * `FishBubbles.split` / `rememberFishReveal`）的任何界面代码。
 *
 * | | 手机 | 手表 |
 * |---|---|---|
 * | 一条回复 | 拆成 1~3 个短气泡 | 一条回复一个气泡，气泡内保留换行 |
 * | 排版 | 左右分栏 + 头像 + 时间戳 | 左右气泡 + 时间戳（不带头像：表上 36dp 等于两行正文） |
 * | 输入 | 贴键盘的横条 | 一颗 52dp 圆键 → 独立输入页 |
 * | 节奏 | 按"条"推进 | 按"字"推进（气泡一边长字一边滚） |
 *
 * 试过"她的话占满整宽、我的话缩成右侧引文"的排版，看着像文档流不像聊天 ——
 * 用户的原话是「这版不好看」。聊天软件的辨识度几乎全在**左右两列气泡**上，
 * 表盘再小也得给这个结构让位，能压缩的只是头像和间距。
 *
 * 硬把这两套塞进一个 Composable，最后一定是一串 `if (isWatch)`，两边都改不动。
 * 共用的是**行为语义**（读 → 想 → 打字），不是界面代码。
 */

@Composable
fun FishWatchScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    /*
     * 经典界面下全局没有 Wear 主题层，M3E 组件会拿到一组不属于当前色板的默认色。
     * 这一页刻意用 M3E 组件（它是唯一能在圆屏上正确排版的骨架），所以补一层。
     * 只影响这一页，不改用户全局的界面风格。
     */
    if (com.dshwatch.ui.theme.isM3eUi) {
        FishWatchBody(container, settings, nav)
    } else {
        M3eWearTheme(androidx.compose.material3.MaterialTheme.colorScheme) {
            FishWatchBody(container, settings, nav)
        }
    }
}

@Composable
private fun FishWatchBody(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val engine = container.engine
    val scope = rememberCoroutineScope()
    val listState = rememberM3eListState()
    // 动态补写要 Context（取应用级容器）；这一页没有别的地方需要它，就地取
    val context = androidx.compose.ui.platform.LocalContext.current

    val conversation by engine.conversation.collectAsStateWithLifecycle()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val streaming by engine.streaming.collectAsStateWithLifecycle()

    /*
     * 没开启就先问一次。
     *
     * 放在所有副作用之前：下面的 LaunchedEffect 会去开会话、补写动态 ——
     * 那是读盘加联网。没同意过的模式不该因为"点进来看看"就产生请求。
     */
    if (!settings.fishEnabled) {
        var gatePicker by remember { mutableStateOf(false) }
        M3eScaffold(
            title = "养鱼",
            onBack = if (nav.canGoBack) ({ nav.pop() }) else null,
            stackedBar = false,
            horizontalInset = 0.dp,
            /*
             * 关闭态也要有出口。
             *
             * 原来这一屏只有一个「开启」按钮：不开就只剩标题栏一行字，
             * 既没有模式切换图标（那是正常态顶栏才有的），也没有返回 ——
             * 从底下往上、从记录点进来都试过，两条路都出不去。
             * 用户的原话是「顶栏没有任何可以切换模式的地方和退出的地方」。
             */
            actions = {
                M3eIconTap(
                    icon = com.dshwatch.ui.screens.scene.sceneIcon(Scenes.FISH),
                    contentDescription = "切换模式",
                    onClick = { gatePicker = true },
                    diameter = 38.dp,
                    iconSize = 22.dp,
                )
                // 根页没有返回键，给一个明确的「去会话列表」
                if (!nav.canGoBack) {
                    M3eIconTap(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "退出",
                        onClick = { nav.push(Screen.Sessions) },
                        diameter = 38.dp,
                        iconSize = 22.dp,
                    )
                }
            },
        ) {
            FishEnablePanel(
                onEnable = {
                    scope.launch { container.settingsRepo.update { it.copy(fishEnabled = true) } }
                },
                onBack = if (nav.canGoBack) ({ nav.pop() }) else null,
                onSwitchScene = { gatePicker = true },
            )
        }
        if (gatePicker) {
            FishScenePicker(
                container = container,
                settings = settings,
                nav = nav,
                onDismiss = { gatePicker = false },
            )
        }
        return
    }

    // 卡面从存储读：用户在设置里改的人设、关掉的长期记忆都以它为准
    var card by remember { mutableStateOf(BuiltinCards.feiyu()) }
    // 外观只影响这一页。她在聊天里被要求改（fish_skin）时这里会立刻重组
    val skin by container.fishSkin.skin.collectAsStateWithLifecycle()
    var ready by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    /** 关掉养鱼的二次确认 */
    var confirmDisable by remember { mutableStateOf(false) }
    /** 点开的那张图 / 那段视频；null 表示没有在查看 */
    var viewing by remember { mutableStateOf<com.dshwatch.domain.model.MediaRef?>(null) }

    val rhythm = rememberFishWatchRhythm(messages, live)

    /*
     * 她说过话没有。
     *
     * 判据是"屏幕上已经露出来几个字"，不是"消息里有没有字"：流式刚开时正文
     * 已经在 live 里了，但按节奏还一个字都没出 —— 这时若撤掉开场白，
     * 屏幕会突然空一块。
     */
    val hasVoice = messages.any { m ->
        if (m.role != Role.ASSISTANT) return@any false
        val raw = if (live.messageId == m.id && live.text.isNotBlank()) live.text else m.content
        // 只回一个「已读」也算开过口：这时候开场白必须让位
        FishRhythm.isSilent(replyText(raw)) || rhythm.spoken(m.id, FishBubbles.split(raw).size)
    }

    /*
     * 进来就接着上次聊：养鱼是「一个人」，不是「一批会话」。
     *
     * 但如果引擎里**已经**开着一条养鱼会话（从记录页点进来就是这样），
     * 就用那一条 —— 用户的动作是「打开这段对话」，不是「回到最新」。
     */
    LaunchedEffect(Unit) {
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

    // 这一轮说完之后，后台整理长期记忆（和手机版同一个策略：一轮一次）
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
            // 聊天用哪只模型，摘要就用哪只
            model = settings.fishModel,
        )
    }

    /*
     * 跟随最新一条。
     *
     * `scrollToItem` 在 `TransformingLazyColumn` 里把目标项放到**锚点（圆心）**，
     * 不是列表底边。所以滚的是最后那个占位项：它落到圆心时，最新一条正好在
     * 圆心上方，既算滚到了底，也不会被浮在底部的圆键压住。
     */
    val tailIndex = (if (hasVoice) 0 else 1) + messages.size +
        (if (rhythm.phase == FishPhase.THINKING) 1 else 0)
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(ready, messages.size, rhythm.phase, rhythm.tick) {
        if (!ready) return@LaunchedEffect
        if (!landed) {
            runCatching { listState.scrollToItem(tailIndex) }
            landed = true
            return@LaunchedEffect
        }
        if (!settings.autoScroll) return@LaunchedEffect
        runCatching { listState.scrollToItem(tailIndex) }
    }

    /*
     * 底部圆键的抬升量。
     *
     * `M3eScaffold` 默认把底栏贴在屏幕底 8dp —— 矩形屏没问题，圆盘上那已经在
     * 圆周之外了。按几何把圆心放到 0.90d 处：那一行弦宽还有约 190dp，
     * 一颗 52dp 的按钮稳稳落在圆内，下沿离圆周还有 7dp。
     */
    val circle = LocalCircle.current
    val lift = if (circle == null) {
        0.dp
    } else {
        val fabCenterY = circle.top.value + circle.diameter.value * 0.90f
        val fabBottom = fabCenterY + 26f
        (circle.height.value - fabBottom - 8f).coerceAtLeast(0f).dp
    }

    M3eScaffold(
        /*
         * 顶栏和酒馆页保持同一套排布：**返回在左、标题居中、动作在右**。
         *
         * 之前这里用 stackedBar（一排居中图标 + 第二行标题小字），理由是
         * 最初这页有三颗动作、常规顶栏放不下。现在动作收到两颗，而「状态」
         * 那半句话挪去副标题 —— 和酒馆、写作页一个样。用户从一个模式切到
         * 另一个，眼睛不用重新学一遍顶栏。
         */
        title = skin.displayNameOr(card.displayName),
        subtitle = when (rhythm.phase) {
            FishPhase.TYPING -> "正在输入…"
            FishPhase.THINKING -> "在想…"
            else -> null
        },
        onBack = if (nav.canGoBack) ({ nav.pop() }) else null,
        stackedBar = false,
        horizontalInset = 0.dp,
        actions = {
            /*
             * 圆表盘用 36dp 的命中区，方形表用 44dp。
             *
             * 这是全项目 M3E 页的约定（见 `M3eChatScreen`）：这一行的弦宽只有
             * 150dp 上下，44dp 的三颗会顶到圆周。返回 44 + 两颗 36 = 116dp，
             * 两侧各剩十几 dp。
             */
            val dense = com.dshwatch.ui.theme.LocalDeviceProfile.current.isRound
            val diameter = if (dense) 36.dp else 44.dp
            val iconSize = if (dense) 24.dp else 26.dp
            // 酒馆顶栏那颗「模式」药丸的位置。这里用同一处的模式图标，
            // 点开是换模式 —— 和酒馆点「酒馆」药丸是同一个动作。
            M3eIconTap(
                icon = com.dshwatch.ui.screens.scene.sceneIcon(Scenes.FISH),
                contentDescription = "换个模式",
                onClick = { picker = true },
                diameter = diameter,
                iconSize = iconSize,
            )
            M3eIconTap(
                icon = Icons.Outlined.MoreHoriz,
                contentDescription = "更多",
                onClick = { menu = true },
                diameter = diameter,
                iconSize = iconSize,
            )
        },
        bottomOverlay = true,
        bottom = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = lift),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /*
                 * 生成中多一颗停止键，而不是把"说话"换掉。
                 *
                 * 换掉的话，用户想插话得先点停、再点键盘 —— 两次点击之间她那几句
                 * 可能已经发完了。两颗并列，主键永远是「说话」。
                 */
                if (streaming) {
                    M3eFab(
                        icon = Icons.Filled.Stop,
                        contentDescription = "停止",
                        danger = true,
                        onClick = { rhythm.interrupt(); engine.stop() },
                    )
                    Spacer(Modifier.width(10.dp))
                    M3eIconTap(
                        icon = Icons.Outlined.Keyboard,
                        contentDescription = "说话",
                        onClick = { nav.push(Screen.Input()) },
                        diameter = 40.dp,
                        iconSize = 22.dp,
                    )
                } else {
                    M3eFab(
                        icon = Icons.Outlined.Keyboard,
                        contentDescription = "说话",
                        onClick = { nav.push(Screen.Input()) },
                    )
                }
            }
        },
    ) {
        if (!ready || conversation?.scene != Scenes.FISH) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "正在接通…",
                    style = W3.typography.bodySmall,
                    color = W3.colorScheme.onSurfaceVariant,
                )
            }
            return@M3eScaffold
        }

        M3eList(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            /*
             * 横向比默认的 6dp 多留一点。
             *
             * 气泡的圆角是 18dp，比列表卡片（12dp 左右）要圆；6dp 时气泡的弧顶
             * 会蹭到圆周，看起来像被削掉一角。纵向仍用官方的 32dp —— 首项要能
             * 完整露出来。
             */
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp,
                vertical = 32.dp,
            ),
        ) {
            /*
             * 开场白：只活在界面上，不进历史。
             * 进了历史模型会以为自己刚说完，于是接着自说自话。
             */
            if (!hasVoice) {
                item(key = "__greeting__") {
                    HerBubble(
                        text = skin.greeting.takeIf { it.isNotBlank() } ?: card.greeting,
                        bg = skin.theirsOrNull(),
                    )
                }
            }

            items(messages.size, key = { messages[it].id }) { index ->
                val m = messages[index]
                val prev = messages.getOrNull(index - 1)
                val raw = if (live.messageId == m.id && live.text.isNotBlank()) live.text else m.content
                val text = replyText(raw)
                val mine = m.role == Role.USER

                /*
                 * 同一个人连着说两句时贴紧一点，换人时拉开。
                 *
                 * 列表本身有 6dp 的 spacedBy，这里只补差值：连续同一人被压到
                 * 目视约 6dp，换人约 10dp。分不出间隔的话，"两条气泡"读起来
                 * 就像"一句话被切成了两段"。
                 */
                val gapAbove = when {
                    prev == null -> 0.dp
                    (prev.role == Role.USER) == mine -> 0.dp
                    else -> 4.dp
                }

                /*
                 * 一条消息 = 若干个气泡。
                 *
                 * 模型一次给的是**一整段**，真人不会一段一段地发 —— 所以按行
                 * （以及过长的句子）切成几条，一条一条地发出去。切法用的是
                 * 手机版那份 `FishBubbles`：同一段回复在两块屏上拆出来的条数
                 * 必须一致，否则用户换块屏看，同一句话的气泡数都不一样。
                 *
                 * 多个气泡仍装在**同一个列表项**里：列表的 key 与「跟到最新一条」
                 * 的索引换算都是按消息算的，拆成多个 item 要把那套数学全部重算。
                 */
                val bubbles = remember(m.id, text) { FishBubbles.split(text) }
                val count = rhythm.count(m.id, bubbles.size)
                val media = remember(m.id, m.attachments, m.media) {
                    userMedia(m.attachments, m.media)
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = gapAbove),
                    /*
                     * 同一个人连发几条：气泡之间只留 2dp。
                     *
                     * 留大了，这几句看起来像「几条独立的消息」；贴紧了，
                     * 又能读出是同一口气说完的 —— 靠左靠右已经区分了是谁在说。
                     */
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    /*
                     * 附件与图片在气泡之前。
                     *
                     * 表盘只有一块，图片已经是内容本身；先给一张图再跟一句话，
                     * 比反过来更像"她发了张照片，接着说了句什么"。
                     */
                    if (m.attachments.isNotEmpty()) {
                        FishAttachmentNotes(
                            attachments = m.attachments,
                            mine = mine,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (media.isNotEmpty()) {
                        FishMediaStrip(
                            media = media,
                            mine = mine,
                            onOpen = { viewing = it },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    when {
                        // 沉默：只留一个已读回执，聊天框里不出现任何字
                        !mine && FishRhythm.isSilent(text) -> NoteStamp("已读")
                        else -> bubbles.take(count).forEachIndexed { i, bubble ->
                            /*
                             * 正在打的那条，在第一个字出来之前不画。
                             *
                             * 已经"发出去"但它还一个字都没有：直接画就是一个只有
                             * 内边距的小空泡，闪一下再长出字来 —— 看着像加载失败。
                             */
                            val shown = rhythm.frame(m.id, i, bubble)
                            if (shown.isEmpty()) return@forEachIndexed
                            /*
                             * 时间只挂在**最后一条**气泡上，而且等它说完。
                             *
                             * 每条都挂时间，等于把一段话切成五段、每段各发一次；
                             * 说话过程中挂，时间会停在半句话后面 ——
                             * 看起来像「这条已经发完了，后面是下一条」。
                             */
                            val last = i == count - 1 && count == bubbles.size
                            val stamp = if (last && shown.length >= bubble.length) {
                                Fmt.clock(m.createdAt)
                            } else {
                                null
                            }
                            if (mine) {
                                MyBubble(text = shown, time = stamp, bg = skin.accentOrNull())
                            } else {
                                HerBubble(text = shown, time = stamp, bg = skin.theirsOrNull())
                            }
                        }
                    }
                }
            }

            // 她正在想：只有这一段才显示三个点。读、打字两段都不显示 ——
            // 真人在想的时候，你也看不到什么。
            if (rhythm.phase == FishPhase.THINKING) {
                item(key = "__thinking__") { ThinkingNote() }
            }

            /*
             * 尾部占位。
             *
             * 100dp ≈ 圆键抬升后的顶边（8 + lift + 52 ≈ 95）再加上列表自带的
             * 32dp 底边距。少了最后一条会被圆键压住，多了列表显空。
             */
            item(key = "__tail__") { Spacer(Modifier.height(100.dp)) }
        }
    }

    if (picker) {
        /*
         * 模式选择器。
         *
         * 之前**这里什么都没有**：`picker` 在两处被置为 true（顶栏那颗模式图标、
         * 菜单里的「换个模式」），却从来没有被渲染过。点下去的直接后果是
         * 菜单关掉、屏幕上不再出现任何东西 —— 用户看到的是「卡住了，
         * 既切不了模式也退不出去」。这不是动画慢，也不是手势失效。
         *
         * 抽成 [FishScenePicker] 是因为**关闭态**也要用它：养鱼关掉之后这一页
         * 只剩一张「要开始养鱼吗」，顶栏再没有别的出口，用户就困在这里了。
         */
        FishScenePicker(
            container = container,
            settings = settings,
            nav = nav,
            onDismiss = { picker = false },
        )
    }

    if (menu) {
        M3eDialog(
            onDismissRequest = { menu = false },
            title = card.displayName,
            buttons = { M3eGhostButton(text = "关闭", onClick = { menu = false }) },
            content = {
                M3eDialogRow(
                    title = "换个模式",
                    subtitle = Scenes.all.joinToString(" / ") { Scenes.label(it) },
                    onClick = {
                        menu = false
                        picker = true
                    },
                )
                M3eDialogRow(
                    title = "养鱼设置",
                    subtitle = "人设、主动发言时间、记忆、默认 API",
                    onClick = {
                        menu = false
                        nav.push(Screen.FishSettings)
                    },
                )
                /*
                 * 关闭入口就放在这里。
                 *
                 * 以前它只在「养鱼设置」第一行 —— 用户开了之后想关，
                 * 得先猜到设置里有个开关；这一页又只有一屏对话，
                 * 看起来就是「没有关的地方」。功能开关应当在自己那一层可达。
                 */
                M3eDialogRow(
                    title = "关掉养鱼",
                    subtitle = "她不再主动说话，记录都留着",
                    onClick = {
                        menu = false
                        confirmDisable = true
                    },
                )
                M3eDialogRow(
                    title = "她的动态",
                    subtitle = "每天一条，她自己写的",
                    onClick = {
                        menu = false
                        nav.push(Screen.FishMoments)
                    },
                )
                M3eDialogRow(
                    title = "记忆",
                    subtitle = "她记着的、以及正在忘掉的",
                    onClick = {
                        menu = false
                        nav.push(Screen.MemoryDoc(card.id))
                    },
                )
                /*
                 * 没有「重新开一段」。
                 *
                 * 养鱼是一个**人**，不是一批会话：记忆文档、长期记忆压缩点、
                 * 上下文全挂在同一个 conversationId 上。重开一段，她就既不记得
                 * 刚才、也不记得昨天 —— 用户的原话是「有点割裂」。
                 * 所以这里只留「换模式」与「设置」，连入口都不给。
                 */
            },
        )
    }

    if (confirmDisable) {
        M3eConfirmDialog(
            title = "关掉养鱼？",
            text = "她不再主动找你，也不再写动态，后台不会联网。\n聊过的内容与记忆都留着，随时能再开。",
            confirmText = "关掉",
            onConfirm = {
                confirmDisable = false
                scope.launch {
                    container.settingsRepo.update { it.copy(fishEnabled = false) }
                }
            },
            onDismiss = { confirmDisable = false },
        )
    }

    viewing?.let { ref ->
        com.dshwatch.ui.media.MediaViewer(ref = ref, onClose = { viewing = null })
    }
}

/* ────────────────────────────── 气泡 ────────────────────────────── */

/**
 * 气泡的最大宽度。
 *
 * 取屏宽的 0.76：留出左右两列的空隙，也保证圆表盘上看不出"顶到圆周"。
 * 不用固定 dp —— 方表 250dp 宽和圆表 330dp 宽，同一个数值一个太窄一个越界。
 */
@Composable
private fun bubbleMaxWidth(): androidx.compose.ui.unit.Dp {
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return (widthDp * 0.76f).dp
}

/**
 * 她说的话：靠左、`surfaceContainerHigh`。
 *
 * 不带头像。表盘上 36dp 的头像等于两行正文，而"谁说的"已经由位置和底色说清。
 * 气泡宽度跟着内容走（`widthIn` 设上限，不 `fillMaxWidth`）—— 只说一句「嗯」
 * 的时候，占满整行会像一张卡片而不是一句话。
 *
 * ## 圆角是四边一致的
 *
 * 以前给"说话人那一侧"收了一个 5dp 的小角，想做出气泡尖。在手机上是那个味道，
 * 在表盘上只剩「一个角塌了」的观感 —— 用户给的参考图也是四角同圆。
 * 靠左 / 靠右 + 底色已经足够区分是谁在说。
 */
@Composable
private fun HerBubble(
    text: String,
    time: String? = null,
    /** 自定义底色（养鱼页可改）。null = 跟随主题 */
    bg: androidx.compose.ui.graphics.Color? = null,
) {
    val scheme = W3.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth())
                .clip(RoundedCornerShape(14.dp))
                .background(bg ?: scheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = bubbleText(text, time, scheme.onSurfaceVariant),
                style = W3.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
    }
}

/**
 * 气泡正文 + 末尾那一小撮时间。
 *
 * 时间**塞在正文末尾**，而不是单独一行、也不是气泡外的居中灰字：
 *
 * - 单独一行，每条消息多占一行 —— 表盘上一屏只有七八行；
 * - 居中灰字，看着像"系统提示"，一段对话被切成了几块；
 * - 跟在最后一个字后面，才是聊天软件里那颗小小的灰色时间：排版上它自然
 *   落在最后一行的右侧，一行放不下时才换行。
 *
 * 用 `AnnotatedString` 而不是两个 `Text` 并排：并排时时间永远顶在气泡右边缘，
 * 一句只有两个字的话会跟正文之间拉开一大段空白。
 */
private fun bubbleText(
    text: String,
    time: String?,
    tint: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    if (time.isNullOrBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        // 两个窄空格：紧贴着会分不清哪部分是时间
        append("\u2009\u2009")
        withStyle(
            SpanStyle(
                fontSize = 10.sp,
                color = tint.copy(alpha = 0.62f),
                letterSpacing = 0.sp,
            ),
        ) { append(time) }
    }
}

@Composable
private fun MyBubble(
    text: String,
    time: String? = null,
    /** 自定义底色（养鱼页可改）。null = 跟随主题 */
    bg: androidx.compose.ui.graphics.Color? = null,
) {
    val scheme = W3.colorScheme
    /*
     * 自定义色可能是浅色，主题的 onPrimaryContainer 在浅底上几乎看不见。
     * 和手机页一样按亮度换字色 —— 表盘更小，看不清就是"坏了"。
     */
    val fg = if (bg == null) {
        scheme.onPrimaryContainer
    } else {
        if (bg.luminance() > 0.62f) androidx.compose.ui.graphics.Color(0xFF1A1A1A)
        else androidx.compose.ui.graphics.Color.White
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth())
                .clip(RoundedCornerShape(14.dp))
                .background(bg ?: scheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = bubbleText(text, time, fg),
                style = W3.typography.bodyMedium,
                color = fg,
            )
        }
    }
}

/** 居中的一条小字：时间戳，或「已读」 */
@Composable
private fun NoteStamp(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        style = W3.typography.labelSmall,
        color = W3.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** 「正在想」：三个呼吸的点，装在和她一样的气泡壳里，靠左 */
@Composable
private fun ThinkingNote() {
    val scheme = W3.colorScheme
    val transition = rememberInfiniteTransition(label = "fish-thinking")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
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
}

/* ────────────────────────────── 节奏 ────────────────────────────── */

/** 她在干什么。决定顶栏那行小字、以及要不要显示三个点。 */
internal enum class FishPhase { IDLE, READING, THINKING, TYPING }

/**
 * 手表版的节奏状态：**按气泡推进，气泡内一个字一个字长出来**。
 *
 * 手机上是"一条气泡打出来，再打下一条"，记的是"发出去几条"；手表这里需要
 * 同一份东西，外加"正在打的那一条已经露出几个字" —— 圆表盘上一屏只有七八行，
 * 整段一次性铺满，读起来就是一篇小作文，而不是聊天。
 *
 * ## 为什么要按气泡
 *
 * 最早这里是"整段回复一张卡、一个字一个字往外长"，理由是表盘窄、拆成多张卡
 * 会白吃行高。实际用下来有两个问题：
 *
 * 1. 模型给的一段话里本来就有换行，全塞进一张卡，等于把"三句话"显示成"一段"，
 *    而真人聊天是三句三条；
 * 2. 手机版早就按 `FishBubbles` 拆条了，同一段回复在两块屏上的条数不一样，
 *    换块表看同一段对话，观感是错位的。
 *
 * 所以现在两端共用 `FishBubbles` 的切法，手表只是把每条气泡继续做成
 * "一个字一个字长出来"。
 *
 * ## 那条"闪一下就没"的 bug 是怎么来的
 *
 * 旧实现记的是"发出去几条"，收尾时算 `split(正文).size`。可正文在流式期间
 * 只存在于 `live` 里，`ChatMessage.content` 还是空的；引擎在 `streamStep`
 * 返回后**先清空 live、再 finalize 写正文**，中间有一个空窗。协程如果在这个
 * 空窗里读到快照，算出来是 0 条，于是已经显出来的气泡全被 `take(0)` 藏掉 ——
 * 用户的观感就是"闪一下，然后消息没了"。
 *
 * 这里从两头堵死：
 *  1. 正文每次都从**最新的消息列表**里取（[rememberUpdatedState]），不读启动时的快照；
 *  2. 收尾不做任何"条数换算" —— 直接把这条消息从表里删掉，让它回到
 *     "历史消息"分支：正文有多少显示多少。
 */
@Stable
internal class FishWatchRhythm {

    /** 每条消息已经发出去几条气泡。**不在表里 = 全部显示**（历史或已播完）。 */
    private val sent = mutableStateMapOf<String, Int>()

    /** 正在打字的那条消息：它还会多显示一个正在长的气泡 */
    private var typing by mutableStateOf<String?>(null)

    /** 正在打的那个气泡已经露出几个字 */
    private var typed by mutableIntStateOf(0)

    /** 已经播过的消息，防止同一条重播 */
    private val played = mutableSetOf<String>()

    var phase by mutableStateOf(FishPhase.IDLE)
        private set

    /**
     * 单调递增的计数。
     *
     * 列表要跟着"气泡变高"滚动，但条数/字数都可能因为重新生成而回退，
     * 拿它当 key 会漏掉变化。这个计数只增不减，专门用来触发副作用。
     */
    var tick by mutableIntStateOf(0)
        private set

    /**
     * 这条消息现在该显示几条气泡。
     *
     * 历史消息（不在表里）全显示；正在打字的那条，是"已经发出去的 + 一个正在长的"。
     */
    fun count(id: String, total: Int): Int {
        val n = sent[id] ?: return total
        val extra = if (typing == id) 1 else 0
        return (n + extra).coerceIn(0, total)
    }

    /**
     * 第 [index] 条气泡现在该露出多少字。
     *
     * 只有"正在打的那一条"是半截，其余都整句出来 —— 让已经发完的上一条
     * 跟着光标一起长，或者半句话上挂时间戳，都会读成"这条还没发完"。
     */
    fun frame(id: String, index: Int, bubble: String): String {
        if (typing != id) return bubble
        if (index != (sent[id] ?: 0)) return bubble
        return bubble.take(typed.coerceIn(0, bubble.length))
    }

    /**
     * 她有没有真的说出话。
     *
     * 看"已经发出去几条气泡"，而不是"消息里有没有字"：流式刚开始时正文
     * 已经在 `live` 里了，但按节奏一条都还没发 —— 这时若撤掉开场白，
     * 屏幕上会突然空一块。
     */
    fun spoken(id: String, total: Int): Boolean {
        val n = sent[id] ?: return total > 0
        return n > 0
    }

    fun interrupt() {
        interruptFlag++
    }

    /** 打断计数：变化时，正在跑的那条立刻收尾 */
    var interruptFlag by mutableIntStateOf(0)
        private set

    internal fun begin(id: String): Boolean {
        if (id in played) return false
        played += id
        sent[id] = 0
        typing = null
        typed = 0
        phase = FishPhase.READING
        tick++
        return true
    }

    /** 第 [index] 条气泡开始打，已经露出 [chars] 个字 */
    internal fun typingBubble(id: String, index: Int, chars: Int) {
        sent[id] = index
        typing = id
        typed = chars
        phase = FishPhase.TYPING
        tick++
    }

    internal fun think() {
        phase = FishPhase.THINKING
        tick++
    }

    internal fun settle(id: String) {
        sent.remove(id)
        if (typing == id) typing = null
        if (phase != FishPhase.IDLE) phase = FishPhase.IDLE
        tick++
    }
}

/**
 * 挂上节奏调度。
 *
 * 关键词是「最后一条消息的 id」：引擎在流式开始时会先追加一条空的助手消息，
 * 这一下正好触发。历史消息（状态不是 STREAMING）不会进来。
 */
@Composable
private fun rememberFishWatchRhythm(
    messages: List<ChatMessage>,
    live: LiveDelta,
): FishWatchRhythm {
    val rhythm = remember { FishWatchRhythm() }

    val latest by rememberUpdatedState(messages)
    val liveText by rememberUpdatedState(live.text)
    val liveId by rememberUpdatedState(live.messageId)
    val last = messages.lastOrNull()

    LaunchedEffect(last?.id) {
        val msg = last ?: return@LaunchedEffect
        if (msg.role != Role.ASSISTANT) return@LaunchedEffect
        if (msg.status != MessageStatus.STREAMING) return@LaunchedEffect
        if (!rhythm.begin(msg.id)) return@LaunchedEffect

        val token = rhythm.interruptFlag

        /** 这条消息**当前**的正文：流式读 live，结束后读列表里的那条 */
        fun rawNow(): String {
            if (liveId == msg.id) {
                val t = liveText
                if (t.isNotBlank()) return t
            }
            return latest.firstOrNull { it.id == msg.id }?.content.orEmpty()
        }

        fun stillGenerating(): Boolean =
            latest.firstOrNull { it.id == msg.id }?.status == MessageStatus.STREAMING

        try {
            // 1. 读你那条
            val userLine = latest.lastOrNull { it.role == Role.USER }?.content.orEmpty()
            if (!nap(FishRhythm.readDelay(userLine)) { rhythm.interruptFlag != token }) {
                return@LaunchedEffect
            }

            // 2. 想。这一段屏幕上是空的，顶栏显示"在想"
            rhythm.think()
            if (!nap(FishRhythm.thinkDelay(rawNow())) { rhythm.interruptFlag != token }) {
                return@LaunchedEffect
            }

            /*
             * 3. 一条一条发，每条自己一个字一个字长出来。
             *
             * `FishBubbles.split` 每一轮都重新算：流式期间正文还在变长，
             * 刚才只有一句，这一轮可能已经来了第三句。已经发到第几条记在
             * `index` 上，重算不会让它回退。
             *
             * 沉默（只回一个「已读」）时 split 返回空表，这个循环就在
             * "等下一句"上空转到生成结束 —— 屏幕上一条气泡都不出，只留回执。
             */
            var index = 0
            rhythm.typingBubble(msg.id, 0, 0)
            while (true) {
                if (rhythm.interruptFlag != token) break
                val bubbles = FishBubbles.split(rawNow())
                if (index >= bubbles.size) {
                    if (!stillGenerating()) break
                    if (!nap(70L) { rhythm.interruptFlag != token }) break
                    continue
                }
                val bubble = bubbles[index]
                // 起手先空一拍：刚冒出来的那条先停一下再开始出字，
                // 否则第一颗字跟气泡边框同时出现，像"整条消息瞬间到齐"
                if (!nap(160L) { rhythm.interruptFlag != token }) break
                var n = 0
                while (n < bubble.length) {
                    if (rhythm.interruptFlag != token) break
                    // 长句按比例多出几个字，免得一条长消息打上一分钟
                    val step = if (bubble.length <= 40) 1 else (bubble.length / 24).coerceAtLeast(1)
                    val wait = charDelay(bubble, n)
                    if (!nap(wait) { rhythm.interruptFlag != token }) break
                    n = minOf(bubble.length, n + step)
                    rhythm.typingBubble(msg.id, index, n)
                }
                if (rhythm.interruptFlag != token) break
                index++
                rhythm.typingBubble(msg.id, index, 0)
                if (!nap(FishRhythm.bubbleGap()) { rhythm.interruptFlag != token }) break
            }
        } finally {
            rhythm.settle(msg.id)
        }
    }

    return rhythm
}

/** 一个字（或一小撮字）的间隔：标点后停久一点，像换气 */
private fun charDelay(text: String, index: Int): Long {
    val c = text.getOrNull(index) ?: return 42L
    return when (c) {
        '。', '！', '？', '.', '!', '?', '…' -> 260L
        '，', '、', ',', '；', ';', '：', ':' -> 150L
        '\n' -> 180L
        else -> 42L
    }
}

/**
 * 可打断的短睡。
 *
 * 不做成一次 `delay(1800)`：那样用户按下停止后，最坏要等两秒才收尾，
 * 而这两秒里她还在"说话"。切成 50ms 一片，取消最多延迟一帧就能看见。
 */
private suspend fun nap(ms: Long, cancelled: () -> Boolean): Boolean {
    var left = ms
    while (left > 0) {
        if (cancelled()) return false
        val step = minOf(50L, left)
        delay(step)
        left -= step
    }
    return !cancelled()
}

/* ────────────────────────────── 文本 ────────────────────────────── */

/** 行首的列表符号（模型偶尔会把聊天写成清单） */
private val LINE_PREFIX = Regex("""^[ \t]*(?:[-•*·]|\d{1,2}\s*[.、)])[ \t]*""", RegexOption.MULTILINE)

/** 行内 Markdown 记号：聊天框里没人打这些 */
private val INLINE_MARK = Regex("""[*_`]{1,3}""")

/** 连续空行压成一个 —— 表盘上一屏就这么点，空行是奢侈的 */
private val BLANK_LINES = Regex("""\n{3,}""")

/**
 * 一条回复在表盘上的最终样子（清洗后的整段）。
 *
 * 拆条由 `FishBubbles.split` 负责：它按换行切、再清列表符号与 Markdown，
 * 两块屏用的是同一份规则。这里只把行首符号、行内记号清掉，并把连续空行
 * 压成一个 —— 表盘上一屏就这么点，空行是奢侈的。
 */
internal fun replyText(raw: String): String =
    raw.replace(LINE_PREFIX, "")
        .replace(INLINE_MARK, "")
        .replace(BLANK_LINES, "\n\n")
        .trim()

/**
 * 切换模式（M3E 弹窗）。
 *
 * 用 M3E 的弹窗而不是 `ScenePickerDialog`（那套是经典界面的 GlassDialog）：
 * 养鱼页整个是 M3E 骨架，混一个 Material3 弹窗会在圆屏上露出一圈方角。
 *
 * 关闭态和正常态共用这一份：关掉养鱼之后，这里是唯一的出口。
 */
@Composable
private fun FishScenePicker(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val engine = container.engine
    M3eDialog(
        onDismissRequest = onDismiss,
        title = "切换模式",
        content = {
            Scenes.all.forEach { id ->
                val active = Scenes.of(settings.useScene) == id
                M3eDialogRow(
                    title = Scenes.label(id),
                    subtitle = if (active) "当前" else Scenes.desc(id),
                    selected = active,
                    onClick = {
                        onDismiss()
                        if (active) return@M3eDialogRow
                        scope.launch {
                            container.settingsRepo.update { it.copy(useScene = id) }
                            /*
                             * 编程页是唯一"直接显示当前会话"的根屏，而且它只在
                             * 没有任何会话时才去找一条 —— 此刻引擎里开着的是
                             * 养鱼这条，切过去就会看到刚聊完的鱼出现在编程界面，
                             * 跟没切一样。所以这里把会话交还给目标场景自己：
                             * 有编程记录就接上，没有就新开一条。
                             *
                             * 酒馆 / 写作是列表页，不显示会话，不用管。
                             */
                            if (id == Scenes.CODING &&
                                Scenes.of(engine.conversation.value?.scene) != Scenes.CODING
                            ) {
                                val latest = runCatching {
                                    container.conversationStore.index()
                                        .firstOrNull { Scenes.of(it.scene) == Scenes.CODING }
                                }.getOrNull()
                                runCatching {
                                    if (latest != null) {
                                        engine.open(latest.id)
                                    } else {
                                        engine.newConversation(settings.copy(useScene = id))
                                    }
                                }
                            }
                        }
                        /*
                         * 回到根就够了：`Screen.Chat` 按 useScene 重新分发，
                         * 选酒馆看到的就是酒馆的卡列表，选编程就是工作台。
                         * 再 push 一个 TavernHome 会在栈里留两份同样的页面。
                         */
                        nav.popToRoot()
                    },
                )
            }
        },
        buttons = { M3eGhostButton(text = "取消", onClick = onDismiss) },
    )
}
