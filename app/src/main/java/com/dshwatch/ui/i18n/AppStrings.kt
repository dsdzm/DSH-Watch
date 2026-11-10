package com.dshwatch.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * 全部界面文案集中在这里。
 *
 * 为什么不用 res/values/strings.xml：
 *  - 用接口实现，少写一个 key 直接编译不过，不会有「上线才发现资源缺失」的运行时崩溃；
 *  - 文案跟使用它的组件在同一屏代码里，改文案时不用来回跳文件。
 * 新增语言：再实现一个 AppStrings 即可（编译器会强制补齐所有字段）。
 */
interface AppStrings {

    // 通用
    val appName: String
    val ok: String
    val cancel: String
    val save: String
    val delete: String
    val confirm: String
    val close: String
    val copy: String
    val copied: String
    val retry: String
    val stop: String
    val send: String
    val add: String
    val done: String
    val none: String
    val loading: String
    val test: String
    val reset: String
    val rename: String
    val more: String
    val clear: String
    val yes: String
    val no: String

    // 导航
    val navChat: String
    val homeContinue: String
    val navSessions: String
    val navSettings: String

    // 帮助与教程
    val helpTitle: String
    val helpSubtitle: String
    val helpCopyAll: String
    val backToBottom: String
    val navUsage: String
    val navProviders: String
    val navAbout: String
    val back: String

    // 对话
    val chatEmptyTitle: String

    /** 酒馆/写作场景下对话页的空态（正常不会出现，兜底用） */
    val roleplayEmptyTitle: String
    val roleplayEmptyHint: String
    val chatEmptyHint: String
    val inputHint: String
    val thinking: String
    val reasoning: String
    val expandReasoning: String
    val collapseReasoning: String
    val workflowTitle: String
    val workflowNow: String
    val workflowDone: String
    val workflowFailed: String
    val workflowExpand: String
    val workflowCollapse: String
    val regenerate: String
    val copyMessage: String
    val deleteMessage: String
    val messageMenu: String
    val contextUsage: String
    val tokensLabel: String
    val estimatedTag: String
    val modelLabel: String
    val switchModel: String
    val generating: String
    val stopped: String
    val sendFailed: String
    val scrollToBottom: String
    val newChat: String

    // 会话
    val sessionsTitle: String
    val noSessions: String
    val deleteSession: String
    val deleteSessionConfirm: String
    val renameSession: String
    val pin: String
    val unpin: String
    val messagesCount: String

    // 设置
    val settingsTitle: String
    val groupAppearance: String
    val theme: String
    val themeSystem: String
    val themeDark: String
    val themeLight: String
    val dynamicColor: String
    val dynamicColorHint: String
    /**
     * 界面整体大小。
     *
     * **这是唯一的缩放轴。** v1.15.22 起「字号」并入这里：以前两个滑块
     * 分别缩 density 和 fontScale，文字上却是相乘的，一起调就会把字撑出
     * 卡片、内容顶到圆周外。文案必须说清「一起缩放」，否则用户会以为
     * 少了字号就调不了字。
     */
    val uiScale: String
    val uiScaleHint: String
    val uiScaleAuto: String
    val uiScaleAutoOn: String
    val uiScaleReset: String
    /** 超过本机推荐值时的提醒，`%s` 是推荐值，如 `0.64×` */
    fun uiScaleOver(recommended: String): String
    val fontSize: String
    val fontSmall: String
    val fontNormal: String
    val fontLarge: String

    /** 字号滑块下面那行实时预览文字 */
    val fontPreview: String
    val language: String

    /** 当前是不是中文界面。少数新页面用它就地取词，省得为一段说明加十几个字段。 */
    val isChinese: Boolean
    val langSystem: String
    val langZh: String
    val langEn: String
    val groupPerformance: String
    val perfMode: String
    val perfAuto: String
    val perfPowerSave: String
    val perfBalanced: String
    val perfPerformance: String
    val deviceTier: String
    val tierLow: String
    val tierMid: String
    val tierHigh: String
    val maxRenderedMessages: String
    val streamThrottle: String
    val followProfile: String
    val groupBehavior: String
    val keepScreenOn: String
    val haptics: String
    val foregroundService: String
    val foregroundServiceHint: String
    val autoScroll: String
    val showReasoning: String
    val showTokenBadge: String
    val groupData: String

    // 设置分组（按「用户想干什么」分，而不是按实现模块分）
    val groupModel: String
    val groupAbility: String
    val groupChat: String
    val groupFiles: String
    val groupRuntime: String
    val groupConnection: String
    val groupPlugins: String
    val pluginsHint: String
    val groupMcp: String
    val mcpHint: String
    val mcpAdd: String
    val mcpNameLabel: String
    val mcpUrlLabel: String
    val mcpTokenLabel: String
    val mcpConnect: String
    val mcpToolsTitle: String
    val mcpToolsHint: String
    val mcpEmpty: String

    // B站
    val groupBili: String
    val biliEnable: String
    val biliEnableHint: String
    val biliComment: String
    val biliCommentHint: String
    val biliQuota: String
    val biliQuotaHint: String
    val biliKeywords: String
    val biliKeywordsHint: String
    val biliCookie: String
    val biliCookieHint: String
    val biliCookieUnset: String
    val biliOpenDigest: String
    val biliHint: String
    val biliLogin: String
    val biliLoginHint: String
    val biliLoginWaiting: String
    val biliLoginScanned: String
    val biliLoginDone: String
    val biliLoginExpired: String
    val biliLoginRetry: String
    val biliLoginPrivacy: String
    val biliTone: String
    val biliToneHint: String
    val biliToneFish: String
    val biliToneSerious: String
    val biliToneDeadpan: String
    val biliToneCustom: String
    val biliToneCustomHint: String
    val biliNotifyDone: String
    val biliNotifyDoneHint: String
    val biliMode: String
    val biliModeSummary: String
    val biliModeSummaryHint: String
    val biliModeComment: String
    val biliModeCommentHint: String
    val biliModeDm: String
    val biliModeDmHint: String
    val biliAtUid: String
    val biliAtUidHint: String
    val biliImage: String
    val biliImageNone: String
    val biliImageNoneHint: String
    val biliImagePack: String
    val biliImagePackHint: String
    val biliImageCard: String
    val biliImageCardHint: String
    val biliImageLocal: String
    val biliImageLocalHint: String
    val biliImageText: String
    val biliImageTextHint: String
    val biliImagePickFailed: String
    val biliCommentQuota: String
    val biliCommentQuotaHint: String
    val biliDyn: String
    val biliDynHint: String
    val biliDynQuota: String
    val biliDynQuotaHint: String
    val biliDynTopicTitle: String
    val biliDynTopicHint: String
    val biliDynTopicLife: String
    val biliDynTopicLifeHint: String
    val biliDynTopicRoast: String
    val biliDynTopicRoastHint: String
    val biliDynTopicMaster: String
    val biliDynTopicMasterHint: String
    val biliLike: String
    val biliLikeHint: String
    val biliCoin: String
    val biliCoinHint: String
    val biliFav: String
    val biliFavHint: String
    val biliFavFolder: String
    val biliFavFolderHint: String
    val communityTitle: String
    val communityHint: String
    val communityGroup: String
    val communityCopy: String

    // 社区与支持（三个独立页面）
    val communitySection: String
    val navGroupChat: String
    val navGroupChatHint: String
    val navDonate: String
    val navDonateHint: String
    val navThanks: String
    val navThanksHint: String

    val groupTitle: String
    val groupIntro: String
    val groupLongPress: String

    val donateTitle: String
    val donateIntro: String
    val donateScan: String
    val donateNote: String
    val donateThanks: String

    val thanksTitle: String
    val thanksIntro: String
    val thanksPeople: String
    val thanksProjects: String
    val thanksFooter: String
    val communityCopied: String
    val biliAutoRun: String
    val biliAutoRunHint: String
    val biliAutoTime: String
    val biliAutoTimeHint: String
    val biliAutoTimeTip: String
    val biliToneNaughty: String

    fun pluginTitle(id: String): String
    fun pluginDesc(id: String): String

    // 设置页条目说明
    val entryProviderUnset: String
    val entryUsageDesc: String
    val entryPairDesc: String
    val entryWorkspaceDesc: String
    val entryDiagnosticsDesc: String
    val entryAboutDesc: String
    val entryAgentStepsDesc: String
    val entryStorageDesc: String
    val storageUsed: String

    // 供应商
    val providersTitle: String
    val addProvider: String
    val editProvider: String
    val newProvider: String
    val providerName: String
    val providerType: String
    val typeOpenAi: String
    val typeOpenAiHint: String
    val typeDsh: String
    val typeDshHint: String
    val baseUrl: String
    val baseUrlHint: String
    val apiKey: String
    val apiKeyHint: String
    val apiKeySaved: String
    val defaultModel: String
    val modelsTitle: String
    val fetchModels: String
    val fetchingModels: String
    val addModelManually: String
    val modelNameInput: String
    val temperature: String
    val topP: String
    val maxTokens: String
    val contextWindow: String
    val systemPrompt: String
    val systemPromptHint: String
    val preset: String
    val customPrompt: String
    val customPromptHint: String
    val reasoningEffort: String
    val streamEnabled: String
    val requestUsage: String
    val priceIn: String
    val priceOut: String
    val pricePerMillion: String
    val setActive: String
    val activeProvider: String
    val deleteProvider: String
    val deleteProviderConfirm: String
    val testConnection: String
    val testing: String
    val connectionFailed: String

    // DSH 服务器
    val pairTitle: String
    val pairHint: String
    val serverAddress: String
    val pairingCode: String
    val pairingCodeHint: String
    val deviceName: String
    val pairNow: String
    val pairing: String
    val pairSuccess: String
    val pairFailed: String
    val pairedDevice: String
    val unpair: String

