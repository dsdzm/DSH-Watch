package com.dshwatch.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderType {
    /** 直连任意 OpenAI 兼容 /chat/completions 服务（DeepSeek 官方、中转站、本地 Ollama…） */
    OPENAI_COMPAT,

    /** 连接自己电脑上运行的 DSH（dsh-workspace 远程访问协议） */
    DSH_SERVER,
}

@Serializable
enum class ReasoningEffort(val wire: String?, val label: String) {
    DEFAULT(null, "跟随模型"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
}

@Serializable
data class Provider(
    val id: String,
    val name: String,
    val type: ProviderType = ProviderType.OPENAI_COMPAT,

    /** 形如 https://api.deepseek.com/v1（末尾斜杠可有可无） */
    val baseUrl: String = "",
    val models: List<String> = emptyList(),
    val defaultModel: String = "",

    val temperature: Float = 0.7f,
    val topP: Float = 1f,
    val maxTokens: Int = 2048,
    val contextWindow: Int = 64_000,

    val systemPrompt: String = "",
    val presetId: String = "vibe_coding",
    /** 附加到 system prompt 末尾的自定义指令 */
    val customPrompt: String = "",

    val stream: Boolean = true,
    /** 流式时是否发送 stream_options.include_usage 以拿回精确用量 */
    val requestUsageInStream: Boolean = true,

    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,

    /** 计价（元 / 百万 token），用于估算费用；0 表示不显示费用 */
    val priceInPerMillion: Double = 0.0,
    val priceOutPerMillion: Double = 0.0,

    /** DSH_SERVER 专用 */
    val deviceName: String = "",
    val pairedAt: Long = 0L,
    /** DSH_SERVER 专用：远端会话 id 缓存 */
    val lastRemoteSession: String = "",
) {
    val isDshServer: Boolean get() = type == ProviderType.DSH_SERVER

    val hasApiKey: Boolean get() = false // 实际判断在 SecureKeyStore，避免把密钥带进内存快照

    fun normalizedBaseUrl(): String {
        var u = baseUrl.trim()
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u.trimEnd('/')
    }
}

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Serializable
enum class PerfMode {
    /** 依据设备内存/核心数自动选择 */
    AUTO,
    /** 极致省电：关闭动画、强节流、限制渲染条数 */
    POWER_SAVE,
    BALANCED,
    PERFORMANCE,
}

/** Agent 模式下工具的放行策略 */
@Serializable
enum class AgentMode(val label: String, val desc: String) {
    /** 不启用工具，退化成普通对话 */
    OFF("关闭", "只聊天，不给模型任何工具"),

    /** 每个工具调用都弹窗确认 */
    ASK_EVERY_STEP("每步都问", "每次调用工具都先确认"),

    /** 只读工具直接执行，写操作与联网仍然询问 */
    AUTO_READONLY("只读自动", "查时间/算数/读笔记直接跑，联网与写操作先问"),

    /** 全部自动执行 */
    AUTO_ALL("全自动", "所有工具直接执行，只在最后给结果"),
}

/**
 * agent 工作空间的存放位置。
 *
 * 两个都在应用私有目录下，**都不需要任何存储权限**，区别只在用户能不能直接看到：
 * 外部私有目录（`Android/data/<包名>/files/workspace`）用文件管理器或 USB 连电脑可以看到、拷出来。
 */
@Serializable
enum class WorkspaceLocation(val label: String, val desc: String) {
    INTERNAL("应用内部", "files/workspace，最稳，只有本 App 能访问"),
    EXTERNAL("外部私有", "Android/data/<包名>/files/workspace，文件管理器可见，方便导出"),
}

@Serializable
data class AppSettings(
    val providers: List<Provider> = emptyList(),
    val activeProviderId: String = "",
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = true,
    /** 0.85 / 1.0 / 1.15，手表屏小，默认略缩一号 */
    val fontScale: Float = 0.92f,
    val perfMode: PerfMode = PerfMode.AUTO,
    /** 覆盖性能档的渲染条数，0 = 跟随性能档 */
    val maxRenderedMessagesOverride: Int = 0,
    val streamThrottleOverrideMs: Int = 0,
    val keepScreenOn: Boolean = false,
    val haptics: Boolean = true,
    /** 流式生成时挂前台服务，锁屏不中断 */
    val foregroundService: Boolean = true,
    val autoScroll: Boolean = true,
    val showReasoning: Boolean = true,
    val showTokenBadge: Boolean = true,
    val activePresetId: String = "vibe_coding",

    // ---------------- Agent ----------------
    /** 默认全自动：用户明确选择了这个策略 */
    val agentMode: AgentMode = AgentMode.AUTO_ALL,
    /** 单次任务最多跑几步，防止在手表上无限循环烧 token */
    val maxAgentSteps: Int = 8,
    /** 用户点过「总是允许」的工具名 */
    val autoApprovedTools: Set<String> = emptySet(),

    // ---------------- 屏幕 ----------------
    /** 屏幕边角安全边距（dp）。-1 表示按屏幕尺寸自动估算 */
    val cornerInsetDp: Int = -1,

    // ---------------- 品牌形象 ----------------
    /**
     * 是否显示看板娘（聊天空态插图、AI 回复头像）。
     * 关掉之后界面退化成纯文字，「DSH」两个字回到头像位置。
     */
    val showMascot: Boolean = true,

    // ---------------- 工作空间 ----------------
    /**
     * 文件存放位置：内部 / 外部私有目录。
     * 默认用**外部私有目录** —— 用户能通过文件管理器或 USB 直接拿到产物，
     * 这是 agent「在手表上产出东西」之后最需要的一步。设备没有外部私有目录时自动回退内部。
     */
    val workspaceLocation: WorkspaceLocation = WorkspaceLocation.EXTERNAL,
) {
    val activeProvider: Provider? get() = providers.firstOrNull { it.id == activeProviderId }
        ?: providers.firstOrNull()

    val isConfigured: Boolean get() = activeProvider != null
}
