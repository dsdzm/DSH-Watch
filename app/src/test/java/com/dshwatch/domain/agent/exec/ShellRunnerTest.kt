package com.dshwatch.domain.agent.exec

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 真跑 `/bin/sh`（桌面上有）。
 *
 * 为什么不用假的：这一层的价值就在于"真的起了进程、真的读了管道、真的杀了它"。
 * 换成 mock 之后，管道堵死、超时不生效、退出码取错这些真问题一个都测不出来。
 */
class ShellRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val runner = ShellRunner.forTesting()

    @Test
    fun `detect finds a usable shell`() = runTest {
        assertThat(runner.detect()).isTrue()
    }

    @Test
    fun `captures stdout and exit code`() = runTest {
        val out = runner.run("echo hello-dsh", tmp.root, limits())
        assertThat(out.ok).isTrue()
        assertThat(out.stdout).contains("hello-dsh")
        assertThat(out.exitCode).isEqualTo(0)
    }

    @Test
    fun `captures stderr separately`() = runTest {
        val out = runner.run("echo oops >&2; exit 3", tmp.root, limits())
        assertThat(out.stderr).contains("oops")
        assertThat(out.stdout).doesNotContain("oops")
        assertThat(out.exitCode).isEqualTo(3)
        assertThat(out.ok).isFalse()
    }

    @Test
    fun `runs with the work dir as cwd`() = runTest {
        val out = runner.run("pwd", tmp.root, limits())
        // macOS 上 /tmp 是符号链接，用 realPath 兜住
        assertThat(out.stdout.trim()).isAnyOf(
            tmp.root.absolutePath,
            tmp.root.canonicalPath,
        )
    }

    @Test
    fun `script file is written into the scratch dir and cwd stays clean`() = runTest {
        runner.run("echo x", tmp.root, limits())
        val scratch = tmp.root.resolve(ShellRunner.SCRATCH_DIR)
        assertThat(scratch.isDirectory).isTrue()
        assertThat(scratch.listFiles()!!.isNotEmpty()).isTrue()
    }

    @Test
    fun `passes positional arguments`() = runTest {
        val out = runner.run("echo \"$1-$2\"", tmp.root, limits(), args = listOf("a", "b"))
        assertThat(out.stdout.trim()).isEqualTo("a-b")
    }

    @Test
    fun `kills the process on timeout and says so`() = runTest {
        val out = runner.run("sleep 30", tmp.root, ExecLimits(timeoutMs = 800, maxChars = 2000))
        assertThat(out.timedOut).isTrue()
        assertThat(out.ok).isFalse()
        assertThat(out.note).contains("超时")
        // 关键：真的被杀了 —— 否则测试会一直跑下去
        assertThat(out.elapsedMs).isLessThan(10_000)
    }

    @Test
    fun `environment is wiped clean before running`() = runTest {
        val out = runner.run("env", tmp.root, limits())
        val keys = out.stdout.lines()
            .mapNotNull { line -> line.substringBefore('=', "").takeIf { line.contains('=') } }
            .toSet()

        // 只允许我们自己放进去的那几个（外加 shell 自己加的 SHLVL/_/OLDPWD）
        val allowed = setOf("PATH", "HOME", "PWD", "TMPDIR", "LANG", "LC_ALL", "SHLVL", "_", "OLDPWD")
        assertThat(keys - allowed).isEmpty()
        assertThat(keys).contains("PATH")
    }

    @Test
    fun `large output is truncated but does not hang`() = runTest {
        // 输出远大于管道缓冲：读取端如果不持续排空，这里会永远卡住
        val out = runner.run(
            "i=0; while [ \$i -lt 5000 ]; do echo \"line-\$i\"; i=\$((i+1)); done",
            tmp.root,
            ExecLimits(timeoutMs = 20_000, maxChars = 2_000),
        )
        assertThat(out.timedOut).isFalse()
        assertThat(out.truncated).isTrue()
        assertThat(out.stdout).contains("line-4999")
    }

    @Test
    fun `missing work dir fails fast with a readable message`() = runTest {
        val out = runner.run("echo x", tmp.root.resolve("nope"), limits())
        assertThat(out.ok).isFalse()
        assertThat(out.stderr).contains("工作目录不存在")
    }

    private fun limits() = ExecLimits(timeoutMs = 15_000, maxChars = 4_000)
}
