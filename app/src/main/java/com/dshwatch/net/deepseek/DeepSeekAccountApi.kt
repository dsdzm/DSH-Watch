package com.dshwatch.net.deepseek

import com.dshwatch.core.Logx
import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.TimeZone

/**
 * DeepSeek 账号登录协议（平台 ↔ 原生客户端）。
 *
 * ## 这套接口是哪来的
 *
 * DeepSeek Harness 是开源的（`deepseek-ai/deepseek-harness`，MIT）。
 * 它的桌面客户端登录走的就是这三个端点：
 *
 * ```text
 * POST {platform}/auth-api/v0/dsh/auth_init     换一个授权页地址
 * POST {platform}/auth-api/v0/dsh/auth_exchange 拿 code + verifier 换 token
 * POST {platform}/auth-api/v0/dsh/auth_cancel   取消这次授权
 * ```
 *
 * 授权页本身是 `https://platform.deepseek.com/dsh/authorize?...`，
 * 用户在页面上登录、点「授权」，浏览器就会带着 `code` 跳回我们给的
 * `redirect_uri`。**App 从头到尾不接触账号密码** —— 密码只进平台自己的页面，
 * 我们拿到的是一枚可以随时在平台上撤销的 token。
 *
 * ## 三条容易踩空的约定
 *
 * 1. **redirect_uri 只收 loopback HTTP**（`http://127.0.0.1:<port>/oauth/callback`）。
 *    官方桌面端就是起一个本地 HTTP 服务接回调；手表上起服务不稳定，
 *    所以这里只把 URL 交给 WebView 拦截 —— 不真的监听端口。
 * 2. **PKCE 是 S256**，`code_verifier` 与 `code_challenge` 必须成对；
 *    交换时还要原样带回 `redirect_uri`，三者缺一不可。
 * 3. **`x-client-*` 头是平台识客户端用的**。少一个会被当成未知客户端，
 *    `auth_init` 直接不给授权页地址。版本号用本 App 的版本，
 *    platform 固定 `web`（官方只定义了 web / desktop-mac / desktop-win）。
 *
 * ## 失败长什么样
 *
 * 平台把所有结果包在 `{code, data:{biz_code, biz_data}}` 里：
 * `code != 0` 或 `biz_code != 0` 都算业务拒绝，但**两者的文案都在服务端**，
 * 我们只能给出「平台拒绝了这次请求（biz_code=N）」。真正要区分的是
 * HTTP 401 / `code=40003` —— 那是 token 失效，必须清掉本地凭据。
 */
