package com.dshwatch.net.dshweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接层的纯逻辑测试。
 *
 * ## 守的是什么
 *
 * 用户反馈的「一直连不上」有两个层次：一是原来打的是不存在的端点（协议整个错），
 * 二是就算打对端点，cookie 名、authority、启动地址解析任何一处算错，
 * 服务端都只会回 401 —— 而 401 看起来和「密码错了」一模一样。
 *
 * 这几条把「算得对不对」变成可执行的断言，不用每次连不上都去猜。
 */
class DshWebTest {

    // ---------------- 启动地址 ----------------

    @Test
    fun `能认出 dsh web 打印的整行`() {
        val launch = DshWeb.parseLaunchUrl("dsh web: http://127.0.0.1:3080/?token=abc123")
        assertNotNull(launch)
        assertEquals("http://127.0.0.1:3080", launch!!.host.baseUrl)
        assertEquals("abc123", launch.token)
        assertEquals("127.0.0.1:3080", launch.host.authority)
    }

    @Test
    fun `能认出裸地址与局域网 IP`() {
        val launch = DshWeb.parseLaunchUrl("http://192.168.1.20:3080/?token=xyz")
        assertEquals("192.168.1.20:3080", launch!!.host.authority)
        assertEquals("http://192.168.1.20:3080", launch.host.baseUrl)
    }

    @Test
    fun `没有 token 的地址不算启动地址`() {
        // 这种地址换不了 cookie。认了它，用户会在「登录」这一步收到一句看不懂的 401
        assertNull(DshWeb.parseLaunchUrl("http://127.0.0.1:3080/"))
        assertNull(DshWeb.parseLaunchUrl("随便一段话"))
    }

    @Test
    fun `baseUrl 里不能残留 token`() {
        val launch = DshWeb.parseLaunchUrl("http://10.0.0.2:3080/?token=secret")
        assertFalse(launch!!.host.baseUrl.contains("secret"))
        assertFalse(launch.host.baseUrl.contains("?"))
    }

    @Test
    fun `默认端口不写进 authority`() {
        // 服务端按 authority 区分 cookie；80/443 写不写必须是同一个身份
        assertEquals("example.com", DshWeb.authorityOf(java.net.URI("http://example.com/")))
        assertEquals("example.com", DshWeb.authorityOf(java.net.URI("http://example.com:80/")))
        assertEquals("example.com", DshWeb.authorityOf(java.net.URI("https://example.com:443/")))
        assertEquals("example.com:8443", DshWeb.authorityOf(java.net.URI("https://example.com:8443/")))
    }

    @Test
    fun `IPv6 地址带方括号`() {
        assertEquals("[::1]:3080", DshWeb.authorityOf(java.net.URI("http://[::1]:3080/")))
    }

    // ---------------- cookie ----------------

    @Test
    fun `cookie 名与服务端算法一致`() {
        /*
         * 这两个值是独立算出来的参考值（sha256 后 base64url 去填充）。
         * 写死而不是「用同样的算法再算一遍」：后者无论实现怎么改都会通过，
         * 等于没测 —— 而这里一旦算法走偏，带上的 cookie 就永远是错的。
         */
        assertEquals(
            "dsh-auth-VPhEEcLKeqRDBoBalzN2Nm7CnfxKhLE00pKIDWxt1sw",
            DshWeb.cookieName("127.0.0.1:3080"),
        )
        assertEquals(
            "dsh-auth-o3mm9u6vuaVeN4wRgDTidR5oL6ufLTCrE9ISVYbOGUc",
            DshWeb.cookieName("example.com"),
        )
    }

    @Test
    fun `解析 Set-Cookie 并读出有效期`() {
        val raw = "dsh-auth-abc=v1.p.s; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict"
        val cookie = DshWeb.parseSetCookie(raw, "127.0.0.1:3080", now = 1_700_000_000_000L)
        assertNotNull(cookie)
        assertEquals("dsh-auth-abc", cookie!!.name)
        assertEquals("v1.p.s", cookie.value)
        assertEquals("dsh-auth-abc=v1.p.s", cookie.header)
        assertEquals(1_700_000_000_000L + 2_592_000_000L, cookie.expiresAt)
        assertFalse(cookie.isExpired(1_700_000_000_000L))
    }

    @Test
    fun `不是 dsh 的 cookie 一律丢掉`() {
        // 中间有代理时会带回自己的 cookie，混进来就会顶掉真正的登录态
        assertNull(DshWeb.parseSetCookie("sessionid=abc; Path=/", "a:1"))
        assertNull(DshWeb.parseSetCookie("garbage", "a:1"))
    }

    // ---------------- 地址拼装 ----------------

    @Test
    fun `RPC 与 mux 地址按协议拼`() {
        val host = DshWebHost("http://192.168.1.5:3080", "192.168.1.5:3080")
        assertEquals("http://192.168.1.5:3080/api/session/list", DshWeb.rpcUrl(host, "session/list"))
        assertEquals("ws://192.168.1.5:3080/api/remote.mux", DshWeb.muxUrl(host))

        val tls = DshWebHost("https://dsh.example.com", "dsh.example.com")
        assertEquals("wss://dsh.example.com/api/remote.mux", DshWeb.muxUrl(tls))
    }

    @Test
    fun `登录地址带 token 查询参数`() {
        val host = DshWebHost("http://127.0.0.1:3080", "127.0.0.1:3080")
        assertEquals("http://127.0.0.1:3080/?token=a%2Bb", DshWeb.loginUrl(host, "a+b"))
    }
}
