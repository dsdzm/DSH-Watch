package com.dshwatch.domain.agent

import com.dshwatch.core.Logx

/**
 * 工具结果的「落盘 + 摘要」。
 *
 * ## 要解决的问题
 *
 * 单个工具结果回灌给模型时会被硬截断（保留头尾、砍掉中间）。对 `read_file` /
 * `grep` / 命令输出这类内容，中间往往正是模型要找的东西 —— 于是它只能再读一遍，
 * 再被截断，形成「读了两次还是没看到」的死循环，上下文还被这两次大结果占满。
 *
 * ## 做法
 *
 * 超过 [THRESHOLD] 字的结果：
 *  1. **全文原样写入工作空间**（`notes/tool-output/<工具>-<内容hash>.txt`）；
 *  2. 回灌给模型的文本只保留「头 + 尾 + 落盘路径 + 怎么查中间」。
 *
 * 这样单次工具调用的上下文成本从「随输出无限增长」变成**常数级**（约 3KB），
 * 而信息没有丢：模型需要中间内容时，用 `grep pattern=...` 或
 * `read_file offset=...` 精确取，只把真正相关的那一段带回来。
 *
 * 文件名用内容 hash 而不是时间戳：同一份内容反复读只会有**一个**文件，
 * 不会把工作空间堆满重复副本。
 */
object ToolResultCompactor {

    /** 超过这个长度才落盘。与原来的回灌上限一致，行为可预期。 */
    const val THRESHOLD = 4_000

    /** 摘要里保留的头部字符数：开头通常是「这是什么」（文件头、命令回显、JSON 前几个字段） */
    const val HEAD_CHARS = 2_000

    /** 摘要里保留的尾部字符数：报错与结论在末尾 */
    const val TAIL_CHARS = 1_000

    data class Outcome(
        /** 回灌给模型的文本 */
        val text: String,
        /** 全文在工作空间里的相对路径；没有落盘时为 null */
        val path: String? = null,
        /** 原始内容的字符数 */
        val originalChars: Int = 0,
    )

    /**
     * 按需落盘。短结果原样返回，不产生任何文件。
     *
     * 落盘失败（工作空间写满、路径非法）时退回「头尾截断」并在文本里说明，
     * **不能因为存不下就让这次工具调用失败** —— 结果本身是有效的。
     */
    suspend fun compact(toolName: String, content: String, ctx: ToolContext): Outcome {
        if (content.length <= THRESHOLD) {
            return Outcome(text = content, originalChars = content.length)
        }

        val rel = "notes/tool-output/${safeName(toolName)}-${hashOf(content)}.txt"
        val global = ctx.scope(rel)
        val saved = global != null && runCatching { ctx.workspace.write(global, content) }.getOrNull() != null

        if (!saved) {
            Logx.w(TAG, "工具结果落盘失败（${content.length} 字），退回头尾截断：$rel")
            return Outcome(
                text = digest(content, relPath = null),
                path = null,
                originalChars = content.length,
            )
        }
        return Outcome(text = digest(content, rel), path = rel, originalChars = content.length)
    }

    /**
     * 生成摘要文本。纯函数，便于单测钉住格式。
     *
     * [relPath] 为 null 表示落盘失败，此时文案要说明「内容已丢失一部分」，
     * 而不是给模型一个打不开的路径。
     */
    fun digest(content: String, relPath: String?): String {
        if (content.length <= HEAD_CHARS + TAIL_CHARS + 200) return content

        val head = content.take(HEAD_CHARS)
        val tail = content.takeLast(TAIL_CHARS)
        val omitted = content.length - head.length - tail.length

        return buildString {
            append(head)
            append("\n\n…〔中间 ").append(omitted).append(" 字已省略〕…\n\n")
            if (relPath != null) {
                append("**全文已保存到 `").append(relPath).append("`（共 ")
                    .append(content.length).append(" 字）。**\n")
                append("要查中间的内容，别重新执行原工具：\n")
                append("- 按关键词找：`grep pattern=<关键词> path=").append(relPath).append("`\n")
                append("- 按位置读：`read_file path=").append(relPath)
                    .append(" offset=").append(HEAD_CHARS).append(" max_chars=4000`\n")
            } else {
                append("（内容过长，落盘失败，只能保留头尾；需要中间部分请缩小查询范围后重试）\n")
            }
            append("\n").append(tail)
        }
    }

    private fun safeName(toolName: String): String {
        val cleaned = toolName.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        return cleaned.joinToString("").take(40).ifBlank { "tool" }
    }

    /** 稳定的内容指纹：同一份输出只落一个文件 */
    private fun hashOf(content: String): String {
        val h = content.hashCode().toLong() and 0xFFFFFFFFL
        return h.toString(16).padStart(8, '0')
    }

    private const val TAG = "ToolResultCompactor"
}
