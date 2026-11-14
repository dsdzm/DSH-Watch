package com.dshwatch.net.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 拿一个"假 Host"跑通整条隧道。
 *
 * 假 Host 是照着协议文档独立写的一侧：自己解析控制帧、自己做 Noise responder、
 * 自己按 counter 发密文。两边只有协议是共同的，所以这里抓到的都是真正的协议错误
 * （字段名、base64 变体、nonce 起点、分片头偏移），而不是"两边一起错"。
 */
class RemoteTunnelTest {

    private lateinit var control: MockWebServer
    private lateinit var relay: MockWebServer
    private lateinit var host: FakeHost

    private val clientPrivate = NoiseIk.newPrivateKey()
    private val clientPublic = NoiseIk.publicOf(clientPrivate)
    private val hostPrivate = NoiseIk.newPrivateKey()
    private val hostPublic = NoiseIk.publicOf(hostPrivate)

    @Before
    fun setUp() {
        control = MockWebServer().apply { start() }
        relay = MockWebServer().apply { start() }
        host = FakeHost(control, relay, hostPrivate, clientPublic)
        host.install()
    }

    @After
    fun tearDown() {
        runCatching { control.shutdown() }
        runCatching { relay.shutdown() }
    }

    @Test
    fun `握手之后能调一元接口`() = runBlocking {
        val tunnel = tunnel()
        tunnel.connect(timeoutMs = 5_000)
        assertEquals(RemoteTunnel.Phase.READY, tunnel.phase.value)
        assertEquals("cli-1", tunnel.clientId)
        assertEquals("/home/me", tunnel.home)

        val value = withTimeout(5_000) { tunnel.call("session/list", buildJsonObject { }) }
        val items = value.jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("s-1", items[0].jsonObject["sessionId"]!!.jsonPrimitive.content)

        val request = host.calls.poll(2, TimeUnit.SECONDS)
        assertNotNull("Host 应该收到一次 harness.remote.call", request)
        assertEquals("session/list", request!!["endpoint"]!!.jsonPrimitive.content)
        assertEquals("harness.remote.call", request["method"]!!.jsonPrimitive.content)

        tunnel.close()
    }

    @Test
    fun `会话流的帧会被原样交到上层`() = runBlocking {
        val tunnel = tunnel()
        tunnel.connect(timeoutMs = 5_000)

        val events = mutableListOf<JsonElement>()
        val job = launch {
            tunnel.openStream("session/follow", buildJsonObject { }).collect { events += it }
        }
        withTimeout(5_000) {
            while (host.followStreamId == null) delay(20)
        }
        val streamId = host.followStreamId!!
        host.pushFrame(
            streamId,
            buildJsonObject {
                put("type", "assistant-stream")
                putJsonObject("frame") {
                    put("type", "chunk")
                    putJsonObject("chunk") {
                        put("type", "text-delta")
                        put("text", "你好")
                    }
                }
            },
        )
        withTimeout(5_000) {
            while (events.isEmpty()) delay(20)
        }
        assertEquals("assistant-stream", events[0].jsonObject["type"]!!.jsonPrimitive.content)
        job.cancel()
        tunnel.close()
    }

