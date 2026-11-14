package com.dshwatch.net.remote

import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

/**
 * DSH Remote 服务器（ds-harness-remote）的 REST 客户端。
 *
 * 端点与校验规则对照 dshgo 的 `packages/dsh_remote/lib/src/server/server_api.dart`，
 * 不是猜的：每一条返回都要按服务端的约束验一遍，验不过就报协议错，
 * 而不是把一个空值带进后面的流程（那会表现成「登录成功但连不上」）。
 */
class RemoteServer(
    baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** 规范化后的源地址，例如 `https://remote.example.com` */
    val origin: String = normalize(baseUrl) ?: throw RemoteError.InvalidUrl("服务器地址不对：$baseUrl")

    // ---------------------------------------------------------------- 登录

    /** `GET /api/v1/auth/oauth/providers`；旧服务器没有这个端点时给内置清单 */
    suspend fun providers(): List<RemoteProvider> = get("/api/v1/auth/oauth/providers") { root ->
        val items = root["items"] as? JsonArray ?: return@get builtinProviders()
        items.mapNotNull { raw ->
            val obj = raw as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val name = obj.str("displayName") ?: id
            val qr = obj.str("qrStartUrl")
            // 二维码登录路径必须是站内相对路径，否则手表会把令牌送到别人的域名
            val safeQr = qr?.takeIf { it.startsWith("/") && !it.startsWith("//") }
            RemoteProvider(id, name, safeQr ?: "/api/v1/auth/oauth/qr/start?provider=$id")
        }.ifEmpty { builtinProviders() }
    }

    /** `POST <qrStartUrl>` → `{qrId, scanUrl, expiresIn, provider?}` */
    suspend fun startQr(provider: RemoteProvider): QrSession = post(provider.qrStartPath, buildJsonObject { }) { root ->
        val qrId = root.str("qrId")?.takeIf { it.length >= 20 }
            ?: throw RemoteError.Protocol("服务器没有返回有效的二维码会话")
        val scan = root.str("scanUrl")?.let { sameOrigin(it) }
            ?: throw RemoteError.Protocol("扫描地址不是同一个站点，已拒绝")
        val ttl = root["expiresIn"]?.jsonPrimitive?.intOrNull ?: 0
        if (ttl <= 0) throw RemoteError.Protocol("二维码有效期无效")
        val echoed = root.str("provider")
        if (echoed != null && echoed != provider.id) {
            throw RemoteError.Protocol("服务器回的是别的登录方式：$echoed")
        }
        QrSession(qrId = qrId, scanUrl = scan, expiresInSeconds = ttl, provider = provider.id)
    }

    /**
     * `GET /api/v1/auth/oauth/qr/{qrId}` — 令牌只返回一次。
     *
     * 返回 `complete` 而拿不到令牌时按「继续等」处理：服务端可能在
     * 状态切换的那一瞬还没写好令牌，急着重试一次比直接报错更接近真相。
     */
    suspend fun pollQr(qrId: String): QrPoll = get("/api/v1/auth/oauth/qr/${urlEncode(qrId)}") { root ->
        when (root.str("status")) {
            "pending" -> QrPoll.Pending
            "expired" -> QrPoll.Expired
            "complete" -> root.str("token")
                ?.takeIf { it.length >= 16 }
                ?.let { QrPoll.Complete(it) }
                ?: QrPoll.Pending
            else -> throw RemoteError.Protocol("服务器返回了无法识别的扫码状态")
        }
    }

    /** 自建服务器常见的邮箱密码登录；返回的是**账号令牌**，只能用来注册设备 */
    suspend fun loginWithPassword(email: String, password: String): String =
        post("/api/v1/auth/login", buildJsonObject { put("email", email.trim()); put("password", password) }) { root ->
            root.str("token")?.takeIf { it.length >= 16 }
                ?: throw RemoteError.Protocol("服务器没有返回登录令牌")
        }

    // ---------------------------------------------------------------- 设备

    /** `POST /api/v1/devices/register`，要带**账号令牌** */
    suspend fun register(device: RemoteDevice, accountToken: String): RemoteTokens {
        val body = buildJsonObject {
            put("v", 1)
            put("device", buildJsonObject {
                put("deviceId", device.deviceId)
                put("name", device.name)
                put("role", "client")
                put("platform", device.platform)
                put("identityKey", device.identityKey)
                put("clientVersion", device.clientVersion)
            })
        }
        return post("/api/v1/devices/register", body, bearer = accountToken) { parseTokens(it) }
    }

    /** `POST /api/v1/auth/refresh`；刷新令牌每次轮换，旧的再用会吊销整个令牌族 */
    suspend fun refresh(deviceId: String, refreshToken: String): RemoteTokens =
        post(
            "/api/v1/auth/refresh",
            buildJsonObject { put("deviceId", deviceId); put("refreshToken", refreshToken) },
        ) { parseTokens(it) }

    /** `GET /api/v1/devices`：同账号下我能连的主机 */
    suspend fun hosts(accessToken: String): List<RemoteHost> = get("/api/v1/devices", bearer = accessToken) { root ->
        val items = root["items"] as? JsonArray ?: return@get emptyList()
        items.mapNotNull { raw ->
            val obj = raw as? JsonObject ?: return@mapNotNull null
            if (obj.str("role") != "host") return@mapNotNull null
            val id = obj.str("deviceId") ?: return@mapNotNull null
            RemoteHost(
                deviceId = id,
                name = obj.str("name") ?: "电脑",
                platform = obj.str("platform") ?: "",
                online = obj["online"]?.let { (it as? JsonPrimitive)?.content == "true" },
                // 握手时要把身份公钥钉死；没有它就等于没有 pinning
                identityKey = obj.str("identityKey") ?: "",
            )
        }
    }

    // ---------------------------------------------------------------- 传输

    private suspend fun <T> get(
        path: String,
        bearer: String? = null,
        parse: (JsonObject) -> T,
    ): T = call("GET", path, null, bearer, parse)

    private suspend fun <T> post(
        path: String,
        body: JsonElement,
        bearer: String? = null,
        parse: (JsonObject) -> T,
    ): T = call("POST", path, body, bearer, parse)

    private suspend fun <T> call(
        method: String,
        path: String,
        body: JsonElement?,
        bearer: String?,
        parse: (JsonObject) -> T,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(origin + path)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (method == "POST") {
            builder.post((body ?: buildJsonObject { }).toString().toRequestBody(Http.JSON_MEDIA))
        } else {
            builder.get()
        }
        builder.header("Accept", "application/json")
        val resp = try {
            Http.client.newCall(builder.build()).execute()
        } catch (io: IOException) {
            throw RemoteError.Network(io.message ?: "网络不通")
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 401 || it.code == 403) throw RemoteError.Unauthorized()
            if (!it.isSuccessful) throw RemoteError.Http(it.code, text.take(200))
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw RemoteError.Protocol("服务器返回的不是 JSON：${text.take(120)}")
            parse(root)
        }
    }

    private fun parseTokens(root: JsonObject): RemoteTokens {
        fun token(key: String): String =
            root.str(key)?.takeIf { it.length in 16..8192 }
                ?: throw RemoteError.Protocol("服务器返回的设备凭据不完整")
        fun expiry(key: String): Long =
            (root[key] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw RemoteError.Protocol("服务器返回的有效期不完整")
        return RemoteTokens(
            accessToken = token("accessToken"),
            accessExpiresAt = expiry("accessTokenExpiresAt"),
            refreshToken = token("refreshToken"),
            refreshExpiresAt = expiry("refreshTokenExpiresAt"),
        )
    }

    /** 扫描地址必须是本站点，否则就是把令牌发给别人 */
    private fun sameOrigin(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val base = runCatching { URI(origin) }.getOrNull() ?: return null
        if (uri.scheme != base.scheme || uri.rawAuthority != base.rawAuthority) return null
        return uri.toString()
    }

    private fun builtinProviders(): List<RemoteProvider> = listOf(
        RemoteProvider("github", "GitHub", "/api/v1/auth/oauth/qr/start?provider=github"),
        RemoteProvider("zhihu", "知乎", "/api/v1/auth/oauth/qr/start?provider=zhihu"),
    )

    companion object {
        /**
         * 只接受「裸源地址」：无 scheme 补 https，带路径/查询/用户信息一律拒绝。
         *
         * 放行 http 的唯一情形是私网主机（自建服务器常见），
         * 公网明文等于把设备令牌摊给同一 Wi-Fi 上的所有人。
         */
        fun normalize(input: String): String? {
            var text = input.trim().trimEnd('/')
            if (text.isEmpty()) return null
            val hadScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)
            if (!hadScheme) text = "https://$text"
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            val path = uri.path.orEmpty().trimEnd('/')
            if (path.isNotEmpty() && path != "/") return null
            var scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
            // 没写协议又是局域网地址：补 http。自建服务器极少配 TLS，
            // 补成 https 会得到一个「连接被拒绝」，而用户根本不知道是协议猜错了。
            if (!hadScheme && scheme == "https" && isPrivate(host)) scheme = "http"
            if (scheme != "https" && !(scheme == "http" && isPrivate(host))) return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val hostPart = if (host.contains(':')) "[$host]" else host
            return "$scheme://$hostPart$port"
        }

        private fun isPrivate(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local")) return true
            val parts = host.split('.')
            val a = parts.getOrNull(0)?.toIntOrNull()
            val b = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 4 && a != null && b != null) {
                return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || a == 127
            }
            // IPv6：唯一本地地址 fc00::/7 与回环
            return host.startsWith("fc") || host.startsWith("fd") || host == "::1"
        }

        private fun urlEncode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

