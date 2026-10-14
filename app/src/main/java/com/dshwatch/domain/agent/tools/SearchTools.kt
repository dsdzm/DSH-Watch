package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import kotlinx.serialization.json.JsonObject

/**
 * 在工作目录里「找东西」的两个工具。
 *
 * 为什么拆成两个而不是一个带开关的工具：
 * 「我知道文件叫什么，但忘了放哪」和「我知道里面写了什么，但忘了在哪个文件」
 * 是两种完全不同的检索意图，返回的也是两种不同的东西（文件列表 vs 命中行）。
 * 合成一个、靠参数形态去猜，模型经常会用错边 —— 而用错边的代价是一次白跑的往返。
 */
object SearchTools {

    fun all(): List<AgentTool> = listOf(GlobTool, GrepTool)

    // ------------------------------------------------------------------

    object GlobTool : AgentTool {
        override val name = "glob"
        override val description =
            "按**文件名模式**查找文件，返回匹配的相对路径。" +
                "支持 *（不跨目录）、**（跨目录）、?（单字符），例如 **/*.js、src/*.css、*.html。" +
                "不区分大小写。要按文件**内容**找请用 grep。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf(
                "pattern" to Schema.str("通配模式，例如 **/*.js 或 src/*.html"),
                "limit" to Schema.int("最多返回多少条，默认 50", 1, 200),
            ),
            required = listOf("pattern"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val pattern = args.strOf("pattern") ?: return ToolResult.fail("缺少 pattern 参数")
            val limit = (args.intOf("limit") ?: 50).coerceIn(1, 200)
            val regex = runCatching { globToRegex(pattern) }
                .getOrElse { return ToolResult.fail("模式不合法：" + it.message) }

            val files = ctx.workspace.listFilesRecursive(ctx.scope("") ?: "")
            val hits = files.filter { regex.matches(ctx.unscope(it.path)) }
            if (hits.isEmpty()) {
                return ToolResult.ok(
                    "没有文件匹配「" + pattern + "」。可以先用 list_files 看看当前工作目录里有什么。"
                )
            }
            val head = "匹配「" + pattern + "」的有 " + hits.size + " 个" +
                (if (hits.size > limit) "（只列出前 " + limit + " 个）" else "") + "：\n"
            val body = buildString {
                hits.take(limit).forEach {
                    append("- ").append(ctx.unscope(it.path))
                        .append("  (").append(it.size).append(" B)\n")
                }
            }
            return ToolResult.ok(head + body)
        }

        override fun summarize(args: JsonObject) = "模式 " + (args.strOf("pattern") ?: "—")

        /** 供 [GrepTool] 的 include 参数复用 */
        internal fun globToRegexPublic(pattern: String): Regex = globToRegex(pattern)

        /**
         * glob → 正则。
         *
         * `*` 与 `**` 必须区分：前者不能跨过 `/`，否则 `*.js` 会把
         * `deep/nested/app.js` 也匹配上，与绝大多数人的直觉不符。
         */
        private fun globToRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            val special = ".[]{}()+-^$|"
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                if (c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*') {
                    sb.append(".*")
                    i++
                    // `**/` 里的斜杠也要一起吞掉，否则 `**/*.js` 匹配不到根目录下的 a.js
                    if (i + 1 < pattern.length && pattern[i + 1] == '/') i++
                } else if (c == '*') {
                    sb.append("[^/]*")
                } else if (c == '?') {
                    sb.append("[^/]")
                } else if (special.indexOf(c) >= 0) {
                    sb.append('\\').append(c)
                } else {
                    sb.append(c)
                }
                i++
            }
            sb.append('$')
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }

    // ------------------------------------------------------------------

    /**
     * 按内容搜索（正则）。
     *
     * 支持正则而不是只做子串匹配 —— 模型找东西时经常需要
     * 「以 fun 开头的行」「TODO 后面跟任意内容」这类模式。
     *
     * 正则写错时**自动退回字面量匹配**并在结果里说明，而不是直接报错罢手：
     * 找一段普通文本本来就是常见需求（里面的括号、点号都会让正则编译失败），
     * 不该因为一个错括号就整个失败。
     */
    object GrepTool : AgentTool {
        override val name = "grep"
        override val description =
            "按内容搜索工作目录里的文件，返回「文件:行号: 该行内容」。支持正则表达式。" +
                "用 include 限定文件（如 *.js、*.py）能明显加快速度。" +
                "已知文件在哪、只是想看内容用 read_file；找文件用 glob。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf(
                "pattern" to Schema.str(
                    "要搜索的内容，支持正则，例如 TODO、def main、val\\s+\\w+"
                ),
                "include" to Schema.str("只搜匹配该通配模式的文件，例如 *.js、*.py、src/*；留空表示全部"),
                "ignore_case" to Schema.bool("是否忽略大小写，默认 true"),
                "max_results" to Schema.int("最多返回多少条匹配，默认 40", 1, 100),
            ),
            required = listOf("pattern"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val pattern = args.strOf("pattern") ?: return ToolResult.fail("缺少 pattern 参数")
            val include = args.strOf("include")
            val ignoreCase = args.boolOf("ignore_case", true)
            val limit = (args.intOf("max_results") ?: 40).coerceIn(1, 100)

            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            var usedRegex = true
            val regex = runCatching { Regex(pattern, options) }.getOrElse {
                usedRegex = false
                Regex(Regex.escape(pattern), options)
            }
            val includeRegex = include?.let {
                runCatching { GlobTool.globToRegexPublic(it) }.getOrNull()
            }

            var files = ctx.workspace.listFilesRecursive(ctx.scope("") ?: "")
            if (includeRegex != null) {
                files = files.filter { includeRegex.matches(ctx.unscope(it.path)) }
            }

            val out = ArrayList<String>()
            var scanned = 0
            for (file in files) {
                if (out.size >= limit) break
                if (file.size > 512 * 1024) continue
                scanned++
                val text = ctx.workspace.read(file.path, maxChars = 200_000) ?: continue
                if (text.contains('\u0000')) continue // 二进制
                text.lineSequence().forEachIndexed { index, line ->
                    if (out.size < limit && regex.containsMatchIn(line)) {
                        out += ctx.unscope(file.path) + ":" + (index + 1) + ": " + line.trim().take(160)
                    }
                }
            }

            val note = if (usedRegex) "" else "（你给的正则无法解析，已按纯文本搜索：「" + pattern + "」）\n"
            if (out.isEmpty()) {
                return ToolResult.ok(
                    note + "在 " + scanned + " 个文件里没有匹配。「找不到」也是一种结论 —— " +
                        "可以换个关键词，或先用 glob / list_files 确认文件名。"
                )
            }
            val head = note + "找到 " + out.size + " 处匹配" +
                (if (out.size >= limit) "（已达上限，可能还有更多）" else "") + "：\n"
            return ToolResult.ok(head + out.joinToString("\n"))
        }

        override fun summarize(args: JsonObject) = "搜 " + (args.strOf("pattern") ?: "—")
    }
}
