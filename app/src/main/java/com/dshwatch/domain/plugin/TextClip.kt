package com.dshwatch.domain.plugin

/**
 * 文本裁剪的共用小工具。
 *
 * 为什么单独抽出来而不是各插件各写一份：这里有两个必须做对的细节，
 * 重复实现迟早会漏掉其中一个 ——
 *
 * 1. **按码点切，不按 char 切**。Kotlin 的 String 是 UTF-16，一个 emoji 占两个 char。
 *    在代理对中间切断会得到半个字符，序列化进 JSON 时可能直接变成替换符，
 *    模型看到的是一堆乱码 —— 而它不会说"你给我的数据坏了"，只会开始瞎猜。
 * 2. **保尾**。报错永远在最后几行，只保留开头等于把最有用的信息扔掉。
 *    这个项目在 `run_script` 的输出处理上已经踩过一次，见 `exec/OutputSink`。
 */
internal object TextClip {

    /**
     * 头 + 尾保留，中间标注省略量。
     *
     * @param headRatio 头部占比。默认 0.55 —— 开头通常是"这是什么"（命令回显、
     *                  文件头、JSON 的前几个字段），信息密度比尾部略高；
     *                  但尾部要留够 45%，因为报错和结论在那儿。
     */
    fun headTail(text: String, max: Int, headRatio: Double = 0.55): String {
        if (text.length <= max) return text
        val headBudget = (max * headRatio).toInt().coerceAtLeast(1)
        val tailBudget = (max - headBudget).coerceAtLeast(1)
        val headEnd = safeEnd(text, headBudget)
        val tailStart = safeStart(text, text.length - tailBudget)
        if (tailStart <= headEnd) return text.take(safeEnd(text, max))
        val head = text.substring(0, headEnd)
        val tail = text.substring(tailStart)
        val dropped = text.length - head.length - tail.length
        return head + "\n…（中间省略 $dropped 个字符）…\n" + tail
    }

    /**
     * 行列表折叠：保留前 [headLines] 行 + 后 [tailLines] 行。
     *
     * 只对"一行一条记录"的文本有意义（`grep`、`ls`、`read_file` 的行号输出）。
     * 对散文/JSON 做这个只会切碎语义，所以调用方必须先判断形态。
     */
    fun foldLines(text: String, headLines: Int, tailLines: Int, max: Int): String {
        val lines = text.lines()
        if (lines.size <= headLines + tailLines) return headTail(text, max)
        val head = lines.subList(0, headLines)
        val tail = lines.subList(lines.size - tailLines, lines.size)
        val dropped = lines.size - head.size - tail.size
        val folded = buildString {
            head.forEach { append(it).append('\n') }
            append("…（中间省略 $dropped 行）…\n")
            tail.forEachIndexed { index, line ->
                append(line)
                if (index != tail.lastIndex) append('\n')
            }
        }
        // 折叠后仍可能超限（单行很长时）
        return if (folded.length <= max) folded else headTail(folded, max)
    }

    /** 切点若落在代理对中间，往回收一格 */
    private fun safeEnd(text: String, index: Int): Int {
        if (index <= 0) return 0
        if (index >= text.length) return text.length
        return if (Character.isLowSurrogate(text[index]) && Character.isHighSurrogate(text[index - 1])) {
            index - 1
        } else {
            index
        }
    }

    /** 起点若落在代理对中间，往后挪一格 */
    private fun safeStart(text: String, index: Int): Int {
        if (index <= 0) return 0
        if (index >= text.length) return text.length
        return if (Character.isLowSurrogate(text[index]) && Character.isHighSurrogate(text[index - 1])) {
            index + 1
        } else {
            index
        }
    }
}
