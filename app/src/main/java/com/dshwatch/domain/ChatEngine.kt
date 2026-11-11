package com.dshwatch.domain

import com.dshwatch.core.Ids
import com.dshwatch.core.Logx
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.store.StoredConversation
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.domain.agent.AgentGuide
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.ApprovalPolicy
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.agent.CanvasGuide
import com.dshwatch.domain.agent.ProgressTracker
import com.dshwatch.domain.plugin.PluginRegistry
import com.dshwatch.domain.plugin.PromptContext
import com.dshwatch.domain.plugin.ToolResultInput
import com.dshwatch.domain.plugin.TurnContext
import com.dshwatch.domain.agent.ToolArgs
import com.dshwatch.domain.agent.ToolRegistry
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolResultCompactor
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.MediaKind
import com.dshwatch.domain.model.MediaModels
import com.dshwatch.domain.model.MediaRef
import com.dshwatch.domain.model.MediaStatus
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.model.TokenUsage
import com.dshwatch.domain.model.ToolCallRecord
import com.dshwatch.domain.model.ToolStatus
import com.dshwatch.net.backend.BackendRegistry
import com.dshwatch.net.backend.ChatEvent
import com.dshwatch.net.backend.ChatRequest
import com.dshwatch.net.backend.DshError
import com.dshwatch.domain.agent.PendingQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 流式过程中单独存放「正在生成的那条」，避免每来一个 token 就重建整个消息列表 */
data class LiveDelta(
    val messageId: String = "",
    val text: String = "",
    val reasoning: String = "",
    /** Agent 模式下的当前步数，0 表示普通对话 */
    val step: Int = 0,
) {
    val isActive: Boolean get() = messageId.isNotEmpty()
}

/** 正在等待用户审批的工具调用 */
data class PendingApproval(
    val callId: String,
    val toolName: String,
    val summary: String,
    val arguments: String,
    val safety: ToolSafety,
    /**
     * 是否支持「本会话允许」。
     *
     * 只有 shell 是 true：它每次都要问，但同一个任务里连着跑几条命令时，
     * 每条都点一次太折磨人。js 与云端仍走「总是允许 / 允许一次」。
     */
    val sessionScoped: Boolean = false,

    /** 模型自己写的用途说明（`adb_shell` 的 purpose）。审批弹窗第一行就是它 */
    val purpose: String = "",

    /** 要执行的命令原文。与 purpose 对照着看，才知道它有没有说一套做一套 */
    val command: String = "",

    /**
     * 危险命令：红色警告 + 两次确认，且不提供「始终允许」。
     *
     * 判定在本地做（[com.dshwatch.domain.shell.ShellRisk]），**不采信模型自评** ——
     * 让模型给自己的命令打分，等于让它自己决定要不要被拦。
     */
    val danger: Boolean = false,

    /** 授权类别名（安装应用 / 读取日志 / …），用于「始终允许这类」的按钮文案 */
    val categoryLabel: String = "",
)

enum class ApprovalDecision {
    ALLOW_ONCE,

    /** 写入设置，永久放行（仅非 sessionScoped 的工具） */
    ALLOW_ALWAYS,

    /** 本会话内放行（仅 shell） */
    ALLOW_SESSION,

    DENY,
}

/**
 * 对话引擎（应用级单例）。
 *
 * v1.1.0 起支持 **Agent 多步循环**：
 *   模型返回 tool_calls → 本地执行工具 → 结果回灌 → 再问模型，
 *   直到模型给出最终回答，或达到 [AppSettings.maxAgentSteps] 步上限。
 * 每一步都是一条独立的助手消息，工具卡片挂在对应消息上，便于回看「它到底做了什么」。
 */
