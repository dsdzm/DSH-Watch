package com.dshwatch.net.deepseek

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * 回调服务的行为。
 *
 * 这一段是登录链路上唯一「我们自己监听端口」的地方，出错的表现都很隐蔽：
 * 浏览器跳回来停在 404、页面转圈永远不返回、或者干脆连不上。
 * 这里用真的 HTTP 请求打一遍，钉住三件事：路径、地址、页面。
 */
class LoopbackCallbackServerTest {

    @Test
    fun `收到回调后返回完整地址并给出完成页`() = runBlocking {
        val server = LoopbackCallbackServer.start()
        // CI 里如果端口全被占（极罕见），跳过而不是误报失败
        if (server == null) return@runBlocking

        try {
            val waiter = async(Dispatchers.IO) { server.await(5_000) }
            val body = fetchWithRetry("${server.callbackUri}?code=abc123&state=st")

            assertThat(body).contains("登录完成")
            val callback = waiter.await()
            assertThat(callback.url).contains("code=abc123")
            assertThat(callback.url).contains("state=st")
        } finally {
            server.close()
        }
    }

    @Test
    fun `路径不对的请求不会提前结束等待`() = runBlocking {
        val server = LoopbackCallbackServer.start()
        if (server == null) return@runBlocking

        try {
            val waiter = async(Dispatchers.IO) { server.await(5_000) }

            // 浏览器一定会来要 favicon；它不能把「等待」当成已完成
            fetchWithRetry("http://127.0.0.1:${server.port}/favicon.ico")

            val callback = fetchWithRetry("${server.callbackUri}?code=real&state=s")
            assertThat(callback).contains("登录完成")
            assertThat(waiter.await().url).contains("code=real")
        } finally {
            server.close()
        }
    }

    /**
     * 服务刚起时 accept 可能还没挂到线程上，第一次连接会被拒。
     * 重试几次而不是 sleep 一个魔法数字，慢机器上也不会假失败。
     */
    private fun fetchWithRetry(url: String): String {
        var last: Exception? = null
        repeat(6) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1_000
                    readTimeout = 2_000
                }
                // 404 的 body 在 errorStream 里；直接拿 inputStream 会抛 FileNotFoundException，
                // 被下面的 catch 当成「连接失败」重试，最后误报
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                return stream?.bufferedReader()?.readText().orEmpty()
            } catch (e: Exception) {
                last = e
                Thread.sleep(80)
            }
        }
        throw last ?: IllegalStateException("连接回调服务失败")
    }
}
