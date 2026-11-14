package com.dshwatch.net.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * DSH Remote 隧道层的线格式。
 *
 * 与 dshgo 的 `packages/dsh_remote/lib/src/protocol/` 对齐，三条约束最容易写错：
 *
 *  1. **控制帧**是 `{v,id,type,timestamp,payload}`，五个字段一个不能少 ——
 *     服务端按严格 schema 校验，多余字段会以 `INVALID_MESSAGE` 断连；
 *  2. **业务消息**（Noise 里跑的那些）用同一个信封，但 `type` 只能是
 *     `rpc.request` / `rpc.response` / `rpc.error` / `event` / `ping` / `pong`；
 *  3. **Noise 单条消息上限 65535 字节**，所以超过 48 KiB 的明文要先切成
 *     `DSHF` 分片再逐片加密。
 *
 * 第 3 条不只是在"很大的消息"上才会踩到：一次 `session/follow` 的历史快照
 * 轻松超过 48 KiB，不切片会得到一个加密失败，而错误信息只会说"发送失败"。
 */
object RemoteProtocol {

    const val VERSION = 1

    /** `wss://<server>/ws/v1/connect` */
    const val CONTROL_PATH = "/ws/v1/connect"

    /** 分片阈值；也是每片的最大明文长度 */
    const val CHUNK_BYTES = 48 * 1024

    /** 单条重组后的消息上限 */
    const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024

    /** 同时在途的分片消息数上限 */
    const val MAX_IN_FLIGHT_FRAGMENTS = 8

    /** 客户端只协商 Relay：手表没有 WebRTC，也没有必要为局域网直连再写一条通道 */
    val CLIENT_CAPABILITIES = listOf("transport.relay", "transport.relay.attach")

    /** 只带 Relay 的连接里 Host 必须协商出这个能力 */
    const val RELAY_CAPABILITY = "transport.relay"

    /** 隧道级 RPC 方法名 */
    const val METHOD_CALL = "harness.remote.call"
    const val METHOD_STREAM_OPEN = "harness.remote.stream.open"
    const val METHOD_STREAM_CLOSE = "harness.remote.stream.close"

    /** Host → 客户端的两类流消息，都包在 `event` 信封里 */
    const val EVENT_FRAME = "harness.remote.frame"
    const val EVENT_STREAM_CLOSED = "harness.remote.stream.closed"

    const val HOST_EVENTS = "\$events"
    const val EVENTS_RESULT = "\$events/result"

    /**
     * Noise prologue：`DSH-REMOTE\0v=1\0connection=..\0host=..\0client=..`。
     *
     * 把连接 id 与两端设备 id 绑进握手，重放一条别的连接的握手消息时
     * 哈希对不上，直接失败 —— 没有这一步，同一对设备之间的旧握手可以重放。
     */
    fun prologue(connectionId: String, hostDeviceId: String, clientDeviceId: String): ByteArray {
        require(connectionId.isNotEmpty() && hostDeviceId.isNotEmpty() && clientDeviceId.isNotEmpty())
        require(listOf(connectionId, hostDeviceId, clientDeviceId).none { it.contains('\u0000') })
        return ("DSH-REMOTE\u0000v=1\u0000connection=$connectionId" +
            "\u0000host=$hostDeviceId\u0000client=$clientDeviceId").toByteArray(Charsets.UTF_8)
    }

    /** 控制帧用的 WebSocket 地址。服务器地址已规范化，这里只换协议 */
    fun controlUrl(origin: String): String {
        val base = origin.trimEnd('/')
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + CONTROL_PATH
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + CONTROL_PATH
            else -> base + CONTROL_PATH
        }
    }
}

/**
 * 一个 Remote 信封。
 *
 * [id] 既用于请求关联（`rpc.response.requestId` 回显它），也用于去重；
 * [timestamp] 必须是正整数，服务端会拒绝 0。
 */
data class RemoteEnvelope(
    val id: String,
    val type: String,
    val payload: JsonObject,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun encode(json: Json): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", RemoteProtocol.VERSION)
            put("id", id)
            put("type", type)
            put("timestamp", timestamp)
            put("payload", payload)
        },
    )
}

/** 控制帧与业务消息共用的一套编解码 */
class RemoteWire(private val json: Json) {

    fun frame(type: String, payload: JsonObject = JsonObject(emptyMap())): RemoteEnvelope =
        RemoteEnvelope(id = uuid(), type = type, payload = payload)

    /**
     * 构造一条 `rpc.request`。
     *
     * [id] 必须由调用方给：服务端把 `rpc.response.requestId` 回显成这个 id，
     * 如果这里自己再生成一个，等待方永远等不到自己的响应 ——
     * 表现是「调用超时」，而服务端其实已经答了。
     */
    fun request(payload: JsonObject, id: String = uuid()): RemoteEnvelope =
        RemoteEnvelope(id, "rpc.request", payload)

    fun encode(envelope: RemoteEnvelope): String = envelope.encode(json)

    fun decode(text: String): RemoteEnvelope {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw RemoteError.Protocol("不是 JSON 对象：${text.take(120)}")
        if (root.int("v") != RemoteProtocol.VERSION) {
            throw RemoteError.Protocol("协议版本不认识：${root["v"]}")
        }
        val id = root.str("id")?.takeIf { it.isNotEmpty() }
            ?: throw RemoteError.Protocol("帧缺少 id")
        val type = root.str("type")?.takeIf { it.isNotEmpty() }
            ?: throw RemoteError.Protocol("帧缺少 type")
        val payload = root["payload"] as? JsonObject
            ?: throw RemoteError.Protocol("帧缺少 payload")
        val timestamp = root.long("timestamp")?.takeIf { it > 0 }
            ?: throw RemoteError.Protocol("帧的 timestamp 无效")
        return RemoteEnvelope(id, type, payload, timestamp)
    }

