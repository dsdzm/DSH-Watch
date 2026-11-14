package com.dshwatch.net.dshweb

import com.dshwatch.net.Http
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

/**
 * `/api/remote.mux` 上的会话流。
 *
 * ## 为什么聊天不能走普通 HTTP
 *
 * dsh web 的对话不是「发一个请求、等一个响应」：一条 prompt 进去之后，
 * 服务端会持续产生事件（回合开始、工具调用、模型逐字输出、回合结束），
 * 这些是靠一条长连接的 WebSocket 推回来的。用 HTTP 只能轮询，
 * 而轮询拿不到「正在输出到一半」的文本 —— 手表上看起来就是「点了发送，十几秒没反应」。
 *
 * ## 帧格式（来自 ds-harness-remote v1，与 OpenAI 兼容协议无关）
 *
 * ```text
 * 客户端 -> Host  {"type":"open","streamId":"<uuid>","endpoint":"session/follow",
 *                  "payload":{"args":{"request":{...}}}}
 *                 {"type":"cancel","streamId":"..."}
 * Host -> 客户端  {"type":"item","streamId":"...","value":{...}}
 *                 {"type":"end","streamId":"..."}
 *                 {"type":"error","streamId":"...","error":{"code","message"}}
 * ```
 *
 * 每条连接开始时必须先开 `$events` 流，它的第一帧是
 * `{"type":"ready","clientId":"...","host":{"home":"..."}}` —— 没等到它就去开
 * 别的流，Host 会按「连接代际还没建立」拒绝。
 *
 * ## 这一版的边界
 *
 * 只做「订阅一回合」：拉历史快照 → 接收增量 → 回合结束就断开。
 * 不做自动重连、不处理 approval（需要用户批准的回合会停在审批上）。
 * 这两件都能加，但要先有真机验证过的基础链路。
 */