    // 用量
    val usageTitle: String
    val periodToday: String
    val periodWeek: String
    val periodMonth: String
    val periodAll: String
    val totalTokens: String
    val promptTokens: String
    val completionTokens: String
    val cachedTokens: String
    val requestsLabel: String
    val costLabel: String
    val byModel: String
    val byDay: String
    val noUsage: String
    val clearUsage: String
    val clearUsageConfirm: String

    // 执行与云端编译
    val execSectionTitle: String
    val execSectionDesc: String
    val execScreenTitle: String
    val execEnable: String
    val execEnableDesc: String
    val execTimeoutLabel: String
    val execMaxOutputLabel: String
    val execSecondsSuffix: String
    val execForceRelease: String
    val execForceReleaseDesc: String
    val execConfirmNotice: String
    val cloudEnable: String
    val cloudBuildEta: String
    val cloudEnableDesc: String
    val cloudRepoLabel: String
    val cloudRepoHint: String
    val cloudRefLabel: String
    val cloudWorkflowLabel: String
    val cloudTokenLabel: String
    val cloudTokenHint: String
    val cloudInstallTemplates: String
    val cloudTestLabel: String

    // 关于
    val aboutTitle: String
    val version: String
    val aboutDesc: String
    val builtWith: String
    val deviceInfo: String
    val sourceHint: String

    // 更新
    val updateCheck: String
    val autoCheckUpdate: String
    val updateTitle: String
    val updateNewVersion: String
    val updateChecking: String
    val updateUpToDate: String
    val updateCurrent: String
    val updateLatest: String
    val updateLastCheck: String
    val updateDownloadInstall: String
    val updateDownloading: String
    val updateInstall: String
    val updateReady: String
    val updateRetry: String
    val updateNeedPermission: String
    val updateGrant: String
    val updateNoSettings: String
    val updateCopied: String
    val updateInstallFailed: String
    val updateNotes: String

    // 诊断
    val navDiagnostics: String
    val diagTitle: String
    val diagRun: String
    val diagRunning: String
    val diagCopyAll: String
    val diagCopied: String
    val diagEnv: String
    val diagSteps: String
    val diagEmpty: String
    val diagPass: String
    val diagFail: String
    val diagHint: String

    // Agent / 工具
    val groupAgent: String
    val agentMode: String
    val agentMaxSteps: String
    val agentTools: String
    val agentToolsHint: String
    val agentClearApprovals: String
    val toolCall: String
    val toolStatusPending: String
    val toolStatusAwaiting: String
    val toolStatusRunning: String
    val toolStatusSuccess: String
    val toolStatusFailed: String
    val toolStatusDenied: String
    val toolStatusSkipped: String
    val approveOnce: String
    val approveAlways: String
    val approveSession: String
    val deny: String
    val stepLabel: String
    val toolArgs: String
    val toolResult: String
    val cornerInset: String
    val cornerInsetAuto: String
    val screenInfo: String

    // 工作空间
    val navWorkspace: String
    val workspaceTitle: String
    val workspaceEmpty: String
    val workspaceHint: String
    val workspaceBuild: String
    val workspaceFiles: String
    val workspacePreview: String
    val workspaceShare: String
    val workspaceDeleteConfirm: String
    val workspaceFilterAll: String
    val workspaceArtifacts: String
    val workspaceSource: String
    val workspaceOpen: String
    val workspaceNoPreview: String
    val refresh2: String
    val workspaceLocation: String
    val workspaceRootPath: String
    val workspaceExternalFallback: String

    // ---------------- 产物 / 工具展示 ----------------
    val artifact: String
    val openArtifact: String
    val exportArtifact: String
    val toolDetails: String
    val moreTools: String
    val allArtifacts: String
    val artifactCount: String

    // ---------------- 关于页 ----------------
    val aboutAuthor: String
    val authorContact: String
    val aboutChangelog: String
    val aboutIntro: String
    val techStack: String
    val inspiredBy: String
    val appFeatures: String
    val techStackText: String
    val inspiredByText: String
    val appFeaturesText: String

    // 看板娘
    val showMascot: String
    val mascotHint: String

    // DeepSeek 峰谷时段
    val peakNow: String
    val offPeakNow: String
    val toPeak: String
    val toOffPeak: String

    // 会话文件位置
    val sessionWorkDir: String
    val sessionWorkDirHint: String
    val workDirPick: String
    val workDirNew: String
    val workDirNewPlaceholder: String
    val workDirUseRoot: String
    val workDirEmpty: String

    // ask_user
    val agentAsking: String
    val agentAskSubmit: String
    val agentAskSkip: String
    val agentAskPlaceholder: String

    val shareFailed: String

    // ---------------- 初次引导 / 外观 ----------------
    val onboardingTitle: String
    val onboardingSubtitle: String
    val onboardingShapeTitle: String
    val onboardingShapeHint: String
    val onboardingLayoutTitle: String
    val onboardingLayoutHint: String
    val onboardingThemeTitle: String
    val onboardingThemeHint: String
    val onboardingNext: String
    val onboardingBack: String
    val onboardingStart: String
    val onboardingDetectedRound: String
    val onboardingDetectedSquare: String
    val shapeRound: String
    val shapeSquare: String
    val shapeAuto: String
    val layoutBubble: String
    val layoutCard: String
    val layoutMinimal: String
    val layoutBubbleHint: String
    val layoutCardHint: String
    val layoutMinimalHint: String
    val screenShape: String
    val chatLayout: String
    val themePreset: String
    val themePresetHint: String
    val accentColor: String
    val rerunOnboarding: String
    val rerunOnboardingHint: String

    /** 主题预设与强调色的显示名（定义在 ui.theme，这里只按语言取） */
    fun themePresetLabel(id: String): String
    fun accentLabel(id: String): String
    fun chatLayoutLabel(id: String): String
    fun chatLayoutHint(id: String): String

    /**
     * 工具名 → 用户看得懂的说法。
     *
     * 模型用的是 `write_file` 这种 snake_case 英文名，直接摆到界面上太工程化；
     * 用户需要的是「创建文件」这四个字。
     */
    fun toolTitle(tool: String): String
}

object Zh : AppStrings {
    override val appName = "DSH 手表"
    override val ok = "确定"
    override val cancel = "取消"
    override val save = "保存"
    override val delete = "删除"
    override val confirm = "确认"
    override val close = "关闭"
    override val copy = "复制"
    override val copied = "已复制"
    override val retry = "重试"
    override val stop = "停止"
    override val send = "发送"
    override val add = "新增"
    override val done = "完成"
    override val none = "无"
    override val loading = "加载中…"
    override val test = "测试"
    override val reset = "恢复默认"
    override val rename = "重命名"
    override val more = "更多"
    override val clear = "清空"
    override val yes = "是"
    override val no = "否"

    override val navChat = "对话"
    override val homeContinue = "继续对话"
    override val navSessions = "会话"
    override val navSettings = "设置"
    override val helpTitle = "帮助与教程"
    override val helpSubtitle = "面板 / 选项 / 导入格式"
    override val helpCopyAll = "复制本页"
    override val backToBottom = "回到最新"
    override val navUsage = "用量"
    override val navProviders = "供应商"
    override val navAbout = "关于"
    override val back = "返回"

    override val chatEmptyTitle = "开始一段 vibe coding"
    override val roleplayEmptyTitle = "还没有角色"
    override val roleplayEmptyHint = "回上一页选一张角色卡，或者新建一个"
    override val chatEmptyHint = "点下方输入框说话，模型会直接给可运行的代码"
    override val inputHint = "说点什么…"
    override val thinking = "思考中"
    override val reasoning = "思维链"
    override val expandReasoning = "展开思维链"
    override val collapseReasoning = "收起"
    override val workflowTitle = "工作流程"
    override val workflowNow = "正在"
    override val workflowDone = "已完成"
    override val workflowFailed = "有失败"
    override val workflowExpand = "展开工作流程"
    override val workflowCollapse = "收起"
    override val regenerate = "重新生成"
    override val copyMessage = "复制内容"
    override val deleteMessage = "删除这条"
    override val messageMenu = "消息操作"
    override val contextUsage = "上下文"
    override val tokensLabel = "tokens"
    override val estimatedTag = "估算"
    override val modelLabel = "模型"
    override val switchModel = "切换模型"
    override val generating = "生成中"
    override val stopped = "已停止"
    override val sendFailed = "发送失败"
    override val scrollToBottom = "回到底部"
    override val newChat = "新对话"

    override val sessionsTitle = "会话"
    override val noSessions = "还没有会话"
    override val deleteSession = "删除会话"
    override val deleteSessionConfirm = "删除后本地记录不可恢复，确定吗？"
    override val renameSession = "重命名"
    override val pin = "置顶"
    override val unpin = "取消置顶"
    override val messagesCount = "条消息"

