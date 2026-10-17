package com.dshwatch.domain.agent.build

enum class Severity { ERROR, WARNING }

data class Issue(
    val path: String,
    val line: Int,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    fun render(): String = "[${if (severity == Severity.ERROR) "错误" else "警告"}] $path:$line $message"
}

/**
 * 静态检查器 —— 「编译」这一步在手表上的等价物。
 *
 * 手表上没有 Kotlin/Java 编译器，能做的、也是最有价值的是：
 * 把语法/结构错误在**打包前**找出来，而不是等用户点开预览看到一片空白。
 *
 * 各类文件的检查强度是刻意区分的：
 *  - JSON：真解析（kotlinx.serialization），报错定位到行列 —— 可以完全信任；
 *  - XML/HTML：标签配对扫描（自己写，不用 DocumentBuilder，顺便避免 XXE）；
 *  - CSS：注释/字符串感知的括号配对；
 *  - JS：**未闭合的字符串/注释判为错误**（高置信），括号配对只判警告
 *        —— 因为没有真正的解析器，正则字面量里的括号会造成误报。
 *
 * 不装懂：JS 括号只报警告这件事写进了给模型的说明里，避免它反复"修"一个其实没问题的文件。
 */
object SourceValidator {

    private val htmlVoidElements = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    /** HTML 里允许省略闭合标签的元素，未闭合只算警告 */
    private val htmlOptionalEnd = setOf(
        "html", "head", "body", "p", "li", "dt", "dd", "tr", "td", "th",
        "thead", "tbody", "tfoot", "option", "colgroup", "rt", "rp",
    )

    private val rawTextElements = setOf("script", "style", "textarea", "title")

    private val scriptExtensions = setOf("js", "mjs", "cjs", "ts", "tsx", "jsx")

    /** 花括号家族：语法差异很大，但「括号配对 + 字符串闭合」这一层是共通的 */
    private val braceExtensions = setOf(
        "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cxx", "hh",
        "cs", "go", "rs", "swift", "scala", "dart", "php", "m", "mm",
    )

    private val pythonExtensions = setOf("py", "pyw", "pyi")
    private val shellExtensions = setOf("sh", "bash", "zsh", "ksh")
    private val yamlExtensions = setOf("yml", "yaml")

    fun isCheckable(path: String): Boolean {
        val ext = extension(path)
        return ext in setOf("json", "html", "htm", "xml", "svg", "css") ||
            ext in scriptExtensions || ext in braceExtensions ||
            ext in pythonExtensions || ext in shellExtensions || ext in yamlExtensions
    }

    /**
     * 按扩展名分发到具体的校验器。
     *
     * 多语言部分委托给 [LanguageValidators] —— 那些检查的目标只有一个：
     * **报了就一定是真的错**。因为读这份报告的是模型，它收到错误就会去改代码，
     * 误报会让它把正确的东西改坏（而且它没有编译器可以对照）。
     */
    fun validate(path: String, content: String): List<Issue> = when (val ext = extension(path)) {
        "json" -> validateJson(path, content)
        "html", "htm" -> validateMarkup(path, content, html = true)
        "xml", "svg" -> validateMarkup(path, content, html = false)
        "css" -> validateCss(path, content)
        in pythonExtensions -> LanguageValidators.validatePython(path, content)
        in shellExtensions -> LanguageValidators.validateShell(path, content)
        in yamlExtensions -> LanguageValidators.validateYaml(path, content)
        in braceExtensions -> LanguageValidators.validateBraces(path, content, ext)
        else -> if (ext in scriptExtensions) validateScript(path, content) else emptyList()
    }

    // ---------------- JSON ----------------

