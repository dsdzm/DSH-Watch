package com.dshwatch.ui.screens.writing

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.roleplay.LoreEntry
import com.dshwatch.domain.roleplay.CardPlugin
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.IconTap
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.scene.ScenePill
import com.dshwatch.ui.screens.scene.ScenePickerDialog
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 写作根界面。
 *
 * 与酒馆同构（列表 → 选一个 → 写），但多两件事：
 *  1. **世界书 / 插件的导入导出**：用户从别处抄来的设定往往是一整段文本，
 *     手表上逐个字段录入不现实，所以提供"粘贴导入"；
 *  2. **续写入口**：写作的主循环是"我写一段 → 它接着写 → 我再改"，
 *     所以列表项上直接把「继续写」做成主按钮。
 */
@Composable
fun WritingScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<RoleCard>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var importTarget by remember { mutableStateOf<RoleCard?>(null) }

    LaunchedEffect(Unit) {
        cards = runCatching { container.roleCardStore.index(Scenes.WRITING) }.getOrDefault(emptyList())
        loaded = true
    }

    WatchScaffold(
        title = "写作",
        actions = {
            ScenePill(Scenes.WRITING) { showPicker = true }
            // 与酒馆页同理：场景根页面必须能直达设置，否则用户得先切回编程模式。
            IconTap(Icons.Outlined.Settings, LocalStrings.current.navSettings) {
                nav.push(Screen.Settings)
            }
        },
    ) {
        if (loaded && cards.isEmpty()) {
            EmptyState(title = "还没有作品", hint = "建一个设定：世界、人物、文风，然后直接开写")
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cards.forEach { card ->
                WritingCardRow(
                    card = card,
                    onContinue = {
                        scope.launch {
                            container.settingsRepo.update { it.copy(lastWritingCardId = card.id) }
                            container.engine.startNew(
                                settings = settings.copy(agentMode = com.dshwatch.domain.model.AgentMode.OFF),
                                scene = Scenes.WRITING,
                                roleCardId = card.id,
                                title = card.displayName,
                            )
                            nav.push(Screen.RoleplayChat)
                        }
                    },
                    onEdit = { nav.push(Screen.CardEdit(Scenes.WRITING, card.id)) },
                    onImport = { importTarget = card },
                )
            }
            Spacer(Modifier.height(2.dp))
            WatchGhostButton(
                text = "新建作品设定",
                icon = Icons.Filled.Add,
                onClick = { nav.push(Screen.CardEdit(Scenes.WRITING, "")) },
            )
        }
    }

    if (showPicker) {
        ScenePickerDialog(
            current = Scenes.WRITING,
            onPick = { id ->
                showPicker = false
                scope.launch { container.settingsRepo.update { it.copy(useScene = id) } }
                when (id) {
                    Scenes.TAVERN -> { nav.popToRoot(); nav.push(Screen.TavernHome) }
                    Scenes.CODING -> nav.popToRoot()
                    else -> Unit
                }
            },
            onDismiss = { showPicker = false },
        )
    }

    importTarget?.let { target ->
        ImportDialog(
            onDismiss = { importTarget = null },
            onImportWorld = { text ->
                scope.launch {
                    val entries = parseLore(text)
                    container.roleCardStore.save(target.copy(entries = target.entries + entries))
                    cards = container.roleCardStore.index(Scenes.WRITING)
                    importTarget = null
                }
            },
            onImportPlugin = { name, text ->
                scope.launch {
                    container.roleCardStore.save(
                        target.copy(plugins = target.plugins + CardPlugin(name = name, prompt = text))
                    )
                    cards = container.roleCardStore.index(Scenes.WRITING)
                    importTarget = null
                }
            },
        )
    }
}

