package com.dshwatch.domain.agent.mcp

import com.dshwatch.net.Http
import com.dshwatch.net.SseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** MCP 调用失败。message 直接给用户/模型看，所以要写清楚下一步 */
class McpException(message: String) : Exception(message)

/**
 * 一个远程 MCP server 的客户端（JSON-RPC 2.0 over HTTP）。
 *
 * ## 为什么自己写而不用官方 Kotlin SDK
 *
 * 官方 SDK 走 KMP + Ktor。对一台 lite 包只有 6MB 的手表来说，多拖一个
 * Ktor client（含引擎与序列化插件）是数 MB 级增量，而这个功能只需要
 * 三个方法：`initialize` / `tools/list` / `tools/call`。
 * 手写两个文件（本文件 + [McpModels]）体积近乎为零，而且 SSE 解析
 * 可以直接复用项目里已经验证过的 [SseReader]。
 *
 * ## 协议版本：先握手，失败降级
 *
 * 2026-07-28 的规范把 MCP 改成了无状态（移除 `initialize` 与 session）。
 * 但主流 server 目前仍然只认 `2025-11-25` 的握手流程。所以：
 *
 * 1. 先按 `2025-11-25` 发 `initialize`；
 * 2. 若 server 明确表示版本不支持，**降级为无状态**：不发握手、
 *    每个请求带 `MCP-Protocol-Version` 与 `Mcp-Method` 头。
 *
 * 反过来（先试无状态）不行：旧 server 收到没有 `initialize` 的 `tools/list`
 * 会直接 400，而错误信息通常只说"not initialized"，看不出该降级还是该重试。
 */
