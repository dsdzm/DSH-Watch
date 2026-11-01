package com.dshwatch.domain.qq

import com.dshwatch.core.Logx
import com.dshwatch.data.settings.SecureKeyStore
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.Role
import com.dshwatch.net.backend.BackendRegistry
import com.dshwatch.net.backend.ChatEvent
import com.dshwatch.net.backend.ChatRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * QQ 通道的业务层：把「群里的一句话」变成「模型的一句话」再发回去。
 *
 * 刻意**不复用 [com.dshwatch.domain.ChatEngine]**，三个原因：
 *
 *  1. ChatEngine 是单会话强状态（当前会话、消息流、正在跑的 job），
 *     QQ 消息进来会把用户正在看的对话顶掉；
 *  2. 它的 `send()` 是「丢进去就不管」，拿不到最终文本，而这里需要同步等人话；
 *  3. 它挂了整套工具与审批，而 QQ 那头**没有人能点确认**，
 *     模型一调需要审批的工具就会永久停住。
 *
 * 所以这里走的是同一套 backend，但发一个干净的一次性请求：不带工具、
 * 不带历史、不落库。代价是 QQ 侧没有上下文记忆 —— 这是有意的取舍：
 * 一个群里几十个人说话，攒上下文既贵又容易串台。
 */
