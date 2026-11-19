package com.dshwatch.domain.qq

import android.content.Context
import com.dshwatch.core.Logx
import com.dshwatch.data.settings.SettingsRepository
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.domain.ChatEngine
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.model.AttachmentKinds
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.QqSettings
import com.dshwatch.net.qq.OneBotClient
import com.dshwatch.net.qq.QqMessage
import com.dshwatch.net.qq.QqOut
import com.dshwatch.net.qq.QqResult
import com.dshwatch.net.qq.QqSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * QQ 与对话引擎之间的桥。
 *
 * ## 数据流
 *
 * ```
 * OneBot 后端 ←HTTP→ 本类 ──→ ChatEngine（独立实例）──→ 模型
 *      ↑                 │
 *      └──── 回复 ────────┘
 * ```
 *
 * ## 三条必须守住的规则
 *
 * 1. **自己发的不能当输入。** 机器人发出去的消息会出现在历史接口里，
 *    不过滤就是自己跟自己聊到天荒地老。按 `senderId == selfId` 挡。
 * 2. **启动时不能补发历史。** 第一次看到某个会话时只记位置，不回复 ——
 *    否则一开开关就把昨天的二十条消息全回一遍。
 * 3. **消息要串行。** 引擎一个实例一次只能跑一轮；并发进来会被
 *    `send()` 直接拒绝。轮询是单线程顺序处理，天然满足。
 */
