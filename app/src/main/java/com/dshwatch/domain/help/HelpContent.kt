package com.dshwatch.domain.help

/**
 * 帮助与教程的全部内容。
 *
 * ## 为什么内容不放在 AppStrings
 *
 * `AppStrings` 是一个接口，新增一条文案要在两个实现里各写一遍，适合**按钮、标题**
 * 这种短文案。教程是成段的说明 + 代码样例，几十条挤进去会让那个文件更难维护，
 * 而且教程要整体阅读、会一起改版，放在一起更好改。
 *
 * 这里用 [HelpText] 同时存中英两份，界面按当前语言取 —— 这样加一条教程
 * 只需要写一次结构，不会出现"中文加了、英文忘了"的半截状态。
 *
 * ## 写内容时的约束
 *
 * 1. **每条教程都要能被照着做**：写"怎么用"，不写"为什么这么设计"；
 * 2. **代码样例必须是真能跑的格式**：面板、选项、导入格式的样例都直接取自解析器；
 * 3. **一句话一行**：手表一屏只有十几行，段落超过 3 行就会被跳过。
 */
data class HelpText(val zh: String, val en: String) {
    fun of(chinese: Boolean): String = if (chinese) zh else en
}

enum class HelpKind {
    /** 普通段落 */
    TEXT,

    /** 列表项，渲染时前面加圆点 */
    BULLET,

    /** 代码 / 格式样例，等宽字体 */
    CODE,

    /** 提示，强调色显示 */
    NOTE,
}

data class HelpBlock(val kind: HelpKind, val text: HelpText)

data class HelpTopic(
    val id: String,
    val title: HelpText,
    val summary: HelpText,
    val blocks: List<HelpBlock>,
) {
    /** 纯文本形式，供「复制本页」用 */
    fun plainText(chinese: Boolean): String = buildString {
        appendLine(title.of(chinese))
        appendLine()
        blocks.forEach { b ->
            val prefix = when (b.kind) {
                HelpKind.BULLET -> "· "
                else -> ""
            }
            appendLine(prefix + b.text.of(chinese))
            appendLine()
        }
    }.trim()
}

private fun t(zh: String, en: String) = HelpText(zh, en)
private fun text(zh: String, en: String) = HelpBlock(HelpKind.TEXT, t(zh, en))
private fun bullet(zh: String, en: String) = HelpBlock(HelpKind.BULLET, t(zh, en))
private fun code(zh: String, en: String) = HelpBlock(HelpKind.CODE, t(zh, en))
private fun note(zh: String, en: String) = HelpBlock(HelpKind.NOTE, t(zh, en))

object HelpContent {