    fun uuid(): String = UUID.randomUUID().toString()
}

/**
 * DSHF 分片。
 *
 * ```text
 * DSHF | version(1) | messageId u32 | index u16 | total u16 | totalBytes u32 | chunk
 * ```
 *
 * 全大端。分片必须**连续且按序**到达：中间丢一片就不能把后面的拼进去，
 * 否则会得到一段"看起来完整"的错误 JSON —— 那比直接报错难查得多。
 */
class SecureFraming {

    private var nextMessageId = 1
    private val assemblies = LinkedHashMap<Int, Assembly>()

    private class Assembly(val total: Int, val totalBytes: Int) {
        val bytes = ByteArrayOutputStream(totalBytes)
        var received = 0
    }

    /** 把一条明文切成若干帧；小的原样返回 */
    fun encode(message: ByteArray): List<ByteArray> {
        require(message.size <= RemoteProtocol.MAX_MESSAGE_BYTES) { "消息超过 4 MiB" }
        if (message.size <= RemoteProtocol.CHUNK_BYTES) return listOf(message)
        val messageId = nextMessageId.also {
            nextMessageId = if (it == 0xffffffff.toInt()) 1 else it + 1
        }
        val total = (message.size + RemoteProtocol.CHUNK_BYTES - 1) / RemoteProtocol.CHUNK_BYTES
        require(total <= 0xffff) { "分片太多" }
        val out = ArrayList<ByteArray>(total)
        for (index in 0 until total) {
            val start = index * RemoteProtocol.CHUNK_BYTES
            val end = minOf(start + RemoteProtocol.CHUNK_BYTES, message.size)
            val frame = ByteArray(17 + (end - start))
            frame[0] = 'D'.code.toByte()
            frame[1] = 'S'.code.toByte()
            frame[2] = 'H'.code.toByte()
            frame[3] = 'F'.code.toByte()
            frame[4] = 1
            putInt(frame, 5, messageId)
            putShort(frame, 9, index)
            putShort(frame, 11, total)
            putInt(frame, 13, message.size)
            System.arraycopy(message, start, frame, 17, end - start)
            out += frame
        }
        return out
    }

    /** 返回完整消息；还在拼的返回 null */
    fun decode(frame: ByteArray): ByteArray? {
        if (!isFragment(frame)) return frame
        if (frame.size < 17 || frame[4].toInt() != 1) {
            throw RemoteError.Protocol("分片头不对")
        }
        val messageId = getInt(frame, 5)
        val index = getShort(frame, 9)
        val total = getShort(frame, 11)
        val totalBytes = getInt(frame, 13)
        if (messageId == 0 || total < 2 || index >= total ||
            totalBytes <= RemoteProtocol.CHUNK_BYTES ||
            totalBytes > RemoteProtocol.MAX_MESSAGE_BYTES ||
            total != (totalBytes + RemoteProtocol.CHUNK_BYTES - 1) / RemoteProtocol.CHUNK_BYTES
        ) {
            throw RemoteError.Protocol("分片元数据不对")
        }
        val remaining = totalBytes - index * RemoteProtocol.CHUNK_BYTES
        val expected = minOf(remaining, RemoteProtocol.CHUNK_BYTES)
        val chunk = frame.size - 17
        if (chunk != expected) throw RemoteError.Protocol("分片长度不对")

        var assembly = assemblies[messageId]
        if (assembly == null) {
            if (index != 0 || assemblies.size >= RemoteProtocol.MAX_IN_FLIGHT_FRAGMENTS) {
                throw RemoteError.Protocol("分片顺序不对")
            }
            assembly = Assembly(total, totalBytes)
            assemblies[messageId] = assembly
        }
        val chunksSoFar = if (assembly.received == 0) 0
        else (assembly.received + RemoteProtocol.CHUNK_BYTES - 1) / RemoteProtocol.CHUNK_BYTES
        if (assembly.total != total || assembly.totalBytes != totalBytes || index != chunksSoFar) {
            assemblies.remove(messageId)
            throw RemoteError.Protocol("分片顺序不对")
        }
        assembly.bytes.write(frame, 17, chunk)
        assembly.received += chunk
        if (index + 1 < total) return null
        assemblies.remove(messageId)
        if (assembly.received != totalBytes) throw RemoteError.Protocol("重组后的长度不对")
        return assembly.bytes.toByteArray()
    }

    fun reset() = assemblies.clear()

    private fun isFragment(frame: ByteArray): Boolean =
        frame.size >= 4 && frame[0] == 'D'.code.toByte() && frame[1] == 'S'.code.toByte() &&
            frame[2] == 'H'.code.toByte() && frame[3] == 'F'.code.toByte()

    private fun putInt(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value ushr 24).toByte()
        buf[at + 1] = (value ushr 16).toByte()
        buf[at + 2] = (value ushr 8).toByte()
        buf[at + 3] = value.toByte()
    }

    private fun putShort(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value ushr 8).toByte()
        buf[at + 1] = value.toByte()
    }

    private fun getInt(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)

    private fun getShort(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    companion object {
        fun isFragmentFrame(frame: ByteArray): Boolean =
            frame.size >= 4 && frame[0] == 'D'.code.toByte() && frame[1] == 'S'.code.toByte() &&
                frame[2] == 'H'.code.toByte() && frame[3] == 'F'.code.toByte()
    }
}

// ------------------------------------------------------------------ JSON 小工具

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonElement?.textOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
