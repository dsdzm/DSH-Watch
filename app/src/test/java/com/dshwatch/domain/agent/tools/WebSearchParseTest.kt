package com.dshwatch.domain.agent.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 搜索结果的解析。
 *
 * 这里不联网：搜索页结构一变，最先坏的就是解析，而联网测试既慢又不稳，
 * 定位不到是「网络不通」还是「正则不认新结构」。所以用固定的 HTML 片段，
 * 守住三件在真机上踩过的事：
 *
 *  1. 必应的标题在 `h2`、摘要在 `p.b_lineclamp*`，链接要跳过 bing.com 自己的跳转；
 *  2. 同一个链接重复出现时只留一条（必应会在下面再挂一次「相关搜索」）；
 *  3. DuckDuckGo 的 `uddg=` 重定向要还原成真实地址，否则模型拿到的是一串跳转。
 */
class WebSearchParseTest {

    private val bingHtml = """
        <ol id="b_results">
        <li class="b_algo" data-id iid=SERP.1><div class="b_tpcn"><a class="tilk" href="https://book.kotlincn.net/text/getting-started.html"><h2>入门 · <strong>Kotlin</strong> 官方文档 中文版</h2></a></div><div class="b_caption"><p class="b_lineclamp3" data-rslinkclamp-iid="">Kotlin 入门 最新 Kotlin 版本： 2.1.21 Kotlin 是一门现代但已成熟的编程语言。</p></div></li>
        <li class="b_algo" data-id iid=SERP.2><div class="b_tpcn"><a class="tilk" href="https://www.runoob.com/kotlin/kotlin-tutorial.html"><h2>Kotlin 教程 | 菜鸟教程</h2></a></div><div class="b_caption"><p class="b_lineclamp2">Kotlin 是一门运行在 Java 虚拟机上的静态类型编程语言。</p></div></li>
        <li class="b_algo" data-id iid=SERP.3><div><a href="https://www.bing.com/images/search?q=kotlin"><h2>Kotlin 图片</h2></a></div></li>
        <li class="b_algo" data-id iid=SERP.4><div><a href="https://www.runoob.com/kotlin/kotlin-tutorial.html"><h2>Kotlin 教程 | 菜鸟教程</h2></a></div></li>
        </ol>
    """.trimIndent()

    @Test
    fun `bing parses title link and snippet`() {
        val hits = WebSearchTool.parseBing(bingHtml, 6)
        assertThat(hits).hasSize(2)
        assertThat(hits[0].title).isEqualTo("入门 · Kotlin 官方文档 中文版")
        assertThat(hits[0].url).isEqualTo("https://book.kotlincn.net/text/getting-started.html")
        assertThat(hits[0].snippet).contains("2.1.21")
        assertThat(hits[1].url).isEqualTo("https://www.runoob.com/kotlin/kotlin-tutorial.html")
    }

    @Test
    fun `bing skips its own redirect links and duplicates`() {
        val urls = WebSearchTool.parseBing(bingHtml, 6).map { it.url }
        assertThat(urls).containsNoDuplicates()
        assertThat(urls.any { it.contains("bing.com") }).isFalse()
        // SERP.3 是必应自己的图片搜索，SERP.4 是重复链接，都不该出现
        assertThat(urls).hasSize(2)
    }

    @Test
    fun `limit is respected`() {
        assertThat(WebSearchTool.parseBing(bingHtml, 1)).hasSize(1)
    }

    @Test
    fun `duckduckgo unwraps uddg redirect`() {
        val html = """
            <a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&rut=abc" class="result-link">Kotlin Programming Language</a>
            <a href="https://duckduckgo.com/settings">设置</a>
            <a href="https://example.com/a">Example Domain</a>
        """.trimIndent()
        val hits = WebSearchTool.parseDuckDuckGo(html, 6)
        assertThat(hits).hasSize(2)
        assertThat(hits[0].url).isEqualTo("https://kotlinlang.org/")
        assertThat(hits[1].url).isEqualTo("https://example.com/a")
    }

    @Test
    fun `plain strips tags and collapses whitespace`() {
        assertThat(WebSearchTool.plain("<strong>Kotlin</strong>   协程 &amp; 流"))
            .isEqualTo("Kotlin 协程 & 流")
    }

    @Test
    fun `empty page yields no hits instead of throwing`() {
        assertThat(WebSearchTool.parseBing("", 6)).isEmpty()
        assertThat(WebSearchTool.parseDuckDuckGo("", 6)).isEmpty()
    }

    @Test
    fun `every engine builds an absolute https url`() {
        WebSearchTool.engines.forEach { engine ->
            val url = engine.buildUrl("kotlin 协程")
            assertThat(url).startsWith("https://")
            assertThat(url).contains("kotlin")
        }
    }
}
