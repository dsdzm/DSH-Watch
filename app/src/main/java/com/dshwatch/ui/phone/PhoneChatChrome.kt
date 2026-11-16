package com.dshwatch.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.R
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.i18n.LocalStrings
import java.time.LocalTime

/**
 * 手机对话页的「外壳零件」。
 *
 * 拆出来而不是塞进 `PhoneChatScreen.kt`：那一页本身有八百行（消息渲染、
 * 附件、弹窗、审批），再往里加顶栏、空状态、输入栏、四个底部弹窗，
 * 改一处要在两千行里找。这里只放**看得见的部分**，且都无状态 ——
 * 状态与副作用留在页面里，这些函数换成预览也能画出来。
 */

// ---------------- 顶栏 ----------------

/**
 * 顶栏：左边一个圆形的菜单键，中间是「会话 + 在哪台机器上跑」，右边是模型。
 *
 * 不用 `TopAppBar`：它的标题区是「一行文字 + 副标题」，而这里中间那颗是
 * **药丸**（有底、有圆角、点得动），右边也是药丸。硬套 TopAppBar 只能靠
 * 往 `title` 里塞自定义 Row，反而比直接排一行更难读。
 */
@Composable
internal fun PhoneChatHeader(
    title: String,
    badge: String,
    remote: Boolean,
    modelLabel: String,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onModel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onMenu,
            shape = CircleShape,
            color = scheme.surfaceContainerLowest,
            shadowElevation = 2.dp,
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "菜单",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 中间这颗：标题 + 「本机 / 远程」。
        // 点它新建会话 —— 聊天应用里「点标题回新对话」是通用手势，
        // 也让右上角的 ＋ 可以省掉，给模型药丸腾出位置。
        Surface(
            onClick = onNewChat,
            shape = RoundedCornerShape(22.dp),
            color = scheme.surfaceContainerLowest,
            shadowElevation = 2.dp,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(
                                // 远程通道用强调色、本机用第三色：一眼看出这句话
                                // 是发到手机上还是发到那台电脑上
                                if (remote) scheme.primary else scheme.tertiary,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        Surface(
            onClick = onModel,
            shape = RoundedCornerShape(22.dp),
            color = scheme.surfaceContainerLowest,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 116.dp),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ---------------- 空状态 ----------------

/**
 * 还没说话时的整屏。
 *
 * 结构照参考图：**大问候语 → 两个状态胶囊 → 一排能直接点的起点**。
 *
 * 为什么不是「输入框上方一行灰字」：新用户打开时最不知道该做什么，
 * 而把他能做的那几件事直接摆出来（看工作区 / 写个工具 / 想点子），
 * 比一句 "输入你的问题" 有用。点一下就把问题填进去并发出去，零打字。
 */
@Composable
internal fun GreetingHome(
    agentMode: AgentMode,
    scene: String,
    showMascot: Boolean,
    onPickMode: () -> Unit,
    onPickScene: () -> Unit,
    onSeed: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        if (showMascot) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.mascot),
                contentDescription = null,
                modifier = Modifier.size(84.dp),
                alpha = 0.95f,
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = greetingOf(LocalTime.now().hour),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "今天想做点什么？",
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickChip(
                icon = Icons.Outlined.AutoAwesome,
                text = agentMode.label,
                onClick = onPickMode,
            )
            QuickChip(
                icon = Icons.Outlined.Translate,
                text = Scenes.label(scene),
                onClick = onPickScene,
            )
        }

        Spacer(Modifier.height(22.dp))
        suggestions(scene).forEach { item ->
            Surface(
                onClick = { onSeed(item.prompt) },
                shape = RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                        )
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = scheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 状态胶囊：模式 / 场景。没有底色边框，靠圆角和图标区分于正文 */
@Composable
private fun QuickChip(icon: ImageVector, text: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private data class Seed(val icon: ImageVector, val title: String, val subtitle: String, val prompt: String)

/**
 * 起点清单。
 *
 * 按场景给不同的一组：酒馆/写作里「看看工作区」毫无意义 —— 那边用户想做的是
 * 「开始一段剧情」「接着写下去」。同一个空状态在两套场景里必须说不同的话，
 * 否则就是「这 App 不知道我在干什么」。
 */
private fun suggestions(scene: String): List<Seed> = when (Scenes.of(scene)) {
    Scenes.TAVERN -> listOf(
        Seed(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = "随便聊两句",
            subtitle = "让角色先开口，你再决定往哪走",
            prompt = "你先开口，用两三句把我带进这个场景。",
        ),
        Seed(
            icon = Icons.Outlined.TableChart,
            title = "换个视角重来",
            subtitle = "同一段剧情，从对方的眼睛里看",
            prompt = "把刚才这段用对方的视角重写一遍，保留全部细节。",
        ),
    )

    Scenes.WRITING -> listOf(
        Seed(
            icon = Icons.Outlined.AutoAwesome,
            title = "接着写下去",
            subtitle = "按现在的设定续一段，保持语气",
            prompt = "接着往下写一段，保持现在的语气和节奏，不要总结。",
        ),
        Seed(
            icon = Icons.Outlined.Lightbulb,
            title = "想几个转折",
            subtitle = "三个能把故事推向下一幕的选择",
            prompt = "给我三个能把故事推向下一幕的转折，各写两句话。",
        ),
    )

    else -> listOf(
        Seed(
            icon = Icons.Outlined.FolderOpen,
            title = "看看工作区",
            subtitle = "读一遍文件和项目，说说各自是做什么的",
            prompt = "看看工作区里有什么，按目录说一遍各自是做什么的。",
        ),
        Seed(
            icon = Icons.Outlined.AutoAwesome,
            title = "写个小工具",
            subtitle = "单文件网页，写完直接能打开",
            prompt = "帮我写一个单文件网页小工具，不依赖任何外部库，写完告诉我怎么打开。",
        ),
        Seed(
            icon = Icons.Outlined.Lightbulb,
            title = "想点能做完的事",
            subtitle = "三个今晚就能收尾的小项目",
            prompt = "给我三个今晚就能做完的小项目，按难度排，每个一句话说清做什么。",
        ),
    )
}

/** 按小时问候。凌晨不写「早上好」——那个点还在用的人不需要被提醒现在几点 */
private fun greetingOf(hour: Int): String = when (hour) {
    in 5..8 -> "早上好"
    in 9..11 -> "上午好"
    12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

// ---------------- 输入栏 ----------------

/**
 * 悬浮输入栏。
 *
 * 参考图是一颗离底边的胶囊：`＋` 在左、右侧一个圆按钮。
 * 和原来那个带描边的 `OutlinedTextField` 比，这样做的好处是**输入框本身
 * 不抢视觉**——正文才是这一页的主角，输入框只要在拇指够得到的地方就行。
 *
 * 右侧按钮是**三态**的：生成中显示「停止」、框里有字显示「发送」、
 * 空框显示「麦克风」。一个位置解决三件事，拇指不用找。
 */
@Composable
internal fun PhoneComposer(
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
    onVoice: () -> Unit,
) {
    val s = LocalStrings.current
    val scheme = MaterialTheme.colorScheme

    // 不加 imePadding：整页根容器已经加过了，这里再加一次会让输入栏
    // 比键盘高出整整一个键盘的高度
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (note.isNotBlank()) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = scheme.surfaceContainerLowest,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, scheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(26.dp)),
        ) {
            Column {
                if (attachments.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        attachments.forEach { att ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "📄 ${att.name}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (att.ok) scheme.onSurface else scheme.error,
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = onPickFile,
                        enabled = !busy,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "附件",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    TextField(
                        value = input,
                        onValueChange = onInput,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = s.inputHint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                            )
                        },
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    FilledIconButton(
                        onClick = {
                            when {
                                streaming -> onStop()
                                input.isBlank() && attachments.isEmpty() -> onVoice()
                                else -> onSend()
                            }
                        },
                        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp).size(40.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                streaming -> Icons.Filled.Stop
                                input.isBlank() && attachments.isEmpty() -> Icons.Filled.Mic
                                else -> Icons.AutoMirrored.Filled.Send
                            },
                            contentDescription = when {
                                streaming -> s.stop
                                input.isBlank() && attachments.isEmpty() -> "语音输入"
                                else -> s.send
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 底部弹窗 ----------------

/**
 * 菜单。
 *
 * 参考图里左上角那个 `☰` 通常开抽屉；这里用底部弹窗 —— 手表的次级页
 * 全是整屏 push，抽屉要额外维护一套手势与遮罩，而这一页真正高频的只有
 * 「换个场景 / 翻记录 / 去设置」三件事，弹窗一次列完更省事。
 *
 * **酒馆与写作放在这里**是这次的关键：之前手机版只有底部三个 tab，
 * 场景切换只存在于手表那套界面里，手机用户根本进不去这两个模式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneMenuSheet(
    scene: String,
    onNewChat: () -> Unit,
    onOpenSessions: () -> Unit,
    onPickScene: () -> Unit,
    onContext: () -> Unit,
    onCutContext: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            Text(
                text = "新对话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
            )
            Text(
                text = "当前模式：${Scenes.label(scene)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            )
            SheetRow(
                icon = Icons.Filled.Add,
                title = "新建会话",
                subtitle = "在当前模式下开一段新的",
                onClick = onNewChat,
            )
            SheetRow(
                icon = Icons.Outlined.Translate,
                title = "切换模式",
                subtitle = "编程 / 酒馆 / 写作",
                onClick = onPickScene,
            )
            SheetRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = LocalStrings.current.navSessions,
                subtitle = "翻以前的对话",
                onClick = onOpenSessions,
            )
            SheetRow(
                icon = Icons.Outlined.TableChart,
                title = "上下文占用",
                subtitle = "这一轮用掉了多少窗口",
                onClick = onContext,
            )
            SheetRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "清空这一轮上下文",
                subtitle = "从这条往后不再带上更早的内容",
                onClick = onCutContext,
            )
            SheetRow(
                icon = Icons.Outlined.TableChart,
                title = "供应商与模型",
                subtitle = "换模型、填 Key、调思考强度",
                onClick = onOpenProviders,
            )
            SheetRow(
                icon = Icons.Outlined.Settings,
                title = LocalStrings.current.navSettings,
                subtitle = "外观、能力、设备类型",
                onClick = onOpenSettings,
            )
            SheetRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "帮助",
                subtitle = "面板语法、能力说明",
                onClick = onOpenHelp,
            )
        }
    }
}

/** 场景选择：三个模式各自一句人话，不做成抽象图标 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneSceneSheet(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            Text(
                text = "切换模式",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            Scenes.all.forEach { id ->
                SheetRow(
                    icon = when (id) {
                        Scenes.TAVERN -> Icons.Outlined.ChatBubbleOutline
                        Scenes.WRITING -> Icons.Outlined.AutoAwesome
                        else -> Icons.Outlined.TableChart
                    },
                    title = Scenes.label(id),
                    subtitle = Scenes.desc(id),
                    selected = Scenes.of(current) == id,
                    onClick = { onPick(id) },
                )
            }
        }
    }
}

/** 工具放行策略：四档，每档一句说明 —— 只写名字没人知道「只读自动」是什么 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneModeSheet(
    current: AgentMode,
    onPick: (AgentMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            Text(
                text = "代理模式",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
            )
            Text(
                text = "决定模型能不能直接动你的东西",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            AgentMode.entries.forEach { mode ->
                SheetRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = mode.label,
                    subtitle = mode.desc,
                    selected = current == mode,
                    onClick = { onPick(mode) },
                )
            }
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