    val topics: List<HelpTopic> = listOf(

        HelpTopic(
            id = "start",
            title = t("快速上手", "Getting started"),
            summary = t("输入、发送、停止与复制", "Type, send, stop, copy"),
            blocks = listOf(
                text(
                    "对话页底部是输入条。点它进入输入页，说完点「发送」。生成过程中再点一次，可以中途停下。",
                    "The bar at the bottom of the chat opens the input page. Tap Send when you are done. Tap again while generating to stop.",
                ),
                bullet(
                    "长按消息正文可以选中复制；被折叠的长回复点一下展开。",
                    "Long-press a message to select and copy. Tap a collapsed reply to expand it.",
                ),
                bullet(
                    "顶栏图标从左到右通常是：会话列表、设置、关于。不同页面略有差别。",
                    "The top bar usually holds sessions, settings and about, from left to right.",
                ),
                note(
                    "第一次使用要先在「设置 → 模型」里填好供应商和 API Key，否则发送时会提示未配置。",
                    "Before the first message, fill in a provider and API key under Settings → Model.",
                ),
            ),
        ),

        HelpTopic(
            id = "scene",
            title = t("三个场景", "Three scenes"),
            summary = t("编程 / 酒馆 / 写作各是什么", "Coding, tavern and writing"),
            blocks = listOf(
                text(
                    "首页按场景分流。切换入口在「设置 → 对话 → 使用场景」，引导页里也能选。",
                    "The home screen depends on the scene. Switch it under Settings → Chat → Scene.",
                ),
                bullet(
                    "编程：带文件、执行、云编译等工具，回复里会出现工具卡。",
                    "Coding: file, shell and cloud-build tools; replies may contain tool cards.",
                ),
                bullet(
                    "酒馆：以角色卡组织，提示词要求第一人称、每次 1~4 句。",
                    "Tavern: organised by character cards, first person, 1–4 sentences per reply.",
                ),
                bullet(
                    "写作：同一套角色卡，提示词换成续写，一次 200~600 字。",
                    "Writing: same cards, but the prompt asks for 200–600 characters of prose.",
                ),
                note(
                    "切场景不会删掉已有会话，原来的对话在会话列表里都还在。",
                    "Switching scenes never deletes conversations; they all stay in the session list.",
                ),
            ),
        ),

        HelpTopic(
            id = "panel",
            title = t("状态面板", "Status panels"),
            summary = t("用一段文本画出状态卡", "A text block that becomes a card"),
            blocks = listOf(
                text(
                    "让模型用 ```panel 围栏描述状态，客户端会渲染成卡片 —— 比让它写 HTML 短得多，也不容易写错。",
                    "Ask the model for a ```panel fenced block and the app renders it as a card — far shorter than HTML and much harder to break.",
                ),
                code(
                    "```panel 公子 · 墨染 | 江南书香世家嫡子\n" +
                        "近侍 | 柳儿 | 48/100\n" +
                        "  父 | 陆先生已去，老爷未至\n" +
                        "宅邸 | 环境与规矩\n" +
                        "```",
                    "```panel Lord Moran | Heir of a Jiangnan house\n" +
                        "Attendant | Liu'er | 48/100\n" +
                        "  Father | Gone; the elder has not arrived\n" +
                        "Estate | Grounds and rules\n" +
                        "```",
                ),
                text(
                    "首行是标题「主标题 | 副标题」，副标题可省。顶格行是条目「名称 | 标签 | 数值」，标签与数值都可省。",
                    "The first line is the title: `Title | Subtitle` (subtitle optional). Unindented lines are rows: `Name | Tag | Value`.",
                ),
                bullet(
                    "数值写成 48/100 会自动渲染成进度条，一眼看出还剩多少。",
                    "A value like 48/100 becomes a progress bar, so you can see what is left at a glance.",
                ),
                bullet(
                    "缩进两格的行是上一条目的详情，点条目右侧的「阅」展开，不跳页。",
                    "A line indented by two spaces is detail for the row above; tap 阅 to expand it in place.",
                ),
                bullet(
                    "一条回复最多 3 块面板，可以和选项、HTML 界面混排。",
                    "Up to three panels per reply, and they can sit alongside choices or an HTML view.",
                ),
                note(
                    "围栏名写 panel、card 或 面板 都认。解析失败的块会原样留在正文里，不会凭空消失。",
                    "The fence name may be panel, card or 面板. A block that fails to parse stays in the text verbatim.",
                ),
            ),
        ),

        HelpTopic(
            id = "choice",
            title = t("行动选项", "Action choices"),
            summary = t("A / B / C 自动变成按钮", "A/B/C lines become buttons"),
            blocks = listOf(
                text(
                    "回复末尾连续的 A. / B. / C. 行会自动提取成按钮。点一下，这句话就作为你的回复发出去。",
                    "Consecutive A. / B. / C. lines at the end of a reply become buttons. Tapping one sends that line as your reply.",
                ),
                bullet(
                    "编号必须从 A（或 1、一）开始逐行递增，每行不超过 64 字，最多 8 条。",
                    "Numbering must start at A (or 1, or 一) and increase by one. Each line is at most 64 characters, up to 8 options.",
                ),
                bullet(
                    "写着「自定义回复 / 其他」的那一项，点了会打开输入框，而不是直接发送。",
                    "An option worded as 自定义回复 or 其他 opens the input page instead of sending itself.",
                ),
                bullet(
                    "历史消息里的选项是灰的，防止误点已经过期的选择。",
                    "Choices in older messages are greyed out so you cannot tap a stale option.",
                ),
                note(
                    "数字编号判断更严：只有引导句像在提问（含「选项」「选择」「你想」等词）才当选项，否则按步骤列表处理。",
                    "Numeric lists are stricter: they count as choices only when the lead-in reads like a question.",
                ),
            ),
        ),

        HelpTopic(
            id = "html",
            title = t("自定义界面", "Custom HTML views"),
            summary = t("```html 代码块渲染成界面", "Render an HTML block as a UI"),
            blocks = listOf(
                text(
                    "回复里放一段 ```html 围栏，或者整段以 <!DOCTYPE html> / <html 开头，就会被当成界面渲染，而不是源码。",
                    "Reply with a ```html fence, or a whole document starting with <!DOCTYPE html> / <html, and it is rendered instead of shown as source.",
                ),
                bullet(
                    "高度随内容自适应；超过可视高度时可以点开全屏看完整界面。",
                    "Height follows the content; when it is taller than the screen, open it full screen.",
                ),
                bullet(
                    "界面可以和多条正文、状态面板、行动选项混排在同一条回复里。",
                    "A view can share a reply with text, panels and choices.",
                ),
                note(
                    "只认完整文档。正文里零散的 <br>、<span> 不会被当成界面，否则气泡会莫名其妙地空掉。",
                    "Only full documents count. Stray tags like <br> are left as text.",
                ),
            ),
        ),

        HelpTopic(
            id = "transcript",
            title = t("导入导出格式", "Import / export format"),
            summary = t("纯文本对话的固定分隔格式", "The plain-text transcript format"),
            blocks = listOf(
                text(
                    "导出：会话列表右上菜单 →「导出对话」，可复制、存为 txt 或分享到手机。",
                    "Export: session list → menu → Export. Copy it, save as .txt, or share to your phone.",
                ),
                text(
                    "导入：会话列表底部 →「导入 TXT」，从系统文件或工作空间里选。",
                    "Import: bottom of the session list → Import TXT, then pick a file from the system picker or the workspace.",
                ),
                code(
                    "#===========================================#\n" +
                        "[USER]:\n" +
                        "雨下到第九天，涵洞的水位已经漫过了标尺的第三格。\n" +
                        "#-------------------------------------------#\n" +
                        "[AI]:\n" +
                        "他把手电往下压了压，光柱里全是斜着的雨。",
                    "#===========================================#\n" +
                        "[USER]:\n" +
                        "Ninth day of rain. The culvert has passed the third mark.\n" +
                        "#-------------------------------------------#\n" +
                        "[AI]:\n" +
                        "He tilted the torch down; the beam was full of slanting rain.",
                ),
                bullet(
                    "块头是等号分隔线，块内分段是减号分隔线，每段以 [角色]: 起头。",
                    "A block starts with an equals line; turns are split by a dash line and begin with [ROLE]:.",
                ),
                bullet(
                    "分隔线的长度不限，冒号可省，全角括号和全角冒号也能认。",
                    "The line length does not matter; the colon may be omitted; full-width brackets work too.",
                ),
                bullet(
                    "角色名认 USER / 我 / 用户，以及 AI / ASSISTANT / 助手 / DSH / 模型。",
                    "Role names: USER / 我 / 用户 and AI / ASSISTANT / 助手 / DSH / 模型.",
                ),
                bullet(
                    "编码自动识别 UTF-8 与 GBK，单个文件最大 4 MB。",
                    "UTF-8 and GBK are detected automatically; the file limit is 4 MB.",
                ),
                note(
                    "完全没有标记的纯文本也能导入 —— 整段会作为一条用户消息，适合把设定或草稿塞进上下文。",
                    "Plain text without any marks also imports: the whole text becomes one user message.",
                ),
            ),
        ),

        HelpTopic(
            id = "memory",
            title = t("长期记忆", "Long-term memory"),
            summary = t("自动整理早期对话，写进记忆文档", "Summarise old turns into a memory file"),
            blocks = listOf(
                text(
                    "打开角色卡的「长期记忆」后，早期的对话会被整理成摘要写进记忆文档，之后只把摘要之后的内容发给模型。",
                    "With Long-term memory enabled on a card, older turns are summarised into a memory file and only what follows is sent to the model.",
                ),
                bullet(
                    "自动触发：未整理的部分到 6000 token（小窗口按 55%）或满 40 条消息。",
                    "Automatic: uncompressed content reaching 6000 tokens (or 55% of a small window), or 40 messages.",
                ),
                bullet(
                    "手动触发：对话页顶栏「记忆」→「立即压缩记忆」。",
                    "Manual: top bar → Memory → Compress now.",
                ),
                bullet(
                    "摘要写在工作空间 memory/<角色>.md，可以查看、编辑、导出。",
                    "The summary lives in workspace memory/<card>.md — view, edit or export it.",
                ),
                note(
                    "聊天记录本身不会被删。往上翻还是全部内容，被压缩的只是发给模型的那一段。",
                    "Your chat log is never deleted. Only the part sent to the model is shortened.",
                ),
            ),
        ),

        HelpTopic(
            id = "card",
            title = t("角色卡与世界书", "Cards and lorebooks"),
            summary = t("设定、开场白、关键词条目", "Persona, opening, keyword entries"),
            blocks = listOf(
                text(
                    "酒馆和写作都以角色卡为起点：性格、用户身份、开场白、说话方式，以及任意自定义字段。",
                    "Both tavern and writing start from a card: persona, your role, opening line, speech style and custom fields.",
                ),
                bullet(
                    "世界书条目按关键词命中才注入；标为常驻的条目每次都带。",
                    "Lore entries are injected only when their keywords match; pinned entries always go in.",
                ),
                bullet(
                    "世界书总量 3000 字封顶，超出按优先级截断；记忆 6000 字，开场白 3000 字。",
                    "Lore is capped at 3000 characters by priority; memory at 6000 and the opening at 3000.",
                ),
                bullet(
                    "卡片的「开始对话 / 继续写」会回到这张卡最近一次会话，不会每次新建。",
                    "Start / Continue on a card returns to its most recent session instead of creating one.",
                ),
            ),
        ),

        HelpTopic(
            id = "model",
            title = t("模型与上下文", "Models and context"),
            summary = t("供应商、窗口、温度与工具", "Providers, window, temperature, tools"),
            blocks = listOf(
                text(
                    "「设置 → 模型」里配置供应商、API Key、模型名、上下文窗口与温度。可以并存多个供应商，随时切换。",
                    "Under Settings → Model set the provider, API key, model name, context window and temperature. Several providers can coexist.",
                ),
                bullet(
                    "上下文窗口用于裁剪：装不下时从最早的消息开始丢，但会保留第一条用户消息。",
                    "The context window drives trimming: oldest turns are dropped first, but the first user message is kept.",
                ),
                bullet(
                    "编程场景可以逐个开关工具；酒馆与写作不带工具。",
                    "Coding can toggle tools individually; tavern and writing run without tools.",
                ),
                note(
                    "有些中转站不返回 usage，客户端会用本地估算兜底计量。",
                    "Some proxies omit usage; the app then falls back to a local estimate.",
                ),
            ),
        ),

        HelpTopic(
            id = "update",
            title = t("版本更新", "Updates"),
            summary = t("自动与手动检查、下载安装", "Automatic and manual updates"),
            blocks = listOf(
                text(
                    "「关于」页有「检查更新」，点进去就是更新页；打开更新页时也会自动查一次。",
                    "About has Check for updates. Opening the update page also checks once automatically.",
                ),
                bullet(
                    "自动检查：启动 10 秒后查一次，24 小时内不重复；应用一直开着的话，每 6 小时再看一眼。",
                    "Automatic checks run 10 s after launch, at most once a day, and are re-evaluated every 6 hours while the app stays open.",
                ),
                bullet(
                    "来源是 GitHub Releases，安装包支持断点续传，网络断了可以从上次的进度接着下。",
                    "Packages come from GitHub Releases and download with resume support.",
                ),
                bullet(
                    "首次安装需要「安装未知应用」权限；手表上没有这个入口时，页面会给一条 adb 命令。",
                    "The first install needs the unknown-sources permission; if the watch has no such screen, the page offers an adb command.",
                ),
                note(
                    "「上次检查」记录在版本号下面，随时可以手动再查一次。",
                    "The last check time sits under the version number; you can always re-check manually.",
                ),
            ),
        ),

        HelpTopic(
            id = "diag",
            title = t("诊断与崩溃取证", "Diagnostics"),
            summary = t("崩溃报告、日志与启动体检", "Crash reports, logs, startup snapshot"),
            blocks = listOf(
                text(
                    "「设置 → 运行 → 诊断」里有上次崩溃报告、最近日志、运行环境和启动体检。",
                    "Settings → Runtime → Diagnostics holds the last crash, recent logs, environment and a startup snapshot.",
                ),
                bullet(
                    "崩溃报告和启动体检会同时抄到 Android/data/com.dshwatch/files 下，插电脑就能取。",
                    "Crash reports and the startup snapshot are also copied to Android/data/com.dshwatch/files, reachable over USB.",
                ),
                note(
                    "报告只含机型、版本、启动耗时与最近的启动节点，不含 API Key、对话或工作空间内容。",
                    "Reports contain device model, version and startup timings only — no keys, chats or workspace files.",
                ),
            ),
        ),
    )

    fun find(id: String): HelpTopic? = topics.firstOrNull { it.id == id }
}
