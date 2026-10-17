package com.dshwatch

import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.domain.ApprovalDecision
import com.dshwatch.domain.ChatEngine
import com.dshwatch.domain.CredentialSource
import com.dshwatch.domain.DeviceProfileDefaults
import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.PerfProfiles
import com.dshwatch.domain.SettingsSource
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolRegistry
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.model.TokenUsage
import com.dshwatch.domain.model.ToolStatus
import com.dshwatch.net.backend.BackendRegistry
import com.dshwatch.net.backend.ChatBackend
import com.dshwatch.net.backend.ChatEvent
import com.dshwatch.net.backend.ChatRequest
import com.dshwatch.net.backend.DiagStep
import com.dshwatch.net.backend.DshError
import com.dshwatch.net.backend.TurnSanitizer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ChatEngine 的回归测试。
 *
 * 两类重点：
 *  1) 「全新安装后的第一条消息」—— 历史上因为 startNew 自己 cancel 自己，
 *     首条消息在发出 HTTP 之前就被取消，界面表现是「点发送毫无反应」且无报错。
 *  2) Agent 多步循环 —— tool_calls 增量拼接、工具执行、结果回灌、步数上限、审批挂起。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val provider = Provider(
        id = "p1",
        name = "Test",
        type = ProviderType.OPENAI_COMPAT,
        baseUrl = "https://example.invalid/v1",
        models = listOf("test-model"),
        defaultModel = "test-model",
    )

    private class FakeSettings(var value: AppSettings) : SettingsSource {
        override suspend fun current(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
            value = transform(value)
            return value
        }
    }

    private class FakeCredentials(
        private val apiKey: String? = "sk-test",
        private val token: String? = "tok",
    ) : CredentialSource {
        override fun getApiKey(providerId: String): String? = apiKey
        override fun getServerToken(providerId: String): String? = token
    }

    /** 假后端：按调用次序播放脚本，并记录每一次请求 */
    private class ScriptedBackend(
        private val scripts: List<List<ChatEvent>>,
    ) : ChatBackend {
        var calls = 0
            private set
        val requests = mutableListOf<ChatRequest>()
        var cancelCount = 0
            private set

        override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            val script = scripts.getOrElse(calls) { listOf(ChatEvent.Completed) }
            calls++
            script.forEach { emit(it) }
        }

        override suspend fun listModels(provider: Provider, apiKey: String, serverToken: String) =
            emptyList<String>()

        override suspend fun testConnection(provider: Provider, apiKey: String, serverToken: String) =
            "ok"

        override suspend fun diagnose(provider: Provider, apiKey: String, serverToken: String) =
            emptyList<DiagStep>()

        override fun cancelActive() {
            cancelCount++
        }
    }

    private class FakeRegistry(private val backend: ChatBackend) : BackendRegistry(
        json = Json { ignoreUnknownKeys = true }
    ) {
        override fun of(type: ProviderType): ChatBackend = backend
    }

    /** 不碰任何系统能力的工具，用来验证循环本身 */
    /** 只关心"工具面里有没有它"，执行本身不重要 */
    private class NamedTool(override val name: String) : AgentTool {
        override val description = "测试用工具"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(properties = emptyMap(), required = emptyList())

        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult.ok("ok")
    }

    private class EchoTool : AgentTool {
        override val name = "echo"
        override val description = "把输入原样返回"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf("text" to Schema.str("要回显的文本")),
            required = listOf("text"),
        )

        var executed = 0
            private set

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            executed++
            return ToolResult.ok("echo:${args.strOf("text").orEmpty()}")
        }

        override fun summarize(args: JsonObject) = args.strOf("text").orEmpty()
    }

    private class BoomTool : AgentTool {
        override val name = "boom"
        override val description = "总是抛异常"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(emptyMap())
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            throw IllegalStateException("炸了")
    }

    private fun buildEngine(
        scope: TestScope,
        settings: AppSettings,
        backend: ChatBackend,
        tools: List<AgentTool> = emptyList(),
        credentials: CredentialSource = FakeCredentials(),
    ): Pair<ChatEngine, ConversationStore> {
        // 复用 runTest 的调度器，否则 advanceUntilIdle() 覆盖不到存储层协程，断言会 flaky
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val store = ConversationStore(tmp.newFolder("conv"), json, dispatcher)
        val usage = UsageRepository(tmp.newFolder("usage"), json, dispatcher)
        val workspace = WorkspaceStore(tmp.newFolder("workspace"), dispatcher)
        val registry = ToolRegistry(
            context = ToolContext(
                appContext = null, // JVM 单测里没有 Android Context，下面这些假工具也用不到
                workspace = workspace,
                usage = usage,
                profile = { DeviceProfileDefaults.fallback },
            ),
            tools = tools,
        )
        val engine = ChatEngine(
            appScope = scope,
            settingsRepo = FakeSettings(settings),
            keyStore = credentials,
            store = store,
            usageRepo = usage,
            backends = FakeRegistry(backend),
            perfProvider = { PerfProfiles.of(PerfMode.AUTO, DeviceTier.MID) },
            toolRegistry = { registry },
            json = json,
        )
        return engine to store
    }

    private fun settingsWithProvider(): AppSettings = AppSettings(
        providers = listOf(provider),
        activeProviderId = provider.id,
    )

    // ================= 基础收发 =================

    @Test
    fun `first message on a fresh engine is not self-cancelled`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                listOf(
                    ChatEvent.Text("你"),
                    ChatEvent.Text("好"),
                    ChatEvent.Usage(TokenUsage(prompt = 5, completion = 2)),
                    ChatEvent.Completed,
                )
            )
        )
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        // 没有任何已有会话 —— 这正是全新安装后的状态
        assertThat(engine.conversation.value).isNull()

        engine.send("hello")
        advanceUntilIdle()

        assertThat(backend.calls).isEqualTo(1)
        assertThat(backend.cancelCount).isEqualTo(0)

        val messages = engine.messages.value
        assertThat(messages).hasSize(2)
        assertThat(messages[0].role).isEqualTo(Role.USER)
        assertThat(messages[0].content).isEqualTo("hello")
        assertThat(messages[1].role).isEqualTo(Role.ASSISTANT)
        assertThat(messages[1].content).isEqualTo("你好")
        assertThat(messages[1].status).isEqualTo(MessageStatus.DONE)
        assertThat(messages[1].usage?.total).isEqualTo(7)

        assertThat(engine.banner.value).isNull()
        assertThat(engine.streaming.value).isFalse()
        assertThat(engine.conversation.value).isNotNull()
    }

    @Test
    fun `system prompt follows the provider preset instead of a global default`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("ok"), ChatEvent.Completed)))
        val settings = settingsWithProvider().let { st ->
            st.copy(providers = st.providers.map { it.copy(presetId = "debug") })
        }
        val (engine, _) = buildEngine(this, settings, backend)

        engine.newConversation(settings)
        advanceUntilIdle()
        engine.send("hi")
        advanceUntilIdle()

        val prompt = backend.requests.single().systemPrompt
        // 「排障」预设的特征语句，用来证明 presetId 真的生效了
        assertThat(prompt).contains("排障工程师")
    }

    @Test
    fun `api key and model are taken from settings and credentials`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("ok"), ChatEvent.Completed)))
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("hi")
        advanceUntilIdle()

        val request = backend.requests.single()
        assertThat(request.apiKey).isEqualTo("sk-test")
        assertThat(request.model).isEqualTo("test-model")
        assertThat(request.baseUrl).isEqualTo("https://example.invalid/v1")
        assertThat(request.stream).isTrue()
    }

    @Test
    fun `missing provider reports a banner instead of failing silently`() = runTest {
        val backend = ScriptedBackend(emptyList())
        val (engine, _) = buildEngine(this, AppSettings(), backend)

        engine.send("hi")
        advanceUntilIdle()

        assertThat(backend.calls).isEqualTo(0)
        assertThat(engine.banner.value?.text).contains("还没有配置供应商")
    }

    @Test
    fun `blank default model reports a banner`() = runTest {
        val backend = ScriptedBackend(emptyList())
        val settings = AppSettings(
            providers = listOf(provider.copy(defaultModel = "", models = emptyList())),
            activeProviderId = provider.id,
        )
        val (engine, _) = buildEngine(this, settings, backend)

        engine.send("hi")
        advanceUntilIdle()

        assertThat(backend.calls).isEqualTo(0)
        assertThat(engine.banner.value?.text).contains("还没有选择模型")
    }

    @Test
    fun `failure event is surfaced on the message and the banner`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Failure(DshError.Http(401, "bad key")))))
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("hi")
        advanceUntilIdle()

        val assistant = engine.messages.value.last()
        assertThat(assistant.status).isEqualTo(MessageStatus.ERROR)
        assertThat(assistant.error).contains("401")
        assertThat(engine.banner.value?.text).contains("API Key 无效")
    }

    @Test
    fun `conversation and messages are persisted so they survive a restart`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("答复"), ChatEvent.Completed)))
        val (engine, store) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("存一下")
        advanceUntilIdle()

        val id = engine.conversation.value!!.id
        val reloaded = store.load(id)
        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.messages).hasSize(2)
        assertThat(reloaded.messages[1].content).isEqualTo("答复")
        assertThat(store.index().map(Conversation::id)).contains(id)
    }

    @Test
    fun `deleting a message rewrites the stored conversation`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("答复"), ChatEvent.Completed)))
        val (engine, store) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("删我")
        advanceUntilIdle()

        val userMessage: ChatMessage = engine.messages.value.first { it.role == Role.USER }
        engine.deleteMessage(userMessage.id)
        advanceUntilIdle()

        assertThat(engine.messages.value).hasSize(1)
        val id = engine.conversation.value!!.id
        assertThat(store.load(id)!!.messages).hasSize(1)
    }

    // ================= Agent 循环 =================

    // ---------------- 插件 hook 接线 ----------------

    @Test
    fun `whale plugin adds terseness to the system prompt`() = runTest {
        // 验证的是**接线**：插件本身对，但 ChatEngine 没把它的输出拼进请求，
        // 那用户打开开关后不会有任何变化，而单元测试仍然全绿
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(
            enabledPlugins = setOf("dsh-whale"),
            pluginsCustomized = true,
        )
        val (engine, _) = buildEngine(this, settings, backend)

        engine.send("你好")
        advanceUntilIdle()

        assertThat(backend.requests.first().systemPrompt).contains("极简")
    }

    @Test
    fun `plugins off keeps the system prompt untouched`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(
            enabledPlugins = emptySet(),
            pluginsCustomized = true,
        )
        val (engine, _) = buildEngine(this, settings, backend)

        engine.send("你好")
        advanceUntilIdle()

        assertThat(backend.requests.first().systemPrompt).doesNotContain("极简")
        assertThat(backend.requests.first().systemPrompt).doesNotContain("首轮判定")
    }

    @Test
    fun `fengshen narrows tools on the first turn then releases`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(name = "read_file", argsChunks = listOf("""{"path":"a.txt"}""")),
                toolCallScript(id = "call_2", name = "read_file", argsChunks = listOf("""{"path":"b.txt"}""")),
                listOf(ChatEvent.Text("好了"), ChatEvent.Completed),
            )
        )
        val settings = settingsWithProvider().copy(
            agentMode = AgentMode.AUTO_ALL,
            enabledPlugins = setOf("dsh-fengshen"),
            pluginsCustomized = true,
        )
        val (engine, _) = buildEngine(
            this,
            settings,
            backend,
            tools = listOf(NamedTool("read_file"), NamedTool("delete_file")),
        )

        engine.send("帮我修复这个崩溃")
        advanceUntilIdle()

        assertThat(backend.requests[0].tools.map { it.toolName() }).containsExactly("read_file")
        // 第二轮必须恢复全量 —— 一直收窄会让模型想用的工具不在清单里
        assertThat(backend.requests[1].tools.map { it.toolName() })
            .containsExactly("read_file", "delete_file")
    }

    @Test
    fun `narrowing falls back to the full set when nothing survives`() = runTest {
        // 收窄名单里的工具一个都不存在时不能把工具面清空 ——
        // 那会让模型这一轮什么都做不了，只回一段文字
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(
            agentMode = AgentMode.AUTO_ALL,
            enabledPlugins = setOf("dsh-fengshen"),
            pluginsCustomized = true,
        )
        val (engine, _) = buildEngine(this, settings, backend, tools = listOf(EchoTool()))

        engine.send("帮我修复这个崩溃")
        advanceUntilIdle()

        assertThat(backend.requests.first().tools.map { it.toolName() }).containsExactly("echo")
    }

    @Test
    fun `system prompt lists the tools the model can actually call`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL)
        val (engine, _) = buildEngine(
            this, settings, backend,
            tools = listOf(NamedTool("cloud_build"), NamedTool("read_file")),
        )

        engine.send("你好")
        advanceUntilIdle()

        val prompt = backend.requests.first().systemPrompt
        assertThat(prompt).contains("【可用工具】")
        assertThat(prompt).contains("cloud_build")
        // 没有收窄时不该出现"本轮只开放"这种限定语
        assertThat(prompt).doesNotContain("本轮只开放")
    }

    @Test
    fun `narrowed turn tells the model it is narrowed`() = runTest {
        // 真机现场：风神首轮把工具面收窄到 4 个，模型的思维链里写
        // "工具列表里没有 cloud_build，我只有 read_file/write_file/edit_file/run_script"，
        // 然后开始给用户解释"本机编不了 APK"。
        // 它看到的确实只有那几个 —— 必须让它知道"这是本轮聚焦，不是没有"。
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(
            agentMode = AgentMode.AUTO_ALL,
            enabledPlugins = setOf("dsh-fengshen"),
            pluginsCustomized = true,
        )
        val (engine, _) = buildEngine(
            this, settings, backend,
            tools = listOf(NamedTool("read_file"), NamedTool("cloud_build")),
        )

        engine.send("帮我修复这个崩溃")
        advanceUntilIdle()

        val prompt = backend.requests.first().systemPrompt
        assertThat(prompt).contains("本轮只开放")
        assertThat(prompt).contains("下一轮")
        // 只检查清单那一行：系统提示别处（agentHint）本来就提到 cloud_build
        val roster = prompt.substringAfter("【可用工具】\n").substringBefore("\n")
        assertThat(roster).contains("read_file")
        assertThat(roster).doesNotContain("cloud_build")
    }

    private fun JsonObject.toolName(): String =
        this["function"]!!.jsonObject["name"]!!.jsonPrimitive.content

    private fun toolCallScript(
        index: Int = 0,
        id: String = "call_1",
        name: String = "echo",
        argsChunks: List<String> = listOf("""{"text":"hi"}"""),
    ): List<ChatEvent> = buildList {
        add(ChatEvent.ToolCallDelta(index, id, name, null))
        argsChunks.forEach { add(ChatEvent.ToolCallDelta(index, null, null, it)) }
        add(ChatEvent.Finish("tool_calls"))
        add(ChatEvent.Completed)
    }

    @Test
    fun `agent loop executes tools and feeds results back`() = runTest {
        val echo = EchoTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(),
                listOf(ChatEvent.Text("最终答复"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL),
            backend,
            tools = listOf(echo),
        )

        engine.send("帮我回显 hi")
        advanceUntilIdle()

        // 两轮请求：第一轮模型要工具，第二轮拿到结果后给最终答复
        assertThat(backend.calls).isEqualTo(2)
        assertThat(echo.executed).isEqualTo(1)

        val messages = engine.messages.value
        // user + 第 1 步(工具) + 第 2 步(最终答复)
        assertThat(messages).hasSize(3)
        assertThat(messages[2].content).isEqualTo("最终答复")

        val toolMessage = messages[1]
        assertThat(toolMessage.stepIndex).isEqualTo(1)
        assertThat(toolMessage.toolCalls).hasSize(1)
        val call = toolMessage.toolCalls.single()
        assertThat(call.name).isEqualTo("echo")
        assertThat(call.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(call.result).isEqualTo("echo:hi")
        assertThat(call.arguments).isEqualTo("""{"text":"hi"}""")

        // 第二轮请求里必须带上 assistant.tool_calls 与 role=tool 的结果，否则协议非法
        val secondHistory = backend.requests[1].history
        assertThat(secondHistory.any { it.role == "assistant" && it.toolCalls.isNotEmpty() }).isTrue()
        val toolTurn = secondHistory.firstOrNull { it.toolCallId.isNotBlank() }
        assertThat(toolTurn).isNotNull()
        assertThat(toolTurn!!.toolCallId).isEqualTo("call_1")
        assertThat(toolTurn.content).isEqualTo("echo:hi")

        // 出站序列必须通过协议校验，否则服务端 400
        assertThat(TurnSanitizer.validate(secondHistory)).isNull()
    }

    @Test
    fun `second message in the same conversation keeps tool calls paired with results`() = runTest {
        // 复现真机上遇到的 400：
        // 第一次对话用了工具并落盘，第二次对话时历史里会带上上一轮的
        // assistant(tool_calls)——如果不同时补上配对的 role=tool 消息，请求就是非法的。
        val echo = EchoTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(),
                listOf(ChatEvent.Text("第一步好了"), ChatEvent.Completed),
                listOf(ChatEvent.Text("第二步好了"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL),
            backend,
            tools = listOf(echo),
        )

        engine.send("第一问")
        advanceUntilIdle()
        engine.send("第二问")
        advanceUntilIdle()

        assertThat(backend.calls).isEqualTo(3)

        val thirdRequest = backend.requests[2]
        val problem = TurnSanitizer.validate(thirdRequest.history)
        assertThat(problem).isNull()

        // 而且顺序必须是 assistant(tool_calls) 紧跟着对应的 tool 结果
        val idx = thirdRequest.history.indexOfFirst { it.toolCalls.isNotEmpty() }
        assertThat(idx).isAtLeast(0)
        assertThat(thirdRequest.history[idx + 1].toolCallId)
            .isEqualTo(thirdRequest.history[idx].toolCalls.first().id)
    }

    @Test
    fun `tool call arguments are concatenated from streaming fragments`() = runTest {
        val echo = EchoTool()
        // 参数被切成 3 片下发，这是真实协议的行为
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(argsChunks = listOf("""{"te""", """xt":""", """"分段"}""")),
                listOf(ChatEvent.Text("ok"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL),
            backend,
            tools = listOf(echo),
        )

        engine.send("分段参数")
        advanceUntilIdle()

        val call = engine.messages.value[1].toolCalls.single()
        assertThat(call.arguments).isEqualTo("""{"text":"分段"}""")
        assertThat(call.result).isEqualTo("echo:分段")
    }

    @Test
    fun `agent mode OFF never sends tools`() = runTest {
        val echo = EchoTool()
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("纯聊天"), ChatEvent.Completed)))
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.OFF),
            backend,
            tools = listOf(echo),
        )

        engine.send("只是聊天")
        advanceUntilIdle()

        assertThat(backend.requests.single().tools).isEmpty()
        assertThat(engine.messages.value).hasSize(2)
        assertThat(echo.executed).isEqualTo(0)
    }

    @Test
    fun `agent mode ON sends tool specs`() = runTest {
        val echo = EchoTool()
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("ok"), ChatEvent.Completed)))
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL),
            backend,
            tools = listOf(echo),
        )

        engine.send("带上工具")
        advanceUntilIdle()

        val specs = backend.requests.single().tools
        assertThat(specs).hasSize(1)
        assertThat(specs.single().toString()).contains("echo")
    }

    @Test
    fun `runaway loop is stopped by the stall detector, not by a step cap`() = runTest {
        val echo = EchoTool()
        // 后端永远返回同一个工具、同一份参数 —— 模型在原地打转
        val backend = ScriptedBackend(List(10) { toolCallScript() })
        // 故意把 maxAgentSteps 设成 3：它已经**不再**决定停止点
        val settings = settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL, maxAgentSteps = 3)
        val (engine, _) = buildEngine(this, settings, backend, tools = listOf(echo))

        engine.send("无限调用")
        advanceUntilIdle()

        // 第 1 步是新指纹（算进展），之后连续 3 步重复 → 第 4 次调用后判停滞
        assertThat(backend.calls).isEqualTo(4)
        assertThat(echo.executed).isEqualTo(4)
        assertThat(engine.banner.value?.text.orEmpty()).contains("没有产生新结果")
        assertThat(engine.streaming.value).isFalse()
    }

    @Test
    fun `step cap no longer stops a task that keeps making progress`() = runTest {
        val echo = EchoTool()
        // 每一步参数都不同 → 每步都是新指纹，属于"有进展"
        val backend = ScriptedBackend(
            List(3) { i ->
                toolCallScript(id = "call_$i", argsChunks = listOf("""{"text":"step $i"}"""))
            } + listOf(listOf(ChatEvent.Text("完成"), ChatEvent.Completed))
        )
        // maxAgentSteps = 1：旧实现会在这里就停
        val settings = settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL, maxAgentSteps = 1)
        val (engine, _) = buildEngine(this, settings, backend, tools = listOf(echo))

        engine.send("做三步")
        advanceUntilIdle()

        assertThat(echo.executed).isEqualTo(3)
        assertThat(engine.banner.value?.text.orEmpty()).isEmpty()
    }

    @Test
    fun `approval mode suspends until the user decides`() = runTest {
        val echo = EchoTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(),
                listOf(ChatEvent.Text("好的"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.ASK_EVERY_STEP),
            backend,
            tools = listOf(echo),
        )

        engine.send("需要确认")
        advanceUntilIdle()

        // 挂起在审批上：工具没执行，界面能看到待审批项
        assertThat(engine.approval.value).isNotNull()
        assertThat(engine.approval.value!!.toolName).isEqualTo("echo")
        assertThat(echo.executed).isEqualTo(0)
        assertThat(engine.messages.value[1].toolCalls.single().status)
            .isEqualTo(ToolStatus.AWAITING_APPROVAL)

        engine.resolveApproval(ApprovalDecision.ALLOW_ONCE)
        advanceUntilIdle()

        assertThat(engine.approval.value).isNull()
        assertThat(echo.executed).isEqualTo(1)
        assertThat(engine.messages.value[1].toolCalls.single().status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(engine.messages.value.last().content).isEqualTo("好的")
    }

    @Test
    fun `denied tool is reported back to the model instead of executing`() = runTest {
        val echo = EchoTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(),
                listOf(ChatEvent.Text("那算了"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.ASK_EVERY_STEP),
            backend,
            tools = listOf(echo),
        )

        engine.send("拒绝它")
        advanceUntilIdle()
        engine.resolveApproval(ApprovalDecision.DENY)
        advanceUntilIdle()

        assertThat(echo.executed).isEqualTo(0)
        assertThat(engine.messages.value[1].toolCalls.single().status).isEqualTo(ToolStatus.DENIED)
        // 拒绝结果也要回灌给模型，否则它会一直重试
        val toolTurn = backend.requests[1].history.last { it.toolCallId.isNotBlank() }
        assertThat(toolTurn.content).contains("拒绝")
    }

    @Test
    fun `read-only auto mode does not suspend on approval`() = runTest {
        val echo = EchoTool()          // READ_ONLY
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("ok"), ChatEvent.Completed)))
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_READONLY),
            backend,
            tools = listOf(echo),
        )

        engine.send("只读自动")
        advanceUntilIdle()

        assertThat(engine.approval.value).isNull()
    }

    @Test
    fun `unknown tool name is reported as failure and loop continues`() = runTest {
        val echo = EchoTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(name = "does_not_exist"),
                listOf(ChatEvent.Text("我换个方式"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL),
            backend,
            tools = listOf(echo),
        )

        engine.send("调用不存在的工具")
        advanceUntilIdle()

        val call = engine.messages.value[1].toolCalls.single()
        assertThat(call.status).isEqualTo(ToolStatus.FAILED)
        assertThat(call.result).contains("没有名为")
        // 关键：失败也要回灌，让模型有机会自我纠正，而不是整个任务挂掉
        assertThat(backend.calls).isEqualTo(2)
        assertThat(engine.messages.value.last().content).isEqualTo("我换个方式")
    }

    @Test
    fun `throwing tool does not crash the loop`() = runTest {
        val boom = BoomTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(name = "boom", argsChunks = listOf("{}")),
                listOf(ChatEvent.Text("工具炸了"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL),
            backend,
            tools = listOf(boom),
        )

        engine.send("让它炸")
        advanceUntilIdle()

        val call = engine.messages.value[1].toolCalls.single()
        assertThat(call.status).isEqualTo(ToolStatus.FAILED)
        assertThat(call.result).contains("执行异常")
        assertThat(backend.calls).isEqualTo(2)
    }

    @Test
    fun `agent steps and tool calls survive a reload from disk`() = runTest {
        val echo = EchoTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(),
                listOf(ChatEvent.Text("最终"), ChatEvent.Completed),
            )
        )
        val (engine, store) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL),
            backend,
            tools = listOf(echo),
        )

        engine.send("落盘")
        advanceUntilIdle()

        val id = engine.conversation.value!!.id
        val stored = store.load(id)!!
        assertThat(stored.messages).hasSize(3)
        assertThat(stored.messages[1].toolCalls.single().name).isEqualTo("echo")
        assertThat(stored.messages[1].toolCalls.single().status).isEqualTo(ToolStatus.SUCCESS)
    }

    @Test
    fun `chat turn for a tool result carries the tool call id`() {
        val turn = ChatTurn(role = "tool", content = "x", toolCallId = "call_9")
        assertThat(turn.toolCallId).isEqualTo("call_9")
        assertThat(turn.role).isEqualTo("tool")
    }
}