class DshMux(
    private val host: DshWebHost,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val cookie: String? get() = host.cookie?.header
    private val hostHeader: String get() = host.hostHeader
    private val muxUrl: String get() = DshWeb.muxUrl(host)


    /**
     * 订阅一个会话。[onOpen] 在快照到达时回调一次，之后是增量。
     *
     * 调用方取消收集即断开连接（[callbackFlow] 的 awaitClose 会 close）。
     */
    fun follow(sessionId: String, maxMessages: Int = 60): Flow<MuxEvent> = callbackFlow {
        val cookie = cookie ?: run {
            trySendBlocking(MuxEvent.Failed("还没有登录这台电脑"))
            close()
            return@callbackFlow
        }
        val request = Request.Builder()
            .url(muxUrl)
            .header("Cookie", cookie)
            .apply { if (hostHeader.isNotBlank()) header("Host", hostHeader) }
            .build()

        val eventsStream = "mux-" + UUID.randomUUID().toString().take(8)
        val followStream = "mux-" + UUID.randomUUID().toString().take(8)
        var followOpened = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    openFrame(eventsStream, DshWeb.HOST_EVENTS, buildJsonObject { })
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (frame == null) {
                    trySendBlocking(MuxEvent.Failed("Host 发来一帧看不懂的数据：${text.take(120)}"))
                    return
                }
                val streamId = frame["streamId"].stringOrNull.orEmpty()
                when (frame["type"].stringOrNull) {
                    "item" -> {
                        val value = frame["value"]
                        if (streamId == eventsStream && !followOpened) {
                            // 只有 ready 之后开 follow 才会被接受
                            if ((value as? JsonObject)?.get("type").stringOrNull == "ready") {
                                followOpened = true
                                webSocket.send(
                                    openFrame(
                                        followStream,
                                        "session/follow",
                                        buildJsonObject {
                                            put(
                                                "request",
                                                buildJsonObject {
                                                    put(
                                                        "address",
                                                        buildJsonObject {
                                                            put("kind", "session")
                                                            put("sessionId", sessionId)
                                                        },
                                                    )
                                                    put("assistantStream", true)
                                                    put("maxMessages", maxMessages)
                                                },
                                            )
                                        },
                                    )
                                )
                            }
                            return
                        }
                        if (streamId == followStream) {
                            MuxWire.parseFollow(value)?.let { trySendBlocking(it) }
                        }
                    }

                    "end" -> {
                        if (streamId == followStream) {
                            trySendBlocking(MuxEvent.Ended)
                            webSocket.close(1000, "done")
                        }
                    }

                    "error" -> {
                        val error = frame["error"] as? JsonObject
                        val code = error?.get("code").stringOrNull ?: "unknown"
                        val message = error?.get("message").stringOrNull ?: "会话流出错"
                        trySendBlocking(MuxEvent.Failed("$code — $message"))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySendBlocking(MuxEvent.Failed(t.message ?: "WebSocket 断开"))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        val socket = Http.client.newWebSocket(request, listener)
        awaitClose {
            runCatching { socket.cancel() }
        }
    }

    private fun openFrame(streamId: String, endpoint: String, args: JsonObject): String =
        buildJsonObject {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", endpoint)
            put("payload", buildJsonObject { put("args", args) })
        }.toString()
}

/** 会话流上的一帧 */
sealed class MuxEvent {
    /** 历史快照：一次订阅只会来第一帧 */
    data class Snapshot(val cursor: Int, val messages: List<DshMessage>) : MuxEvent()

    /** 新的一条完整消息（用户发的、或模型最终定稿的助手消息） */
    data class Message(val role: String, val text: String) : MuxEvent()

    /** 模型正在输出的文本增量 */
    data class Delta(val text: String) : MuxEvent()

    /** 回合结束 */
    data object TurnEnded : MuxEvent()

    /** Host 正常结束了这条流 */
    data object Ended : MuxEvent()

    data class Failed(val reason: String) : MuxEvent()
}

data class DshMessage(val role: String, val text: String)

/**
 * 帧解析。抽成对象是为了能脱离网络单测 —— 这里错一个字段名，
 * 表现是「连上了但聊天页永远空白」，从日志里几乎看不出来。
 */
object MuxWire {

    fun parseFollow(value: kotlinx.serialization.json.JsonElement?): MuxEvent? {
        val obj = value as? JsonObject ?: return null
        return when (obj["type"].stringOrNull) {
            "snapshot" -> {
                val cursor = obj["cursor"].longOrNull?.toInt() ?: -1
                val records = obj["records"] as? JsonArray ?: JsonArray(emptyList())
                val messages = records.mapNotNull { record ->
                    val event = (record as? JsonObject)?.get("event") as? JsonObject ?: return@mapNotNull null
                    messageOf(event)
                }
                MuxEvent.Snapshot(cursor, messages)
            }

            "event" -> {
                val event = obj["event"] as? JsonObject ?: return null
                val type = event["type"].stringOrNull.orEmpty()
                when {
                    type == "turn/end" -> MuxEvent.TurnEnded
                    type.endsWith("/message") || type == "user/message" -> messageOf(event)?.let {
                        MuxEvent.Message(it.role, it.text)
                    }
                    else -> null
                }
            }

            "assistant-stream" -> {
                val frame = obj["frame"] as? JsonObject ?: return null
                when (frame["type"].stringOrNull) {
                    "chunk" -> {
                        val chunk = frame["chunk"] as? JsonObject ?: return null
                        val kind = chunk["type"].stringOrNull
                        // 只取正文；思考过程（reasoning）在手表上没地方放
                        if (kind == "text-delta") {
                            val text = chunk["text"].stringOrNull ?: chunk["delta"].stringOrNull
                            if (!text.isNullOrEmpty()) MuxEvent.Delta(text) else null
                        } else {
                            null
                        }
                    }
                    "end" -> {
                        val outcome = frame["outcome"] as? JsonObject
                        if (outcome?.get("kind").stringOrNull == "committed") null else null
                    }
                    else -> null
                }
            }

            else -> null
        }
    }

    /**
     * 从一条持久事件里取出「谁说了什么」。
     *
     * `user/message` 的 data 本身就是消息；`assistant/message` 的 data 里
     * 消息在 `message` 下。这个不对称来自服务端的存储格式，不是笔误。
     */
    fun messageOf(event: JsonObject): DshMessage? {
        val type = event["type"].stringOrNull.orEmpty()
        val data = event["data"] as? JsonObject ?: return null
        val raw = when (type) {
            "user/message" -> data
            "assistant/message" -> data["message"] as? JsonObject ?: return null
            else -> return null
        }
        val role = raw["role"].stringOrNull ?: if (type == "user/message") "user" else "assistant"
        val text = textOf(raw) ?: return null
        if (text.isBlank()) return null
        return DshMessage(role, text)
    }

    /** content 是 parts 数组，只拼 text 部分 */
    fun textOf(message: JsonObject): String? {
        val content = message["content"]
        if (content is kotlinx.serialization.json.JsonPrimitive) {
            return content.stringOrNull
        }
        val parts = content as? JsonArray ?: return null
        return parts.mapNotNull { part ->
            (part as? JsonObject)?.takeIf { it["type"].stringOrNull == "text" }?.get("text").stringOrNull
        }.joinToString("").ifBlank { null }
    }
}