@Composable
private fun WritingCardRow(
    card: RoleCard,
    onContinue: () -> Unit,
    onEdit: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.tagline.isNotBlank()) {
                    Text(
                        text = card.tagline,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val counts = buildString {
                    if (card.entries.isNotEmpty()) append("世界书 ${card.entries.size} · ")
                    append("插件 ${card.plugins.size}")
                    if (card.longTermMemory) append(" · 长期记忆")
                }
                Text(
                    text = counts,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .tap(onClick = withHaptic(onEdit))
                    .padding(8.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        WatchButton(text = "继续写", icon = Icons.Filled.ArrowForward, onClick = onContinue)
        Spacer(Modifier.height(6.dp))
        WatchGhostButton(text = "导入世界书 / 插件", onClick = onImport)
    }
}

/**
 * 导入对话框。
 *
 * 世界书的文本格式刻意做得很宽松 —— 用户抄来的东西格式五花八门，
 * 与其要求他整理，不如尽力猜：
 *  - 每行 `标题: 内容` 或 `标题：内容` （中英文冒号都认）
 *  - 空行分隔的段落，第一行当标题
 *  - 其余整段当成一条常驻条目
 */
@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImportWorld: (String) -> Unit,
    onImportPlugin: (String, String) -> Unit,
) {
    var mode by remember { mutableStateOf(0) } // 0 世界书 / 1 插件
    var text by remember { mutableStateOf("") }
    var pluginName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == 0) "导入世界书" else "导入插件", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wearVerticalScroll(rememberScrollState()),
            ) {
                SectionHeader("类型")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WatchGhostButton(
                        text = "世界书",
                        modifier = Modifier.weight(1f),
                        onClick = { mode = 0 },
                    )
                    WatchGhostButton(
                        text = "提示词",
                        modifier = Modifier.weight(1f),
                        onClick = { mode = 1 },
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (mode == 1) {
                    OutlinedTextField(
                        value = pluginName,
                        onValueChange = { pluginName = it },
                        label = { Text("插件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                HintText(
                    if (mode == 0) "每行「标题：内容」；空行分段。留空关键词即常驻"
                    else "直接粘贴要求原文，会追加到设定之后"
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            if (mode == 0) "车站：建于 1958 年，只有两个站台" else "句式要短，避免形容词堆叠",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    minLines = 5,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    if (mode == 0) onImportWorld(text) else onImportPlugin(pluginName.ifBlank { "导入的插件" }, text)
                },
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 把一段文本解析成世界书条目。
 *
 * 规则见 [ImportDialog] 的注释。解析失败也不报错 —— 最差的情况是
 * 整段落成一个常驻条目，用户还能手动改。
 */
internal fun parseLore(raw: String): List<LoreEntry> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()

    val out = mutableListOf<LoreEntry>()
    // 先按空行分成段
    var blocks = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    /*
     * 「标题 + 空行 + 正文」是最常见的写法（尤其从别处复制过来的），
     * 但空行同时也是段间分隔符 —— 直接按空行切会把它拆成两条，
     * 标题变成一条没有内容的常驻条目，正文变成另一条。
     *
     * 这里先合并一次：一个短句独占一段、且下一段不是键值对时，视为它的标题。
     */
    blocks = mergeLooseHeadings(blocks)

    blocks.forEach { block ->
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return@forEach

        // 情形一：整段是「标题：内容」一行（或多行 键：值）
        val pairs = lines.mapNotNull { line ->
            val idx = line.indexOfFirst { it == ':' || it == '：' }
            if (idx <= 0 || idx >= line.length - 1) null
            else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        if (pairs.size == lines.size) {
            pairs.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) out.add(LoreEntry(title = k, content = v))
            }
            return@forEach
        }

        // 情形二：第一行是标题，其余是内容
        if (lines.size >= 2 && lines[0].length <= 20 && !lines[0].endsWith("。")) {
            out.add(LoreEntry(title = lines[0], content = lines.drop(1).joinToString("\n")))
        } else {
            out.add(LoreEntry(title = "", content = block))
        }
    }
    return out
}

/**
 * 把「短标题独占一段 + 下一段是正文」合并成一段。
 *
 * 判定条件刻意保守：标题段只有一行、不超过 20 个字、不像句子（不以句号结尾）、
 * 不以冒号结尾（那是键值对）、且下一段不是一整个键值对块。
 * 宁可漏合并（退化成常驻条目，用户还能改），也不要错误吞掉一条真正的条目。
 */
private fun mergeLooseHeadings(blocks: List<String>): List<String> {
    if (blocks.size < 2) return blocks
    fun isKeyValueBlock(b: String): Boolean {
        val lines = b.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        return lines.all { line ->
            val i = line.indexOfFirst { it == ':' || it == '：' }
            i > 0 && i < line.length - 1
        }
    }
    fun looksLikeHeading(b: String): Boolean {
        val lines = b.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size != 1) return false
        val t = lines[0]
        if (t.length > 20) return false
        if (t.endsWith("。") || t.endsWith("！") || t.endsWith("？")) return false
        if (t.endsWith(":") || t.endsWith("：")) return false
        return true
    }

    val out = mutableListOf<String>()
    var i = 0
    while (i < blocks.size) {
        val cur = blocks[i]
        val next = blocks.getOrNull(i + 1)
        if (next != null && looksLikeHeading(cur) && !isKeyValueBlock(next)) {
            out.add(cur + "\n" + next)
            i += 2
        } else {
            out.add(cur)
            i += 1
        }
    }
    return out
}