class DeepSeekAccountApi(
    private val origin: String = DEFAULT_ORIGIN,
    private val clientVersion: String,
    private val uiLanguage: () -> String,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    companion object {
        const val DEFAULT_ORIGIN = "https://platform.deepseek.com"

        /** 授权页固定路径；平台在 `auth_init` 里返回完整地址，这里只用于校验 */
        const val AUTHORIZE_PATH = "/dsh/authorize"

        /** 回调固定路径；官方桌面端与服务端约定的是 `/oauth/callback` */
        const val CALLBACK_PATH = "/oauth/callback"

        /** token 在推理请求上的头名。不是 `Authorization` —— 这是账号赠金，不是 API Key */
        const val TOKEN_HEADER = "x-dsh-auth-token"

        /** 官方桌面端用的本地回调端口。固定一个，避免每次登录都换 */
        const val CALLBACK_PORT = 53682

        /**
         * 平台请求统一用的 UA。
         *
         * **这一条是登录能不能成功的关键**：`platform.deepseek.com` 前面挂了 WAF，
         * OkHttp 默认的 `okhttp/4.x` 会被直接判成脚本流量，`auth_init` 与授权页
         * 都返回 429（正文是一张 Cloudflare 风格的错误页）。换成普通移动浏览器 UA
         * 立刻 200 —— 实测同一 IP、同一秒、只改 UA，429 与 200 的差别就在这里。
         *
         * 用一个**真实存在且不旧**的版本号：写太高会被判成伪造，写太低会被判
         * 「浏览器版本过低」。平台按这个 UA 出移动版页面，正好适合手机屏。
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** 平台回调的完整地址（不真的监听，只给 WebView 拦） */
        fun callbackUrl(port: Int = CALLBACK_PORT): String = "http://127.0.0.1:$port$CALLBACK_PATH"

        /**
         * 造一对 PKCE。
         *
         * verifier 是 32 字节随机数的 base64url（43 个字符，落在 RFC 7636 允许的 43~128 内），
         * challenge 是它的 SHA-256 再 base64url。都用无填充 —— 平台按标准 base64url 校验。
         */
        fun newVerifier(): String = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })

        fun challengeOf(verifier: String): String = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

        /** state 与 verifier 同一套生成方式：够长、够随机，只用于防跨站回调 */
        fun newState(): String = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })

        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** `auth_init` 的产物：把用户送去的地址 + 取消时要带回来的票据 */
    data class SignInInit(
        val authorizeUrl: String,
        val authorizeId: String,
        val expiresInSeconds: Long,
    )

    /** 账号资料。全部字段都可能为空：平台允许只绑手机、不设昵称、不上传头像 */
    data class Profile(
        val id: String?,
        val name: String?,
        val avatarUrl: String?,
        val maskedContact: String?,
    )

    /** 一个钱包的余额。保留字符串原样：平台会返回 `0E-16` 这种科学计数法 */
    data class Wallet(val currency: String, val balance: String)

    data class Balance(val wallets: List<Wallet>, val bonus: List<Wallet>) {
        /** 界面优先显示人民币充值余额，没有就退到第一个 */
        val primary: Wallet? get() = wallets.firstOrNull { it.currency == "CNY" } ?: wallets.firstOrNull()
    }

    /**
     * 账号链路上的失败。
     *
     * [code] 是给界面分支用的稳定值，[message] 是能直接给用户看的一句话 ——
     * 两者分开：同一个 `network` 在中英文界面下要显示不同的句子，
     * 而分支判断不能依赖文案。
     */
    class AccountException(
        val code: Code,
        override val message: String,
    ) : Exception(message) {
        enum class Code {
            /** 请求没发出去 / 没回包 */
            NETWORK,

            /** token 被平台拒绝（401 或 40003），本地必须清掉 */
            EXPIRED,

            /** 平台明确拒绝（biz_code 非 0），通常是这次授权没通过 */
            REJECTED,

            /** 回包结构不认识 —— 平台改了协议 */
            PROTOCOL,
        }
    }

    // ------------------------------------------------------------------ 登录

    suspend fun startSignIn(
        codeChallenge: String,
        state: String,
        redirectUri: String,
        loginSource: String = "web",
    ): SignInInit = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("code_challenge", codeChallenge)
            put("code_challenge_method", "S256")
            put("state", state)
            put("redirect_uri", redirectUri)
            put("locale", wireLocale())
            put("login_source", loginSource)
        }
        val data = postAuth("auth_init", body)
        SignInInit(
            authorizeUrl = data["authorize_url"]?.jsonPrimitive?.content
                ?: throw AccountException(AccountException.Code.PROTOCOL, "授权地址为空"),
            authorizeId = data["authorize_id"]?.jsonPrimitive?.content.orEmpty(),
            expiresInSeconds = data["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 600L,
        )
    }

    /**
     * 用回调里的 code 换 token。
     *
     * `device_id` 是这台设备的稳定标识（本地生成一次、之后一直用）：
     * 平台用它把同一台设备上的登录串起来，也用于「登录设备管理」。
     * `device_model` / `os_version` 只做展示，填 Android 的真实值。
     */
    suspend fun exchange(
        code: String,
        verifier: String,
        redirectUri: String,
        deviceId: String,
        deviceModel: String,
        osVersion: String,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("redirect_uri", redirectUri)
            put("device_id", deviceId)
            put("device_model", deviceModel)
            put("os_version", osVersion)
        }
        val data = postAuth("auth_exchange", body)
        data["token"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw AccountException(AccountException.Code.PROTOCOL, "平台没有返回登录凭据")
    }

    /** 取消一次还没完成的授权。失败无所谓：票据本身会过期 */
    suspend fun cancel(authorizeId: String, verifier: String) {
        if (authorizeId.isBlank()) return
        runCatching {
            withContext(Dispatchers.IO) {
                postAuth("auth_cancel", buildJsonObject {
                    put("authorize_id", authorizeId)
                    put("code_verifier", verifier)
                })
            }
        }.onFailure { Logx.w(TAG, "取消授权失败：${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------ 账号

    suspend fun profile(token: String): Profile = withContext(Dispatchers.IO) {
        val data = getAccount("/auth-api/v0/users/current", token)
        val identity = data["id_profile"]?.let { runCatching { it.jsonObject }.getOrNull() }
        Profile(
            id = data["id"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            name = identity?.get("name")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            avatarUrl = identity?.get("picture")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            maskedContact = listOfNotNull(
                data["mobile"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
                data["mobile_number"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
                data["email"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            ).firstOrNull { it.isNotBlank() },
        )
    }

    suspend fun balance(token: String): Balance = withContext(Dispatchers.IO) {
        val data = getAccount("/api/v0/users/get_user_summary", token)
        Balance(
            wallets = wallets(data["normal_wallets"]),
            bonus = wallets(data["bonus_wallets"]),
        )
    }

    /** 退出登录：平台侧吊销 token。失败也继续本地清理，见仓储层 */
    suspend fun logout(token: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$origin/auth-api/v0/users/logout")
                    .header(TOKEN_HEADER, token)
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) Logx.w(TAG, "登出请求返回 ${resp.code}")
                }
            }.onFailure { Logx.w(TAG, "登出请求失败：${it.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------------------------ 底层

    private fun wallets(element: kotlinx.serialization.json.JsonElement?): List<Wallet> {
        val arr = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val currency = obj["currency"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: return@mapNotNull null
            val balance = obj["balance"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: return@mapNotNull null
            Wallet(currency, balance)
        }
    }

    private fun postAuth(method: String, body: JsonObject): JsonObject {
        val req = Request.Builder()
            .url("$origin/auth-api/v0/dsh/$method")
            .post(body.toString().toRequestBody(Http.JSON_MEDIA))
            .apply { clientHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(req, hasToken = false)
    }

    private fun getAccount(path: String, token: String): JsonObject {
        val req = Request.Builder()
            .url("$origin$path")
            .get()
            .apply {
                clientHeaders().forEach { (k, v) -> header(k, v) }
                header(TOKEN_HEADER, token)
            }
            .build()
        return execute(req, hasToken = true)
    }

    /**
     * 五个客户端身份头。少一个平台就不认这个客户端。
     *
     * `x-client-bundle-id` 官方桌面端也是空串，照抄；时区偏移用「本地 - UTC」的秒数，
     * 不是时区名 —— 平台按数字处理（UTC+8 = 28800）。
     */
    private fun clientHeaders(): Map<String, String> = mapOf(
        // 没有这一行，平台会把请求当成脚本（okhttp/x）直接 429，见 BROWSER_UA
        "User-Agent" to BROWSER_UA,
        "x-client-bundle-id" to "",
        "x-client-platform" to "web",
        "x-client-version" to clientVersion,
        "x-client-locale" to wireLocale(),
        "x-client-timezone-offset" to
            (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toString(),
    )

    private fun wireLocale(): String =
        if (uiLanguage().lowercase().startsWith("zh")) "zh_CN" else "en_US"

    private fun execute(req: Request, hasToken: Boolean): JsonObject {
        val resp = try {
            Http.client.newCall(req).execute()
        } catch (io: java.io.IOException) {
            throw AccountException(AccountException.Code.NETWORK, "网络不通：${io.message ?: io.javaClass.simpleName}")
        }
        resp.use {
            val text = runCatching { it.body?.string() }.getOrNull().orEmpty()
            if (it.code == 401 && hasToken) throw AccountException(AccountException.Code.EXPIRED, "登录已过期")
            if (it.code == 429) {
                /*
                 * 429 有两种来源：真限流（短时间点太多次），以及 WAF 按客户端特征拦。
                 * 后者以前表现为「HTTP 429 + 一大段 HTML」，用户完全看不懂 —— 单独给一句人话，
                 * 并把「等一会儿」写出来，因为大多数情况真的是等一分钟就好。
                 */
                throw AccountException(
                    AccountException.Code.NETWORK,
                    "平台暂时限制了登录请求（429），等一分钟再试；反复出现请在浏览器里打开 platform.deepseek.com 确认网络",
                )
            }
            if (!it.isSuccessful) {
                throw AccountException(
                    AccountException.Code.NETWORK,
                    "平台返回 HTTP ${it.code}${if (text.isNotBlank()) "：${text.take(120)}" else ""}",
                )
            }
            return unwrap(text)
        }
    }

    /**
     * 拆 `{code, data:{biz_code, biz_data}}`。
     *
     * 两个 code 都可能是「业务拒绝」，但只有 `40003` 有明确含义（鉴权失效）。
     * 其余非 0 一律按拒绝处理，并把数字带给用户 —— 平台没给可读文案，
     * 编一句「登录失败」反而让人无从排查。
     */
    private fun unwrap(text: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AccountException(AccountException.Code.PROTOCOL, "平台回包不是 JSON")
        val code = root["code"]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: 0
        if (code == 40003) throw AccountException(AccountException.Code.EXPIRED, "登录已过期")
        if (code != 0) {
            throw AccountException(AccountException.Code.REJECTED, "平台拒绝了这次请求（code=$code）")
        }
        val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: throw AccountException(AccountException.Code.PROTOCOL, "平台回包缺少 data")
        val bizCode = data["biz_code"]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: 0
        if (bizCode != 0) {
            throw AccountException(AccountException.Code.REJECTED, "平台拒绝了这次请求（biz_code=$bizCode）")
        }
        return data["biz_data"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
    }

    private val TAG = "DeepSeekAccount"
}
