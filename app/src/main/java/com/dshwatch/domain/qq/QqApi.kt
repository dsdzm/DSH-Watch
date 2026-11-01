package com.dshwatch.domain.qq

import com.dshwatch.core.Logx
import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** QQ 通道的异常。message 直接给用户看，所以都是人话 */
class QqException(message: String) : Exception(message)

/**
 * QQ 机器人开放平台的 HTTP 部分。
 *
 * 三件事：换 access_token、问网关地址、发群消息。
 * 协议本身不复杂，难的是**状态**：token 两小时过期、沙箱和正式是两套域名、
 * 发消息必须带事件里的 openid 而不是群号。这三处各自都有一种「看起来能用、
 * 过一会儿就不行」的失败方式，所以都集中在这里，不让调用方各写一遍。
 */
class QqApi(
    val appId: String,
    private val appSecret: String,
    private val sandbox: Boolean,
) {

    companion object {
        private const val TAG = "QqApi"
        private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"

        /** 提前 5 分钟刷新：等到真的过期再换，中间那几秒的请求会集体 401 */
        private const val REFRESH_AHEAD_MS = 5 * 60_000L

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var token: String = ""

    @Volatile
    private var tokenExpireAt: Long = 0L

    /** 当前环境的基础域名。沙箱只影响 api 域，token 域是同一个 */
    val baseUrl: String
        get() = if (sandbox) "https://sandbox.api.sgroup.qq.com" else "https://api.sgroup.qq.com"

    /**
     * 取 access_token。
     *
     * `expires_in` 在文档里是 7200，但实际下发过字符串形态（`"7200"`），
     * 两种都按字符串解析再转数字 —— 直接用 `int` 取会在真机上抛
     * 「Unexpected JSON token」，而这条路径恰好在连上之后才走到，很难复现。
     */
    suspend fun token(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && token.isNotEmpty() && now < tokenExpireAt) return token

        val body = buildString {
            append("{\"appId\":\"").append(appId.trim()).append("\",\"clientSecret\":\"")
                .append(appSecret.trim()).append("\"}")
        }
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val text = withContext(Dispatchers.IO) {
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw QqException("连不上 QQ 开放平台：${it.message.orEmpty().take(80)}") }
        }
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw QqException("换取 access_token 失败：返回不是 JSON")

        val fresh = obj.str("access_token")
        if (fresh.isBlank()) {
            // 失败时 QQ 会回 {"code":100016,"message":"..."}，把它原样带出来
            val msg = obj.str("message").ifBlank { text.take(120) }
            throw QqException("换取 access_token 失败：$msg")
        }
        val expiresIn = obj.str("expires_in").toLongOrNull() ?: 7200L
        token = fresh
        tokenExpireAt = now + (expiresIn * 1000 - REFRESH_AHEAD_MS)
        Logx.d(TAG, "access_token 已刷新，有效期 ${expiresIn}s")
        return fresh
    }

    /** 问网关地址。返回 `wss://api.sgroup.qq.com/websocket/` */
    suspend fun gateway(): String {
        val request = Request.Builder()
            .url("$baseUrl/gateway")
            .header("Authorization", "QQBot ${token()}")
            .header("X-Union-Appid", appId.trim())
            .get()
            .build()
        val text = withContext(Dispatchers.IO) {
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw QqException("取网关地址失败：${it.message.orEmpty().take(80)}") }
        }
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw QqException("取网关地址失败：返回不是 JSON")
        val url = obj.str("url")
        if (url.isBlank()) throw QqException("取网关地址失败：${text.take(120)}")
        return url
    }

    /**
     * 发一条群消息。
     *
     * 带 [msgId] 是**被动回复**（不占主动额度、不限条数），
     * 不带就是主动消息 —— 群聊里主动消息有独立限额，发几条就没了。
     * 所以这个方法把 msgId 设成必填，不给「忘了传」留口子。
     */
    suspend fun sendGroupMessage(groupOpenid: String, content: String, msgId: String) {
        val body = buildString {
            append("{\"content\":\"").append(escapeJson(content))
                .append("\",\"msg_type\":0,\"msg_id\":\"").append(escapeJson(msgId)).append("\"}")
        }
        val request = Request.Builder()
            .url("$baseUrl/v2/groups/$groupOpenid/messages")
            .header("Authorization", "QQBot ${token()}")
            .header("X-Union-Appid", appId.trim())
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val text = withContext(Dispatchers.IO) {
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw QqException("发消息失败：${it.message.orEmpty().take(80)}") }
        }
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        // 成功时返回的是消息对象（带 id），没有顶层 code 字段
        val code = obj?.get("code")?.jsonPrimitive?.contentOrNull
        if (!code.isNullOrBlank() && code != "0") {
            val msg = obj?.str("message").orEmpty().ifBlank { text.take(120) }
            throw QqException("发消息被拒：$msg")
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /**
     * 极简 JSON 转义。
     *
     * 不用 `Json.encodeToString(String)` 是因为这里拼的是手写 body ——
     * 换成序列化器要再套一层 `@Serializable` 数据类，为两个字段不值得。
     * 转义字符覆盖 RFC 8259 要求的那些，够用。
     */
    private fun escapeJson(s: String): String = buildString(s.length + 8) {
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
