package com.dshwatch.domain.plugin

/**
 * `dsh-token-saver` —— 工具结果的**无损**裁剪。
 *
 * 「无损」指不改变信息的结构，只删掉重复/冗余的中间段，并且明确标注删了多少。
 * 对比 `dsh-plugin-save-token`：那个会改写编码（JSON→TOON），是有损感的。
 *
 * ## 为什么需要它
 *
 * 工具结果进上下文前的唯一保护是 `ChatEngine` 的 4000 字符硬截断，而且只留头。
 * 一次 `grep` 命中 200 行、一次 `http_fetch` 抓到整页正文，都会把 4000 字符
 * 原样塞进上下文 —— 按本项目的估算器约 1000+ token，而模型真正要的
 * 往往只是其中十几行。
 *
 * ## 为什么 read_file 例外
 *
 * 这是唯一一个**不该裁**的工具。模型调 `read_file` 是为了读那段内容本身，
 * 把中间折叠掉等于让它白读一次，它还得再读一遍（更贵）。
 * 省 token 的正确解法是让它用 offset/limit 只读需要的那段 —— 那是提示词该管的事，
 * 不是在这里偷偷删内容。
 */
object TokenSaverPlugin : ToolResultPlugin {

    override val id: String = "dsh-token-saver"

    override val defaultEnabled: Boolean = true

    /** 交给模型的单条工具结果上限（字符） */
    const val TARGET_CHARS = 1_800

    /** 折叠时保留的首尾行数。尾部多留 —— 报错和结论都在最后 */
    private const val HEAD_LINES = 8
    private const val TAIL_LINES = 16

    /** 判定"一行一条记录"的门槛：太短不算列表，太长的行说明是散文/代码 */
    private const val LINE_LIST_MIN_LINES = 20
    private const val LINE_AVG_MAX = 140

    private val PASSTHROUGH = setOf("read_file")

    /** 这些工具的输出天然是"一行一条"，折叠比头尾裁更符合语义 */
    private val LINE_LIST_TOOLS = setOf("grep", "glob", "list_files", "web_search")

    override suspend fun transform(input: ToolResultInput): String {
        val text = input.content
        if (text.length <= TARGET_CHARS) return text
        if (input.toolName in PASSTHROUGH) return text

        val foldable = input.toolName in LINE_LIST_TOOLS || looksLikeLineList(text)
        return if (foldable) {
            TextClip.foldLines(text, HEAD_LINES, TAIL_LINES, TARGET_CHARS)
        } else {
            TextClip.headTail(text, TARGET_CHARS)
        }
    }

    /**
     * 形态判断：行数够多、且平均行长够短。
     *
     * 两个条件都要，缺一不可 —— 只看行数会把代码也判成列表（行数很多）。
     * 只看平均行长也不行：**格式化的 JSON 每行也很短**（`  "key": "value",`），
     * 而折叠它会把结构切成两半，模型拿到的是一份语法上不成立的 JSON。
     * 所以结构化文本（以 `{` / `[` 开头）直接排除。
     */
    private fun looksLikeLineList(text: String): Boolean {
        val head = text.trimStart()
        if (head.startsWith("{") || head.startsWith("[")) return false

        var count = 0
        var total = 0
        for (line in text.lineSequence()) {
            count++
            total += line.length
            if (count >= 400) break
        }
        if (count < LINE_LIST_MIN_LINES) return false
        return total / count <= LINE_AVG_MAX
    }
}
