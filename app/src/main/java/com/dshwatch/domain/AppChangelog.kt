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
        ChangelogEntry(
            version = "1.23.0-beta.5",
            date = "2026-11-14",
            highlights = listOf(
                "新增 两套界面并列：设置 → 外观 → 界面体系，可在「经典」与「M3E」之间切换",
                "新增 M3E 界面整套换成 Wear Material 3 Expressive 组件：骨架、卡片、按钮、开关、对话框、列表",
                "调整 按钮改回手表尺寸：52dp 高、20sp 标签、26dp 图标（之前是手机尺寸）",
                "调整 对话框只留一个动作，取消靠下滑或返回键",
                "新增 引导页最后一屏可以直接选界面体系",
            ),
        ),
        ChangelogEntry(
            version = "1.23.0-beta.4",
            date = "2026-11-14",
            highlights = listOf(
                "新增 会话流走 Noise 隧道：Remote 连接之后可以直接聊天，不用再开 SSH",
                "新增 电脑上的审批会在手表上弹出，可选「允许一次」或「拒绝」",
                "修复 Noise 握手的关联数据用错，此前和真实服务器握手必然失败",
                "修复 一元调用的请求 id 与等待方不一致，表现为调用超时",
            ),
        ),
        ChangelogEntry(
            version = "1.23.0-beta.3",
            date = "2026-11-14",
            highlights = listOf(
                "新增 手机配对：手表开个网页，手机扫码打开后完成 DSH Remote 登录，不用在表上打字",
                "支持 在手机上粘贴令牌或用邮箱密码登录自建服务器",
                "修复 配对页面只认一次性口令与 IP 地址，同网段的其他人打不开",
            ),
        ),
        ChangelogEntry(
            version = "1.23.0-beta.2",
            date = "2026-11-14",
            highlights = listOf(
                "新增 用 SSH 连接电脑上的 DSH：填主机、用户名、密码，手表自动在电脑上启动服务并转发端口",
                "支持 直接粘贴 ssh 整行，自动拆出主机、端口与用户名",
                "改进 对话改为订阅会话流，回答逐字出现",
            ),
        ),
        ChangelogEntry(
            version = "1.23.0-beta.1",
            date = "2026-11-13",
            highlights = listOf(
                "新增 测试更新通道：设置里切「稳定版 / 测试版」，测试版能收到预发布版本",
                "新增 自定义背景：从相册选一张图，支持虚化与铺满",
                "修复 浅色模式下全屏画板变成黑底深字，整屏推导只剩轮廓",
            ),
        ),
        ChangelogEntry(
            version = "1.22.4",
            date = "2026-11-13",
            highlights = listOf(
                "修复 浅色模式下玻璃卡片与弹窗被误判成深色，整块变黑、文字读不出来",
            ),
        ),
        ChangelogEntry(
            version = "1.22.3",
            date = "2026-11-13",
            highlights = listOf(
                "支持 更大的插件包：不再整包读进内存，上限提到 32 MB，二进制包也吃得下",
                "改进 深色玻璃弹窗加上压暗的底，正文对比度从 2.1:1 提到 4.6:1",
            ),
        ),
        ChangelogEntry(
            version = "1.22.2",
            date = "2026-11-13",
            highlights = listOf(
                "新增 从文件导入插件：.tgz / .tar.gz / .json，用系统选择器直接挑",
                "支持 手表没有文件管理器时，从工作空间里选包",
                "改进 链接导入能认出「这是网页不是文件」，直接说清怎么拿 raw 地址",
            ),
        ),
        ChangelogEntry(
            version = "1.22.1",
            date = "2026-11-13",
            highlights = listOf(
                "新增 插件清单的 tools 段：隐藏工具、只留白名单、给指定工具加确认",
                "新增 插件导入：从剪贴板、链接或本地文件直接装，装之前先预检",
                "支持 多份插件同时生效，白名单取交集、确认项取并集，只会越收越紧",
            ),
        ),
        ChangelogEntry(
            version = "1.22.0",
            date = "2026-11-13",
            highlights = listOf(
                "新增 声明式插件：把 JSON 清单放进工作空间 plugins/ 目录，就能改系统提示与尾部规则",
                "支持 按模型匹配与变量插值，同一份清单可以只在 deepseek-* 上生效",
                "提供 逐份校验：某份清单写错了只跳过它，其余照常加载",
            ),
        ),
        ChangelogEntry(
            version = "1.21.5",
            date = "2026-11-12",
            // 纯修复版：Shizuku provider 的访问权限写错，binder 推不进来
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.21.4",
            date = "2026-11-11",
            // 纯修复版：后台服务死线、定时任务保活、设置写入，没有新能力
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.21.3",
            date = "2026-11-11",
            // 纯修复版：只补了清单里漏掉的一条 API 权限声明，没有新能力
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.21.2",
            date = "2026-11-11",
            highlights = listOf(
                "修好「装了 Shizuku、也授权了，App 仍说没装」：之前查的包名写错了",
                "补上 Android 11+ 的包可见性声明，查询不再被系统拦掉",
                "设置页的「打开 Shizuku」按钮现在能真的跳过去",
            ),
        ),
        ChangelogEntry(
            version = "1.21.1",
            date = "2026-11-11",
            highlights = listOf(
                "工具恢复全量下发：模型不再需要先取工具，卡在「获取工具」的问题消失",
                "操控设备期间也不再收窄工具表，做完能直接写文件或总结",
                "旧读屏内容的折叠保留，长任务的历史仍然只占几行",
            ),
        ),
        ChangelogEntry(
            version = "1.21.0",
            date = "2026-11-11",
            highlights = listOf(
                "接入 Shizuku：AI 能以 adb 身份执行命令，装应用、读日志、改屏幕与电量",
                "每条命令先说明用途再执行，危险命令二次确认且永不记住",
                "常规操作按类别「始终允许」，并记录可查的执行审计",
            ),
        ),
        ChangelogEntry(
            version = "1.20.0",
            date = "2026-11-10",
            highlights = listOf(
                "工具改为按需取用：每轮下发的工具表从 1.8 万字降到 5 千字",
                "常驻读写改、搜索、执行等 13 个高频工具，B站 / 构建 / 提醒等分组按需加载",
                "模型用 tool_search 取工具，取到的工具下一轮起带完整参数",
            ),
        ),
        ChangelogEntry(
            version = "1.19.0",
            date = "2026-11-10",
            highlights = listOf(
                "操控设备时只带设备工具，每轮的工具表开销降到约十分之一",
                "设备操控历史里的旧屏幕自动折叠，只保留最近三屏",
                "界面没有变化时不再重发整屏",
            ),
        ),
        // 1.18.5 是纯修复（设备操控的假成功与审批挂死），按约定不写条目
        // 1.18.4 是纯修复（文件浏览器面板补不透明底），按约定不写条目
        // 1.18.3 起是文件浏览器
        ChangelogEntry(
            version = "1.18.3",
            date = "2026-11-09",
            highlights = listOf(
                "新增内置文件浏览器：不依赖系统文件管理器，直接翻目录选文件",
                "附件按钮默认走内置浏览器，可翻下载 / 文档 / 图片 / 相机 / 存储根目录",
                "设置 → 文件里可切回系统选择器",
            ),
        ),
        // 1.18.2 是修复与瘦身（提示词压缩、放开设置页、取消操控停滞判定），按约定不写条目
        ChangelogEntry(
            version = "1.18.2",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        // 1.18.1 是纯修复（图片压缩、翻页滑动、操控期间保活），按约定不写条目
        ChangelogEntry(
            version = "1.18.1",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.18.0",
            date = "2026-11-09",
            highlights = listOf(
                "新增设备操控：AI 可以读取屏幕、点击、输入、打开应用",
                "操作时 DSH 退到后台，屏幕底部显示当前动作与停止按钮",
                "密码、验证码与支付确认不代填，遇到会让用户接手",
            ),
        ),
        // 1.17.4 是纯修复（几何内联坐标解析、公式 \Big 与长箭头），按约定不写条目
        ChangelogEntry(
            version = "1.17.4",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        // 1.17.3 是纯修复（几何线段端点用错坐标轴），按约定不写条目
        ChangelogEntry(
            version = "1.17.3",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        // 1.17.2 是纯修复（画布说明、搜索源、JS 顶层 return），按约定不写条目
        ChangelogEntry(
            version = "1.17.2",
            date = "2026-11-09",
            highlights = emptyList(),
        ),
        // 1.17.1 是纯修复（智谱内置项未走内置分支），按约定不写条目
        ChangelogEntry(
            version = "1.17.1",
            date = "2026-11-08",
            highlights = emptyList(),
        ),
        ChangelogEntry(
            version = "1.17.0",
            date = "2026-11-08",
            highlights = listOf(
                "新增智谱 GLM 内置通道：GLM-4.7-Flash / GLM-4.6V-Flash / CogView-3-Flash 三个免费模型",
                "内置通道设置页只保留模型参数，不再显示也不允许修改 API Key 与地址",
                "新增文件问答：txt / md / docx / pdf / 代码，本地提取文本后随消息发送",
                "新增画布：AI 生成的 LaTeX 公式、思维导图、几何图直接在对话里渲染并可全屏",
                "刷 B站新增收藏夹查看、投币与收藏（默认关闭，需手动打开）",
            ),
        ),
        ChangelogEntry(
            version = "1.16.0",
            date = "2026-11-08",
            highlights = listOf(
                "新增内置 Agnes 通道：5 个免费模型（文本 / 出图 / 出视频），装完即可对话",
                "对话页直接显示图片与视频：可放大、播放并分享，媒体随会话保存在本机",
                "新增新手教学：讲清 API Key、供应商地址、模型选择、上下文窗口与温度",
                "支持把图片作为多模态输入，视觉模型可直接读图",
            ),
        ),
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
