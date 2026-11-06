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

/**
 * 屏幕形状。
 *
 * 存在设置里的原因：`Configuration.isScreenRound` 在少数手表 ROM 上会判错
 * （把方表报成圆屏，于是左右白收 16dp），而圆屏和方屏的布局差别很大，
 * 用户需要一条「我自己知道」的出口。`auto` 表示继续信任系统。
 */
object ScreenShapes {
    const val AUTO = "auto"
    const val ROUND = "round"
    const val SQUARE = "square"

    val all = listOf(AUTO, ROUND, SQUARE)

    fun of(id: String): String = id.takeIf { it in all } ?: AUTO

    /** 最终是否按圆屏布局：显式选择优先，其次看系统检测值 */
    fun resolve(choice: String, detected: Boolean): Boolean = when (of(choice)) {
        ROUND -> true
        SQUARE -> false
        else -> detected
    }
}

/**
 * 对话页布局。
 *
 * 存字符串而不是枚举：设置整包 JSON 落盘，枚举改名会让老用户整包读不回来。
 */
object ChatLayouts {
    const val BUBBLE = "bubble"
    const val CARD = "card"
    const val MINIMAL = "minimal"

    val all = listOf(BUBBLE, CARD, MINIMAL)

    fun of(id: String): String = id.takeIf { it in all } ?: BUBBLE