class ChatEngine(
    private val appScope: CoroutineScope,
    private val settingsRepo: SettingsSource,
    private val keyStore: CredentialSource,
    private val store: ConversationStore,
    private val usageRepo: UsageRepository,
    private val backends: BackendRegistry,
    private val perfProvider: () -> PerfProfile,
    private val toolRegistry: () -> ToolRegistry,
    /** 工作空间根目录的绝对路径；只用于在系统提示里告诉模型「不要写绝对路径」 */
    private val workspaceRoot: () -> String = { "" },
    /** ask_user 工具与界面之间的通道 */
    private val promptChannel: com.dshwatch.domain.agent.UserPromptChannel? = null,
    /**
     * 角色扮演 / 写作场景下取卡的通道。
     *
     * 用 lambda 而不是直接持有 store：ChatEngine 在单测里被大量构造，
     * 而单测既不关心卡库、也不该为了构造引擎去准备一套目录。
     * 返回 null 时按「没有卡」处理，退化成普通对话。
     */
    private val roleCard: (suspend (String) -> com.dshwatch.domain.roleplay.RoleCard?)? = null,
    /** 长期记忆文档（空实现即可，默认不读不写） */
    private val memoryReader: (suspend (String) -> String)? = null,
    private val json: Json,
    /**
     * 图片 / 视频生成通道。
     *
     * 允许为 null：单测构造引擎时不关心媒体，也不该为此准备一个网络客户端。
     * 为 null 时选中出图模型会得到一句明确的提示，而不是崩溃。
     */
    private val mediaApi: com.dshwatch.net.media.MediaApi? = null,
    /** 媒体落盘根目录（通常是 filesDir/media） */
    private val mediaRoot: () -> java.io.File = { java.io.File("") },
) {

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /**
     * 打开会话时被 [PerfProfile.historyLoadLimit] 裁掉的早期消息。
     *
     * [_messages] 只是「渲染窗口」，不是完整的会话真相。落盘时必须用
     * `droppedPrefix + _messages`，否则老会话打开一次、回一句话，磁盘上就只剩最近
     * limit 条 —— 前面的历史被静默删掉，用户完全不知道。
     *
     * 内存紧张的设备上仍然只渲染窗口内的消息，但数据一条都不丢。
     */
    private var droppedPrefix: List<ChatMessage> = emptyList()

    /**
     * 长期记忆压缩后，发给模型的上下文从这里开始。
     *
     * 与 [droppedPrefix] 不是一回事：那个是「界面渲染窗口」的裁剪（数据还在磁盘上），
     * 这个是「发给模型的历史」的裁剪（数据还在会话文件里、用户照样看得见，
     * 只是不再每轮重复发给模型，改由记忆文档承载）。
     *
     * 持久化在 `Conversation.tavernTurn`，所以关掉 App 再进来不会失效 ——
     * 否则每次冷启动都会把已经压过的几万字又发一遍，记忆等于白做。
     */
    private var contextStart: Int = 0

    /** 落盘入口：一律写完整历史，而不是被裁剪过的渲染窗口 */
    private suspend fun persist(conv: Conversation, ui: List<ChatMessage> = _messages.value) {
        val full = if (droppedPrefix.isEmpty()) ui else droppedPrefix + ui
        store.save(StoredConversation(conv, full))
    }

    // ---------------- 长期记忆（角色扮演 / 写作） ----------------

    /** 完整消息（渲染窗口 + 被裁剪的前缀），供压缩器取原文 */
    fun snapshotMessages(): List<ChatMessage> =
        if (droppedPrefix.isEmpty()) _messages.value else droppedPrefix + _messages.value

    /** 当前发给模型的历史起点（下标，相对于 [snapshotMessages]） */
    fun contextStart(): Int = contextStart.coerceIn(0, snapshotMessages().size)

    /**
     * 把上下文切到「当前末尾」，并落盘。
     *
     * 只在压缩成功后调用。切完之后这一轮之前的内容不再发给模型，
     * 记忆文档负责在系统提示里补上它。
     */
    suspend fun markContextCut() {
        val size = snapshotMessages().size
        contextStart = size
        val conv = _conversation.value ?: return
        val updated = conv.copy(tavernTurn = size)
        _conversation.value = updated
        runCatching { persist(updated) }
            .onFailure { Logx.e(TAG, "上下文压缩落盘失败", it) }
    }

    /**
     * 一次性摘要请求。
     *
     * 复用同一条后端通道，但**不带工具、不落盘、不进历史** —— 它就是一次
     * 普通的 chat 请求，拿回纯文本。单开一条只会让取消/超时逻辑多一份。
     */
    suspend fun probe(req: ResponseProbe): String {
        val settings = settingsRepo.current()
        val provider = settings.activeProvider ?: return ""
        val model = provider.defaultModel.ifBlank { provider.models.firstOrNull().orEmpty() }
        if (model.isBlank()) return ""
        val request = ChatRequest(
            provider = provider,
            baseUrl = provider.normalizedBaseUrl(),
            apiKey = keyStore.getApiKey(provider.id).orEmpty(),
            serverToken = keyStore.getServerToken(provider.id).orEmpty(),
            model = model,
            systemPrompt = req.systemPrompt,
            history = listOf(ChatTurn(role = "user", content = req.userText)),
            temperature = req.temperature,
            topP = provider.topP,
            maxTokens = req.maxTokens,
            stream = false,
            reasoningEffort = com.dshwatch.domain.model.ReasoningEffort.DEFAULT,
            usageInStream = false,
            tools = emptyList(),
        )
        val sb = StringBuilder()
        backends.of(provider.type).streamChat(request).collect { ev ->
            if (ev is ChatEvent.Text) sb.append(ev.delta)
        }
        return sb.toString()
    }

    private val _live = MutableStateFlow(LiveDelta())
    val live: StateFlow<LiveDelta> = _live.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _banner = MutableStateFlow<Banner?>(null)
    val banner: StateFlow<Banner?> = _banner.asStateFlow()

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    /** agent 正在等用户回答的问题 */
    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val question: StateFlow<PendingQuestion?> = _question.asStateFlow()

    private var job: Job? = null
    private val ioMutex = Mutex()

    @Volatile
    private var approvalDeferred: CompletableDeferred<ApprovalDecision>? = null

    @Volatile
    private var questionDeferred: CompletableDeferred<String?>? = null

    /**
     * 本会话内已放行的 shell 执行。
     *
     * 只在内存里，换会话/重启即失效 —— 用户点的是「本会话允许」，不是「永久放行」。
     * 永久放行对 shell 来说风险不对等：一条 `rm -rf` 就能把工作空间清空。
     */
    private val sessionApproved = mutableSetOf<String>()

    init {
        // 工具集先于引擎构造（AppContainer 里的顺序相反），所以这里做后置注册
        promptChannel?.handler = { q -> awaitUserAnswer(q) }
    }

    /** 生成开始/结束的回调，供前台服务启停 */
    var onStreamingChanged: ((Boolean) -> Unit)? = null

    /**
     * 每次真正开始跑一个工具时回调一次（参数是给用户看的一句话）。
     *
     * 用途只有一个：让前台服务的通知写上「正在读字幕」而不是一直「正在生成」。
     * 手表上用户不会盯着屏幕等，通知栏是他唯一能确认「还在动」的地方。
     */
    var onToolStarted: ((String) -> Unit)? = null

    // ---------------- 会话 ----------------

    suspend fun open(conversationId: String) {
        /*
         * 正在这个会话里生成时被再次「打开」，不能把生成掐掉。
         *
         * 真实场景：用户发完话退回列表，过一会儿又想接着看，点了「继续写」。
         * 旧实现在这里无条件 cancelRunning —— 请求被取消，那段回复永远写不出来，
         * 磁盘上只剩用户自己那条消息，看起来就像「消息只发到了本地」。
         * 引擎里的会话本来就是它，直接返回即可（界面会订阅到正在流式的内容）。
         */
        if (_conversation.value?.id == conversationId && job?.isActive == true) return

        cancelRunning(silent = true)
        val sc = store.load(conversationId) ?: run {
            Logx.w(TAG, "会话不存在：$conversationId")
            return
        }
        _live.value = LiveDelta()
        _banner.value = null
        _conversation.value = sc.meta
        val limit = perfProvider().historyLoadLimit
        if (sc.messages.size > limit) {
            droppedPrefix = sc.messages.dropLast(limit)
            _messages.value = sc.messages.takeLast(limit)
        } else {
            droppedPrefix = emptyList()
            _messages.value = sc.messages
        }
        // 记忆压缩点从会话里恢复：冷启动后不能把压过的历史再发一遍
        contextStart = sc.meta.tavernTurn.coerceIn(0, sc.messages.size)
    }

    /** 记忆压缩点（相对完整消息的下标），给界面显示"从第几条之后开始记" */
    fun contextCut(): Int = contextStart

    /** 供 UI 使用：先停掉正在跑的生成，再开一个新会话 */
    suspend fun newConversation(
        settings: AppSettings,
        scene: String = com.dshwatch.domain.roleplay.Scenes.CODING,
    ): Conversation {
        cancelRunning(silent = true)
        return startNew(settings, scene = scene)
    }

    /**
     * 创建一个新会话。
     *
     * ⚠️ 这里**绝对不能**再调用 stop()/cancelRunning()。
     * [runSend] 在「当前没有会话」时会调用本方法，而此刻正在运行的 job 就是 runSend 自己，
     * 一旦取消就会把自己杀掉——表现为「全新安装后第一条消息点发送毫无反应」。
     * 这个 bug 真实存在过，删除前请先确认 [runSend] 的调用链。
     */
    /**
     * 创建一个新会话。
     *
     * [scene] / [roleCardId]：角色扮演与写作场景要把「这张卡」钉在会话上，
     * 之后每次发消息都靠它重建系统提示。编程场景两者都留空。
     * 用带默认值的追加参数，是为了不惊动现有的 15 处调用点与单测。
     */
    suspend fun startNew(
        settings: AppSettings,
        scene: String = com.dshwatch.domain.roleplay.Scenes.CODING,
        roleCardId: String = "",
        title: String = "",
    ): Conversation {
        val p = settings.activeProvider
        val conv = ConversationStore.newConversation(
            providerId = p?.id.orEmpty(),
            model = p?.defaultModel.orEmpty(),
            presetId = p?.presetId ?: settings.activePresetId,
        ).copy(
            scene = com.dshwatch.domain.roleplay.Scenes.of(scene),
            roleCardId = roleCardId,
            title = title.ifBlank { "新对话" },
        )
        // ⚠️ 这里**不落盘**。
        //
        // 旧实现每点一次「新建对话」就 store.create 一条，结果是：用户连着点几下，
        // 会话列表里就堆起一串同名、点开什么都没有的「新对话」——
        // 列表不再是「聊过什么」，而变成了「点过几次加号」。
        //
        // 真正落盘的时机是**第一条消息写下来的时候**（见 runSend 的持久化点），
        // 一个没说过话的会话本来也不该在列表里占位置。
        _conversation.value = conv
        _messages.value = emptyList()
        droppedPrefix = emptyList()
        // 新会话从头开始算上下文，否则上一轮的记忆压缩点会带着走
        contextStart = 0
        _live.value = LiveDelta()
        _banner.value = null
        // 新会话不继承上一个会话的 shell 放行
        sessionApproved.clear()
        return conv
    }

    suspend fun deleteConversation(id: String) {
        if (_conversation.value?.id == id) {
            _conversation.value = null
            _messages.value = emptyList()
            droppedPrefix = emptyList()
            _live.value = LiveDelta()
        }
        store.delete(id)
    }

    /** 只改本会话使用的模型，不影响供应商默认值 */
    suspend fun overrideModel(model: String) {
        val conv = _conversation.value ?: return
        val updated = conv.copy(model = model, updatedAt = System.currentTimeMillis())
        _conversation.value = updated
        persist(updated)
    }

    suspend fun deleteMessage(messageId: String) {
        if (job?.isActive == true) return
        val conv = _conversation.value ?: return
        val list = _messages.value.filterNot { it.id == messageId }
        if (list.size == _messages.value.size) return
        _messages.value = list
        persist(conv, list)
    }

    suspend fun refreshConversation() {
        val id = _conversation.value?.id ?: return
        store.load(id)?.let { _conversation.value = it.meta }
    }

    // ---------------- 发送 ----------------

    /**
     * 发一条消息。
     *
     * @return 是否真的进入了发送队列。false 只有两种情况：内容为空、或上一轮
     *   还在生成。**调用方要据此决定「要不要关掉输入页」** —— 以前它返回 Unit，
     *   输入页无论如何都会关掉，于是用户在生成期间打的字被静默吞掉：
     *   界面看起来像发出去了，会话里却没有这条消息。
     */
    fun send(text: String, attachments: List<com.dshwatch.domain.model.Attachment> = emptyList()): Boolean {
        val trimmed = text.trim()
        // 只传文件不写字是合法用法（「这份合同有什么风险」），所以附件也算内容
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        if (job?.isActive == true) {
            _banner.value = Banner("正在生成中，先停止或等待完成")
            return false
        }
        job = appScope.launch {
            guardedRun {
                /*
                 * 出图 / 出视频模型走另一条链。
                 *
                 * 判定放在这里而不是界面上：三个入口（输入页、快捷选项、重发）
                 * 都汇聚到 send()，判一次就都对了；让每个入口各自判，
                 * 迟早漏一个 —— 漏掉的那个会对着 image 模型发 /chat/completions，
                 * 拿回一句「我是 image 模型，请用 /v1/images/generations」。
                 */
                val kind = mediaKindOfActiveModel()
                if (kind != null) {
                    if (attachments.isNotEmpty()) {
                        _banner.value = Banner("当前是出图/出视频模型，附件不会参与生成")
                    }
                    runMediaSend(trimmed, kind, reuseUserMessage = false)
                } else {
                    runSend(trimmed, regenerateOf = "", attachments = attachments)
                }
            }
        }
        return true
    }

    /** 当前选中的模型若是出图 / 出视频模型，返回类型；文本模型返回 null */
    private suspend fun mediaKindOfActiveModel(): MediaKind? {
        val settings = settingsRepo.current()
        val provider = settings.activeProvider ?: return null
        if (provider.type != ProviderType.OPENAI_COMPAT) return null
        val model = provider.defaultModel.ifBlank { provider.models.firstOrNull().orEmpty() }
        return MediaModels.kindOf(model)
    }

    /** 重新生成某条助手消息（丢弃它及其之后的消息，用它的父消息再跑一遍） */
    fun regenerate(assistantMessageId: String) {
        if (job?.isActive == true) {
            _banner.value = Banner("正在生成中")
            return
        }
        val list = _messages.value
        val index = list.indexOfFirst { it.id == assistantMessageId }
        if (index < 0) return
        val target = list[index]
        val parent = target.parentId
        val kept = list.take(index)
        val media = target.media.firstOrNull()
        job = appScope.launch {
            guardedRun {
                if (media != null && media.prompt.isNotBlank()) {
                    // 媒体消息没有「父消息」概念：用户那条提示还在 kept 里，直接复用，
                    // 否则会多出一条一模一样的用户消息
                    _messages.value = kept
                    runMediaSend(media.prompt, media.kind, reuseUserMessage = true)
                } else {
                    runSend(regenerateOf = parent, existingHistory = kept)
                }
            }
        }
    }

    /** 用户主动点「停止」 */
    fun stop() = cancelRunning(silent = false)

    fun dismissBanner() {
        _banner.value = null
    }

    /** 界面上的审批按钮回调 */
    fun resolveApproval(decision: ApprovalDecision) {
        approvalDeferred?.complete(decision)
    }

    /**
     * 取消当前生成。
     * 只做「取消 + 把已经流出来的内容落盘」，不再调用任何会二次触发取消的公开方法，
     * 避免出现 runSend → startNew → stop → cancel 自己的循环。
     */
    private fun cancelRunning(silent: Boolean) {
        val j = job
        val wasRunning = j != null && j.isActive
        job = null
        // 如果正在等审批，先放行一个「拒绝」，让挂起的协程能正常退出
        approvalDeferred?.complete(ApprovalDecision.DENY)
        approvalDeferred = null
        _approval.value = null
        // 正在等用户回答 ask_user 的情况同理：不唤醒的话协程会一直挂着
        questionDeferred?.complete(null)
        questionDeferred = null
        _question.value = null
        if (wasRunning) {
            backends.cancelAll()
            j?.cancel()
        }

        val live = _live.value
        val convId = _conversation.value?.id
        if (live.messageId.isNotEmpty() && convId != null) {
            appScope.launch {
                finalize(
                    conversationId = convId,
                    messageId = live.messageId,
                    text = live.text,
                    reasoning = live.reasoning,
                    status = MessageStatus.STOPPED,
                    usage = null,
                    error = "",
                )
            }
        }

        setStreaming(false)
        _live.value = LiveDelta()
        if (!silent && wasRunning) _banner.value = Banner("已停止生成")
    }

    /**
     * `ask_user` 的落地实现：把问题交给界面，然后挂起等待。
     *
     * 为什么要超时：用户完全可能看完问题就去干别的了。任务不能永远挂着占住引擎
     * （那样「停止」按钮也点不动），所以到点就返回 null，由工具转成
     * 「用户没有回答」，让模型自己决定是继续猜还是收尾。
     */
    private suspend fun awaitUserAnswer(q: PendingQuestion): String? {
        val deferred = CompletableDeferred<String?>()
        questionDeferred = deferred
        _question.value = q
        return try {
            withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
        } finally {
            questionDeferred = null
            _question.value = null
        }
    }

    /** 用户在弹窗里提交了回答 */
    fun answerQuestion(text: String) {
        questionDeferred?.complete(text.trim())
    }

    /** 用户选择「跳过这题」：给一个空回答，让模型自己挑个默认值继续 */
    fun skipQuestion() {
        questionDeferred?.complete("")
    }

    /**
     * 兜底：runSend 里任何没被捕获的异常都转成界面提示。
     * 没有这层的话，协程异常会被 SupervisorJob 吞掉，用户看到的就是「点了发送没反应」。
     */
    private suspend fun guardedRun(block: suspend () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Logx.e(TAG, "发送流程异常", t)
            setStreaming(false)
            _live.value = LiveDelta()
            _banner.value = Banner("发送失败：${t.javaClass.simpleName}\n${t.message.orEmpty().take(200)}")
        }
    }

    // ---------------- 媒体生成（图 / 视频） ----------------

    /**
     * 出图 / 出视频。
     *
     * 和 [runSend] 的差别只在「怎么拿到结果」：那边是一个字节流，这边是一次 HTTP 返回
     * 或一个异步任务。**消息模型完全一样** —— 两条消息（用户 + 助手），助手消息上挂
     * [MediaRef]。这样列表渲染、落盘、删除、重发都不需要为媒体写第二套逻辑。
     *
     * @param reuseUserMessage 重新生成时为 true：用户那条提示已经在列表里，不再追加
     */
    private suspend fun runMediaSend(
        text: String,
        kind: MediaKind,
        reuseUserMessage: Boolean,
    ) {
        val settings = settingsRepo.current()
        val provider = settings.activeProvider
        if (provider == null) {
            _banner.value = Banner("还没有配置供应商，请到「供应商」里新增一个")
            return
        }
        val model = provider.defaultModel.ifBlank { provider.models.firstOrNull().orEmpty() }
        if (model.isBlank()) {
            _banner.value = Banner("供应商「${provider.name}」还没有选择模型")
            return
        }
        val api = mediaApi
        if (api == null) {
            _banner.value = Banner("当前版本没有可用的媒体通道")
            return
        }
        val key = keyStore.getApiKey(provider.id).orEmpty()
        if (key.isBlank()) {
            _banner.value = Banner("「${provider.name}」缺少 API Key，请到供应商设置里填写")
            return
        }

        var conv = _conversation.value ?: startNew(settings)
        val dir = java.io.File(mediaRoot(), conv.id).apply { mkdirs() }
        val parentId = if (reuseUserMessage) _messages.value.lastOrNull()?.id.orEmpty() else ""

        val userMsg = if (reuseUserMessage) {
            null
        } else {
            ConversationStore.newMessage(
                role = Role.USER,
                content = text,
                model = model,
                providerId = provider.id,
            )
        }

        val mediaId = Ids.new()
        val placeholder = MediaRef(
            id = mediaId,
            kind = kind,
            prompt = text,
            model = model,
            status = MediaStatus.PENDING,
        )
        val assistant = ConversationStore.newMessage(
            role = Role.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING,
            model = model,
            providerId = provider.id,
            parentId = userMsg?.id ?: parentId,
            media = listOf(placeholder),
        )

        if (conv.title.isBlank() || conv.title == "新对话") conv = conv.copy(title = autoTitle(text))
        conv = conv.copy(
            providerId = provider.id,
            model = model,
            updatedAt = System.currentTimeMillis(),
        )
        _conversation.value = conv
        _messages.value = _messages.value + listOfNotNull(userMsg) + assistant
        _banner.value = null
        runCatching { persist(conv) }
        setStreaming(true)

        val baseUrl = provider.normalizedBaseUrl()
        try {
            val remote = when (kind) {
                MediaKind.IMAGE -> api.generateImage(
                    baseUrl = baseUrl,
                    apiKey = key,
                    model = model,
                    prompt = text,
                    destDir = dir,
                    fileName = "$mediaId.png",
                )

                MediaKind.VIDEO -> api.generateVideo(
                    baseUrl = baseUrl,
                    apiKey = key,
                    model = model,
                    prompt = text,
                ) { pct, _ ->
                    patchMedia(assistant.id, mediaId) {
                        it.copy(
                            status = MediaStatus.RUNNING,
                            progress = pct,
                        )
                    }
                }
            }

            // 远端地址可能是签名链接（几小时后失效），必须先落盘
            val file = if (remote.startsWith("http")) {
                val target = java.io.File(dir, mediaId + guessExt(kind, remote))
                api.download(remote, target, key)
                target
            } else {
                java.io.File(remote)
            }
            val info = com.dshwatch.net.media.MediaProbe.of(file)

            patchMedia(assistant.id, mediaId) {
                it.copy(
                    url = remote.takeIf { u -> u.startsWith("http") }.orEmpty(),
                    path = file.absolutePath,
                    mime = info.mime.ifBlank { if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg" },
                    width = info.width,
                    height = info.height,
                    durationMs = info.durationMs,
                    sizeBytes = file.length(),
                    status = MediaStatus.DONE,
                    progress = 100,
                    error = "",
                )
            }
            patchMessage(assistant.id) { it.copy(status = MessageStatus.DONE) }
        } catch (c: CancellationException) {
            patchMedia(assistant.id, mediaId) { it.copy(status = MediaStatus.CANCELED) }
            patchMessage(assistant.id) { it.copy(status = MessageStatus.STOPPED) }
            throw c
        } catch (t: Throwable) {
            val msg = (t as? DshError)?.describeForUser()
                ?: "${t.javaClass.simpleName}: ${t.message.orEmpty().take(200)}"
            patchMedia(assistant.id, mediaId) {
                it.copy(status = MediaStatus.FAILED, error = msg)
            }
            patchMessage(assistant.id) {
                it.copy(status = MessageStatus.ERROR, error = msg)
            }
            _banner.value = Banner(msg)
            Logx.w(TAG, "媒体生成失败 kind=$kind model=$model", t)
        } finally {
            setStreaming(false)
            runCatching {
                _conversation.value?.let { persist(it) }
            }
        }
    }

    /** 只改某条消息里的某个媒体（视频轮询每 1.5s 会调一次） */
    private fun patchMedia(messageId: String, mediaId: String, block: (MediaRef) -> MediaRef) {
        patchMessage(messageId) { m ->
            m.copy(media = m.media.map { if (it.id == mediaId) block(it) else it })
        }
    }

    private fun patchMessage(messageId: String, block: (ChatMessage) -> ChatMessage) {
        val cur = _messages.value
        val idx = cur.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        _messages.value = cur.toMutableList().also { it[idx] = block(it[idx]) }
    }

    private fun guessExt(kind: MediaKind, url: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "")
        if (fromUrl.length in 2..5 && fromUrl.all { it.isLetterOrDigit() }) return ".$fromUrl"
        return if (kind == MediaKind.VIDEO) ".mp4" else ".png"
    }

    // ---------------- Agent 主循环 ----------------

    private suspend fun runSend(
        userText: String = "",
        regenerateOf: String,
        existingHistory: List<ChatMessage>? = null,
        attachments: List<com.dshwatch.domain.model.Attachment> = emptyList(),
    ) {
        val settings = settingsRepo.current()
        val provider = settings.activeProvider
        if (provider == null) {
            _banner.value = Banner("还没有配置供应商，请到「供应商」里新增一个")
            return
        }

        val model = provider.defaultModel.ifBlank { provider.models.firstOrNull().orEmpty() }
        if (model.isBlank()) {
            _banner.value = Banner("供应商「${provider.name}」还没有选择模型，请到「供应商」里选一个")
            return
        }

        var conv = _conversation.value ?: startNew(settings)
        val perf = perfProvider()

        val history = existingHistory ?: _messages.value

        /*
         * 场景：编程走完整 Agent，酒馆 / 写作走纯对话。
         *
         * 用 conv.scene 而不是 settings 里的当前场景 —— 会话的场景是**创建时定死的**，
         * 用户在别的会话里切到编程，不应该让正在进行的角色扮演突然长出一堆工具。
         * 场景字段为空的（老会话）一律按编程处理，行为与升级前完全一致。
         */
        val scene = com.dshwatch.domain.roleplay.Scenes.of(conv.scene)
        val isRoleplay = !com.dshwatch.domain.roleplay.Scenes.usesAgent(scene)
        val card = if (isRoleplay) {
            conv.roleCardId.takeIf { it.isNotBlank() }?.let { id ->
                runCatching { roleCard?.invoke(id) }.getOrNull()
            }
        } else {
            null
        }

        // 1) 追加用户消息（重新生成时不追加）
        val newUserMessage: ChatMessage?
        val workingList: List<ChatMessage>
        if (regenerateOf.isEmpty()) {
            newUserMessage = ConversationStore.newMessage(
                role = Role.USER,
                content = userText,
                model = model,
                providerId = provider.id,
                attachments = attachments,
            )
            workingList = history + newUserMessage
        } else {
            newUserMessage = null
            workingList = history
        }

        // 2) 标题（用首条用户消息，不额外消耗 token）
        if (conv.title.isBlank() || conv.title == "新对话") {
            val seed = workingList.firstOrNull { it.role == Role.USER }?.content.orEmpty()
            if (seed.isNotBlank()) conv = conv.copy(title = autoTitle(seed))
        }
        conv = conv.copy(
            providerId = provider.id,
            model = model,
            updatedAt = System.currentTimeMillis(),
        )
        _conversation.value = conv
        _messages.value = workingList
        _live.value = LiveDelta()
        _banner.value = null

        // 持久化点：走到这里说明这是一次真实对话，会话该出现在列表里了。
        // 新建的会话就是靠这一句落盘的（startNew 刻意不落盘）。
        // 顺带把用户消息也立刻写进去 —— 以前要等 finalize，中途被杀就丢了。
        runCatching { persist(conv, workingList) }
            .onFailure { Logx.e(TAG, "会话落盘失败", it) }

        // 3) Agent 开关
        val registry = toolRegistry()
        val agentOn = !isRoleplay &&
            settings.agentMode != AgentMode.OFF &&
            provider.type == ProviderType.OPENAI_COMPAT &&
            registry.tools.isNotEmpty()
        /*
         * 工具表全量下发。
         *
         * 1.20 试过「常驻十几个 + 其余进目录、模型先 tool_search」，固定成本确实
         * 从一万八降到五千，但真机上模型会卡在这一步：先搜「构建」，再搜别的，
         * 或者搜到了却记不住「下一轮才生效」。省下的是 token，赔进去的是一整个
         * 任务。可用性优先，机制整体移除。
         */
        // 不再用 settings.maxAgentSteps 限制轮数：它和任务复杂度无关 ——
        // 读 8 个文件改 5 个文件的重构，20 步根本不够；而模型卡住时，
        // 20 步全是重复调同一个工具，白烧二十轮 token。
        // 真正会失控的是"原地打转"，交给 ProgressTracker 判断；这里的上限只防病态情况。
        val maxSteps = if (agentOn) HARD_STEP_CEILING else 1
        val progressTracker = if (agentOn) ProgressTracker() else null

        // 插件：常驻段在这里算一次，路由段每轮现算（风神只在首轮插手）
        val plugins = PluginRegistry.from(settings)
        /*
         * 系统提示的三种来源，从上到下优先级递减：
         *  1. 角色扮演 / 写作：由卡面拼出来，**每轮重算**（改了卡立刻生效），
         *     长期记忆与命中的世界书条目也在这里一并注入；
         *  2. 编程：走原有的 buildSystemPrompt（预设 + 工具使用约定）；
         *  3. 插件追加段：只有编程场景才生效 —— 风神那类插件是给 Agent 用的，
         *     塞进角色对话只会污染人设。
         */
        val baseSystemPrompt = if (isRoleplay && card != null) {
            val memory = conv.roleCardId.takeIf { it.isNotBlank() }?.let { id ->
                runCatching { memoryReader?.invoke(id) }.getOrNull().orEmpty()
            }.orEmpty()
            val recent = workingList.takeLast(6).joinToString("\n") { it.content }
            if (scene == com.dshwatch.domain.roleplay.Scenes.WRITING) {
                com.dshwatch.domain.roleplay.TavernPrompt.buildWriting(
                    card, memory, recent, opening = card.greeting,
                )
            } else {
                com.dshwatch.domain.roleplay.TavernPrompt.build(card, memory, recent)
            }
        } else {
            buildSystemPrompt(provider, settings, conv.workDir)
        }
        val pluginPrompt = if (isRoleplay) {
            ""
        } else {
            runCatching {
                plugins.promptSuffix(PromptContext(settings = settings, workDir = conv.workDir))
            }.getOrDefault("")
        }
        val systemPrompt = if (pluginPrompt.isBlank()) {
            baseSystemPrompt
        } else {
            baseSystemPrompt + "\n\n" + pluginPrompt
        }
        // 首轮路由靠它判任务类型。取**第一条**用户消息而不是最后一条：
        // 风神的语义是"这个会话在干什么"，而不是"这一句在说什么"
        val firstUserMessage = workingList.firstOrNull { it.role == Role.USER }?.content.orEmpty()
        val apiKey = keyStore.getApiKey(provider.id).orEmpty()
        val token = keyStore.getServerToken(provider.id).orEmpty()

        /*
         * 长期记忆压缩之后，只把「压缩点之后」的消息发给模型。
         *
         * contextStart 是相对**完整消息**的下标（含被渲染窗口裁掉的前缀），
         * 而 workingList 末尾可能刚追加了这次的用户消息 —— 所以要把两者对齐：
         * 前缀长度 + workingList 才是一次请求的真实消息序列。
         *
         * 只在角色扮演场景生效：编程场景的历史里混着 tool_calls 组，
         * 从中间切会破坏配对（TurnSanitizer 虽能补占位，但那是兜底不是常态）。
         */
        val fullSeq = if (droppedPrefix.isEmpty()) workingList else droppedPrefix + workingList
        val fromIndex = if (isRoleplay) contextStart.coerceIn(0, fullSeq.size) else 0
        /*
         * 附件正文按 id 预读一次。
         *
         * 放在这里而不是 toTurns 里现场读，是因为 turns 的构建每轮都要走一遍，
         * 同一个附件会被重复读；也不能用 withContext(Dispatchers.IO) 包起来 ——
         * 测试用 runTest 的虚拟时间，切到真实调度器会卡在别的线程上等不到结果。
         * 单文件最多 6 万字，一次同步读在几毫秒内，且只发生在带附件的消息上。
         */
        val attachTexts: Map<String, String> = fullSeq.flatMap { it.attachments }
            .filter { it.ok && it.kind != com.dshwatch.domain.model.AttachmentKinds.IMAGE }
            .associate { it.id to com.dshwatch.data.attachment.AttachmentStore.readText(it) }
        var turns: List<ChatTurn> = fullSeq.drop(fromIndex).flatMap { it.toTurns(attachTexts) }
        // 供应商不支持图片输入时，图仍留在会话里可回看，但不进请求
        if (!provider.visionInput) {
            turns = turns.map { if (it.images.isEmpty()) it else it.copy(images = emptyList()) }
        }
        /*
         * 内置通道再按模型收一道。
         *
         * 供应商级的 visionInput 只能表达「这家有没有视觉能力」，而智谱一家里
         * glm-4.7-flash（纯文本）和 glm-4.6v-flash（视觉）混着放 —— 用户选了前者
         * 又附了图，请求会直接 400。这里按模型名单再筛一次，并且明确告诉用户
         * 图没发出去，而不是让他对着「怎么答非所问」猜。
         */
        val builtinSpec = com.dshwatch.domain.model.BuiltinProviders.of(provider.id)
        if (builtinSpec != null && model !in builtinSpec.visionModels) {
            val hadImages = turns.any { it.images.isNotEmpty() }
            turns = turns.map { if (it.images.isEmpty()) it else it.copy(images = emptyList()) }
            if (hadImages) {
                _banner.value = Banner("当前模型（$model）读不了图，图片没有发出去；切到视觉模型再试")
            }
        }
        // 用户在这次任务里拒绝过的工具：后续同一工具不再弹窗，直接静默拒绝
        val deniedTools = mutableSetOf<String>()
        // 已经把「被拒绝」回灌给模型一次；如果再被拒一次就收手，避免无限循环
        var denialFedBack = false

        if (settings.foregroundService) setStreaming(true)

        var failure: DshError? = null
        var usedSteps = 0
        var lastMessageId = ""
        var awaitingMoreSteps = false

        for (step in 1..maxSteps) {
            if (!currentCoroutineContext().isActive) break
            usedSteps = step

            // 插件路由：目前只有风神，首轮返回 persona + 收窄工具面，之后返回 null
            val overlay = runCatching {
                plugins.overlay(
                    TurnContext(step = step, firstUserMessage = firstUserMessage, settings = settings)
                )
            }.getOrNull()
            // 本轮实际开放的工具有哪些 —— 必须用**收窄后**的名单，
            // 不能写全量。真机上出过这一幕：风神的首轮路由把工具面收窄到 5 个，
            // 模型的思维链里写"工具列表里没有 cloud_build，我只有 read_file/write_file/
            // edit_file/run_script/delete_file"，然后开始给用户解释"本机编不了 APK"。
            // 它没有错 —— 它看到的确实只有那几个；错在这个事实只存在于 tools 数组里，
            // 而"本轮是收窄的、下一轮会放开"这件事它无从得知。
            /*
             * 工具面只受插件路由影响，不再按设备模式收窄。
             *
             * 早先操控期间只下发 device_*，省了固定成本，但模型做完操控想写总结
             * 时看不见 write_file，只会说「我没法写文件」。这个失败模式比省下的
             * 那点 token 贵得多。
             */
            val visibleTools = registry.tools
            val allowed = overlay?.allowedTools
            val roundNames = if (allowed != null) {
                visibleTools.filter { it.name in allowed }.map { it.name }
            } else {
                visibleTools.map { it.name }
            }
            // 酒馆/写作：一句工具说明都不能出现在提示里。
            // 模型看到"可用工具"就会想调用，哪怕 tools 数组是空的 ——
            // 它会在正文里写"我需要先查一下文件"，戏就断了。
            val roster = if (!agentOn || roundNames.isEmpty()) {
                ""
            } else {
                buildString {
                    append("\n\n【可用工具】\n").append(roundNames.joinToString("、"))
                    if (allowed != null) {
                        // 这一句不能删：首轮收窄时，模型会据「名单里没有」推断「App 做不到」
                        append("\n（本轮只开放这些，下一轮放开其余 —— 「这里没有」≠「App 做不到」。）")
                    } else {
                        append("\n（以上已带完整参数，可直接调用。）")
                    }
                }
            }
            val roundPrompt = buildString {
                append(systemPrompt)
                if (agentOn) {
                    overlay?.promptSuffix?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
                    append(roster)
                }
                /*
                 * 画布是 UI 的输出格式，和「agent 开没开」无关：关掉工具也照样能画。
                 * 放在 roster 之后、角色扮演之外 —— 上面那份工具名单正是模型
                 * 得出「没有画图工具 = 做不到」的依据，说明必须紧跟其后；
                 * 而酒馆/写作场景里任何能力说明都会把人设带跑。
                 */
                if (!isRoleplay) append(CanvasGuide.PROMPT)
                /*
                 * 设备操控的规则只在开关打开时注入。它讲的是「操作期间用户看不见对话」
                 * 这类任务级约束 —— 不看这段，模型会在中途用 ask_user 提问，
                 * 而那个问题会永远悬在那里（用户正盯着被操作的那个 App）。
                 */
                if (!isRoleplay && settings.deviceControlEnabled) {
                    append("\n\n").append(com.dshwatch.domain.agent.DeviceGuide.PROMPT)
                }
            }
            // agentOn=false 时一个 spec 都不发，这里再加一道保险：
            // overlay（风神）是编程场景的插件，角色扮演场景不该被它插一手。
            val roundSpecs = if (!agentOn) {
                emptyList()
            } else {
                val base = if (allowed != null) {
                    // 收窄后一个都不剩就别收窄了 —— 否则模型没有任何工具可用，
                    // 表现为"这轮它什么都不做，只回一段文字"
                    visibleTools.filter { it.name in allowed }.ifEmpty { visibleTools }
                } else {
                    visibleTools
                }
                base.map(ToolRegistry::specOf)
            }

            val placeholder = ChatMessage(
                id = Ids.new(),
                role = Role.ASSISTANT,
                content = "",
                createdAt = System.currentTimeMillis(),
                status = MessageStatus.STREAMING,
                model = model,
                providerId = provider.id,
                parentId = newUserMessage?.id ?: regenerateOf,
                stepIndex = if (agentOn) step else 0,
            )
            lastMessageId = placeholder.id
            _messages.value = _messages.value + placeholder
            _live.value = LiveDelta(placeholder.id, step = if (agentOn) step else 0)

            // 注意：这里**不能**再对结果做 takeLast —— 会把
            // [assistant(tool_calls), tool, ...] 这组从中间切断，服务端直接 400。
            // 条数上限通过 maxTurns 交给裁剪器按组处理。
            //
            // fullContext=true：上下文**不再主动裁剪**（用户明确要求
            // 「不要截断上下文，即使导致设备卡顿也不要」—— 模型看不到
            // 自己前面做过什么，长任务里就会原地打转）。只有估算量超出
            // 模型真实窗口时，trimToBudget 内部才退化为按预算装填兜底。
            //
            // 设备操控是唯一例外：那是一次几十步的长轨迹，每步都带回一整屏。
            // 旧屏既没用（屏幕早就变了）又危险（模型会照着旧编号点），
            // 所以只保留最近几条全文，更早的压成一行「做过什么」。
            // 这不是「省钱技巧」—— 不折叠时模型反而更容易被过时编号带偏。
            val sendTurns = com.dshwatch.domain.agent.DeviceTurnPruner.prune(turns)
            val trimmed = TokenEstimator.trimToBudget(
                systemTokens = TokenEstimator.estimate(roundPrompt),
                history = sendTurns,
                contextWindow = provider.contextWindow,
                maxOutputTokens = provider.maxTokens,
                maxTurns = Int.MAX_VALUE,
                fullContext = true,
            )

            val request = ChatRequest(
                provider = provider,
                baseUrl = provider.normalizedBaseUrl(),
                apiKey = apiKey,
                serverToken = token,
                model = model,
                systemPrompt = roundPrompt,
                history = trimmed,
                temperature = provider.temperature,
                topP = provider.topP,
                maxTokens = provider.maxTokens,
                stream = provider.stream,
                reasoningEffort = provider.reasoningEffort,
                usageInStream = provider.requestUsageInStream,
                remoteSessionId = conv.remoteSessionId,
                tools = roundSpecs,
            )

            val result = streamStep(request, placeholder, perf)
            _live.value = LiveDelta()

            val estimated = result.usage == null
            val effectiveUsage = result.usage ?: TokenUsage(
                prompt = TokenEstimator.estimateTurns(trimmed) + TokenEstimator.estimate(roundPrompt),
                completion = TokenEstimator.estimate(result.text) + TokenEstimator.estimate(result.reasoning),
            )

            if (result.failure != null) {
                failure = result.failure
                finalize(
                    conversationId = conv.id,
                    messageId = placeholder.id,
                    text = result.text,
                    reasoning = result.reasoning,
                    status = MessageStatus.ERROR,
                    usage = if (result.text.isBlank()) null else effectiveUsage,
                    error = result.failure.friendly + "：" + result.failure.message.orEmpty(),
                    provider = provider,
                    model = model,
                    estimated = estimated,
                    toolCalls = result.toolCalls,
                )
                break
            }

            if (result.stopped) {
                finalize(
                    conversationId = conv.id,
                    messageId = placeholder.id,
                    text = result.text,
                    reasoning = result.reasoning,
                    status = MessageStatus.STOPPED,
                    usage = effectiveUsage,
                    error = "",
                    provider = provider,
                    model = model,
                    estimated = estimated,
                    toolCalls = result.toolCalls,
                )
                recordUsage(effectiveUsage, provider, model, conv.id, estimated)
                break
            }

            if (result.toolCalls.isEmpty()) {
                finalize(
                    conversationId = conv.id,
                    messageId = placeholder.id,
                    text = result.text,
                    reasoning = result.reasoning,
                    status = MessageStatus.DONE,
                    usage = effectiveUsage,
                    error = "",
                    provider = provider,
                    model = model,
                    estimated = estimated,
                    toolCalls = emptyList(),
                )
                recordUsage(effectiveUsage, provider, model, conv.id, estimated)
                conv = _conversation.value ?: conv
                /*
                 * 模型不再调工具 = 这次任务做完了。
                 *
                 * 设备操控的悬浮条和「只带设备工具」都挂在 running 上，而它以前
                 * 只在 handoff / 用户点停止时才清。结果：任务早做完了，悬浮条还挂在
                 * 屏幕上；用户接着问「帮我写个文件」，工具面还是那四个设备工具 ——
                 * 模型只能回答「我现在在操控模式」。这里按「说完话就收工」清掉。
                 */
                if (com.dshwatch.domain.device.DeviceControlHub.state.value.running) {
                    com.dshwatch.domain.device.DeviceControlHub.end()
                }
                break
            }

            // 4) 这一轮是工具调用：先把助手消息定稿（带工具卡片），再执行工具
            finalize(
                conversationId = conv.id,
                messageId = placeholder.id,
                text = result.text,
                reasoning = result.reasoning,
                status = MessageStatus.DONE,
                usage = effectiveUsage,
                error = "",
                provider = provider,
                model = model,
                estimated = estimated,
                toolCalls = result.toolCalls,
            )
            recordUsage(effectiveUsage, provider, model, conv.id, estimated)
            conv = _conversation.value ?: conv

            turns = turns +
                ChatTurn(role = "assistant", content = result.text, toolCalls = result.toolCalls)

            val executed = executeTools(
                calls = result.toolCalls,
                messageId = placeholder.id,
                settings = settings,
                registry = registry,
                deniedTools = deniedTools,
                sessionDir = conv.workDir,
            )
            executed.forEach { call ->
                // 插件在这里接管工具结果 —— 这是**唯一**能省下 tool 结果 token 的位置。
                // 放到别处都不行：太早（工具内部）拿不到工具名与产物，太晚（请求前）
                // 消息已经被 trimToBudget 按组切过了。
                val content = if (plugins.hasResultPlugins) {
                    runCatching {
                        plugins.transformToolResult(
                            ToolResultInput(
                                toolName = call.name,
                                content = call.result,
                                artifact = call.artifact.ifBlank { null },
                                sessionDir = conv.workDir,
                                workspace = registry.toolContext.workspace,
                                settings = settings,
                            )
                        )
                    }.getOrElse { t ->
                        Logx.w(TAG, "插件处理工具结果失败，用原内容", t)
                        call.result
                    }
                } else {
                    call.result
                }
                turns = turns + ChatTurn(
                    role = "tool",
                    content = content.ifBlank { "(无输出)" },
                    toolCallId = call.id,
                )
            }

            if (!currentCoroutineContext().isActive) break

            val allRefused = executed.all {
                it.status == ToolStatus.DENIED || it.status == ToolStatus.SKIPPED
            }
            if (allRefused) {
                // 第一次把「被拒绝」告诉模型，它往往能换个不需要权限的做法；
                // 如果它还是坚持同一个工具，就不再来回打扰用户。
                if (denialFedBack) {
                    _banner.value = Banner("工具调用被拒绝，任务已中止")
                    break
                }
                denialFedBack = true
            }

            // 停滞检测：连续几步没有任何新进展，就别再让它空转。
            // 设备操控期间不启用 —— 读屏两次内容相同是常态，见 ProgressTracker 的说明。
            val inDeviceSession =
                com.dshwatch.domain.device.DeviceControlHub.state.value.running
            val verdict = progressTracker?.record(executed, stallDetection = !inDeviceSession)
            if (verdict is ProgressTracker.Verdict.Stalled) {
                _banner.value = Banner(verdict.reason + "任务可能卡住了 —— 可以补充信息后继续。")
                break
            }

            // 跑到兜底上限仍在要工具（正常任务到不了这里）
            if (step == maxSteps) awaitingMoreSteps = true
        }

        if (failure == null && awaitingMoreSteps) {
            _banner.value = Banner(
                "已连续执行 $HARD_STEP_CEILING 步仍未结束，已停下。任务可能过于复杂，建议拆成几步来做。"
            )
        }

        val f = failure
        if (f != null && f !is DshError.Canceled) {
            _banner.value = Banner(f.describeForUser())
        } else if (failure == null && usedSteps > 0 &&
            _messages.value.lastOrNull { it.id == lastMessageId }?.status == MessageStatus.ERROR
        ) {
            _banner.value = Banner("模型没有返回内容，换个模型或检查参数试试")
        }

        setStreaming(false)
        _live.value = LiveDelta()
        /*
         * 设备操控的悬浮条跟着这一轮一起收起。
         *
         * 模型不再调工具 = 这轮操控结束了；此时如果条还挂在屏幕上，
         * 传达的信息是「AI 仍然在操作你的设备」，而事实并非如此。
         * 用户点停止的那条路径已经收过一次，end() 是幂等的。
         */
        runCatching { com.dshwatch.domain.device.DeviceControlHub.end() }
    }

    /** 单步流式请求的产物 */
    private class StepResult(
        val text: String,
        val reasoning: String,
        val toolCalls: List<ToolCallRecord>,
        val usage: TokenUsage?,
        val failure: DshError?,
        val stopped: Boolean,
    )

    /**
     * 流式 tool_calls 的累积器。
     * OpenAI 兼容协议里 tool_calls 按 index 分片：
     * 第一片带 id + function.name，后续片只带 function.arguments 的字符串片段，
     * 所以必须按 index 归并、把 arguments 拼起来才能得到完整参数。
     */
    private class PartialCall(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    ) {
        fun toRecord(ordinal: Int) = ToolCallRecord(
            id = id.ifBlank { "call_$ordinal" },
            name = ToolRegistry.normalizeName(name),
            arguments = args.toString().ifBlank { "{}" },
        )
    }

    private suspend fun streamStep(
        request: ChatRequest,
        placeholder: ChatMessage,
        perf: PerfProfile,
    ): StepResult {
        val textBuf = StringBuilder()
        val reasonBuf = StringBuilder()
        val calls = LinkedHashMap<Int, PartialCall>()
        var usage: TokenUsage? = null
        var failure: DshError? = null
        var stopped = false
        val throttle = perf.streamThrottleMs
        var lastFlush = System.currentTimeMillis()

        fun flush(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastFlush < throttle) return
            lastFlush = now
            _live.value = LiveDelta(
                messageId = placeholder.id,
                text = textBuf.toString(),
                reasoning = reasonBuf.toString(),
                step = placeholder.stepIndex,
            )
        }

        try {
            backends.of(request.provider.type).streamChat(request).collect { event ->
                when (event) {
                    is ChatEvent.Text -> {
                        textBuf.append(event.delta)
                        flush()
                    }

                    is ChatEvent.Reasoning -> {
                        reasonBuf.append(event.delta)
                        flush()
                    }

                    is ChatEvent.ToolCallDelta -> {
                        val partial = calls.getOrPut(event.index) { PartialCall() }
                        event.id?.takeIf { it.isNotBlank() }?.let { partial.id = it }
                        event.name?.takeIf { it.isNotBlank() }?.let { partial.name = it }
                        event.argsDelta?.let { partial.args.append(it) }
                        flush(force = true)
                    }

                    is ChatEvent.Usage -> usage = event.usage

                    is ChatEvent.Title -> {
                        val conv = _conversation.value
                        if (conv != null && event.title.isNotBlank()) {
                            _conversation.value = conv.copy(title = event.title)
                        }
                    }

                    is ChatEvent.RemoteSession -> {
                        val conv = _conversation.value
                        if (conv != null && event.id.isNotBlank() && conv.remoteSessionId != event.id) {
                            _conversation.value = conv.copy(remoteSessionId = event.id)
                        }
                    }

                    is ChatEvent.Failure -> {
                        failure = event.error
                        if (event.error is DshError.Canceled) stopped = true
                    }

                    is ChatEvent.Finish -> Unit
                    ChatEvent.Completed -> Unit
                }
            }
        } catch (c: CancellationException) {
            stopped = true
        } catch (t: Throwable) {
            failure = DshError.from(t)
            Logx.e(TAG, "流式异常", t)
        }

        flush(force = true)
        return StepResult(
            text = textBuf.toString(),
            reasoning = reasonBuf.toString(),
            toolCalls = calls.entries
                .sortedBy { it.key }
                .mapIndexed { ordinal, entry -> entry.value.toRecord(ordinal) }
                .filter { it.name.isNotBlank() },
            usage = usage,
            failure = failure,
            stopped = stopped,
        )
    }

    // ---------------- 工具执行 ----------------

    private suspend fun executeTools(
        calls: List<ToolCallRecord>,
        messageId: String,
        settings: AppSettings,
        registry: ToolRegistry,
        deniedTools: MutableSet<String>,
        sessionDir: String,
    ): List<ToolCallRecord> {
        // 工具集是应用级单例，而工作目录是每个会话各自的 —— 这里换一副「眼镜」，
        // 之后所有相对路径都会被解析到本会话的目录下。
        val toolCtx = registry.toolContext.withSession(sessionDir)
        val out = ArrayList<ToolCallRecord>(calls.size)
        for (call in calls) {
            if (!currentCoroutineContext().isActive) {
                val skipped = call.copy(status = ToolStatus.SKIPPED, result = "任务已取消")
                out += skipped
                updateToolCall(messageId, skipped)
                continue
            }

            val tool = registry.get(call.name)
            if (tool == null) {
                val rec = call.copy(
                    status = ToolStatus.FAILED,
                    result = "没有名为「${call.name}」的工具。可用工具：" +
                        registry.tools.joinToString(", ") { it.name },
                    summary = call.name,
                )
                out += rec
                updateToolCall(messageId, rec)
                continue
            }

            // 参数解析。模型给的 JSON 可能带裸换行、也可能被 token 上限截断，
            // 这两种都必须给出**可操作**的说明，而不是回退成空对象后报「缺少 path 参数」——
            // 那会把真正的原因彻底盖住（真机上就是这么被误导的）。
            val parsed = ToolArgs.parse(call.arguments, json)
            if (parsed !is ToolArgs.Result.Ok) {
                val rec = call.copy(
                    status = ToolStatus.FAILED,
                    result = ToolArgs.explain(parsed),
                    summary = call.arguments.replace('\n', ' ').take(80),
                )
                out += rec
                updateToolCall(messageId, rec)
                continue
            }
            val args = parsed.args
            val summary = runCatching { tool.summarize(args) }.getOrDefault("")

            if (tool.name in deniedTools) {
                val rec = call.copy(
                    status = ToolStatus.DENIED,
                    result = "用户此前已拒绝执行该工具，本次不再询问",
                    summary = summary,
                )
                out += rec
                updateToolCall(messageId, rec)
                continue
            }

            if (needsApproval(settings, tool, args)) {
                updateToolCall(
                    messageId,
                    call.copy(status = ToolStatus.AWAITING_APPROVAL, summary = summary),
                )
                when (awaitApproval(call, tool, summary, args)) {
                    ApprovalDecision.DENY -> {
                        deniedTools += tool.name
                        if (tool.name == "adb_shell") {
                            toolCtx.appContext?.let { ctx ->
                                val cmd = args.strOf("command").orEmpty()
                                com.dshwatch.domain.shell.ShellAudit.record(
                                    context = ctx,
                                    command = cmd,
                                    risk = com.dshwatch.domain.shell.ShellRules.classify(cmd),
                                    category = ApprovalPolicy.shellCategory(cmd),
                                    decision = "deny",
                                    exitCode = -1,
                                )
                            }
                        }
                        val rec = call.copy(
                            status = ToolStatus.DENIED,
                            result = "用户拒绝执行该工具",
                            summary = summary,
                        )
                        out += rec
                        updateToolCall(messageId, rec)
                        continue
                    }

                    ApprovalDecision.ALLOW_ALWAYS -> settingsRepo.update { st ->
                        // 提权命令按**类别**记忆：同意「装应用」不等于同意「改屏幕」。
                        // 危险命令不会走到这里（UI 不给这个按钮，策略层也不认）。
                        if (tool.name == "adb_shell") {
                            val cat = ApprovalPolicy.shellCategory(args.strOf("command").orEmpty())
                            st.copy(shellGrants = st.shellGrants + cat.key)
                        } else {
                            st.copy(autoApprovedTools = st.autoApprovedTools + tool.name)
                        }
                    }

                    ApprovalDecision.ALLOW_SESSION -> {
                        ApprovalPolicy.sessionKey(tool.name, args)?.let { sessionApproved += it }
                    }

                    ApprovalDecision.ALLOW_ONCE -> Unit
                }
            }

            updateToolCall(messageId, call.copy(status = ToolStatus.RUNNING, summary = summary))
            runCatching { onToolStarted?.invoke(summary.ifBlank { tool.name }) }
            val startedAt = System.currentTimeMillis()
            val result = runCatching { tool.execute(args, toolCtx) }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
                    Logx.e(TAG, "工具 ${tool.name} 执行异常", t)
                    ToolResult.fail("执行异常：${t.javaClass.simpleName}: ${t.message}")
                }
            val rec = call.copy(
                status = if (result.ok) ToolStatus.SUCCESS else ToolStatus.FAILED,
                // 工具结果**完整**回灌，不再落盘压缩。v1.6 曾把超长结果换成
                // 「头尾 + 路径」的摘要，结果模型到后面忘了自己做过什么，
                // 长任务里原地打转。用户明确选择：宁可上下文变大、设备卡顿，
                // 也要让模型看得见每一次操作的完整结果。
                result = result.content,
                summary = summary,
                startedAt = startedAt,
                elapsedMs = System.currentTimeMillis() - startedAt,
                artifact = if (result.ok) result.artifact else "",
            )
            out += rec
            updateToolCall(messageId, rec)
        }
        return out
    }

    private suspend fun awaitApproval(
        call: ToolCallRecord,
        tool: AgentTool,
        summary: String,
        args: JsonObject,
    ): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        approvalDeferred = deferred
        val isShell = tool.name == "adb_shell"
        val command = if (isShell) args.strOf("command").orEmpty() else ""
        _approval.value = PendingApproval(
            callId = call.id,
            toolName = tool.name,
            summary = summary,
            arguments = call.arguments,
            safety = tool.safety,
            sessionScoped = ApprovalPolicy.isSessionScoped(tool.name, args),
            purpose = args.strOf("purpose").orEmpty(),
            command = command,
            danger = isShell && com.dshwatch.domain.shell.ShellRules.classify(command) ==
                com.dshwatch.domain.shell.ShellRisk.DANGER,
            categoryLabel = if (isShell) ApprovalPolicy.shellCategory(command).label else "",
        )
        return try {
            deferred.await()
        } finally {
            _approval.value = null
            approvalDeferred = null
        }
    }

    private fun needsApproval(settings: AppSettings, tool: AgentTool, args: JsonObject): Boolean =
        ApprovalPolicy.needsApproval(
            settings = settings,
            toolName = tool.name,
            safety = tool.safety,
            args = args,
            sessionApproved = sessionApproved,
        )

    /** 把某条助手消息里的工具记录替换成最新状态，并落盘 */
    private suspend fun updateToolCall(messageId: String, record: ToolCallRecord) {
        ioMutex.withLock {
            val list = _messages.value
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx < 0) return@withLock
            val message = list[idx]
            val calls = message.toolCalls.toMutableList()
            val pos = calls.indexOfFirst { it.id == record.id }
            if (pos >= 0) calls[pos] = record else calls.add(record)
            val updated = list.toMutableList().also { it[idx] = message.copy(toolCalls = calls) }
            _messages.value = updated
            _conversation.value?.let { conv ->
                persist(conv, updated)
            }
        }
    }

    // ---------------- 收尾 ----------------

    private suspend fun finalize(
        conversationId: String,
        messageId: String,
        text: String,
        reasoning: String,
        status: MessageStatus,
        usage: TokenUsage?,
        error: String,
        provider: Provider? = null,
        model: String = "",
        estimated: Boolean = false,
        titleOverride: String? = null,
        remoteSessionId: String? = null,
        toolCalls: List<ToolCallRecord> = emptyList(),
    ) {
        ioMutex.withLock {
            val current = _messages.value
            val idx = current.indexOfFirst { it.id == messageId }
            val updated = if (idx >= 0) {
                current.toMutableList().also {
                    val existing = it[idx]
                    it[idx] = existing.copy(
                        content = text,
                        reasoning = reasoning,
                        status = status,
                        usage = usage,
                        error = error,
                        // 工具卡片在 executeTools 期间会被逐步更新，这里不能覆盖掉
                        toolCalls = if (existing.toolCalls.isNotEmpty()) existing.toolCalls else toolCalls,
                    )
                }.toList()
            } else {
                current
            }
            _messages.value = updated

            val prevConv = _conversation.value
            val conv = (prevConv ?: Conversation(id = conversationId)).copy(
                id = conversationId,
                title = titleOverride ?: prevConv?.title ?: "新对话",
                updatedAt = System.currentTimeMillis(),
                usage = (prevConv?.usage ?: TokenUsage.ZERO) + (usage ?: TokenUsage.ZERO),
                remoteSessionId = remoteSessionId ?: prevConv?.remoteSessionId.orEmpty(),
            )
            _conversation.value = conv
            persist(conv, updated)
        }
    }

    private suspend fun recordUsage(
        usage: TokenUsage,
        provider: Provider,
        model: String,
        conversationId: String,
        estimated: Boolean,
    ) {
        if (usage.isEmpty) return
        usageRepo.record(
            UsageRepository.makeRecord(
                usage = usage,
                providerId = provider.id,
                providerName = provider.name,
                model = model.ifBlank { provider.defaultModel },
                conversationId = conversationId,
                priceInPerMillion = provider.priceInPerMillion,
                priceOutPerMillion = provider.priceOutPerMillion,
                estimated = estimated,
            )
        )
    }

    private fun buildSystemPrompt(
        provider: Provider,
        settings: AppSettings,
        workDir: String,
    ): String {
        val base = if (provider.systemPrompt.isNotBlank()) {
            if (provider.customPrompt.isNotBlank()) {
                provider.systemPrompt + "\n\n补充要求：\n" + provider.customPrompt
            } else {
                provider.systemPrompt
            }
        } else {
            PromptPresets.compose(
                presetId = provider.presetId.ifBlank { settings.activePresetId },
                customPrompt = provider.customPrompt,
            )
        }
        // Agent 模式下追加一段工具使用约定：
        // 不加的话模型经常把工具当装饰不用，或者凭记忆编造实时信息。
        //
        val agentHint = if (settings.agentMode != AgentMode.OFF) {
            // 只写「怎么用」，参数细节留在各工具的 description 里 —— 两边不重复讲
            "\n\n" + AgentGuide.PROMPT + workspaceHint(workDir)
        } else {
            ""
        }
        return base + agentHint
    }

    /**
     * 工作空间约定。
     *
     * 这段是**必须**的。不给的话，模型会把自己「以为」的绝对路径
     * （`/data/data/<包名>/files/workspace/index.html`）直接当参数传进来，
     * 而沙盒只接受相对路径 —— 于是文件被写进深层嵌套目录，
     * 接着 `build_project` 报「找不到入口文件」，现象极难排查。
     * 详见 [com.dshwatch.data.workspace.WorkspacePaths] 的注释。
     */
    private fun workspaceHint(workDir: String): String {
        val abs = runCatching { workspaceRoot() }.getOrDefault("")
        val label = workDir.trim().trim('/').ifBlank { "工作空间根目录" }
        return buildString {
            append("\n\n【工作空间】\n")
            append("你有一个私有工作目录：**").append(label).append("**。")
            append("每个会话一个独立目录，所以不同会话的产物不会互相覆盖。\n")
            append("所有文件类工具的 path 一律用**相对于当前工作目录的相对路径**，")
            append("**不要**把 `").append(label).append("` 这个前缀也写进去：\n")
            append("  ✅ index.html    ✅ src/app.js    ✅ mysite/index.html\n")
            append("  ❌ ").append(label).append("/index.html（重复了，会被当成子目录）\n")
            append("  ❌ /data/data/<包名>/files/workspace/index.html\n")
            append("  ❌ data/data/<包名>/files/workspace/index.html\n")
            append("带上绝对路径前缀会被当成普通目录名，文件会被写进莫名其妙的深层目录，构建也会失败。\n")
            append("需要在一个项目里分多个文件时，用自己的子目录（如 mysite/index.html、mysite/app.js），互不冲突。\n")
            append("**写长内容要分段**：单次 write_file 的内容建议控制在 200 行以内。")
            append("更长的文件先 write_file 写入骨架，再用 append_file 逐段追加 —— ")
            append("一次性写完很容易超出单次输出上限、导致参数被截断而整次调用失败。\n")
            if (abs.isNotBlank()) {
                append("（工作空间在你设备上的真实位置是 ")
                append(abs)
                append("，仅供你理解，**不要**把它写进任何参数。）")
            }
        }
    }

    /**
     * 把一条落盘的消息翻译成协议消息。
     *
     * ⚠️ 带工具调用的助手消息必须**展开成 1 + N 条**：
     *   1 条 assistant(tool_calls) + 每个已落定的调用各 1 条 role=tool。
     * 只发 assistant(tool_calls) 而不发 tool 结果，服务端会返回
     * "An assistant message with 'tool_calls' must be followed by tool messages
     *  responding to each 'tool_call.id'"。
     */
    private fun ChatMessage.toTurns(attachTexts: Map<String, String> = emptyMap()): List<ChatTurn> {
        if (role == Role.SYSTEM) return emptyList()

        if (toolCalls.isNotEmpty()) {
            // 只有「已经终结」的调用才能进历史：还在跑 / 被跳过的没有结果可回传，
            // 一起发出去就是缺 tool 消息。
            val settled = toolCalls.filter { it.isSettled }
            if (settled.isEmpty()) {
                return if (content.isBlank()) {
                    emptyList()
                } else {
                    listOf(ChatTurn(role = role.wire, content = content))
                }
            }
            return buildList {
                add(ChatTurn(role = "assistant", content = content, toolCalls = settled))
                settled.forEach { call ->
                    add(
                        ChatTurn(
                            role = "tool",
                            content = call.result.ifBlank { "(无输出)" },
                            toolCallId = call.id,
                        )
                    )
                }
            }
        }

        /*
         * 附件正文拼在用户消息末尾。
         *
         * 不另发一条 role=user 的「文件内容」消息：那样一轮对话里会出现两条用户消息，
         * 有些服务端会拒（要求严格 user/assistant 交替），而拼在正文里对所有
         * OpenAI 兼容端点都成立。分隔符用明确的文件名，模型才知道哪段属于哪个文件。
         */
        val filesBlock = if (role == Role.USER) {
            attachments.filter { it.ok && it.kind != com.dshwatch.domain.model.AttachmentKinds.IMAGE }
                .mapNotNull { att ->
                    val body = attachTexts[att.id]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    "【文件：${att.name}】\n$body\n【文件结束：${att.name}】"
                }
                .joinToString("\n\n")
        } else {
            ""
        }
        val mergedContent = when {
            filesBlock.isBlank() -> content
            content.isBlank() -> filesBlock
            else -> "$content\n\n$filesBlock"
        }

        if (mergedContent.isBlank() && media.none { it.kind == MediaKind.IMAGE && it.isReady }) {
            return emptyList()
        }
        /*
         * 只带用户消息的图。
         *
         * 助手消息上挂的是「模型生成的图」，把它当输入再发回去既没意义
         * （模型看不见自己刚生成的产物），又会让下一轮请求体白白翻倍。
         * 用户上传的图片附件也归到 images —— 走的是同一条多模态通道。
         */
        val images = if (role == Role.USER) {
            media.filter { it.kind == MediaKind.IMAGE && it.isReady }
                .mapNotNull { it.localFile()?.absolutePath } +
                attachments.filter {
                    it.ok && it.kind == com.dshwatch.domain.model.AttachmentKinds.IMAGE
                }.mapNotNull { it.path.takeIf { p -> p.isNotBlank() && java.io.File(p).exists() } }
        } else {
            emptyList()
        }
        return listOf(ChatTurn(role = role.wire, content = mergedContent, images = images))
    }

    private fun setStreaming(value: Boolean) {
        if (_streaming.value == value) return
        _streaming.value = value
        onStreamingChanged?.invoke(value)
    }

    private fun autoTitle(text: String): String {
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length <= 18) line else line.take(18) + "…"
    }

    data class Banner(val text: String)

    companion object {

        /**
         * 兜底步数上限。
         *
         * 正常情况下靠 ProgressTracker 停（它检测"原地打转"）；
         * 这个数字只是防止病态场景真的跑飞，正常任务永远碰不到。
         */
        private const val HARD_STEP_CEILING = 200

        /**
         * 每次都必须确认的工具（已迁到 [ApprovalPolicy.ALWAYS_CONFIRM]）。
         *
         * shell 也仍然每次确认，但多了「本会话允许」；js 不再强制拦。
         * 具体规则见 [ApprovalPolicy]，那里可以单测。
         */
        val ALWAYS_CONFIRM_TOOLS = ApprovalPolicy.ALWAYS_CONFIRM
        private const val TAG = "ChatEngine"

        /** 等用户回答 ask_user 的最长时间；超时后交给模型自行收尾 */
        private const val QUESTION_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * 单个工具结果回灌给模型的**目标**长度。
         *
         * 已停用（2026-10-19）：v1.6 曾把超长结果整份落盘、回灌「头 + 尾 + 路径」
         * 摘要，但模型因此忘了自己做过什么，长任务里原地打转。用户明确要求
         * 「不要截断上下文，即使导致设备卡顿也不要」，现在工具结果全文回灌。
         * 落盘能力保留在 [ToolResultCompactor]，供插件与工作空间翻查使用。
         */
        const val MAX_TOOL_RESULT_CHARS = ToolResultCompactor.THRESHOLD
    }
}
