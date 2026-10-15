package com.dshwatch.domain.agent.exec

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Rhino 引擎的关键行为。
 *
 * 其中 `while(true){}` 那条是**整个集成里最值得钉住的一条**：
 * Rhino 只有在解释模式（optimizationLevel = -1）下才会回调指令观察者，
 * 一旦有人为了性能把它改回编译模式，超时就会静默失效 ——
 * 用户会得到一个永远转圈、只能强杀 App 的界面。这条测试就是那种改动的报警器。
 */
class JsRunnerTest {

    private val runner = JsRunner()

    @Test
    fun `print is captured`() = runTest {
        val out = runner.run("print('hello-js')", limits())
        assertThat(out.ok).isTrue()
        assertThat(out.stdout).contains("hello-js")
    }

    @Test
    fun `console log and error go to different streams`() = runTest {
        val out = runner.run("console.log('to-out'); console.error('to-err');", limits())
        assertThat(out.stdout).contains("to-out")
        assertThat(out.stdout).doesNotContain("to-err")
        assertThat(out.stderr).contains("to-err")
    }

    @Test
    fun `numbers and objects print readably`() = runTest {
        val out = runner.run("print(42); print({a:1}); print(null);", limits())
        assertThat(out.stdout).contains("42")
        assertThat(out.stdout).contains("a")
    }

    @Test
    fun `syntax error is reported as a failure with a line hint`() = runTest {
        val out = runner.run("function ( {", limits())
        assertThat(out.ok).isFalse()
        assertThat(out.stderr).contains("JavaScript 错误")
    }

    @Test
    fun `runtime error reports the failing line`() = runTest {
        val out = runner.run("var a = 1;\nundefinedFunction();\n", limits())
        assertThat(out.ok).isFalse()
        assertThat(out.stderr).contains("第 2 行")
    }

    @Test
    fun `infinite loop is stopped by the deadline`() = runTest {
        val out = runner.run("while (true) {}", ExecLimits(timeoutMs = 1_200, maxChars = 2_000))

        assertThat(out.timedOut).isTrue()
        assertThat(out.ok).isFalse()
        // 最强的一条断言：观察者**真的打断了脚本**。
        // 如果只剩外层兜底，这里会是 true —— 那意味着一个线程在后台永远空转烧 CPU，
        // 而用户只看到"超时了"，完全不知道手表在偷偷发热。
        assertThat(out.stillRunning).isFalse()
        assertThat(out.elapsedMs).isLessThan(2_000)
    }

    @Test
    fun `a script that catches its own errors still times out`() = runTest {
        // 用户脚本用 try/catch 吞掉异常时，超时不能被吞掉：
        // 这正是把超时做成 Error 而不是 Exception 的原因
        val script = "while (true) { try { var x = 1; } catch (e) {} }"
        val out = runner.run(script, ExecLimits(timeoutMs = 1_200, maxChars = 2_000))
        assertThat(out.timedOut).isTrue()
    }

    @Test
    fun `arguments are injected as a global array`() = runTest {
        val out = runner.run("print(args.length); print(args[0]);", limits(), args = listOf("x", "y"))
        assertThat(out.stdout).contains("2")
        assertThat(out.stdout).contains("x")
    }

    @Test
    fun `stdout is truncated with head and tail kept`() = runTest {
        val out = runner.run(
            "for (var i = 0; i < 5000; i++) { print('line-' + i); }",
            ExecLimits(timeoutMs = 20_000, maxChars = 1_500),
        )
        assertThat(out.truncated).isTrue()
        assertThat(out.stdout).contains("line-0")
        assertThat(out.stdout).contains("line-4999")
    }

    @Test
    fun `empty script succeeds`() = runTest {
        val out = runner.run("", limits())
        assertThat(out.ok).isTrue()
    }

    private fun limits() = ExecLimits(timeoutMs = 10_000, maxChars = 8_000)
}
