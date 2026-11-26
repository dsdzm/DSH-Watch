package com.dshwatch.domain.roleplay

/**
 * 把模型的一条回复拆成"连发的几条消息"。
 *
 * ## 为什么按换行拆
 *
 * QQ / 微信里真人说话是碎片化的：想到一句发一句，三秒钟能来三条。
 * 模型一次生成的是**一整段**，如果整段塞进一个气泡，哪怕内容再口语，
 * 观感也是"对方发了一篇小作文"。
 *
 * 提示词里已经要求"每条单独一行"，这里负责把换行变成气泡边界，
 * 顺带清掉模型偶尔带出来的列表符号和 Markdown 星号 —— 聊天框里没人打这些。
 *
 * ## 为什么还要兜底切长句
 *
 * 模型不一定守规矩：一句 90 字的长句照样会来。直接放进一个气泡，
 * 手机上就是占满四行的一大块。所以超过 [SOFT_LIMIT] 的行按句末标点再切，
 * 实在没有标点就硬切。
 *
 * 切分只影响**显示**，存储里仍是完整的一条消息 —— 重新进入会话时
 * 按同一套规则再拆一次，结果一致。
 */
object FishBubbles {

    /** 一次最多显示几个气泡。再多就不像人打字，像刷屏 */
    const val MAX = 5

    /** 单条超过这个长度就考虑再切 */
    private const val SOFT_LIMIT = 60

    /** 合并后允许残留的最大长度，超出就截断 */
    private const val TAIL_LIMIT = 120

    private val LIST_PREFIX = Regex("""^\s*(?:[-•*·]|\d{1,2}\s*[.、)])\s*""")
    private val MARKDOWN = Regex("""[*_`]{1,3}""")

    /** 模型偶尔会在正文前面也贴一个已读标记，别让它出现在气泡里 */
    private val READ_TAG = Regex("""^\s*[\[（(]\s*已读\s*[\]）)]\s*""")
    private const val SENTENCE_END = "。！？!?…"

    /**
     * 被标签包住的思考块。
     *
     * 主流推理模型走 `reasoning_content` 字段，那一层已经和正文分开了（见
     * `OpenAiCompatBackend`）。但中转站与部分模型会把思考直接写进 `content`，
     * 用 `<thought>` / `<thinking>` 这类标签隔开。提示词已经明令禁止，
     * 这里再兜一层 —— 标签是**明确的边界**，剥离它不会误伤任何一句正常台词，
     * 而放任它过去，用户看到的就是"她的日记本被贴在了聊天框里"。
     */
    private val THINK_BLOCK = Regex(
        "(?is)<\\s*(?:thought|thinking|reasoning|analysis|scratchpad|思考)\\s*>.*?<\\s*/\\s*(?:thought|thinking|reasoning|analysis|scratchpad|思考)\\s*>"
    )

    /** 只有开标签、没有闭标签（多在生成被 token 上限截断时出现）：从这里到结尾全丢 */
    private val THINK_OPEN = Regex(
        "(?is)<\\s*(?:thought|thinking|reasoning|analysis|scratchpad|思考)\\s*>.*$"
    )

    /** 没有标签、但整行是"思考：…"这种标注 —— 那是给自己看的，不是台词 */
    private val THINK_LINE = Regex("""(?im)^[ \t]*(?:思考|心里想|内心|心理活动)\s*[:：].*$""")

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        // 她选择不说话：一个气泡都不发，界面只留已读回执
        if (FishRhythm.isSilent(text)) return emptyList()
        val body = stripThinking(text)
        if (body.isBlank() || FishRhythm.isSilent(body)) return emptyList()
        val out = mutableListOf<String>()
        body.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { raw ->
            val line = clean(raw)
            if (line.isEmpty()) return@forEach
            if (line.length <= SOFT_LIMIT) out += line else out += cutLong(line)
        }
        return merge(out)
    }

    /**
     * 去掉正文里混进来的思考。
     *
     * 公开出来是因为通知预览也走这里（`FishProactive.preview`）——
     * 屏幕上不显示思考、通知里却显示，等于没修。
     */
    fun stripThinking(text: String): String =
        text.replace(THINK_BLOCK, "")
            .replace(THINK_OPEN, "")
            .replace(THINK_LINE, "")
            .trim()

    /** 去掉列表符号、Markdown 标记与首尾空白；空行直接丢掉 */
    private fun clean(raw: String): String =
        raw.replace(READ_TAG, "")
            .replace(LIST_PREFIX, "")
            .replace(MARKDOWN, "")
            .trim()

    /** 长句按句末标点切；没有标点就按长度硬切 */
    private fun cutLong(line: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in line) {
            sb.append(ch)
            val atSentenceEnd = ch in SENTENCE_END && sb.length >= 8
            val tooLong = sb.length >= SOFT_LIMIT
            if (atSentenceEnd || tooLong) {
                val piece = sb.toString().trim()
                if (piece.isNotEmpty()) parts += piece
                sb.clear()
            }
        }
        val rest = sb.toString().trim()
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    /**
     * 超过 [MAX] 条时把尾部并成一条。
     *
     * 不直接丢弃：模型多说的那几句往往正是重点（比如最后才回答用户的问题）。
     */
    private fun merge(items: List<String>): List<String> {
        if (items.size <= MAX) return items
        val head = items.take(MAX - 1).toMutableList()
        var tail = items.drop(MAX - 1).joinToString(" ")
        if (tail.length > TAIL_LIMIT) tail = tail.take(TAIL_LIMIT).trimEnd() + "…"
        head += tail
        return head
    }
}