class QqBridge(
    private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val engine: ChatEngine,
    private val store: ConversationStore,
    private val client: OneBotClient,
    private val json: Json,
    private val scope: CoroutineScope,
) {

    /** 对外可见的运行状态，设置页与通知栏都读它 */
    data class State(
        val running: Boolean = false,
        val connected: Boolean = false,
        val selfId: String = "",
        val nickname: String = "",
        val error: String = "",
        val handled: Int = 0,
        val lastLine: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** 每轮重新问后端要一次登录信息是多余的；拿到就记住，失败再问 */
    @Volatile
    private var cachedSelfId: String = ""

    @Volatile
    private var cachedNick: String = ""

    /** 每个会话看到哪了 */
    private val cursors = HashMap<String, Cursor>()
    private var cursorsLoaded = false

    /**
     * 自己发出去的消息 id，按会话分。
     *
     * 只为了认「引用回复」—— 群里有人长按我那句「引用」然后接话，这条消息
     * 里既没有 @ 也不带前缀，只看那两条规则就是漏回。存活期只在本次运行内：
     * 重启后旧消息本来也不会再处理一遍。
     */
    private val sentIds = HashMap<String, ArrayDeque<String>>()

    private fun rememberSent(key: String, id: String) {
        if (id.isBlank()) return
        val q = sentIds.getOrPut(key) { ArrayDeque() }
        q.addLast(id)
        while (q.size > 32) q.removeFirst()
    }

    private data class Cursor(val lastId: Long, val at: Long)

    // ---------------- 生命周期 ----------------

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, connected = false) }
    }

    /** 设置改动后让循环立刻重新读一遍配置（不等下一轮 sleep） */
    fun poke() {
        if (job?.isActive != true) start()
    }

    private suspend fun loop() {
        _state.update { it.copy(running = true, error = "") }
        try {
            while (currentCoroutineContext().isActive) {
                val cfg = settingsRepo.current().qq
                client.update(cfg)

                if (!cfg.enabled) {
                    _state.update { it.copy(connected = false, error = "", running = true) }
                    delay(2000)
                    continue
                }

                try {
                    tick(cfg)
                    _state.update { it.copy(connected = true, error = "") }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logx.w(TAG, "轮询失败", e)
                    _state.update {
                        it.copy(connected = false, error = e.message.orEmpty().take(160))
                    }
                }

                delay(cfg.pollSeconds.coerceIn(2, 60) * 1000L)
            }
        } finally {
            _state.update { it.copy(running = false, connected = false) }
        }
    }

    // ---------------- 一轮 ----------------

    private suspend fun tick(cfg: QqSettings) {
        if (!cursorsLoaded) {
            loadCursors()
            cursorsLoaded = true
        }

        val self = ensureSelf(cfg) ?: run {
            _state.update { it.copy(error = "拿不到登录账号，检查后端 token / 是否已登录") }
            return
        }

        val targets = buildList {
            if (cfg.privateEnabled && cfg.privatePeer.isNotBlank()) {
                add(Target(private = true, peer = cfg.privatePeer.trim()))
            }
            if (cfg.groupEnabled && cfg.groupPeer.isNotBlank()) {
                add(Target(private = false, peer = cfg.groupPeer.trim()))
            }
        }
        if (targets.isEmpty()) {
            _state.update { it.copy(error = "还没填私聊对象或群号") }
            return
        }

        for (t in targets) {
            pollTarget(t, cfg, self)
        }
    }

    private data class Target(val private: Boolean, val peer: String) {
        val key: String get() = (if (private) "p:" else "g:") + peer
    }

    private suspend fun ensureSelf(cfg: QqSettings): String? {
        val fixed = cfg.selfId.trim()
        if (fixed.isNotEmpty()) {
            cachedSelfId = fixed
            _state.update { it.copy(selfId = fixed) }
            if (cachedNick.isNotBlank()) _state.update { it.copy(nickname = cachedNick) }
            return fixed
        }
        if (cachedSelfId.isNotBlank()) return cachedSelfId

        return when (val r = client.loginInfo()) {
            is QqResult.Err -> {
                _state.update { it.copy(error = r.message) }
                null
            }
            is QqResult.Ok -> {
                cachedSelfId = r.value.userId
                cachedNick = r.value.nickname
                _state.update { it.copy(selfId = r.value.userId, nickname = r.value.nickname) }
                if (r.value.userId.isBlank()) null else r.value.userId
            }
        }
    }

    private suspend fun pollTarget(t: Target, cfg: QqSettings, self: String) {
        val res = if (t.private) {
            client.friendHistory(t.peer, HISTORY)
        } else {
            client.groupHistory(t.peer, HISTORY)
        }
        val list = when (res) {
            is QqResult.Err -> {
                _state.update { it.copy(error = res.message) }
                return
            }
            is QqResult.Ok -> res.value
        }
        if (list.isEmpty()) return

        val ordered = list.sortedWith(
            compareBy({ it.timeSec }, { it.id.toLongOrNull() ?: 0L })
        )
        val newest = ordered.last()
        val cursor = cursors[t.key]

        /*
         * 第一次看到这个会话：只记位置。
         *
         * 不这么做的话，用户一打开开关，历史接口里的二十条消息会被当成
         * 「刚收到的」全部回一遍 —— 群聊里就是二十条刷屏。
         */
        if (cursor == null) {
            cursors[t.key] = Cursor(newest.id.toLongOrNull() ?: 0L, newest.timeSec)
            saveCursors()
            return
        }

        val fresh = ordered.filter { m ->
            val id = m.id.toLongOrNull() ?: 0L
            m.timeSec > cursor.at || (m.timeSec == cursor.at && id > cursor.lastId)
        }
        if (fresh.isEmpty()) return

        for (m in fresh) {
            runCatching { handle(m, cfg, self) }
                .onFailure { Logx.w(TAG, "处理 QQ 消息失败", it) }
            // 逐条推进：中途被杀掉时，已回过的不会再来一遍
            cursors[t.key] = Cursor(m.id.toLongOrNull() ?: 0L, m.timeSec)
            saveCursors()
        }
    }

    // ---------------- 单条 ----------------

    private suspend fun handle(msg: QqMessage, cfg: QqSettings, self: String) {
        val key = (if (msg.isGroup) "g:" else "p:") + msg.peerId
        if (!QqTrigger.shouldReply(msg, cfg, self, sentIds[key].orEmpty().toSet())) return

        val text = QqTrigger.cleanInput(msg.text, cfg.prefixList())
            .let { clip(it, cfg.maxInputChars.coerceIn(200, 20_000)) }
        if (text.isBlank() && msg.imageUrls.isEmpty()) return

        val atts = if (cfg.sendImages) downloadImages(msg.imageUrls.take(MAX_IMAGES)) else emptyList()

        val conv = ensureConversation(msg)
        engine.open(conv.id)

        _state.update {
            it.copy(lastLine = "← ${msg.senderName}: ${text.take(40)}")
        }

        val reply = askEngine(text, conv.id, atts)
        if (reply.isBlank()) {
            _state.update { it.copy(error = "模型没有返回内容") }
            return
        }

        val out = QqOut.of(clip(reply.trim(), MAX_REPLY))
        val sent = if (msg.isGroup) {
            client.sendGroup(msg.peerId, out)
        } else {
            client.sendPrivate(msg.senderId, out)
        }
        when (sent) {
            is QqResult.Err -> _state.update { it.copy(error = sent.message) }
            is QqResult.Ok -> {
                rememberSent(key, sent.value)
                _state.update {
                    it.copy(
                        handled = it.handled + 1,
                        error = "",
                        lastLine = "→ ${reply.take(40)}",
                    )
                }
            }
        }
    }

    // ---------------- 引擎 ----------------

    private suspend fun askEngine(
        text: String,
        conversationId: String,
        attachments: List<Attachment>,
    ): String {
        val waiter = CompletableDeferred<ChatEngine.TurnDone>()
        /*
         * 先订阅再发送。
         *
         * `send()` 是 fire-and-forget，一轮结束时不会回头通知调用方；
         * 而 turnDone 是无重放的 SharedFlow —— 发完再订阅，那次事件已经丢了，
         * 只能等到超时。UNDISPATCHED 让协程立刻跑到 `first` 的挂起点，
         * 订阅在 send 之前就位。
         */
        val sub = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.turnDone.first { it.conversationId == conversationId }
                .let { waiter.complete(it) }
        }

        val started = runCatching { engine.send(text, attachments) }.getOrDefault(false)
        if (!started) {
            sub.cancel()
            return ""
        }
        val done = withTimeoutOrNull(TURN_TIMEOUT_MS) { waiter.await() }
        sub.cancel()
        return done?.text.orEmpty()
    }

    /**
     * QQ 会话：一个对端一个会话，id 固定。
     *
     * 用固定 id 而不是每次新建，是为了让用户在 App 的会话列表里看见
     * 「QQ · 张三」并继续追问 —— 桥接发出去的话和人在 App 里说的话
     * 落在同一段历史里，模型看到的上下文是一致的。
     */
    private suspend fun ensureConversation(msg: QqMessage): Conversation {
        val id = if (msg.isGroup) "qq-group-${msg.peerId}" else "qq-private-${msg.peerId}"
        store.load(id)?.let { return it.meta }

        val s = settingsRepo.current()
        val p = s.activeProvider
        val now = System.currentTimeMillis()
        val conv = Conversation(
            id = id,
            title = if (msg.isGroup) "QQ 群 ${msg.peerId}" else "QQ ${msg.senderName}",
            createdAt = now,
            updatedAt = now,
            providerId = p?.id.orEmpty(),
            model = p?.defaultModel.orEmpty(),
            presetId = p?.presetId ?: s.activePresetId,
            scene = com.dshwatch.domain.roleplay.Scenes.CODING,
            workDir = "qq/" + msg.peerId.takeLast(10),
        )
        runCatching { store.create(conv) }
        return conv
    }

    // ---------------- 图片 ----------------

    /**
     * 下载 QQ 图片到应用私有目录。
     *
     * 不用 `Attachment.path` 直接指向远端：后端返回的 url 常带时效签名，
     * 而且同一张图在后续轮次里还要能再看（用户追问「刚才那张图」）。
     */
    private suspend fun downloadImages(urls: List<String>): List<Attachment> {
        if (urls.isEmpty()) return emptyList()
        val dir = File(appContext.filesDir, "qq/media").apply { mkdirs() }
        val out = ArrayList<Attachment>()

        for (u in urls) {
            if (!u.startsWith("http")) continue
            val name = sha1(u).take(16) + guessExt(u)
            val f = File(dir, name)
            if (!f.exists() || f.length() == 0L) {
                val ok = runCatching {
                    val req = okhttp3.Request.Builder().url(u).build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val body = resp.body?.bytes() ?: return@use false
                        if (body.isEmpty() || body.size > MAX_IMAGE_BYTES) return@use false
                        f.writeBytes(body)
                        true
                    }
                }.getOrElse { false }
                if (!ok) continue
            }
            out += Attachment(
                id = name,
                name = name,
                mime = guessMime(name),
                sizeBytes = f.length(),
                kind = AttachmentKinds.IMAGE,
                path = f.absolutePath,
            )
        }
        return out
    }

    private val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun guessExt(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".png") -> ".png"
            clean.endsWith(".gif") -> ".gif"
            clean.endsWith(".webp") -> ".webp"
            else -> ".jpg"
        }
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".png") -> "image/png"
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".webp") -> "image/webp"
        else -> "image/jpeg"
    }

    // ---------------- 落盘 ----------------

    private fun cursorFile() = File(appContext.filesDir, "qq/cursor.json")

    private fun loadCursors() {
        val f = cursorFile()
        if (!f.exists()) return
        runCatching {
            val root = json.parseToJsonElement(f.readText()).jsonObject
            root.forEach { (k, v) ->
                val o = v.jsonObject
                val id = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.toLongOrNull() ?: 0L
                val at = (o["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.toLongOrNull() ?: 0L
                cursors[k] = Cursor(id, at)
            }
        }.onFailure { Logx.w(TAG, "读 cursor 失败，按首次处理", it) }
    }

    private fun saveCursors() {
        runCatching {
            val f = cursorFile()
            f.parentFile?.mkdirs()
            val root = buildJsonObject {
                cursors.forEach { (k, c) ->
                    put(
                        k,
                        buildJsonObject {
                            put("id", c.lastId)
                            put("at", c.at)
                        },
                    )
                }
            }
            f.writeText(json.encodeToString(JsonObject.serializer(), root))
        }.onFailure { Logx.w(TAG, "写 cursor 失败", it) }
    }

    // ---------------- 小工具 ----------------

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "…"

    private fun sha1(s: String): String = try {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        d.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        s.hashCode().toUInt().toString(16)
    }

    companion object {
        private const val TAG = "QqBridge"

        /**
         * 注入到 QQ 会话系统提示末尾的通道约定。
         *
         * 写死的理由和「Android 上没有 Markdown」一样：模型默认按网页排版说话，
         * 星号、表格、代码围栏发到 QQ 里全是符号噪音。这一段是**唯一**能让它
         * 换一种说话方式的地方 —— 放到设置里让用户填，等于把这件事交给运气。
         */
        val QQ_PROMPT: String = """
            【当前通道：QQ】
            你现在是在 QQ 上跟人聊天，不是在做代码任务。
            - 回复尽量控制在 200 字以内；对方明确要求长文时再展开
            - 不要用 Markdown：标题、表格、代码围栏、加粗星号在 QQ 里都是噪音，用纯文本短句
            - 不要复述对方的问题，直接说结论
            - 不要写「我这就去查」「稍等一下」这类只在界面里成立的过渡句，你只有一次发言机会
            - 对方发来的图片已经看过；如果没看清就说没看清，不要猜
        """.trimIndent()

        /** 每次拉多少条。20 条足够覆盖一个轮询周期内的消息量 */
        const val HISTORY = 20

        /** 一条 QQ 消息最多带几张图给模型 */
        const val MAX_IMAGES = 3

        /** 单张图上限，超过就跳过（不占内存也不烧 token） */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        /** 一条回复最多多少字，超出截断（QQ 单条消息有长度限制） */
        const val MAX_REPLY = 3_000

        /** 一轮对话最多等多久 */
        const val TURN_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
