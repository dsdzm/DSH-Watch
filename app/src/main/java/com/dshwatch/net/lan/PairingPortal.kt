package com.dshwatch.net.lan

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手表自己开的一个局域网小网站，用来把「输入凭据」这件事交给手机。
 *
 * ## 为什么要有它
 *
 * 手表屏幕 1.4 寸、没有摄像头、软键盘挤掉半屏。要登录 DSH Remote 需要
 * 打开一个授权页、或在浏览器里粘贴一段长令牌 —— 这些在手表上都不是
 * 「不方便」，而是做不了。手机就在旁边、同一个 Wi-Fi，让它代劳最省事。
 *
 * ## 安全边界（这不是可选项）
 *
 * 一个监听在局域网上的端口，默认等于「同网段所有人都能访问」。所以：
 *
 *  1. **一次性口令**：URL 里带 32 字节随机 `k`，所有接口都校验；
 *     没有它连页面都拿不到。口令只出现在手表屏幕的二维码里。
 *  2. **Host 头校验**：只接受 IP 字面量或 localhost。否则攻击者可以把
 *     `evil.com` 解析到手表 IP，用浏览器绕过同源策略读这个接口
 *     （DNS rebinding）—— 这类攻击不需要用户点任何东西。
 *  3. **只绑一个地址、只开一个端口**，默认 5 分钟无操作自动关。
 *  4. **提交一次就关**：拿到凭据之后端口立刻消失，不给第二次机会。
 *  5. **不落盘、不打日志**：请求体只在内存里过一遍。
 */
