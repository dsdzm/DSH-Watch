package com.dshwatch.core

import android.content.Context
import com.dshwatch.DshWatchApp
import com.dshwatch.data.settings.SecureKeyStore
import com.dshwatch.data.settings.SettingsRepository
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.domain.ChatEngine
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.DeviceProfiler
import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.PerfProfile
import com.dshwatch.domain.PerfProfiles
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolRegistry
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.PerfMode
import com.dshwatch.domain.model.WorkspaceLocation
import com.dshwatch.net.backend.BackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * 手写依赖容器。
 * 不引入 Hilt/Koin：手表项目总共就这么几个单例，手写反而更短、更透明，
 * 也省掉了注解处理带来的构建时间与 APK 体积。
 */
class AppContainer(private val app: Context) {

    val json = Jsonx.lenient

    val settingsRepo = SettingsRepository(app, json)
    val keyStore = SecureKeyStore(app)
    val conversationStore = ConversationStore(File(app.filesDir, "conversations"), json)

    /** 工作空间默认放应用内部私有目录 */
    private val internalWorkspace = File(app.filesDir, "workspace")

    /**
     * 外部私有目录（`Android/data/<包名>/files/workspace`）。
     * 同样不需要任何权限，但用户能用文件管理器 / USB 连电脑直接看到并拷出文件。
     * 少数 ROM 上 getExternalFilesDir 会返回 null，那就只能留在内部。
     */
    private val externalWorkspace: File? =
        runCatching { app.getExternalFilesDir("workspace") }.getOrNull()

    val workspaceStore = WorkspaceStore(internalWorkspace)

    val usageRepo = UsageRepository(File(app.filesDir, "usage"), json)
    val backends = BackendRegistry(json)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 设备画像：性能档 + 屏幕尺寸 + 圆角安全边距 */
    private val _profile = MutableStateFlow(DeviceProfiler.detect(app, -1))
    val profile: StateFlow<DeviceProfile> = _profile.asStateFlow()

    val deviceTier: DeviceTier get() = _profile.value.tier

    private val _perf = MutableStateFlow(PerfProfiles.of(PerfMode.AUTO, deviceTier))
    val perf: StateFlow<PerfProfile> = _perf.asStateFlow()

    /** agent 设过的闹钟（AlarmManager 查不到自己的闹钟，只能自己记账） */
    val alarmStore = com.dshwatch.data.store.AlarmStore(File(app.filesDir, "alarms"), json)

    /** ask_user 的通道：工具集先构造、引擎后构造，靠它在两者之间搭桥 */
    val promptChannel = com.dshwatch.domain.agent.UserPromptChannel()

    /** agent 的多步任务清单；界面直接订阅它的 state */
    val todoBoard = com.dshwatch.domain.agent.TodoBoard(File(app.filesDir, "todos"), json)

    /** 需要界面才能完成的动作（打开页面 / 弹预览），由聊天页在进入时注册 */
    val uiActions = com.dshwatch.domain.agent.UiActionChannel()

    /** 云端编译的 run 记录（按会话分键） */
    val cloudRunStore = com.dshwatch.domain.agent.cloud.CloudRunStore(
        File(app.filesDir, "cloud"),
        json,
    )

    /**
     * GitHub 通道。
     * token 用 lambda 现取：用户在设置里刚填完就要能用，不能在构造时快照成 null。
     */
    val githubApi = com.dshwatch.domain.agent.cloud.GitHubApi { keyStore.getGithubToken() }

    /**
     * 工具的公共上下文。
     *
     * 提成字段而不是内联在 ToolRegistry 构造里：MCP 连上/断开后要**重建**注册表，
     * 重建时得复用同一个上下文（尤其 `settings` 那个 lambda —— 换成快照会让
     * 工具一直看到旧设置）。
     */
    private val toolContext = ToolContext(
        appContext = app,
        workspace = workspaceStore,
        usage = usageRepo,
        profile = { _profile.value },
        // sessionDir 是空的：真正的会话目录在每次执行工具时由 withSession 换上
        workspaceRoot = { workspaceStore.rootPath() },
        prompter = promptChannel,
        alarms = alarmStore,
        todos = todoBoard,
        uiActions = uiActions,
        settings = { settings.value },
        cloudRuns = cloudRunStore,
        github = githubApi,
    )

    /** agent 工具集；ToolContext 通过 lambda 拿实时设备画像 */
    private val _toolRegistry = MutableStateFlow(ToolRegistry(toolContext))
    val toolRegistry: StateFlow<ToolRegistry> = _toolRegistry.asStateFlow()

    /** MCP 连接池：连接远程 server 并把它们的工具并入注册表 */
    val mcpManager = com.dshwatch.domain.agent.mcp.McpManager(toolContext, keyStore)

