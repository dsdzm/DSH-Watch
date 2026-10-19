package com.dshwatch.net

import com.google.common.truth.Truth.assertThat
import okhttp3.Request
import org.junit.Test

/**
 * 自定义请求头的清洗。
 *
 * 这组测试的重点不是「能加头」，而是**加不进去的那些必须被拦住**：
 * `Host` 会把请求改发到别的主机，`\r\n` 是请求头注入，Content-Length 会让协议错乱。
 */
class CustomHeadersTest {

    @Test
    fun `normal headers pass through`() {
        val result = CustomHeaders.sanitize(
            mapOf(
                "Authorization" to "Bearer abc",
                "Referer" to "https://example.com",
                "X-Requested-With" to "XMLHttpRequest",
            )
        )
        assertThat(result.values).hasSize(3)
        assertThat(result.values["Authorization"]).isEqualTo("Bearer abc")
        assertThat(result.rejected).isEmpty()
    }

    @Test
    fun `host and framing headers are rejected`() {
        val result = CustomHeaders.sanitize(
            mapOf(
                "Host" to "evil.example",
                "Content-Length" to "10",
                "Connection" to "keep-alive",
                "Transfer-Encoding" to "chunked",
                "Accept" to "text/html",
            )
        )
        assertThat(result.values.keys).containsExactly("Accept")
        assertThat(result.rejected).containsExactly("Host", "Content-Length", "Connection", "Transfer-Encoding")
    }

    @Test
    fun `header injection via newline is rejected`() {
        val result = CustomHeaders.sanitize(
            mapOf("X-Test" to "value\r\nX-Injected: yes")
        )
        assertThat(result.values).isEmpty()
        assertThat(result.rejected).containsExactly("X-Test")
    }

    @Test
    fun `invalid names are rejected`() {
        val result = CustomHeaders.sanitize(
            mapOf(
                "Bad Header" to "x",       // 空格
                "Bad:Header" to "x",       // 冒号
                "" to "x",                 // 空名称
                "Good-Name" to "x",
            )
        )
        assertThat(result.values.keys).containsExactly("Good-Name")
        assertThat(result.rejected).hasSize(3)
    }

    @Test
    fun `too many headers are capped`() {
        val raw = (1..30).associate { "X-$it" to "v" }
        val result = CustomHeaders.sanitize(raw)
        assertThat(result.values).hasSize(CustomHeaders.MAX_COUNT)
        assertThat(result.rejected).hasSize(10)
    }

    @Test
    fun `custom header replaces the default instead of duplicating it`() {
        val builder = Request.Builder()
            .url("https://example.com")
            .header("User-Agent", "default-agent")
        CustomHeaders.apply(builder, mapOf("User-Agent" to "my-agent"))

        val request = builder.build()
        assertThat(request.header("User-Agent")).isEqualTo("my-agent")
        // header() 是追加语义，直接调会出现两个值；apply 必须先 remove
        assertThat(request.headers.values("User-Agent")).containsExactly("my-agent")
    }

    @Test
    fun `rejected note is empty when nothing was rejected`() {
        assertThat(CustomHeaders.rejectedNote(emptyList())).isEmpty()
        assertThat(CustomHeaders.rejectedNote(listOf("Host"))).contains("Host")
    }
}
