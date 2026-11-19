package com.dshwatch.net.qq

import com.dshwatch.core.Logx
import com.dshwatch.domain.model.QqSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 桥接调用的结果。错误一律带人能看懂的一句话，不把 retcode 抛给用户 */
sealed interface QqResult<out T> {
    data class Ok<T>(val value: T) : QqResult<T>
    data class Err(val message: String) : QqResult<Nothing>
}

data class QqLogin(val userId: String, val nickname: String)

/**
 * OneBot 11 的 HTTP 客户端。
 *
 * ## 为什么单独一个 OkHttpClient
 *
 * 全局 [com.dshwatch.net.Http.client] 的读超时是 180 秒 —— 那是为推理模型
 * 留的。OneBot 是本地回环上的小接口，正常几毫秒返回；用 180 秒等一个
 * 已经死掉的后端，表现就是「轮询线程卡住三分钟」，期间所有消息都不进来。
 * 这里 10 秒不响应就当失败，下一轮重试。
 *
 * ## 为什么同时带 header 和 query 的 token
 *
 * 各实现的支持面不一样：NapCat 认 `Authorization: Bearer`，
 * 部分 go-cqhttp 分支只认 `?access_token=`。两者都带，代价是本机请求的
 * URL 里多一个参数 —— 而这条链路本来就不出设备。
 */
class OneBotClient(
    private val json: Json,
) {

    @Volatile
    private var cfg: QqSettings = QqSettings()

    fun update(settings: QqSettings) {
        cfg = settings
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    // ---------------- 基础 ----------------

    /**
     * 调一个 action。
     *
     * OneBot 的成功判定有两套：`status == "ok"` 或 `retcode == 0`。
     * 有的实现只给其中一个，两个都看。
     */
    private suspend fun call(action: String, body: JsonObject = JsonObject(emptyMap())): QqResult<JsonElement> =
        withContext(Dispatchers.IO) {
            val base = cfg.normalizedBaseUrl()
            if (base.isBlank()) return@withContext QqResult.Err("还没填后端地址")

            val token = cfg.token.trim()
            val url = buildString {
                append(base).append('/').append(action)
                if (token.isNotEmpty()) {
                    append("?access_token=").append(java.net.URLEncoder.encode(token, "UTF-8"))
                }
            }
            val payload = json.encodeToString(JsonObject.serializer(), body)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url(url)
                .post(payload)
                .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@use QqResult.Err("后端返回 HTTP ${resp.code}：${text.take(120)}")
                    }
                    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                        ?: return@use QqResult.Err("后端返回的不是 JSON：${text.take(120)}")

                    val status = (root["status"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content
                    val retcode = (root["retcode"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content
                    val ok = status == "ok" || retcode == "0"
                    if (!ok) {
                        val wording = (root["wording"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content
                        val msg = (root["message"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content
                        return@use QqResult.Err(
                            wording ?: msg ?: "调用 $action 失败（retcode=$retcode）"
                        )
                    }
                    QqResult.Ok(root["data"] ?: JsonObject(emptyMap()))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logx.w(TAG, "OneBot 调用失败：$action", e)
                QqResult.Err(describe(e))
            }
        }

    private fun describe(e: Exception): String = when (e) {
        is java.net.ConnectException -> "连不上 ${cfg.normalizedBaseUrl()} —— 后端没启动，或地址/端口填错了"
        is java.net.SocketTimeoutException -> "后端 ${cfg.normalizedBaseUrl()} 响应超时"
        is java.net.UnknownHostException -> "地址解析不了：${cfg.normalizedBaseUrl()}"
        else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
    }

    // ---------------- 账号 ----------------

    /** 后端登录的账号。selfId 没填时靠它兜底 */
    suspend fun loginInfo(): QqResult<QqLogin> = when (val r = call("get_login_info")) {
        is QqResult.Err -> r
        is QqResult.Ok -> {
            val o = runCatching { r.value.jsonObject }.getOrNull()
            if (o == null) {
                QqResult.Err("get_login_info 返回格式不认识")
            } else {
                QqResult.Ok(
                    QqLogin(
                        userId = o.str("user_id"),
                        nickname = o.str("nickname"),
                    )
                )
            }
        }
    }

    // ---------------- 历史 ----------------

    suspend fun friendHistory(userId: String, count: Int = 20): QqResult<List<QqMessage>> {
        if (userId.isBlank()) return QqResult.Err("没填私聊对象 QQ 号")
        val body = buildJsonObject {
            put("user_id", userId)
            put("count", count)
        }
        return history("get_friend_msg_history", body, isGroup = false, peer = userId)
    }

    suspend fun groupHistory(groupId: String, count: Int = 20): QqResult<List<QqMessage>> {
        if (groupId.isBlank()) return QqResult.Err("没填群号")
        val body = buildJsonObject {
            put("group_id", groupId)
            put("count", count)
        }
        return history("get_group_msg_history", body, isGroup = true, peer = groupId)
    }

    private suspend fun history(
        action: String,
        body: JsonObject,
        isGroup: Boolean,
        peer: String,
    ): QqResult<List<QqMessage>> = when (val r = call(action, body)) {
        is QqResult.Err -> r
        is QqResult.Ok -> {
            val o = runCatching { r.value.jsonObject }.getOrNull()
            val arr: JsonArray? = when {
                o == null -> null
                o["messages"] is JsonArray -> o["messages"] as JsonArray
                r.value is JsonArray -> r.value as JsonArray
                else -> null
            }
            if (arr == null) {
                QqResult.Err("$action 没有返回 messages 数组")
            } else {
                val list = arr.mapNotNull { item ->
                    runCatching { item.jsonObject }.getOrNull()?.let { m ->
                        QqParser.message(m, isGroup, peer)
                    }
                }
                QqResult.Ok(list)
            }
        }
    }

    // ---------------- 发送 ----------------

    suspend fun sendPrivate(userId: String, segments: List<QqSegment>): QqResult<String> {
        if (userId.isBlank()) return QqResult.Err("没填私聊对象 QQ 号")
        if (segments.isEmpty()) return QqResult.Err("消息是空的")
        val body = buildJsonObject {
            put("user_id", userId)
            put("message", toArray(segments))
            put("auto_escape", false)
        }
        return send("send_private_msg", body)
    }

    suspend fun sendGroup(groupId: String, segments: List<QqSegment>): QqResult<String> {
        if (groupId.isBlank()) return QqResult.Err("没填群号")
        if (segments.isEmpty()) return QqResult.Err("消息是空的")
        val body = buildJsonObject {
            put("group_id", groupId)
            put("message", toArray(segments))
            put("auto_escape", false)
        }
        return send("send_group_msg", body)
    }

    private suspend fun send(action: String, body: JsonObject): QqResult<String> =
        when (val r = call(action, body)) {
            is QqResult.Err -> r
            is QqResult.Ok -> {
                val id = runCatching { r.value.jsonObject.str("message_id") }.getOrDefault("")
                QqResult.Ok(id)
            }
        }

    private fun toArray(segments: List<QqSegment>): JsonArray = buildJsonArray {
        segments.forEach { seg ->
            add(
                buildJsonObject {
                    put("type", seg.type)
                    put(
                        "data",
                        buildJsonObject {
                            when (seg.type) {
                                "text" -> put("text", seg.text)
                                "image" -> put("file", seg.file.ifBlank { seg.url })
                                "at" -> put("qq", seg.qq)
                                "reply" -> put("id", seg.id)
                                else -> Unit
                            }
                        },
                    )
                }
            )
        }
    }

    private companion object {
        const val TAG = "OneBot"
    }
}
