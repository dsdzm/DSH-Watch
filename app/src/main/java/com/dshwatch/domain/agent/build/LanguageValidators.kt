package com.dshwatch.domain.agent.build

/**
 * Python / Kotlin / Java / C / Shell / YAML 等语言的静态校验。
 *
 * ## 为什么只做「高置信度」检查
 * 手表上没有这些语言的编译器，这里做的是**词法层面的**检查：括号配对、字符串闭合、
 * Python 缩进与冒号、Shell 关键字配对。
 *
 * 括号配对、字符串闭合、Shell 关键字配对这些是**语法级**判断，报了基本就是真错；
 * 但「Python 漏冒号」是**启发式**的，没这么可靠 —— 真机上就出过一次事：
 * 生成器里跨行的 `if`（写成 `sum(1 for _ in range(n)` 换行接 `if _ % 2 == 0)`）
 * 被判成缺冒号，模型于是**改写了本来正确的代码**。
 * 所以那一条必须做得足够保守：**宁可漏报，不可误报**。
 *
 * 这一条约束比「多查几类问题」重要得多：校验器的读者是模型，它收到错误后会去改代码。
 * 如果这里误报，模型就会把本来正确的代码改坏，而且它自己无法判断谁对谁错
 * （它没有编译器可以对照）。所以像「未使用的变量」「可能的空指针」这类
 * 需要类型信息才能下结论的检查，一律不做。
 *
 * ## 实现方式
 * 所有检查都基于同一套**词法剥离**：先把注释、字符串、字符字面量从源码里挖空
 * （替换成等长空格，保持行列不变），再在剩下的「纯代码」上做括号计数。
 * 这样字符串里的 `<` `(` `{` 就不会被误算进去 —— 这是括号检查最常见的误报来源。
 */
object LanguageValidators {

    // ---------------- Python ----------------

    /**
     * Python 检查三件事：
     *  1. **缩进里混用 tab 与空格** —— Python 3 直接抛 TabError，而且肉眼很难看出来；
     *  2. **括号没配对**；
     *  3. **`if/for/def/class` 等之后漏了冒号** —— 这条是启发式的，下面单独说明它为什么必须保守。
     */
    fun validatePython(path: String, src: String): List<Issue> {
        val issues = ArrayList<Issue>()
        val scan = scanPython(src)

        issues += scan.bracketIssues.map { it.forPath(path) }

        // 缩进混用：只要文件里既有「以 tab 开头缩进的行」又有「以空格开头缩进的行」就报
        var sawTabIndent = false
        var sawSpaceIndent = false
        scan.lines.forEach { line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            if (indent.isEmpty() || indent.length == line.length) return@forEach
            if (indent.contains('\t')) sawTabIndent = true
            if (indent.contains(' ')) sawSpaceIndent = true
        }
        if (sawTabIndent && sawSpaceIndent) {
            val firstBad = scan.lines.indexOfFirst { l ->
                val ind = l.takeWhile { it == ' ' || it == '\t' }
                ind.isNotEmpty() && ind.length < l.length && ind.contains('\t')
            } + 1
            issues += Issue(
                path,
                firstBad.coerceAtLeast(1),
                "缩进里混用了制表符和空格。Python 3 会直接抛 TabError —— " +
                    "请统一成 4 个空格（这是绝大多数项目的约定）。",
            )
        }

        // 漏冒号。
        //
        // 这条是**启发式**的，必须足够保守 —— 误报会让模型去改本来正确的代码。
        // 真机上就出过一次：生成器里跨行的 `if`
        //
        //     total = sum(1 for _ in range(n)
        //                 if _ % 2 == 0)
        //
        // 括号恰恰是在 `if` 这一行闭合的，所以"看行末括号深度"会认为语句已结束，
        // 于是报"缺冒号"。真正该问的是：**这一行是不是续行**。
        val needsColon = Regex(
            "^\\s*(if|elif|else|for|while|def|class|try|except|finally|with|match|case)\\b.*"
        )
        scan.codeLines.forEachIndexed { index, code ->
            val trimmed = code.trimEnd()
            if (trimmed.isBlank()) return@forEachIndexed
            if (!needsColon.containsMatchIn(trimmed)) return@forEachIndexed
            // 续行：这一行只是表达式的一部分，不该以冒号收尾。三种情形都要放过：
            if (isContinuation(scan, index)) return@forEachIndexed
            // 本行以反斜杠续行 → 语句还没写完，冒号在下一行
            if (trimmed.endsWith("\\")) return@forEachIndexed
            // 本行结束时括号仍未闭合 → 同上（多行条件）
            if (depthAtLineEnd(scan, index) > 0) return@forEachIndexed
            // `else` 后面可能跟冒号也可能单独一行；统一要求以 : 结尾
            if (!trimmed.endsWith(":")) {
                val keyword = trimmed.trimStart().takeWhile { it.isLetter() }
                issues += Issue(
                    path,
                    index + 1,
                    "「" + keyword + "」语句这一行的末尾缺少冒号 `:`。" +
                        "（若这是多行表达式里的续行，请忽略本条）",
                )
            }
        }

        return issues
    }

