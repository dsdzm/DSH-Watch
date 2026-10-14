package com.dshwatch

import com.dshwatch.domain.agent.ToolArgs
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 工具参数解析测试。
 *
 * 背景是真机上的一次误报：模型写大 HTML 时参数被 token 上限截断，
 * 旧的解析逻辑静默回退成空对象，于是工具报「**缺少 path 参数**」，
 * 把「参数被截断」这个真正的原因彻底盖住了 —— 用户和模型都在猜为什么 path 会丢。
 *
 * 这里把三种结果都钉死。
 */
class ToolArgsTest {

    private fun okOf(raw: String): ToolArgs.Result.Ok {
        val r = ToolArgs.parse(raw)
        assertThat(r).isInstanceOf(ToolArgs.Result.Ok::class.java)
        return r as ToolArgs.Result.Ok
    }

    @Test
    fun `valid json parses normally`() {
        val r = okOf("""{"path":"index.html","content":"hi"}""")
        assertThat(r.args["path"].toString()).contains("index.html")
        assertThat(r.args["content"].toString()).contains("hi")
    }

    @Test
    fun `blank arguments mean no parameters`() {
        val r = okOf("")
        assertThat(r.args).isEmpty()
    }

    @Test
    fun `raw newlines inside a string are escaped and recovered`() {
        // 模型写多行 HTML 时最常见的形态：字符串里是**真正的换行**，
        // 这不符合 JSON 规范，但意图完全明确，转义一下就能救回来
        val raw = "{\"path\":\"a.html\",\"content\":\"<div>\n  <p>x</p>\n</div>\"}"
        val r = okOf(raw)
        assertThat(r.args["path"].toString()).contains("a.html")
    }

    @Test
    fun `already escaped newlines are not double escaped`() {
        // 本来就合法的 \n 必须原样保留，不能被二次转义成 \\n
        val raw = """{"path":"a","content":"line1\nline2"}"""
        val r = okOf(raw)
        assertThat(r.args["content"].toString()).isEqualTo("\"line1\\nline2\"")
    }

    @Test
    fun `truncated json is detected instead of becoming an empty object`() {
        // 真机上的那个形态：content 写到一半 token 用尽，引号和右括号都没了
        val raw = """{"path":"index.html","content":"<!DOCTYPE html> <html><body>--ok:"""
        val r = ToolArgs.parse(raw)
        assertThat(r).isInstanceOf(ToolArgs.Result.Truncated::class.java)
        val recovered = (r as ToolArgs.Result.Truncated).recovered
        // 关键：path 其实完好，只是被截断的 content 拖累
        assertThat(recovered["path"].toString()).contains("index.html")
    }

    @Test
    fun `truncation guidance is actionable`() {
        val raw = """{"path":"x","content":"abc"""
        val text = ToolArgs.explain(ToolArgs.parse(raw))
        assertThat(text).contains("截断")
        // 必须告诉模型下一步该怎么写，否则它只会原地重试
        assertThat(text).contains("append_file")
    }

    @Test
    fun `genuinely broken json is reported as broken`() {
        assertThat(ToolArgs.parse("not json at all"))
            .isInstanceOf(ToolArgs.Result.Broken::class.java)
    }

    @Test
    fun `unbalanced brackets are not salvaged into nonsense`() {
        // 结构已经错乱（多了一个 }）：宁可判 Broken，也不要造出看似合法实则错乱的参数
        val r = ToolArgs.parse("""{"a":1}}""")
        assertThat(r).isInstanceOf(ToolArgs.Result.Broken::class.java)
    }

    @Test
    fun `ok result has no explanation text`() {
        assertThat(ToolArgs.explain(okOf("""{"a":1}"""))).isEmpty()
    }
}
