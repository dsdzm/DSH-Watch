package com.dshwatch.domain.agent.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * lite 路径（JVM 上没有 Chaquopy）+ 跨语言结果的解析。
 *
 * 解析这一环最容易出问题：Python 侧拼 JSON、Kotlin 侧解 JSON，
 * 中间隔着 Chaquopy 的一次 `toString()`。固定样本能把这类错误钉死在编译期之外。
 */
class PythonBridgeTest {

    @Test
    fun `engine class is absent in a plain JVM environment`() {
        // 这正是 lite 包在真机上的表现；工具据此返回「请安装 full 版」
        assertThat(PythonBridge.classPresent()).isFalse()
    }

    @Test
    fun `lite engine reports unavailability with an actionable reason`() {
        val engine = ReflectionPythonEngine(appContext = null)
        assertThat(engine.available).isFalse()
        assertThat(engine.unavailableReason()).contains("Python")
    }

    @Test
    fun `parses a successful result`() {
        val raw = """
            {"exit":0,"stdout":"双鱼\n","stderr":"","timed_out":false,
             "truncated":false,"elapsed_ms":42,"still_running":false}
        """.trimIndent()

        val out = PythonBridge.parseResult(raw)
        assertThat(out.exitCode).isEqualTo(0)
        assertThat(out.stdout).isEqualTo("双鱼\n")
        assertThat(out.stderr).isEmpty()
        assertThat(out.ok).isTrue()
        assertThat(out.elapsedMs).isEqualTo(42)
        assertThat(out.timedOut).isFalse()
    }

    @Test
    fun `parses a failed result`() {
        val raw = """{"exit":1,"stdout":"","stderr":"Traceback...","timed_out":false,"truncated":false,"elapsed_ms":5}"""
        val out = PythonBridge.parseResult(raw)
        assertThat(out.ok).isFalse()
        assertThat(out.stderr).contains("Traceback")
    }

    @Test
    fun `parses a timeout result and keeps the honest note`() {
        val raw = """{"exit":1,"stdout":"","stderr":"超时","timed_out":true,"truncated":false,"elapsed_ms":20000}"""
        val out = PythonBridge.parseResult(raw)
        assertThat(out.timedOut).isTrue()
        assertThat(out.note).contains("超时")
    }

    @Test
    fun `parses a still-running result`() {
        val raw = """{"exit":1,"stdout":"","stderr":"","timed_out":true,
                      "truncated":false,"elapsed_ms":1000,"still_running":true}"""
        val out = PythonBridge.parseResult(raw)
        assertThat(out.stillRunning).isTrue()
        assertThat(out.note).contains("后台")
    }

    @Test
    fun `missing fields fall back to safe defaults`() {
        val out = PythonBridge.parseResult("""{"exit":0}""")
        assertThat(out.exitCode).isEqualTo(0)
        assertThat(out.stdout).isEmpty()
        assertThat(out.timedOut).isFalse()
    }

    @Test
    fun `unknown extra fields are ignored`() {
        val out = PythonBridge.parseResult("""{"exit":0,"future_field":"x"}""")
        assertThat(out.ok).isTrue()
    }

    @Test
    fun `non-json output is reported instead of silently treated as stdout`() {
        // 引擎启动失败时 Chaquopy 可能把错误直接抛成文本，这里要看得见原因
        val out = PythonBridge.parseResult("Java stack trace: something broke")
        assertThat(out.ok).isFalse()
        assertThat(out.stderr).contains("没有返回预期结果")
    }

    @Test
    fun `broken json does not crash`() {
        val out = PythonBridge.parseResult("""{"exit":0,"stdout":""")
        assertThat(out.ok).isFalse()
        assertThat(out.stderr).isNotEmpty()
    }
}
