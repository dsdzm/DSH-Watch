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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.core.AppContainer
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 长期记忆文档的查看与编辑页。
 *
 * ## 为什么允许用户直接改
 *
 * 摘要一定会出错 —— 模型会漏掉细节、张冠李戴、把伏笔当无关内容丢掉。
 * 一旦出错，用户唯一的补救手段就是"改它的记忆"，而不是重开一个会话。
 * 所以这个页面是编辑优先的：默认就是可编辑的 textarea，
 * 而不是只读预览 + 一个隐藏的编辑按钮。
 *
 * 文件本身是工作空间里的普通 markdown（`memory/<卡 id>.md`），
 * 也能从「工作空间」页打开、导出、用电脑改。
 */
@Composable
fun MemoryDocScreen(
    container: AppContainer,
    cardId: String,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var cardName by remember { mutableStateOf("") }

    LaunchedEffect(cardId) {
        text = runCatching { container.memoryStore.read(cardId) }.getOrDefault("")
        cardName = runCatching { container.roleCardStore.load(cardId)?.displayName.orEmpty() }
            .getOrDefault("")
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
                "这份文档会随系统提示一起发给模型。可以直接改、删、加 —— " +
                    "下次对话以这里为准。文件位置：${container.memoryStore.pathOf(cardId)}"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    dirty = true
                },
                placeholder = {
                    Text(
                        "还没有记忆。聊得多了（上下文超过窗口一半）会自动写在这里。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                minLines = 8,
                maxLines = 24,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchGhostButton(
                    text = if (dirty) "保存" else "已保存",
                    modifier = Modifier.weight(1f),
                    enabled = dirty,
                    onClick = {
                        scope.launch {
                            container.memoryStore.replace(cardId, text)
                            dirty = false
                            nav.pop()
                        }
                    },
                )
                WatchGhostButton(
                    text = "清空记忆",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            container.memoryStore.clear(cardId)
                            text = ""
                            dirty = false
                        }
                    },
                )
            }
        }
    }
}