    @Test
    fun `审批结果带回 clientId 与 eventId`() = runBlocking {
        val tunnel = tunnel()
        tunnel.connect(timeoutMs = 5_000)

        tunnel.respond("evt-1", buildJsonObject { put("kind", "result"); put("value", "allowed-once") })

        var args: JsonObject? = null
        withTimeout(5_000) {
            while (args == null) {
                val call = host.calls.poll(100, TimeUnit.MILLISECONDS)
                if (call != null && call["endpoint"]!!.jsonPrimitive.content == "\$events/result") {
                    args = call["args"] as JsonObject
                }
            }
        }
        assertEquals("cli-1", args!!["clientId"]!!.jsonPrimitive.content)
        assertEquals("evt-1", args["eventId"]!!.jsonPrimitive.content)
        assertEquals("result", args["outcome"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        tunnel.close()
    }

    @Test
    fun `超过 48 KiB 的快照能分片发过来`() = runBlocking {
        val tunnel = tunnel()
        tunnel.connect(timeoutMs = 5_000)

        val events = mutableListOf<JsonElement>()
        val job = launch {
            tunnel.openStream("session/follow", buildJsonObject { }).collect { events += it }
        }
        withTimeout(5_000) {
            while (host.followStreamId == null) delay(20)
        }
        // 120 KiB 的正文：一定会走 DSHF 分片
        val big = "x".repeat(120 * 1024)
        host.pushFrame(
            host.followStreamId!!,
            buildJsonObject {
                put("type", "snapshot")
                put("cursor", 7)
                put("records", JsonArray(emptyList()))
                put("blob", big)
            },
        )
        withTimeout(10_000) {
            while (events.isEmpty()) delay(20)
        }
        assertEquals(big, events[0].jsonObject["blob"]!!.jsonPrimitive.content)
        job.cancel()
        tunnel.close()
    }

    private fun tunnel() = RemoteTunnel(
        server = "http://127.0.0.1:${control.port}",
        deviceId = "watch-1",
        accessToken = "token-abcdefghijklmnop",
        hostDeviceId = "host-1",
        identityPrivateKey = clientPrivate,
        hostIdentityKey = hostPublic,
        clientVersion = "1.23.0",
        json = Json { ignoreUnknownKeys = true; isLenient = true },
    )

    // ------------------------------------------------------------------ 假 Host

    private class FakeHost(
        private val control: MockWebServer,
        private val relay: MockWebServer,
        private val hostPrivate: ByteArray,
        private val clientPublic: ByteArray,
    ) {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val framing = SecureFraming()
        private val connectionId = "conn-1"
        private val clientId = "cli-1"

        @Volatile
        private var controlSocket: WebSocket? = null

        @Volatile
        private var relaySocket: WebSocket? = null

        private var noise: NoiseIk? = null

        /** 客户端发来的 `harness.remote.call`：endpoint / method / args */
        val calls = LinkedBlockingQueue<JsonObject>()

        @Volatile
        var followStreamId: String? = null

        @Volatile
        private var eventsStreamId: String? = null

        fun install() {
            control.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        controlSocket = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = onControl(text)
                })
            )
            relay.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        relaySocket = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = onData(text)
                })
            )
        }

        private fun onControl(text: String) {
            val frame = json.parseToJsonElement(text).jsonObject
            val payload = frame["payload"] as JsonObject
            when (frame["type"]!!.jsonPrimitive.content) {
                "hello" -> sendControl(
                    "hello.ack",
                    buildJsonObject {
                        put("protocol", 1)
                        put("serverVersion", "test-1")
                        put("connectionSessionId", "sess-1")
                        put("heartbeatIntervalMs", 20_000)
                        put("maxControlFrameBytes", 65536)
                        put("maxRelayFrameBytes", 1048576)
                        put(
                            "capabilities",
                            JsonArray(
                                listOf(
                                    JsonPrimitive("transport.relay"),
                                    JsonPrimitive("transport.relay.attach"),
                                )
                            ),
                        )
                        put("webrtcEnabled", false)
                    },
                )
                "connect.request" -> sendControl(
                    "connect.accepted",
                    buildJsonObject {
                        put("connectionId", connectionId)
                        put("relayEndpoint", "ws://127.0.0.1:${relay.port}")
                        put("relayTicket", "ticket-1")
                        put("relayEpoch", 1)
                    },
                )
                "secure.handshake" -> onHandshake(payload)
                "relay" -> decrypt(payload)
            }
        }

        private fun onHandshake(payload: JsonObject) {
            if (payload["step"]!!.jsonPrimitive.content.toInt() != 1) return
            val session = NoiseIk.responder(
                localPrivate = hostPrivate,
                remotePublic = clientPublic,
                prologue = RemoteProtocol.prologue(connectionId, "host-1", "watch-1"),
            )
            noise = session
            session.readHandshake(payload["data"]!!.jsonPrimitive.content.fromB64())
            val reply = session.writeHandshake()
            sendControl(
                "secure.handshake",
                buildJsonObject {
                    put("connectionId", connectionId)
                    put("targetDeviceId", "watch-1")
                    put("step", 2)
                    put("data", reply.toB64())
                },
            )
        }

        private fun onData(text: String) {
            val frame = json.parseToJsonElement(text).jsonObject
            when (frame["type"]!!.jsonPrimitive.content) {
                "relay.attach" -> sendData(
                    "relay.ready",
                    buildJsonObject {
                        put("connectionId", connectionId)
                        put("epoch", 1)
                    },
                )
                "relay" -> decrypt(frame["payload"] as JsonObject)
            }
        }

        private fun decrypt(payload: JsonObject) {
            val session = noise ?: return
            val counter = payload["counter"]!!.jsonPrimitive.content.toLong()
            if (counter != session.receivingNonce) {
                throw IllegalStateException("counter 跳了：期望 ${session.receivingNonce}，收到 $counter")
            }
            val plain = session.decrypt(payload["ciphertext"]!!.jsonPrimitive.content.fromB64())
            val message = framing.decode(plain) ?: return
            val envelope = json.parseToJsonElement(String(message, Charsets.UTF_8)).jsonObject
            if (envelope["type"]!!.jsonPrimitive.content != "rpc.request") return

            val body = envelope["payload"] as JsonObject
            val method = body["method"]!!.jsonPrimitive.content
            val params = body["params"] as JsonObject
            val requestId = envelope["id"]!!.jsonPrimitive.content
            when (method) {
                RemoteProtocol.METHOD_CALL -> {
                    val endpoint = params["endpoint"]!!.jsonPrimitive.content
                    val args = (params["payload"] as JsonObject)["args"] as? JsonObject ?: JsonObject(emptyMap())
                    calls.offer(
                        buildJsonObject {
                            put("method", method)
                            put("endpoint", endpoint)
                            put("args", args)
                        }
                    )
                    val value = when (endpoint) {
                        "session/list" -> buildJsonObject {
                            put("items", JsonArray(listOf(buildJsonObject { put("sessionId", "s-1") })))
                        }
                        else -> JsonObject(emptyMap())
                    }
                    respond(requestId, buildJsonObject { put("ok", true); put("value", value) })
                }
                RemoteProtocol.METHOD_STREAM_OPEN -> {
                    val streamId = params["streamId"]!!.jsonPrimitive.content
                    val endpoint = params["endpoint"]!!.jsonPrimitive.content
                    if (endpoint == RemoteProtocol.HOST_EVENTS) {
                        eventsStreamId = streamId
                        respond(requestId, buildJsonObject { put("ok", true) })
                        pushFrame(
                            streamId,
                            buildJsonObject {
                                put("type", "ready")
                                put("clientId", clientId)
                                putJsonObject("host") { put("home", "/home/me") }
                            },
                        )
                    } else {
                        followStreamId = streamId
                        respond(requestId, buildJsonObject { put("ok", true) })
                    }
                }
                else -> respond(requestId, buildJsonObject { put("ok", true) })
            }
        }

        private fun respond(requestId: String, result: JsonObject) {
            sendSecure(
                buildJsonObject {
                    put("v", 1)
                    put("id", "resp-" + requestId)
                    put("type", "rpc.response")
                    put("timestamp", System.currentTimeMillis())
                    putJsonObject("payload") {
                        put("requestId", requestId)
                        put("result", result)
                    }
                }
            )
        }

        fun pushFrame(streamId: String, value: JsonObject) {
            sendSecure(
                buildJsonObject {
                    put("v", 1)
                    put("id", "evt-" + System.nanoTime())
                    put("type", "event")
                    put("timestamp", System.currentTimeMillis())
                    putJsonObject("payload") {
                        put("event", RemoteProtocol.EVENT_FRAME)
                        putJsonObject("data") {
                            put("streamId", streamId)
                            put("hasValue", true)
                            put("value", value)
                        }
                    }
                }
            )
        }

        private fun sendSecure(envelope: JsonObject) {
            val session = noise ?: return
            val bytes = json.encodeToString(JsonObject.serializer(), envelope).toByteArray()
            for (fragment in framing.encode(bytes)) {
                val counter = session.sendingNonce
                val ciphertext = session.encrypt(fragment)
                sendData(
                    "relay",
                    buildJsonObject {
                        put("connectionId", connectionId)
                        put("targetDeviceId", "watch-1")
                        put("counter", counter)
                        put("ciphertext", ciphertext.toB64())
                    },
                )
            }
        }

        private fun sendControl(type: String, payload: JsonObject) {
            controlSocket?.send(envelope(type, payload))
        }

        private fun sendData(type: String, payload: JsonObject) {
            relaySocket?.send(envelope(type, payload))
        }

        private fun envelope(type: String, payload: JsonObject): String =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("v", 1)
                    put("id", "srv-" + System.nanoTime())
                    put("type", type)
                    put("timestamp", System.currentTimeMillis())
                    put("payload", payload)
                },
            )

        private fun ByteArray.toB64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
        private fun String.fromB64(): ByteArray = Base64.getUrlDecoder().decode(this)
    }
}
