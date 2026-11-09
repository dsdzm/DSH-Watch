package com.dshwatch.ui.screens.input

import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.attachment.AttachmentStore
import com.dshwatch.domain.DeepSeekPricing
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.model.AttachmentKinds
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.DeepSeekPriceBar
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.cornerSafe
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.files.FileBrowserPanel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.theme.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /*
     * 附件。
     *
     * 两条通路，默认走内置浏览器（见 FileBrowserPanel 的注释：一部分手表 ROM
     * 根本没有文件管理器，OpenDocument 会静默失败，用户看到的是「点了没反应」）。
     * 关掉「内置文件浏览器」这个开关就退回系统选择器 —— 有正常文件管理器的设备
     * 上，系统那套能翻到更远的地方。
     *
     * 用 OpenDocument 而不是 GetContent：前者拿到的 uri 在页面重建后仍然有效
     * （附件要跟着消息走，用户可能退出输入页再回来），后者只是一次性授权。
     * mime 传「任意类型」：手表上的文件管理器往往不认更细的类别，限死只会让可选文件为空。
     */
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val attachments = remember { mutableStateListOf<Attachment>() }
    var attachBusy by remember { mutableStateOf(false) }
    var attachNote by remember { mutableStateOf("") }
    var showBrowser by remember { mutableStateOf(false) }

    /** 把一个 uri（系统选择器给的，或内置浏览器给的 file://）读成附件 */
    fun absorb(uri: Uri) {
        if (attachments.size >= AttachmentKinds.MAX_FILES) {
            attachNote = "一次最多带 ${AttachmentKinds.MAX_FILES} 个文件"
            return
        }
        scope.launch {
            attachBusy = true
            attachNote = ""
            val att = withContext(Dispatchers.IO) {
                AttachmentStore.import(context, context.filesDir, uri)
            }
            attachments.add(att)
            if (!att.ok) attachNote = "${att.name}：${att.error}"
            attachBusy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { absorb(it) }
    }

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
        // 只带附件不写字是合法用法：上传一份文件直接问「这份合同有什么风险」
        if (value.isEmpty() && attachments.isEmpty()) return
        if (container.engine.send(value, attachments.filter { it.ok })) {
            nav.pop()
        } else {
            busy = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        WatchScaffold(
            title = s.inputHint,
            onBack = { nav.pop() },
            bottom = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cornerSafe(),

                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryButton(
                        text = s.send,
                        modifier = Modifier.weight(1f),
                        enabled = text.isNotBlank() || attachments.isNotEmpty(),
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

            /*
             * 附件区。
             *
             * 放在输入框和快捷指令之间：这两个都是「发送前最后一次调整」的东西，
             * 挨在一起比塞进顶栏更容易被发现。已选文件连同「提取出多少字」一起显示 ——
             * 用户需要知道这份 PDF 到底有没有读出内容，否则会得到一次基于空文档的回答。
             */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(
                    text = when {
                        attachBusy -> "读取中…"
                        attachments.isEmpty() -> "＋ 文件"
                        else -> "＋ 再加一个"
                    },
                    onClick = {
                        if (attachBusy) return@Chip
                        if (settings.builtinFilePicker) {
                            // 键盘会盖掉半块表盘，面板要全屏，先收起来
                            runCatching { keyboard?.hide() }
                            focusManager.clearFocus()
                            showBrowser = true
                        } else {
                            val launched = runCatching { picker.launch(arrayOf("*/*")) }.isSuccess
                            if (!launched) {
                                attachNote = "系统文件选择器不可用，可在设置 → 文件里改用内置浏览器"
                            }
                        }
                    },
                )
                attachments.forEach { att ->
                    Chip(
                        text = "${att.name.take(12)} · ${att.summary()}  ✕",
                        onClick = { attachments.remove(att) },
                    )
                }
            }
            if (attachNote.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = attachNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (attachments.any { it.ok && it.kind != AttachmentKinds.IMAGE }) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "文本会在发送时随消息一起交给模型（最多 6 万字/文件）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

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

        /*
         * 内置文件浏览器：盖在输入页上，而不是 push 一个新页面。
         *
         * 页面栈换页会销毁旧页的组合 —— 输入页里的草稿文字和已选附件都是普通
         * remember，一 push 就没了。用户挑完文件回来发现自己打的半句话不见了，
         * 这比「没有文件管理器」还难受。面板留在同一棵组合里，什么都不丢。
         */
        if (showBrowser) {
            FileBrowserPanel(
                workspace = container.workspaceStore.rootDir(),
                onClose = { showBrowser = false },
                onPick = { file ->
                    showBrowser = false
                    absorb(Uri.fromFile(file))
                },
                /*
                 * 系统选择器的出口。
                 *
                 * `launch()` 在没有任何 App 响应 ACTION_OPEN_DOCUMENT 时会抛
                 * ActivityNotFoundException —— 这正是本功能要兜的那个场景，
                 * 所以必须接住：否则用户点了「用系统选择器」，App 直接崩。
                 */
                onSystemPicker = {
                    val launched = runCatching { picker.launch(arrayOf("*/*")) }.isSuccess
                    showBrowser = false
                    if (!launched) {
                        attachNote = "这台设备没有可用的系统文件选择器，用内置浏览器选就行"
                    }
                },
            )
        }
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