    override val settingsTitle = "设置"
    override val groupAppearance = "外观"
    override val theme = "主题"
    override val themeSystem = "跟随系统"
    override val themeDark = "深色"
    override val themeLight = "浅色"
    override val dynamicColor = "动态取色"
    override val dynamicColorHint = "Android 12+ 跟随壁纸取色"
    override val uiScale = "界面大小"
    override val uiScaleHint = "文字、卡片与间距一起缩放"
    override val uiScaleAuto = "跟随本机"
    override val uiScaleAutoOn = "已按屏幕宽度自动适配"
    override val uiScaleReset = "恢复自适应"
    override fun uiScaleOver(recommended: String) =
        "超过本机推荐 $recommended，边缘可能被切到"
    override val fontSize = "字号"
    override val fontSmall = "小"
    override val fontNormal = "标准"
    override val fontLarge = "大"
    override val fontPreview = "这段字会跟着上面的滑块变大变小"
    override val language = "界面语言"
    override val isChinese = true
    override val langSystem = "跟随系统"
    override val langZh = "中文"
    override val langEn = "English"
    override val groupPerformance = "性能"
    override val perfMode = "性能档位"
    override val perfAuto = "自动"
    override val perfPowerSave = "省电"
    override val perfBalanced = "均衡"
    override val perfPerformance = "性能"
    override val deviceTier = "识别到的设备档位"
    override val tierLow = "低功耗手表"
    override val tierMid = "中等配置"
    override val tierHigh = "高配置"
    override val maxRenderedMessages = "列表渲染上限"
    override val streamThrottle = "流式刷新间隔"
    override val followProfile = "跟随档位"
    override val groupBehavior = "行为"
    override val keepScreenOn = "生成时保持亮屏"
    override val haptics = "触感反馈"
    override val foregroundService = "后台保持连接"
    override val foregroundServiceHint = "熄屏后继续生成，通知栏可停止"
    override val autoScroll = "自动滚动到最新"
    override val showReasoning = "显示思维链"
    override val showTokenBadge = "显示 token 角标"
    override val groupData = "数据"

    override val groupModel = "模型"
    override val groupAbility = "AI 能力"
    override val groupChat = "对话"
    override val groupFiles = "文件与存储"
    override val groupRuntime = "性能与运行"
    override val groupConnection = "连接与关于"
    override val groupPlugins = "插件"
    override val groupMcp = "MCP 服务器"
    override val mcpHint =
        "MCP 能把手表接上外部服务（GitHub、数据库、搜索……）。" +
            "对方的工具描述会进入模型上下文，所以第一次连接会先让你看清清单。"
    override val mcpAdd = "添加服务器"
    override val mcpNameLabel = "名称"
    override val mcpUrlLabel = "MCP 地址"
    override val mcpTokenLabel = "访问令牌"
    override val mcpConnect = "保存并连接"
    override val mcpToolsTitle = "工具清单"
    override val mcpToolsHint = "这些描述会发给模型。不认识的项关掉即可，关掉的不会出现。"
    override val mcpEmpty = "还没有添加服务器"

    override val groupBili = "刷 B站"
    override val biliEnable = "启用 B站工具"
    override val biliEnableHint = "搜索、读字幕、写日报；关掉后模型看不到 bili_* 工具"
    override val biliComment = "允许发评论"
    override val biliCommentHint = "默认关闭。关着的时候发评论只生成预览，不会真发"
    override val biliQuota = "每日配额"
    override val biliQuotaHint = "每天最多往日报里记几条，0 表示不限"
    override val biliKeywords = "关注关键词"
    override val biliKeywordsHint = "逗号分隔；留空则由模型按你当次说的话决定搜什么"
    override val biliCookie = "登录 Cookie"
    override val biliCookieHint = "只有发评论需要。粘贴浏览器里的 SESSDATA 与 bili_jct，加密存在本机"
    override val biliCookieUnset = "未配置"
    override val biliOpenDigest = "今日日报"
    override val biliHint =
        "刷B站 = 搜索 → 读字幕正文 → 判断值不值得看 → 写进日报。日报存在工作空间 bili/ 下，" +
            "每条的「### BV号」就是配额计数单位，同一天不会重复记同一条。"
    override val biliLogin = "扫码登录"
    override val biliLoginHint = "用手机 B站 App 扫表盘上的二维码；也可以继续用 Cookie 手填"
    override val biliLoginWaiting = "等待扫码…"
    override val biliLoginScanned = "已扫码，在手机上点确认"
    override val biliLoginDone = "登录成功"
    override val biliLoginExpired = "二维码过期了"
    override val biliLoginRetry = "重新生成"
    override val biliLoginPrivacy = "只保存登录凭证，加密存在本机，不上传"
    override val biliTone = "评论风格"
    override val biliToneHint = "决定它替你说话的语气"
    override val biliToneFish = "吃白饭的摸鱼大肥鱼"
    override val biliToneSerious = "认真点评"
    override val biliToneDeadpan = "抽象玩梗"
    override val biliToneCustom = "自定义"
    override val biliToneCustomHint = "用一段话描述「像谁在说话」，会原样交给模型"
    override val biliNotifyDone = "跑完通知我"
    override val biliNotifyDoneHint = "后台跑完弹一条通知，不用反复抬腕看"
    override val biliMode = "刷完做什么"
    override val biliModeSummary = "只总结"
    override val biliModeSummaryHint = "只写日报，不对外发任何东西"
    override val biliModeComment = "发评论"
    override val biliModeCommentHint = "在视频下评论，也能接别人的话"
    override val biliModeDm = "发私信"
    override val biliModeDmHint = "给 UP 主发私信（不可撤回）"
    override val biliAtUid = "@ 的人"
    override val biliAtUidHint = "填 UID，评论会自动 @ 他（一般填你自己）"
    override val biliImage = "评论配图"
    override val biliImageNone = "不带图"
    override val biliImageNoneHint = "纯文字评论"
    override val biliImagePack = "大肥鱼表情包"
    override val biliImagePackHint = "内置 4 张，每次随机挑一张"
    override val biliImageCard = "摸鱼卡"
    override val biliImageCardHint = "自动画一张黑底卡片，字可以自己写"
    override val biliImageLocal = "相册图"
    override val biliImageLocalHint = "从设备里挑一张，之后每次都带它"
    override val biliImageText = "卡上写什么"
    override val biliImageTextHint = "留空则按日期轮换默认文案"
    override val biliImagePickFailed = "这张图读不出来，换一张试试"
    override val biliCommentQuota = "每日发言上限"
    override val biliCommentQuotaHint = "评论 + 私信合计；超出就停，防刷屏封号"
    override val biliDyn = "发动态"
    override val biliDynHint = "让大肥鱼自己发一条碎碎念；会进粉丝时间线，不依附任何视频"
    override val biliDynQuota = "每日动态上限"
    override val biliDynQuotaHint = "独立计算，同时也计入上面的总发言数"
    override val biliDynTopicTitle = "动态写什么"
    override val biliDynTopicHint = "可多选，它会自己挑一个角度写"
    override val biliDynTopicLife = "生活日常"
    override val biliDynTopicLifeHint = "在干嘛、吃了什么、犯困、摸鱼"
    override val biliDynTopicRoast = "视频吐槽"
    override val biliDynTopicRoastHint = "刚刷到的视频，哪里离谱、哪里真不错"
    override val biliDynTopicMaster = "对你的感受"
    override val biliDynTopicMasterHint = "跟着你、被你使唤、嘴上嫌心里舍不得"
    override val biliLike = "自动点赞"
    override val biliLikeHint = "觉得好才点，同一条视频一天只点一次"
    override val biliCoin = "允许投币"
    override val biliCoinHint = "硬币不可撤销，默认关闭；打开后单条最多 1 枚，每天单独计数"
    override val biliFav = "允许收藏"
    override val biliFavHint = "会往你的收藏夹里塞东西，默认关闭"
    override val biliFavFolder = "收藏夹 ID"
    override val biliFavFolderHint = "留空 = 默认收藏夹；填了只往这个夹里放"
    override val communityTitle = "Bug 反馈群"
    override val communityHint = "遇到问题、想提需求，扫码进来"
    override val communityGroup = "群号"
    override val communityCopy = "复制群号"

    override val communitySection = "社区与支持"
    override val navGroupChat = "加入群聊"
    override val navGroupChatHint = "QQ 群，答疑与反馈"
    override val navDonate = "捐赠支持"
    override val navDonateHint = "请大肥鱼喝一杯"
    override val navThanks = "特别感谢"
    override val navThanksHint = "没有他们就没有这个 App"

    override val groupTitle = "加入群聊"
    override val groupIntro = "扫码进群，或者手动搜群号。提需求、报 Bug、单纯来骂两句都行。"
    override val groupLongPress = "群号可长按复制"

    override val donateTitle = "捐赠支持"
    override val donateIntro = "这个 App 不收费、不放广告，服务器和代码签名都是自掏腰包。"
    override val donateScan = "微信扫码，金额随意"
    override val donateNote = "不打赏也照样用，功能一个不少。别因为这个给自己添负担。"
    override val donateThanks = "谢谢你的心意"

    override val thanksTitle = "特别感谢"
    override val thanksIntro = "这个 App 站在很多人肩膀上。"
    override val thanksPeople = "内测与反馈"
    override val thanksProjects = "开源项目"
    override val thanksFooter = "名单随版本更新，漏掉的人随时来群里找我补上。"
    override val communityCopied = "已复制"
    override val biliAutoRun = "每日自动刷一轮"
    override val biliAutoRunHint = "到点自动跑，跑完按上面选的方式处理"
    override val biliAutoTime = "自动时间"
    override val biliAutoTimeHint = "默认凌晨 3:30，谷时几乎不影响人"
    override val biliAutoTimeTip = "手表进入省电模式时可能晚几分钟，不会不跑"
    override val biliToneNaughty = "调皮模式（抽象/罢工/倒反天罡）"
    override val pluginsHint = "插件改变模型看到或写出的内容。关掉就完全回到默认行为。"

    override fun pluginTitle(id: String): String = when (id) {
        "dsh-token-saver" -> "结果裁剪"
        "dsh-plugin-save-token" -> "TOON 编码"
        "dsh-whale" -> "极简回复"
        "dsh-fengshen" -> "首轮路由"
        else -> id
    }

    override fun pluginDesc(id: String): String = when (id) {
        "dsh-token-saver" ->
            "工具结果太长时保留头尾、折叠中间。不改含义，只删冗余"
        "dsh-plugin-save-token" ->
            "把 JSON 结果压成表格形态，原文存进工作空间可随时取回（省得更多，但换了形态）"
        "dsh-whale" ->
            "回复不复述、不预告、不总结，省输出 token"
        "dsh-fengshen" ->
            "首轮判断是修 bug 还是建功能，只给读写执行 4 个工具（云端编译等下一轮才出现）"
        else -> ""
    }

