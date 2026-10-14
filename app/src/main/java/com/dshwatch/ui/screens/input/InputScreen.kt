package com.dshwatch.ui.screens.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.DeepSeekPricing
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.DeepSeekPriceBar
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.cornerSafe
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator

/**
 * 手表上的输入页。
 *
 * 为什么单独一页：手表键盘会占掉大半个屏，把输入框塞在对话页底部只剩两行可见，
 * 体验很差。这里做成全屏 + 顶部发送按钮，输入完直接回对话页。
 */
@Composable
fun InputScreen(
    container: AppContainer,
    nav: WatchNavigator,
    initial: String = "",
) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val settings by container.settings.collectAsStateWithLifecycle()
    val conversation by container.engine.conversation.collectAsStateWithLifecycle()

    // 只有 DeepSeek 才有峰谷计价，别的供应商显示这行只会是噪音
    val provider = settings.activeProvider
    val model = conversation?.model?.ifBlank { provider?.defaultModel.orEmpty() }.orEmpty()
    val showPriceBar = provider != null &&
        DeepSeekPricing.isDeepSeek(provider.normalizedBaseUrl(), model, provider.name)

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        container.engine.send(value)
        nav.toChat()
    }

    WatchScaffold(
        title = s.inputHint,
        onBack = { nav.pop() },
        bottom = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .cornerSafe()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),

                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    text = s.send,
                    modifier = Modifier.weight(1f),
                    enabled = text.isNotBlank(),
                    onClick = { submit() },
                )
            }
        },
    ) {
        // 发出去之前是最后能改主意的时刻，所以提示放在输入框正上方
        if (showPriceBar) {
            DeepSeekPriceBar()
            Spacer(Modifier.height(5.dp))
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = {
                Text(s.inputHint, style = MaterialTheme.typography.bodySmall)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )

        Spacer(Modifier.height(6.dp))

        // 快捷指令：手表打字慢，常用意图一键插入
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QUICK_PROMPTS.forEach { (label, prompt) ->
                Chip(text = label, onClick = {
                    text = if (text.isBlank()) prompt else (text.trimEnd() + "\n" + prompt)
                })
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private val QUICK_PROMPTS: List<Pair<String, String>> = listOf(
    "解释" to "解释下面这段代码在做什么，并指出潜在问题：\n\n",
    "修 Bug" to "这段代码报错了，请定位根因并给出最小修复补丁：\n\n",
    "写测试" to "为下面这段代码补一份单元测试，覆盖边界情况：\n\n",
    "重构" to "重构下面这段代码，保持行为不变，说明改动理由：\n\n",
    "完整文件" to "给出完整可替换的文件内容，不要片段：\n\n",
    "继续" to "继续",
)
