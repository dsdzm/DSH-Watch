package com.dshwatch.net.deepseek

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 账号登录协议的关键约束。
 *
 * 这里不测「平台会不会认这个客户端」——那得真机联网。能钉住的是**我们自己不能错**的部分：
 * PKCE 的算法（错一个字节平台就换不到 token）、回包拆包（两个 code 都要看）、
 * 余额的字段名与科学计数法（`0E-16` 是平台真实会返回的值）。
 */
class DeepSeekAccountApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = DeepSeekAccountApi(
        origin = server.url("/").toString().trimEnd('/'),
        clientVersion = "1.24.1",
        uiLanguage = { "zh-CN" },
    )

    private fun envelope(bizData: String, bizCode: Int = 0): String =
        """{"code":0,"data":{"biz_code":$bizCode,"biz_data":$bizData}}"""

    // ------------------------------------------------------------------ PKCE

    @Test
    fun `pkce 对上前面的标准向量`() {
        // RFC 7636 附录 B：这一组值所有实现都对得上，能一次抓出「用了 hex 而不是 base64url」
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertThat(DeepSeekAccountApi.challengeOf(verifier))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `verifier 是 43 位且每次不同`() {
        val a = DeepSeekAccountApi.newVerifier()
        val b = DeepSeekAccountApi.newVerifier()
        assertThat(a).hasLength(43)
        assertThat(a).isNotEqualTo(b)
        assertThat(a).matches("[A-Za-z0-9_-]{43}")
    }

    @Test
    fun `回调地址是 loopback 的固定路径`() {
        assertThat(DeepSeekAccountApi.callbackUrl())
            .isEqualTo("http://127.0.0.1:53682/oauth/callback")
    }

    // ------------------------------------------------------------------ auth_init

    @Test
    fun `auth_init 带上五个客户端头与 PKCE 参数`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                envelope(
                    """{"authorize_url":"https://platform.deepseek.com/dsh/authorize?x=1","authorize_id":"aid-1","expires_in":600}""",
                ),
            ),
        )

        val init = api().startSignIn(
            codeChallenge = "challenge",
            state = "state-1",
            redirectUri = DeepSeekAccountApi.callbackUrl(),
        )

        assertThat(init.authorizeUrl).contains("/dsh/authorize")
        assertThat(init.authorizeId).isEqualTo("aid-1")
        assertThat(init.expiresInSeconds).isEqualTo(600)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/auth-api/v0/dsh/auth_init")
        assertThat(req.getHeader("x-client-version")).isEqualTo("1.24.1")
        assertThat(req.getHeader("x-client-locale")).isEqualTo("zh_CN")
        assertThat(req.getHeader("x-client-platform")).isEqualTo("web")
        assertThat(req.getHeader("x-client-bundle-id")).isEmpty()
        assertThat(req.getHeader("x-client-timezone-offset")).isNotNull()

        /*
         * UA 必须是浏览器，不能是 OkHttp 默认值。
         *
         * 平台 WAF 会把 `okhttp/4.x` 直接判成脚本流量返回 429 —— 这就是
         * 1.24.1-beta.22 上「登录没有完成 / HTTP 429」的真正原因。
         * 这一行钉住它，避免以后有人「顺手」把 UA 去掉。
         */
        assertThat(req.getHeader("User-Agent")).isEqualTo(DeepSeekAccountApi.BROWSER_UA)
        assertThat(req.getHeader("User-Agent")).doesNotContain("okhttp")

        val body = req.body.readUtf8()
        assertThat(body).contains("\"code_challenge_method\":\"S256\"")
        assertThat(body).contains("\"redirect_uri\":\"http://127.0.0.1:53682/oauth/callback\"")
        assertThat(body).contains("\"state\":\"state-1\"")
        // web：浏览器里失败时平台会给一张可读的提示页，desktop 只回 204 空白页
        assertThat(body).contains("\"login_source\":\"web\"")
    }

    // ------------------------------------------------------------------ auth_exchange

    @Test
    fun `auth_exchange 带回 code_verifier 与设备信息`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                envelope("""{"token":"tok-abc","authorized_url":"https://platform.deepseek.com/dsh/authorized"}"""),
            ),
        )

        val token = api().exchange(
            code = "code-1",
            verifier = "verifier-1",
            redirectUri = DeepSeekAccountApi.callbackUrl(),
            deviceId = "device-1",
            deviceModel = "android-arm64-v8a",
            osVersion = "Android 14",
        )

        assertThat(token).isEqualTo("tok-abc")
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"code\":\"code-1\"")
        assertThat(body).contains("\"code_verifier\":\"verifier-1\"")
        assertThat(body).contains("\"device_id\":\"device-1\"")
        assertThat(body).contains("\"device_model\":\"android-arm64-v8a\"")
    }

    @Test
    fun `业务拒绝时报出 biz_code 而不是编一句登录失败`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("{}", bizCode = 40004)))

        val error = runCatching {
            api().exchange("c", "v", DeepSeekAccountApi.callbackUrl(), "d", "m", "o")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(DeepSeekAccountApi.AccountException::class.java)
        assertThat((error as DeepSeekAccountApi.AccountException).code)
            .isEqualTo(DeepSeekAccountApi.AccountException.Code.REJECTED)
        assertThat(error.message).contains("40004")
    }

    // ------------------------------------------------------------------ 资料 / 余额

    @Test
    fun `profile 只取昵称 头像与脱敏联系方式`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                envelope(
                    """{"id":"u-1","email":"a@b.com","mobile":"138****0000",
                       "id_profile":{"name":"肥鱼","picture":"https://x/y.png"}}""",
                ),
            ),
        )

        val profile = api().profile("tok")

        assertThat(profile.id).isEqualTo("u-1")
        assertThat(profile.name).isEqualTo("肥鱼")
        assertThat(profile.avatarUrl).isEqualTo("https://x/y.png")
        // mobile 优先于 email：平台给的是脱敏串，显示手机号比邮箱更好认
        assertThat(profile.maskedContact).isEqualTo("138****0000")
        assertThat(server.takeRequest().getHeader(DeepSeekAccountApi.TOKEN_HEADER)).isEqualTo("tok")
    }

    @Test
    fun `balance 解析充值钱包与赠金 含科学计数法`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                envelope(
                    """{"normal_wallets":[{"currency":"CNY","balance":"12.3400"}],
                       "bonus_wallets":[{"currency":"CNY","balance":"0E-16"}]}""",
                ),
            ),
        )

        val balance = api().balance("tok")

        assertThat(balance.primary?.balance).isEqualTo("12.3400")
        assertThat(balance.bonus.single().balance).isEqualTo("0E-16")
        assertThat(balance.primary?.currency).isEqualTo("CNY")
    }

    @Test
    fun `带 token 的请求被 401 时算登录过期`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401}"""))

        val error = runCatching { api().profile("stale") }.exceptionOrNull()

        assertThat((error as DeepSeekAccountApi.AccountException).code)
            .isEqualTo(DeepSeekAccountApi.AccountException.Code.EXPIRED)
    }

    @Test
    fun `回包结构不认识时给 protocol 而不是当成成功`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val error = runCatching { api().profile("tok") }.exceptionOrNull()

        assertThat((error as DeepSeekAccountApi.AccountException).code)
            .isEqualTo(DeepSeekAccountApi.AccountException.Code.PROTOCOL)
    }
}
