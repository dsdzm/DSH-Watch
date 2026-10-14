package com.dshwatch.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

data class CodeColors(
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color,
)

/**
 * 极简代码着色。
 *
 * 只做「一眼能分清结构」的着色：注释 / 字符串 / 关键字 / 数字 / 函数名。
 * 不做语法树解析——手表上没必要，而逐字符扫描是 O(n) 且没有额外分配。
 * 低端设备会在 MarkdownText 里直接跳过本类（见 PerfProfile.enableCodeHighlight）。
 */
object CodeHighlighter {

    private val commonKeywords = setOf(
        "abstract", "as", "assert", "async", "await", "base", "bool", "boolean", "break",
        "byte", "case", "catch", "char", "class", "const", "constructor", "continue",
        "data", "def", "default", "delete", "do", "double", "elif", "else", "enum",
        "except", "export", "extends", "false", "final", "finally", "float", "fn",
        "for", "from", "fun", "function", "global", "goto", "if", "implements", "import",
        "in", "instanceof", "int", "interface", "is", "lambda", "let", "long", "match",
        "namespace", "new", "nil", "none", "null", "object", "override", "package",
        "private", "protected", "public", "raise", "readonly", "record", "return",
        "sealed", "short", "static", "struct", "super", "switch", "synchronized",
        "this", "throw", "throws", "trait", "true", "try", "type", "typeof", "unsigned",
        "use", "using", "val", "var", "void", "volatile", "when", "while", "with",
        "yield", "self", "dict", "list", "set", "str", "print", "echo", "local",
        "then", "fi", "done", "esac", "function_end", "impl", "where", "mod", "pub",
    )

    private val hashCommentLanguages = setOf("py", "python", "sh", "bash", "zsh", "yaml", "yml", "toml", "rb", "ruby", "perl", "r", "makefile", "dockerfile", "ini", "conf", "properties")

    fun highlight(code: String, language: String, colors: CodeColors): AnnotatedString {
        val useHashComment = language.lowercase() in hashCommentLanguages
        return buildAnnotatedString {
            var i = 0
            val n = code.length
            while (i < n) {
                val c = code[i]

                // 行注释
                val lineCommentStart = when {
                    !useHashComment && c == '/' && i + 1 < n && code[i + 1] == '/' -> 2
                    useHashComment && c == '#' -> 1
                    !useHashComment && c == '-' && i + 1 < n && code[i + 1] == '-' -> 2
                    else -> 0
                }
                if (lineCommentStart > 0) {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    withStyle(SpanStyle(color = colors.comment)) { append(code.substring(i, end)) }
                    i = end
                    continue
                }

                // 块注释
                if (!useHashComment && c == '/' && i + 1 < n && code[i + 1] == '*') {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    withStyle(SpanStyle(color = colors.comment)) { append(code.substring(i, end)) }
                    i = end
                    continue
                }

                // 字符串
                if (c == '"' || c == '\'' || c == '`') {
                    var j = i + 1
                    while (j < n) {
                        if (code[j] == '\\' && j + 1 < n) {
                            j += 2
                            continue
                        }
                        if (code[j] == c || code[j] == '\n') {
                            if (code[j] == c) j++
                            break
                        }
                        j++
                    }
                    withStyle(SpanStyle(color = colors.string)) { append(code.substring(i, j)) }
                    i = j
                    continue
                }

                // 数字
                if (c.isDigit() && (i == 0 || !isIdentChar(code[i - 1]))) {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    withStyle(SpanStyle(color = colors.number)) { append(code.substring(i, j)) }
                    i = j
                    continue
                }

                // 标识符 / 关键字 / 函数名
                if (isIdentStart(c)) {
                    var j = i
                    while (j < n && isIdentChar(code[j])) j++
                    val word = code.substring(i, j)
                    val lower = word.lowercase()
                    when {
                        lower in commonKeywords || lower in LANGUAGE_EXTRA_KEYWORDS[language.lowercase()].orEmpty() ->
                            withStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium)) {
                                append(word)
                            }

                        j < n && code[j] == '(' ->
                            withStyle(SpanStyle(color = colors.function)) { append(word) }

                        else -> append(word)
                    }
                    i = j
                    continue
                }

                append(c)
                i++
            }
        }
    }

    private val LANGUAGE_EXTRA_KEYWORDS: Map<String, Set<String>> = mapOf(
        "kt" to setOf("suspend", "companion", "object", "internal", "inline", "reified", "crossinline", "noinline", "init", "by", "lateinit", "vararg", "operator", "infix", "annotation"),
        "kotlin" to setOf("suspend", "companion", "object", "internal", "inline", "reified", "crossinline", "noinline", "init", "by", "lateinit", "vararg", "operator", "infix", "annotation"),
        "swift" to setOf("guard", "defer", "mutating", "weak", "unowned", "lazy", "some", "any", "protocol", "extension"),
        "go" to setOf("chan", "defer", "goroutine", "map", "range", "select", "interface"),
        "rust" to setOf("mut", "ref", "unsafe", "dyn", "crate", "loop", "move", "as"),
        "java" to setOf("synchronized", "transient", "native", "strictfp", "instanceof"),
        "ts" to setOf("type", "interface", "enum", "namespace", "declare", "readonly", "keyof", "infer", "satisfies"),
        "typescript" to setOf("type", "interface", "enum", "namespace", "declare", "readonly", "keyof", "infer", "satisfies"),
        "js" to setOf("var", "let", "const", "function", "await", "async"),
        "javascript" to setOf("var", "let", "const", "function", "await", "async"),
        "sql" to setOf("select", "from", "where", "join", "group", "order", "insert", "update", "delete", "create", "table", "index", "primary", "key", "foreign", "references"),
        "json" to emptySet(),
        "xml" to emptySet(),
        "html" to emptySet(),
    )

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
