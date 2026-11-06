package com.dshwatch.domain.roleplay

/**
 * 把 [RoleCard] 拼成给模型的系统提示。
 *
 * ## 为什么所有内容都塞进 system，而不是发成一条用户消息
 *
 * 用户的要求是「初次对话时发送给模型，之后不再发送」。实现上有两条路：
 *  1. 第一轮把设定作为一条 user 消息写进历史，之后靠历史自动带过去；
 *  2. 每轮都放进 system 提示。
 *
 * 选后者，因为：
 *  - 对话长了以后，早期的 user 消息会被历史截断（`TokenEstimator.trimToBudget`），
 *    设定一旦被截掉，角色就开始崩人设 —— 这是最难排查的一类"跑偏"；
 *  - 历史里留一条巨大的 user 消息，模型很容易把它当成"用户刚说的话"来回应，
 *    表现为莫名其妙地总结设定；
 *  - 用户在设置里改一张卡，应该下一句就生效，而历史里的旧消息不会跟着变。
 *
 * system 提示每轮重算，天然满足「改了就生效、永不丢失」，
 * 而"之后不再发送"的诉求（省 token）由 [TavernPrompt.buildSummary] 在压缩时处理。
 */
object TavernPrompt {

    /** 硬性约束：只说话，不解释、不旁白、不跳出角色 */
    private const val CORE_RULES = """【最高优先级规则】
1. 你**就是**下面设定的这个角色，用第一人称直接说话。不要自称 AI、助手或模型。
2. 只输出角色在**此刻**说的话和必要的动作描写，不要写"你好，我是……"这类介绍。
3. 不要解释、不要总结、不要分析、不要加标题、不要用 Markdown 格式（除必要时用 *斜体* 表示动作）。
4. 不要跳出角色询问"需要我帮你什么"。角色不会主动提供"服务"。
5. 不要替用户说话或替用户做决定，也不要描写用户的心理活动。
6. 回答要短：默认 1~4 句，像真人聊天。用户要求展开时再展开。
7. 不知道的事就按角色的认知去猜或反问，不要用"作为一个 AI 我无法"这类话术。
8. 有些内容用文字讲很累，用一块板子讲一眼就懂 —— 这时可以用**面板**或**界面**：
   - 角色状态、随身物品、人物关系、场景地点、章节进度这类**结构化信息** → 用面板；
   - 用户明确要求「好看一点 / 做个界面 / 用 HTML 展示」→ 才用 HTML 界面。
   能用面板就不要写 HTML：面板写得短、不会写错、渲染也快。
   日常对白两者都不要输出，直接说话 —— 每句话都配一块板子会把对话淹掉。
9. HTML 界面的写法（会被**原样渲染在对话里**，并且**可以点击交互**）：
   - 用 ```html 围栏包住一份完整文档，样式写在 <style> 里，不要外链 CSS；
   - 屏幕只有约 330px 宽：不要固定像素宽度、不要多列网格，纵向排列就好；
   - 字号 12~14px，内边距 8~10px，行高 1.5 左右；
   - 可以带交互：<details>/<summary> 做展开收起，或几行 JS 切换标签页、翻页；
   - 高度有上限，超高时可以全屏查看，所以关键信息放最前面；
   - 配色自行决定（这就是"自定义界面"的意义），但文字与背景对比要够。
10. 需要用户在几个方向里挑一个时，把选项放在回复的**最末尾**，单独成行：
    A. 走过去看看
    B. 留在原地
    C. 先问问她
    规则：
    - 一行一个，从 A 开始按顺序排，最多 5 个；
    - 每个选项写成用户**可以直接说出口的一整句**（会被原样当作用户的回复发出）；
    - 选项块必须紧贴结尾，后面不要再补话、不要空行、不要加解释；
    - 需要允许自由发挥时补一条「D. 自定义回复」；
    - 一次回复最多一组选项，不需要选择时就不要写。
11. 面板的写法（比 HTML 省事得多，结构化信息优先用它）：
    以 ```panel 开头，第一行写「标题 | 副标题」；
    之后每行一个条目，写成「名称 | 标签 | 数值」，不用的位置留空但竖线要保留；
    条目下面缩进两格的行是它的详情，用户点「阅」才展开；
    最后用 ``` 收尾。数值写成 48/100 会渲染成进度条。
"""