    override val entryProviderUnset = "未配置"
    override val entryUsageDesc = "token 消耗与估算花费"
    override val entryPairDesc = "连接电脑上的 DSH"
    override val entryWorkspaceDesc = "AI 生成的文件与构建产物"
    override val entryDiagnosticsDesc = "连不上时先跑这个，能看到每一步的原始报错"
    override val entryAboutDesc = "版本、更新日志与作者"
    override val entryAgentStepsDesc = "不再按轮数限制，停滞时自动停下"
    override val entryStorageDesc = "会话、工作空间与缓存合计"
    override val storageUsed = "本地占用"

    override val providersTitle = "供应商"
    override val addProvider = "新增供应商"
    override val editProvider = "编辑供应商"
    override val newProvider = "新供应商"
    override val providerName = "名称"
    override val providerType = "类型"
    override val typeOpenAi = "直连 API"
    override val typeOpenAiHint = "OpenAI 兼容，直接用自己的 API Key"
    override val typeDsh = "DSH 服务器"
    override val typeDshHint = "连接电脑上运行的 DeepSeek Harness"
    override val baseUrl = "接口地址"
    override val baseUrlHint = "如 https://api.deepseek.com/v1"
    override val apiKey = "API Key"
    override val apiKeyHint = "sk-…（只保存在本机加密存储）"
    override val apiKeySaved = "已保存，留空表示不修改"
    override val defaultModel = "默认模型"
    override val modelsTitle = "模型列表"
    override val fetchModels = "从服务端拉取"
    override val fetchingModels = "拉取中…"
    override val addModelManually = "手动添加模型"
    override val modelNameInput = "模型名，如 deepseek-chat"
    override val temperature = "温度"
    override val topP = "top_p"
    override val maxTokens = "最大输出 tokens"
    override val contextWindow = "上下文窗口"
    override val systemPrompt = "系统提示词"
    override val systemPromptHint = "留空则使用预设"
    override val preset = "预设"
    override val customPrompt = "追加指令"
    override val customPromptHint = "在预设之后追加的自定义要求"
    override val reasoningEffort = "思考强度"
    override val streamEnabled = "流式输出"
    override val requestUsage = "请求精确用量"
    override val priceIn = "输入单价"
    override val priceOut = "输出单价"
    override val pricePerMillion = "元 / 百万 tokens"
    override val setActive = "设为当前"
    override val activeProvider = "当前使用"
    override val deleteProvider = "删除供应商"
    override val deleteProviderConfirm = "同时会清除本机保存的 Key，确定吗？"
    override val testConnection = "测试连接"
    override val testing = "测试中…"
    override val connectionFailed = "连接失败"

    override val pairTitle = "配对 DSH 服务器"
    override val pairHint = "在电脑端 DSH 打开 dsh-workspace 的「远程访问」，启用并创建配对，把地址和一次性配对码填到这里。"
    override val serverAddress = "服务器地址"
    override val pairingCode = "配对码"
    override val pairingCodeHint = "10 分钟内有效"
    override val deviceName = "设备名称"
    override val pairNow = "配对并连接"
    override val pairing = "配对中…"
    override val pairSuccess = "配对成功"
    override val pairFailed = "配对失败"
    override val pairedDevice = "已配对设备"
    override val unpair = "解除配对"

    override val usageTitle = "Token 用量"
    override val periodToday = "今天"
    override val periodWeek = "近 7 天"
    override val periodMonth = "本月"
    override val periodAll = "全部"
    override val totalTokens = "总 tokens"
    override val promptTokens = "输入"
    override val completionTokens = "输出"
    override val cachedTokens = "命中缓存"
    override val requestsLabel = "请求数"
    override val costLabel = "估算花费"
    override val byModel = "按模型"
    override val byDay = "按天"
    override val noUsage = "还没有用量记录"
    override val clearUsage = "清空用量记录"
    override val clearUsageConfirm = "只清除统计，不影响会话内容。确定吗？"

    override val aboutTitle = "关于"
    override val version = "版本"
    override val aboutDesc = "非官方第三方 DSH 客户端，为方形小屏手表做了渲染与内存优化。"
    override val builtWith = "Kotlin · Jetpack Compose · Material 3"
    override val deviceInfo = "设备信息"
    override val sourceHint = "本项目基于开源社区的 DeepSeek Harness 客户端思路重写，与 DeepSeek 官方无关。"

    override val updateCheck = "检查更新"
    override val autoCheckUpdate = "自动检查更新"
    override val updateTitle = "检查更新"
    override val updateNewVersion = "有新版本"
    override val updateChecking = "正在检查…"
    override val updateUpToDate = "已经是最新版本"
    override val updateCurrent = "当前版本"
    override val updateLatest = "最新版本"
    override val updateLastCheck = "上次检查"
    override val updateDownloadInstall = "下载并安装"
    override val updateDownloading = "正在下载…"
    override val updateInstall = "安装"
    override val updateReady = "已下载，可以安装"
    override val updateRetry = "重试"
    override val updateNeedPermission = "需要先允许「安装未知应用」"
    override val updateGrant = "去授权"
    override val updateNoSettings = "这台设备没有这个设置页。用数据线执行一次下面的命令即可："
    override val updateCopied = "已复制"
    override val updateInstallFailed = "无法调起系统安装器"
    override val updateNotes = "更新内容"

    override val navDiagnostics = "诊断"
    override val diagTitle = "连接诊断"
    override val diagRun = "开始诊断"
    override val diagRunning = "诊断中…"
    override val diagCopyAll = "复制全部结果"
    override val diagCopied = "已复制，可以发给开发者"
    override val diagEnv = "当前配置"
    override val diagSteps = "分步结果"
    override val diagEmpty = "点上面的按钮开始。会依次验证：配置 → 模型列表 → 对话接口 → 流式输出。"
    override val diagPass = "通过"
    override val diagFail = "失败"
    override val diagHint = "「对话接口」和「流式输出」才是发消息真正走的路径。哪一步红就说明问题在哪一层，把这里的原文复制出来即可定位。"

    override val groupAgent = "AI Agent"
    override val agentMode = "工具调用策略"
    override val agentMaxSteps = "步数限制"
    override val agentTools = "可用工具"
    override val agentToolsHint = "模型可以自主调用这些工具"
    override val agentClearApprovals = "清除「总是允许」记录"
    override val toolCall = "工具调用"
    override val toolStatusPending = "待执行"
    override val toolStatusAwaiting = "等待确认"
    override val toolStatusRunning = "执行中"
    override val toolStatusSuccess = "成功"
    override val toolStatusFailed = "失败"
    override val toolStatusDenied = "已拒绝"
    override val toolStatusSkipped = "已跳过"
    override val approveOnce = "允许一次"
    override val approveAlways = "总是允许"
    override val approveSession = "本会话允许"
    override val deny = "拒绝"
    override val stepLabel = "步"
    override val toolArgs = "参数"
    override val toolResult = "结果"
    override val cornerInset = "屏幕圆角安全边距"
    override val cornerInsetAuto = "自动"
    override val screenInfo = "屏幕"

    override val navWorkspace = "工作空间"
    override val workspaceTitle = "本地工作空间"
    override val workspaceEmpty = "工作空间还是空的"
    override val workspaceHint = "让 AI 写点东西，它会在这里创建文件并构建出可预览的产物"
    override val workspaceBuild = "构建"
    override val workspaceFiles = "个文件"
    override val workspacePreview = "预览"
    override val workspaceShare = "导出"
    override val workspaceDeleteConfirm = "删除后不可恢复，确定吗？"
    override val workspaceFilterAll = "全部"
    override val workspaceArtifacts = "产物"
    override val workspaceSource = "源码"
    override val workspaceOpen = "在预览里打开"
    override val workspaceNoPreview = "这个类型不能预览，只能导出"
    override val refresh2 = "刷新"
    override val workspaceLocation = "文件存放位置"
    override val workspaceRootPath = "当前实际路径"
    override val workspaceExternalFallback = "该设备没有外部私有目录，已回退到应用内部"

    override val artifact = "产物"
    override val openArtifact = "打开"
    override val exportArtifact = "导出"
    override val toolDetails = "调用详情"
    override val moreTools = "更多"
    override val allArtifacts = "全部产物"
    override val artifactCount = "个产物"

    override val aboutAuthor = "关于作者"
    override val authorContact = "联系方式"
    override val aboutChangelog = "更新日志"
    override val aboutIntro = "应用介绍"
    override val techStack = "开发技术栈"
    override val inspiredBy = "借鉴项目"
    override val appFeatures = "功能"
    override val techStackText = "Kotlin + Jetpack Compose（Wear Material 3）\n" +
        "自研 Markdown 渲染，不用 WebView（省 30–60MB 常驻内存）\n" +
        "自研 LaTeX 排版 · 思维导图布局 · 几何画板，纯 Canvas 绘制，不引图库\n" +
        "手写 SSE 流式解析 · 自研递归下降表达式求值\n" +
        "OkHttp + kotlinx.serialization\n" +
        "DataStore + Keystore 加密存储 · 文件级会话持久化\n" +
        "单模块，R8 全量混淆"
    override val inspiredByText = "DSH 服务器模式参考 dsh-workspace 的配对协议形态；\n" +
        "B站读写接口参考开源第三方 B站终端（bili 终端类项目）的网页端实现；\n" +
        "产品形态参考社区 DSH 客户端（如 dsh-android-app）的思路。\n" +
        "本项目为独立实现，未复用上述项目的代码；\n" +
        "界面与工程全部自研，针对手表屏幕从零设计。"
    override val showMascot = "显示看板娘"
    override val mascotHint = "聊天空态插图与 AI 回复头像"