class McpClient(
    val serverId: String,
    val serverName: String,
    private val endpoint: String,
    private val token: String?,
    private val clientVersion: String = "1.4",
) {

    private val nextId = AtomicLong(1L)
    private var sessionId: String? = null
    private var stateless = false

    /** 握手成功后 server 告知的协议版本；无状态模式下是我们自己声明的版本 */
    var protocolVersion: String = HANDSHAKE_PROTOCOL
        private set

    suspend fun connect(): McpConnectOutcome = withContext(Dispatchers.IO) {
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return@withContext McpConnectOutcome.Failed(
                "地址要以 http:// 或 https:// 开头。MCP 的标准输入（stdio）传输在手表上不可用 —— " +
                    "Android 不允许应用启动私有目录里的可执行文件，所以只能连远程 server。"
            )
        }

        val handshake = runCatching {
            rpc(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", HANDSHAKE_PROTOCOL)
                    putJsonObject("capabilities") { }
                    putJsonObject("clientInfo") {
                        put("name", "dsh-watch")
                        put("version", clientVersion)
                    }
                },
            )
        }

        val error = handshake.getOrNull()?.error
        when {
            handshake.isSuccess && error == null -> {
                protocolVersion = handshake.getOrNull()
                    ?.result?.let { McpProtocol.readVersion(it) }
                    ?: HANDSHAKE_PROTOCOL
                // initialized 是"尽力而为"的通知：某些实现根本不检查它，
                // 它失败不该让整个连接失败
                runCatching { notify("notifications/initialized") }
                stateless = false
            }

            isProtocolMismatch(error, handshake.exceptionOrNull()) -> {
                stateless = true
                protocolVersion = STATELESS_PROTOCOL
            }

            else -> {
                val reason = error?.let { "server 报错 ${it.code}：${it.message}" }
                    ?: handshake.exceptionOrNull()?.let { friendly(it) }
                    ?: "握手失败"
                return@withContext McpConnectOutcome.Failed(reason)
            }
        }

        val listed = runCatching { rpc("tools/list", null) }
        listed.exceptionOrNull()?.let { return@withContext McpConnectOutcome.Failed(friendly(it)) }
        val response = listed.getOrNull() ?: return@withContext McpConnectOutcome.Failed("没有收到响应")
        response.error?.let {
            return@withContext McpConnectOutcome.Failed("server 拒绝列出工具：${it.message}")
        }

        val tools = JsonRpc.parseTools(response.result)
        McpConnectOutcome.Ok(tools = tools, protocol = protocolVersion, stateless = stateless)
    }

    suspend fun callTool(name: String, args: JsonObject): McpCallResult = withContext(Dispatchers.IO) {
        val response = rpc(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
            // 工具调用可能很慢（抓网页、查数据库），给足时间
            methodForHeader = name,
        )
        response.error?.let { throw McpException("工具调用失败：${it.message}") }
        JsonRpc.parseCallResult(response.result)
    }

    // ------------------------------------------------------------------

    private suspend fun rpc(
        method: String,
        params: JsonObject?,
        methodForHeader: String? = null,
    ): JsonRpc.Message = withContext(Dispatchers.IO) {
        val id = nextId.getAndIncrement()
        val request = buildRequest(
            body = JsonRpc.request(id, method, params),
            method = method,
            toolName = methodForHeader,
        )
        execute(request) { source, contentType ->
            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                readFromSse(source, id)
            } else {
                val text = source.readUtf8()
                JsonRpc.parse(text) ?: throw McpException("无法解析响应：${text.take(200)}")
            }
        }
    }

    private suspend fun notify(method: String) = withContext(Dispatchers.IO) {
        val request = buildRequest(body = JsonRpc.notification(method), method = method, toolName = null)
        execute(request) { source, _ ->
            // 通知的响应体没有内容；读一下防止连接泄漏
            runCatching { source.readUtf8() }
        }
    }

    private fun buildRequest(body: String, method: String, toolName: String?): Request =
        Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(Http.JSON_MEDIA))
            .header("Accept", "application/json, text/event-stream")
            .apply {
                token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                sessionId?.let { header("Mcp-Session-Id", it) }
                if (stateless) {
                    header("MCP-Protocol-Version", STATELESS_PROTOCOL)
                    header("Mcp-Method", method)
                    toolName?.let { header("Mcp-Name", it) }
                }
            }
            .build()

    /**
     * 统一处理 HTTP 层的错误。
     *
     * 这几条文案是刻意写具体的：MCP 的配置错误几乎全落在这几个状态码上，
     * 而模型看到"连接失败"只会回一句"失败了，请检查配置"，用户不知道该改什么。
     */
    private fun <T> execute(
        request: Request,
        parse: (BufferedSource, String) -> T,
    ): T {
        val call = Http.client.newCall(request)
        call.timeout().timeout(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

            if (!response.isSuccessful) {
                val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("").take(300)
                throw McpException(httpHint(response.code, body))
            }

            val contentType = response.header("Content-Type").orEmpty()
            val source = response.body?.source() ?: throw McpException("server 返回了空响应")
            return parse(source, contentType)
        }
    }

    private fun httpHint(code: Int, body: String): String = when (code) {
        401, 403 -> "认证失败（HTTP $code）：检查这个 server 的令牌是否有权限。"
        404 -> "地址不对（HTTP 404）：确认 MCP endpoint 的完整地址，通常以 /mcp 或 /mcp/ 结尾而不是站根目录。"
        405 -> "server 不接受 POST（HTTP 405）：这个地址可能不是 MCP endpoint。"
        429 -> "被限流（HTTP 429）：稍后再试。"
        else -> "server 返回 HTTP $code：$body"
    }

    private fun readFromSse(source: BufferedSource, id: Long): JsonRpc.Message {
        val reader = SseReader(source)
        var guard = 0
        while (guard++ < MAX_SSE_EVENTS) {
            val event = reader.read() ?: throw McpException("连接已关闭，没等到响应")
            if (event.data.isBlank()) continue
            val message = JsonRpc.parse(event.data) ?: continue
            // server 主动推的通知（进度、日志）不是响应，跳过
            if (message.method != null) continue
            if (message.id == id) return message
        }
        throw McpException("server 发了太多无关消息，放弃等待")
    }

    private fun friendly(t: Throwable): String = when (t) {
        is McpException -> t.message.orEmpty()
        is java.net.UnknownHostException -> "域名解析失败：检查地址拼写与网络。"
        is javax.net.ssl.SSLException -> "TLS 握手失败：这个地址的证书有问题，或者它其实是 http 而不是 https。"
        is java.net.SocketTimeoutException -> "连接超时：server 没响应，或者网络不通。"
        else -> "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
    }

    /**
     * 判断"版本不支持"。
     *
     * 有两种表现：显式的 `UnsupportedProtocolVersion` 错误码，
     * 或者只给一句人话（"Unsupported protocol version: 2025-11-25"）。
     * 两种都要认 —— 只认错误码的话，遇到第二种会当成普通失败直接放弃，
     * 而它其实完全可以用无状态模式工作。
     */
    private fun isProtocolMismatch(error: JsonRpc.JsonRpcError?, thrown: Throwable?): Boolean {
        if (error != null) {
            if (error.code == UNSUPPORTED_PROTOCOL_CODE) return true
            val text = (error.message + " " + error.data.orEmpty()).lowercase()
            if ("protocol" in text && ("unsupport" in text || "version" in text)) return true
        }
        val thrownText = thrown?.message.orEmpty().lowercase()
        return thrown != null && "protocol" in thrownText && "version" in thrownText
    }

    companion object {
        /** 主流 server 仍在用的版本 */
        const val HANDSHAKE_PROTOCOL = "2025-11-25"

        /** 无状态规范版本 */
        const val STATELESS_PROTOCOL = "2026-07-28"

        private const val UNSUPPORTED_PROTOCOL_CODE = -32000
        private const val RPC_TIMEOUT_MS = 60_000L
        private const val MAX_SSE_EVENTS = 200
    }
}

sealed interface McpConnectOutcome {
    data class Ok(
        val tools: List<McpToolInfo>,
        val protocol: String,
        /** 是否降级成了无状态模式 —— 设置页会把这个显示出来，方便排查 */
        val stateless: Boolean,
    ) : McpConnectOutcome

    data class Failed(val message: String) : McpConnectOutcome
}

/** 只做一点小解析，单独放一个内部对象免得污染主类 */
internal object McpProtocol {
    fun readVersion(result: kotlinx.serialization.json.JsonElement): String? =
        runCatching {
            (result as JsonObject)["protocolVersion"]?.let {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
