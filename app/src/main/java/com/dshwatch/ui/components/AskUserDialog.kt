package com.dshwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.agent.PendingQuestion
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.theme.WatchDimens

/**
 * `ask_user` 的提问弹窗。
 *
 * 两个设计上的取舍：
 *
 *  1. **选项做成一整行的可点区域**，而不是小胶囊 ——
 *     手表上目标区域太小就会点错，而这里点错的代价是「AI 按错误的假设继续做下去」。
 *     同时选项文字**保持模型给的完整句子**：把「用 CSS 做圆角卡片」压缩成「方案 A」
 *     会让用户根本没法选。
 *
 *  2. **自由输入框始终可用（除非模型明确禁止）**。
 *     模型给的选项再全，也总有它没想到的答案；逼用户二选一不如给他一个输入框。
 */
@Composable
fun AskUserDialog(
    question: PendingQuestion,
    onAnswer: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val s = LocalStrings.current
    var text by remember(question.question) { mutableStateOf("") }

    fun submit(value: String) {
        val v = value.trim()
        if (v.isNotEmpty()) onAnswer(v)
    }

    AlertDialog(
        onDismissRequest = { /* 必须明确选择，点外部不关闭（关了任务就卡住了） */ },
        title = {
            Text(
                text = s.agentAsking,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (question.options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    question.options.forEachIndexed { index, option ->
                        OptionRow(
                            index = index + 1,
                            text = option,
                            onClick = { submit(option) },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }

                if (question.allowFreeText) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(s.agentAskPlaceholder, style = MaterialTheme.typography.labelSmall)
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                }
            }
        },
        confirmButton = {
            if (question.allowFreeText) {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = { submit(text) },
                ) { Text(s.agentAskSubmit) }
            } else {
                // 只能选选项时，确认按钮就没什么可确认的了，留个跳过的出口
                TextButton(onClick = onSkip) { Text(s.agentAskSkip) }
            }
        },
        dismissButton = {
            // 「跳过」不是「取消」：它会明确告诉模型「你自己定一个默认值继续」，
            // 而不是让任务停在这里。所以措辞上也要区分开。
            TextButton(onClick = onSkip) { Text(s.agentAskSkip) }
        },
    )
}

@Composable
private fun OptionRow(index: Int, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WatchDimens.cardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .tap(onClick = withHaptic(onClick))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = "$index",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
