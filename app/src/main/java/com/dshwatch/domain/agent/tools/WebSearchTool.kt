package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.agent.stringMapOf
import com.dshwatch.net.CustomHeaders
import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 联网搜索。
 *
 * ## 为什么不再只走 DuckDuckGo
 *
 * 1.17 之前这里只有一个源：`lite.duckduckgo.com`。它在境外网络很好用，
 * 但在国内是**直接超时**——用户看到的是「连不上底层搜索源」，
 * 然后模型被迫退化成 http_fetch 抓某个它猜出来的网址。
 *
 * 现在的顺序是「先国内可达、再海外备选」：
 *
 *  1. `cn.bing.com`：国内直连，返回的是服务端渲染的 HTML（5 条自然结果），
 *     支持 site: / 引号 等语法，实测 0.3s 内返回；
 *  2. `lite.duckduckgo.com`：保留给能出去的设备，但**只给 10 秒**，
 *     免得一个不可达的源把整轮对话吊住。
 *
 * 百度没有进名单：它对非浏览器 UA 会返回 2MB 的「安全验证」页，
 * 解析出来全是空结果，比失败还误导人。
 *
 * ## 关于超时
 *
 * [Http.client] 的 readTimeout 是 180 秒 —— 那是给大模型流式响应用的。
 * 搜索必须快速失败换源，所以这里单独建一个 callTimeout=12s 的 client。
 */
object WebSearchTool : AgentTool {
    override val name = "web_search"
    override val description =
        "联网搜索关键词，返回标题 + 链接 + 摘要的列表。用于获取你不知道或可能已过时的信息。" +
            "query 支持常见搜索语法：site:github.com 限定站点、-site:example.com 排除、" +
            "\"精确短语\"、after:2025-01-01 / before:2026 限定时间。多个条件用空格组合。" +
            "需要自定义请求头（UA、Referer、API Key）时传 headers 键值对。" +
            "需要精确抓取某个已知网页的内容时，直接用 http_fetch 更可靠。"
    override val safety = ToolSafety.NETWORK
    override val parameters = Schema.obj(
        properties = mapOf(
            "query" to Schema.str("搜索关键词，支持 site: / -site: / \"短语\" / after: / before: 语法"),
            "max_results" to Schema.int("最多返回多少条，默认 6", 1, 10),
            "headers" to Schema.strMap(
                "自定义请求头（键值对，值必须是字符串）。可覆盖默认的 User-Agent / Accept；" +
                    "部分搜索源会按 Referer 或 Cookie 返回不同结果"
            ),
        ),
        required = listOf("query"),
    )

    /** 一条搜索结果 */
    internal data class Hit(val title: String, val url: String, val snippet: String)

    /**
     * 一个搜索源。
     *
     * 解析函数单独抽出来，是为了能用固定的 HTML 片段做单测 ——
     * 搜索页结构会变，但至少「解析器认不认这份结构」这件事不该靠联网验证。
     */
    internal class Engine(
        val label: String,
        val buildUrl: (String) -> String,
        val parse: (String, Int) -> List<Hit>,
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120 Mobile Safari/537.36"

    /** 顺序即优先级：国内可达的放前面 */
    internal val engines: List<Engine> = listOf(
        Engine(
            label = "bing",
            buildUrl = { q -> "https://cn.bing.com/search?q=${enc(q)}&count=10&setlang=zh-Hans" },
            parse = ::parseBing,
        ),
        Engine(
            label = "duckduckgo",
            buildUrl = { q -> "https://lite.duckduckgo.com/lite/?q=${enc(q)}" },
            parse = ::parseDuckDuckGo,
        ),
    )

    /** 搜索专用 client：整次调用 12 秒封顶，失败立刻换源 */
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.strOf("query") ?: return ToolResult.fail("缺少 query 参数")
        val limit = (args.intOf("max_results") ?: 6).coerceIn(1, 10)
        val custom = CustomHeaders.sanitize(args.stringMapOf("headers"))

        val failures = ArrayList<String>()
        var emptySource: String? = null

        for (engine in engines) {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { fetch(engine, query, custom.values) }
            }
            outcome.onFailure { e ->
                failures += "${engine.label}: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}"
            }
            val html = outcome.getOrNull() ?: continue
            val hits = runCatching { engine.parse(html, limit) }.getOrDefault(emptyList())
            if (hits.isEmpty()) {
                emptySource = engine.label
                continue
            }
            return ToolResult.ok(
                buildString {
                    if (custom.rejected.isNotEmpty()) {
                        append(CustomHeaders.rejectedNote(custom.rejected)).append('\n')
                    }
                    append("搜索「").append(query).append("」的结果（")
                        .append(engine.label).append("）：\n")
                    hits.forEachIndexed { i, h ->
                        append(i + 1).append(". ").append(h.title).append('\n')
                        append("   ").append(h.url).append('\n')
                        if (h.snippet.isNotBlank()) append("   ").append(h.snippet).append('\n')
                    }
                    append("（如需完整内容，用 http_fetch 抓上面某个链接）")
                }
            )
        }

