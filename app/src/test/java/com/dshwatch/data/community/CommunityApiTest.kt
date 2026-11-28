package com.dshwatch.data.community

import com.dshwatch.core.Jsonx
import com.dshwatch.domain.roleplay.CardPlugin
import com.dshwatch.domain.roleplay.LoreEntry
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 社区接口的编解码。
 *
 * 这里不打真服务器：社区主机在境外、还要审核，一旦测试依赖它，
 * 跑一次单测就等于给服务器发几十个请求。用 MockWebServer 钉住三件事：
 *
 *  1. 响应字段缺失时不能崩（服务端是 `?a=xxx` 拼数组，增减字段很随意）；
 *  2. 投稿的 payload **不能**带 `priority` / `enabled` / 插件正文
 *     —— 这三样会让一张卡把别人的设定挤出上下文，服务端明确不收；
 *  3. 服务端那句中文错误要原样透传给界面（「这个名字已经被用了」），
 *     套一层「请求失败：」就会让用户看不懂到底哪一步错了。
 */
class CommunityApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CommunityApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = CommunityApi(Jsonx.lenient, server.url("/api/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `列表解析 items 与 hasMore`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"ok":true,"items":[
                  {"id":7,"kind":"tavern","name":"雨天便利店","tagline":"值夜班","author":"站长",
                   "downloads":3,"likes":1,"createdAt":1700000000}
                ],"page":0,"hasMore":true}
                """.trimIndent()
            )
        )

        val page = api.list(kind = Scenes.TAVERN, query = "雨")

        assertThat(page.items).hasSize(1)
        assertThat(page.items[0].name).isEqualTo("雨天便利店")
        assertThat(page.items[0].author).isEqualTo("站长")
        assertThat(page.items[0].downloads).isEqualTo(3)
        assertThat(page.hasMore).isTrue()

        val path = server.takeRequest().path.orEmpty()
        assertThat(path).contains("a=list")
        assertThat(path).contains("kind=tavern")
        // 中文查询要编码，否则 PHP 那边收到的是乱码
        assertThat(path).contains("q=%E9%9B%A8")
    }

    @Test
    fun `列表缺字段时给默认值而不是崩`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"items":[{"id":3}]}"""))

        val page = api.list()

        assertThat(page.items).hasSize(1)
        assertThat(page.items[0].name).isEmpty()
        assertThat(page.items[0].kind).isEqualTo(Scenes.TAVERN)
        assertThat(page.items[0].status).isEqualTo(CommunityCard.STATUS_APPROVED)
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun `详情把 payload 转成本地卡且不带远端 id`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"ok":true,"item":{"id":9,"kind":"tavern","name":"雨天便利店","tagline":"值夜班",
                 "author":"站长","downloads":1,"likes":0,"createdAt":1700000000,
                 "card":{"kind":"tavern","name":"雨天便利店","tagline":"值夜班","persona":"她守着店",
                  "greeting":"欢迎光临","style":"短句",
                  "fields":[{"key":"年龄","value":"23"}],
                  "entries":[{"title":"店里的规矩","content":"十一点打烊","keywords":["打烊"],
                              "priority":9,"enabled":false}]}}}
                """.trimIndent()
            )
        )

        val detail = api.detail(9, download = true)

        assertThat(detail.meta.id).isEqualTo(9L)
        assertThat(detail.card.id).isEmpty()
        assertThat(detail.card.builtin).isFalse()
        assertThat(detail.card.name).isEqualTo("雨天便利店")
        assertThat(detail.card.persona).isEqualTo("她守着店")
        assertThat(detail.card.fields).hasSize(1)
        assertThat(detail.card.fields[0].key).isEqualTo("年龄")
        assertThat(detail.card.entries).hasSize(1)
        assertThat(detail.card.entries[0].keywords).containsExactly("打烊")

        // download=1 只在「真的导入」时出现，服务端靠它给下载数 +1
        assertThat(server.takeRequest().path.orEmpty()).contains("download=1")
    }

    @Test
    fun `投稿不带优先级与插件`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":12,"status":"pending"}"""))

        val card = RoleCard(
            kind = Scenes.TAVERN,
            name = "雨天便利店",
            persona = "她守着这家店，等一个不会再来的客人。".repeat(4),
            greeting = "欢迎光临",
            entries = listOf(
                LoreEntry(
                    title = "店里的规矩",
                    content = "十一点打烊",
                    keywords = listOf("打烊"),
                    priority = 9,
                    enabled = false,
                )
            ),
            plugins = listOf(CardPlugin(name = "风神", prompt = "写得快一点")),
        )

        val id = api.submit("tok-1", card)

        assertThat(id).isEqualTo(12L)
        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok-1")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"name\":\"雨天便利店\"")
        assertThat(body).contains("\"persona\"")
        // 这三样会让一张卡挤掉别人的设定 / 客户端无法审核其效果，服务端明确不收
        assertThat(body).doesNotContain("priority")
        assertThat(body).doesNotContain("\"enabled\"")
        assertThat(body).doesNotContain("\"prompt\"")
        // 没投过的卡不带 id：带错了服务端会去改一条不属于自己的稿
        assertThat(body).doesNotContain("\"id\"")
    }

    @Test
    fun `改稿带上远端 id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":12,"updated":true}"""))

        api.submit("tok", RoleCard(name = "雨天便利店", persona = "x".repeat(30)), remoteId = 12)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"id\":12")
    }

    @Test
    fun `服务端的中文错误原样抛出`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"ok":false,"error":"这个名字已经被用了"}""")
        )

        val err = runCatching { api.login("站长", "123456") }.exceptionOrNull()

        assertThat(err).isInstanceOf(CommunityException::class.java)
        assertThat(err!!.message).isEqualTo("这个名字已经被用了")
    }

    @Test
    fun `返回 HTML 错误页时也给出可读的提示`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody("<html><body>502 Bad Gateway</body></html>")
        )

        val err = runCatching { api.list() }.exceptionOrNull()

        assertThat(err).isInstanceOf(CommunityException::class.java)
        assertThat(err!!.message).contains("服务器返回异常")
    }

    @Test
    fun `登录返回的 token 与用户名被取出来`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"token":"abc","user":{"id":1,"username":"站长"}}""")
        )

        val auth = api.login("站长", "123456")

        assertThat(auth.token).isEqualTo("abc")
        assertThat(auth.username).isEqualTo("站长")
        // 密码只在这一个请求体里出现过，之后连 App 自己都不该留着
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"password\":\"123456\"")
    }

    @Test
    fun `没有 token 的登录响应算失败`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"user":{"id":1,"username":"站长"}}"""))

        val err = runCatching { api.login("站长", "123456") }.exceptionOrNull()

        assertThat(err).isInstanceOf(CommunityException::class.java)
    }
}
