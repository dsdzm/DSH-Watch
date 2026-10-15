package com.dshwatch.domain

/**
 * 更新日志。
 *
 * **每次发版，往 [entries] 的最前面加一条即可** —— 关于页会自动渲染，
 * 不需要另外维护文档、也不会漏掉。最新的一条永远在最上面。
 *
 * 约定：一条日志写 3–5 条「用户能感知的变化」，不写内部重构细节。
 */
data class ChangelogEntry(
    val version: String,
    val date: String,
    val highlights: List<String>,
)

object AppChangelog {

    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "1.3",
            date = "2026-10-15",
            highlights = listOf(
                "新增「运行脚本」：在手表上真的执行 shell / JavaScript / Python，" +
                    "拿到真实的输出和报错（默认关闭，且每次执行都会单独确认）",
                "新增「云端编译」：把手表做不到的事（编 apk、跑 gradle、用 gcc）交给 " +
                    "GitHub Actions，产物自动下载回工作空间",
                "两种安装包：lite（约 7MB）与 full（含 Python 引擎，约 30MB），按需选择",
                "开箱自带两个云端模板，在设置页一键装进你自己的仓库",
                "「校验通过」不再等于「跑得起来」：写完代码可以直接验证结果对不对",
            ),
        ),
        ChangelogEntry(
            version = "1.2",
            date = "2026-10-14",
            highlights = listOf(
                "支持多语言代码校验：Python / Kotlin / Java / C / Go / Rust / Swift / Shell / YAML 等",
                "新增 edit_file：改文件不再需要整份重写，省一半 token 也不易被截断",
                "新增 glob / grep：按文件名或内容（支持正则）找东西",
                "新增 todo_write：多步任务会先在对话里列出计划并实时更新进度",
                "新增 open_file / open_screen：AI 能把界面直接打开，不用自己找菜单",
                "新增 create_event：安排日程（打开系统日历预填好，确认即可）",
                "网页抓取自动转纯文本，同样的内容省下九成 token",
            ),
        ),
        ChangelogEntry(
            version = "1.1",
            date = "2026-10-14",
            highlights = listOf(
                "每个会话使用独立的工作目录（progress/），产物不再互相覆盖",
                "对话管理页可自定义文件位置：选已有文件夹 or 新建",
                "新增 ask_user：信息不够时 AI 会直接问你，而不是自己替你假设",
                "DeepSeek 模型下输入框上方显示峰谷时段 —— 工作日 9-12 点、14-18 点最贵",
                "修复导出分享点了没反应（默认存放位置在外部目录，FileProvider 没映射到）",
                "修复「新建对话」没说话也会占用会话列表",
                "新增工具：联网搜索、单位换算、文件移动与搜索、闹钟管理与震动",
            ),
        ),
        ChangelogEntry(
            version = "1.4.0",
            date = "2026-10-14",
            highlights = listOf(
                "修复「拉取模型」必然崩溃（网络请求跑在了主线程）",
                "工具名称改为中文：创建文件 / 打包构建 / 联网获取 …",
                "工具卡片可直接打开产物预览、导出分享",
                "代码块支持整段折叠；思维链默认收起，流式时自动展开",
                "修复工具参数被截断时报「缺少 path 参数」的误导性错误",
            ),
        ),
        ChangelogEntry(
            version = "1.3.1",
            date = "2026-10-14",
            highlights = listOf(
                "文件位置自动修复改为「每次访问都做」，不再只依赖启动时一次",
                "构建结果会回显「已自动修复 N 个位置错误的文件」",
                "默认文件位置改为外部私有目录，可用数据线直接取出",
                "目标目录不可写时保持原位置，不再切换后彻底失效",
            ),
        ),
        ChangelogEntry(
            version = "1.3.0",
            date = "2026-10-14",
            highlights = listOf(
                "修复「找不到入口文件」：路径未归一化导致文件被写进 6 层嵌套目录",
                "文件存放位置可在内部 / 外部目录之间切换，已有文件自动迁移",
                "构建产物目录可自定义（默认 dist）",
                "系统提示中加入工作空间约定，从源头减少写错路径",
            ),
        ),
        ChangelogEntry(
            version = "1.2.0",
            date = "2026-10-14",
            highlights = listOf(
                "修复使用工具后下一轮请求被 400 拒绝（消息序列非法）",
                "移除剪贴板工具，改为本地工作空间：真正建文件、可预览、可导出",
                "新增「构建」：静态校验 + 内联资源打包成单文件 HTML",
                "新增工作空间浏览页，支持源码 / 产物筛选",
            ),
        ),
        ChangelogEntry(
            version = "1.1.0",
            date = "2026-10-14",
            highlights = listOf(
                "新增本地 Agent：13 个工具 + 多步工具循环，不依赖电脑",
                "工具审批策略可调：关闭 / 每步都问 / 只读自动 / 全自动",
                "性能档位修正：4GB 内存不再被误判为低端机",
                "适配大 R 角屏幕，内容不再被屏幕圆角切掉",
            ),
        ),
        ChangelogEntry(
            version = "1.0.1",
            date = "2026-10-14",
            highlights = listOf(
                "修复首次发消息毫无反应（发送流程把自己取消了）",
                "「测试连接」改为覆盖真实聊天路径，不再测一套发一套",
                "错误提示带上底层原因，不再只说「网络不可达」",
                "新增连接诊断页，四步自检 + 一键复制报告",
            ),
        ),
        ChangelogEntry(
            version = "1.0.0",
            date = "2026-10-13",
            highlights = listOf(
                "首个版本：双后端（直连 API / DSH 服务器）、MD3 界面",
                "Token 用量看板：今天 / 近 7 天 / 本月 / 全部",
                "手表性能分档、无 WebView 的 Markdown 渲染",
            ),
        ),
    )

    /** 关于页里「当前版本」对应的那一条 */
    val latest: ChangelogEntry? get() = entries.firstOrNull()
}