    /**
     * 完整系统提示。
     *
     * @param memory 长期记忆文档的内容（可为空）。它放在设定**之后、世界书之前**：
     *   模型先确认"我是谁"（设定），再看"我经历过什么"（记忆），
     *   最后才是"这个世界是什么样"（世界书）。倒过来写会让模型拿早期摘要
     *   去覆盖当前人设，改了卡也不生效。
     */
    fun build(card: RoleCard, memory: String = "", extraLore: String = ""): String = buildString {
        append(CORE_RULES)
        append("\n\n【角色设定】\n")
        append("姓名：").append(card.displayName).append('\n')
        card.fields.forEach { f ->
            if (f.key.isNotBlank() && f.value.isNotBlank()) {
                append(f.key).append('：').append(f.value).append('\n')
            }
        }
        if (card.persona.isNotBlank()) {
            append("\n").append(card.persona.trim()).append('\n')
        }
        if (card.userPersona.isNotBlank()) {
            append("\n【用户的身份】（对话中的“你”指这个角色，用户是下列人物）\n")
            append(card.userPersona.trim()).append('\n')
        }
        if (card.style.isNotBlank()) {
            append("\n【说话方式】\n").append(card.style.trim()).append('\n')
        }

        if (memory.isNotBlank()) {
            append("\n【你记得的事】（之前对话的摘要，按时间顺序，越靠后越近）\n")
            append(memory.trim().take(MEMORY_CHAR_BUDGET)).append('\n')
        }

        val lore = buildLore(card, extraLore)
        if (lore.isNotBlank()) {
            append("\n【世界设定】\n").append(lore).append('\n')
        }

        val sysPlugins = card.plugins.filter { it.enabled && it.position == CardPlugin.POS_SYSTEM }
        if (sysPlugins.isNotEmpty()) {
            append("\n【补充要求】\n")
            sysPlugins.forEach { append(it.prompt.trim()).append('\n') }
        }

        val lastPlugins = card.plugins.filter { it.enabled && it.position == CardPlugin.POS_LAST }
        if (lastPlugins.isNotEmpty()) {
            append("\n【最终约束】（优先级高于以上所有描述）\n")
            lastPlugins.forEach { append(it.prompt.trim()).append('\n') }
        }
    }

    /**
     * 世界书注入。
     *
     * 常驻条目全放；带关键词的只在 [extraLore]（最近的对话文本）里命中时才放。
     * 按优先级降序、总量封顶 [LORE_CHAR_BUDGET] —— 手表上上下文本来就紧张，
     * 世界书不能无节制地占。
     */
    fun buildLore(card: RoleCard, extraLore: String = ""): String {
        if (card.entries.isEmpty()) return ""
        val haystack = extraLore.lowercase()
        val picked = card.entries
            .filter { it.enabled && it.content.isNotBlank() }
            .filter { e ->
                e.alwaysOn || e.keywords.any { k -> k.isNotBlank() && haystack.contains(k.lowercase()) }
            }
            .sortedByDescending { it.priority }

        val sb = StringBuilder()
        for (e in picked) {
            val block = (if (e.title.isNotBlank()) "· ${e.title}：\n" else "· ") + e.content.trim()
            if (sb.length + block.length > LORE_CHAR_BUDGET) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(block)
        }
        return sb.toString()
    }