// -------------------------------------------------------------------- 结构

data class RemoteProvider(val id: String, val displayName: String, val qrStartPath: String)

data class QrSession(
    val qrId: String,
    val scanUrl: String,
    val expiresInSeconds: Int,
    val provider: String,
)

sealed interface QrPoll {
    data object Pending : QrPoll
    data object Expired : QrPoll
    data class Complete(val accountToken: String) : QrPoll
}

/** 注册时要发给服务器的本机描述 */
data class RemoteDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    val identityKey: String,
    val clientVersion: String,
)

data class RemoteTokens(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
)

data class RemoteHost(
    val deviceId: String,
    val name: String,
    val platform: String,
    val online: Boolean?,
    val identityKey: String = "",
)

sealed class RemoteError(message: String) : Exception(message) {
    class InvalidUrl(message: String) : RemoteError(message)

    /** 服务端业务失败。`code` 是对端给的（METHOD_NOT_ALLOWED / RATE_LIMITED…） */
    class Remote(val errorCode: String, message: String) : RemoteError(message)

    /** 本地判断连接已经不可用：还没连上、或已经断开 */
    class Closed(message: String) : RemoteError(message)
    /** 401/403：账号令牌失效、设备被移除，或刷新令牌被吊销 */
    class Unauthorized : RemoteError("登录已失效，请重新扫码")
    class Http(val code: Int, val body: String) : RemoteError("服务器返回 HTTP $code：$body")
    class Protocol(message: String) : RemoteError(message)
    class Network(cause2: String) : RemoteError("网络不通：$cause2")
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * base64url 无填充，与服务端的 `identityKey` 规则一致。
 *
 * 用 `java.util.Base64` 而不是 `android.util.Base64`：后者在 JVM 单测里是空桩，
 * 调用直接返回 null —— 这类代码一旦进了单元测试就会以 NullPointerException
 * 的形式出现，看着像密钥算错了，其实是被测框架 stub 掉了。
 */
internal fun ByteArray.toBase64Url(): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)

internal fun String.fromBase64Url(): ByteArray =
    java.util.Base64.getUrlDecoder().decode(this)