    override val peakNow = "高峰时段 · 价格较高"
    override val offPeakNow = "空闲时段 · 约为高峰半价"
    override val toPeak = "后转高峰"
    override val toOffPeak = "后转空闲"

    override val sessionWorkDir = "文件位置"
    override val sessionWorkDirHint = "这个会话里 AI 生成的文件都放在这里，互不干扰"
    override val workDirPick = "选择已有文件夹"
    override val workDirNew = "新建文件夹"
    override val workDirNewPlaceholder = "文件夹名，例如 my-site"
    override val workDirUseRoot = "放到工作空间根目录"
    override val workDirEmpty = "还没有任何文件夹"

    override val agentAsking = "AI 想确认一下"
    override val agentAskSubmit = "回答并继续"
    override val agentAskSkip = "跳过这题"
    override val agentAskPlaceholder = "也可以自己输入…"

    override val shareFailed = "导出失败"
    override val appFeaturesText = "双内置通道：Agnes 与智谱 GLM 免费档，开箱即用；密钥由应用托管，用户只调参数\n" +
        "多模态：对话页直接显示图片与视频，可附图提问\n" +
        "文件问答：txt / md / docx / pdf / 代码，本地提取文本后随消息发送\n" +
        "画布：LaTeX 公式、思维导图、几何图，AI 生成后可直接看、可全屏\n" +
        "AI Agent：本地工具 + 多步循环，含真实 B站读写（收藏夹 / 点赞 / 投币 / 收藏）\n" +
        "本地工作空间：真建文件 → 校验 → 打包成单文件 HTML，可预览可导出\n" +
        "Token 用量看板 · 手表性能分档与圆屏安全边距"

    // ---------------- 执行与云端编译 ----------------
    override val execSectionTitle = "执行与编译"
    override val execSectionDesc = "在设备上真的跑代码，或交给云端编译"
    override val execScreenTitle = "执行与编译"
    override val execEnable = "启用本地执行"
    override val execEnableDesc = "允许 AI 在本机运行 shell / JS 脚本"
    override val execTimeoutLabel = "执行超时"
    override val execMaxOutputLabel = "输出上限"
    override val execSecondsSuffix = " 秒"
    override val execForceRelease = "强制释放执行闸门"
    override val execForceReleaseDesc = "上一次脚本卡住不动时用"
    override val execConfirmNotice =
        "本地执行权限很大：脚本能读写当前工作目录、能联网。" +
            "开启后每次执行仍会单独确认一次，不会因为你点过「总是允许」就永久放行。"
    override val cloudEnable = "启用云端编译"
    override val cloudBuildEta = "云端构建通常 3–8 分钟，首次要下依赖会更久"
    override val cloudEnableDesc = "把手表做不了的事（编 apk、跑 gradle）交给 GitHub Actions"
    override val cloudRepoLabel = "目标仓库"
    override val cloudRepoHint = "只写 owner/repo，如 dsdzm/DSH-Watch"
    override val cloudRefLabel = "分支"
    override val cloudWorkflowLabel = "默认 workflow（文件名）"
    override val cloudTokenLabel = "GitHub 令牌"
    override val cloudTokenHint = "需要 Actions 读写权限；只加密存在本机"
    override val cloudInstallTemplates = "安装 workflow 模板到仓库"
    override val cloudTestLabel = "测试连接"

    override val onboardingTitle = "先认一下你的表"
    override val onboardingSubtitle = "三步把界面调成适合这块屏幕的样子，之后在设置里随时能改。"
    override val onboardingShapeTitle = "屏幕形状"
    override val onboardingShapeHint = "圆屏四角没有像素，贴边的内容会被切掉；选错会让界面白白内收。"
    override val onboardingLayoutTitle = "对话布局"
    override val onboardingLayoutHint = "同一段对话，四种读法。选个顺眼的，之后随时换。"
    override val onboardingThemeTitle = "主题"
    override val onboardingThemeHint = "先挑底色，再挑强调色。OLED 纯黑底最省电。"
    override val onboardingNext = "下一步"
    override val onboardingBack = "上一步"
    override val onboardingStart = "开始使用"
    override val onboardingDetectedRound = "系统检测：圆形屏"
    override val onboardingDetectedSquare = "系统检测：方形屏"
    override val shapeRound = "圆形"
    override val shapeSquare = "方形"
    override val shapeAuto = "跟随系统"
    override val layoutBubble = "气泡"
    override val layoutCard = "卡片"
    override val layoutMinimal = "极简"
    override val layoutBubbleHint = "左右气泡，对话感最强"
    override val layoutCardHint = "每条一张卡片，信息分层清楚"
    override val layoutMinimalHint = "去掉底色，小屏也能多读两行"
    override val screenShape = "屏幕形状"
    override val chatLayout = "对话布局"
    override val themePreset = "主题预设"
    override val themePresetHint = "换一套底色，不影响明暗与字号"
    override val accentColor = "强调色"
    override val rerunOnboarding = "重新运行引导"
    override val rerunOnboardingHint = "重选屏幕形状、布局与主题；不会删除任何对话"

    override fun themePresetLabel(id: String) =
        com.dshwatch.ui.theme.ThemePresets.of(id).labelZh

    override fun accentLabel(id: String) =
        com.dshwatch.ui.theme.Accents.of(id).labelZh

    override fun chatLayoutLabel(id: String) = when (com.dshwatch.domain.model.ChatLayouts.of(id)) {
        com.dshwatch.domain.model.ChatLayouts.CARD -> layoutCard
        com.dshwatch.domain.model.ChatLayouts.MINIMAL -> layoutMinimal
        else -> layoutBubble
    }

    override fun chatLayoutHint(id: String) = when (com.dshwatch.domain.model.ChatLayouts.of(id)) {
        com.dshwatch.domain.model.ChatLayouts.CARD -> layoutCardHint
        com.dshwatch.domain.model.ChatLayouts.MINIMAL -> layoutMinimalHint
        else -> layoutBubbleHint
    }

    override fun toolTitle(tool: String): String = when (tool) {
        "tool_search" -> "取用工具"
        "get_datetime" -> "查看时间"
        "calculate" -> "计算"
        "get_device_info" -> "查看设备信息"
        "get_token_usage" -> "查询用量"
        "http_fetch" -> "联网获取"
        "set_alarm" -> "设置闹钟"
        "list_files" -> "查看文件列表"
        "read_file" -> "读取文件"
        "write_file" -> "创建文件"
        "append_file" -> "追加内容"
        "make_dir" -> "新建文件夹"
        "delete_file" -> "删除文件"
        "build_project" -> "校验构建"
        "edit_file" -> "编辑文件"
        "glob" -> "查找文件"
        "grep" -> "搜索内容"
        "todo_write" -> "更新任务清单"
        "open_file" -> "打开文件"
        "open_screen" -> "打开页面"
        "create_event" -> "新建日程"
        "move_file" -> "移动文件"
        "ask_user" -> "向你提问"
        "list_alarms" -> "查看闹钟"
        "cancel_alarm" -> "取消闹钟"
        "vibrate" -> "震动提醒"
        "get_clipboard" -> "读取剪贴板"
        "unit_convert" -> "单位换算"
        "web_search" -> "联网搜索"
        "run_script" -> "运行脚本"
        "cloud_build" -> "云端编译"
        "cloud_job" -> "云端任务"
        "bili_status" -> "B站状态"
        "bili_search" -> "搜B站"
        "bili_video" -> "视频详情"
        "bili_subtitle" -> "读字幕"
        "bili_comments" -> "看评论"
        "bili_inbox" -> "看回复"
        "bili_thread" -> "看评论楼"
        "bili_report" -> "写日报"
        "bili_reply" -> "发评论"
        "bili_dm" -> "发私信"
        "bili_dyn" -> "发动态"
        "bili_like" -> "点赞"
        // 标题限 8 字：工具卡只有一行，超了会被截成省略号（ToolTitleTest 会红）
        "bili_fav_folders" -> "看收藏夹"
        "bili_fav_list" -> "收藏夹内容"
        "bili_coin" -> "投币"
        "bili_fav" -> "收藏视频"
        "device_screen" -> "看屏幕"
        "device_act" -> "操作屏幕"
        "device_open" -> "打开应用"
        "device_handoff" -> "交给用户"
        else -> tool
    }
}

object En : AppStrings {
    override val appName = "DSH Watch"
    override val ok = "OK"
    override val cancel = "Cancel"
    override val save = "Save"
    override val delete = "Delete"
    override val confirm = "Confirm"
    override val close = "Close"
    override val copy = "Copy"
    override val copied = "Copied"
    override val retry = "Retry"
    override val stop = "Stop"
    override val send = "Send"
    override val add = "Add"
    override val done = "Done"
    override val none = "None"
    override val loading = "Loading…"
    override val test = "Test"
    override val reset = "Reset"
    override val rename = "Rename"
    override val more = "More"
    override val clear = "Clear"
    override val yes = "Yes"
    override val no = "No"

    override val navChat = "Chat"
    override val homeContinue = "Continue"
    override val navSessions = "Chats"
    override val navSettings = "Settings"
    override val helpTitle = "Help"
    override val helpSubtitle = "Panels / choices / import"
    override val helpCopyAll = "Copy page"
    override val backToBottom = "Jump to latest"
    override val navUsage = "Usage"
    override val navProviders = "Providers"
    override val navAbout = "About"
    override val back = "Back"

