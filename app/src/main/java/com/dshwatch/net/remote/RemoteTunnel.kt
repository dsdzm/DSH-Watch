package com.dshwatch.net.remote

import com.dshwatch.core.Logx
import com.dshwatch.net.Http
import com.dshwatch.net.remote.RemoteProtocol.CHUNK_BYTES
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

/**
 * 一条 DSH Remote 隧道：控制 WebSocket → Relay 数据面 → Noise IK → 业务消息。
 *
 * ## 为什么是四层而不是"一个 WebSocket 带 JSON"
 *
 * 服务端的连接是**中继**的：控制通道只负责协商（`hello` / `connect.request`），
 * 真正的加密帧走另一条数据 WebSocket（`relay.attach`），而里面跑的又是 Noise
 * 密文。少接一层就会停在某个"看起来正常但永远没数据"的状态：
 *
 *  1. 不接数据面 → Host 等 `transport.selected`，Noise 握手一直不来；
 *  2. 不跑 Noise → 收到的 `relay` 帧解不开，表现为"连上了但没响应"；
 *  3. 不做 DSHF 分片 → 历史快照超过 48 KiB 时整条消息发不出去。
 *
 * ## 线程模型
 *
 * OkHttp 的回调是在它自己的线程上顺序触发的，两条 socket 之间用一个锁串起来：
 * Noise 的 nonce 必须严格递增，解密和加密都不能交错。所有跨线程状态都放在
 * [lock] 里，UI 只读 [phase] 与 [failure]。
 *
 * ## 边界
 *
 * 只做 Relay：手表没有 WebRTC，局域网直连也没有必要 —— Relay 已经够快，
 * 而多一条直连意味着多一套 ICE、候补和失败回退。
 */
