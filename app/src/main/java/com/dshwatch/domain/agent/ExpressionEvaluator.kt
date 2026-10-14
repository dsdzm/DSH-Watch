package com.dshwatch.domain.agent

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 安全表达式求值器，给 agent 的 `calculate` 工具用。
 *
 * 为什么自己写而不用 ScriptEngine / Rhino：那些要么在 Android 上不可用，
 * 要么会把整个 JS 引擎（几 MB）打进 APK。这里是个递归下降解析器，
 * 纯 Kotlin、零依赖、可单测，也不可能被注入执行任意代码。
 *
 * 语法：
 *   expr    := term (('+' | '-') term)*
 *   term    := factor (('*' | '/' | '%') factor)*
 *   factor  := unary ('^' factor)?          // 幂运算右结合
 *   unary   := ('+' | '-')? primary
 *   primary := number | ident | ident '(' args ')' | '(' expr ')'
 */
object ExpressionEvaluator {

    private val functions: Map<String, (List<Double>) -> Double> = mapOf(
        "sqrt" to { a -> arity(a, 1, "sqrt"); if (a[0] < 0) fail("sqrt 的参数不能为负数") else sqrt(a[0]) },
        "abs" to { a -> arity(a, 1, "abs"); abs(a[0]) },
        "round" to { a -> arity(a, 1, "round"); round(a[0]) },
        "floor" to { a -> arity(a, 1, "floor"); floor(a[0]) },
        "ceil" to { a -> arity(a, 1, "ceil"); ceil(a[0]) },
        "ln" to { a -> arity(a, 1, "ln"); if (a[0] <= 0) fail("ln 的参数必须为正") else ln(a[0]) },
        "log10" to { a -> arity(a, 1, "log10"); if (a[0] <= 0) fail("log10 的参数必须为正") else log10(a[0]) },
        "sin" to { a -> arity(a, 1, "sin"); sin(a[0]) },
        "cos" to { a -> arity(a, 1, "cos"); cos(a[0]) },
        "tan" to { a -> arity(a, 1, "tan"); tan(a[0]) },
        "pow" to { a -> arity(a, 2, "pow"); a[0].pow(a[1]) },
        "min" to { a -> arity(a, 2, "min"); min(a[0], a[1]) },
        "max" to { a -> arity(a, 2, "max"); max(a[0], a[1]) },
    )

    private val constants: Map<String, Double> = mapOf(
        "pi" to Math.PI,
        "e" to Math.E,
        "π" to Math.PI,
    )

    /** @throws IllegalArgumentException 表达式非法时抛出，附带可直接展示给模型的中文原因 */
    fun evaluate(expression: String): Double {
        val parser = Parser(expression)
        val value = parser.parseExpression()
        parser.skipSpaces()
        if (!parser.atEnd()) parser.fail("位置 ${parser.pos} 处有多余字符 '${parser.peek()}'")
        if (value.isNaN() || value.isInfinite()) fail("计算结果不是有限数（$value）")
        return value
    }

    /** 给模型看的文本结果：整数去掉小数点，小数最多保留 10 位有效数字 */
    fun evaluateToText(expression: String): String {
        val v = evaluate(expression)
        return format(v)
    }

    fun format(v: Double): String {
        if (abs(v - v.toLong()) < 1e-9 && abs(v) < 1e15) return v.toLong().toString()
        val rounded = String.format(java.util.Locale.US, "%.10g", v)
        // %.10g 会把 3.5 打成 "3.500000000"，得手动去掉尾随的 0；
        // 科学计数法（如 1.000000000e-05）保留原样，否则会破坏有效位。
        return if (rounded.contains('.') && !rounded.contains('e') && !rounded.contains('E')) {
            rounded.trimEnd('0').trimEnd('.')
        } else {
            rounded
        }
    }

    private fun arity(args: List<Double>, count: Int, name: String) {
        if (args.size != count) fail("$name 需要 $count 个参数，实际给了 ${args.size} 个")
    }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException(msg)

    private class Parser(private val src: String) {
        var pos = 0

        fun atEnd() = pos >= src.length
        fun peek(): Char = src[pos]
        fun fail(msg: String): Nothing = throw IllegalArgumentException(msg)

        fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                if (atEnd()) break
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> break
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipSpaces()
                if (atEnd()) break
                when (peek()) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> {
                        pos++
                        val d = parseFactor()
                        if (d == 0.0) fail("除数不能为 0")
                        value /= d
                    }
                    '%' -> {
                        pos++
                        val d = parseFactor()
                        if (d == 0.0) fail("取模的除数不能为 0")
                        value %= d
                    }
                    else -> break
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            skipSpaces()
            if (!atEnd() && peek() == '^') {
                pos++
                // 右结合：2^3^2 = 2^(3^2)
                return base.pow(parseFactor())
            }
            return base
        }

        private fun parseUnary(): Double {
            skipSpaces()
            if (atEnd()) fail("表达式意外结束")
            return when (peek()) {
                '-' -> { pos++; -parseUnary() }
                '+' -> { pos++; parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            if (atEnd()) fail("表达式意外结束")

            if (peek() == '(') {
                pos++
                val v = parseExpression()
                skipSpaces()
                if (atEnd() || peek() != ')') fail("缺少右括号")
                pos++
                return v
            }

            if (peek().isDigit() || peek() == '.') return parseNumber()

            if (peek().isLetter() || peek() == 'π') return parseIdentifier()

            fail("位置 $pos 处出现无法识别的字符 '${peek()}'")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            // 科学计数法 1e-3
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                val save = pos
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos < src.length && src[pos].isDigit()) {
                    while (pos < src.length && src[pos].isDigit()) pos++
                } else {
                    pos = save
                }
            }
            val text = src.substring(start, pos)
            return text.toDoubleOrNull() ?: fail("无法解析数字 '$text'")
        }

        private fun parseIdentifier(): Double {
            val start = pos
            if (peek() == 'π') {
                pos++
                return Math.PI
            }
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos).lowercase()

            skipSpaces()
            if (!atEnd() && peek() == '(') {
                pos++
                val args = ArrayList<Double>(2)
                skipSpaces()
                if (!atEnd() && peek() == ')') {
                    pos++
                } else {
                    while (true) {
                        args.add(parseExpression())
                        skipSpaces()
                        if (atEnd()) fail("函数 $name 缺少右括号")
                        when (peek()) {
                            ',' -> { pos++; continue }
                            ')' -> { pos++; break }
                            else -> fail("函数 $name 的参数分隔符应为逗号")
                        }
                    }
                }
                val fn = functions[name] ?: fail("不认识的函数 '$name'，可用：${functions.keys.sorted().joinToString(", ")}")
                return fn(args)
            }

            return constants[name] ?: fail("不认识的常量 '$name'，可用：pi、e")
        }
    }
}