    override val chatEmptyTitle = "Start vibe coding"
    override val roleplayEmptyTitle = "No character yet"
    override val roleplayEmptyHint = "Go back and pick a card, or create one"
    override val chatEmptyHint = "Tap the input below — you get runnable code, not small talk"
    override val inputHint = "Say something…"
    override val thinking = "Thinking"
    override val reasoning = "Reasoning"
    override val expandReasoning = "Show reasoning"
    override val collapseReasoning = "Collapse"
    override val workflowTitle = "Workflow"
    override val workflowNow = "Now"
    override val workflowDone = "done"
    override val workflowFailed = "has failures"
    override val workflowExpand = "Show workflow"
    override val workflowCollapse = "Collapse"
    override val regenerate = "Regenerate"
    override val copyMessage = "Copy text"
    override val deleteMessage = "Delete message"
    override val messageMenu = "Message actions"
    override val contextUsage = "Context"
    override val tokensLabel = "tokens"
    override val estimatedTag = "est."
    override val modelLabel = "Model"
    override val switchModel = "Switch model"
    override val generating = "Generating"
    override val stopped = "Stopped"
    override val sendFailed = "Send failed"
    override val scrollToBottom = "To bottom"
    override val newChat = "New chat"

    override val sessionsTitle = "Chats"
    override val noSessions = "No chats yet"
    override val deleteSession = "Delete chat"
    override val deleteSessionConfirm = "Local history cannot be recovered. Continue?"
    override val renameSession = "Rename"
    override val pin = "Pin"
    override val unpin = "Unpin"
    override val messagesCount = "messages"

    override val settingsTitle = "Settings"
    override val groupAppearance = "Appearance"
    override val theme = "Theme"
    override val themeSystem = "System"
    override val themeDark = "Dark"
    override val themeLight = "Light"
    override val dynamicColor = "Dynamic color"
    override val dynamicColorHint = "Follow wallpaper on Android 12+"
    override val uiScale = "UI size"
    override val uiScaleHint = "Scales text, cards and spacing together"
    override val uiScaleAuto = "Match device"
    override val uiScaleAutoOn = "Fitted to this screen"
    override val uiScaleReset = "Use device fit"
    override fun uiScaleOver(recommended: String) =
        "Beyond $recommended for this screen — edges may be clipped"
    override val fontSize = "Font size"
    override val fontSmall = "S"
    override val fontNormal = "M"
    override val fontLarge = "L"
    override val fontPreview = "This line resizes with the slider above"
    override val language = "Language"
    override val isChinese = false
    override val langSystem = "System"
    override val langZh = "中文"
    override val langEn = "English"
    override val groupPerformance = "Performance"
    override val perfMode = "Performance preset"
    override val perfAuto = "Auto"
    override val perfPowerSave = "Battery saver"
    override val perfBalanced = "Balanced"
    override val perfPerformance = "Performance"
    override val deviceTier = "Detected tier"
    override val tierLow = "Low-power watch"
    override val tierMid = "Mid range"
    override val tierHigh = "High end"
    override val maxRenderedMessages = "Max rendered messages"
    override val streamThrottle = "Stream refresh interval"
    override val followProfile = "Follow preset"
    override val groupBehavior = "Behaviour"
    override val keepScreenOn = "Keep screen on while generating"
    override val haptics = "Haptics"
    override val foregroundService = "Keep connection in background"
    override val foregroundServiceHint = "Keeps generating when screen off"
    override val autoScroll = "Auto scroll"
    override val showReasoning = "Show reasoning"
    override val showTokenBadge = "Show token badge"
    override val groupData = "Data"

    override val groupModel = "Model"
    override val groupAbility = "AI capability"
    override val groupChat = "Chat"
    override val groupFiles = "Files & storage"
    override val groupRuntime = "Performance & runtime"
    override val groupConnection = "Connection & about"
    override val groupPlugins = "Plugins"
    override val groupMcp = "MCP servers"
    override val mcpHint =
        "MCP connects the watch to external services (GitHub, databases, search…). " +
            "Their tool descriptions go into the model context, so the first connection shows you the list first."
    override val mcpAdd = "Add server"
    override val mcpNameLabel = "Name"
    override val mcpUrlLabel = "MCP URL"
    override val mcpTokenLabel = "Access token"
    override val mcpConnect = "Save and connect"
    override val mcpToolsTitle = "Tools"
    override val mcpToolsHint = "These descriptions go to the model. Turn off anything you do not recognise."
    override val mcpEmpty = "No servers yet"

    override val groupBili = "Bilibili"
    override val biliEnable = "Bilibili tools"
    override val biliEnableHint = "Search, subtitles, daily digest. Off = the bili_* tools disappear"
    override val biliComment = "Allow commenting"
    override val biliCommentHint = "Off by default. When off, replies are previewed but never sent"
    override val biliQuota = "Daily quota"
    override val biliQuotaHint = "How many picks per day; 0 means unlimited"
    override val biliKeywords = "Keywords"
    override val biliKeywordsHint = "Comma separated; leave empty to let the model decide"
    override val biliCookie = "Login cookie"
    override val biliCookieHint = "Only needed for commenting. SESSDATA + bili_jct, encrypted on device"
    override val biliCookieUnset = "Not set"
    override val biliOpenDigest = "Today's digest"
    override val biliHint =
        "The loop is: search → read the actual subtitles → judge → write the digest. " +
            "Digests live under bili/ in the workspace; each '### BV id' line counts toward the quota."
    override val biliLogin = "Scan to sign in"
    override val biliLoginHint = "Scan the code on the watch with the Bilibili app, or paste cookies instead"
    override val biliLoginWaiting = "Waiting for scan…"
    override val biliLoginScanned = "Scanned — confirm on your phone"
    override val biliLoginDone = "Signed in"
    override val biliLoginExpired = "QR code expired"
    override val biliLoginRetry = "New code"
    override val biliLoginPrivacy = "Only the login ticket is stored, encrypted on this device"
    override val biliTone = "Comment tone"
    override val biliToneHint = "How it sounds when it speaks for you"
    override val biliToneFish = "Lazy freeloader fish"
    override val biliToneSerious = "Serious review"
    override val biliToneDeadpan = "Deadpan memes"
    override val biliToneCustom = "Custom"
    override val biliToneCustomHint = "Describe who it should sound like; passed to the model as-is"
    override val biliNotifyDone = "Notify when done"
    override val biliNotifyDoneHint = "Ping me when a background run finishes"
    override val biliMode = "After a run"
    override val biliModeSummary = "Digest only"
    override val biliModeSummaryHint = "Write the digest, post nothing"
    override val biliModeComment = "Comment"
    override val biliModeCommentHint = "Comment on videos and reply to people"
    override val biliModeDm = "Direct message"
    override val biliModeDmHint = "DM the uploader (cannot be recalled)"
    override val biliAtUid = "Mention UID"
    override val biliAtUidHint = "Comments will @ this user (usually yourself)"
    override val biliImage = "Comment image"
    override val biliImageNone = "None"
    override val biliImageNoneHint = "Text only"
    override val biliImagePack = "Fish stickers"
    override val biliImagePackHint = "4 built-in stickers, one picked at random"
    override val biliImageCard = "Fish card"
    override val biliImageCardHint = "Auto-drawn dark card with your own line"
    override val biliImageLocal = "From gallery"
    override val biliImageLocalHint = "Pick one image and reuse it every time"
    override val biliImageText = "Card text"
    override val biliImageTextHint = "Empty = rotates daily"
    override val biliImagePickFailed = "Couldn't read that image, try another"
    override val biliCommentQuota = "Daily post limit"
    override val biliCommentQuotaHint = "Comments + DMs combined; stops before spam detection"
    override val biliDyn = "Publish dynamics"
    override val biliDynHint = "Let it post a stray thought; shows on followers' timeline"
    override val biliDynQuota = "Daily dynamic limit"
    override val biliDynQuotaHint = "Counted separately, also counts toward the total above"
    override val biliDynTopicTitle = "What to post"
    override val biliDynTopicHint = "Multi-select; it picks one angle itself"
    override val biliDynTopicLife = "Daily life"
    override val biliDynTopicLifeHint = "What it's doing, eating, or slacking off from"
    override val biliDynTopicRoast = "Video roast"
    override val biliDynTopicRoastHint = "About the video it just watched"
    override val biliDynTopicMaster = "Feelings for you"
    override val biliDynTopicMasterHint = "Follows you around, complains but stays"
    override val biliLike = "Auto like"
    override val biliLikeHint = "Only when it genuinely likes something; once per video per day"
    override val biliCoin = "Allow coin tipping"
    override val biliCoinHint = "Coins are irreversible, off by default; max 1 per video, counted separately"
    override val biliFav = "Allow favourites"
    override val biliFavHint = "It will add items to your favourites folder; off by default"
    override val biliFavFolder = "Favourites folder ID"
    override val biliFavFolderHint = "Empty = default folder; otherwise only that folder is used"
    override val communityTitle = "Bug report group"
    override val communityHint = "Scan to join; report bugs or request features"
    override val communityGroup = "Group ID"
    override val communityCopy = "Copy ID"

    override val communitySection = "Community & support"
    override val navGroupChat = "Join the group"
    override val navGroupChatHint = "QQ group for help and feedback"
    override val navDonate = "Support the author"
    override val navDonateHint = "Buy the fish a coffee"
    override val navThanks = "Special thanks"
    override val navThanksHint = "This app exists because of them"

    override val groupTitle = "Join the group"
    override val groupIntro = "Scan to join, or type the group ID. Bugs, requests, complaints — all welcome."
    override val groupLongPress = "Long-press the ID to copy"

    override val donateTitle = "Support the author"
    override val donateIntro = "Free, no ads. Servers and signing keys come out of my own pocket."
    override val donateScan = "WeChat Pay, any amount"
    override val donateNote = "Everything works without paying. Don't strain your wallet over it."
    override val donateThanks = "Thank you"

