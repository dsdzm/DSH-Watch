package com.dshwatch.ui.screens.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.core.AppContainer
import com.dshwatch.data.store.MemoryHealth
import com.dshwatch.domain.roleplay.MemoryEntry
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长期记忆文档的查看与编辑页。
 *
 * ## 为什么不再是一个大 textarea
 *
 * 1.24 之前这页只有一个文本框，用户能改，但看不出**她到底记得什么**：
 * 哪条快忘了、哪条已经固化成永久记忆、哪条被反复提到过。
 * 而「会忘」这件事恰恰是用户这次要的核心 —— 看不见就等于不存在。
 *
 * 所以现在是一张张记忆卡：日期、保留度进度条、状态标签，点开能编辑、
 * 能手动「想起来一次」、能直接忘掉。原始文本编辑仍然保留在最下面，
 * 因为摘要一定会出错，手改是最后的兜底。
 */
@Composable
fun MemoryDocScreen(
    container: AppContainer,
    cardId: String,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    var raw by remember { mutableStateOf("") }
    var cardName by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var rawDirty by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntry?>(null) }
    /** 上一次整理记忆走到哪一步 —— 空文档时用户能看出是"没到门槛"还是"请求失败" */
    var health by remember { mutableStateOf<MemoryHealth?>(null) }
    /** 这张卡所属的场景与最近一段对话的条数（"80 句"到底落在哪一段） */
    var cardScene by remember { mutableStateOf("") }
    var sessionCount by remember { mutableIntStateOf(0) }
    /** 「立即整理一次」的运行状态与结果 */
    var busy by remember { mutableStateOf(false) }
    var runNote by remember { mutableStateOf("") }
    /** 已经等了多少秒 —— 一个数字就能回答「它到底死没死」 */
    var busySecs by remember { mutableIntStateOf(0) }
    /** 这次整理会用哪家、哪个模型（进去就告诉用户，省得失败后再猜） */
    var summaryTarget by remember { mutableStateOf("") }

    // 界面上的"现在"只取一次：翻页过程中进度条不该自己跳
    val now = remember { System.currentTimeMillis() }

    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect
        busySecs = 0
        while (true) {
            kotlinx.coroutines.delay(1_000)
            busySecs += 1
        }
    }

    suspend fun reload() {
        entries = runCatching { container.memoryStore.entries(cardId) }.getOrDefault(emptyList())
        raw = runCatching { container.memoryStore.read(cardId) }.getOrDefault("")
        health = runCatching { container.memoryHealth.read() }.getOrNull()
            ?.takeIf { it.cardId == cardId }
        sessionCount = runCatching {
            if (cardScene.isBlank()) 0
            else container.conversationStore.latestFor(cardScene, cardId)?.messageCount ?: 0
        }.getOrDefault(0)
    }

    LaunchedEffect(cardId) {
        val c = runCatching { container.roleCardStore.load(cardId) }.getOrNull()
        cardName = c?.displayName.orEmpty()
        cardScene = c?.kind.orEmpty()
        /*
         * 先把「整理会打给哪家、哪个模型」算出来摆在界面上。
         *
         * 这件事以前只能靠失败文案猜：用户看到「模型名不对」，但不知道
         * 是哪个模型不对、去哪儿改。现在进去就能看到名字。
         */
        summaryTarget = runCatching {
            val conv = container.conversationStore.latestFor(cardScene, cardId)
            container.engine.briefTarget(conv?.providerId.orEmpty(), conv?.model.orEmpty()).label
        }.getOrElse { "还没配好模型，去「设置 → 模型」看看" }
        reload()
        loaded = true
    }

    WatchScaffold(
        title = "长期记忆",
        subtitle = cardName.takeIf { it.isNotBlank() },
        onBack = { nav.pop() },
    ) {
        if (!loaded) return@WatchScaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            HintText(
                "记忆会随时间变淡。常被提起的会记得更牢，" +
                    "情绪强烈的会慢慢变成永久记忆。点开一条可以改、可以忘。"
            )
            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                HintText("还没有记忆。聊得多了会自动写在这里。")
                if (sessionCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    HintText("最近一段对话共 $sessionCount 句。")
                }
                // 空文档时最要紧的是"为什么还空着"：没到门槛、请求失败、还是根本
                // 没有会话。四种情况以前长得一模一样，用户只能反复猜。
                health?.let { h ->
                    val line = describeHealth(h, System.currentTimeMillis())
                    if (line.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        HintText(line)
                    }
                }
            } else {
                MemorySummary(entries, now)
                Spacer(Modifier.height(8.dp))

                SectionHeader("她记得的事")
                // 记得清楚的在前，快忘的在后 —— 和提示词里的顺序一致
                entries
                    .sortedWith(
                        compareBy<MemoryEntry> { it.isFaded(now) }
                            .thenByDescending { it.retention(now) }
                    )
                    .forEach { e ->
                        MemoryEntryCard(
                            entry = e,
                            now = now,
                            onClick = { editing = e },
                        )
                    }
            }

            Spacer(Modifier.height(10.dp))
            /*
             * 就地整理。
             *
             * 用户正盯着一个空文档，"为什么是空的"这个问题在这里最该被回答。
             * 整理完立刻重读记忆与体检记录：成功就出现条目，失败就出现原因。
             */
            if (summaryTarget.isNotBlank() && !busy) {
                HintText("整理会用 ${summaryTarget}")
                Spacer(Modifier.height(4.dp))
            }
            WatchGhostButton(
                text = if (busy) "正在整理 ${busySecs}s…" else "立即整理一次",
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    busy = true
                    runNote = ""
                    scope.launch {
                        /*
                         * try/finally 是这一整段的重点。
                         *
                         * 以前 `busy = false` 写在最后一行，中间任何一步抛异常
                         * （打开会话失败、整理请求报错）都会让它永远执行不到 ——
                         * 按钮就停在「正在整理…」，而且再也点不动。
                         * 这一个 bug 就够让"整理功能坏了"成立。
                         */
                        try {
                            val conv = runCatching {
                                container.engine.conversation.value
                                    ?.takeIf { it.roleCardId == cardId }
                                    ?: container.conversationStore.latestFor(cardScene, cardId)
                            }.getOrNull()
                            if (conv == null) {
                                runNote = "你们还没聊过，没什么可整理的"
                                return@launch
                            }
                            if (container.engine.conversation.value?.id != conv.id) {
                                runCatching { container.engine.open(conv.id) }
                            }
                            val st = container.settings.value
                            val fish = cardScene == com.dshwatch.domain.roleplay.Scenes.FISH
                            /*
                             * 用**这个会话自己的**供应商与模型。
                             *
                             * 以前这里传空串，摘要就走 activeProvider 的默认模型 ——
                             * 而顶栏换过模型之后，会话模型和默认模型往往不是同一个。
                             * 结果就是聊天一切正常，记忆页却报「模型的 API 或模型名不对」。
                             */
                            val providerId = conv.providerId.ifBlank {
                                if (fish) st.fishProviderId else ""
                            }
                            val model = conv.model.ifBlank {
                                if (fish) st.fishModel else ""
                            }
                            val provider = st.providers.firstOrNull { it.id == providerId }
                                ?: st.activeProvider
                            val res = container.memoryCompressor.compressNow(
                                conversationId = conv.id,
                                cardId = cardId,
                                scene = cardScene,
                                contextWindow = provider?.contextWindow ?: 0,
                                providerId = providerId,
                                model = model,
                            )
                            reload()
                            runNote = if (res.ok) {
                                "已记下 ${entries.size} 条（本次新增 ${res.added} 条，覆盖 ${res.covered} 句）"
                            } else {
                                res.message
                            }
                        } catch (e: Throwable) {
                            runNote = "整理失败：${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
            )

            if (runNote.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                HintText(runNote)
            }

            Spacer(Modifier.height(10.dp))
            WatchGhostButton(
                text = if (showRaw) "收起原始文本" else "查看 / 编辑原始文本",
                modifier = Modifier.fillMaxWidth(),
                onClick = { showRaw = !showRaw },
            )

            if (showRaw) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = raw,
                    onValueChange = {
                        raw = it
                        rawDirty = true
                    },
                    placeholder = {
                        Text(
                            "还没有记忆。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    minLines = 6,
                    maxLines = 20,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchGhostButton(
                        text = if (rawDirty) "保存" else "已保存",
                        modifier = Modifier.weight(1f),
                        enabled = rawDirty,
                        onClick = {
                            scope.launch {
                                container.memoryStore.replace(cardId, raw)
                                rawDirty = false
                                reload()
                            }
                        },
                    )
                    WatchGhostButton(
                        text = "全部清空",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                container.memoryStore.clear(cardId)
                                raw = ""
                                rawDirty = false
                                reload()
                            }
                        },
                    )
                }
            }
        }
    }

    editing?.let { e ->
        MemoryEntryDialog(
            entry = e,
            now = now,
            onDismiss = { editing = null },
            onSave = { newText ->
                scope.launch {
                    container.memoryStore.update(cardId, e.at, e.text, newText)
                    editing = null
                    reload()
                }
            },
            onTouch = {
                scope.launch {
                    container.memoryStore.touch(cardId, e.at, e.text)
                    editing = null
                    reload()
                }
            },
            onForget = {
                scope.launch {
                    container.memoryStore.forget(cardId, e.at, e.text)
                    editing = null
                    reload()
                }
            },
        )
    }
}