class QqBridge(
    private val settings: () -> AppSettings,
    private val appSecret: () -> String,
    private val keyStore: SecureKeyStore,
    private val backends: BackendRegistry,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "QqBridge"

        /** QQ 单条消息上限约 2000 字，留出余量按 900 切 */
        private const val CHUNK = 900
        private const val MAX_CHUNKS = 3

        /** 等模型的时间。超过就回一句「卡住了」，总比让对面干等强 */
        private const val ANSWER_TIMEOUT_MS = 60_000L
    }

    private val _state = MutableStateFlow(QqState.IDLE)
    val state: StateFlow<QqState> = _state.asStateFlow()

    /** 最近一次失败的说明，给设置页直接显示（不含 token 之类的敏感内容） */
    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private var gateway: QqGateway? = null

    /** 与 gateway 共用同一个实例：token 缓存挂在它身上，另起一个等于每次回复都重换 token */
    private var api: QqApi? = null

    /** 同一个群连发几条时串行处理：并发打模型既费钱，回复顺序也会乱 */
    private val gate = Mutex()

    val running: Boolean get() = gateway != null

    /**
     * 按当前设置重启通道。
     *
     * 每次改设置都整体重启，而不是热改字段：连接用的 AppID / 环境 / token
     * 都是握手时定死的，局部更新只会得到一个「看起来改了、其实还是旧连接」的状态。
     */
    fun restart() {
        stop()
        val s = settings()
        if (!s.qqEnabled) {
            _state.value = QqState.IDLE
            return
        }
        val appId = s.qqAppId.trim()
        val secret = appSecret().trim()
        if (appId.isEmpty() || secret.isEmpty()) {
            _state.value = QqState.FAILED
            _lastError.value = "还没填 AppID 或 AppSecret"
            return
        }
        if (s.qqAllowOpenids.isBlank()) {
            // 允许启动，但说清楚：不填白名单等于谁都不理
            _lastError.value = "白名单是空的，任何人都不会被回复"
        } else {
            _lastError.value = ""
        }
        val client = QqApi(appId, secret, s.qqSandbox)
        api = client
        val g = QqGateway(
            api = client,
            scope = scope,
            onGroupAt = ::handle,
            onState = { st ->
                _state.value = st
                if (st == QqState.ONLINE) _lastError.value = ""
            },
        )
        gateway = g
        g.start()
        Logx.d(TAG, "QQ 通道启动（${if (s.qqSandbox) "沙箱" else "正式"}）")
    }

    fun stop() {
        gateway?.stop()
        gateway = null
        api = null
        _state.value = QqState.IDLE
    }

    // ------------------------------------------------------------------
    // 一条消息的完整生命周期
    // ------------------------------------------------------------------

    private suspend fun handle(msg: QqGroupMessage) {
        val s = settings()
        val allow = s.qqAllowOpenids.split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (allow.isEmpty() || msg.senderOpenid !in allow) {
            // 不打日志内容，只记 openid 的前 8 位：方便用户把自己加进白名单，
            // 又不至于把群友的 id 全量写进 logcat。
            Logx.d(TAG, "忽略未授权消息，sender=${msg.senderOpenid.take(8)}…")
            return
        }

        val provider = s.activeProvider ?: run {
            reply(msg, "我这边还没配模型，先在手表上选个供应商。")
            return
        }

        val question = msg.content.ifBlank { "在吗" }

        val answer = gate.withLock {
            withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
                runCatching { ask(provider, question) }
                    .getOrElse { e ->
                        Logx.w(TAG, "模型调用失败：${e.message}")
                        null
                    }
            }
        }

        if (answer.isNullOrBlank()) {
            reply(msg, "我这边卡了一下，等会儿再问我一次。")
            return
        }
        reply(msg, answer)
    }

    /** 调一次模型，不带工具、不带历史 */
    private suspend fun ask(provider: Provider, question: String): String {
        val backend = backends.of(provider.type)
        val model = provider.defaultModel.ifBlank { provider.models.firstOrNull().orEmpty() }
        if (model.isBlank()) throw QqException("供应商「${provider.name}」没有选模型")

        val request = ChatRequest(
            provider = provider,
            baseUrl = provider.normalizedBaseUrl(),
            apiKey = keyStore.getApiKey(provider.id).orEmpty(),
            serverToken = keyStore.getServerToken(provider.id).orEmpty(),
            model = model,
            systemPrompt = systemPrompt(provider),
            history = listOf(ChatTurn(role = Role.USER.wire, content = question)),
            temperature = provider.temperature,
            topP = provider.topP,
            // QQ 里没人想看长文，把输出上限压到 512：既快又省
            maxTokens = provider.maxTokens.coerceAtMost(512),
            stream = false,
            reasoningEffort = provider.reasoningEffort,
            usageInStream = false,
            // 关键：不带工具。带了就会停在等审批上，永远等不到回音
            tools = emptyList(),
        )

        val sb = StringBuilder()
        var failure: String? = null
        backend.streamChat(request).collect { ev ->
            when (ev) {
                is ChatEvent.Text -> sb.append(ev.delta)
                is ChatEvent.Failure -> failure = ev.error.message
                else -> Unit
            }
        }
        failure?.let { throw QqException(it) }
        return sb.toString().trim()
    }

    /**
     * QQ 侧的说话方式。
     *
     * 和手机里的助手不是同一个场景：那边用户看得见工具在干什么，
     * 这边只有一段裸文本，所以要求「像发微信」而不是「像写报告」。
     * 同时必须明确写死「你做不了什么」—— 模型默认会答应帮人发评论，
     * 而这条通道根本没有那个能力。
     */
    private fun systemPrompt(provider: Provider): String = buildString {
        if (provider.systemPrompt.isNotBlank()) {
            append(provider.systemPrompt.trim()).append("\n\n")
        }
        append("现在你在 QQ 群里跟人聊天，不是在手机上。规矩：\n")
        append("- 一条回复不超过 80 字，像发消息，不像写文章\n")
        append("- 不用 markdown、不用列表、不用「首先其次」这类结构词\n")
        append("- 不知道就说不知道，别编\n")
        append("- 你在这里只能聊天：发不了 B 站评论/动态、不能执行命令、不能读写文件。\n")
        append("  有人让你做这些，就说「这个得在手表上弄」。\n")
    }

    /** 长回复按段发。QQ 单条超长会被截断，截断后连「说完了」都看不出来 */
    private suspend fun reply(msg: QqGroupMessage, text: String) {
        val client = api ?: return

        val chunks = text.chunkedByCodePoint(CHUNK).take(MAX_CHUNKS)
        chunks.forEachIndexed { index, part ->
            runCatching { client.sendGroupMessage(msg.groupOpenid, part, msg.msgId) }
                .onFailure {
                    Logx.w(TAG, "回复第 ${index + 1} 段失败：${it.message}")
                    _lastError.value = "回复失败：${it.message.orEmpty().take(60)}"
                }
        }
    }
}

/**
 * 按「字符数」切段，但**不切开代理对**。
 *
 * 直接用 `String.chunked` 会把 emoji 从中间劈成两个孤立代理项，
 * 发出去就是两个问号。这里按 code point 走，边界落在完整字符上。
 */
internal fun String.chunkedByCodePoint(size: Int): List<String> {
    if (this.length <= size) return listOf(this)
    val out = ArrayList<String>(this.length / size + 1)
    var start = 0
    var count = 0
    var i = 0
    while (i < this.length) {
        val step = if (Character.isHighSurrogate(this[i]) && i + 1 < this.length) 2 else 1
        i += step
        count++
        if (count == size) {
            out += substring(start, i)
            start = i
            count = 0
        }
    }
    if (start < this.length) out += substring(start)
    return out
}