    override val thanksTitle = "Special thanks"
    override val thanksIntro = "This app stands on a lot of shoulders."
    override val thanksPeople = "Testers & feedback"
    override val thanksProjects = "Open source"
    override val thanksFooter = "The list grows with each release. Ping me in the group if I missed you."
    override val communityCopied = "Copied"
    override val biliAutoRun = "Run daily"
    override val biliAutoRunHint = "Fires on schedule and handles it as selected above"
    override val biliAutoTime = "Run at"
    override val biliAutoTimeHint = "Defaults to 03:30, off-peak"
    override val biliAutoTimeTip = "May slip a few minutes in power-saving mode"
    override val biliToneNaughty = "Naughty (absurd / on strike)"
    override val pluginsHint =
        "Plugins change what the model sees or writes. Turning one off restores the default behaviour."

    override fun pluginTitle(id: String): String = when (id) {
        "dsh-token-saver" -> "Result trimming"
        "dsh-plugin-save-token" -> "TOON encoding"
        "dsh-whale" -> "Terse replies"
        "dsh-fengshen" -> "First-turn routing"
        else -> id
    }

    override fun pluginDesc(id: String): String = when (id) {
        "dsh-token-saver" ->
            "Keep head and tail of long tool results, fold the middle. Meaning preserved"
        "dsh-plugin-save-token" ->
            "Re-encode JSON results as tables; the original is saved in the workspace (saves more, changes shape)"
        "dsh-whale" ->
            "No restating, no preamble, no summary — saves output tokens"
        "dsh-fengshen" ->
            "First turn decides bug-fix vs build, exposes only 4 file/exec tools (cloud tools return next turn)"
        else -> ""
    }

    override val entryProviderUnset = "Not configured"
    override val entryUsageDesc = "Token usage and estimated cost"
    override val entryPairDesc = "Connect to DSH on your computer"
    override val entryWorkspaceDesc = "Files and build outputs from AI"
    override val entryDiagnosticsDesc = "Run this first when it fails; shows the raw error at each step"
    override val entryAboutDesc = "Version, changelog and author"
    override val entryAgentStepsDesc = "No round limit; stops when stalled"
    override val entryStorageDesc = "Sessions, workspace and cache combined"
    override val storageUsed = "Local storage"

    override val providersTitle = "Providers"
    override val addProvider = "Add provider"
    override val editProvider = "Edit provider"
    override val newProvider = "New provider"
    override val providerName = "Name"
    override val providerType = "Type"
    override val typeOpenAi = "Direct API"
    override val typeOpenAiHint = "OpenAI compatible, bring your own key"
    override val typeDsh = "DSH server"
    override val typeDshHint = "Connect to DeepSeek Harness on your computer"
    override val baseUrl = "Base URL"
    override val baseUrlHint = "e.g. https://api.deepseek.com/v1"
    override val apiKey = "API Key"
    override val apiKeyHint = "sk-… (stored encrypted on device)"
    override val apiKeySaved = "Saved — leave blank to keep"
    override val defaultModel = "Default model"
    override val modelsTitle = "Models"
    override val fetchModels = "Fetch from server"
    override val fetchingModels = "Fetching…"
    override val addModelManually = "Add model manually"
    override val modelNameInput = "Model id, e.g. deepseek-chat"
    override val temperature = "Temperature"
    override val topP = "top_p"
    override val maxTokens = "Max output tokens"
    override val contextWindow = "Context window"
    override val systemPrompt = "System prompt"
    override val systemPromptHint = "Leave blank to use preset"
    override val preset = "Preset"
    override val customPrompt = "Extra instruction"
    override val customPromptHint = "Appended after the preset"
    override val reasoningEffort = "Reasoning effort"
    override val streamEnabled = "Streaming"
    override val requestUsage = "Request exact usage"
    override val priceIn = "Input price"
    override val priceOut = "Output price"
    override val pricePerMillion = "CNY / 1M tokens"
    override val setActive = "Set active"
    override val activeProvider = "Active"
    override val deleteProvider = "Delete provider"
    override val deleteProviderConfirm = "The stored key will be removed too. Continue?"
    override val testConnection = "Test connection"
    override val testing = "Testing…"
    override val connectionFailed = "Connection failed"

    override val pairTitle = "Pair DSH server"
    override val pairHint = "On your computer open dsh-workspace → Remote access, enable and create a pairing code."
    override val serverAddress = "Server address"
    override val pairingCode = "Pairing code"
    override val pairingCodeHint = "Valid for 10 minutes"
    override val deviceName = "Device name"
    override val pairNow = "Pair and connect"
    override val pairing = "Pairing…"
    override val pairSuccess = "Paired"
    override val pairFailed = "Pairing failed"
    override val pairedDevice = "Paired device"
    override val unpair = "Unpair"

    override val usageTitle = "Token usage"
    override val periodToday = "Today"
    override val periodWeek = "7 days"
    override val periodMonth = "This month"
    override val periodAll = "All time"
    override val totalTokens = "Total tokens"
    override val promptTokens = "Input"
    override val completionTokens = "Output"
    override val cachedTokens = "Cached"
    override val requestsLabel = "Requests"
    override val costLabel = "Est. cost"
    override val byModel = "By model"
    override val byDay = "By day"
    override val noUsage = "No usage yet"
    override val clearUsage = "Clear usage"
    override val clearUsageConfirm = "Only statistics are removed. Continue?"

    override val aboutTitle = "About"
    override val version = "Version"
    override val aboutDesc = "Unofficial third-party DSH client, tuned for small square watch screens."
    override val builtWith = "Kotlin · Jetpack Compose · Material 3"
    override val deviceInfo = "Device"
    override val sourceHint = "Rewritten from community DSH client ideas. Not affiliated with DeepSeek."

    override val updateCheck = "Check for updates"
    override val autoCheckUpdate = "Auto-check for updates"
    override val updateTitle = "Update"
    override val updateNewVersion = "New version"
    override val updateChecking = "Checking…"
    override val updateUpToDate = "You are up to date"
    override val updateCurrent = "Current"
    override val updateLatest = "Latest"
    override val updateLastCheck = "Last checked"
    override val updateDownloadInstall = "Download and install"
    override val updateDownloading = "Downloading…"
    override val updateInstall = "Install"
    override val updateReady = "Downloaded, ready to install"
    override val updateRetry = "Retry"
    override val updateNeedPermission = "Allow installing unknown apps first"
    override val updateGrant = "Allow"
    override val updateNoSettings = "This device has no such settings page. Run this once over USB:"
    override val updateCopied = "Copied"
    override val updateInstallFailed = "Could not open the system installer"
    override val updateNotes = "What's new"

    override val navDiagnostics = "Diagnostics"
    override val diagTitle = "Connection diagnostics"
    override val diagRun = "Run diagnostics"
    override val diagRunning = "Running…"
    override val diagCopyAll = "Copy all results"
    override val diagCopied = "Copied"
    override val diagEnv = "Current config"
    override val diagSteps = "Step results"
    override val diagEmpty = "Tap the button above. It checks: config → models → chat endpoint → streaming."
    override val diagPass = "PASS"
    override val diagFail = "FAIL"
    override val diagHint = "The chat endpoint and streaming steps are what sending a message actually uses."

    override val groupAgent = "AI Agent"
    override val agentMode = "Tool policy"
    override val agentMaxSteps = "Step limit"
    override val agentTools = "Available tools"
    override val agentToolsHint = "Tools the model may call"
    override val agentClearApprovals = "Clear \"always allow\" list"
    override val toolCall = "Tool call"
    override val toolStatusPending = "Pending"
    override val toolStatusAwaiting = "Awaiting approval"
    override val toolStatusRunning = "Running"
    override val toolStatusSuccess = "OK"
    override val toolStatusFailed = "Failed"
    override val toolStatusDenied = "Denied"
    override val toolStatusSkipped = "Skipped"
    override val approveOnce = "Allow once"
    override val approveAlways = "Always allow"
    override val approveSession = "Allow this session"
    override val deny = "Deny"
    override val stepLabel = "step"
    override val toolArgs = "Args"
    override val toolResult = "Result"
    override val cornerInset = "Screen corner safe inset"
    override val cornerInsetAuto = "Auto"
    override val screenInfo = "Screen"

    override val navWorkspace = "Workspace"
    override val workspaceTitle = "Local workspace"
    override val workspaceEmpty = "Workspace is empty"
    override val workspaceHint = "Ask the AI to build something — files will appear here"
    override val workspaceBuild = "Build"
    override val workspaceFiles = "files"
    override val workspacePreview = "Preview"
    override val workspaceShare = "Export"
    override val workspaceDeleteConfirm = "This cannot be undone. Continue?"
    override val workspaceFilterAll = "All"
    override val workspaceArtifacts = "Built"
    override val workspaceSource = "Source"
    override val workspaceOpen = "Open preview"
    override val workspaceNoPreview = "This type cannot be previewed"
    override val refresh2 = "Refresh"
    override val workspaceLocation = "File location"
    override val workspaceRootPath = "Current path"
    override val workspaceExternalFallback = "No external files dir on this device; falling back to internal"

    override val artifact = "Artifact"
    override val openArtifact = "Open"
    override val exportArtifact = "Export"
    override val toolDetails = "Details"
    override val moreTools = "More"
    override val allArtifacts = "All artifacts"
    override val artifactCount = "artifacts"

    override val aboutAuthor = "Author"
    override val authorContact = "Contact"
    override val aboutChangelog = "Changelog"
    override val aboutIntro = "About this app"
    override val techStack = "Tech stack"
    override val inspiredBy = "Inspired by"
    override val appFeatures = "Features"
    override val techStackText = "Kotlin + Jetpack Compose (Wear Material 3)\n" +
        "Custom Markdown renderer, no WebView (saves 30-60MB RSS)\n" +
        "Custom LaTeX layout, mind-map layout and geometry board, pure Canvas, no chart libs\n" +
        "Hand-written SSE parser · custom recursive-descent expression evaluator\n" +
        "OkHttp + kotlinx.serialization\n" +
        "DataStore + Keystore encryption · file-backed conversation storage\n" +
        "Single module, full R8 shrinking"
    override val inspiredByText = "The DSH server mode follows the pairing protocol shape of dsh-workspace;\n" +
        "Bilibili read/write calls follow the web endpoints used by open-source bili terminal projects;\n" +
        "the product concept draws on community DSH clients (e.g. dsh-android-app).\n" +
        "This project is an independent implementation and reuses none of their code;\n" +
        "the UI and engineering are built from scratch for watch-sized screens."
    override val showMascot = "Show mascot"
    override val mascotHint = "Illustration and AI reply avatar"

