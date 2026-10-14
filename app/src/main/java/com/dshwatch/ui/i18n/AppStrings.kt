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
    val navSessions: String
    val navSettings: String
    val navUsage: String
    val navProviders: String
    val navAbout: String
    val back: String

    // 对话
    val chatEmptyTitle: String
    val chatEmptyHint: String
    val inputHint: String
    val thinking: String
    val reasoning: String
    val expandReasoning: String
    val collapseReasoning: String
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
    val fontSize: String
    val fontSmall: String
    val fontNormal: String
    val fontLarge: String
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

    // 关于
    val aboutTitle: String
    val version: String
    val aboutDesc: String
    val builtWith: String
    val deviceInfo: String
    val sourceHint: String

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
    override val navSessions = "会话"
    override val navSettings = "设置"
    override val navUsage = "用量"
    override val navProviders = "供应商"
    override val navAbout = "关于"
    override val back = "返回"

    override val chatEmptyTitle = "开始一段 vibe coding"
    override val chatEmptyHint = "点下方输入框说话，模型会直接给可运行的代码"
    override val inputHint = "说点什么…"
    override val thinking = "思考中"
    override val reasoning = "思维链"
    override val expandReasoning = "展开思维链"
    override val collapseReasoning = "收起"
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
    override val fontSize = "字号"
    override val fontSmall = "小"
    override val fontNormal = "标准"
    override val fontLarge = "大"
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
    override val agentMaxSteps = "最大步数"
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
    override val techStackText = "Kotlin 2.0 + Jetpack Compose（Material 3）\n" +
        "自研 Markdown 渲染，不用 WebView（省 30–60MB 常驻内存）\n" +
        "手写 SSE 流式解析 · 自研递归下降表达式求值\n" +
        "OkHttp + kotlinx.serialization\n" +
        "DataStore + Keystore 加密存储 · 文件级会话持久化\n" +
        "单模块，R8 全量混淆后约 4.8MB"
    override val inspiredByText = "DSH 服务器模式参考 dsh-workspace 的配对协议形态；\n" +
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
    override val appFeaturesText = "双后端：直连自己的 API（DeepSeek / 中转站 / 本地 Ollama），" +
        "或连电脑上的 DSH 服务器\n" +
        "AI Agent：13 个本地工具 + 多步工具循环，不依赖电脑\n" +
        "本地工作空间：真正建文件 → 校验 → 打包成单文件 HTML，可预览可导出\n" +
        "Token 用量看板：按天 / 按模型统计，估算花费\n" +
        "手表适配：性能分档、大 R 角安全边距、流式节流"

    override fun toolTitle(tool: String): String = when (tool) {
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
    override val navSessions = "Chats"
    override val navSettings = "Settings"
    override val navUsage = "Usage"
    override val navProviders = "Providers"
    override val navAbout = "About"
    override val back = "Back"

    override val chatEmptyTitle = "Start vibe coding"
    override val chatEmptyHint = "Tap the input below — you get runnable code, not small talk"
    override val inputHint = "Say something…"
    override val thinking = "Thinking"
    override val reasoning = "Reasoning"
    override val expandReasoning = "Show reasoning"
    override val collapseReasoning = "Collapse"
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
    override val fontSize = "Font size"
    override val fontSmall = "S"
    override val fontNormal = "M"
    override val fontLarge = "L"
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
    override val agentMaxSteps = "Max steps"
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
    override val techStackText = "Kotlin 2.0 + Jetpack Compose (Material 3)\n" +
        "Custom Markdown renderer, no WebView (saves 30-60MB RSS)\n" +
        "Hand-written SSE parser · custom recursive-descent expression evaluator\n" +
        "OkHttp + kotlinx.serialization\n" +
        "DataStore + Keystore encryption · file-backed conversation storage\n" +
        "Single module, ~4.8MB after full R8 shrinking"
    override val inspiredByText = "The DSH server mode follows the pairing protocol shape of dsh-workspace;\n" +
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
    override val appFeaturesText = "Two backends: your own API (DeepSeek / gateways / local Ollama) " +
        "or a DSH server on your computer\n" +
        "AI Agent: 13 on-device tools with a multi-step loop, no computer required\n" +
        "Local workspace: real files -> validation -> bundled single-file HTML, preview & export\n" +
        "Token dashboard: by day / by model, with estimated cost\n" +
        "Watch tuning: performance tiers, rounded-corner safe insets, stream throttling"

    override fun toolTitle(tool: String): String = when (tool) {
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
        else -> tool
    }
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { Zh }

/** 依据系统语言挑一套文案；以后加语言只改这里 */
fun defaultStrings(): AppStrings {
    val lang = Locale.getDefault().language
    return if (lang.startsWith("zh")) Zh else En
}
