package com.dshwatch.domain.agent

import com.dshwatch.core.Jsonx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 工具参数解析。
 *
 * ## 为什么不能直接 `decodeFromString`
 *
 * 模型给的 `arguments` 字符串经常不是合法 JSON，实际遇到的两类：
 *
 * 1. **字符串里有裸控制字符** —— 写多行 HTML / 代码时，模型直接把真换行塞进
 *    `"content": "....."` 里。这在 JSON 规范里非法，但意图完全明确，转义一下就好。
 *
 * 2. **被 token 上限截断** —— `{"path": "index.html", "content": "<!DOCTYPE html>...--ok:`
 *    引号和右括号都没了。
 *
 * 旧实现遇到这两种情况都静默 `JsonObject(emptyMap())`，
 * 于是工具报「**缺少 path 参数**」—— 把「参数被截断」这个真问题彻底盖住了，
 * 用户和模型都在猜为什么 path 会丢。
 *
 * 现在分三种结果：[Result.Ok] 能跑、[Result.Truncated] 能认出是截断（不执行，给出可操作的指引）、
 * [Result.Broken] 其它格式错误。
 */
object ToolArgs {

    sealed interface Result {
        /** 解析成功，参数完整，可以执行 */
        data class Ok(val args: JsonObject) : Result

        /** 参数被截断（通常是单次输出超过 max_tokens）。[recovered] 是尽力恢复出的部分 */
        data class Truncated(val recovered: JsonObject, val preview: String) : Result

        /** 结构完整但内容非法，救不回来 */
        data class Broken(val preview: String) : Result
    }

    private const val MAX_PREVIEW = 160

    fun parse(raw: String, json: Json = Jsonx.lenient): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Ok(JsonObject(emptyMap()))

        decode(text, json)?.let { return Result.Ok(it) }

        // ① 裸控制字符：这是「看起来完整其实非法」最常见的原因，救了就能正常执行
        val cleaned = escapeRawControlChars(text)
        if (cleaned != text) {
            decode(cleaned, json)?.let { return Result.Ok(it) }
        }

        // ② 截断：闭合字符串与括号后能解析出来，说明确实是「没写完」而不是「写错了」
        closeTruncated(cleaned)?.let { closed ->
            decode(closed, json)?.let { return Result.Truncated(it, preview(text)) }
        }

        return Result.Broken(preview(text))
    }

    /** 给用户和模型看的说明。内容必须**可执行** —— 只说「参数错误」等于没说 */
    fun explain(result: Result): String = when (result) {
        is Result.Ok -> ""

        is Result.Truncated -> buildString {
            append("工具参数被截断：模型还没写完就断了（单次输出超过了「最大输出 token」）。\n")
            append("建议换一种写法：先用 write_file 写入文件骨架，再用 append_file 分段追加内容；")
            append("或者在「供应商」设置里把最大输出调大。\n\n")
            append("收到的参数片段：\n").append(result.preview)
        }

        is Result.Broken -> buildString {
            append("工具参数不是合法的 JSON，无法执行。常见原因是内容里的引号或换行没转义。\n\n")
            append("收到的参数片段：\n").append(result.preview)
        }
    }

    private fun preview(s: String) = if (s.length <= MAX_PREVIEW) s else s.take(MAX_PREVIEW) + "…"

    private fun decode(text: String, json: Json): JsonObject? =
        runCatching { json.decodeFromString(JsonObject.serializer(), text) }.getOrNull()

    /**
     * 转义 JSON 字符串**内部**的裸控制字符。
     *
     * 只处理引号内的内容，并正确跳过 `\"` 这类转义 ——
     * 盲目全局替换会把本来就合法的 `\n` 变成 `\\n`，反而弄坏数据。
     */
    private fun escapeRawControlChars(s: String): String {
        val out = StringBuilder(s.length + 16)
        var inString = false
        var escaped = false

        for (ch in s) {
            if (inString) {
                when {
                    escaped -> {
                        out.append(ch)
                        escaped = false
                    }

                    ch == '\\' -> {
                        out.append(ch)
                        escaped = true
                    }

                    ch == '"' -> {
                        out.append(ch)
                        inString = false
                    }

                    ch == '\n' -> out.append("\\n")
                    ch == '\r' -> out.append("\\r")
                    ch == '\t' -> out.append("\\t")
                    ch.code < 0x20 -> out.append("\\u").append(String.format("%04x", ch.code))
                    else -> out.append(ch)
                }
            } else {
                when {
                    ch == '"' -> {
                        out.append(ch)
                        inString = true
                    }
                    // 结构区里的空白换成空格，不影响语义
                    ch == '\n' || ch == '\r' || ch == '\t' -> out.append(' ')
                    ch.code < 0x20 -> Unit
                    else -> out.append(ch)
                }
            }
        }
        return out.toString()
    }

    /**
     * 尝试补全被截断的 JSON。
     *
     * @return 补全后的文本；如果结构本来就没截断（或括号已经错乱），返回 null ——
     *   宁可判 [Result.Broken] 也不要造出一份看似合法、实则错乱的参数。
     */
    private fun closeTruncated(s: String): String? {
        val out = StringBuilder(s)
        var inString = false
        var escaped = false
        val stack = ArrayDeque<Char>()

        for (ch in s) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> stack.addLast('{')
                    '[' -> stack.addLast('[')
                    '}' -> if (stack.lastOrNull() == '{') stack.removeLast() else return null
                    ']' -> if (stack.lastOrNull() == '[') stack.removeLast() else return null
                }
            }
        }

        if (stack.isEmpty() && !inString) return null // 没截断，是别的问题
        if (inString) out.append('"')
        while (stack.isNotEmpty()) {
            out.append(if (stack.removeLast() == '{') '}' else ']')
        }
        return out.toString()
    }
}