    /**
     * 这一行是不是「续行」—— 即上一行的语句还没结束。
     *
     * 两个判据：上一行结束时括号还开着；或者上一行以反斜杠显式续行。
     */
    private fun isContinuation(scan: PythonScan, lineIndex: Int): Boolean {
        if (lineIndex <= 0) return false
        if (depthAtLineEnd(scan, lineIndex - 1) > 0) return true
        return scan.codeLines.getOrNull(lineIndex - 1).orEmpty().trimEnd().endsWith("\\")
    }

    /** 某一行的括号净深度（用累积深度判断语句是否结束） */
    private fun depthAtLineEnd(scan: PythonScan, lineIndex: Int): Int {
        var d = 0
        for (i in 0..lineIndex) {
            val code = scan.codeLines.getOrNull(i).orEmpty()
            d += code.count { it == '(' || it == '[' || it == '{' }
            d -= code.count { it == ')' || it == ']' || it == '}' }
        }
        return d
    }

    private data class PythonScan(
        val lines: List<String>,
        /** 与 lines 一一对应，字符串与注释被替换成空格 */
        val codeLines: List<String>,
        val bracketIssues: List<RawIssue>,
    )

    /**
     * Python 词法扫描。
     *
     * 需要处理三引号（会跨行）与 f-string 前缀 —— 用「逐字符状态机 + 跨行保持状态」实现，
     * 而不是逐行正则：三引号字符串里可以有无数个 `#` 和括号，逐行处理必然误报。
     */
    private fun scanPython(src: String): PythonScan {
        val lines = src.split("\n")
        val codeLines = ArrayList<String>(lines.size)
        val issues = ArrayList<RawIssue>()
        val stack = ArrayList<Pair<Char, Int>>() // 括号 + 行号

        // 三引号字符串的状态：跨行保持
        var inTriple: Char? = null
        var tripleStartLine = 0

        lines.forEachIndexed { lineIndex, raw ->
            val lineNo = lineIndex + 1
            val sb = StringBuilder(raw.length)
            var i = 0

            if (inTriple != null) {
                // 上一行留下的三引号还没结束，找本行的结束符
                val q = inTriple!!
                val end = raw.indexOf("$q$q$q")
                if (end < 0) {
                    sb.append(" ".repeat(raw.length))
                    codeLines += sb.toString()
                    return@forEachIndexed
                }
                sb.append(" ".repeat(end + 3))
                i = end + 3
                inTriple = null
            }

            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '#' -> {
                        sb.append(" ".repeat(raw.length - i))
                        i = raw.length
                    }

                    // 三引号（含 r/f/b 前缀的情况也走这里，前缀本身不影响词法）
                    c == '"' || c == '\'' -> {
                        val triple = i + 2 < raw.length && raw[i + 1] == c && raw[i + 2] == c
                        if (triple) {
                            val end = raw.indexOf("$c$c$c", i + 3)
                            if (end < 0) {
                                sb.append(" ".repeat(raw.length - i))
                                inTriple = c
                                tripleStartLine = lineNo
                                i = raw.length
                            } else {
                                sb.append(" ".repeat(end + 3 - i))
                                i = end + 3
                            }
                        } else {
                            sb.append(c)
                            i++
                            var closed = false
                            while (i < raw.length) {
                                if (raw[i] == '\\') {
                                    sb.append("  ".take(minOf(2, raw.length - i)))
                                    i += 2
                                    continue
                                }
                                if (raw[i] == c) {
                                    sb.append(c); i++; closed = true; break
                                }
                                sb.append(' '); i++
                            }
                            if (!closed) {
                                issues += RawIssue(lineNo, "字符串没有闭合（缺少配对的 " + c + "）")
                            }
                        }
                    }

                    c == '(' || c == '[' || c == '{' -> {
                        stack.add(c to lineNo); sb.append(c); i++
                    }

                    c == ')' || c == ']' || c == '}' -> {
                        val expect = when (c) {
                            ')' -> '('
                            ']' -> '['
                            else -> '{'
                        }
                        val top = stack.lastOrNull()
                        if (top == null) {
                            issues += RawIssue(lineNo, "多余的右括号 " + c + "：没有与之配对的左括号")
                        } else if (top.first != expect) {
                            issues += RawIssue(
                                lineNo,
                                "括号不匹配：" + c + " 对应的应该是 " + expect +
                                    "，但最近打开的是 " + top.first + "（第 " + top.second + " 行）",
                            )
                            stack.removeAt(stack.lastIndex)
                        } else {
                            stack.removeAt(stack.lastIndex)
                        }
                        sb.append(c); i++
                    }

                    else -> {
                        sb.append(c); i++
                    }
                }
            }
            codeLines += sb.toString()
        }

        if (inTriple != null) {
            issues += RawIssue(tripleStartLine, "三引号字符串没有闭合（从这一行开始）")
        }
        stack.lastOrNull()?.let {
            issues += RawIssue(it.second, "左括号 " + it.first + " 没有找到对应的右括号")
        }

        return PythonScan(lines, codeLines, issues)
    }

    // ---------------- 括号型语言（Kotlin / Java / C / Go / Rust / Swift ...） ----------------

    /**
     * 面向「花括号语言」的检查：括号配对 + 字符串闭合。
     *
     * 覆盖 Kotlin、Java、C/C++、C#、Go、Rust、Swift、Scala、Dart、PHP…
     * 它们共同的低级错误形式是一样的，所以用同一套扫描即可。
     */
    fun validateBraces(path: String, src: String, language: String): List<Issue> {
        val issues = ArrayList<Issue>()
        val stack = ArrayList<Pair<Char, Int>>()
        var line = 1
        var i = 0
        val n = src.length
        var inBlockComment = false
        var lineComment = false

        fun push(c: Char) = stack.add(c to line)
        fun pop(c: Char) {
            val expect = when (c) {
                ')' -> '('
                ']' -> '['
                else -> '{'
            }
            val top = stack.lastOrNull()
            if (top == null) {
                issues += Issue(path, line, "多余的右括号 " + c + "：没有与之配对的左括号")
            } else if (top.first != expect) {
                issues += Issue(
                    path, line,
                    "括号不匹配：" + c + " 对应的应该是 " + expect + "，但最近打开的是 " +
                        top.first + "（第 " + top.second + " 行）",
                )
                stack.removeAt(stack.lastIndex)
            } else {
                stack.removeAt(stack.lastIndex)
            }
        }

        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else ' '

            if (c == '\n') {
                line++; lineComment = false; i++; continue
            }
            if (lineComment) {
                i++; continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false; i += 2; continue
                }
                i++; continue
            }

            // 注释
            if (c == '/' && next == '/') {
                lineComment = true; i += 2; continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true; i += 2; continue
            }

            // 字符字面量（在 Kotlin/Java 里 'a' 是字符，在 C 里也是；先于字符串处理）
            if (c == '\'') {
                var j = i + 1
                var closed = false
                while (j < n && src[j] != '\n') {
                    if (src[j] == '\\') {
                        j += 2; continue
                    }
                    if (src[j] == '\'') {
                        closed = true; j++; break
                    }
                    j++
                }
                if (!closed) {
                    issues += Issue(path, line, "字符字面量没有闭合（缺少配对的单引号）")
                }
                i = j; continue
            }

            // 字符串（含三引号）
            if (c == '"') {
                val isTriple = i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"'
                if (isTriple) {
                    val end = src.indexOf("\"\"\"", i + 3)
                    if (end < 0) {
                        issues += Issue(path, line, "三引号字符串没有闭合（从这一行开始）")
                        i = n
                    } else {
                        // 三引号里可以有换行，行号要跟上
                        for (k in i until end) if (src[k] == '\n') line++
                        i = end + 3
                    }
                } else {
                    var j = i + 1
                    var closed = false
                    while (j < n) {
                        if (src[j] == '\n') break
                        if (src[j] == '\\') {
                            j += 2; continue
                        }
                        if (src[j] == '"') {
                            closed = true; j++; break
                        }
                        j++
                    }
                    if (!closed) {
                        issues += Issue(path, line, "字符串没有闭合（缺少配对的双引号）")
                    }
                    i = j
                }
                continue
            }

            if (c == '(' || c == '[' || c == '{') {
                push(c); i++; continue
            }
            if (c == ')' || c == ']' || c == '}') {
                pop(c); i++; continue
            }
            i++
        }

        if (inBlockComment) {
            issues += Issue(path, line, "块注释 /* 没有闭合")
        }
        stack.lastOrNull()?.let {
            issues += Issue(path, it.second, "左括号 " + it.first + " 没有找到对应的右括号")
        }

        // 只在文件「明显不像完整文件」时提示，避免误报（片段代码经常只有半个类）
        issues += checkCasing(path, src, language)
        return issues
    }

    /** Kotlin 的小坑：`fun`/`val` 写成大写不是错误但几乎总是笔误，给个警告 */
    private fun checkCasing(path: String, src: String, language: String): List<Issue> {
        if (language != "kotlin") return emptyList()
        val out = ArrayList<Issue>()
        val suspicious = Regex("^\\s*(Fun|Val|Var|Class|Object|Import)\\s", RegexOption.MULTILINE)
        val m = suspicious.find(src) ?: return emptyList()
        val line = src.take(m.range.first).count { it == '\n' } + 1
        out += Issue(
            path, line,
            "Kotlin 关键字应小写：" + m.value.trim() + " —— 是不是写成了大写？",
            Severity.WARNING,
        )
        return out
    }

    // ---------------- Shell ----------------

    /**
     * Shell 检查 `if/fi`、`case/esac`、`do/done` 的配对。
     *
     * 这是 Shell 脚本最常见的低级错误，而且**报错信息极难懂**
     * （bash 只会说 `syntax error: unexpected end of file`，不告诉你是哪个 if）。
     */
    fun validateShell(path: String, src: String): List<Issue> {
        val issues = ArrayList<Issue>()
        val stack = ArrayList<Pair<String, Int>>()
        var line = 1
        src.split("\n").forEachIndexed { idx, raw ->
            val lineNo = idx + 1
            // 去掉注释（# 之后），但跳过字符串里的 #
            val code = stripShellComment(raw)
            val tokens = code.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            tokens.forEach { t ->
                when (t) {
                    "if" -> stack.add("if" to lineNo)
                    "fi" -> popStack(stack, "if", "fi", path, lineNo, issues)
                    "case" -> stack.add("case" to lineNo)
                    "esac" -> popStack(stack, "case", "esac", path, lineNo, issues)
                    "do" -> stack.add("do" to lineNo)
                    "done" -> popStack(stack, "do", "done", path, lineNo, issues)
                    else -> Unit
                }
            }
        }
        stack.lastOrNull()?.let {
            issues += Issue(path, it.second, it.first + " 没有对应的结束标记（" + enderOf(it.first) + "）")
        }
        return issues
    }

    private fun enderOf(kw: String) = when (kw) {
        "if" -> "fi"
        "case" -> "esac"
        else -> "done"
    }

    private fun popStack(
        stack: MutableList<Pair<String, Int>>,
        expected: String,
        token: String,
        path: String,
        line: Int,
        issues: MutableList<Issue>,
    ) {
        val top = stack.lastOrNull()
        if (top == null) {
            issues += Issue(path, line, "多余的 " + token + "：前面没有对应的 " + expected)
        } else if (top.first != expected) {
            issues += Issue(
                path, line,
                token + " 与第 " + top.second + " 行的 " + top.first + " 不匹配",
            )
            stack.removeAt(stack.lastIndex)
        } else {
            stack.removeAt(stack.lastIndex)
        }
    }

    private fun stripShellComment(line: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> i += 2
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle; i++
                }

                c == '"' && !inSingle -> {
                    inDouble = !inDouble; i++
                }

                c == '#' && !inSingle && !inDouble -> return line.substring(0, i)
                else -> i++
            }
        }
        return line
    }

    // ---------------- YAML ----------------

    /**
     * YAML 只查一件事：**缩进里用了 Tab**。
     * YAML 规范明确禁止用 Tab 缩进，而编辑器里 Tab 和空格看起来几乎一样 ——
     * 这是 YAML 最经典的踩坑，报错信息同样很难懂。
     */
    fun validateYaml(path: String, src: String): List<Issue> {
        val out = ArrayList<Issue>()
        src.split("\n").forEachIndexed { idx, raw ->
            val indent = raw.takeWhile { it == ' ' || it == '\t' }
            if (indent.isNotEmpty() && indent.contains('\t') && raw.isNotBlank()) {
                out += Issue(
                    path, idx + 1,
                    "YAML 不允许用制表符缩进，请改成空格。",
                )
            }
        }
        return out
    }

    // ---------------- 内部 ----------------

    private data class RawIssue(val line: Int, val message: String) {
        fun forPath(path: String) = Issue(path, line, message)
    }
}
