package com.dshwatch

import com.dshwatch.domain.agent.build.ProjectBuilder
import com.dshwatch.domain.agent.build.Severity
import com.dshwatch.domain.agent.build.SourceValidator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceValidatorTest {

    private fun errors(path: String, src: String) =
        SourceValidator.validate(path, src).filter { it.severity == Severity.ERROR }

    private fun warnings(path: String, src: String) =
        SourceValidator.validate(path, src).filter { it.severity == Severity.WARNING }

    // ---------------- JSON ----------------

    @Test
    fun `valid json passes`() {
        assertThat(SourceValidator.validate("a.json", """{"a":[1,2,3],"b":"x"}""")).isEmpty()
    }

    @Test
    fun `broken json reports an error with a line number`() {
        val src = """
            {
              "a": 1,
              "b": ,
            }
        """.trimIndent()
        val issues = errors("a.json", src)
        assertThat(issues).isNotEmpty()
        assertThat(issues.first().line).isAtLeast(2)
        assertThat(issues.first().message).contains("JSON")
    }

    @Test
    fun `empty json is an error`() {
        assertThat(errors("a.json", "  ")).hasSize(1)
    }

    // ---------------- HTML ----------------

    @Test
    fun `well formed html passes`() {
        val src = """
            <!DOCTYPE html>
            <html>
              <head><title>t</title><meta charset="utf-8"></head>
              <body>
                <h1>hi</h1>
                <img src="a.png">
                <br>
                <div class="x"><p>text</p></div>
              </body>
            </html>
        """.trimIndent()
        assertThat(errors("index.html", src)).isEmpty()
    }

    @Test
    fun `unclosed div is an error`() {
        val issues = errors("index.html", "<html><body><div><p>x</p></body></html>")
        assertThat(issues).isNotEmpty()
        assertThat(issues.joinToString { it.message }).contains("div")
    }

    @Test
    fun `stray closing tag is an error`() {
        val issues = errors("index.html", "<html><body></div></body></html>")
        assertThat(issues).isNotEmpty()
        assertThat(issues.first().message).contains("没有对应的开始标签")
    }

    @Test
    fun `unclosed script is an error`() {
        val issues = errors("index.html", "<html><body><script>var a=1;</body></html>")
        assertThat(issues).isNotEmpty()
        assertThat(issues.joinToString { it.message }).contains("script")
    }

    @Test
    fun `html inside script string is not treated as a tag`() {
        val src = "<html><body><script>var s = \"<div>not a tag</div>\";</script></body></html>"
        assertThat(errors("index.html", src)).isEmpty()
    }

    @Test
    fun `unterminated comment is an error`() {
        val issues = errors("index.html", "<html><!-- oops </html>")
        assertThat(issues).isNotEmpty()
        assertThat(issues.first().message).contains("注释")
    }

    @Test
    fun `stray closing tag with empty stack says it is superfluous`() {
        val issues = errors("index.html", "<html></html></div>")
        assertThat(issues.joinToString { it.message }).contains("多余的结束标签")
    }

    @Test
    fun `optional end tags are warnings not errors`() {
        val issues = SourceValidator.validate("index.html", "<html><body><ul><li>a<li>b</ul></body></html>")
        assertThat(issues.none { it.severity == Severity.ERROR }).isTrue()
    }

    // ---------------- XML ----------------

    @Test
    fun `mismatched xml tags are errors`() {
        val issues = errors("a.xml", "<root><a></b></root>")
        assertThat(issues).isNotEmpty()
    }

    @Test
    fun `valid svg passes`() {
        val src = """<svg xmlns="http://www.w3.org/2000/svg"><rect width="1" height="1"/></svg>"""
        assertThat(errors("a.svg", src)).isEmpty()
    }

    // ---------------- CSS ----------------

    @Test
    fun `valid css passes`() {
        assertThat(errors("a.css", "body { color: red; }\n@media (min-width:1px){ a{} }")).isEmpty()
    }

    @Test
    fun `unbalanced braces are errors`() {
        assertThat(errors("a.css", "body { color: red;")).isNotEmpty()
        assertThat(errors("a.css", "body { } }")).isNotEmpty()
    }

    @Test
    fun `brace inside css string or comment is ignored`() {
        assertThat(errors("a.css", "a::after { content: \"}\"; }\n/* } */")).isEmpty()
    }

    // ---------------- JS ----------------

    @Test
    fun `valid js passes`() {
        assertThat(errors("a.js", "function f(a){ return [1,2].map(x => (x*a)); }")).isEmpty()
    }

    @Test
    fun `unterminated string is an error`() {
        val issues = errors("a.js", "var s = \"unterminated;\nvar t = 1;")
        assertThat(issues).isNotEmpty()
        assertThat(issues.first().message).contains("字符串没有闭合")
    }

    @Test
    fun `unterminated block comment is an error`() {
        val issues = errors("a.js", "/* never ends\nvar a = 1;")
        assertThat(issues).isNotEmpty()
        assertThat(issues.first().message).contains("块注释")
    }

    @Test
    fun `bracket imbalance is only a warning because regex literals fool the scanner`() {
        val issues = SourceValidator.validate("a.js", "var re = /[(]/; function f(){")
        assertThat(issues.any { it.severity == Severity.ERROR }).isFalse()
        assertThat(issues.any { it.severity == Severity.WARNING }).isTrue()
    }

    @Test
    fun `block comment content is ignored`() {
        assertThat(errors("a.js", "/* \" } */\nvar a = 1;")).isEmpty()
    }

    @Test
    fun `unknown extension is skipped`() {
        assertThat(SourceValidator.validate("a.md", "# 乱七八糟 {{{")).isEmpty()
        assertThat(SourceValidator.isCheckable("a.md")).isFalse()
    }

    @Test
    fun `binary content is detected`() {
        assertThat(SourceValidator.looksBinary("\u0000\u0001abc")).isTrue()
        assertThat(SourceValidator.looksBinary("plain text")).isFalse()
    }

    // ---------------- 路径规范化 ----------------

    @Test
    fun `normalizePath folds dots and clamps traversal`() {
        assertThat(ProjectBuilder.normalizePath("a/b/../c.css")).isEqualTo("a/c.css")
        assertThat(ProjectBuilder.normalizePath("./a//b.css")).isEqualTo("a/b.css")
        assertThat(ProjectBuilder.normalizePath("../escape.css")).isEmpty()
        assertThat(ProjectBuilder.normalizePath("dist/x.html")).isEqualTo("dist/x.html")
    }
}
