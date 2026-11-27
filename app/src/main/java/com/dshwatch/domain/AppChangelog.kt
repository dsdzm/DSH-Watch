package com.dshwatch.domain

/**
 * 版本更新日志。
 *
 * **每次发版，往 [entries] 最前面加一条** —— 关于页自动渲染，无需另维护文档。
 *
 * 书写约定（硬性）：
 *
 * 1. **只记正式版本**。beta 迭代属于测试通道，不进入此列表；正式版把这一轮
 *    的能力合并成一条，避免用户在关于页翻到几十条内部构建记录。
 * 2. **一条一句，动宾结构**：新增 / 支持 / 优化 / 修复 / 移除。不写实现细节，
 *    不写「以前怎样、现在怎样」的对比，不使用拟人化、口语化表达。
 * 3. **一版 1–4 条**，按重要性排序。没有新增能力的版本只保留版本号。
 * 4. **测试版不替稳定版占号**：1.24.0 之后只能叫 `1.24.1-beta.N`，不得跳到
 *    `1.25.0-beta.1`。稳定版由人明确要求才发，`publish_update.py --beta` 会拦截。
 */
data class ChangelogEntry(
    val version: String,
    val date: String,
    val highlights: List<String>,
)

object AppChangelog {

    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "1.24.1",
            date = "2026-11-26",
            highlights = listOf(
                "新增「养鱼」场景：独立角色会话，支持主动消息、每日动态、长期记忆与外观自定义",
                "新增 QQ 直连：扫码登录后在应用内收发私聊与群消息，回复不授予工具执行权限",
                "新增正文公式排版：$…$ 与 $$…$$ 原位渲染，支持上下标、分式、根号与重音记号",
                "新增 Cordis 插件包转译：从 .tgz 中提取提示词与预设，核对后导入",
            ),
        ),
        ChangelogEntry(
            version = "1.24.0",
            date = "2026-11-16",
            highlights = listOf(
                "新增手机版界面：Material 3 底部导航，对话、记录、设置分页，与手表主题相互独立",
                "新增上下文构成面板与实时生成速率：按系统提示、消息、工具调用分项统计占用",
                "新增对话归档：按 DeepSeek 官方格式导入导出，含思考过程与图片原件",
                "新增回答多版本、续写与提问编辑，并提供 DeepSeek 官方联网搜索",
            ),
        ),
        ChangelogEntry(
            version = "1.23.4",
            date = "2026-11-15",
            highlights = listOf(
                "新增「当前会话上下文」面板：按系统提示、消息、工具调用、工具输出分项统计占用",
                "新增生成速率显示：流式回复时实时显示 tok/s",
            ),
        ),
        ChangelogEntry(
            version = "1.23.3",
            date = "2026-11-15",
            highlights = listOf(
                "修复 M3E 界面下对话框仅显示首个动作，保存与提交按钮超出可视区域",
            ),
        ),
        ChangelogEntry(
            version = "1.23.2",
            date = "2026-11-15",
            highlights = listOf(
                "新增应用内更新支持将安装包保存至本地，并提供路径与安装命令复制",
                "支持已保存的安装包在更新页直接安装，无需重新下载",
            ),
        ),
        ChangelogEntry(
            version = "1.23.1",
            date = "2026-11-14",
            highlights = listOf(
                "修复后台唤醒的进程被误判为启动失败，导致重复提示",
                "新增启动体检区分启动路径与运行期异常，并附上次崩溃的异常行",
            ),
        ),
        ChangelogEntry(
            version = "1.23.0",
            date = "2026-11-14",
            highlights = listOf(
                "新增 SSH 连接 DSH：填写主机、用户名与密码，由设备自动启动服务并转发端口",
                "新增手机扫码配对 DSH Remote，无需在设备上输入凭据",
                "新增会话流通道：电脑端审批同步至设备，支持单次允许或拒绝",
                "新增界面体系切换：经典与 Wear M3E 两套界面并列可选",
            ),
        ),
        ChangelogEntry(
            version = "1.22.4",
            date = "2026-11-13",
            highlights = listOf(
                "修复浅色模式下玻璃卡片与对话框被判定为深色，导致文字不可读",
            ),
        ),
        ChangelogEntry(
            version = "1.22.3",
            date = "2026-11-13",
            highlights = listOf(
                "支持最大 32 MB 的插件包，采用流式读取，避免整包载入内存",
                "优化深色玻璃对话框对比度，正文对比度由 2.1:1 提升至 4.6:1",
            ),
        ),
        ChangelogEntry(
            version = "1.22.2",
            date = "2026-11-13",
            highlights = listOf(
                "新增从文件导入插件：支持 .tgz、.tar.gz 与 .json",
                "支持无文件管理器的设备从工作空间选取插件包",
            ),
        ),
        ChangelogEntry(
            version = "1.22.1",
            date = "2026-11-13",
            highlights = listOf(
                "新增插件清单 tools 段：支持隐藏工具、白名单与指定工具确认",
                "新增插件导入：支持剪贴板、链接与本地文件，导入前执行预检",
                "支持多份插件并行生效，白名单取交集、确认项取并集",
            ),
        ),
        ChangelogEntry(
            version = "1.22.0",
            date = "2026-11-13",
            highlights = listOf(
                "新增声明式插件：将 JSON 清单置于工作空间 plugins/ 目录即可修改系统提示",
                "支持按模型匹配与变量插值，同一份清单可限定于指定模型",
                "支持逐份校验，单份清单异常不影响其余加载",
            ),
        ),
        ChangelogEntry(
            version = "1.21.5",
            date = "2026-11-12",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.21.4",
            date = "2026-11-11",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.21.3",
            date = "2026-11-11",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.21.2",
            date = "2026-11-11",
            highlights = listOf(
                "修复 Shizuku 已安装并授权时仍提示未安装，包名匹配有误",
                "补充 Android 11 及以上包可见性声明",
            ),
        ),
        ChangelogEntry(
            version = "1.21.1",
            date = "2026-11-11",
            highlights = listOf(
                "工具恢复全量下发，移除按需获取导致的阻塞",
                "优化设备操控期间的工具表范围，任务结束后可直接写入文件",
            ),
        ),
        ChangelogEntry(
            version = "1.21.0",
            date = "2026-11-11",
            highlights = listOf(
                "新增 Shizuku 接入：以 adb 身份执行命令，支持安装应用、读取日志、调整屏幕与电量",
                "新增命令用途说明与危险命令二次确认，确认记录不作持久化",
                "新增按类别始终允许与执行审计记录",
            ),
        ),
        ChangelogEntry(
            version = "1.20.0",
            date = "2026-11-10",
            highlights = listOf(
                "工具改为按需加载，单轮工具表由约 1.8 万字降至 5 千字",
                "常驻 13 个高频工具，B 站、构建、提醒等分组按需注入",
                "新增 tool_search，模型可按需获取工具定义",
            ),
        ),
        ChangelogEntry(
            version = "1.19.0",
            date = "2026-11-10",
            highlights = listOf(
                "设备操控仅注入设备类工具，单轮工具表开销降至约十分之一",
                "设备操控历史中的旧屏幕内容自动折叠，仅保留最近三屏",
                "界面无变化时跳过整屏重发",
            ),
        ),
        ChangelogEntry(
            version = "1.18.3",
            date = "2026-11-09",
            highlights = listOf(
                "新增内置文件浏览器，不依赖系统文件管理器",
                "附件入口默认使用内置浏览器，可访问下载、文档、图片等目录",
                "支持在设置中切换回系统选择器",
            ),
        ),
        ChangelogEntry(
            version = "1.18.2",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.18.1",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.18.0",
            date = "2026-11-09",
            highlights = listOf(
                "新增设备操控：支持读取屏幕、点击、输入与打开应用",
                "操作期间应用退至后台，底部显示当前动作与停止入口",
                "密码、验证码与支付确认不代为填写",
            ),
        ),
        ChangelogEntry(
            version = "1.17.4",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.17.3",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.17.2",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.17.1",
            date = "2026-11-08",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.17.0",
            date = "2026-11-08",
            highlights = listOf(
                "新增智谱 GLM 内置通道：提供 GLM-4.7-Flash、GLM-4.6V-Flash 与 CogView-3-Flash，仅保留模型参数",
                "新增文件问答：支持 txt、md、docx、pdf 与代码文件",
                "新增画布：LaTeX 公式、思维导图与几何图在对话内渲染并支持全屏",
                "新增 B 站收藏夹查看、投币与收藏，默认关闭",
            ),
        ),
        ChangelogEntry(
            version = "1.16.0",
            date = "2026-11-08",
            highlights = listOf(
                "新增内置 Agnes 通道：提供 5 个免费模型，覆盖文本、图片与视频",
                "支持在对话页显示图片与视频，可放大、播放并分享",
                "新增新手教学：说明 API Key、供应商地址、模型选择、上下文窗口与温度",
                "支持图片作为多模态输入",
            ),
        ),
        ChangelogEntry(
            version = "1.15.26",
            date = "2026-11-07",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.15.22",
            date = "2026-11-06",
            highlights = listOf(
                "统一界面缩放：字号并入界面大小，文字、卡片与间距保持等比",
            ),
        ),
        ChangelogEntry(
            version = "1.15.21",
            date = "2026-11-06",
            highlights = listOf(
                "新增界面大小调节：卡片、间距与字号同步缩放，支持高 DPI 小屏自动适配",
            ),
        ),
        ChangelogEntry(
            version = "1.15.20",
            date = "2026-11-06",
            highlights = listOf(
                "新增帮助与教程：覆盖功能用法、状态面板语法、行动选项规则与导入格式",
                "更新检查支持运行期复查",
                "长期记忆改为按未压缩内容自动整理",
            ),
        ),
        ChangelogEntry(
            version = "1.15.19",
            date = "2026-11-06",
            highlights = listOf(
                "更新分发迁移至 GitHub Releases",
                "下载支持断点续传",
            ),
        ),
        ChangelogEntry(
            version = "1.15.18",
            date = "2026-11-06",
            highlights = listOf(
                "支持应用内检查更新并安装新版本",
                "支持配置启动后自动检查更新",
            ),
        ),
        ChangelogEntry(
            version = "1.15.16",
            date = "2026-11-06",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.15.15",
            date = "2026-11-05",
            highlights = listOf(
                "对话导入改为读取 txt 文件，自动识别 UTF-8 与 GBK 编码",
            ),
        ),
        ChangelogEntry(
            version = "1.15.14",
            date = "2026-11-05",
            highlights = listOf(
                "新增对话导出：按固定分隔格式生成纯文本，可复制、保存或分享",
                "支持同格式文本导入，自动识别说话人并重建会话",
            ),
        ),
        ChangelogEntry(
            version = "1.15.13",
            date = "2026-11-05",
            highlights = listOf(
                "写作模式将已写好的开头作为续写依据一并发送",
            ),
        ),
        ChangelogEntry(
            version = "1.15.12",
            date = "2026-11-04",
            highlights = listOf(
                "新增对话页状态面板：标题、条目与可展开详情三段结构",
                "面板数值支持「当前/上限」写法并渲染为进度条",
                "支持面板、选项与自定义界面在同一条回复中混排",
            ),
        ),
        ChangelogEntry(
            version = "1.15.11",
            date = "2026-11-04",
            highlights = listOf(
                "回复末尾的选项自动提取为可点击按钮",
                "选项支持自定义回复，点击后进入输入框",
                "历史消息中的选项自动置灰",
            ),
        ),
        ChangelogEntry(
            version = "1.15.10",
            date = "2026-11-04",
            highlights = listOf(
                "自定义界面改为在对话流内渲染，高度随内容自适应",
                "界面超出可视高度时提供全屏入口",
                "系统提示支持角色状态、物品、关系与场景等结构化信息",
            ),
        ),
        ChangelogEntry(
            version = "1.15.9",
            date = "2026-11-04",
            highlights = listOf(
                "酒馆与写作支持续接至该设定最近一次会话",
                "角色卡新增新开对话与历史记录入口，历史列表按角色过滤",
                "支持 HTML 与 CSS 自定义界面，代码块可全屏渲染",
            ),
        ),
        ChangelogEntry(
            version = "1.15.8",
            date = "2026-11-04",
            highlights = listOf(
                "圆屏返回键统一为 44dp 圆形按钮，触控区域与视觉边界一致",
                "对话页内容区改为内接矩形排版",
                "更新日志改为按版本列举功能变更",
            ),
        ),
        ChangelogEntry(
            version = "1.15.7",
            date = "2026-11-03",
            highlights = listOf(
                "圆屏对话页移除鱼眼特效，改为静态排版",
            ),
        ),
        ChangelogEntry(
            version = "1.15.6",
            date = "2026-11-03",
            highlights = listOf(
                "页面切换采用系统标准转场，速度遵循系统过渡动画缩放设置",
                "从二级页面返回时保留列表滚动位置",
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
                "转场首帧尺寸取自父级约束，避免先满屏后回缩",
                "崩溃报告与版本提示独立于页面层",
            ),
        ),
        ChangelogEntry(
            version = "1.15.3",
            date = "2026-11-03",
            highlights = listOf(
                "转场改为对页面直接缩放，由触发按钮位置展开",
                "启动体检新增性能档位与切页起点信息",
            ),
        ),
        ChangelogEntry(
            version = "1.15.2",
            date = "2026-11-03",
            highlights = listOf(
                "转场等待真实绘制完成后开始，最长等待 0.3 秒",
                "无法取得按钮位置时跳过动画",
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
                "新增崩溃取证：崩溃后可查看完整堆栈并复制",
                "新增质感玻璃外观，可与任意配色组合",
                "新增诊断入口：崩溃报告、最近问题、运行环境与启动体检",
                "小内存设备自动进入省内存档",
            ),
        ),
        ChangelogEntry(
            version = "1.14",
            date = "2026-11-03",
            highlights = listOf(
                "新增质感玻璃主题：胶囊、卡片与按钮采用半透明材质",
                "控件光效跟随触控位置，按压带形变反馈",
                "省电档自动关闭动画",
            ),
        ),
        ChangelogEntry(
            version = "1.13",
            date = "2026-11-02",
            highlights = listOf(
                "新增酒馆场景：角色卡、长期记忆与第一人称对话",
                "新增写作场景：世界书、剧情、人物、插件与独立提示词",
                "引导页新增用途选择，顶栏支持场景切换",
                "按键触控区统一扩大至 48dp",
            ),
        ),
        ChangelogEntry(
            version = "1.12",
            date = "2026-11-02",
            highlights = listOf(
                "移除内置 Python 引擎，保留设备自带的 shell 与 JavaScript",
                "新增代码静态检查：Python 缩进与语法、Java 类名与包名、C/C++ 预处理配对",
            ),
        ),
        ChangelogEntry(
            version = "1.11",
            date = "2026-11-01",
            highlights = listOf(
                "新增首次引导：屏幕形状、对话布局与主题三步配置",
                "新增圆屏专属排版，列表项按距圆心距离收窄",
                "新增四种对话布局：气泡、卡片、极简与环形",
                "提供 6 套底色与 6 种强调色，共 36 种组合",
            ),
        ),
        ChangelogEntry(
            version = "1.10",
            date = "2026-10-31",
            highlights = listOf(
                "B 站处理方式扩展为总结、评论与私信三种",
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
                "评论风格支持默认、认真点评、抽象玩梗与自定义",
                "新增 bili_inbox、bili_thread 与 bili_reply 工具",
                "后台任务支持通知栏进度与唤醒锁续期",
            ),
        ),
        ChangelogEntry(
            version = "1.8",
            date = "2026-10-31",
            highlights = listOf(
                "新增刷 B 站：搜索、读取字幕、筛选并写入当日日报",
                "新增七个 bili_* 工具，关闭后不进入上下文",
                "日报按 BV 号计数，同一视频当日不重复记录",
                "发评论默认仅预览，需显式开启并逐次确认",
            ),
        ),
        ChangelogEntry(
            version = "1.7",
            date = "2026-10-20",
            highlights = listOf(
                "界面迁移至 Jetpack Compose for Wear OS 与 Material 3",
                "启动后直接进入对话，左右滑动切换设置与对话",
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
                "对话页新增工作流程块，统一展示思考与工具调用",
            ),
        ),
        ChangelogEntry(
            version = "1.5",
            date = "2026-10-17",
            highlights = listOf(
                "云端编译全流程自动化，从触发到产出 APK 无需人工介入",
                "提交后立即返回运行链接，构建在云端继续",
                "工具卡片标注预计耗时",
            ),
        ),
        ChangelogEntry(
            version = "1.4",
            date = "2026-10-17",
            highlights = listOf(
                "新增 MCP 服务器接入，外部服务工具进入 Agent 工具清单",
                "新增插件机制，四个模块可独立开关",
                "取消步数限制，长任务可持续执行",
                "设置页拆分为二级分组",
            ),
        ),
        ChangelogEntry(
            version = "1.3",
            date = "2026-10-15",
            highlights = listOf(
                "新增运行脚本：在设备上执行 shell、JavaScript 与 Python",
                "新增云端编译：构建任务交由 GitHub Actions 执行",
                "提供 lite 与 full 两种安装包",
                "内置两个云端模板，可部署至自有仓库",
            ),
        ),
        ChangelogEntry(
            version = "1.2",
            date = "2026-10-14",
            highlights = listOf(
                "新增多语言代码校验：Python、Kotlin、Java、C、Go、Rust、Swift、Shell 与 YAML",
                "新增 edit_file、glob、grep、todo_write、open_file、open_screen 与 create_event",
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
                "新增联网搜索、单位换算、文件操作与闹钟管理",
            ),
        ),
        ChangelogEntry(
            version = "1.0.0",
            date = "2026-10-13",
            highlights = listOf(
                "首个版本：双后端（直连 API 与 DSH 服务器）、Material 3 界面",
                "Token 用量看板：今日、近 7 天、本月与全部",
                "提供设备性能分档与无 WebView 的 Markdown 渲染",
            ),
        ),
    )

    /** 关于页里「当前版本」对应的那一条 */
    val latest: ChangelogEntry? get() = entries.firstOrNull()

    /**
     * 测试版更新日志。
     *
     * 正式版客户端不显示这段，正式版的 [entries] 也不合并 beta 内容 ——
     * 测试通道的构建号对正式用户没有意义。反过来，beta 客户端要能看到
     * 自己这一版的改动：装 beta 的人正在验证的就是这些条目。
     *
     * 书写约定与 [entries] 相同（见文件头），只多一条：版本号带 `-beta.N`。
     */
    val betaEntries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "1.24.2-beta.8",
            date = "2026-11-27",
            highlights = listOf(
                "玻璃高斯模糊在 Android 12 以下改用 RenderScript 离线计算，低版本同样生效",
                "设置页不再在低版本把高斯模糊标为不可用",
                "模糊计算失败时自动退回未模糊的背景，不影响界面显示",
            ),
        ),
        ChangelogEntry(
            version = "1.24.2-beta.7",
            date = "2026-11-27",
            highlights = listOf(
                "外观设置新增「高斯模糊」开关，玻璃卡片下的背景可叠加真高斯模糊",
                "开启后玻璃卡片本体变薄，背景纹理透出更明显",
                "开关仅在玻璃材质下显示，M3E 材质卡片不透明、效果不可见",
            ),
        ),
        ChangelogEntry(
            version = "1.24.2-beta.6",
            date = "2026-11-27",
            highlights = listOf(
                "修复深色模式下部分页面与弹窗文字颜色不随主题变化的问题",
                "确认弹窗与动态页正文按主题层级取色",
                "图标默认色统一跟随当前主题",
            ),
        ),
        ChangelogEntry(
            version = "1.24.2-beta.5",
            date = "2026-11-27",
            highlights = listOf(
                "修复养鱼页在经典界面下仍使用 M3E 顶栏与弹窗的问题，改为跟随当前界面体系",
                "修复模型仅思考未调用工具时思维链无法展开的问题",
                "修复工作流卡片在两种界面体系下圆角与底色不一致的问题",
                "关于页在测试版客户端中显示测试通道日志，正式版仍只列正式版本",
            ),
        ),
        ChangelogEntry(
            version = "1.24.2-beta.4",
            date = "2026-11-26",
            highlights = listOf(
                "新增 ADB 直连通道：无线调试扫码配对或 USB 授权后，可在 Shizuku 不可用时执行 shell 命令",
                "修复 shell 命令执行中的长度解析异常与流复用问题，失败时按服务等级自动降级",
                "修复脚本输出无退出码时被误判为失败",
            ),
        ),
    )

    /**
     * 当前客户端应该显示的日志。
     *
     * `-beta.N` 构建 = 测试版条目 + 正式版历史；其余构建只显示正式版。
     * 传 `BuildConfig.VERSION_NAME` 即可，不在这里读构建信息（单测要好构造）。
     */
    fun visibleFor(versionName: String): List<ChangelogEntry> =
        if (versionName.contains("-beta", ignoreCase = true)) betaEntries + entries else entries
}