    /**
     * 写作场景的提示。
     *
     * 与酒馆相反：写作**要**描写、要叙述、要长文。所以规则完全不一样，
     * 复用同一套 CORE_RULES 会把续写写成对话体。
     */
    fun buildWriting(
        card: RoleCard,
        memory: String = "",
        extraLore: String = "",
        /**
         * 用户「已经写好的开头」（卡里的 greeting）。
         *
         * 这段文字以前**从来没有**进过模型上下文：它只显示在开场提示卡里，
         * 而首次续写的引导语写的是「以上是我已经写好的开头」—— 对模型来说
         * 「以上」是空的，于是它要么另起一段，要么反问「你想让我写什么」。
         * 现在作为系统提示的一段常驻：写作是长期工程，开头是所有后续内容的
         * 依据，每轮都带上是值得的（超长时只保留末尾，见 [OPENING_CHAR_BUDGET]）。
         */
        opening: String = "",
    ): String = buildString {
        append(
            """【你是这部作品的作者】
直接续写正文，不要任何说明、前言、总结或"以下是续写"之类的话。
不要用 Markdown 标题与列表，除非原文就是这种体裁。
保持视角、人称、时态与原文一致；保持已有人物的语气与行为逻辑。
需要描写时写具体的动作与细节，不要堆砌抽象的形容词。
一次续写默认 200~600 字，用户明确要求更短或更长时按用户要求。
不要替用户"总结剧情"，也不要问"需要我怎么写"——直接写。

【什么时候用界面】
正文一律用纯文字续写。只有「人物表、势力关系、时间线、道具清单、章节大纲」
这类结构化信息、且用户希望一眼看清时，才在正文之外附一段 ```html 界面
（宽约 330px、纵向排列、字号 12~14px，可带展开收起）。正文不允许塞进 HTML。"""
        )
        append("\n\n【作品设定】\n")
        append("标题/主角：").append(card.displayName).append('\n')
        card.fields.forEach { f ->
            if (f.key.isNotBlank() && f.value.isNotBlank()) {
                append(f.key).append('：').append(f.value).append('\n')
            }
        }
        if (card.persona.isNotBlank()) {
            append("\n").append(card.persona.trim()).append('\n')
        }
        if (card.style.isNotBlank()) {
            append("\n【文风要求】\n").append(card.style.trim()).append('\n')
        }
        if (opening.isNotBlank()) {
            append("\n【已经写好的开头】（正文从这里往下接，不要复述、不要重写）\n")
            append(opening.trim().takeLast(OPENING_CHAR_BUDGET)).append('\n')
        }
        if (memory.isNotBlank()) {
            append("\n【前情提要】（已写内容的摘要）\n")
            append(memory.trim().take(MEMORY_CHAR_BUDGET)).append('\n')
        }
        val lore = buildLore(card, extraLore)
        if (lore.isNotBlank()) {
            append("\n【世界观】\n").append(lore).append('\n')
        }
        val sysPlugins = card.plugins.filter { it.enabled && it.position == CardPlugin.POS_SYSTEM }
        if (sysPlugins.isNotEmpty()) {
            append("\n【补充要求】\n")
            sysPlugins.forEach { append(it.prompt.trim()).append('\n') }
        }
        val lastPlugins = card.plugins.filter { it.enabled && it.position == CardPlugin.POS_LAST }
        if (lastPlugins.isNotEmpty()) {
            append("\n【最终约束】（优先级高于以上所有描述）\n")
            lastPlugins.forEach { append(it.prompt.trim()).append('\n') }
        }
    }

    /**
     * 第一次进入会话时用的开场消息。
     *
     * 酒馆：模型直接说开场白（以助手身份），而不是让用户先开口 ——
     * 否则用户面对一个空白界面不知道能说什么。
     * 写作：开场白作为**用户**侧的第一段正文（"这是我已经写好的开头"），
     * 模型接着往下写。
     */
    fun greetingOf(card: RoleCard): String = card.greeting.trim()

    /** 长期记忆压缩时给模型的指令 */
    fun compressInstruction(scene: String): String = if (scene == Scenes.WRITING) {
        """把下面的正文压缩成一段【前情提要】，用于之后续写时复述。
要求：
- 按时间顺序，只写已发生的关键事件、人物关系变化、重要设定揭示。
- 保留专有名词、地点、数字、伏笔；不要保留原文句子、不要评论文笔。
- 用第三人称概述，不要加入原文没有的内容。
- 控制在 400 字以内。
只输出这段提要本身。"""
    } else {
        """把下面的对话压缩成一段【记忆】，用于之后继续扮演时复述。
要求：
- 按时间顺序，写"谁做了什么、关系发生了什么变化、承诺过什么、知道了哪些秘密"。
- 保留专有名词与数字；不要保留原句，不要评论，不要写"用户说/我回复"这种流水账。
- 用第三人称概述，不要编造对话里没有的情节。
- 控制在 400 字以内。
只输出这段记忆本身。"""
    }

    /** 记忆文档的总预算：超过就只保留最新的部分（按"## 时间戳"分段取尾部） */
    const val MEMORY_CHAR_BUDGET = 6_000

    /** 世界书总预算 */
    const val LORE_CHAR_BUDGET = 3_000

    /**
     * 「已经写好的开头」注入预算。
     *
     * 留末尾而不是开头：续写看的是**最后发生了什么**，一段两万字的旧稿
     * 全塞进系统提示只会把上下文吃光。3000 字足够交代到断点处。
     */
    const val OPENING_CHAR_BUDGET = 3_000
}
