package com.dshwatch

import com.dshwatch.net.HtmlText
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * HTML → 纯文本。
 *
 * 这个转换存在的唯一理由是**省 token**：手表上上下文紧张、按次计费，
 * 而一个网页的 HTML 里九成以上是模型根本不会看的标签与脚本。
 * 所以下面每条用例关注的都不是「转换够不够漂亮」，而是
 * **正文有没有被误伤、噪音有没有被去掉**。
 */
class HtmlTextTest {

    @Test
    fun `script and style are removed entirely`() {
        val html = """
            <html><head><style>.a{color:red}</style></head>
            <body>
              <script>var secret = 1; console.log('noise');</script>
              <p>正文在这里</p>
            </body></html>
        """.trimIndent()
        val text = HtmlText.extract(html)
        assertThat(text).contains("正文在这里")
        assertThat(text).doesNotContain("console.log")
        assertThat(text).doesNotContain("color:red")
        assertThat(text).doesNotContain("<p>")
    }

    @Test
    fun `block elements become line breaks`() {
        val html = "<div>第一段</div><div>第二段</div>"
        val text = HtmlText.extract(html)
        assertThat(text.lines().filter { it.isNotBlank() }).hasSize(2)
    }

    @Test
    fun `nav footer aside are dropped when aggressive`() {
        val html = """
            <nav>首页 关于 联系</nav>
            <article>真正的内容</article>
            <footer>版权所有</footer>
        """.trimIndent()
        val text = HtmlText.extract(html, aggressive = true)
        assertThat(text).contains("真正的内容")
        assertThat(text).doesNotContain("版权所有")

        // 非 aggressive 时保留 —— 有时用户就是想知道页面上有哪些链接
        val full = HtmlText.extract(html, aggressive = false)
        assertThat(full).contains("版权所有")
    }

    @Test
    fun `entities are decoded`() {
        val html = "<p>a &amp; b &lt;tag&gt; &quot;q&quot; &#65; &#x42;</p>"
        val text = HtmlText.extract(html)
        assertThat(text).contains("a & b")
        assertThat(text).contains("<tag>")
        assertThat(text).contains("\"q\"")
        assertThat(text).contains("A B")
    }

    @Test
    fun `amp is decoded last so double escaped text stays readable`() {
        // &amp;lt; 应该变成 &lt;（字面），而不是 <
        val text = HtmlText.extract("<p>&amp;lt;</p>")
        assertThat(text).contains("&lt;")
    }

    @Test
    fun `huge html collapses to a small text`() {
        // 这是这个组件存在的意义：把体积里的标签部分全部丢掉
        val body = (1..400).joinToString("") {
            "<div class=\"row-${'$'}it\"><span style=\"color:red\">item ${'$'}it</span></div>"
        }
        val html = "<html><body>$body</body></html>"
        val text = HtmlText.extract(html)
        assertThat(text.length).isLessThan(html.length / 3)
        // 400 个 div 各压成一行，标签全部消失
        assertThat(text.lines().count { it.isNotBlank() }).isEqualTo(400)
    }

    @Test
    fun `isHtml detects doctype and content type`() {
        assertThat(HtmlText.isHtml("text/html; charset=utf-8", "x")).isTrue()
        assertThat(HtmlText.isHtml("", "<!DOCTYPE html><html>")).isTrue()
        assertThat(HtmlText.isHtml("", "{\"a\":1}")).isFalse()
        assertThat(HtmlText.isHtml("application/json", "{\"a\":1}")).isFalse()
    }
}