    val settings: StateFlow<AppSettings> = settingsRepo.flow
        .stateIn(appScope, SharingStarted.Eagerly, AppSettings())

    val engine = ChatEngine(
        appScope = appScope,
        settingsRepo = settingsRepo,
        keyStore = keyStore,
        store = conversationStore,
        usageRepo = usageRepo,
        backends = backends,
        perfProvider = { _perf.value },
        toolRegistry = { _toolRegistry.value },
        workspaceRoot = { workspaceStore.rootPath() },
        promptChannel = promptChannel,
        json = json,
    )

    init {
        appScope.launch {
            settings.collect { s ->
                val prof = DeviceProfiler.detect(app, s.cornerInsetDp)
                _profile.value = prof

                var p = PerfProfiles.of(s.perfMode, prof.tier)
                if (s.maxRenderedMessagesOverride > 0) {
                    p = p.copy(maxRenderedMessages = s.maxRenderedMessagesOverride)
                }
                if (s.streamThrottleOverrideMs > 0) {
                    p = p.copy(streamThrottleMs = s.streamThrottleOverrideMs.toLong())
                }
                p = p.copy(enableHaptics = s.haptics)
                _perf.value = p
            }
        }
        appScope.launch { usageRepo.ensureLoaded() }

        appScope.launch {
            // MCP 连接对齐。
            // 用增量而不是"每次都全断全连"：用户只是勾掉一个工具时，
            // 全量重连要等好几秒，而那个改动根本不需要重新握手。
            settings
                .map { it.mcpServers }
                .distinctUntilChanged()
                .collect { servers ->
                    val wanted = servers.filter { it.enabled && it.confirmed && it.isValid }
                        .map { it.id }.toSet()

                    mcpManager.connectedIds().filter { it !in wanted }.forEach { mcpManager.disconnect(it) }

                    var connectedAny = false
                    for (server in servers.filter { it.id in wanted }) {
                        if (server.id in mcpManager.connectedIds()) continue
                        val outcome = runCatching { mcpManager.connect(server) }.getOrNull()
                        if (outcome is com.dshwatch.domain.agent.mcp.McpConnectOutcome.Ok) {
                            connectedAny = true
                        }
                    }

                    // disabledTools 变化不需要重连，但需要重建表（工具会被过滤掉）
                    if (connectedAny || servers.isNotEmpty()) refreshToolRegistry()
                }
        }

        appScope.launch {
            // 清掉升级前堆积的空会话：旧版本每点一次「新建对话」就落一条盘，
            // 用户点几下之后列表里全是同名、点开没内容的「新对话」。
            // 现在既不再产生，也要把历史存量收干净。
            val removed = conversationStore.pruneEmpty()
            if (removed > 0) Logx.d("AppContainer", "清理了 $removed 个空会话")
        }
        appScope.launch {
            // 先把历史脏数据搬回正确位置：早期版本会把模型给的绝对路径当成相对路径，
            // 写进 data/data/<pkg>/files/workspace/ 那种嵌套目录，表现为「构建时找不到入口文件」。
            workspaceStore.selfHeal()

            // 再按用户的设置决定文件到底放在内部还是外部私有目录
            settings.collect { s ->
                val target = when (s.workspaceLocation) {
                    WorkspaceLocation.INTERNAL -> internalWorkspace
                    WorkspaceLocation.EXTERNAL -> externalWorkspace ?: internalWorkspace
                }
                if (target.absolutePath != workspaceStore.rootPath()) {
                    // 返回 -1 表示目标不可写（外部存储没挂载 / 只读），此时保持原位置不动
                    if (workspaceStore.relocate(target) >= 0) {
                        // 脏数据会随目录一起被搬过去，换完位置再收敛一次
                        workspaceStore.selfHeal()
                    }
                }
            }
        }
    }

    /**
     * 用「内置工具 + 当前 MCP 工具」重建注册表。
     *
     * 引擎每轮都实时读 `_toolRegistry.value`，所以热替换就能立刻生效 ——
     * **不需要改 ChatEngine 一行代码**。这也是当初把注册表放在 StateFlow 上的原因。
     */
    fun refreshToolRegistry() {
        val builtin = com.dshwatch.domain.agent.tools.BuiltinTools.all()
        _toolRegistry.value = mcpManager.buildRegistry(builtin, settings.value.mcpServers)
    }

    /** 设置页用来显示工作空间实际路径 */
    fun workspacePaths(): Pair<String, String?> =
        internalWorkspace.absolutePath to externalWorkspace?.absolutePath

    /** 本地数据目录，设置页用来显示占用 */
    fun appFilesDir(): File = app.filesDir

    companion object {
        fun of(context: Context): AppContainer =
            (context.applicationContext as DshWatchApp).container
    }
}
