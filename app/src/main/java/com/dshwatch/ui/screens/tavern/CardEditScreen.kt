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
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.roleplay.CardField
import com.dshwatch.domain.roleplay.LoreEntry
import com.dshwatch.domain.roleplay.CardPlugin
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.HintText
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

    LaunchedEffect(cardId) {
        if (cardId.isNotBlank()) {
            container.roleCardStore.load(cardId)?.let { card = it }
        }
        loaded = true
    }

    fun edit(block: (RoleCard) -> RoleCard) {
        card = block(card)
        dirty = true
    }

    fun save(thenChat: Boolean) {
        scope.launch {
            val saved = runCatching { container.roleCardStore.save(card) }.getOrNull() ?: return@launch
            card = saved
            dirty = false
            if (thenChat) {
                val s = container.settings.value
                container.engine.startNew(
                    settings = s.copy(agentMode = com.dshwatch.domain.model.AgentMode.OFF),
                    scene = Scenes.of(kind),
                    roleCardId = saved.id,
                    title = saved.displayName,
                )
                nav.replaceTop(Screen.RoleplayChat())
            } else {
                nav.pop()
            }
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
        }
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
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 无涟漪点击：表单里的「删除 / 添加」不是导航也不是开关，带涟漪会显得很重 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
    indication = null,
    onClick = onClick,
)