/** 顶部概览：一共几条、几条清楚、几条模糊、几条已经想不起来 */
@Composable
private fun MemorySummary(entries: List<MemoryEntry>, now: Long) {
    val clear = entries.count { !it.isBlurred(now) }
    val blurred = entries.count { it.isBlurred(now) && !it.isFaded(now) }
    val faded = entries.count { it.isFaded(now) }
    val perm = entries.count { it.permanent }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "共 ${entries.size} 条",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "记得清楚 $clear · 有点模糊 $blurred · 想不起来 $faded" +
                    if (perm > 0) " · 永久 $perm" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 体检记录翻成一句人话，前面带上时刻。
 *
 * 时间要一起传进去：磁盘里那条「正在整理」有可能是上一次被打断留下的，
 * 超过有效期就该说「没走完」，而不是永远让页面显示正在整理。
 */
private fun describeHealth(h: MemoryHealth, now: Long): String {
    val text = MemoryHealth.describe(h, now)
    if (text.isBlank()) return ""
    if (h.at <= 0L) return text
    val time = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(h.at))
    return "$time ｜ $text"
}

/** 一条记忆。进度条就是保留度，不用解释也能看懂 */
@Composable
private fun MemoryEntryCard(
    entry: MemoryEntry,
    now: Long,
    onClick: () -> Unit,
) {
    val retention = entry.retention(now)
    val faded = entry.isFaded(now)
    val accent = when {
        entry.permanent -> MaterialTheme.colorScheme.primary
        faded -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        entry.isBlurred(now) -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dateLabel(entry.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (entry.permanent) "永久" else "记得 ${(retention * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (faded) 0.02f else retention },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
            Text(
                entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (faded) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 3,
            )
            if (entry.hits > 0 || entry.permanent) {
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        if (entry.hits > 0) append("被想起 ${entry.hits} 次")
                        if (entry.permanent) {
                            if (isNotEmpty()) append(" · ")
                            append("一直记着")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun MemoryEntryDialog(
    entry: MemoryEntry,
    now: Long,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onTouch: () -> Unit,
    onForget: () -> Unit,
) {
    var text by remember(entry) { mutableStateOf(entry.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                dateLabel(entry.at) + " · 记得 ${(entry.retention(now) * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("保存") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onTouch) { Text("想起来一次") }
                TextButton(onClick = onForget) {
                    Text("忘掉这条", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun dateLabel(at: Long): String =
    if (at <= 0) "未知时间"
    else SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(at))
