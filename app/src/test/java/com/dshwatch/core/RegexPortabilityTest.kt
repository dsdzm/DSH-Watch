package com.dshwatch.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 正则的**可移植性**守卫。
 *
 * ## 为什么需要这一层
 *
 * 1.23.2(84) 的一条历史消息里有 ` ```mindmap ` 围栏，解析时用了
 * `Regex("^\\{(.*)}$")` —— 结尾的 `}` 没转义。JDK 的 `java.util.regex`
 * 把孤立的 `}` 当字面量，测试全绿；Android 9 起换成 ICU 实现，ICU 判它非法，
 * 构造 Pattern 就抛 `PatternSyntaxException`，而且抛在组合期（帧回调）里，
 * 直接闪退。用户被自己的会话历史卡进 crash loop。
 *
 * 两个引擎的差别不止 `}`：转义规则、字符类里的连字符、`\d` 的 Unicode 语义、
 * 反向引用都有出入。全部模拟不现实，但**未配对的 `}` 是唯一会让人踩到的那个**，
 * 而它恰好可以用静态扫描抓出来 —— 不用跑在 ICU 上。
 *
 * 所以：扫全部源码里的正则字面量，字符类外出现没有 `{` 配对的 `}` 就失败。
 */
class RegexPortabilityTest {

    /** 找出所有 `Regex("...")` 与 `Regex("""...""")` */
    private val plain = Regex("Regex\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val triple = Regex("Regex\\(\\s*\"\"\"([\\s\\S]*?)\"\"\"")

    private fun kotlinFiles(): List<File> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** 还原 Java/Kotlin 字符串转义：`\\{` 是正则里的 `\{`，不是字面量反斜杠 */
    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * 字符类之外、又没有对应 `{` 的 `}` 的位置。
     *
     * 已经转义的（`\}`）跳过 —— 那本来就是「字面量花括号」，两个引擎都认。
     */
    private fun strayCloseBraces(pattern: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        var open = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (inClass) {
                if (c == ']') inClass = false
                i++
                continue
            }
            when (c) {
                '[' -> inClass = true
                '{' -> open++
                '}' -> if (open > 0) open-- else out += i
            }
            i++
        }
        return out
    }

    @Test
    fun `源码里没有会让 ICU 报错的正则`() {
        val files = kotlinFiles()
        // 路径写错时 walkTopDown 会返回空列表，那样这个用例永远通过 —— 先钉住规模
        assertThat(files.size).isGreaterThan(50)

        val problems = ArrayList<String>()
        var scanned = 0
        for (file in files) {
            val text = file.readText()
            val found = ArrayList<Pair<Int, String>>()
            // 三引号的先扫，避免同一位置被普通字符串规则重复命中
            for (m in triple.findAll(text)) {
                found += m.range.first to m.groupValues[1]
            }
            for (m in plain.findAll(text)) {
                found += m.range.first to unescape(m.groupValues[1])
            }
            scanned += found.size
            for ((offset, pattern) in found) {
                val bad = strayCloseBraces(pattern)
                if (bad.isNotEmpty()) {
                    val line = text.take(offset).count { it == '\n' } + 1
                    problems += "${file.path}:$line  $pattern  → 未转义的 '}' 在下标 $bad"
                }
            }
        }
        // 同理：正则没匹配到字面量也说明扫描器坏了
        assertThat(scanned).isGreaterThan(20)
        assertThat(problems).isEmpty()
    }

    /**
     * 守卫本身要能抓到东西 —— 否则它只是个永远通过的摆设。
     *
     * 用的就是线上那条：`^\{(.*)}$`。
     */
    @Test
    fun `扫描器能认出出问题的写法`() {
        assertThat(strayCloseBraces("^\\{(.*)}$")).isNotEmpty()
        assertThat(strayCloseBraces("^\\{(.*)\\}$")).isEmpty()
        // 源码里写成 "^\\\{(.*)}$" 时，编译后正则才是 `^\{(.*)}$`
        assertThat(strayCloseBraces(unescape("^\\\\{(.*)}$"))).isNotEmpty()
        assertThat(strayCloseBraces("a{2,3}")).isEmpty()
        assertThat(strayCloseBraces("[}]")).isEmpty()
    }

    @Test
    fun `标准 Java 正则不会把孤立的右括号当错误`() {
        // 这正是当年没被测试发现的原因：JVM 让它过，ICU 不让。
        // 这条用例把「两个引擎不一致」这件事钉在代码里，提醒后来的人别信 JVM。
        java.util.regex.Pattern.compile("^\\{(.*)}$")
    }
}