class RemoteTunnel(
    private val server: String,
    private val deviceId: String,
    private val accessToken: String,
    private val hostDeviceId: String,
    private val identityPrivateKey: ByteArray,
    private val hostIdentityKey: ByteArray,
    private val clientVersion: String,
    json: Json,
    private val client: OkHttpClient = Http.client,
) {

    enum class Phase { IDLE, CONNECTING, NEGOTIATING, HANDSHAKING, READY, CLOSED }

    private val wire = RemoteWire(json)
    private val framing = SecureFraming()
    private val lock = Any()

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    /** 服务端给这代连接分配的客户端 id，`$events/result` 要带它 */
    @Volatile
    var clientId: String? = null
        private set

    @Volatile
    var connectionId: String? = null
        private set

    /** Host 账号的 home 目录，界面上用来缩写路径；拿不到就是空串 */
    @Volatile
    var home: String = ""
        private set

    private var control: WebSocket? = null
    private var data: WebSocket? = null
    private var noise: NoiseIk? = null

    private var relayEndpoint: String? = null
    private var relayTicket: String? = null
    private var relayEpoch: Int? = null
    private var heartbeatMs = 25_000L
    private var lastInbound = System.currentTimeMillis()

    private val accepted = CompletableDeferred<Unit>()
    private val dataReady = CompletableDeferred<Unit>()
    private val handshakeDone = CompletableDeferred<Unit>()
    private val eventsReady = CompletableDeferred<String>()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val streams = ConcurrentHashMap<String, StreamSink>()
    private var eventsStreamId: String? = null

    private var watchdog: Timer? = null
    private var closed = false

    // ---------------------------------------------------------------- 连接

    /**
     * 建立到 Host 的可用隧道。
     *
     * 返回时 `$events` 已经就绪 —— 也就是说调用方拿到隧道之后立刻 `follow` 不会
     * 撞上"连接代际还没建立"。握手里任何一步失败都会把整条链关掉，不留半开。
     */
    suspend fun connect(timeoutMs: Long = 30_000) {
        if (_phase.value == Phase.READY) return
        _phase.value = Phase.CONNECTING
        val url = RemoteProtocol.controlUrl(server)
        Logx.i("remote", "连接 $url（host=$hostDeviceId）")
        val socket = client.newWebSocket(Request.Builder().url(url).build(), ControlListener())
        control = socket
        sendControl(
            "hello",
            buildJsonObject {
                put("role", "client")
                put("deviceId", deviceId)
                put("accessToken", accessToken)
                put("protocols", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(1))))
                put(
                    "capabilities",
                    kotlinx.serialization.json.JsonArray(
                        RemoteProtocol.CLIENT_CAPABILITIES.map { kotlinx.serialization.json.JsonPrimitive(it) }
                    ),
                )
                put("clientVersion", clientVersion.take(64))
            },
        )
        try {
            withTimeout(timeoutMs) { accepted.await() }
            // 有 Relay 分配就先等数据面确认；没有就直接握手
            if (relayEndpoint != null && relayTicket != null) {
                withTimeout(timeoutMs) { dataReady.await() }
            }
            startHandshake()
            withTimeout(timeoutMs) { handshakeDone.await() }
            val id = withTimeout(timeoutMs) { eventsReady.await() }
            clientId = id
            _phase.value = Phase.READY
            startWatchdog()
            Logx.i("remote", "隧道就绪 connection=$connectionId client=$id")
        } catch (t: Throwable) {
            // 失败原因优先保留对端给的那句（HOST_OFFLINE / AUTH_INVALID…），
            // 只有超时这种本地判断才换成看得懂的话
            val message = if (t is kotlinx.coroutines.TimeoutCancellationException) {
                "连接超时：电脑可能不在线，或者中继连不上"
            } else {
                t.message ?: "连接失败"
            }
            fail(
                when (t) {
                    is RemoteError.Unauthorized -> "AUTH_INVALID"
                    else -> "CONNECTION_FAILED"
                },
                message,
            )
            throw t
        }
    }

    private fun startHandshake() {
        _phase.value = Phase.HANDSHAKING
        val conn = connectionId ?: throw RemoteError.Protocol("没有 connectionId")
        val session = NoiseIk.initiator(
            localPrivate = identityPrivateKey,
            remotePublic = hostIdentityKey,
            prologue = RemoteProtocol.prologue(conn, hostDeviceId, deviceId),
        )
        noise = session
        val message = session.writeHandshake()
        sendControl(
            "secure.handshake",
            buildJsonObject {
                put("connectionId", conn)
                put("targetDeviceId", hostDeviceId)
                put("step", 1)
                put("data", message.toBase64Url())
            },
        )
    }

    // ---------------------------------------------------------------- RPC

    /**
     * 一次一元调用。
     *
     * 返回的是 `result.value`；`result.ok == false` 抛 [RemoteError.Remote]。
     * 隧道级的失败（`rpc.error`）与业务失败要分开：前者重试有意义，后者没有。
     */
    suspend fun call(
        endpoint: String,
        args: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 40_000,
    ): JsonElement {
        requireReady()
        val id = wire.uuid()
        val waiter = CompletableDeferred<JsonElement>()
        pending[id] = waiter
        try {
            sendSecure(
                wire.request(
                    buildJsonObject {
                        put("method", RemoteProtocol.METHOD_CALL)
                        put(
                            "params",
                            buildJsonObject {
                                put("endpoint", endpoint)
                                put("payload", buildJsonObject { put("args", args) })
                            },
                        )
                    },
                    id = id,
                )
            )
            val result = withTimeout(timeoutMs) { waiter.await() }
            return unwrap(result)
        } finally {
            pending.remove(id)
        }
    }

    private fun unwrap(result: JsonElement): JsonElement {
        val obj = result as? JsonObject ?: return result
        if (obj.bool("ok") == true) return obj["value"] ?: JsonNull
        val error = obj.obj("error")
        throw RemoteError.Remote(
            errorCode = error?.str("code") ?: "RPC_FAILED",
            message = error?.str("message") ?: "服务端拒绝了这个调用",
        )
    }

    // ---------------------------------------------------------------- 流

    /**
     * 打开一条逻辑流。
     *
     * 取消收集会发 `harness.remote.stream.close` —— 否则 Host 那边会一直往
     * 一条没人读的流里推帧，直到连接上限。
     */
    fun openStream(endpoint: String, args: JsonObject = JsonObject(emptyMap())): Flow<JsonElement> {
        val streamId = wire.uuid()
        return channelFlow {
            val sink = StreamSink(Channel(capacity = 64))
            streams[streamId] = sink
            try {
                sendSecure(
                    wire.request(
                        buildJsonObject {
                            put("method", RemoteProtocol.METHOD_STREAM_OPEN)
                            put(
                                "params",
                                buildJsonObject {
                                    put("streamId", streamId)
                                    put("endpoint", endpoint)
                                    put("payload", buildJsonObject { put("args", args) })
                                },
                            )
                        }
                    )
                )
                for (value in sink.channel) send(value)
            } finally {
                streams.remove(streamId)
                runCatching { closeStream(streamId) }
                sink.channel.cancel()
            }
        }
    }

    private fun closeStream(streamId: String) {
        if (_phase.value != Phase.READY) return
        sendSecure(
            wire.request(
                buildJsonObject {
                    put("method", RemoteProtocol.METHOD_STREAM_CLOSE)
                    put("params", buildJsonObject { put("streamId", streamId) })
                }
            )
        )
    }

    /** `$events` 上的 Host 事件（审批、会话状态）。由 [RemoteLink] 订阅 */
    val hostEvents: Flow<JsonElement> by lazy { eventsFlow() }

    private fun eventsFlow(): Flow<JsonElement> = channelFlow {
        val id = eventsStreamId ?: error("还没有建立事件流")
        val sink = streams[id] ?: error("事件流已经关闭")
        for (value in sink.channel) send(value)
    }

    /** 回答一个 `$events` 瀑布（审批 / 提问） */
    suspend fun respond(eventId: String, outcome: JsonObject) {
        val id = clientId ?: throw RemoteError.Protocol("事件流还没就绪")
        call(
            RemoteProtocol.EVENTS_RESULT,
            buildJsonObject {
                put("clientId", id)
                put("eventId", eventId)
                put("outcome", outcome)
            },
        )
    }

    // ---------------------------------------------------------------- 关闭

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            _phase.value = Phase.CLOSED
        }
        watchdog?.cancel()
        watchdog = null
        runCatching { control?.close(1000, "client closed") }
        runCatching { data?.close(1000, "client closed") }
        control = null
        data = null
        noise = null
        failWaiters(RemoteError.Closed("连接已关闭"))
    }

    private fun fail(code: String, message: String) {
        synchronized(lock) {
            if (closed) return
            closed = true
            _phase.value = Phase.CLOSED
            _failure.value = message
        }
        watchdog?.cancel()
        watchdog = null
        Logx.w("remote", "隧道断开：$code — $message")
        runCatching { control?.close(4000, code) }
        runCatching { data?.close(4000, code) }
        control = null
        data = null
        noise = null
        // 在等待的这几步必须以异常结束：完成成"成功"的话，上层会接着往下走，
        // 最后拿到一句"连接已关闭"，真正的原因（HOST_OFFLINE 之类）就丢了
        val error = RemoteError.Remote(code, message)
        if (!accepted.isCompleted) accepted.completeExceptionally(error)
        if (!dataReady.isCompleted) dataReady.completeExceptionally(error)
        if (!handshakeDone.isCompleted) handshakeDone.completeExceptionally(error)
        if (!eventsReady.isCompleted) eventsReady.completeExceptionally(error)
        failWaiters(error)
    }

    private fun completeQuietly(d: CompletableDeferred<Unit>) {
        if (!d.isCompleted) d.complete(Unit)
    }

    private fun failWaiters(error: RemoteError) {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.completeExceptionally(error)
        }
        for ((id, sink) in streams) {
            streams.remove(id)
            sink.channel.close(error)
        }
    }

    /** 解密、握手、加密共用一把锁：Noise 的 nonce 只有在严格串行时才有意义 */
    private inline fun locked(block: () -> Unit) = synchronized(lock, block)

    private fun requireReady() {
        if (_phase.value != Phase.READY) {
            throw _failure.value?.let { RemoteError.Closed(it) } ?: RemoteError.Closed("隧道还没就绪")
        }
    }

    // ---------------------------------------------------------------- 发送

    private fun sendControl(type: String, payload: JsonObject = JsonObject(emptyMap())) {
        val socket = control ?: throw RemoteError.Closed("控制通道已经关闭")
        lastInbound = System.currentTimeMillis()
        socket.send(wire.encode(wire.frame(type, payload)))
    }

    private fun sendData(type: String, payload: JsonObject) {
        val socket = data ?: control ?: throw RemoteError.Closed("数据通道已经关闭")
        socket.send(wire.encode(wire.frame(type, payload)))
    }

    /** 把一条业务消息加密后发出去，超大消息先切 DSHF 分片 */
    private fun sendSecure(envelope: RemoteEnvelope) {
        val session = noise ?: throw RemoteError.Closed("握手还没完成")
        val plain = wire.encode(envelope).toByteArray(Charsets.UTF_8)
        val fragments = framing.encode(plain)
        val conn = connectionId ?: throw RemoteError.Protocol("没有 connectionId")
        // 加密不能只锁住"发送"这一段：nonce 在 encrypt 里递增，
        // 两个线程同时加密会拿到同一个 counter，对端直接按重放丢弃。
        synchronized(lock) {
            for (fragment in fragments) {
                val counter = session.sendingNonce
                val ciphertext = session.encrypt(fragment)
                val payload = buildJsonObject {
                    put("connectionId", conn)
                    put("targetDeviceId", hostDeviceId)
                    put("counter", counter)
                    put("ciphertext", ciphertext.toBase64Url())
                }
                if (data != null) sendData("relay", payload) else sendControl("relay", payload)
            }
        }
    }

    // ---------------------------------------------------------------- 接收

    private fun onControlText(text: String) {
        val envelope = try {
            wire.decode(text)
        } catch (e: RemoteError) {
            fail("INVALID_MESSAGE", "服务端发来一帧看不懂的数据：${text.take(120)}")
            return
        }
        lastInbound = System.currentTimeMillis()
        when (envelope.type) {
            "hello.ack" -> onHelloAck(envelope.payload)
            "connect.accepted" -> onAccepted(envelope.payload)
            "connect.rejected" -> {
                val code = envelope.payload.str("code") ?: "CONNECTION_REJECTED"
                val message = envelope.payload.str("message") ?: "电脑拒绝了这次连接"
                fail(code, message)
            }
            "error" -> {
                val code = envelope.payload.str("code") ?: "CONNECTION_FAILED"
                fail(code, envelope.payload.str("message") ?: "服务端返回错误")
            }
            "secure.handshake" -> onHandshakeStep(envelope.payload)
            "relay" -> onRelayFrame(envelope.payload)
            "ping" -> {
                val nonce = envelope.payload.str("nonce") ?: return
                runCatching { sendControl("pong", buildJsonObject { put("nonce", nonce) }) }
            }
            else -> Unit
        }
    }

    private fun onHelloAck(payload: JsonObject) {
        if (payload.int("protocol") != RemoteProtocol.VERSION) {
            fail("UNSUPPORTED_VERSION", "服务端只支持协议 ${payload["protocol"]}，这个版本还不认识")
            return
        }
        heartbeatMs = payload.long("heartbeatIntervalMs")?.takeIf { it > 0 } ?: heartbeatMs
        val caps = (payload["capabilities"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.textOrNull() }
        if (caps != null && RemoteProtocol.RELAY_CAPABILITY !in caps) {
            fail("CAPABILITY_NOT_SUPPORTED", "服务端没有开放 Relay，手表这边连不上")
            return
        }
        runCatching {
            sendControl(
                "connect.request",
                buildJsonObject {
                    put("hostDeviceId", hostDeviceId)
                    put("preferredTransports", kotlinx.serialization.json.JsonArray(
                        listOf(kotlinx.serialization.json.JsonPrimitive("relay"))
                    ))
                },
            )
        }.onFailure { fail("CONNECTION_FAILED", it.message ?: "发不出 connect.request") }
    }

    private fun onAccepted(payload: JsonObject) {
        val conn = payload.str("connectionId") ?: run {
            fail("INVALID_MESSAGE", "connect.accepted 没有 connectionId")
            return
        }
        connectionId = conn
        _phase.value = Phase.NEGOTIATING
        relayEndpoint = payload.str("relayEndpoint")
        relayTicket = payload.str("relayTicket")
        relayEpoch = payload.int("relayEpoch")
        val endpoint = relayEndpoint
        val ticket = relayTicket
        if (endpoint == null || ticket == null) {
            // 老服务器（或没有 Relay 分配）直接在控制通道上跑密文
            completeQuietly(dataReady)
            accepted.complete(Unit)
            return
        }
        val socket = client.newWebSocket(
            Request.Builder().url(endpoint).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = onDataText(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail("CONNECTION_FAILED", "数据通道断了：${t.message ?: "未知原因"}")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!closed) fail("CONNECTION_FAILED", "数据通道关闭（$code）")
                }
            },
        )
        data = socket
        // 票据只走数据通道，不上控制通道 —— 它是这条数据面的唯一凭证
        socket.send(
            wire.encode(
                wire.frame(
                    "relay.attach",
                    buildJsonObject {
                        put("connectionId", conn)
                        put("deviceId", deviceId)
                        put("peerDeviceId", hostDeviceId)
                        put("ticket", ticket)
                    },
                )
            )
        )
        accepted.complete(Unit)
    }

    private fun onDataText(text: String) {
        val envelope = try {
            wire.decode(text)
        } catch (e: RemoteError) {
            fail("INVALID_MESSAGE", "数据通道发来一帧看不懂的数据：${text.take(120)}")
            return
        }
        lastInbound = System.currentTimeMillis()
        when (envelope.type) {
            "relay.ready" -> {
                val conn = connectionId
                val epoch = envelope.payload.int("epoch")
                if (conn != null && envelope.payload.str("connectionId") != conn) {
                    fail("INVALID_MESSAGE", "数据面的 connectionId 对不上")
                    return
                }
                if (relayEpoch != null && epoch != null && epoch != relayEpoch) {
                    fail("INVALID_MESSAGE", "数据面的 epoch 对不上")
                    return
                }
                runCatching {
                    val id = connectionId ?: return@runCatching
                    sendControl(
                        "transport.selected",
                        buildJsonObject {
                            put("connectionId", id)
                            put("targetDeviceId", hostDeviceId)
                            put("transport", "relay")
                        },
                    )
                }
                completeQuietly(dataReady)
            }
            "relay.detach" -> fail("CONNECTION_FAILED", "电脑从 Relay 节点脱离")
            "secure.handshake" -> onHandshakeStep(envelope.payload)
            "relay" -> onRelayFrame(envelope.payload)
            "ping" -> {
                val nonce = envelope.payload.str("nonce") ?: return
                runCatching { sendData("pong", buildJsonObject { put("nonce", nonce) }) }
            }
            else -> Unit
        }
    }

    private fun onHandshakeStep(payload: JsonObject) = locked { handleHandshakeStep(payload) }

    private fun handleHandshakeStep(payload: JsonObject) {
        val step = payload.int("step")
        if (step != 2) {
            fail("NOISE_HANDSHAKE_ORDER", "收到了顺序不对的握手消息（step=$step）")
            return
        }
        val session = noise ?: run {
            fail("SECURE_CHANNEL_FAILED", "还没开始握手就收到了应答")
            return
        }
        val raw = payload.str("data")?.let { runCatching { it.fromBase64Url() }.getOrNull() }
        if (raw == null) {
            fail("SECURE_CHANNEL_FAILED", "握手消息不是合法的 base64url")
            return
        }
        try {
            session.readHandshake(raw)
            if (!session.complete) throw NoiseError("握手没有完成")
        } catch (e: Throwable) {
            fail(
                if (e is NoiseError && e.message?.contains("身份") == true) "PEER_IDENTITY_MISMATCH"
                else "SECURE_CHANNEL_FAILED",
                e.message ?: "握手失败",
            )
            return
        }
        completeQuietly(handshakeDone)
        openHostEvents()
    }

    private fun onRelayFrame(payload: JsonObject) = locked { handleRelayFrame(payload) }

    private fun handleRelayFrame(payload: JsonObject) {
        val session = noise ?: return
        if (!session.complete) {
            fail("SECURE_CHANNEL_FAILED", "握手还没完成就收到了密文")
            return
        }
        val counter = payload.long("counter")
        if (counter == null || counter != session.receivingNonce) {
            // 重放、丢帧、乱序都会走到这里。继续解下去会得到一个错的 nonce，
            // 那之后的每条消息都会失败，不如立刻关门。
            fail("SECURE_CHANNEL_FAILED", "密文的序号对不上（期望 ${session.receivingNonce}，收到 $counter）")
            return
        }
        val cipher = payload.str("ciphertext")?.let { runCatching { it.fromBase64Url() }.getOrNull() }
        if (cipher == null) {
            fail("SECURE_CHANNEL_FAILED", "密文不是合法的 base64url")
            return
        }
        val plain = try {
            session.decrypt(cipher)
        } catch (e: Throwable) {
            fail("SECURE_CHANNEL_FAILED", e.message ?: "解密失败")
            return
        }
        val message = try {
            framing.decode(plain)
        } catch (e: Throwable) {
            fail("SECURE_CHANNEL_FAILED", e.message ?: "分片重组失败")
            return
        } ?: return
        dispatchSecure(message)
    }

    private fun dispatchSecure(bytes: ByteArray) {
        val envelope = try {
            wire.decode(String(bytes, Charsets.UTF_8))
        } catch (e: RemoteError) {
            fail("INVALID_MESSAGE", "业务消息格式不对：${e.message}")
            return
        }
        when (envelope.type) {
            "rpc.response" -> {
                val id = envelope.payload.str("requestId") ?: return
                pending.remove(id)?.let { waiter ->
                    val result = envelope.payload["result"] ?: JsonNull
                    if (result is JsonObject && result.bool("ok") == false) {
                        val error = result.obj("error")
                        waiter.completeExceptionally(
                            RemoteError.Remote(
                                error?.str("code") ?: "RPC_FAILED",
                                error?.str("message") ?: "调用失败",
                            )
                        )
                    } else {
                        waiter.complete(result)
                    }
                }
            }
            "rpc.error" -> {
                val id = envelope.payload.str("requestId") ?: return
                pending.remove(id)?.completeExceptionally(
                    RemoteError.Remote(
                        envelope.payload.str("code") ?: "RPC_FAILED",
                        envelope.payload.str("message") ?: "调用失败",
                    )
                )
            }
            "event" -> onHostEvent(envelope.payload)
            "ping" -> runCatching {
                sendSecure(wire.frame("pong", buildJsonObject { put("nonce", envelope.payload.str("nonce") ?: "") }))
            }
            else -> Unit
        }
    }

    private fun onHostEvent(payload: JsonObject) {
        val name = payload.str("event") ?: return
        val data = payload["data"]
        when (name) {
            RemoteProtocol.EVENT_FRAME -> {
                val frame = data as? JsonObject ?: return
                val streamId = frame.str("streamId") ?: return
                val sink = streams[streamId] ?: return
                // hasValue: true 但 value 缺失 = 这个流产生了一个 undefined item，
                // 不能当成"流结束"（流结束走 stream.closed）
                if (frame.bool("hasValue") == false) return
                val value = frame["value"] ?: JsonNull
                sink.channel.trySend(value)
                if (streamId == eventsStreamId) onEventsItem(value)
            }
            RemoteProtocol.EVENT_STREAM_CLOSED -> {
                val frame = data as? JsonObject ?: return
                val streamId = frame.str("streamId") ?: return
                val reason = frame.str("reason") ?: "completed"
                val sink = streams.remove(streamId) ?: return
                if (reason == "failed") {
                    val failure = frame.obj("failure")
                    sink.channel.close(
                        RemoteError.Remote(
                            failure?.str("code") ?: "STREAM_FAILED",
                            failure?.str("message") ?: "流出错",
                        )
                    )
                } else {
                    sink.channel.close()
                }
                // $events 结束等于这一代连接失效，整条隧道都得重来
                if (streamId == eventsStreamId && !closed) {
                    fail("CONNECTION_FAILED", "事件流结束了（$reason）")
                }
            }
        }
    }

    private fun onEventsItem(value: JsonElement) {
        val obj = value as? JsonObject ?: return
        if (obj.str("type") == "ready") {
            val id = obj.str("clientId") ?: return
            home = obj.obj("host")?.str("home") ?: ""
            if (!eventsReady.isCompleted) eventsReady.complete(id)
        }
    }

    private fun openHostEvents() {
        val streamId = wire.uuid()
        eventsStreamId = streamId
        val sink = StreamSink(Channel(capacity = 64))
        streams[streamId] = sink
        sendSecure(
            wire.request(
                buildJsonObject {
                    put("method", RemoteProtocol.METHOD_STREAM_OPEN)
                    put(
                        "params",
                        buildJsonObject {
                            put("streamId", streamId)
                            put("endpoint", RemoteProtocol.HOST_EVENTS)
                            put("payload", buildJsonObject { put("args", JsonObject(emptyMap())) })
                        },
                    )
                }
            )
        )
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = Timer("dsh-remote-watchdog", true).also { timer ->
            timer.scheduleAtFixedRate(
                object : TimerTask() {
                    override fun run() {
                        if (closed) {
                            timer.cancel()
                            return
                        }
                        val silent = System.currentTimeMillis() - lastInbound
                        if (silent > heartbeatMs * 3) {
                            fail("CONNECTION_FAILED", "连接静默超过 ${heartbeatMs * 3 / 1000} 秒")
                            return
                        }
                        runCatching {
                            sendSecure(wire.frame("ping", buildJsonObject { put("nonce", wire.uuid()) }))
                        }
                    }
                },
                heartbeatMs,
                heartbeatMs,
            )
        }
    }

    // ---------------------------------------------------------------- 内部件

    private class StreamSink(val channel: Channel<JsonElement>)

    private inner class ControlListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = onControlText(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.let { if (it.code == 401 || it.code == 403) "AUTH_INVALID" else "CONNECTION_FAILED" }
                ?: "CONNECTION_FAILED"
            fail(code, response?.let { "控制通道 HTTP ${it.code}" } ?: (t.message ?: "控制通道断开"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed) return
            // 4003 = 同一设备的另一条连接顶掉了这条；这不是"网络抖了一下"
            fail(if (code == 4003) "CONNECTION_REPLACED" else "CONNECTION_FAILED", reason.ifBlank { "控制通道关闭（$code）" })
        }
    }
}

/** 一个 `$events` 瀑布：审批或提问，以及它是给哪个会话的 */
data class HostWaterfall(
    val eventId: String,
    val event: String,
    val agentId: String,
    val request: JsonObject,
)

/** 从 `$events` 的一帧里认出瀑布；不是瀑布时返回 null */
fun waterfallOf(value: JsonElement): HostWaterfall? {
    val obj = value as? JsonObject ?: return null
    if (obj.str("type") != "waterfall") return null
    val event = obj.str("event") ?: return null
    val eventId = obj.str("eventId") ?: return null
    return HostWaterfall(
        eventId = eventId,
        event = event,
        agentId = obj.str("agentId") ?: "",
        request = obj.obj("request") ?: JsonObject(emptyMap()),
    )
}

/** 单条密文的明文上限，给单测用 */
internal const val MAX_NOISE_PLAINTEXT = 65535

/** 一条业务消息最多切成几片 */
internal fun fragmentsFor(size: Int): Int = (size + CHUNK_BYTES - 1) / CHUNK_BYTES
