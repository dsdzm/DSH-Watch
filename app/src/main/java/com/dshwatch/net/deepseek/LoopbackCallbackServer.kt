package com.dshwatch.net.deepseek

import com.dshwatch.core.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * 本机回调服务：接住平台跳回来的 `http://127.0.0.1:<port>/oauth/callback?code=…`。
 *
 * ## 为什么需要它
 *
 * 平台只认 loopback 地址作为回调（`http://127.0.0.1:port/oauth/callback`），
 * 因为它假定「发起登录的客户端就在这台机器上，能监听端口」。
 * 官方桌面端就是这么做的：起一个临时 HTTP 服务，等浏览器跳回来。
 *
 * 早先这里为了「手表上没有浏览器、起服务不稳定」改成了 WebView 拦截，
 * 代价是**登录页跑在 WebView 里**——而平台的 WAF 对 WebView / 脚本客户端
 * 并不友好（UA 一换就 429），且手机号、验证码在手表小屏上几乎没法输。
 * 现在改回官方这条路：**系统浏览器负责页面，本机服务负责收口**，
 * WebView 只作为没有浏览器时的兜底。
 *
 * ## 只监听 127.0.0.1
 *
 * 不绑 `0.0.0.0`：否则同一 Wi-Fi 下任何人都能往这个端口塞一个 code。
 * 虽然 `state` 还会校验一次，但少开一个入口总是对的。
 *
 * ## 端口
 *
 * 官方固定 53682。被占用时（比如上一个页面没退干净）顺延几个，
 * 因为 `redirect_uri` 是发起登录时告诉平台的，**必须在 auth_init 之前定下来**。
 */
class LoopbackCallbackServer private constructor(
    private val server: ServerSocket,
    val port: Int,
) {

    /** 回调地址。发起登录时把它交给平台，浏览器就是往这里跳 */
    val callbackUri: String get() = "http://127.0.0.1:$port${DeepSeekAccountApi.CALLBACK_PATH}"

    /** 等到了授权码；[url] 是完整回调地址，state / code 由仓储层校验 */
    class Callback(val url: String)

    /** 等到超时也没人来 */
    class Timeout : Exception("等待授权回调超时")

    companion object {
        private const val TAG = "DsCallback"

        /** 从官方的 53682 开始顺延；5 个足够，再多说明本机有东西在乱占端口 */
        private val CANDIDATE_PORTS = (DeepSeekAccountApi.CALLBACK_PORT..DeepSeekAccountApi.CALLBACK_PORT + 4)

        fun start(): LoopbackCallbackServer? {
            for (port in CANDIDATE_PORTS) {
                val socket = try {
                    ServerSocket().apply {
                        // 上一个进程刚退时端口常处于 TIME_WAIT，不开这个会 bind 失败
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
                    }
                } catch (io: IOException) {
                    Logx.w(TAG, "端口 $port 不可用：${io.javaClass.simpleName}")
                    continue
                }
                Logx.i(TAG, "回调服务已启动：$port")
                return LoopbackCallbackServer(socket, port)
            }
            return null
        }
    }

    /**
     * 阻塞等到一次真正的回调。
     *
     * 浏览器的请求不止一个（还有 `favicon.ico`、预连接探测），所以不是
     * 「accept 一次就完事」：路径不对的请求回 404 然后继续等。
     */
    suspend fun await(timeoutMs: Long): Callback = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (server.isClosed) throw Timeout()
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw Timeout()

            // 每秒醒一次，这样 close() 之后能很快退出，而不是卡到超时
            server.soTimeout = minOf(remaining, 1_000L).toInt()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (io: IOException) {
                if (server.isClosed) throw Timeout() else continue
            }

            socket.use { conn ->
                conn.soTimeout = 3_000
                val url = runCatching { readCallbackUrl(conn.getInputStream()) }.getOrNull()
                val response = if (url != null && url.contains(DeepSeekAccountApi.CALLBACK_PATH)) {
                    donePage()
                } else {
                    notFoundPage()
                }
                runCatching {
                    conn.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
                if (url != null && url.contains(DeepSeekAccountApi.CALLBACK_PATH)) {
                    Logx.i(TAG, "收到授权回调")
                    return@withContext Callback(url)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw Timeout()
    }

    /** 关掉监听。可以重复调用：页面销毁与流程结束都会调一次 */
    fun close() {
        runCatching { server.close() }
        Logx.i(TAG, "回调服务已关闭")
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 从请求头里读出完整回调地址。
     *
     * 只读请求行就够（`GET /oauth/callback?code=…&state=… HTTP/1.1`），
     * 不解析 body —— 这是个 GET，body 一定是空的。
     */
    private fun readCallbackUrl(input: java.io.InputStream): String? {
        val buf = ByteArray(4096)
        val read = input.read(buf)
        if (read <= 0) return null
        val head = String(buf, 0, read, Charsets.ISO_8859_1)
        val requestLine = head.lineSequence().firstOrNull() ?: return null
        val target = requestLine.split(' ').getOrNull(1) ?: return null
        if (!target.startsWith("/")) return null
        return "http://127.0.0.1:$port$target"
    }

    private fun httpResponse(status: String, html: String): String {
        val body = html.toByteArray(Charsets.UTF_8)
        return buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            // 不缓存：用户重开标签页时不该看到上一次的「已完成」
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
            append(html)
        }
    }

    /**
     * 用户看到的那一页。
     *
     * 他在浏览器里，不在 App 里，所以这页要自己说清楚「可以回去了」——
     * 不写任何账号信息，也不放跳转链接（`127.0.0.1` 在浏览器里没有意义）。
     */
    private fun donePage(): String = httpResponse(
        "200 OK",
        """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>登录完成</title>
        <style>
          :root{color-scheme:light dark}
          body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
               font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
               background:#f6f7f9;color:#1b1d22;padding:24px}
          .card{max-width:420px;text-align:center}
          .dot{width:56px;height:56px;border-radius:50%;background:#e8f5ec;color:#1f8a4c;
               display:flex;align-items:center;justify-content:center;font-size:30px;margin:0 auto 18px}
          h1{font-size:20px;margin:0 0 8px}
          p{margin:0;color:#61666f;font-size:14px}
          @media (prefers-color-scheme:dark){
            body{background:#111318;color:#eceef2}
            .dot{background:#16301f;color:#4ade80}
            p{color:#9aa0aa}
          }
        </style></head>
        <body><div class="card">
          <div class="dot">✓</div>
          <h1>登录完成</h1>
          <p>可以关闭这个页面，回到 DSH Watch 继续。</p>
          <p style="margin-top:10px;font-size:12px;opacity:.7">Signed in. You can close this tab.</p>
        </div></body></html>
        """.trimIndent(),
    )

    private fun notFoundPage(): String = httpResponse(
        "404 Not Found",
        "<!doctype html><meta charset=\"utf-8\"><title>404</title><p>Not found.</p>",
    )
}
