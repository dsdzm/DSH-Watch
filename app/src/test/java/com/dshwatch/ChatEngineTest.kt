package com.dshwatch

import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.store.MemoryHealthStore
import com.dshwatch.domain.ResponseProbe
import com.dshwatch.data.store.MemoryStore
import com.dshwatch.data.store.StoredConversation
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
import com.dshwatch.domain.agent.PendingQuestion
import com.dshwatch.domain.agent.UserPromptChannel
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolRegistry
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.agent.tools.BuiltinTools
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
import com.dshwatch.domain.roleplay.MemoryCompressor
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.net.backend.BackendRegistry
import com.dshwatch.net.backend.ChatBackend
import com.dshwatch.net.backend.ChatEvent
import com.dshwatch.net.backend.ChatRequest
import com.dshwatch.net.backend.DiagStep
import com.dshwatch.net.backend.DshError
import com.dshwatch.net.backend.TurnSanitizer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        /** 第一次调用直接抛错，用来模拟"网关不支持非流式" */
        private val failFirst: Boolean = false,
    ) : ChatBackend {
        var calls = 0
            private set
        val requests = mutableListOf<ChatRequest>()
        var cancelCount = 0
            private set
        private var failedOnce = false

        override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            if (failFirst && !failedOnce) {
                // 只失败一次：重试那次仍然播放脚本第 0 条
                failedOnce = true
                throw java.io.IOException("HTTP 400 Bad Request")
            }
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

    /** 模拟 attach_file / device_screenshot：结果里带一张图 */
    private class ShotTool : AgentTool {
        override val name = "shot"
        override val description = "取一张图"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(properties = emptyMap())

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            ToolResult.withImages("已截屏", listOf("/tmp/fake-shot.png", "  "))

        override fun summarize(args: JsonObject) = "截屏"
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
        promptChannel: UserPromptChannel? = null,
    ): Pair<ChatEngine, ConversationStore> {
        // 复用 runTest 的调度器，否则 advanceUntilIdle() 覆盖不到存储层协程，断言会 flaky
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        // 无参 newFolder：同一个用例里可能构造两个引擎（共享通道的回归测试），
        // 固定名字会撞车抛 IOException
        val store = ConversationStore(tmp.newFolder(), json, dispatcher)
        val usage = UsageRepository(tmp.newFolder(), json, dispatcher)
        val workspace = WorkspaceStore(tmp.newFolder(), dispatcher)
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
            promptChannel = promptChannel,
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
    fun `imported manifest plugin appends to the system prompt`() = runTest {
        // 用户装进来的插件只有 prompt.append：开启后必须真的出现在请求里。
        // 这条和 whale 那条是同一个目的 —— 只测插件的 compose 不测接线，
        // 「开关拨了、请求没变」这种故障会漏过去。
        val manifest = com.dshwatch.domain.plugin.PluginManifest(
            id = "dsh-plugin-boost",
            name = "Boost",
            prompt = com.dshwatch.domain.plugin.PromptPart(
                append = "<dsh_boost>不要预告、不要复述、直接做。</dsh_boost>",
            ),
        )
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(
            enabledPlugins = setOf(manifest.id),
            pluginsCustomized = true,
            importedPlugins = listOf(manifest),
        )
        val (engine, _) = buildEngine(this, settings, backend)

        engine.send("你好")
        advanceUntilIdle()

        assertThat(backend.requests.first().systemPrompt).contains("不要预告")
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
            // 两个都用常驻工具：验证「收窄 → 放开」时不要混进插件之外的机制
            tools = listOf(NamedTool("read_file"), NamedTool("todo_write")),
        )

        engine.send("帮我修复这个崩溃")
        advanceUntilIdle()

        assertThat(backend.requests[0].tools.map { it.toolName() }).containsExactly("read_file")
        // 第二轮必须恢复全量 —— 一直收窄会让模型想用的工具不在清单里
        assertThat(backend.requests[1].tools.map { it.toolName() })
            .containsExactly("read_file", "todo_write")
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
    fun `顶栏切换的模型会真的用在请求里`() = runTest {
        /*
         * 会话级模型（overrideModel → conv.model）以前只被界面读，runSend 完全不看；
         * 更糟的是发送时 `conv.copy(model = model)` 会把它覆写回供应商默认值 ——
         * 顶栏显示换了、请求里还是旧模型，发完连显示都跟着退回去。
         * 用户看到的就是「表面上换了」。
         */
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(
            providers = listOf(provider.copy(models = listOf("test-model", "other-model"))),
        )
        val (engine, _) = buildEngine(this, settings, backend)

        engine.overrideModel("other-model")
        engine.send("你好")
        advanceUntilIdle()

        assertThat(backend.requests.first().model).isEqualTo("other-model")
        // 发完之后会话里记的也必须是它，否则下一轮顶栏又跳回默认模型
        assertThat(engine.conversation.value?.model).isEqualTo("other-model")
    }

    @Test
    fun `会话里留着的别家模型名会退回当前供应商的默认值`() = runTest {
        /*
         * 换供应商之后，旧供应商的模型名打过去只会换回 404 model not found。
         * 采用会话模型的前提是它属于当前这家。
         */
        val second = provider.copy(id = "p2", name = "Second")
        val settings = AppSettings(
            providers = listOf(provider.copy(models = listOf("test-model", "other-model")), second),
            activeProviderId = second.id,
        )
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val (engine, _) = buildEngine(this, settings, backend)

        engine.overrideModel("other-model")
        engine.send("你好")
        advanceUntilIdle()

        assertThat(backend.requests.first().model).isEqualTo("test-model")
    }

    @Test
    fun `共享提问通道时后构造的引擎不会抢走问题`() = runTest {
        /*
         * 容器里有两个引擎（主引擎 + QQ 桥接），而 ToolRegistry 的工具只认识
         * **一个**全局提问通道。以前两边都往同一个通道注册 handler，后构造的
         * 把先注册的覆盖掉 —— 主引擎调 ask_user 时，问题进了 QQ 引擎的状态流，
         * 界面永远不弹窗，五分钟后返回「用户没有回答」。
         *
         * 真机表现：ask_user 没有选择页面、工具没有弹窗。
         */
        val channel = UserPromptChannel()
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider()
        val (main, _) = buildEngine(this, settings, backend, promptChannel = channel)
        val (second, _) = buildEngine(this, settings, backend, promptChannel = channel)

        val job = launch {
            channel.ask(PendingQuestion(callId = "", question = "要不要继续"))
        }
        // 不能用 advanceUntilIdle：它会把 5 分钟的超时一起推完，问题刚出现就被清掉
        runCurrent()

        assertThat(main.question.value?.question).isEqualTo("要不要继续")
        assertThat(second.question.value).isNull()

        job.cancel()
    }

    @Test
    fun `编程场景不会漏掉 ask_user，也不会带上养鱼工具`() = runTest {
        /*
         * 场景过滤是上一版加进来的：`onlyInScene` 只让某个工具出现在一个场景里。
         * 它一旦写错（比如把"没有场景限制"当成"不在任何场景"），
         * 后果是编程场景里大批工具无声消失 —— 用户看到的是"ask_user 不能用了"。
         *
         * 钉住两头：常规工具必须还在，养鱼工具必须被剔掉。
         */
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(
            agentMode = AgentMode.AUTO_ALL,
            useScene = Scenes.CODING,
        )
        val (engine, _) = buildEngine(
            this, settings, backend,
            tools = listOf(NamedTool("ask_user"), BuiltinTools.ScheduleProactiveTool),
        )

        engine.send("你好")
        advanceUntilIdle()

        assertThat(backend.requests.first().tools.map { it.toolName() })
            .containsExactly("ask_user")
    }

    @Test
    fun `长尾工具也不用先取，第一轮就随请求下发`() = runTest {
        // 1.20 的教训：把长尾工具藏进目录、让模型先 tool_search，
        // 真机上模型会卡在取工具这一步 —— 反复搜，或者搜到了却以为同一轮能用。
        // 现在必须一次全给，且提示里不能再出现目录。
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("hi"), ChatEvent.Completed)))
        val settings = settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL)
        val (engine, _) = buildEngine(
            this, settings, backend,
            tools = listOf(
                NamedTool("read_file"),
                NamedTool("delete_file"),
                NamedTool("cloud_build"),
            ),
        )

        engine.send("你好")
        advanceUntilIdle()

        assertThat(backend.requests.first().tools.map { it.toolName() })
            .containsExactly("read_file", "delete_file", "cloud_build")
        assertThat(backend.requests.first().systemPrompt).doesNotContain("工具目录")
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

    // ================= 多版本 / 续写 / 编辑 =================

    private fun oneTurn(text: String) = listOf(
        ChatEvent.Text(text),
        ChatEvent.Usage(TokenUsage(prompt = 3, completion = 2)),
        ChatEvent.Completed,
    )

    @Test
    fun `regenerate keeps the previous answer as a version`() = runTest {
        val backend = ScriptedBackend(listOf(oneTurn("第一版"), oneTurn("第二版")))
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("问题")
        advanceUntilIdle()
        val id = engine.messages.value.last().id
        assertThat(engine.messages.value.last().content).isEqualTo("第一版")

        engine.regenerate(id)
        advanceUntilIdle()

        // 列表还是一条提问 + 一条回答：版本不该改变「这个会话说了几句话」
        assertThat(engine.messages.value).hasSize(2)
        val answer = engine.messages.value.last()
        assertThat(answer.content).isEqualTo("第二版")
        assertThat(answer.versions).hasSize(2)
        assertThat(answer.versions[0].content).isEqualTo("第一版")
        assertThat(answer.versionIndex).isEqualTo(1)

        // 切回第一版：内容、用量一起换，消息位置不动
        engine.switchVersion(answer.id, 0)
        advanceUntilIdle()
        val shown = engine.messages.value.last()
        assertThat(shown.content).isEqualTo("第一版")
        assertThat(shown.versionIndex).isEqualTo(0)
        assertThat(shown.usage?.completion).isEqualTo(2)
    }

    @Test
    fun `continue sends the half answer as the last assistant turn`() = runTest {
        val backend = ScriptedBackend(listOf(oneTurn("前半句，"), oneTurn("后半句。")))
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("讲个故事")
        advanceUntilIdle()
        val first = engine.messages.value.last()

        engine.continueGeneration(first.id)
        advanceUntilIdle()

        // 续写的关键：模型看到的最后一句话是自己说的半截内容
        val history = backend.requests.last().history
        assertThat(history.last().role).isEqualTo("assistant")
        assertThat(history.last().content).isEqualTo("前半句，")

        // 结果拼回同一条消息，不是新冒出来一条
        assertThat(engine.messages.value).hasSize(2)
        val merged = engine.messages.value.last()
        assertThat(merged.id).isEqualTo(first.id)
        assertThat(merged.content).isEqualTo("前半句，后半句。")
    }

    @Test
    fun `continue refuses when the answer is not the last message`() = runTest {
        val backend = ScriptedBackend(listOf(oneTurn("第一轮"), oneTurn("第二轮")))
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("第一问")
        advanceUntilIdle()
        val firstAnswer = engine.messages.value.last().id
        engine.send("第二问")
        advanceUntilIdle()

        engine.continueGeneration(firstAnswer)
        advanceUntilIdle()

        assertThat(engine.banner.value?.text).contains("只能续写最后一条")
        assertThat(backend.calls).isEqualTo(2)
    }

    @Test
    fun `edit replaces the prompt and drops everything after it`() = runTest {
        val backend = ScriptedBackend(listOf(oneTurn("旧回答"), oneTurn("新回答")))
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("旧问题")
        advanceUntilIdle()
        val userId = engine.messages.value.first().id

        engine.editMessage(userId, "新问题")
        advanceUntilIdle()

        val messages = engine.messages.value
        assertThat(messages).hasSize(2)
        assertThat(messages[0].id).isEqualTo(userId)
        assertThat(messages[0].content).isEqualTo("新问题")
        assertThat(messages[1].content).isEqualTo("新回答")
        // 请求里的历史也只能有改写后的那一句
        assertThat(backend.requests.last().history.first().content).isEqualTo("新问题")
    }

    // ================= 服务端标题 =================

    private fun deepSeekSettings(): AppSettings {
        val ds = provider.copy(
            id = "ds",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
        )
        return AppSettings(providers = listOf(ds), activeProviderId = ds.id)
    }

    @Test
    fun `title is requested on the first turn only`() = runTest {
        val backend = ScriptedBackend(listOf(oneTurn("一"), oneTurn("二")))
        val (engine, _) = buildEngine(this, deepSeekSettings(), backend)

        engine.send("第一个问题")
        advanceUntilIdle()
        assertThat(backend.requests.first().titleRequested).isTrue()

        engine.send("第二个问题")
        advanceUntilIdle()
        // 第二轮再要标题就是白花 token
        assertThat(backend.requests.last().titleRequested).isFalse()
    }

    @Test
    fun `non deepseek provider never asks for a title`() = runTest {
        val backend = ScriptedBackend(listOf(oneTurn("答")))
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("问题")
        advanceUntilIdle()

        assertThat(backend.requests.first().titleRequested).isFalse()
    }

    // ================= 思考模式的思维链回传 =================

    /**
     * DeepSeek 的 thinking 模式在多轮对话里会把整单请求 400 掉：
     * `The 'reasoning_content' in the thinking mode must be passed back to the API.`
     * 所以历史里的 assistant 消息必须带着上一轮的思维链原文。
     */
    @Test
    fun `deepseek history carries the previous reasoning back`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                listOf(
                    ChatEvent.Reasoning("先拆一下问题"),
                    ChatEvent.Text("答案一"),
                    ChatEvent.Completed,
                ),
                oneTurn("答案二"),
            )
        )
        val (engine, _) = buildEngine(this, deepSeekSettings(), backend)

        engine.send("第一问")
        advanceUntilIdle()
        engine.send("第二问")
        advanceUntilIdle()

        val second = backend.requests[1]
        assertThat(second.echoReasoning).isTrue()
        val answer = second.history.last { it.role == "assistant" }
        assertThat(answer.content).isEqualTo("答案一")
        assertThat(answer.reasoning).isEqualTo("先拆一下问题")
    }

    @Test
    fun `other providers do not send reasoning back`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                listOf(
                    ChatEvent.Reasoning("内部推理"),
                    ChatEvent.Text("答案一"),
                    ChatEvent.Completed,
                ),
                oneTurn("答案二"),
            )
        )
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        engine.send("第一问")
        advanceUntilIdle()
        engine.send("第二问")
        advanceUntilIdle()

        val second = backend.requests[1]
        assertThat(second.echoReasoning).isFalse()
        // 字段仍然不发：非 DeepSeek 网关见到未知的 reasoning_content 会 400
        val assistantTurns = second.history.filter { it.role == "assistant" }
        assertThat(assistantTurns).isNotEmpty()
        assistantTurns.forEach { assertThat(it.reasoning).isEmpty() }
    }

    @Test
    fun `server title updates the conversation`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                listOf(
                    ChatEvent.Title("周报里那三个指标"),
                    ChatEvent.Text("好的"),
                    ChatEvent.Completed,
                ),
            ),
        )
        val (engine, _) = buildEngine(this, deepSeekSettings(), backend)

        engine.send("帮我看下这份周报，第三段那几个指标怎么算")
        advanceUntilIdle()

        assertThat(engine.conversation.value?.title).isEqualTo("周报里那三个指标")
    }

    // ================= 插件预设 =================

    private val pluginPresetJson = """
        {
          "schema": 3,
          "id": "dsh-plugin-purge",
          "name": "Purge 预设",
          "presets": [
            {
              "id": "unrestricted",
              "name": "连续执行",
              "systemPrompt": "你处在连续执行模式。",
              "opening": "已进入连续执行模式，直接说做什么。"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `plugin preset opening is inserted into a new conversation`() = runTest {
        val manifest = (com.dshwatch.domain.plugin.PluginLoader.parse(pluginPresetJson)
            as com.dshwatch.domain.plugin.PluginLoader.Parsed.Ok).manifest
        val tagged = provider.copy(presetId = "dsh-plugin-purge/unrestricted")
        val settings = AppSettings(
            providers = listOf(tagged),
            activeProviderId = tagged.id,
            enabledPlugins = setOf(manifest.id),
            pluginsCustomized = true,
            importedPlugins = listOf(manifest),
        )
        val (engine, _) = buildEngine(this, settings, ScriptedBackend(emptyList()))

        engine.startNew(settings)

        val messages = engine.messages.value
        assertThat(messages).hasSize(1)
        assertThat(messages[0].role).isEqualTo(Role.ASSISTANT)
        assertThat(messages[0].content).contains("连续执行模式")
    }

    @Test
    fun `no opening when the plugin is disabled`() = runTest {
        val manifest = (com.dshwatch.domain.plugin.PluginLoader.parse(pluginPresetJson)
            as com.dshwatch.domain.plugin.PluginLoader.Parsed.Ok).manifest
        val tagged = provider.copy(presetId = "dsh-plugin-purge/unrestricted")
        val settings = AppSettings(
            providers = listOf(tagged),
            activeProviderId = tagged.id,
            // 插件关着：预设失效，开场白也不该出现
            enabledPlugins = emptySet(),
            pluginsCustomized = true,
            importedPlugins = listOf(manifest),
        )
        val (engine, _) = buildEngine(this, settings, ScriptedBackend(emptyList()))

        engine.startNew(settings)

        assertThat(engine.messages.value).isEmpty()
    }

    // ================= 长期记忆压缩 =================

    /**
     * 用户真实反馈的回归：聊到 12:20 时突然翻出 12:00 的话来回复，
     * 12:00–12:20 的对话像凭空消失。
     *
     * 根因是压缩完成时把切点推到了「当前末尾」，而摘要只覆盖发请求那一刻的快照。
     * 这里用一道闸门按住摘要请求，模拟「请求还没回来，用户又聊了一轮」。
     */
    @Test
    fun `压缩只切到摘要覆盖的末尾，不吃掉期间新聊的内容`() = runTest {
        val probeGate = CompletableDeferred<Unit>()
        var streams = 0
        val backend = object : ChatBackend {
            override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
                if (streams++ == 0) {
                    probeGate.await()
                    emit(ChatEvent.Text("摘要：聊了几句天气"))
                } else {
                    emit(ChatEvent.Text("嗯"))
                }
                emit(ChatEvent.Completed)
            }

            override suspend fun listModels(provider: Provider, apiKey: String, serverToken: String) =
                emptyList<String>()

            override suspend fun testConnection(provider: Provider, apiKey: String, serverToken: String) =
                "ok"

            override suspend fun diagnose(provider: Provider, apiKey: String, serverToken: String) =
                emptyList<DiagStep>()

            override fun cancelActive() {}
        }

        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, backend)
        engine.newConversation(settings, scene = Scenes.TAVERN)
        val conv = engine.conversation.value!!

        val history = (1..6).map { i ->
            ConversationStore.newMessage(
                role = if (i % 2 == 1) Role.USER else Role.ASSISTANT,
                content = "第 $i 条",
            )
        }
        store.save(StoredConversation(conv, history))
        engine.open(conv.id)
        assertThat(engine.snapshotMessages()).hasSize(6)

        val memDir = tmp.newFolder("memory")
        val memory = MemoryStore(
            workspaceRoot = { memDir },
            io = UnconfinedTestDispatcher(testScheduler),
        )
        val compressor = MemoryCompressor(
            scope = this,
            engine = engine,
            memory = memory,
            loadCard = { null },
        )
        compressor.maybeCompress(
            conversationId = conv.id,
            cardId = "card1",
            scene = Scenes.TAVERN,
            contextWindow = 8000,
            force = true,
        )
        advanceUntilIdle() // 跑到 probe 挂起

        // 摘要还在路上，用户又聊了一轮
        engine.send("刚说到哪儿了")
        advanceUntilIdle()
        assertThat(engine.snapshotMessages()).hasSize(8)

        probeGate.complete(Unit)
        advanceUntilIdle()

        // 切点必须停在压缩时的 6 条，而不是回来时的 8 条
        assertThat(engine.contextStart()).isEqualTo(6)
        assertThat(engine.conversation.value?.tavernTurn).isEqualTo(6)
    }

    /** 压缩期间切到别的会话，绝不能把切点写进那个会话 */
    @Test
    fun `会话已切换时不写切点`() = runTest {
        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, ScriptedBackend(emptyList()))
        engine.newConversation(settings, scene = Scenes.TAVERN)
        val conv = engine.conversation.value!!
        store.save(
            StoredConversation(
                conv,
                (1..6).map { ConversationStore.newMessage(Role.USER, "第 $it 条") },
            )
        )
        engine.open(conv.id)

        engine.markContextCut(upTo = 6, onlyIfConversation = "another-conversation")
        advanceUntilIdle()

        assertThat(engine.contextStart()).isEqualTo(0)
        assertThat(engine.conversation.value?.tavernTurn).isEqualTo(0)
    }

    /** 不传下标的老用法（手动清上下文）仍然切到当前末尾 */
    @Test
    fun `不传下标的切点仍然切到当前末尾`() = runTest {
        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, ScriptedBackend(emptyList()))
        engine.newConversation(settings, scene = Scenes.TAVERN)
        val conv = engine.conversation.value!!
        store.save(
            StoredConversation(
                conv,
                (1..6).map { ConversationStore.newMessage(Role.USER, "第 $it 条") },
            )
        )
        engine.open(conv.id)

        engine.markContextCut()
        advanceUntilIdle()

        assertThat(engine.contextStart()).isEqualTo(6)
    }

    /**
     * 摘要原文超出预算时，只能压「最早的一段」。
     *
     * 以前是取前 12000 字、切点却推到全部末尾 —— 后面那些内容既没进摘要
     * 又被切掉，长会话里照样会「突然忘掉最近一段」。
     */
    @Test
    fun `摘要超预算时切点不越过已摘要的范围`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("摘要"), ChatEvent.Completed)))
        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, backend)
        engine.newConversation(settings, scene = Scenes.TAVERN)
        val conv = engine.conversation.value!!

        val history = (1..40).map { i ->
            ConversationStore.newMessage(
                role = if (i % 2 == 1) Role.USER else Role.ASSISTANT,
                content = "第 $i 条 " + "字".repeat(1000),
            )
        }
        store.save(StoredConversation(conv, history))
        engine.open(conv.id)

        val memDir = tmp.newFolder("memory-over-budget")
        val memory = MemoryStore(
            workspaceRoot = { memDir },
            io = UnconfinedTestDispatcher(testScheduler),
        )
        val compressor = MemoryCompressor(
            scope = this,
            engine = engine,
            memory = memory,
            loadCard = { null },
        )
        compressor.maybeCompress(
            conversationId = conv.id,
            cardId = "card1",
            scene = Scenes.TAVERN,
            contextWindow = 128_000,
            force = true,
        )
        advanceUntilIdle()

        // 12000 字预算、每条约 1008 字 → 只能覆盖前 11~12 条，绝不能是 40
        val cut = engine.contextStart()
        assertThat(cut).isLessThan(40)
        assertThat(cut).isAtLeast(2)
    }

    /**
     * 养鱼聊过门槛之后，**自动**判定必须真的把记忆写下来。
     *
     * 之前的用例全都传了 `force = true`，等于跳过了「该不该压」这一段：
     * 门槛算错、尾巴取错、会话 id 对不上，任何一种都不会被测出来。
     * 用户反馈「聊了八十句还是没有记忆」，正是这一段。
     */
    @Test
    fun `养鱼聊满门槛后自动写入记忆`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                listOf(
                    ChatEvent.Text("[-] 他怕黑，睡觉要留一盏灯"),
                    ChatEvent.Completed,
                )
            )
        )
        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, backend)
        val conv = engine.startNew(settings, Scenes.FISH, "builtin-tavern-feiyu", "大肥鱼")

        val history = (1..13).map { i ->
            ConversationStore.newMessage(
                role = if (i % 2 == 1) Role.USER else Role.ASSISTANT,
                content = "第 $i 句",
            )
        }
        store.save(StoredConversation(conv, history))
        engine.open(conv.id)
        assertThat(engine.snapshotMessages()).hasSize(13)

        val memDir = tmp.newFolder("memory-auto")
        val memory = MemoryStore(
            workspaceRoot = { memDir },
            io = UnconfinedTestDispatcher(testScheduler),
        )
        val healthDir = tmp.newFolder("health-auto")
        val health = MemoryHealthStore(
            workspaceRoot = { healthDir },
            io = UnconfinedTestDispatcher(testScheduler),
            json = json,
        )
        val compressor = MemoryCompressor(
            scope = this,
            engine = engine,
            memory = memory,
            loadCard = { null },
            health = health,
        )
        // 不传 force：走真实的门槛判定
        compressor.maybeCompress(
            conversationId = conv.id,
            cardId = "builtin-tavern-feiyu",
            scene = Scenes.FISH,
            contextWindow = 8000,
        )
        advanceUntilIdle()

        assertThat(backend.calls).isEqualTo(1) // 确实发了一次摘要请求
        assertThat(memory.entries("builtin-tavern-feiyu")).isNotEmpty()
        assertThat(engine.contextStart()).isGreaterThan(0)
        // 体检记录要落到 written，界面才说得清"上次写了几条"
        assertThat(health.read()?.outcome)
            .isEqualTo(com.dshwatch.data.store.MemoryHealth.WRITTEN)
        assertThat(health.read()?.written ?: 0).isGreaterThan(0)
    }

    /**
     * 手动点「立即整理」时，界面必须**当场拿到结果**。
     *
     * 旧写法是 `maybeCompress` + `delay(5000)` 再读一遍文件：网络慢就读到空，
     * 界面上却还写着「正在整理」—— 用户看到的就是「记忆整理一直卡住」。
     * 这条用例钉住返回值：成功要说写了几条，失败要说卡在哪。
     */
    @Test
    fun `手动整理同步返回写了几条`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                listOf(
                    ChatEvent.Text("[-] 他怕黑，睡觉要留一盏灯"),
                    ChatEvent.Completed,
                )
            )
        )
        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, backend)
        val conv = engine.startNew(settings, Scenes.FISH, "builtin-tavern-feiyu", "大肥鱼")

        val history = (1..13).map { i ->
            ConversationStore.newMessage(
                role = if (i % 2 == 1) Role.USER else Role.ASSISTANT,
                content = "第 $i 句",
            )
        }
        store.save(StoredConversation(conv, history))
        engine.open(conv.id)

        val memDir = tmp.newFolder("manual-ok")
        val memory = MemoryStore(
            workspaceRoot = { memDir },
            io = UnconfinedTestDispatcher(testScheduler),
        )
        val compressor = MemoryCompressor(
            scope = this,
            engine = engine,
            memory = memory,
            loadCard = { null },
        )

        val res = compressor.compressNow(
            conversationId = conv.id,
            cardId = "builtin-tavern-feiyu",
            scene = Scenes.FISH,
            contextWindow = 8000,
        )

        assertThat(res.ok).isTrue()
        assertThat(res.added).isGreaterThan(0)
        // 覆盖句数也要报出来：用户能对上"我聊了十三句，她说整理了十三句"
        assertThat(res.covered).isEqualTo(13)
        assertThat(memory.entries("builtin-tavern-feiyu")).isNotEmpty()
    }

    /** 内容太少时不是"失败"，而是明确说清楚为什么没整理 —— 含具体句数 */
    @Test
    fun `内容太少时给出可读的原因`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("x"), ChatEvent.Completed)))
        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, backend)
        val conv = engine.startNew(settings, Scenes.FISH, "builtin-tavern-feiyu", "大肥鱼")
        store.save(
            StoredConversation(
                conv,
                listOf(ConversationStore.newMessage(role = Role.USER, content = "在吗")),
            )
        )
        engine.open(conv.id)

        val memDir = tmp.newFolder("manual-few")
        val compressor = MemoryCompressor(
            scope = this,
            engine = engine,
            memory = MemoryStore(
                workspaceRoot = { memDir },
                io = UnconfinedTestDispatcher(testScheduler),
            ),
            loadCard = { null },
        )
        val res = compressor.compressNow(
            conversationId = conv.id,
            cardId = "builtin-tavern-feiyu",
            scene = Scenes.FISH,
            contextWindow = 8000,
        )

        assertThat(res.ok).isFalse()
        assertThat(res.message).contains("太少")
        assertThat(backend.calls).isEqualTo(0) // 不该为一个"太少"白花一次请求
    }

    /**
     * 后端把 401 报成 `ChatEvent.Failure`，不是抛异常。
     *
     * 这条事件以前被 probe 静默丢掉，于是"Key 无效"和"模型没说话"在界面上
     * 是同一句话：「模型没有返回内容」。用户拿这句话没法排查任何东西。
     */
    @Test
    fun `后端拒绝时 probe 抛出真正的原因`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                listOf(
                    ChatEvent.Failure(DshError.Http(401, "{\"error\":\"invalid api key\"}")),
                    ChatEvent.Completed,
                )
            )
        )
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        val err = runCatching {
            engine.probe(ResponseProbe(systemPrompt = "s", userText = "u"))
        }.exceptionOrNull()

        assertThat(err).isInstanceOf(DshError::class.java)
        assertThat((err as DshError).friendly).contains("401")
    }

    /**
     * 工具取到的图片必须作为一条 user 消息进下一轮请求。
     *
     * 复现的问题：设备操控能读控件文字，却看不见画面；模型只能请人描述屏幕。
     * attach_file / device_screenshot 把图交给引擎之后，如果这里不注入，
     * 模型拿到的只有一句「图在 /xxx.png」—— 等于让它对着路径想象。
     */
    @Test
    fun `工具取到的图片作为一条用户消息进下一轮`() = runTest {
        val shot = ShotTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(name = "shot", argsChunks = listOf("{}")),
                listOf(ChatEvent.Text("看到了"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(
            this,
            settingsWithProvider().copy(agentMode = AgentMode.AUTO_ALL),
            backend,
            tools = listOf(shot),
        )

        engine.send("看看这屏")
        advanceUntilIdle()

        val history = backend.requests[1].history
        val withImage = history.firstOrNull { it.images.isNotEmpty() }
        assertThat(withImage).isNotNull()
        assertThat(withImage!!.role).isEqualTo("user")
        // 空串要在 ToolResult 里就被过滤掉，不能把 "" 送进请求
        assertThat(withImage.images).containsExactly("/tmp/fake-shot.png")
        // 协议校验仍然要过：多出来的这条 user 消息不能让出站序列变成非法
        assertThat(TurnSanitizer.validate(history)).isNull()
    }

    /** 模型读不了图时不要白传，而且要明确告诉用户 —— 否则表现为「怎么答非所问」 */
    @Test
    fun `不支持视觉的模型不会收到工具图片`() = runTest {
        val shot = ShotTool()
        val backend = ScriptedBackend(
            listOf(
                toolCallScript(name = "shot", argsChunks = listOf("{}")),
                listOf(ChatEvent.Text("好"), ChatEvent.Completed),
            )
        )
        val textOnly = settingsWithProvider().copy(
            agentMode = AgentMode.AUTO_ALL,
            providers = listOf(provider.copy(visionInput = false)),
        )
        val (engine, _) = buildEngine(this, textOnly, backend, tools = listOf(shot))

        engine.send("看看这屏")
        advanceUntilIdle()

        // 图不发出去，但要让用户知道：横幅上写明「当前模型读不了图」
        assertThat(backend.requests[1].history.none { it.images.isNotEmpty() }).isTrue()
        assertThat(engine.banner.value?.text.orEmpty()).contains("读不了图")
    }

    /** 摘要请求必须用调用方指定的模型，而不是供应商默认的那只 */
    @Test
    fun `摘要请求使用指定的模型`() = runTest {
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("ok"), ChatEvent.Completed)))
        val settings = settingsWithProvider()
        val (engine, _) = buildEngine(this, settings, backend)

        val out = engine.probe(
            ResponseProbe(systemPrompt = "s", userText = "u"),
            modelOverride = "other-model",
        )

        assertThat(out).isEqualTo("ok")
        assertThat(backend.requests.single().model).isEqualTo("other-model")
    }

    /**
     * 没指定模型时，摘要必须跟着**会话自己的**模型走。
     *
     * 用户报的就是这件事：聊天一切正常，记忆页却显示「模型的 API 或模型名不对」。
     * 因为顶栏切模型写的是 `conv.model`，而摘要以前只看 `provider.defaultModel` ——
     * 两个值不一样时，摘要打到一个用户根本没在用的模型名上。
     */
    @Test
    fun `摘要跟着会话自己的模型走`() = runTest {
        val twoModels = provider.copy(
            models = listOf("test-model", "glm-x"),
            defaultModel = "test-model",
        )
        val settings = AppSettings(
            providers = listOf(twoModels),
            activeProviderId = twoModels.id,
        )
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Text("ok"), ChatEvent.Completed)))
        val (engine, _) = buildEngine(this, settings, backend)

        engine.startNew(settings, Scenes.TAVERN, "card-1", "小卡")
        engine.overrideModel("glm-x")

        engine.probe(ResponseProbe(systemPrompt = "s", userText = "u"))

        assertThat(backend.requests.single().model).isEqualTo("glm-x")
    }

    /** 没选模型时不许静默返回空串：一句话说清楚，也别白花一次请求 */
    @Test
    fun `供应商没选模型时明确报错且不发请求`() = runTest {
        val bare = provider.copy(models = emptyList(), defaultModel = "")
        val settings = AppSettings(providers = listOf(bare), activeProviderId = bare.id)
        val backend = ScriptedBackend(emptyList())
        val (engine, _) = buildEngine(this, settings, backend)

        val err = runCatching {
            engine.probe(ResponseProbe(systemPrompt = "s", userText = "u"))
        }.exceptionOrNull()

        assertThat(err).isInstanceOf(DshError.BadConfig::class.java)
        assertThat((err as DshError).describeForUser()).contains("没有选择模型")
        assertThat(backend.calls).isEqualTo(0)
    }

    /** 摘要为空时，提示里要点名**哪个模型**没说话 —— 只说"模型名不对"等于没说 */
    @Test
    fun `摘要为空时提示里带上模型名`() = runTest {
        // 两次尝试都只回一个 Completed，一个字都没有
        val backend = ScriptedBackend(listOf(listOf(ChatEvent.Completed)))
        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, backend)
        val conv = engine.startNew(settings, Scenes.FISH, "builtin-tavern-feiyu", "大肥鱼")
        store.save(
            StoredConversation(
                conv,
                (1..13).map { i ->
                    ConversationStore.newMessage(
                        role = if (i % 2 == 1) Role.USER else Role.ASSISTANT,
                        content = "第 $i 句",
                    )
                },
            )
        )
        engine.open(conv.id)

        val memDir = tmp.newFolder("blank-summary")
        val compressor = MemoryCompressor(
            scope = this,
            engine = engine,
            memory = MemoryStore(
                workspaceRoot = { memDir },
                io = UnconfinedTestDispatcher(testScheduler),
            ),
            loadCard = { null },
        )

        val res = compressor.compressNow(
            conversationId = conv.id,
            cardId = "builtin-tavern-feiyu",
            scene = Scenes.FISH,
            contextWindow = 8000,
        )

        assertThat(res.ok).isFalse()
        assertThat(res.message).contains("test-model")
        assertThat(res.message).contains("Test")
    }

    /**
     * 网关只认流式时，摘要必须还能拿到内容。
     *
     * 这类中转很常见：聊天一直好使（走流式），而记忆整理、每日动态、标题
     * 这些"后台一次性请求"全部返回 400 —— 用户看到的就是「聊了八十句，
     * 一条记忆都没有」，还查不出错在哪。
     */
    @Test
    fun `非流式摘要失败时改用流式重试`() = runTest {
        val backend = ScriptedBackend(
            listOf(listOf(ChatEvent.Text("他怕黑"), ChatEvent.Completed)),
            failFirst = true,
        )
        val settings = settingsWithProvider()
        val (engine, _) = buildEngine(this, settings, backend)

        val out = engine.probe(ResponseProbe(systemPrompt = "s", userText = "u"))

        assertThat(out).isEqualTo("他怕黑")
        assertThat(backend.requests).hasSize(2)
        assertThat(backend.requests[0].stream).isFalse()
        assertThat(backend.requests[1].stream).isTrue()
    }

    /** 养鱼没到门槛就不该花钱请求 */
    @Test
    fun `养鱼没到门槛不发摘要请求`() = runTest {
        val backend = ScriptedBackend(emptyList())
        val settings = settingsWithProvider()
        val (engine, store) = buildEngine(this, settings, backend)
        val conv = engine.startNew(settings, Scenes.FISH, "builtin-tavern-feiyu", "大肥鱼")
        store.save(
            StoredConversation(
                conv,
                (1..5).map { ConversationStore.newMessage(Role.USER, "第 $it 句") },
            )
        )
        engine.open(conv.id)

        val memDir = tmp.newFolder("memory-skip")
        val memory = MemoryStore(
            workspaceRoot = { memDir },
            io = UnconfinedTestDispatcher(testScheduler),
        )
        MemoryCompressor(
            scope = this,
            engine = engine,
            memory = memory,
            loadCard = { null },
        ).maybeCompress(
            conversationId = conv.id,
            cardId = "builtin-tavern-feiyu",
            scene = Scenes.FISH,
            contextWindow = 8000,
        )
        advanceUntilIdle()

        assertThat(backend.calls).isEqualTo(0)
        assertThat(memory.entries("builtin-tavern-feiyu")).isEmpty()
    }

    /**
     * 「每个模型都一样，全说没有返回内容」—— 这是思考型模型把额度吃光的样子。
     *
     * 后台请求只给几百个 token，模型把额度全花在思维链上，`content` 是空的、
     * `finish_reason` 是 `length`。以前这些信息被丢掉，只能报一句"模型名不对"，
     * 用户换十个模型都一样。现在要：放大预算重试一次，并在提示里要求只给结果。
     */
    @Test
    fun `只回了思考过程时会加大预算重试`() = runTest {
        val backend = ScriptedBackend(
            listOf(
                listOf(
                    ChatEvent.Reasoning("我先想想这段对话里他怕黑这件事……"),
                    ChatEvent.Finish("length"),
                    ChatEvent.Completed,
                ),
                listOf(ChatEvent.Text("[-] 他怕黑，睡觉要留一盏灯"), ChatEvent.Completed),
            )
        )
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        val out = engine.probe(ResponseProbe(systemPrompt = "s", userText = "u", maxTokens = 400))

        assertThat(out).contains("他怕黑")
        assertThat(backend.requests).hasSize(2)
        // 额度要放大：还是 400 的话，第二次照样只想不说
        assertThat(backend.requests[1].maxTokens).isGreaterThan(400)
        // 而且要明确要求"只给结果"，否则它会拿着新额度再想一遍
        assertThat(backend.requests[1].systemPrompt).contains("只给结果")
        assertThat(backend.requests[1].stream).isTrue()
    }

    /** 重试后仍然只有思维链：把原因说出来，别再让用户去换模型名 */
    @Test
    fun `重试后仍只有思考过程时说明原因`() = runTest {
        val backend = ScriptedBackend(
            listOf(listOf(ChatEvent.Reasoning("想"), ChatEvent.Finish("length"), ChatEvent.Completed))
        )
        val (engine, _) = buildEngine(this, settingsWithProvider(), backend)

        val err = runCatching {
            engine.probe(ResponseProbe(systemPrompt = "s", userText = "u", maxTokens = 400))
        }.exceptionOrNull()

        assertThat(err).isInstanceOf(DshError.Blank::class.java)
        assertThat((err as DshError).describeForUser()).contains("思考")
    }

}
