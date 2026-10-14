package com.dshwatch.domain

import com.dshwatch.core.Ids
import com.dshwatch.core.Logx
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.store.StoredConversation
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.ToolArgs
import com.dshwatch.domain.agent.ToolRegistry
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.MessageStatus
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
)

enum class ApprovalDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

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
    private val json: Json,
) {

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

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

    init {
        // 工具集先于引擎构造（AppContainer 里的顺序相反），所以这里做后置注册
        promptChannel?.handler = { q -> awaitUserAnswer(q) }
    }

    /** 生成开始/结束的回调，供前台服务启停 */
    var onStreamingChanged: ((Boolean) -> Unit)? = null

    // ---------------- 会话 ----------------

    suspend fun open(conversationId: String) {
        cancelRunning(silent = true)
        val sc = store.load(conversationId) ?: run {
            Logx.w(TAG, "会话不存在：$conversationId")
            return
        }
        _live.value = LiveDelta()
        _banner.value = null
        _conversation.value = sc.meta
        val limit = perfProvider().historyLoadLimit
        _messages.value = if (sc.messages.size > limit) sc.messages.takeLast(limit) else sc.messages
    }

    /** 供 UI 使用：先停掉正在跑的生成，再开一个新会话 */
    suspend fun newConversation(settings: AppSettings): Conversation {
        cancelRunning(silent = true)
        return startNew(settings)
    }

    /**
     * 创建一个新会话。
     *
     * ⚠️ 这里**绝对不能**再调用 stop()/cancelRunning()。
     * [runSend] 在「当前没有会话」时会调用本方法，而此刻正在运行的 job 就是 runSend 自己，
     * 一旦取消就会把自己杀掉——表现为「全新安装后第一条消息点发送毫无反应」。
     * 这个 bug 真实存在过，删除前请先确认 [runSend] 的调用链。
     */
    suspend fun startNew(settings: AppSettings): Conversation {
        val p = settings.activeProvider
        val conv = ConversationStore.newConversation(
            providerId = p?.id.orEmpty(),
            model = p?.defaultModel.orEmpty(),
            presetId = p?.presetId ?: settings.activePresetId,
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
        _live.value = LiveDelta()
        _banner.value = null
        return conv
    }

    suspend fun deleteConversation(id: String) {
        if (_conversation.value?.id == id) {
            _conversation.value = null
            _messages.value = emptyList()
            _live.value = LiveDelta()
        }
        store.delete(id)
    }

    /** 只改本会话使用的模型，不影响供应商默认值 */
    suspend fun overrideModel(model: String) {
        val conv = _conversation.value ?: return
        val updated = conv.copy(model = model, updatedAt = System.currentTimeMillis())
        _conversation.value = updated
        store.save(StoredConversation(updated, _messages.value))
    }

    suspend fun deleteMessage(messageId: String) {
        if (job?.isActive == true) return
        val conv = _conversation.value ?: return
        val list = _messages.value.filterNot { it.id == messageId }
        if (list.size == _messages.value.size) return
        _messages.value = list
        store.save(StoredConversation(conv, list))
    }

    suspend fun refreshConversation() {
        val id = _conversation.value?.id ?: return
        store.load(id)?.let { _conversation.value = it.meta }
    }

    // ---------------- 发送 ----------------

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (job?.isActive == true) {
            _banner.value = Banner("正在生成中，先停止或等待完成")
            return
        }
        job = appScope.launch { guardedRun { runSend(trimmed, regenerateOf = "") } }
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
        job = appScope.launch { guardedRun { runSend(regenerateOf = parent, existingHistory = kept) } }
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

    // ---------------- Agent 主循环 ----------------

    private suspend fun runSend(
        userText: String = "",
        regenerateOf: String,
        existingHistory: List<ChatMessage>? = null,
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

        // 1) 追加用户消息（重新生成时不追加）
        val newUserMessage: ChatMessage?
        val workingList: List<ChatMessage>
        if (regenerateOf.isEmpty()) {
            newUserMessage = ConversationStore.newMessage(
                role = Role.USER,
                content = userText,
                model = model,
                providerId = provider.id,
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
        runCatching { store.save(StoredConversation(conv, workingList)) }
            .onFailure { Logx.e(TAG, "会话落盘失败", it) }

        // 3) Agent 开关
        val registry = toolRegistry()
        val agentOn = settings.agentMode != AgentMode.OFF &&
            provider.type == ProviderType.OPENAI_COMPAT &&
            registry.tools.isNotEmpty()
        val toolSpecs = if (agentOn) registry.specs() else emptyList()
        val maxSteps = if (agentOn) settings.maxAgentSteps.coerceIn(1, 20) else 1

        val systemPrompt = buildSystemPrompt(provider, settings, conv.workDir)
        val apiKey = keyStore.getApiKey(provider.id).orEmpty()
        val token = keyStore.getServerToken(provider.id).orEmpty()

        var turns: List<ChatTurn> = workingList.flatMap { it.toTurns() }
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
            val trimmed = TokenEstimator.trimToBudget(
                systemTokens = TokenEstimator.estimate(systemPrompt),
                history = turns,
                contextWindow = provider.contextWindow,
                maxOutputTokens = provider.maxTokens,
                maxTurns = perf.requestHistoryLimit,
            )

            val request = ChatRequest(
                provider = provider,
                baseUrl = provider.normalizedBaseUrl(),
                apiKey = apiKey,
                serverToken = token,
                model = model,
                systemPrompt = systemPrompt,
                history = trimmed,
                temperature = provider.temperature,
                topP = provider.topP,
                maxTokens = provider.maxTokens,
                stream = provider.stream,
                reasoningEffort = provider.reasoningEffort,
                usageInStream = provider.requestUsageInStream,
                remoteSessionId = conv.remoteSessionId,
                tools = toolSpecs,
            )

            val result = streamStep(request, placeholder, perf)
            _live.value = LiveDelta()

            val estimated = result.usage == null
            val effectiveUsage = result.usage ?: TokenUsage(
                prompt = TokenEstimator.estimateTurns(trimmed) + TokenEstimator.estimate(systemPrompt),
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
                turns = turns + ChatTurn(
                    role = "tool",
                    content = call.result.ifBlank { "(无输出)" },
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

            // 跑到最后一步仍然要调工具 → 说明任务没做完
            if (step == maxSteps) awaitingMoreSteps = true
        }

        if (failure == null && awaitingMoreSteps) {
            _banner.value = Banner("已达到最大步数 $maxSteps，任务可能还没完成。可在「设置 → 行为」里调大。")
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

            if (needsApproval(settings, tool)) {
                updateToolCall(
                    messageId,
                    call.copy(status = ToolStatus.AWAITING_APPROVAL, summary = summary),
                )
                when (awaitApproval(call, tool, summary)) {
                    ApprovalDecision.DENY -> {
                        deniedTools += tool.name
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
                        st.copy(autoApprovedTools = st.autoApprovedTools + tool.name)
                    }

                    ApprovalDecision.ALLOW_ONCE -> Unit
                }
            }

            updateToolCall(messageId, call.copy(status = ToolStatus.RUNNING, summary = summary))
            val startedAt = System.currentTimeMillis()
            val result = runCatching { tool.execute(args, toolCtx) }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
                    Logx.e(TAG, "工具 ${tool.name} 执行异常", t)
                    ToolResult.fail("执行异常：${t.javaClass.simpleName}: ${t.message}")
                }
            val rec = call.copy(
                status = if (result.ok) ToolStatus.SUCCESS else ToolStatus.FAILED,
                result = result.content.take(MAX_TOOL_RESULT_CHARS),
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
    ): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        approvalDeferred = deferred
        _approval.value = PendingApproval(
            callId = call.id,
            toolName = tool.name,
            summary = summary,
            arguments = call.arguments,
            safety = tool.safety,
        )
        return try {
            deferred.await()
        } finally {
            _approval.value = null
            approvalDeferred = null
        }
    }

    private fun needsApproval(settings: AppSettings, tool: AgentTool): Boolean {
        if (tool.name in settings.autoApprovedTools) return false
        return when (settings.agentMode) {
            AgentMode.OFF, AgentMode.AUTO_ALL -> false
            AgentMode.ASK_EVERY_STEP -> true
            AgentMode.AUTO_READONLY -> tool.safety != ToolSafety.READ_ONLY
        }
    }

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
                store.save(StoredConversation(conv, updated))
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
            store.save(StoredConversation(conv, updated))
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
        val agentHint = if (settings.agentMode != AgentMode.OFF) {
            """

            【工具使用】
            你可以调用工具获取实时信息或产生副作用。需要外部信息时**直接调用工具**，不要凭记忆编造。
            可以连续调用多步：每步只做一件事，拿到结果后再决定下一步。
            最终回答不要复述工具原始输出，只给结论。

            【改文件优先用 edit_file】
            改已有文件用 edit_file 做精确替换，**不要** read 全文再 write 回去 ——
            那要多花一倍 token，而且整份重写很容易因为单次输出上限被截断而整次失败。
            只有新建文件或整份重写时才用 write_file；在末尾追加用 append_file。

            【多步任务先列清单】
            预计超过 3 步的任务，先调 todo_write 把计划写下来，之后每完成一步更新一次。
            这既是给你自己看的（长对话里上下文会被工具输出挤占，容易忘记原本要做什么），
            也让用户能实时看到进度。

            【写完代码必须校验】
            写完代码后调一次 build_project。它会按语言做静态检查（括号与字符串闭合、
            Python 缩进与冒号、HTML/XML 标签配对、JSON/YAML 语法等）并给出「文件:行号」。
            手表上装不下 Python/Kotlin 的编译器，这是你**在本机验证代码的唯一手段** ——
            不要写完就直接交付。有报错就改完再调一次，直到通过。

            【需要用户操作时，直接送他过去】
            要用户手动做某件事（配置 API Key、看产物、设日程）时，用 open_screen / open_file /
            create_event 把界面打开，而不是只在回答里描述路径 —— 手表屏幕上找菜单很费劲。
            """ + workspaceHint(workDir)
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
    private fun ChatMessage.toTurns(): List<ChatTurn> {
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

        if (content.isBlank()) return emptyList()
        return listOf(ChatTurn(role = role.wire, content = content))
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
        private const val TAG = "ChatEngine"

        /** 等用户回答 ask_user 的最长时间；超时后交给模型自行收尾 */
        private const val QUESTION_TIMEOUT_MS = 5 * 60 * 1000L

        /** 单个工具结果回灌给模型的最大长度，避免一次请求把上下文撑爆 */
        const val MAX_TOOL_RESULT_CHARS = 4_000
    }
}
