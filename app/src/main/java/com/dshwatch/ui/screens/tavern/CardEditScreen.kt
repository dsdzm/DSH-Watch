package com.dshwatch.ui.screens.tavern

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.roleplay.CardField
import com.dshwatch.domain.roleplay.LoreEntry
import com.dshwatch.domain.roleplay.CardPlugin
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.M3Switch
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 卡编辑页（酒馆角色卡 / 写作设定卡共用）。
 *
 * ## 为什么是一个"字段列表"而不是固定表单
 *
 * 酒馆要的是「姓名 / 年龄 / 性格 / 性别 / 种族」这类结构化条目，
 * 写作要的是「世界背景 / 剧情背景 / 人物描述」这类长短不一的段落。
 * 硬编码一套字段，两种场景都会别扭；做成可增删的键值列表，
 * 用户自己决定要哪几行，界面也只有一套。
 *
 * ## 手表上的输入策略
 *
 * 一屏只放**一个**输入框是底线 —— 手表键盘会占掉大半屏，两个框并排必然互相挤。
 * 所以这里全部竖排、每个框独立一行，配 [KeyboardCapitalization.None] 关掉
 * 自动大写（中文用不上，英文人名又常被它改错）。
 */
@Composable
fun CardEditScreen(
    container: AppContainer,
    nav: WatchNavigator,
    kind: String,
    cardId: String,
) {
    val scope = rememberCoroutineScope()
    val isTavern = Scenes.of(kind) == Scenes.TAVERN

    var card by remember { mutableStateOf(RoleCard(kind = Scenes.of(kind))) }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }
    var shareBusy by remember { mutableStateOf(false) }
    var shareNotice by remember { mutableStateOf<String?>(null) }
    var shareFailed by remember { mutableStateOf(false) }
    val community by container.communityAccount.state.collectAsStateWithLifecycle()

    LaunchedEffect(cardId) {
        if (cardId.isNotBlank()) {
            container.roleCardStore.load(cardId)?.let { card = it }
        }
        loaded = true
    }

    /*
     * 从登录页回来时把那句「先登录社区账号，回来再点一次」擦掉。
     *
     * 否则它会一直挂在按钮下面：用户明明已经登录了，界面还在教他去登录，
     * 看起来像没登上。登录态一变就清空，提示由下一次点击重新生成。
     */
    LaunchedEffect(community.signedIn) {
        if (community.signedIn) shareNotice = null
    }

    fun edit(block: (RoleCard) -> RoleCard) {
        card = block(card)
        dirty = true
    }

    /** 存盘但不离开页面：投稿前要先落盘，否则投出去的是上一次保存的内容 */
    suspend fun persist(): RoleCard? =
        runCatching { container.roleCardStore.save(card) }
            .onSuccess {
                card = it
                dirty = false
            }
            .getOrNull()

    fun save(thenChat: Boolean) {
        scope.launch {
            val saved = persist() ?: return@launch
            if (thenChat) {
                val s = container.settings.value
                container.engine.startNew(
                    settings = s.copy(agentMode = com.dshwatch.domain.model.AgentMode.OFF),
                    scene = Scenes.of(kind),
                    roleCardId = saved.id,
                    title = saved.displayName,
                )
                nav.replaceTop(Screen.RoleplayChat)
            } else {
                nav.pop()
            }
        }
    }

    /**
     * 投稿。
     *
     * 三件必须先做的事，缺一件用户就会看到一个莫名其妙的错误：
     *  1. **先存盘** —— 手上是编辑中的 `card`，可能是刚改过还没保存的版本；
     *  2. **本地先自查名字与设定长度** —— 服务端的错误确实会返回，
     *     但等到一个来回再说「名字不能为空」很蠢，尤其是手表网络慢；
     *  3. **查有没有投过** —— 投过就带上远端 id 走改稿，否则社区里会出现两张同名的卡。
     */
    fun submitToCommunity() {
        if (card.name.isBlank()) {
            shareFailed = true
            shareNotice = "先给这张卡起个名字"
            return
        }
        if (card.persona.trim().length < 20) {
            shareFailed = true
            shareNotice = "角色设定太短了（至少 20 字）"
            return
        }
        shareBusy = true
        shareNotice = null
        scope.launch {
            val saved = persist()
            if (saved == null) {
                shareBusy = false
                shareFailed = true
                shareNotice = "保存失败，先检查一下存储权限"
                return@launch
            }
            val remote = container.communityAccount.remoteIdOf(saved.id)
            runCatching {
                container.communityApi.submit(
                    token = container.communityAccount.token,
                    card = saved,
                    remoteId = remote,
                )
            }.onSuccess { id ->
                container.communityAccount.rememberSubmission(saved.id, id)
                shareFailed = false
                shareNotice = if (remote > 0) {
                    "已更新，会重新走一遍审核"
                } else {
                    "提交成功，通过审核后就会出现在社区"
                }
            }.onFailure { e ->
                shareFailed = true
                shareNotice = com.dshwatch.ui.screens.community.describeError(e)
            }
            shareBusy = false
        }
    }

    WatchScaffold(
        title = if (cardId.isBlank()) {
            if (isTavern) "新建角色" else "新建设定"
        } else {
            "编辑"
        },
        onBack = { nav.pop() },
    ) {
        if (!loaded) return@WatchScaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            SectionHeader("基本信息")
            Field(
                label = if (isTavern) "角色名" else "作品 / 主角名",
                value = card.name,
                placeholder = if (isTavern) "例如：深夜食堂的老板娘" else "例如：雨夜列车",
                onChange = { v -> edit { it.copy(name = v) } },
            )
            Field(
                label = "一句话简介",
                value = card.tagline,
                placeholder = "列表里显示，一句话就够",
                maxLines = 2,
                onChange = { v -> edit { it.copy(tagline = v) } },
            )

            SectionHeader(if (isTavern) "角色设定" else "世界与剧情")
            Field(
                label = if (isTavern) "人物背景" else "世界背景 / 剧情描述",
                value = card.persona,
                placeholder = if (isTavern) {
                    "她是谁、经历过什么、和你是什么关系"
                } else {
                    "故事发生在哪、正在发生什么、读者已知哪些信息"
                },
                maxLines = 6,
                onChange = { v -> edit { it.copy(persona = v) } },
            )

            if (isTavern) {
                SectionHeader("你的身份")
                Field(
                    label = "我扮演",
                    value = card.userPersona,
                    placeholder = "我是谁、和角色的关系（留空则由模型自行适应）",
                    maxLines = 3,
                    onChange = { v -> edit { it.copy(userPersona = v) } },
                )
            }

            SectionHeader(if (isTavern) "说话方式" else "文风")
            Field(
                label = if (isTavern) "风格要求" else "文风要求",
                value = card.style,
                placeholder = if (isTavern) {
                    "用短句、口语化，不描写心理活动"
                } else {
                    "第三人称限知，句子偏短，不用感叹号"
                },
                maxLines = 3,
                onChange = { v -> edit { it.copy(style = v) } },
            )

            SectionHeader(if (isTavern) "开场白" else "开头")
            Field(
                label = if (isTavern) "第一次见面时角色说的话" else "已经写好的第一段",
                value = card.greeting,
                placeholder = if (isTavern) {
                    "门上的风铃响了一下。她抬头：「还是老样子？」"
                } else {
                    "雨下到第九天，涵洞的水位已经漫过了标尺的第三格。"
                },
                maxLines = 4,
                onChange = { v -> edit { it.copy(greeting = v) } },
            )

            // ---------- 结构化字段 ----------
            SectionHeader(if (isTavern) "结构化设定" else "人物档案")
            HintText(
                if (isTavern) "年龄 / 性别 / 种族 / 性格 …… 每行一个，会按顺序写进设定"
                else "姓名 / 年龄 / 性别 / 身份 …… 每行一个"
            )
            card.fields.forEachIndexed { index, f ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SmallField(
                            value = f.key,
                            placeholder = "项目，如 年龄",
                            onChange = { v ->
                                edit {
                                    it.copy(fields = it.fields.toMutableList().also { l ->
                                        l[index] = l[index].copy(key = v)
                                    })
                                }
                            },
                        )
                        SmallField(
                            value = f.value,
                            placeholder = "内容",
                            onChange = { v ->
                                edit {
                                    it.copy(fields = it.fields.toMutableList().also { l ->
                                        l[index] = l[index].copy(value = v)
                                    })
                                }
                            },
                        )
                    }
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .padding(start = 6.dp, top = 4.dp)
                                    .clickableNoRipple {
                                edit { c -> c.copy(fields = c.fields.filterIndexed { i, _ -> i != index }) }
                            }
                            .padding(10.dp),
                    )
                }
            }
            WatchGhostButton(
                text = "添加一项",
                icon = Icons.Filled.Add,
                onClick = { edit { it.copy(fields = it.fields + CardField()) } },
            )

            // ---------- 世界书 ----------
            SectionHeader("世界书")
            HintText("常驻条目每轮都会发给模型；带关键词的只在对话里出现该词时才发")
            card.entries.forEachIndexed { index, e ->
                LoreEditor(
                    entry = e,
                    onChange = { v ->
                        edit {
                            it.copy(entries = it.entries.toMutableList().also { l -> l[index] = v })
                        }
                    },
                    onDelete = {
                        edit { c -> c.copy(entries = c.entries.filterIndexed { i, _ -> i != index }) }
                    },
                )
            }
            WatchGhostButton(
                text = "添加世界书条目",
                icon = Icons.Filled.Add,
                onClick = { edit { it.copy(entries = it.entries + LoreEntry()) } },
            )

            // ---------- 提示词插件 ----------
            SectionHeader("提示词插件")
            HintText("你自己写的要求，只影响生成走向与风格。可以在这里加，也可以从文本导入")
            card.plugins.forEachIndexed { index, p ->
                PluginEditor(
                    plugin = p,
                    onChange = { v ->
                        edit {
                            it.copy(plugins = it.plugins.toMutableList().also { l -> l[index] = v })
                        }
                    },
                    onDelete = {
                        edit { c -> c.copy(plugins = c.plugins.filterIndexed { i, _ -> i != index }) }
                    },
                )
            }
            WatchGhostButton(
                text = "添加插件",
                icon = Icons.Filled.Add,
                onClick = { edit { it.copy(plugins = it.plugins + CardPlugin()) } },
            )

            // ---------- 开关 ----------
            SectionHeader("对话设置")
            SwitchLine(
                title = "长期记忆",
                subtitle = "上下文变长时自动压缩成摘要，写进 memory/ 下的文档，然后只带最近的内容",
                checked = card.longTermMemory,
                onChange = { v -> edit { it.copy(longTermMemory = v) } },
            )
            if (!isTavern) {
                SwitchLine(
                    title = "自动续写",
                    subtitle = "每轮结束后自动接着写下一段",
                    checked = card.autoContinue,
                    onChange = { v -> edit { it.copy(autoContinue = v) } },
                )
            }

            Spacer(Modifier.height(14.dp))
            WatchButton(
                text = "保存并开始",
                icon = Icons.Filled.Check,
                onClick = { save(thenChat = true) },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchGhostButton(
                    text = if (dirty) "保存" else "已保存",
                    modifier = Modifier.weight(1f),
                    enabled = dirty,
                    onClick = { save(thenChat = false) },
                )
                WatchGhostButton(
                    text = "取消",
                    modifier = Modifier.weight(1f),
                    onClick = { nav.pop() },
                )
            }
            if (card.builtin) {
                HintText("这是内置卡，改动会直接生效；想保留原样可以先把名字改掉再保存")
            }

            /*
             * 分享到社区。
             *
             * 放在保存 / 取消之后而不是顶栏：投稿是低频动作，顶栏那排图标
             * 留给「每次都点」的功能。但它必须在**编辑页**里 —— 用户刚写完一张卡
             * 是最想分享的时候，让他回到列表再找入口，八成就不投了。
             *
             * 未登录时不清空任何内容、也不跳走太远：登录页是压在编辑页上面的一层，
             * 返回就回到这张卡，填过的字段都还在内存里。
             */
            Spacer(Modifier.height(16.dp))
            WatchGhostButton(
                text = if (shareBusy) "正在提交…" else "分享到社区",
                modifier = Modifier.fillMaxWidth(),
                enabled = !shareBusy,
                onClick = {
                    if (!community.signedIn) {
                        shareFailed = false
                        shareNotice = "先登录社区账号，回来再点一次"
                        nav.push(Screen.CommunityAuth)
                    } else {
                        confirmShare = true
                    }
                },
            )
            shareNotice?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (shareFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            HintText(
                if (community.signedIn) "会公开设定、开场白与世界书；通过审核后才出现在社区"
                else "投稿需要一个社区账号（2~20 位用户名 + 6 位密码）"
            )
            /*
             * 删除入口。
             *
             * 以前这一页只有「保存 / 取消」——卡片一旦建出来就再也删不掉，
             * 用户只能靠「改个名字当废弃」绕过去。列表页也没提供长按菜单
             * （手表上长按被系统占着），所以删除只能放在这里。
             *
             * 内置卡同样允许删：`RoleCardStore` 有一本安装账本，
             * 删掉之后升级不会再把它们塞回来。
             */
            if (cardId.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                WatchGhostButton(
                    text = if (isTavern) "删除这张角色卡" else "删除这则设定",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmDelete = true },
                )
                HintText(
                    if (isTavern) "删除后无法撤销；已经聊过的会话仍留在历史里"
                    else "删除后无法撤销；已经续写过的内容仍留在历史里"
                )
            }
        }
    }

    if (confirmDelete) {
        GlassDialog(
            onDismissRequest = { confirmDelete = false },
            title = "删除？",
            content = {
                Text(
                    text = "「${card.displayName.ifBlank { "这张卡" }}」会从列表里消失，无法撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            buttons = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            runCatching { container.roleCardStore.delete(card.id) }
                            nav.pop()
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    if (confirmShare) {
        GlassDialog(
            onDismissRequest = { confirmShare = false },
            title = "分享到社区？",
            content = {
                Column {
                    Text(
                        text = "「${card.displayName}」会提交给管理员审核，通过后所有人都能看到并下载。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "包含：设定 ${card.persona.length} 字、开场白 ${card.greeting.length} 字、"
                            + "世界书 ${card.entries.size} 条。提示插件不会上传。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            buttons = {
                TextButton(onClick = { confirmShare = false }) { Text("再想想") }
                TextButton(
                    onClick = {
                        confirmShare = false
                        submitToCommunity()
                    },
                ) {
                    Text("提交")
                }
            },
        )
    }
}

/* -------------------------------------------------------------------------- */

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    maxLines: Int = 1,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            singleLine = false,
            minLines = 1,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Default,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SmallField(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun LoreEditor(entry: LoreEntry, onChange: (LoreEntry) -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "条目",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickableNoRipple(onDelete)
                    .padding(8.dp),
            )
        }
        SmallField(entry.title, "标题，如「7 号车厢」") { v -> onChange(entry.copy(title = v)) }
        SmallField(entry.content, "内容") { v -> onChange(entry.copy(content = v)) }
        SmallField(
            entry.keywords.joinToString(","),
            "关键词，逗号分隔；留空 = 常驻",
        ) { v ->
            onChange(
                entry.copy(
                    keywords = v.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                )
            )
        }
        SwitchLine(
            title = "启用",
            subtitle = null,
            checked = entry.enabled,
            onChange = { v -> onChange(entry.copy(enabled = v)) },
        )
    }
}

@Composable
private fun PluginEditor(
    plugin: CardPlugin,
    onChange: (CardPlugin) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "插件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickableNoRipple(onDelete)
                    .padding(8.dp),
            )
        }
        SmallField(plugin.name, "插件名，如「更毒舌一点」") { v -> onChange(plugin.copy(name = v)) }
        Field(
            label = "提示词",
            value = plugin.prompt,
            placeholder = "写下你希望模型遵守的要求",
            maxLines = 4,
            onChange = { v -> onChange(plugin.copy(prompt = v)) },
        )
        SwitchLine(
            title = "插在最后（权重最高）",
            subtitle = "关 = 插在设定之后",
            checked = plugin.position == CardPlugin.POS_LAST,
            onChange = { v ->
                onChange(plugin.copy(position = if (v) CardPlugin.POS_LAST else CardPlugin.POS_SYSTEM))
            },
        )
        SwitchLine(
            title = "启用",
            subtitle = null,
            checked = plugin.enabled,
            onChange = { v -> onChange(plugin.copy(enabled = v)) },
        )
    }
}

@Composable
private fun SwitchLine(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickableNoRipple { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 统一走 M3Switch：玻璃主题下它会自动换成 GlassSwitch，
        // 不再是一块 M3 纯色塑料贴在玻璃卡片上
        M3Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 无涟漪点击：表单里的「删除 / 添加」不是导航也不是开关，带涟漪会显得很重 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
    indication = null,
    onClick = onClick,
)