    override val peakNow = "Peak hours · higher price"
    override val offPeakNow = "Off-peak · about half price"
    override val toPeak = "until peak"
    override val toOffPeak = "until off-peak"

    override val sessionWorkDir = "File location"
    override val sessionWorkDirHint = "Files the AI creates in this chat live here"
    override val workDirPick = "Existing folders"
    override val workDirNew = "New folder"
    override val workDirNewPlaceholder = "Folder name, e.g. my-site"
    override val workDirUseRoot = "Use workspace root"
    override val workDirEmpty = "No folders yet"

    override val agentAsking = "The AI wants to check"
    override val agentAskSubmit = "Answer & continue"
    override val agentAskSkip = "Skip"
    override val agentAskPlaceholder = "Or type your own…"

    override val shareFailed = "Export failed"
    override val appFeaturesText = "Two built-in channels: Agnes and Zhipu GLM free tier, ready out of the box; " +
        "keys are managed by the app, you only tune parameters\n" +
        "Multimodal: images and videos shown inline in chat; ask questions about pictures\n" +
        "File Q&A: txt / md / docx / pdf / code, text extracted on-device and sent with the message\n" +
        "Canvas: LaTeX formulas, mind maps and geometry figures rendered in chat, expandable full screen\n" +
        "AI Agent: on-device tools with a multi-step loop, including real Bilibili actions " +
        "(favourites / like / coin / favourite)\n" +
        "Local workspace: real files -> validation -> bundled single-file HTML, preview & export\n" +
        "Token dashboard · watch performance tiers and round-screen safe insets"

    // ---------------- Execution & cloud build ----------------
    override val execSectionTitle = "Run & build"
    override val execSectionDesc = "Run code on the device, or compile it in the cloud"
    override val execScreenTitle = "Run & build"
    override val execEnable = "Enable on-device execution"
    override val execEnableDesc = "Let the AI run shell / JS scripts on this device"
    override val execTimeoutLabel = "Timeout"
    override val execMaxOutputLabel = "Output cap"
    override val execSecondsSuffix = " s"
    override val execForceRelease = "Force release the run gate"
    override val execForceReleaseDesc = "Use when a previous script is stuck"
    override val execConfirmNotice =
        "On-device execution is powerful: scripts can read and write the current work " +
            "directory and use the network. Even when enabled, every run asks for " +
            "confirmation again — an \"always allow\" tap never unlocks it permanently."
    override val cloudEnable = "Enable cloud build"
    override val cloudBuildEta = "Cloud builds usually take 3–8 min; the first run is slower"
    override val cloudEnableDesc = "Hand tasks a watch cannot do (APK, gradle) to GitHub Actions"
    override val cloudRepoLabel = "Target repository"
    override val cloudRepoHint = "owner/repo only, e.g. dsdzm/DSH-Watch"
    override val cloudRefLabel = "Branch"
    override val cloudWorkflowLabel = "Default workflow (file name)"
    override val cloudTokenLabel = "GitHub token"
    override val cloudTokenHint = "Needs Actions read/write; stored encrypted on this device"
    override val cloudInstallTemplates = "Install workflow templates into the repo"
    override val cloudTestLabel = "Test connection"

    override val onboardingTitle = "Let's match your watch"
    override val onboardingSubtitle = "Three steps to fit this screen. Everything stays editable in Settings."
    override val onboardingShapeTitle = "Screen shape"
    override val onboardingShapeHint = "Round screens have no pixels in the corners, so edge content gets clipped. A wrong pick wastes width."
    override val onboardingLayoutTitle = "Chat layout"
    override val onboardingLayoutHint = "Four ways to read the same conversation. Pick one now, switch anytime."
    override val onboardingThemeTitle = "Theme"
    override val onboardingThemeHint = "Pick a base first, then an accent. Pure black OLED saves the most power."
    override val onboardingNext = "Next"
    override val onboardingBack = "Back"
    override val onboardingStart = "Start"
    override val onboardingDetectedRound = "Detected: round"
    override val onboardingDetectedSquare = "Detected: square"
    override val shapeRound = "Round"
    override val shapeSquare = "Square"
    override val shapeAuto = "Auto"
    override val layoutBubble = "Bubble"
    override val layoutCard = "Card"
    override val layoutMinimal = "Minimal"
    override val layoutBubbleHint = "Left/right bubbles, the most conversational"
    override val layoutCardHint = "One card per turn, clear hierarchy"
    override val layoutMinimalHint = "No fills, more lines on a tiny screen"
    override val screenShape = "Screen shape"
    override val chatLayout = "Chat layout"
    override val themePreset = "Theme preset"
    override val themePresetHint = "Changes the base palette, not brightness or font size"
    override val accentColor = "Accent color"
    override val rerunOnboarding = "Run setup again"
    override val rerunOnboardingHint = "Re-pick shape, layout and theme; no conversations are deleted"

    override fun themePresetLabel(id: String) =
        com.dshwatch.ui.theme.ThemePresets.of(id).labelEn

    override fun accentLabel(id: String) =
        com.dshwatch.ui.theme.Accents.of(id).labelEn

    override fun chatLayoutLabel(id: String) = when (com.dshwatch.domain.model.ChatLayouts.of(id)) {
        com.dshwatch.domain.model.ChatLayouts.CARD -> layoutCard
        com.dshwatch.domain.model.ChatLayouts.MINIMAL -> layoutMinimal
        else -> layoutBubble
    }

    override fun chatLayoutHint(id: String) = when (com.dshwatch.domain.model.ChatLayouts.of(id)) {
        com.dshwatch.domain.model.ChatLayouts.CARD -> layoutCardHint
        com.dshwatch.domain.model.ChatLayouts.MINIMAL -> layoutMinimalHint
        else -> layoutBubbleHint
    }

    override fun toolTitle(tool: String): String = when (tool) {
        "tool_search" -> "Load tools"
        "get_datetime" -> "Check time"
        "calculate" -> "Calculate"
        "get_device_info" -> "Device info"
        "get_token_usage" -> "Token usage"
        "http_fetch" -> "Fetch URL"
        "set_alarm" -> "Set alarm"
        "list_files" -> "List files"
        "read_file" -> "Read file"
        "write_file" -> "Create file"
        "append_file" -> "Append to file"
        "make_dir" -> "New folder"
        "delete_file" -> "Delete file"
        "build_project" -> "Verify & build"
        "edit_file" -> "Edit file"
        "glob" -> "Find files"
        "grep" -> "Search content"
        "todo_write" -> "Update todos"
        "open_file" -> "Open file"
        "open_screen" -> "Open screen"
        "create_event" -> "New event"
        "move_file" -> "Move file"
        "ask_user" -> "Ask you"
        "list_alarms" -> "List alarms"
        "cancel_alarm" -> "Cancel alarm"
        "vibrate" -> "Vibrate"
        "get_clipboard" -> "Read clipboard"
        "unit_convert" -> "Convert units"
        "web_search" -> "Web search"
        "run_script" -> "Run script"
        "cloud_build" -> "Cloud build"
        "cloud_job" -> "Cloud job"
        "bili_status" -> "Bilibili status"
        "bili_search" -> "Search Bilibili"
        "bili_video" -> "Video info"
        "bili_subtitle" -> "Read subtitles"
        "bili_comments" -> "Top comments"
        "bili_inbox" -> "Reply feed"
        "bili_thread" -> "Comment thread"
        "bili_report" -> "Write digest"
        "bili_reply" -> "Post comment"
        "bili_dm" -> "Send DM"
        "bili_dyn" -> "Publish"
        "bili_like" -> "Like"
        "bili_fav_folders" -> "Fav folders"
        "bili_fav_list" -> "Folder videos"
        "bili_coin" -> "Toss a coin"
        "bili_fav" -> "Fav video"
        "device_screen" -> "Read screen"
        "device_act" -> "Act on screen"
        "device_open" -> "Open app"
        "device_handoff" -> "Hand off"
        else -> tool
    }
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { Zh }

/** 界面语言设置：跟随系统 / 强制中文 / 强制英文 */
object AppLanguage {
    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"
}

/**
 * 依据设置与系统语言挑一套文案；以后加语言只改这里。
 *
 * **不能只看 `Locale.getDefault()`** —— 它反映的是 JVM 进程级 locale，
 * 而 App 实际生效的 locale 由 `Configuration` 决定。Android 13 的 per-app
 * language、以及部分手表 ROM，会改后者而不改前者，表现就是
 * 「系统明明是中文，App 里全是英文」。所以优先看配置里的 locale 列表。
 *
 * `zh-Hans` / `zh-Hant` / `zh-CN` 都算中文：只比较 language 段，
 * 不做 script/region 的精确匹配（找不到「简体」就退回英文是更糟的体验）。
 */
fun stringsFor(mode: String, locales: List<Locale>?): AppStrings = when (mode) {
    AppLanguage.ZH -> Zh
    AppLanguage.EN -> En
    else -> if (prefersChinese(locales)) Zh else En
}

private fun prefersChinese(locales: List<Locale>?): Boolean {
    if (!locales.isNullOrEmpty()) {
        return locales.any { it.language.equals("zh", ignoreCase = true) }
    }
    return Locale.getDefault().language.equals("zh", ignoreCase = true)
}
