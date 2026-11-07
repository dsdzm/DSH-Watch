package com.dshwatch.ui.screens.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.theme.LocalGlass

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
    // 上一次发送因为「正在生成」被挡下来：留在本页，并给出原因
    var busy by remember { mutableStateOf(false) }
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

    /*
     * 发送后**只退一层**，回到把这一页推进来的那一页。
     *
     * 这里原来是 `nav.toChat()`（= popToRoot）。编程场景下输入页就压在根屏上，
     * 退到根和退一层是同一个结果，所以一直没人发现；但写作 / 酒馆的层级是
     * 列表 → 对话 → 输入，退到根会直接跳过对话页落在**列表页**上：
     * 用户刚说完一句话就被弹出对话，看不到自己发的内容，也看不到回复。
     * 更糟的是他再点「继续写」时，`engine.open()` 会把还在跑的生成取消掉 ——
     * 于是消息留在本地、回复永远不来。
     *
     * 另外这里要按 send 的返回值决定退不退：上一轮还在生成时消息根本没入队，
     * 关闭输入页等于把用户刚打的字吃掉。
     */
    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        if (container.engine.send(value)) {
            nav.pop()
        } else {
            busy = true
        }
    }

    val watchLayout = com.dshwatch.domain.model.LayoutThemes
        .isWatchLayout(com.dshwatch.ui.theme.LocalLayoutTheme.current)
    WatchScaffold(
        title = s.inputHint,
        onBack = { nav.pop() },
        /*
         * 输入框自己吃满剩余高度并由它内部滚动，骨架不能再套一层
         * verticalScroll —— 里面用了 weight(1f)，套上去会撞无限高约束。
         */
        scrollable = false,
        /*
         * 手表布局：发送是下缘边键，和 Wraive 的输入页一致 ——
         * 一条只有「发送」的横条钉在圆底，会被圆周切掉两端。
         */
        edgeAction = if (watchLayout) {
            {
                com.dshwatch.ui.wear.WearEdgeAction(
                    onClick = { submit() },
                    enabled = text.isNotBlank(),
                ) {
                    androidx.wear.compose.material3.Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = s.send,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            null
        },
        bottom = if (watchLayout) {
            null
        } else {
            {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cornerSafe(),

                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryButton(
                        text = s.send,
                        modifier = Modifier.weight(1f),
                        enabled = text.isNotBlank(),
                        onClick = { submit() },
                    )
                }
            }
        },
    ) {
        // 发出去之前是最后能改主意的时刻，所以提示放在输入框正上方
        if (showPriceBar) {
            DeepSeekPriceBar()
            Spacer(Modifier.height(5.dp))
        }
        if (busy) {
            Text(
                text = "上一轮还在生成，等它写完再发（内容已保留）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(5.dp))
        }

        // 输入框本身做成玻璃卡片：OutlinedTextField 的描边框在玻璃主题下
        // 是一条突兀的主色细线（圆屏上尤其明显），所以这里去掉描边，
        // 只用玻璃的边与底色来界定「这是一个可输入的框」。
        val glass = LocalGlass.current
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                if (busy) busy = false
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (glass) {
                        Modifier.glassSurface(
                            shape = RoundedCornerShape(18.dp),
                            cornerRadius = 18.dp,
                        )
                    } else {
                        Modifier
                    },
                )
                .focusRequester(focusRequester),
            placeholder = {
                Text(s.inputHint, style = MaterialTheme.typography.bodySmall)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            // 玻璃主题：容器透明、无描边、无指示线 —— 框的形状由玻璃自己给
            colors = if (glass) {
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                )
            } else {
                OutlinedTextFieldDefaults.colors()
            },
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