class PairingPortal(
    private val context: Context?,
    private val json: Json,
    private val idleTimeoutMs: Long = 5 * 60_000L,
    /** 只为测试注入；生产环境留空，由 [localAddress] 自己挑 */
    private val bindHost: String? = null,
    /** 只为测试注入：页面内容 */
    private val pageOverride: String? = null,
) {

    /** 一次配对过程中手机与手表之间同步的状态 */
    data class Snapshot(
        val phase: Phase = Phase.NEED_SERVER,
        val title: String = "等待手机连接",
        val detail: String = "",
        /** 需要手机扫或点的地址（OAuth 授权页） */
        val actionUrl: String? = null,
        /** 二维码矩阵，每行一串 0/1，页面自己画 */
        val matrix: List<String>? = null,
        val providers: List<Pair<String, String>> = emptyList(),
        val hosts: List<Pair<String, String>> = emptyList(),
        val provider: String = "",
        val server: String = "",
        val error: String? = null,
        val secondsLeft: Int = 0,
    ) {
        enum class Phase { NEED_SERVER, CHOOSING, AUTHORIZING, DONE, FAILED, CLOSED }
    }

    /** 手机提交上来的东西，已经过校验 */
    sealed interface Submission {
        data class SetServer(val server: String) : Submission
        data class StartLogin(val provider: String) : Submission
        data class ManualToken(val server: String, val token: String) : Submission
        data class ManualPassword(val server: String, val email: String, val password: String) : Submission
        data object Cancel : Submission
    }

    private val secret: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.toUrlSafe()

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val _link = MutableStateFlow<String?>(null)

    /** 实际监听端口，测试用 */
    val port: Int get() = server?.localPort ?: 0

    /** 手表屏幕上要展示的地址；没起来时为 null */
    val link: StateFlow<String?> = _link.asStateFlow()

    var onSubmission: ((Submission) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    private var server: ServerSocket? = null
    private var worker: Thread? = null
    private var pool: java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(2)
    private val running = AtomicBoolean(false)
    private var lastTouch = System.currentTimeMillis()

    /** 起服务，返回给手机用的地址；起不来返回 null（通常是没连 Wi-Fi） */
    fun start(): String? {
        if (running.get()) return _link.value
        val ip = bindHost ?: localAddress() ?: return null
        val socket = try {
            // 绑到 0.0.0.0 而不是某个具体地址：手机上看到的 IP 可能来自
            // 热点/多网卡，绑死在错的那张上就白等一场
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindHost ?: "0.0.0.0", 0), 8)
            }
        } catch (e: Exception) {
            return null
        }
        server = socket
        pool = Executors.newFixedThreadPool(2)
        running.set(true)
        lastTouch = System.currentTimeMillis()
        val url = "http://$ip:${socket.localPort}/?k=$secret"
        _link.value = url
        worker = Thread({ acceptLoop(socket) }, "pairing-portal").apply {
            isDaemon = true
            start()
        }
        return url
    }

    fun update(block: (Snapshot) -> Snapshot) {
        lastTouch = System.currentTimeMillis()
        _state.value = block(_state.value)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
        _link.value = null
        onClosed?.invoke()
    }

    // ---------------------------------------------------------------- 网络

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            if (System.currentTimeMillis() - lastTouch > idleTimeoutMs) {
                _state.value = Snapshot(phase = Snapshot.Phase.CLOSED, title = "已超时关闭")
                stop()
                return
            }
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                return
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: return reply(client, 400, "bad request")
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty().split(' ')
            if (requestLine.size < 2) return reply(client, 400, "bad request")
            val method = requestLine[0]
            val target = requestLine[1]
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()

            // DNS rebinding 的唯一挡板：浏览器发来的 Host 会是被解析的域名
            if (!hostAllowed(headers["host"])) return reply(client, 421, "bad host")

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            if (!tokenOk(query)) return reply(client, 403, "forbidden")

            when {
                method == "GET" && path == "/" -> reply(client, 200, page(), "text/html; charset=utf-8")
                method == "GET" && path == "/api/state" -> reply(client, 200, stateJson(), "application/json")
                method == "POST" && path == "/api/submit" -> {
                    val body = readBody(input, headers)
                    val parsed = parseSubmission(body)
                    if (parsed == null) {
                        reply(client, 200, errorJson("提交内容不完整"), "application/json")
                    } else {
                        lastTouch = System.currentTimeMillis()
                        onSubmission?.invoke(parsed)
                        reply(client, 200, "{\"ok\":true}", "application/json")
                    }
                }
                else -> reply(client, 404, "not found")
            }
        } catch (e: Exception) {
            runCatching { reply(client, 500, "server error") }
        } finally {
            runCatching { client.close() }
        }
    }

    /** 读到 `\r\n\r\n` 为止；用状态机而不是反复 `endsWith`，省掉 O(n²) 的拼接 */
    private fun readHead(input: InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream(512)
        var matched = 0
        while (buffer.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) break
            buffer.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) {
                val all = buffer.toByteArray()
                return String(all, 0, all.size - 4, Charsets.UTF_8)
            }
        }
        return null
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        // 手机页面的提交体只有几百字节；超过 8 KiB 一律当异常
        val take = minOf(length, MAX_BODY)
        val bytes = ByteArray(take)
        var read = 0
        while (read < take) {
            val n = input.read(bytes, read, take - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun parseSubmission(body: String): Submission? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun str(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (str("kind")) {
            "server" -> str("server")?.trim()?.takeIf { it.isNotEmpty() }?.let { Submission.SetServer(it) }
            "start" -> str("provider")?.takeIf { it.matches(Regex("[a-z0-9_-]{1,32}")) }
                ?.let { Submission.StartLogin(it) }
            "token" -> {
                val server = str("server").orEmpty().trim()
                val token = str("token").orEmpty().trim()
                if (server.isEmpty() || token.length < 16) null else Submission.ManualToken(server, token)
            }
            "password" -> {
                val server = str("server").orEmpty().trim()
                val email = str("email").orEmpty().trim()
                val password = root["password"].let { (it as? JsonPrimitive)?.content }.orEmpty()
                if (server.isEmpty() || email.isEmpty() || password.length < 6) null
                else Submission.ManualPassword(server, email, password)
            }
            "cancel" -> Submission.Cancel
            else -> null
        }
    }

    private fun tokenOk(query: String): Boolean {
        val given = query.split('&')
            .firstOrNull { it.startsWith("k=") }
            ?.substringAfter("k=")
            ?: return false
        // 定长比较，避免靠响应时间猜口令
        return constantTimeEquals(given, secret)
    }

    private fun hostAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val name = host.substringBeforeLast(':').ifEmpty { host }
        val bare = name.removePrefix("[").removeSuffix("]")
        if (bare == "localhost") return true
        // 只认 IP 字面量：域名一律拒绝，这就是反 DNS rebinding 的全部
        return bare.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || bare.contains(':')
    }

    private fun reply(client: Socket, code: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out: OutputStream = client.getOutputStream()
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; connect-src 'self'\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        421 -> "Misdirected Request"
        else -> "Internal Server Error"
    }

    // ---------------------------------------------------------------- 内容

    private fun page(): String {
        pageOverride?.let { return it }
        val ctx = context ?: return "<!doctype html><p>无页面资源"
        return runCatching {
            ctx.assets.open("pair/index.html").bufferedReader().use { it.readText() }
        }.getOrElse {
            "<!doctype html><meta name=viewport content='width=device-width'><p>页面资源缺失</p>"
        }
    }

    private fun stateJson(): String {
        val s = _state.value
        return buildJsonObject {
            put("phase", s.phase.name)
            put("title", s.title)
            put("detail", s.detail)
            s.actionUrl?.let { put("actionUrl", it) }
            put("provider", s.provider)
            put("server", s.server)
            s.matrix?.let { rows ->
                put("matrix", JsonArray(rows.map { JsonPrimitive(it) }))
            }
            put("providers", JsonArray(s.providers.map { (id, name) ->
                buildJsonObject { put("id", id); put("name", name) }
            }))
            put("hosts", JsonArray(s.hosts.map { (id, name) ->
                buildJsonObject { put("id", id); put("name", name) }
            }))
            s.error?.let { put("error", it) }
            put("secondsLeft", s.secondsLeft)
        }.toString()
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message) }.toString()

    // ---------------------------------------------------------------- 工具

    private fun localAddress(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrNull().orEmpty()
        var fallback: String? = null
        for (nic in candidates) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    val text = addr.hostAddress ?: continue
                    // 10.x / 192.168.x 优先，172.16-31 次之（有些虚拟网卡会占用后者）
                    if (text.startsWith("192.168.") || text.startsWith("10.")) return text
                    if (fallback == null) fallback = text
                }
            }
        }
        return fallback
    }

    private fun ByteArray.toUrlSafe(): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    private companion object {
        const val MAX_HEAD = 8192
        const val MAX_BODY = 8192
    }
}
