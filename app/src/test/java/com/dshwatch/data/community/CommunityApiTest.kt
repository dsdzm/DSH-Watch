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

    @Test
    fun `线上地址必须是 https`() {
        // 登录和投稿都带 Bearer token，走明文等于把 token 抄在明信片上。
        // 证书已经装好（ZeroSSL，链完整），没有任何理由再退回 http。
        assertThat(CommunityApi.BASE_URL).startsWith("https://")
        assertThat(CommunityApi.BASE_URL).contains("dshwatch.vxni.ink")
        assertThat(CommunityApi.BASE_URL).doesNotContain("http://")
    }

    // ------------------------------------------------------ 评论 / 评分 / 举报

    @Test
    fun `评论列表解析作者与正文`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"items":[{"id":3,"author":"阿糖","body":"写得好","createdAt":1795847119}],"page":0,"hasMore":false}"""
            )
        )

        val p = api.comments(4)

        assertThat(p.items).hasSize(1)
        assertThat(p.items[0].author).isEqualTo("阿糖")
        assertThat(p.items[0].body).isEqualTo("写得好")
        assertThat(p.hasMore).isFalse()
        assertThat(server.takeRequest().path).contains("a=comments")
    }

    @Test
    fun `发评论带上卡片 id 和正文`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"comment":{"id":9,"author":"阿糖","body":"不错","createdAt":1795847119}}"""
            )
        )

        val c = api.postComment("tok", 4, "  不错  ")

        assertThat(c.id).isEqualTo(9L)
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"id\":4")
        // 前后空格在这里没意义，服务端也会 trim，客户端先做掉省一次往返
        assertThat(body).contains("\"body\":\"不错\"")
    }

    @Test
    fun `评分返回新的均分与人数`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"rating":4.5,"ratingCount":2,"myScore":5}""")
        )

        val r = api.rate("tok", 4, 5)

        assertThat(r.rating).isEqualTo(4.5)
        assertThat(r.count).isEqualTo(2)
        assertThat(r.myScore).isEqualTo(5)
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"score\":5")
    }

    @Test
    fun `评分越界会被夹到 1 到 5`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"rating":5,"ratingCount":1,"myScore":5}"""))

        api.rate("tok", 4, 99)

        assertThat(server.takeRequest().body.readUtf8()).contains("\"score\":5")
    }

    @Test
    fun `详情带 token 时读得到我的评分与作者标记`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"item":{"id":4,"kind":"tavern","name":"鲸鱼娘","downloads":1,"likes":2,
                   "rating":4.5,"ratingCount":2,"commentCount":3,"createdAt":1,"updatedAt":1,
                   "myScore":4,"mine":true,"card":{"kind":"tavern","name":"鲸鱼娘","persona":"你是鲸鱼娘"}}}"""
            )
        )

        val d = api.detail(4, token = "tok")

        assertThat(d.myScore).isEqualTo(4)
        assertThat(d.mine).isTrue()
        assertThat(d.meta.rating).isEqualTo(4.5)
        assertThat(d.meta.commentCount).isEqualTo(3)
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer tok")
    }

    @Test
    fun `不登录看详情时评分与作者标记是默认值`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"item":{"id":4,"kind":"tavern","name":"x","downloads":0,"likes":0,"createdAt":1,"updatedAt":1,
                   "card":{"kind":"tavern","name":"x","persona":"p"}}}"""
            )
        )

        val d = api.detail(4)

        assertThat(d.myScore).isEqualTo(0)
        assertThat(d.mine).isFalse()
        assertThat(d.meta.rating).isEqualTo(0.0)
        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `举报只发理由，不把补充说明塞成空串`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"reported":true}"""))

        api.report("tok", 4, "内容不适")

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"reason\":\"内容不适\"")
        assertThat(body).doesNotContain("\"detail\"")
    }

    @Test
    fun `撤回投稿是一个带 id 的 POST`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"deleted":true}"""))

        api.deleteOwn("tok", 7)

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).contains("a=delete")
        assertThat(req.body.readUtf8()).contains("\"id\":7")
    }

    // ------------------------------------------------ 邮箱 / 回复 / 消息

    @Test
    fun `注册把邮箱一并发出去`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"token":"t1","user":{"id":1,"username":"阿糖","email":"a@b.com"}}"""
            )
        )

        val auth = api.register("阿糖", "secret1", "a@b.com")

        assertThat(auth.token).isEqualTo("t1")
        assertThat(auth.email).isEqualTo("a@b.com")
        val req = server.takeRequest()
        assertThat(req.path).contains("a=register")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"email\":\"a@b.com\"")
    }

    @Test
    fun `登录不发邮箱字段`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"token":"t1","user":{"id":1,"username":"阿糖"}}""")
        )

        api.login("阿糖", "secret1")

        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("\"email\"")
    }

    @Test
    fun `评论解析回复与点赞状态`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"ok":true,"items":[
                  {"id":3,"author":"阿糖","body":"写得好","createdAt":1795847119,
                   "likes":2,"liked":true,"mine":false,
                   "replies":[
                     {"id":4,"author":"站长","body":"谢谢","createdAt":1795847222,
                      "likes":0,"liked":false,"mine":true,"parentId":3}
                   ]}
                ],"page":0,"hasMore":true}
                """.trimIndent()
            )
        )

        val p = api.comments(4, token = "tok")

        assertThat(p.items[0].likes).isEqualTo(2)
        assertThat(p.items[0].liked).isTrue()
        assertThat(p.items[0].replies).hasSize(1)
        assertThat(p.items[0].replies[0].parentId).isEqualTo(3L)
        assertThat(p.items[0].replies[0].mine).isTrue()
        // 带 token 才会返回 liked / mine
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer tok")
    }

    @Test
    fun `回复评论带上 parent id`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"comment":{"id":9,"author":"阿糖","body":"回你","createdAt":1,"parentId":3}}"""
            )
        )

        val c = api.postComment("tok", 4, "回你", parentId = 3)

        assertThat(c.parentId).isEqualTo(3L)
        assertThat(server.takeRequest().body.readUtf8()).contains("\"parent\":3")
    }

    @Test
    fun `评论点赞是 clike 且返回新数字`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"likes":7,"liked":true}"""))

        val likes = api.likeComment("tok", 12)

        assertThat(likes).isEqualTo(7)
        val req = server.takeRequest()
        assertThat(req.path).contains("a=clike")
        assertThat(req.body.readUtf8()).contains("\"id\":12")
    }

    @Test
    fun `消息列表读出未读数与内容`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"ok":true,"unread":2,"items":[
                  {"id":5,"kind":"clike","cardId":4,"commentId":3,"actor":"阿糖",
                   "body":"写得好","createdAt":1795847119,"read":false}
                ]}
                """.trimIndent()
            )
        )

        val p = api.notices("tok")

        assertThat(p.unread).isEqualTo(2)
        assertThat(p.items[0].actor).isEqualTo("阿糖")
        assertThat(p.items[0].read).isFalse()
    }

    @Test
    fun `标记全部已读不带 id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"unread":0}"""))

        val left = api.readNotices("tok")

        assertThat(left).isEqualTo(0)
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).doesNotContain("\"id\"")
    }

    @Test
    fun `详情读出我赞过没有`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"item":{"id":4,"kind":"tavern","name":"x","downloads":0,"likes":2,
                   "createdAt":1,"updatedAt":1,"myScore":0,"liked":true,"mine":false,
                   "card":{"kind":"tavern","name":"x","persona":"p"}}}"""
            )
        )

        val d = api.detail(4, token = "tok")

        assertThat(d.liked).isTrue()
        assertThat(d.myScore).isEqualTo(0)
    }
}