        // 全都失败：把每个源的原因列出来，模型才能判断是「网络不通」还是「关键词没结果」
        if (failures.isNotEmpty()) {
            return ToolResult.fail(
                "搜索失败：所有搜索源都不可用（" + failures.joinToString("；") + "）。" +
                    "这台设备可能访问不了这些服务，请改用 http_fetch 直接抓已知网址，" +
                    "或基于已有知识回答并说明不确定性。"
            )
        }
        return ToolResult.ok(
            "没有搜到「$query」的结果（${emptySource ?: "搜索源"} 返回了空页面，可能无结果或页面结构变了）。"
        )
    }

    override fun summarize(args: JsonObject) = "搜 ${args.strOf("query")}"

    private fun fetch(engine: Engine, query: String, headers: Map<String, String>): String {
        val builder = Request.Builder()
            .url(engine.buildUrl(query))
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        CustomHeaders.apply(builder, headers)
        client.newCall(builder.get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------
    // Bing
    // ------------------------------------------------------------------

    private val BING_ITEM = Regex(
        """<li class="b_algo".*?</li>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val H2 = Regex(
        """<h2[^>]*>(.*?)</h2>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val BING_SNIPPET = Regex(
        """<p class="b_lineclamp[^"]*"[^>]*>(.*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val HREF = Regex("""href="(https?://[^"]+)"""", RegexOption.IGNORE_CASE)

    /**
     * 解析必应结果页。
     *
     * 结构是 `li.b_algo` 一条结果，标题在 `h2` 里，摘要在 `p.b_lineclamp*` 里。
     * 链接取块内第一个非必应域名 —— 必应会插自己的「图片/视频/翻译」链接，
     * 但那些都在 `bing.com` 下，按域名排掉即可。
     */
    internal fun parseBing(html: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val seen = HashSet<String>()
        BING_ITEM.findAll(html).forEach { m ->
            if (out.size >= limit) return@forEach
            val block = m.value
            val url = HREF.findAll(block).map { it.groupValues[1] }
                .firstOrNull { u ->
                    !u.contains("bing.com") && !u.contains("microsoft.com") && !u.contains("msn.com")
                } ?: return@forEach
            val title = H2.find(block)?.groupValues?.get(1)?.let(::plain).orEmpty()
            if (title.length < 2) return@forEach
            if (!seen.add(url)) return@forEach
            val snippet = BING_SNIPPET.find(block)?.groupValues?.get(1)?.let(::plain).orEmpty()
            out += Hit(title, url, snippet)
        }
        return out
    }

    // ------------------------------------------------------------------
    // DuckDuckGo lite
    // ------------------------------------------------------------------

    private val ANCHOR = Regex(
        """<a\b[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    internal fun parseDuckDuckGo(html: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val seen = HashSet<String>()
        ANCHOR.findAll(html).forEach { m ->
            if (out.size >= limit) return@forEach
            var href = m.groupValues[1]
            val title = plain(m.groupValues[2])

            // lite 端点会包一层跳转：//duckduckgo.com/l/?uddg=<编码后的真实地址>
            if (href.contains("uddg=")) {
                href = runCatching {
                    val e = href.substringAfter("uddg=").substringBefore('&')
                    URLDecoder.decode(e, "UTF-8")
                }.getOrDefault(href)
            } else if (href.startsWith("//")) {
                href = "https:$href"
            }
            if (!href.startsWith("http")) return@forEach
            if (href.contains("duckduckgo.com")) return@forEach
            if (title.length < 3) return@forEach
            if (!seen.add(href)) return@forEach
            out += Hit(title, href, "")
        }
        return out
    }

    // ------------------------------------------------------------------

    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")

    /** 去标签 + 还原实体 + 压空白；结果里混着 `<strong>` 高亮，不去掉会干扰阅读 */
    internal fun plain(s: String): String = SPACES.replace(
        unescape(TAGS.replace(s, "")).trim(),
        " ",
    )

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace("&#183;", "·").replace("&mdash;", "—")
}