    private fun validateJson(path: String, src: String): List<Issue> {
        if (src.isBlank()) return listOf(Issue(path, 1, "文件为空"))
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(src)
            emptyList<Issue>()
        }.getOrElse { e ->
            val offset = OFFSET_REGEX.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val line = if (offset != null && offset in 0..src.length) lineAt(src, offset) else 1
            listOf(Issue(path, line, "JSON 解析失败：${cleanJsonMessage(e.message)}"))
        }
    }

    private fun cleanJsonMessage(raw: String?): String =
        raw.orEmpty().substringBefore(" at path").substringBefore(" at offset").trim().ifBlank { "语法错误" }

    private val OFFSET_REGEX = Regex("offset (\\d+)")

    // ---------------- HTML / XML ----------------

    private fun validateMarkup(path: String, src: String, html: Boolean): List<Issue> {
        val issues = ArrayList<Issue>()
        val stack = ArrayDeque<Pair<String, Int>>() // name -> line
        var i = 0
        var line = 1
        val n = src.length

        fun normalize(name: String) = if (html) name.lowercase() else name

        while (i < n) {
            val c = src[i]
            if (c == '\n') {
                line++
                i++
                continue
            }
            if (c != '<') {
                i++
                continue
            }

            // 注释
            if (src.startsWith("<!--", i)) {
                val end = src.indexOf("-->", i + 4)
                if (end < 0) {
                    issues += Issue(path, line, "注释没有闭合（缺少 -->）")
                    break
                }
                line += src.substring(i, end).count { it == '\n' }
                i = end + 3
                continue
            }
            // DOCTYPE / CDATA / 处理指令：跳过
            if (src.startsWith("<!", i) || src.startsWith("<?", i)) {
                val end = src.indexOf('>', i)
                if (end < 0) break
                i = end + 1
                continue
            }

            val end = src.indexOf('>', i)
            if (end < 0) {
                issues += Issue(path, line, "标签没有闭合（缺少 >）")
                break
            }
            var inner = src.substring(i + 1, end)
            val tagLine = line
            line += src.substring(i, end).count { it == '\n' }
            i = end + 1

            val closing = inner.startsWith("/")
            if (closing) inner = inner.substring(1)
            val selfClosing = inner.endsWith("/")
            if (selfClosing) inner = inner.dropLast(1)

            val name = inner.trimStart().takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' }
            if (name.isEmpty()) continue
            val key = normalize(name)

            if (closing) {
                if (stack.isEmpty()) {
                    issues += Issue(path, tagLine, "多余的结束标签 </$name>：没有对应的开始标签")
                    continue
                }
                val (openName, openLine) = stack.last()
                if (openName == key) {
                    stack.removeLast()
                } else if (stack.any { it.first == key }) {
                    // 中间还有没关的标签
                    while (stack.isNotEmpty() && stack.last().first != key) {
                        val (dangling, dLine) = stack.removeLast()
                        issues += Issue(
                            path,
                            dLine,
                            "<$dangling> 没有闭合（被 </$name> 提前终止）",
                            // 只有「允许省略闭合」的元素才降级成警告；
                            // 早期版本在这里一律按 html 降级，导致 <div> 没闭合也放过了。
                            if (html && dangling in htmlOptionalEnd) Severity.WARNING else Severity.ERROR,
                        )
                    }
                    if (stack.isNotEmpty()) stack.removeLast()
                } else {
                    issues += Issue(path, tagLine, "结束标签 </$name> 没有对应的开始标签（$openName 在第 $openLine 行打开）")
                }
                continue
            }

            if (html && key in htmlVoidElements) continue
            if (selfClosing) continue

            stack.addLast(key to tagLine)

            // 原始文本元素：<script>/<style> 里的 < 不是标签，要整体跳过
            if (key in rawTextElements) {
                val closeIdx = src.indexOf("</$name", i, ignoreCase = html)
                if (closeIdx < 0) {
                    issues += Issue(path, tagLine, "<$name> 没有闭合")
                    stack.removeLast()
                    break
                }
                val bodySegment = src.substring(i, closeIdx)
                line += bodySegment.count { it == '\n' }
                if (key == "script") {
                    issues += validateScript(path, bodySegment, startLine = tagLine + 1).map {
                        it.copy(path = path)
                    }
                } else if (key == "style") {
                    issues += validateCss(path, bodySegment, startLine = tagLine + 1)
                }
                i = closeIdx
            }
        }

        stack.forEach { (name, openLine) ->
            issues += Issue(
                path,
                openLine,
                "<$name> 没有闭合",
                if (html && name in htmlOptionalEnd) Severity.WARNING else Severity.ERROR,
            )
        }
        return issues
    }

    // ---------------- CSS ----------------

    fun validateCss(path: String, src: String, startLine: Int = 1): List<Issue> {
        val issues = ArrayList<Issue>()
        var depth = 0
        var line = startLine
        var i = 0
        var openLine = startLine

        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> { line++; i++ }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    if (end < 0) {
                        issues += Issue(path, line, "注释没有闭合（缺少 */）")
                        return issues
                    }
                    line += src.substring(i, end).count { it == '\n' }
                    i = end + 2
                }

                c == '"' || c == '\'' -> {
                    val end = skipString(src, i)
                    if (end < 0) {
                        issues += Issue(path, line, "字符串没有闭合")
                        return issues
                    }
                    line += src.substring(i, end).count { it == '\n' }
                    i = end
                }

                c == '{' -> { depth++; openLine = line; i++ }
                c == '}' -> {
                    depth--
                    if (depth < 0) {
                        issues += Issue(path, line, "多余的 }")
                        return issues
                    }
                    i++
                }

                else -> i++
            }
        }
        if (depth > 0) issues += Issue(path, openLine, "有 $depth 个 { 没有闭合")
        return issues
    }

    // ---------------- JavaScript ----------------

    fun validateScript(path: String, src: String, startLine: Int = 1): List<Issue> {
        val issues = ArrayList<Issue>()
        var line = startLine
        var i = 0
        var round = 0
        var square = 0
        var curly = 0

        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> { line++; i++ }

                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    val end = src.indexOf('\n', i)
                    i = if (end < 0) src.length else end
                }

                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    if (end < 0) {
                        issues += Issue(path, line, "块注释没有闭合（缺少 */）")
                        return issues
                    }
                    line += src.substring(i, end).count { it == '\n' }
                    i = end + 2
                }

                c == '"' || c == '\'' || c == '`' -> {
                    val end = skipString(src, i)
                    if (end < 0) {
                        issues += Issue(path, line, "字符串没有闭合（引号 $c）")
                        return issues
                    }
                    line += src.substring(i, end).count { it == '\n' }
                    i = end
                }

                c == '(' -> { round++; i++ }
                c == ')' -> { round--; i++ }
                c == '[' -> { square++; i++ }
                c == ']' -> { square--; i++ }
                c == '{' -> { curly++; i++ }
                c == '}' -> { curly--; i++ }
                else -> i++
            }
        }

        // 括号配对没有真正的解析器，正则字面量里的括号会造成误报，所以只报警告
        if (round != 0) {
            issues += Issue(path, startLine, "圆括号疑似不配对（差 $round 个）", Severity.WARNING)
        }
        if (square != 0) {
            issues += Issue(path, startLine, "方括号疑似不配对（差 $square 个）", Severity.WARNING)
        }
        if (curly != 0) {
            issues += Issue(path, startLine, "花括号疑似不配对（差 $curly 个）", Severity.WARNING)
        }
        return issues
    }

    // ---------------- 工具 ----------------

    /** 跳过一段字符串字面量，返回结束后的下标；未闭合返回 -1 */
    private fun skipString(src: String, start: Int): Int {
        val quote = src[start]
        var i = start + 1
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            if (quote != '`' && c == '\n') return -1 // 普通字符串不允许跨行
            i++
        }
        return -1
    }

    private fun extension(path: String): String =
        path.substringAfterLast('.', "").lowercase().trim()

    private fun lineAt(src: String, offset: Int): Int =
        1 + src.take(offset.coerceIn(0, src.length)).count { it == '\n' }

    /** 粗略判断是不是文本文件（二进制文件跳过检查） */
    fun looksBinary(content: String): Boolean =
        content.take(2000).any { it == '\u0000' }
}
