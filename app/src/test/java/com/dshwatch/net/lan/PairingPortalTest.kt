package com.dshwatch.net.lan

import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * 局域网门户的行为测试。
 *
 * 这里测的不是「能不能显示页面」，而是**它会不会被同网段的其他人用上**：
 * 没有口令能不能打开、域名 Host 能不能绕过、提交体乱写会不会崩。
 * 这几条任何一条漏了，这个功能就从「方便」变成「把登录凭据递给隔壁」。
 */
class PairingPortalTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private lateinit var portal: PairingPortal
    private var received: PairingPortal.Submission? = null

    private val secret: String
        get() = portal.link.value!!.substringAfter("?k=")

    @Before
    fun setUp() {
        portal = PairingPortal(
            context = null,
            json = json,
            bindHost = "127.0.0.1",
            pageOverride = "<html>ok</html>",
        )
        portal.onSubmission = { received = it }
        assertNotNull("端口没起来", portal.start())
    }

    @After
    fun tearDown() {
        portal.stop()
    }

    @Test
    fun `口令对了才给页面`() {
        val ok = get("/?k=$secret")
        assertEquals(200, ok.code)
        assertTrue(ok.body.contains("ok"))

        val denied = get("/?k=wrong")
        assertEquals(403, denied.code)
    }

    @Test
    fun `没有口令连状态都拿不到`() {
        assertEquals(403, get("/api/state").code)
    }

    @Test
    fun `域名 Host 被拒绝`() {
        // 攻击者把 evil.com 解析到手表 IP，浏览器就会带着 Host: evil.com 打进来
        val resp = raw("GET /?k=$secret HTTP/1.1\r\nHost: evil.com\r\n\r\n")
        assertEquals(421, resp.code)
    }

    @Test
    fun `IP 形式的 Host 放行`() {
        val resp = raw("GET /?k=$secret HTTP/1.1\r\nHost: 192.168.1.9:${portal.port}\r\n\r\n")
        assertEquals(200, resp.code)
    }

    @Test
    fun `提交登录方式会转交出去`() {
        val resp = post("/api/submit", """{"kind":"start","provider":"github"}""")
        assertEquals(200, resp.code)
        assertTrue(resp.body.contains("\"ok\":true"))
        val s = received
        assertTrue(s is PairingPortal.Submission.StartLogin)
        assertEquals("github", (s as PairingPortal.Submission.StartLogin).provider)
    }

    @Test
    fun `非法的 provider 不会被当成命令`() {
        val resp = post("/api/submit", """{"kind":"start","provider":"../../etc/passwd"}""")
        assertTrue(resp.body.contains("false"))
        assertNull(received)
    }

    @Test
    fun `残缺的 JSON 不会抛异常`() {
        val resp = post("/api/submit", """{"kind":"token","server":"x"}""")
        assertTrue(resp.body.contains("false"))
        assertNull(received)
    }

    @Test
    fun `状态里带出当前阶段和口令字段`() {
        val resp = get("/api/state?k=$secret")
        assertEquals(200, resp.code)
        assertTrue(resp.body.contains("\"phase\""))
    }

    @Test
    fun `关掉之后端口不再接受连接`() {
        val port = portal.port
        portal.stop()
        val failed = runCatching {
            Socket("127.0.0.1", port).use { it.soTimeout = 800 }
        }.isFailure
        assertTrue("端口还开着", failed)
        portal = PairingPortal(null, json, bindHost = "127.0.0.1", pageOverride = "ok")
        assertNotNull(portal.start())
    }

    // ------------------------------------------------------------ 工具

    private data class Resp(val code: Int, val body: String)

    private fun get(path: String): Resp = raw("GET $path HTTP/1.1\r\nHost: 127.0.0.1:${portal.port}\r\n\r\n")

    private fun post(path: String, body: String): Resp = raw(
        "POST $path?k=$secret HTTP/1.1\r\n" +
            "Host: 127.0.0.1:${portal.port}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
            body,
    )

    private fun raw(request: String): Resp = Socket("127.0.0.1", portal.port).use { socket ->
        socket.soTimeout = 5_000
        val out = PrintWriter(socket.getOutputStream(), false, Charsets.UTF_8)
        out.print(request)
        out.flush()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val status = reader.readLine().orEmpty()
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        var length = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val buffer = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        Resp(code, String(buffer, 0, read))
    }
}
