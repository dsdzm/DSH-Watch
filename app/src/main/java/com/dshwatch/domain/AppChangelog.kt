package com.dshwatch.domain

/**
 * 版本更新日志。
 *
 * **每次发版，往 [entries] 最前面加一条** —— 关于页自动渲染，无需另维护文档。
 *
 * 书写约定（这三条是硬性的）：
 *
 * 1. **只记新增能力**。修复类改动（崩溃、闪烁、错位、兼容性）一律不进这里，
 *    它们属于 commit message 与 issue，不属于版本说明。
 * 2. **一条一句**，动词开头：新增 / 支持 / 提供 / 统一。不写实现细节，
 *    不写「以前怎样、现在怎样」的对比句。
 * 3. **一版 1–4 条**，按重要性排序。写不出新增内容的版本，就只留版本号。
 */
data class ChangelogEntry(
    val version: String,
    val date: String,
    val highlights: List<String>,
)

object AppChangelog {

    val entries: List<ChangelogEntry> = listOf(
        // 稳定版重发：以 1.15.22 的统一缩放实现为准，无新增能力，按约定不写条目
        ChangelogEntry(
            version = "1.15.26",
            date = "2026-11-07",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.15.22",
            date = "2026-11-06",
            highlights = listOf(
                "统一界面缩放：字号并入「界面大小」，文字、卡片与间距始终等比缩放",
            ),
        ),
        ChangelogEntry(
            version = "1.15.21",
            date = "2026-11-06",
            highlights = listOf(
                "外观与首次引导新增「界面大小」调节：卡片、间距与字号一起缩放，高 dpi 小屏可自动适配，不再溢出",
            ),
        ),
        ChangelogEntry(
            version = "1.15.20",
            date = "2026-11-06",
            highlights = listOf(
                "新增「帮助与教程」：设置里可查每个功能的用法，含状态面板语法、行动选项规则与导入格式",
                "更新支持运行期主动复查，应用一直开着也会按天检查新版本",
                "长期记忆改为按未压缩内容自动整理，长对话不再需要手动压缩",
            ),
        ),
        ChangelogEntry(
            version = "1.15.19",
            date = "2026-11-06",
            highlights = listOf(
                "更新改由 GitHub Releases 分发，不再依赖自建存储",
                "下载支持断点续传，网络中断后可从上次进度继续",
            ),
        ),
        ChangelogEntry(
            version = "1.15.18",
            date = "2026-11-06",
            highlights = listOf(
                "支持检查更新：可在应用内下载并安装新版本",
                "设置中可选择是否在启动后自动检查更新",
            ),
        ),
        // 纯稳定性修复，按约定不写新增条目
        ChangelogEntry(
            version = "1.15.16",
            date = "2026-11-06",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.15.15",
            date = "2026-11-05",
            highlights = listOf(
                "导入对话改为读取 txt 文件，可从系统文件或工作空间里选择，自动识别 UTF-8 与 GBK 编码",
            ),
        ),
        ChangelogEntry(
            version = "1.15.14",
            date = "2026-11-05",
            highlights = listOf(
                "会话列表支持导出对话：按固定分隔格式生成纯文本，可复制、存为 txt 或分享到手机",
                "支持导入同格式文本，自动识别说话人并重建为一条新会话",
            ),
        ),
        ChangelogEntry(
            version = "1.15.13",
            date = "2026-11-05",
            highlights = listOf(
                "写作模式把「已经写好的开头」一并作为续写依据发送给模型，续写不再从空白起步",
            ),
        ),
        ChangelogEntry(
            version = "1.15.12",
            date = "2026-11-04",
            highlights = listOf(
                "对话页新增状态面板：标题、条目、可展开详情三段结构，点「阅」就地展开",
                "面板数值支持「当前/上限」写法，自动渲染为进度条",
                "面板、选项与自定义界面可在同一条回复中混排",
            ),
        ),
        ChangelogEntry(
            version = "1.15.11",
            date = "2026-11-04",
            highlights = listOf(
                "回复末尾的 A/B/C 选项自动提取为可点按钮，点一下即作为回复发出",
                "选项支持「自定义回复」，点击直接进入输入框",
                "历史消息中的选项自动置灰，避免误发过期选择",
            ),
        ),
        ChangelogEntry(
            version = "1.15.10",
            date = "2026-11-04",
            highlights = listOf(
                "自定义界面改为在对话流内直接渲染，高度随内容自适应，可点击展开与切换",
                "界面超出可视高度时提供全屏入口，长界面不再截断",
                "系统提示支持角色状态、物品、关系、场景等结构化信息以界面形式呈现",
            ),
        ),
        ChangelogEntry(
            version = "1.15.9",
            date = "2026-11-04",
            highlights = listOf(
                "酒馆与写作支持续接：「开始对话 / 继续写」直接回到该设定最近一次会话",
                "角色卡新增「新开对话」与「历史记录」两个圆形按钮，历史列表按角色过滤",
                "对话页支持 HTML + CSS 自定义界面，```html 代码块可全屏渲染",
            ),
        ),
        ChangelogEntry(
            version = "1.15.8",
            date = "2026-11-04",
            highlights = listOf(
                "圆屏页面返回键统一为 44dp 圆形玻璃按钮，触控区域与视觉边界一致",
                "对话页内容区改为内接矩形排版，消息完整显示于屏幕中央",
                "关于页更新日志改为按版本列举功能变更",
            ),
        ),
        ChangelogEntry(
            version = "1.15.7",
            date = "2026-11-03",
            highlights = listOf(
                "圆屏聊天页移除鱼眼特效，消息改为静态排版，滚动过程稳定",
                "圆屏保留安全区、气泡收窄与右侧滚动弧线",
            ),
        ),
        ChangelogEntry(
            version = "1.15.6",
            date = "2026-11-03",
            highlights = listOf(
                "页面切换统一采用系统标准转场，速度遵循系统「过渡动画缩放」设置",
                "从二级页面返回时保留列表原有滚动位置",
            ),
        ),
        ChangelogEntry(
            version = "1.15.5",
            date = "2026-11-03",
            highlights = listOf(
                "转场改为单次几何计算驱动，自手指抬起当帧起播",
                "返回键与侧滑返回共用同一套退场逻辑",
            ),
        ),
        ChangelogEntry(
            version = "1.15.4",
            date = "2026-11-03",
            highlights = listOf(
                "转场首帧尺寸直接取自父级约束，页面不再先满屏后回缩",
                "崩溃报告与版本提示独立于页面层，不参与页面缩放",
            ),
        ),
        ChangelogEntry(
            version = "1.15.3",
            date = "2026-11-03",
            highlights = listOf(
                "转场改为直接对页面做缩放，新页面由触发按钮位置展开",
                "启动体检新增性能档位与切页起点信息",
            ),
        ),
        ChangelogEntry(
            version = "1.15.2",
            date = "2026-11-03",
            highlights = listOf(
                "转场等待真实绘制完成后开始，最长等待 0.3 秒",
                "无法取得按钮位置时跳过动画，直接完成切换",
            ),
        ),
        ChangelogEntry(
            version = "1.15.1",
            date = "2026-11-03",
            highlights = listOf(
                "转场动画增加几何兜底，异常时降级为直接切页",
            ),
        ),
        ChangelogEntry(
            version = "1.15",
            date = "2026-11-03",
            highlights = listOf(
                "新增崩溃取证：崩溃后重新打开可查看完整堆栈并一键复制",
                "新增「质感玻璃」外观风格，可与任意配色组合，引导页提供材质小样",
                "设置新增诊断入口：上次崩溃报告、最近问题、运行环境与启动体检",
                "小内存设备自动进入省内存档，关闭动画并限制气泡高度",
            ),
        ),
        ChangelogEntry(
            version = "1.14",
            date = "2026-11-03",
            highlights = listOf(
                "新增「质感玻璃」主题：胶囊、卡片、按钮改为半透明玻璃材质",
                "控件光效跟随手指位置，按压带弹性形变",
                "省电档自动关闭动画，保留静态层次",
            ),
        ),
        ChangelogEntry(
            version = "1.13",
            date = "2026-11-02",
            highlights = listOf(
                "新增「酒馆」场景：角色卡、长期记忆、第一人称对话",
                "新增「写作」场景：世界书、剧情、人物、插件与独立提示词",
                "引导页新增用途选择，顶栏可随时切换场景",
                "按键触控区统一扩大至 48dp，字号改为连续滑块",
            ),
        ),
        ChangelogEntry(
            version = "1.12",
            date = "2026-11-02",
            highlights = listOf(
                "移除内置 Python 引擎，仅保留设备自带的 shell 与 JavaScript",
                "新增代码静态检查：Python 缩进与语法、Java 类名与包名、C/C++ 预处理配对",
            ),
        ),
        ChangelogEntry(
            version = "1.11",
            date = "2026-11-01",
            highlights = listOf(
                "新增首次引导：屏幕形状、对话布局、主题三步配置",
                "新增圆屏专属排版，列表项按距圆心距离收窄",
                "新增四种对话布局：气泡、卡片、极简、环形",
                "提供 6 套底色与 6 种强调色，共 36 种组合",
            ),
        ),
        ChangelogEntry(
            version = "1.10",
            date = "2026-10-31",
            highlights = listOf(
                "刷 B 站支持三种处理方式：只总结、发评论、发私信",
                "评论支持自动 @ 与配图，新增 bili_dm 私信工具",
                "支持每日定时任务，默认 03:30 执行",
                "新增动态发布与点赞能力",
            ),
        ),
        ChangelogEntry(
            version = "1.9",
            date = "2026-10-31",
            highlights = listOf(
                "新增 B 站扫码登录",
                "评论风格可选：默认、认真点评、抽象玩梗、自定义",
                "新增 bili_inbox、bili_thread、bili_reply 三个互动工具",
                "后台任务支持通知栏进度与唤醒锁续期",
            ),
        ),
        ChangelogEntry(
            version = "1.8",
            date = "2026-10-31",
            highlights = listOf(
                "新增「刷 B 站」：搜索、读字幕、筛选并写入当日日报",
                "新增七个 bili_* 工具，关闭后不进入上下文",
                "日报按 BV 号计数，同一条当日不重复记录",
                "发评论默认仅预览，需显式开启并逐次确认",
            ),
        ),
        ChangelogEntry(
            version = "1.7",
            date = "2026-10-20",
            highlights = listOf(
                "界面迁移至 Jetpack Compose for Wear OS 与 Material 3",
                "打开即进入对话，左右滑动切换设置与对话",
                "对话气泡重做，角色名置于气泡上方",
                "设置页改为分组卡片，各页面统一顶栏与卡片样式",
            ),
        ),
        ChangelogEntry(
            version = "1.6",
            date = "2026-10-19",
            highlights = listOf(
                "新增完整上下文：工具结果全文回灌，历史不做裁剪",
                "执行确认分级：js 与 python 直接执行，shell 可按会话放行",
                "联网工具支持自定义请求头",
                "Agent 在工作空间留存任务说明",
                "对话页新增「工作流程」块，统一展示思考与工具调用",
            ),
        ),
        ChangelogEntry(
            version = "1.5",
            date = "2026-10-17",
            highlights = listOf(
                "云端编译全流程自动化，从触发到产出 APK 无需人工介入",
                "提交后立即返回运行链接，构建在云端继续",
                "工具卡片标注预计耗时",
                "lite 版不再提供 Python 入口",
            ),
        ),
        ChangelogEntry(
            version = "1.4",
            date = "2026-10-17",
            highlights = listOf(
                "新增「MCP 服务器」接入，外部服务工具直接进入 Agent 工具清单",
                "新增「插件」：四个可选模块，逐个开关",
                "取消步数限制，长任务可持续执行",
                "设置页拆分为二级分组，对话页新增「回到最新」",
            ),
        ),
        ChangelogEntry(
            version = "1.3",
            date = "2026-10-15",
            highlights = listOf(
                "新增「运行脚本」：在设备上执行 shell、JavaScript、Python",
                "新增「云端编译」：将构建任务交由 GitHub Actions 执行",
                "提供 lite 与 full 两种安装包",
                "内置两个云端模板，可一键部署至自有仓库",
            ),
        ),
        ChangelogEntry(
            version = "1.2",
            date = "2026-10-14",
            highlights = listOf(
                "新增多语言代码校验：Python、Kotlin、Java、C、Go、Rust、Swift、Shell、YAML",
                "新增 edit_file、glob、grep、todo_write、open_file、open_screen、create_event",
                "网页抓取自动转换为纯文本",
            ),
        ),
        ChangelogEntry(
            version = "1.1",
            date = "2026-10-14",
            highlights = listOf(
                "每个会话使用独立工作目录",
                "对话管理页支持自定义文件位置",
                "新增 ask_user 工具",
                "DeepSeek 模型下显示峰谷时段",
                "新增联网搜索、单位换算、文件移动与搜索、闹钟管理",
            ),
        ),
        ChangelogEntry(
            version = "1.0.0",
            date = "2026-10-13",
            highlights = listOf(
                "首个版本：双后端（直连 API 与 DSH 服务器）、Material 3 界面",
                "Token 用量看板：今日、近 7 天、本月、全部",
                "提供设备性能分档与无 WebView 的 Markdown 渲染",
            ),
        ),
    )

    /** 关于页里「当前版本」对应的那一条 */
    val latest: ChangelogEntry? get() = entries.firstOrNull()
}