    /**
     * 当前设备可选的布局。
     *
     * 参数留着是为了调用点不用改：环形布局已经删掉，方屏圆屏都是这三套。
     * 老用户设置里存过 "ring" 的，[of] 会把它落回气泡。
     */
    @Suppress("UNUSED_PARAMETER")
    fun available(isRound: Boolean): List<String> = all
}

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
    /**
     * 界面语言：`system` 跟随系统 / `zh` / `en`。
     *
     * 需要手动开关是因为「跟随系统」在部分手表上不可靠：系统 locale 报英文，
     * 但用户要的是中文。留一个显式出口，比去猜 ROM 的行为稳。
     */
    val appLanguage: String = "system",
    /**
     * **已废弃，仅用于从旧存档迁移。** 新代码不要读它，用 [uiScale]。
     *
     * v1.15.21 及以前这里还有一个独立的「字号」滑块，它只改 `Density.fontScale`：
     * 文字变大、卡片和间距不变。于是字号和界面大小变成两个**相乘**的轴 ——
     * 先调缩放再调字号、或先调字号再调缩放，最终字号相对容器的比例都会被放大，
     * 字撑出卡片、内容顶到圆周外被切掉。单独调其中一个反而是正常的，
     * 所以用户反馈成了「两个一起调才出问题」。
     *
     * v1.15.22 起两者合并成一个轴（[uiScale]），迁移时把旧值乘进 uiScale
     * 再把它归 1.0，详见 SettingsRepository.migrate。
     */
    val fontScale: Float = 1.0f,
    /**
     * 界面整体缩放（1.0 = 标准）。缩放的是密度而不是字号，因此卡片、间距、
     * 图标会跟着一起变。只在 [uiScaleAuto] 关闭时生效。
     *
     * 为什么需要：界面按 320dp 宽设计，而 412×502 px 的屏若按 320dpi 报告，
     * 可用宽度只有 206dp，整套界面等比放大后必然溢出。手动滑块是给
     * 自动值不合口味、或某些 ROM 密度虚标的设备留的出口。
     */
    val uiScale: Float = 1.0f,
    /**
     * 界面大小是否跟随本机自动。
     *
     * 默认开：绝大多数用户不会去外观页里找一个「界面大小」滑块，
     * 而高 dpi 设备一上手就是溢出的界面。自动值按 `可用宽度 / 320dp` 算，
     * 正常表盘上约等于 1.0，不会改变现有观感。
     */
    val uiScaleAuto: Boolean = true,
    val perfMode: PerfMode = PerfMode.AUTO,
    /** 覆盖性能档的渲染条数，0 = 跟随性能档 */
    val maxRenderedMessagesOverride: Int = 0,
    val streamThrottleOverrideMs: Int = 0,
    val keepScreenOn: Boolean = false,
    val haptics: Boolean = true,
    /** 流式生成时挂前台服务，锁屏不中断 */
    val foregroundService: Boolean = true,
    /**
     * 启动后自动检查更新（一天最多一次）。
     *
     * 关掉之后只能从「关于 → 检查更新」手动查。默认开：手表上没人会主动去点
     * 那个入口，而版本落后带来的问题（尤其是崩溃修复）恰恰需要它主动被发现。
     */
    val autoCheckUpdate: Boolean = true,
    /** 上次自动检查的时间戳（毫秒），0 表示从没查过。只用来做 24h 节流 */
    val lastUpdateCheckAt: Long = 0,
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

    // ---------------- 初次引导 / 外观 ----------------
    /**
     * 是否完成首次启动引导。
     * false 时 [com.dshwatch.ui.AppRoot] 直接进引导页，不渲染任何主界面。
     */
    val onboardingDone: Boolean = false,

    /** 屏幕形状：`auto` 跟随系统 / `round` 圆屏 / `square` 方屏 */
    val screenShape: String = ScreenShapes.AUTO,

    /** 对话页布局 id，见 [ChatLayouts] */
    val chatLayout: String = ChatLayouts.BUBBLE,

    /**
     * 用户的主要使用场景：`coding` / `tavern` / `writing`。
     *
     * 引导页会让用户选一次，决定「进 App 落到哪个模式的界面」。
     * 它只是**默认落点**，不影响已有会话的场景（那是会话自己的属性）。
     */
    val useScene: String = "coding",

    /**
     * 上次启动时看到的版本号（如 "1.12"）。
     *
     * 只用来判断「这条一次性说明还要不要再弹」。空串 = 从没记录过，
     * 也就是从更老的版本升上来的用户 —— 该看到的说明都该看到。
     */
    val lastSeenVersion: String = "",

    /** 上次用过的角色卡 / 写作卡，下次进对应场景直接续上 */
    val lastTavernCardId: String = "",
    val lastWritingCardId: String = "",
    /** 酒馆与写作里是否显示思维链与工具调用（默认关闭：用户要的是"只有对话"） */
    val roleplayShowReasoning: Boolean = false,

    /**
     * 主题预设 id。
     *
     * 与 [themeMode] 是**正交**的两层：预设决定色板（哪套颜色），
     * themeMode 决定明暗。这样「深色」和「深海蓝」可以自由组合。
     */
    val themePreset: String = "oled",

    /**
     * 外观风格：M3E 纯色 还是 质感玻璃。
     *
     * ## 为什么单独一个字段，而不是继续当主题预设
     *
     * 以前 `themePreset = "glass"` 是七套预设里的一套，和「深海」「樱花」平级。
     * 这是错的：预设是**换颜色**，玻璃是**换材质** —— 两者不是同一个维度。
     * 把材质塞进颜色的列表里，会出现「深海蓝 + 玻璃」这种本该存在、
     * 却在 UI 上无从选起的组合，而「玻璃 + 强调色」又看着像别的预设。
     *
     * 现在拆成两层：
     *
     * | 字段 | 决定 | 取值 |
     * |---|---|---|
     * | [appearance] | 材质：纯色还是玻璃 | [Appearances.M3E] / [Appearances.GLASS] |
     * | [themePreset] | 颜色：哪套色板 | oled / ocean / sakura / … |
     * | [accentColor] | 主色 | blue / teal / violet / … |
     *
     * 玻璃风格下预设仍然生效 —— 它决定底色晕的主色与副色，所以「玻璃 + 樱花」
     * 是粉调玻璃，「玻璃 + 深海」是蓝调玻璃。强调色照样可以换。
     *
     * 默认 M3E：绝大多数用户第一次打开时不该被一个不熟悉的材质接管界面。
     * 引导页会问一次，用户选了什么就是什么。
     */
    val appearance: String = Appearances.M3E,

    /** 强调色 id：在预设色板之上换主色，不改背景与正文层次 */
    val accentColor: String = "blue",

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

    // ---------------- 本地脚本执行 ----------------
    /**
     * 总开关，**出厂关闭**。
     *
     * 这个工具能执行任意代码，权限比其它所有工具加起来都大，
     * 所以默认状态必须是"没打开"，由用户自己决定要不要给。
     * 即使打开了，`run_script` 仍然每次都弹确认（见 ChatEngine.ALWAYS_CONFIRM_TOOLS）。
     */
    val localExecEnabled: Boolean = false,
    val allowShell: Boolean = true,
    val allowJs: Boolean = true,
    /** 单次脚本执行的超时（秒）。手表 CPU 慢，超过这个时间基本就是写错了 */
    val execTimeoutSec: Int = 20,
    /** 单条输出流保留的字符数（头尾各一半） */
    val execMaxOutputChars: Int = 20_000,

    // ---------------- 云端编译（GitHub Actions）----------------
    /** 同样出厂关闭：它会把代码发到外部 */
    val cloudBuildEnabled: Boolean = false,
    /** 目标仓库，形如 owner/repo */
    val cloudRepo: String = "",
    val cloudRef: String = "main",
    /** 默认使用的 workflow 文件名 */
    val cloudWorkflowFile: String = "dsh-run.yml",
    /** 等待云端运行结束的上限（秒）。Actions 排队 + 构建经常要几分钟 */
    val cloudPollTimeoutSec: Int = 900,

    // ---------------- 插件 ----------------
    /**
     * 已开启的插件 id。
     *
     * 存 id 集合而不是一堆布尔字段：插件会增删，用集合时新增插件不需要动
     * 数据类的结构（老设置里没有它的 id 就是没开）。
     *
     * 默认值**不在这里写死**，而是由 `PluginRegistry.defaultEnabled()` 决定 ——
     * 插件的默认开关是插件自己的属性，写两处必然对不上。
     * 空集合表示"用的是默认值"，见 [pluginsResolved]。
     */
    val enabledPlugins: Set<String> = emptySet(),

    /**
     * 用户是否动过插件开关。
     *
     * 需要这个标志的原因：`enabledPlugins` 为空既可能是"用户全关了"，
     * 也可能是"从没动过"（老版本升级上来）。两者行为完全不同 ——
     * 前者要尊重用户，后者要套用默认值。用一个显式布尔区分，
     * 比在空集合上猜要可靠得多。
     */
    val pluginsCustomized: Boolean = false,

    // ---------------- B站（刷一刷）----------------
    /**
     * B站工具总开关，默认开启。
     *
     * 这一组工具只读公开接口 + 写本地日报，不碰账号数据；关掉之后
     * 那七个 `bili_*` 的 schema 不再进入每一次请求（见 [AgentTool.enabled]）。
     */
    val biliEnabled: Boolean = true,

    /** 关注关键词，逗号分隔。留空表示由模型按用户当次的要求决定搜什么 */
    val biliKeywords: String = "",

    /** 每天最多往日报里记几条。0 = 不限 */
    val biliDailyQuota: Int = 5,

    /**
     * 旧版「允许发评论」开关。
     *
     * 1.10 起改由 [biliMode] 三选一表达（评论 / 私信 / 只总结），这个字段
     * **只为迁移保留**：老用户的设置里它可能是 true，读出来时据此推断模式，
     * 避免升级后「本来能发，突然变成只总结」。
     */
    val biliCommentEnabled: Boolean = false,

    /**
     * 刷完之后的动作：`comment` 发评论 / `dm` 发私信 / `summary` 只写日报。
     *
     * 空串表示老版本没这个字段，由 [biliCommentEnabled] 推导 ——
     * 用空串而不是直接默认 `summary`，是为了区分「用户主动选了只总结」
     * 和「还没选过」这两种情况。
     */
    val biliMode: String = "",

    /**
     * 评论里要 @ 的 UID（一般是用户自己）。
     *
     * 只填 UID 不够：B站的 at 需要「昵称 → mid」的映射才能真的通知到人，
     * 昵称在发送前用 `x/web-interface/card` 现查。
     */
    val biliAtUid: String = "",

    /**
     * 配图来源：`none` 不带图 / `pack` 内置大肥鱼表情包（随机一张）/
     * `card` 程序生成的摸鱼卡 / `local` 用户从相册选的那张。
     *
     * 默认 `pack` 而不是 `none`：评论带图明显更像真人，而且内置的几张
     * 本来就是用户给的形象，比自己画的卡片更贴合人设。
     */
    val biliImage: String = "pack",

    /** 摸鱼卡上那行字。留空则按日期轮换默认文案 */
    val biliImageText: String = "",

    /**
     * 用户是否主动改过配图来源。
     *
     * 用来区分「主动选了不带图」和「从没动过、只是继承了旧默认值」：
     * 前者要尊重，后者应该在升级时换成新默认（内置表情包）。
     * 没有这个标记就只能靠 `biliImage == "none"` 猜，两种情况下会猜错一种。
     */
    val biliImageTouched: Boolean = false,

    /**
     * 每天最多对外发几条（评论 + 私信合计）。
     *
     * 没有这个上限，「不需要确认」就等于把账号交给模型刷 —— B站 对高频互动
     * 有风控，刷太快是直接封号而不是折叠。0 = 不限（不建议）。
     */
    val biliCommentQuota: Int = 3,

    /**
     * 允许自己发动态。
     *
     * 和「刷完做什么」的三选一是正交的：评论 / 私信都是对某条视频的反应，
     * 动态是独立广播，不依附任何视频。所以做成单独开关，而不是塞进模式枚举 ——
     * 塞进去就变成四选一，用户没法表达「刷的同时偶尔发一条」。
     */
    val biliDynEnabled: Boolean = false,

    /**
     * 每天最多发几条动态。
     *
     * 单独设上限且默认只有 1：动态会推到粉丝时间线，可见度比评论高一个量级，
     * 一天三条以上就已经很像营销号了。它同时计入 [biliCommentQuota] 的总发言数，
     * 避免「3 条评论 + 1 条动态」变成四次对外发言。0 = 不限。
     */
    val biliDynQuota: Int = 1,

    /**
     * 动态写什么，逗号分隔的多选：`life` 生活日常 / `roast` 刷到的视频吐槽 /
     * `master` 对主人（用户）的感受。
     *
     * 做成多选而不是单选：这三种本来就不互斥，一次真实的朋友圈式动态
     * 往往是「今天干了啥 + 顺便吐槽一句 + 想主人了」揉在一起。
     */
    val biliDynTopics: String = "life,roast,master",

    /**
     * 允许它自己点赞。
     *
     * 只有点赞 —— 投币那套已经整条链路删掉了：硬币是账号里不可逆的存量资源，
     * 让模型替用户花，风险远大于收益。点赞重复点也不消耗任何东西。
     */
    val biliLikeEnabled: Boolean = true,

    /** 每天定时自动刷一轮 */
    val biliAutoRun: Boolean = false,

    /** 定时时间（本地时区，24 小时制）。默认 03:30 —— 谷时，几乎不影响任何人 */
    val biliAutoHour: Int = 3,
    val biliAutoMinute: Int = 30,

    /**
     * 评论风格预设 id：`fish`（默认，吃白饭的摸鱼大肥鱼）/ `serious` / `deadpan` / `custom`。
     *
     * 存字符串而不是枚举：设置是整包 JSON 落盘的，枚举改名会让老用户读不回来。
     */
    val biliTone: String = "fish",

    /** `custom` 风格的实际描述。其它预设下忽略 */
    val biliToneCustom: String = "",

    /** 扫码登录成功后的昵称，只用于在设置页显示「已登录：xxx」（凭证本身在加密存储里） */
    val biliUserName: String = "",

    /** 后台跑完是否发一条通知。手表上最怕「跑完了但没人知道」 */
    val biliNotifyDone: Boolean = true,

    // ---------------- MCP ----------------
    /**
     * 远程 MCP server 列表。
     *
     * 每个 server 默认 `enabled = false` —— 与 `cloud_build` 同档：
     * 它会把工具调用的参数发到第三方，属于"数据离开设备"的操作，
     * 必须由用户显式开启并确认工具清单。
     */
    val mcpServers: List<McpServerConfig> = emptyList(),
) {
    val activeProvider: Provider? get() = providers.firstOrNull { it.id == activeProviderId }
        ?: providers.firstOrNull()

    val isConfigured: Boolean get() = activeProvider != null

    /** 实际生效的插件集合（区分"用户全关"与"从没设置过"） */
    fun pluginsResolved(defaults: Set<String>): Set<String> =
        if (pluginsCustomized) enabledPlugins else defaults
}
