package com.dshwatch.domain.agent.mcp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * MCP 协议层测试，用 JDK 自带的 HttpServer 起一个假 server。
 *
 * 为什么不引入 MockWebServer：这个项目至今没有、也不需要它。
 * `com.sun.net.httpserver` 是 JDK 内置的，能覆到我们唯一关心的东西 ——
 * **真实的 HTTP 往返 + Content-Type 分支 + SSE 流**，而这正是手写 JSON-RPC
 * 最容易出错的地方。多一个测试依赖换不来更多保障。
 */
class McpClientTest {

    private val servers = mutableListOf<FakeServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        servers.clear()
    }

    private fun server(route: (JsonObject) -> FakeResponse): FakeServer =
        FakeServer(route).also { servers += it }

    // ---------------- 握手 ----------------

    @Test
    fun `handshake then list tools`() : Unit = runBlocking {
        val fake = server { req ->
            val id = req["id"].orEmptyLiteral()
            when (req.method()) {
                "initialize" -> FakeResponse(
                    200,
                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25",
                       "capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}}""",
                )
                "notifications/initialized" -> FakeResponse(202, "")
                "tools/list" -> FakeResponse(
                    200,
                    """{"jsonrpc":"2.0","id":$id,"result":{"tools":[
                       {"name":"search","description":"搜索仓库","inputSchema":{"type":"object","properties":{}}},
                       {"name":"create_issue","description":"建 issue","inputSchema":{"type":"object"}}
                    ]}}""",
                )
                else -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no"}}""")
            }
        }

        val outcome = McpClient("s1", "Fake", fake.url, token = null).connect()

        assertThat(outcome).isInstanceOf(McpConnectOutcome.Ok::class.java)
        val ok = outcome as McpConnectOutcome.Ok
        assertThat(ok.tools.map { it.name }).containsExactly("search", "create_issue")
        assertThat(ok.protocol).isEqualTo("2025-11-25")
        assertThat(ok.stateless).isFalse()
        // 握手后必须发 initialized 通知，否则不少 server 会拒绝后续请求
        assertThat(fake.methods()).contains("notifications/initialized")
    }

    @Test
    fun `bearer token is sent when configured`() : Unit = runBlocking {
        val fake = server { req ->
            val id = req["id"].orEmptyLiteral()
            when (req.method()) {
                "initialize" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25"}}""")
                "notifications/initialized" -> FakeResponse(202, "")
                "tools/list" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}""")
                else -> FakeResponse(200, "{}")
            }
        }
        McpClient("s1", "Fake", fake.url, token = "secret-token").connect()

        assertThat(fake.lastAuthHeader).isEqualTo("Bearer secret-token")
    }

    @Test
    fun `session id from response is echoed back`() : Unit = runBlocking {
        val fake = server { req ->
            val id = req["id"].orEmptyLiteral()
            val headers = if (req.method() == "initialize") mapOf("Mcp-Session-Id" to "sess-42") else emptyMap()
            when (req.method()) {
                "initialize" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25"}}""", headers = headers)
                "notifications/initialized" -> FakeResponse(202, "")
                "tools/list" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}""")
                else -> FakeResponse(200, "{}")
            }
        }

        McpClient("s1", "Fake", fake.url, null).connect()

        // tools/list 那一发必须带上 initialize 返回的 session
        assertThat(fake.sessionHeaders()).contains("sess-42")
    }

    // ---------------- SSE ----------------

    @Test
    fun `parses sse response and skips notifications`() : Unit = runBlocking {
        val fake = server { req ->
            val id = req["id"].orEmptyLiteral()
            when (req.method()) {
                "initialize" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25"}}""")
                "notifications/initialized" -> FakeResponse(202, "")
                "tools/list" -> FakeResponse(
                    200,
                    // 中间插一条 server 推送的通知：真实 server 会这么干（进度、日志）
                    "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"p\":1}}\n" +
                        "\n" +
                        "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"t1\",\"description\":\"d\",\"inputSchema\":{}}]}}\n" +
                        "\n",
                    contentType = "text/event-stream",
                )
                else -> FakeResponse(200, "{}")
            }
        }

        val outcome = McpClient("s1", "Fake", fake.url, null).connect()

        val ok = outcome as McpConnectOutcome.Ok
        assertThat(ok.tools.map { it.name }).containsExactly("t1")
    }

    // ---------------- 版本降级 ----------------

    @Test
    fun `falls back to stateless when the version is rejected`() : Unit = runBlocking {
        val fake = server { req ->
            val id = req["id"].orEmptyLiteral()
            when (req.method()) {
                "initialize" -> FakeResponse(
                    200,
                    """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"Unsupported protocol version: 2025-11-25"}}""",
                )
                "tools/list" -> FakeResponse(
                    200,
                    """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"x","description":"d","inputSchema":{}}]}}""",
                )
                else -> FakeResponse(200, "{}")
            }
        }

        val outcome = McpClient("s1", "Fake", fake.url, null).connect()

        val ok = outcome as McpConnectOutcome.Ok
        assertThat(ok.stateless).isTrue()
        assertThat(ok.tools.map { it.name }).containsExactly("x")
        // 降级后必须带上版本声明头，否则无状态的 server 无法判断该按哪版解析
        assertThat(fake.seenHeaders.any { it["MCP-Protocol-Version"] == McpClient.STATELESS_PROTOCOL }).isTrue()
    }

    @Test
    fun `version mismatch reported without the word protocol is still treated as failure`() : Unit = runBlocking {
        val fake = server { req ->
            val id = req["id"].orEmptyLiteral()
            // 只有一句人话、没有 protocol 字样 → 不能当成版本不兼容去降级
            FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"internal error"}}""")
        }

        val outcome = McpClient("s1", "Fake", fake.url, null).connect()

        assertThat(outcome).isInstanceOf(McpConnectOutcome.Failed::class.java)
        assertThat((outcome as McpConnectOutcome.Failed).message).contains("internal error")
    }

    // ---------------- 错误提示 ----------------

    @Test
    fun `401 tells the user to check the token`() : Unit = runBlocking {
        val fake = server { FakeResponse(401, """{"message":"bad credentials"}""") }

        val outcome = McpClient("s1", "Fake", fake.url, "wrong").connect()

        val failed = outcome as McpConnectOutcome.Failed
        assertThat(failed.message).contains("认证失败")
        assertThat(failed.message).contains("令牌")
    }

    @Test
    fun `404 points at the endpoint url`() : Unit = runBlocking {
        val fake = server { FakeResponse(404, "not found") }

        val outcome = McpClient("s1", "Fake", fake.url, null).connect()

        val failed = outcome as McpConnectOutcome.Failed
        assertThat(failed.message).contains("404")
        assertThat(failed.message).contains("endpoint")
    }

    @Test
    fun `non http endpoint is rejected before any network call`() : Unit = runBlocking {
        val outcome = McpClient("s1", "Fake", "ftp://example.com/mcp", null).connect()

        val failed = outcome as McpConnectOutcome.Failed
        assertThat(failed.message).contains("http")
    }

    // ---------------- tools/call ----------------

    @Test
    fun `call tool sends name and arguments`() : Unit = runBlocking {
        val fake = server { req ->
            val id = req["id"].orEmptyLiteral()
            when (req.method()) {
                "initialize" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25"}}""")
                "notifications/initialized" -> FakeResponse(202, "")
                "tools/list" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}""")
                "tools/call" -> FakeResponse(
                    200,
                    """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"结果在此"}],"isError":false}}""",
                )
                else -> FakeResponse(200, "{}")
            }
        }
        val client = McpClient("s1", "Fake", fake.url, null)
        client.connect()

        val result = client.callTool("search", Json.parseToJsonElement("""{"q":"x"}""").jsonObject)

        assertThat(result.text).isEqualTo("结果在此")
        assertThat(result.isError).isFalse()
        val call = fake.lastRequest()
        assertThat(call["params"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("search")
    }

    @Test
    fun `error flag and image content are surfaced`() : Unit = runBlocking {
        val fake = server { req ->
            val id = req["id"].orEmptyLiteral()
            when (req.method()) {
                "initialize" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25"}}""")
                "notifications/initialized" -> FakeResponse(202, "")
                "tools/list" -> FakeResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}""")
                "tools/call" -> FakeResponse(
                    200,
                    """{"jsonrpc":"2.0","id":$id,"result":{"isError":true,"content":[
                       {"type":"text","text":"权限不足"},
                       {"type":"image","mimeType":"image/png"}
                    ]}}""",
                )
                else -> FakeResponse(200, "{}")
            }
        }
        val client = McpClient("s1", "Fake", fake.url, null)
        client.connect()

        val result = client.callTool("x", Json.parseToJsonElement("{}").jsonObject)

        assertThat(result.isError).isTrue()
        assertThat(result.text).contains("权限不足")
        // 图片没法进模型上下文，但必须让模型知道"这里本来有张图"
        assertThat(result.text).contains("图片")
    }

    // ------------------------------------------------------------------

    private fun JsonObject.method(): String =
        this["method"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun kotlinx.serialization.json.JsonElement?.orEmptyLiteral(): String =
        this?.toString() ?: "null"

    data class FakeResponse(
        val status: Int,
        val body: String,
        val contentType: String = "application/json",
        val headers: Map<String, String> = emptyMap(),
    )

    /**
     * 极简 HTTP/1.1 假 server。
     *
     * 为什么不用 `com.sun.net.httpserver`：Android 的单元测试跑在 android.jar 桩上，
     * 那里**没有** com.sun.* 包（编译期就找不到类）。而引 MockWebServer 又要多一个依赖。
     * 这里只需要"读一个请求、写一个响应"，40 行 socket 代码换零依赖。
     *
     * 每条响应都带 `Connection: close`，强制 OkHttp 不复用连接 ——
     * 否则第二轮请求会复用上一条连接，而我们的循环已经把它关掉了。
     */
    private class FakeServer(private val route: (JsonObject) -> FakeResponse) {

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val requests = mutableListOf<JsonObject>()
        val seenHeaders = mutableListOf<Map<String, String>>()
        @Volatile
        var lastAuthHeader: String? = null

        init {
            Thread({
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { handle(client) }
                    runCatching { client.close() }
                }
            }, "fake-mcp").apply { isDaemon = true }.start()
        }

        private fun handle(client: Socket) {
            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
            reader.readLine() ?: return

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
                }
            }
            synchronized(seenHeaders) { seenHeaders += headers }
            headers.entries.firstOrNull { it.key.equals("Authorization", true) }
                ?.let { lastAuthHeader = it.value }

            val length = headers.entries
                .firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
            val buffer = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            val bodyText = String(buffer, 0, read)
            val json = runCatching { Json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
            if (json != null) synchronized(requests) { requests += json }

            val response = route(json ?: JsonObject(emptyMap()))
            val payload = response.body.toByteArray()
            val head = buildString {
                append("HTTP/1.1 ").append(response.status).append(" OK\r\n")
                response.headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("Content-Type: ").append(response.contentType).append("\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            client.getOutputStream().apply {
                write(head.toByteArray())
                write(payload)
                flush()
            }
        }

        val url: String get() = "http://127.0.0.1:${socket.localPort}/mcp"

        fun stop() = runCatching { socket.close() }.let { }

        fun methods(): List<String> =
            synchronized(requests) {
                requests.map { it["method"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            }

        fun sessionHeaders(): List<String> =
            synchronized(seenHeaders) {
                seenHeaders.mapNotNull { it.entries.firstOrNull { e -> e.key.equals("Mcp-Session-Id", true) }?.value }
            }

        fun lastRequest(): JsonObject = synchronized(requests) { requests.last() }
    }
}
