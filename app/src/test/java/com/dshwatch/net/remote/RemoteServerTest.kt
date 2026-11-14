package com.dshwatch.net.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 服务器客户端的协议测试。
 *
 * 用一个真 HTTP 服务端（MockWebServer）来跑，而不是把 OkHttp 整个 mock 掉：
 * 这一层出错的典型方式是**字段名或状态码判断错了**，mock 会把这类错误一起
 * 照抄进假设里，等于自己给自己发合格证。
 */
class RemoteServerTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ok(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    // ------------------------------------------------------------ 地址规范化

    @Test
    fun `裸域名补成 https`() {
        assertEquals("https://remote.example.com", RemoteServer.normalize("remote.example.com"))
        assertEquals("https://remote.example.com", RemoteServer.normalize("https://remote.example.com/"))
    }

    @Test
    fun `局域网 IP 允许明文`() {
        assertEquals("http://192.168.1.5:8080", RemoteServer.normalize("http://192.168.1.5:8080"))
        assertEquals("http://127.0.0.1:3000", RemoteServer.normalize("127.0.0.1:3000"))
    }

    @Test
    fun `公网地址拒绝明文`() {
        // 明文把设备令牌摊给同一个 Wi-Fi 上的所有人
        assertNull(RemoteServer.normalize("http://remote.example.com"))
    }

    @Test
    fun `带路径或查询的地址不算源`() {
        assertNull(RemoteServer.normalize("https://example.com/api"))
        assertNull(RemoteServer.normalize("https://example.com?x=1"))
        assertNull(RemoteServer.normalize("https://user:pw@example.com"))
    }

    @Test
    fun `空地址返回 null`() {
        assertNull(RemoteServer.normalize("   "))
    }

    // ------------------------------------------------------------ 协议

    @Test
    fun `二维码会话解析出 qrId 与授权地址`() = runBlocking {
        ok("""{"qrId":"${"a".repeat(24)}","scanUrl":"$baseUrl/pair/qr","expiresIn":120,"provider":"github"}""")
        val api = RemoteServer(baseUrl)
        val session = api.startQr(RemoteProvider("github", "GitHub", "/api/v1/auth/oauth/qr/start?provider=github"))
        assertEquals(24, session.qrId.length)
        assertEquals("$baseUrl/pair/qr", session.scanUrl)
        assertEquals(120, session.expiresInSeconds)

        val request = server.takeRequest()
        assertEquals("/api/v1/auth/oauth/qr/start?provider=github", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun `授权地址指向别的站点会被拒绝`() = runBlocking {
        // 服务端如果（被攻破或出 bug）返回了外部地址，手表会把令牌送到那里
        ok("""{"qrId":"${"a".repeat(24)}","scanUrl":"https://evil.example.com/steal","expiresIn":120}""")
        try {
            RemoteServer(baseUrl).startQr(RemoteProvider("github", "GitHub", "/api/v1/auth/oauth/qr/start?provider=github"))
            fail("站外地址必须被拒绝")
        } catch (expected: RemoteError.Protocol) {
            assertTrue(expected.message.orEmpty().contains("站点"))
        }
    }

    @Test
    fun `轮询的三种状态`() = runBlocking {
        val api = RemoteServer(baseUrl)
        val qr = "b".repeat(24)

        ok("""{"status":"pending"}""")
        assertTrue(api.pollQr(qr) is QrPoll.Pending)

        ok("""{"status":"expired"}""")
        assertTrue(api.pollQr(qr) is QrPoll.Expired)

        ok("""{"status":"complete","token":"${"t".repeat(40)}"}""")
        val done = api.pollQr(qr)
        assertTrue(done is QrPoll.Complete)
        assertEquals(40, (done as QrPoll.Complete).accountToken.length)

        assertEquals("/api/v1/auth/oauth/qr/$qr", server.takeRequest().path)
    }

    @Test
    fun `complete 但没令牌时继续等而不是报错`() = runBlocking {
        ok("""{"status":"complete"}""")
        assertTrue(RemoteServer(baseUrl).pollQr("c".repeat(24)) is QrPoll.Pending)
    }

    @Test
    fun `注册设备带上账号令牌并解析凭据`() = runBlocking {
        ok(
            """
            {"accessToken":"${"a".repeat(32)}","accessTokenExpiresAt":1790000000000,
             "refreshToken":"${"r".repeat(32)}","refreshTokenExpiresAt":1800000000000}
            """.trimIndent()
        )
        val tokens = RemoteServer(baseUrl).register(
            RemoteDevice(
                deviceId = "2f1c4a9e-1111-4222-8333-444455556666",
                name = "Watch",
                platform = "wearos",
                identityKey = NoiseIk.publicOf(NoiseIk.newPrivateKey()).toBase64Url(),
                clientVersion = "1.23.0",
            ),
            accountToken = "account-token-123456",
        )
        assertEquals(32, tokens.accessToken.length)
        assertEquals(1800000000000L, tokens.refreshExpiresAt)

        val request = server.takeRequest()
        assertEquals("Bearer account-token-123456", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"role\":\"client\""))
    }

    @Test
    fun `凭据缺字段按协议错处理`() = runBlocking {
        ok("""{"accessToken":"short"}""")
        try {
            RemoteServer(baseUrl).register(
                RemoteDevice("2f1c4a9e-1111-4222-8333-444455556666", "W", "wearos", "k", "1.0"),
                "account-token-123456",
            )
            fail("不完整的凭据不该被接受")
        } catch (expected: RemoteError.Protocol) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `401 映射成登录失效`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        try {
            RemoteServer(baseUrl).hosts("expired")
            fail("401 应该抛 Unauthorized")
        } catch (expected: RemoteError.Unauthorized) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `主机列表只留 host 角色`() = runBlocking {
        ok(
            """
            {"items":[
              {"deviceId":"h1","name":"MacBook","platform":"macos","role":"host"},
              {"deviceId":"c1","name":"Phone","platform":"ios","role":"client"}
            ]}
            """.trimIndent()
        )
        val hosts = RemoteServer(baseUrl).hosts("token-1234567890")
        assertEquals(1, hosts.size)
        assertEquals("MacBook", hosts.first().name)
    }

    @Test
    fun `服务器挂了给网络错而不是崩`() = runBlocking {
        val api = RemoteServer("http://127.0.0.1:1")
        try {
            api.hosts("token-1234567890")
            fail("连不上应抛网络错")
        } catch (expected: RemoteError) {
            assertNotNull(expected.message)
        }
    }
}
