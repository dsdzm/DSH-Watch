package com.dshwatch.domain.agent.tools

import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.cloud.CloudRunRecord
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.DeviceProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 执行类工具的门禁与文案。
 *
 * 重点不是"能不能跑"，而是**不能跑的时候说清楚了没有** ——
 * 这两个工具失败的最常见原因是"开关没开"，
 * 而模型看到的原因必须包含"去哪里开"，否则它只会回一句"操作失败了"。
 */
class ScriptToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun context(settings: AppSettings): ToolContext {
        val root = tmp.newFolder("ws")
        return ToolContext(
            appContext = null,
            workspace = WorkspaceStore(root),
            usage = UsageRepository(tmp.newFolder("usage"), kotlinx.serialization.json.Json),
            profile = { testProfile() },
            settings = { settings },
        )
    }

    private fun args(language: String, code: String = "echo hi") = buildJsonObject {
        put("language", language)
        put("code", code)
    }

    @Test
    fun `refuses when local execution is off and says where to turn it on`() = runTest {
        val ctx = context(AppSettings(localExecEnabled = false))
        val result = ScriptTools.RunScriptTool.execute(args("shell"), ctx)
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("设置")
        assertThat(result.content).contains("本地执行")
    }

    @Test
    fun `refuses a single language that is off`() = runTest {
        val ctx = context(
            AppSettings(localExecEnabled = true, allowShell = false, allowJs = true, allowPython = true)
        )
        val result = ScriptTools.RunScriptTool.execute(args("shell"), ctx)
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("Shell")
    }

    @Test
    fun `missing language parameter explains the options`() = runTest {
        val ctx = context(AppSettings(localExecEnabled = true))
        val result = ScriptTools.RunScriptTool.execute(buildJsonObject { put("code", "x") }, ctx)
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("shell")
    }

    @Test
    fun `unsupported language points at cloud build`() = runTest {
        val ctx = context(AppSettings(localExecEnabled = true))
        val result = ScriptTools.RunScriptTool.execute(args("rust"), ctx)
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("cloud_build")
    }

    @Test
    fun `missing code explains it wants source text not a path`() = runTest {
        val ctx = context(AppSettings(localExecEnabled = true))
        val result = ScriptTools.RunScriptTool.execute(
            buildJsonObject { put("language", "shell") },
            ctx,
        )
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("源码")
    }

    @Test
    fun `python on a lite build says to install the full apk`() = runTest {
        // JVM 环境天然没有 Chaquopy 类，正好等价于 lite 包
        val ctx = context(AppSettings(localExecEnabled = true, allowPython = true))
        val result = ScriptTools.RunScriptTool.execute(args("python", "print(1)"), ctx)
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("lite")
    }

    @Test
    fun `success text carries exit code duration and streams`() {
        val text = ScriptTools.formatExecOutput(
            "shell",
            com.dshwatch.domain.agent.exec.ExecOutput(
                exitCode = 0,
                stdout = "hello\n",
                stderr = "",
                elapsedMs = 12,
            ),
        )
        assertThat(text).contains("执行成功")
        assertThat(text).contains("退出码 0")
        assertThat(text).contains("12 ms")
        assertThat(text).contains("hello")
    }

    @Test
    fun `failure text marks exit code and stderr`() {
        val text = ScriptTools.formatExecOutput(
            "python",
            com.dshwatch.domain.agent.exec.ExecOutput(
                exitCode = 1,
                stdout = "",
                stderr = "Traceback...\n",
                elapsedMs = 30,
            ),
        )
        assertThat(text).contains("执行失败")
        assertThat(text).contains("Traceback")
    }

    @Test
    fun `timeout text never looks like success`() {
        val text = ScriptTools.formatExecOutput(
            "js",
            com.dshwatch.domain.agent.exec.ExecOutput(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = true,
                elapsedMs = 20_000,
                note = "超时 20 秒，已强制结束。",
            ),
        )
        assertThat(text).contains("超时")
        assertThat(text).doesNotContain("执行成功")
        assertThat(text).contains("强制结束")
    }

    @Test
    fun `truncation is announced`() {
        val text = ScriptTools.formatExecOutput(
            "shell",
            com.dshwatch.domain.agent.exec.ExecOutput(
                exitCode = 0,
                stdout = "…（省略 100 个字符）…",
                stderr = "",
                truncated = true,
            ),
        )
        assertThat(text).contains("截断")
    }

    @Test
    fun `empty output says so explicitly`() {
        val text = ScriptTools.formatExecOutput(
            "shell",
            com.dshwatch.domain.agent.exec.ExecOutput(exitCode = 0, stdout = "", stderr = ""),
        )
        assertThat(text).contains("没有任何输出")
    }

    @Test
    fun `summarize shows language and line count`() {
        val summary = ScriptTools.RunScriptTool.summarize(
            buildJsonObject {
                put("language", "js")
                put("code", "a\nb\nc")
            }
        )
        assertThat(summary).contains("js")
        assertThat(summary).contains("3")
    }

    // ------------------------------------------------------------------
    // 「命令找不到」的引导
    // 下面三条 stderr 都是从真机日志里抄来的原样文本。
    // ------------------------------------------------------------------

    private fun shellFailure(stderr: String, exitCode: Int = 127) =
        com.dshwatch.domain.agent.exec.ExecOutput(exitCode = exitCode, stdout = "", stderr = stderr)

    @Test
    fun `python not found in shell points at language=python`() {
        // 真机原样：/storage/.../run-12105891902032.sh[4]: python: not found
        val out = shellFailure("/x/.dsh-run/run-12105891902032.sh[4]: python: not found")
        val hint = ScriptTools.interpreterHint("shell", out)
        assertThat(hint).contains("language 改成 python")
    }

    @Test
    fun `python3 not found also points at language=python`() {
        val hint = ScriptTools.interpreterHint(
            "shell",
            shellFailure("/x/run-12099304217229.sh[4]: python3: not found"),
        )
        assertThat(hint).contains("language 改成 python")
    }

    @Test
    fun `node not found points at language=js`() {
        val hint = ScriptTools.interpreterHint("shell", shellFailure("/x/run.sh[2]: node: not found"))
        assertThat(hint).contains("language 改成 js")
    }

    @Test
    fun `compiler not found points at cloud build`() {
        val hint = ScriptTools.interpreterHint("shell", shellFailure("/x/run.sh[1]: gcc: not found"))
        assertThat(hint).contains("cloud_build")
    }

    @Test
    fun `unknown missing command still gets a useful answer`() {
        val hint = ScriptTools.interpreterHint("shell", shellFailure("/x/run.sh[1]: figlet: not found"))
        assertThat(hint).contains("figlet")
        assertThat(hint).contains("cloud_build")
    }

    @Test
    fun `no hint when the command actually worked`() {
        val ok = com.dshwatch.domain.agent.exec.ExecOutput(exitCode = 0, stdout = "hi", stderr = "")
        assertThat(ScriptTools.interpreterHint("shell", ok)).isEmpty()
    }

    @Test
    fun `no hint for a normal script failure`() {
        // 脚本自己逻辑错了（比如 test 失败）不该被误判成"命令不存在"
        val out = shellFailure("assertion failed: expected 3 got 4", exitCode = 1)
        assertThat(ScriptTools.interpreterHint("shell", out)).isEmpty()
    }

    @Test
    fun `hint is only added for shell`() {
        val out = shellFailure("python: not found")
        assertThat(ScriptTools.interpreterHint("python", out)).isEmpty()
    }

    @Test
    fun `failure text from the real device log gets the hint appended`() {
        val text = ScriptTools.formatExecOutput(
            "shell",
            shellFailure("/x/.dsh-run/run.sh[4]: python: not found"),
        )
        assertThat(text).contains("python: not found")
        assertThat(text).contains("language 改成 python")
    }
}

/**
 * 云端工具的入口门禁。
 *
 * 这三种"没配好"是最常发生的状态，每一种都必须给出**下一步该做什么**。
 */
class CloudToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun context(settings: AppSettings) = ToolContext(
        appContext = null,
        workspace = WorkspaceStore(tmp.newFolder("ws")),
        usage = UsageRepository(tmp.newFolder("usage"), kotlinx.serialization.json.Json),
        profile = { testProfile() },
        settings = { settings },
        github = com.dshwatch.domain.agent.cloud.GitHubApi { "" },
    )

    @Test
    fun `cloud_build refuses when disabled`() = runTest {
        val result = CloudTools.CloudBuildTool.execute(
            buildJsonObject { put("workflow", "dsh-run.yml") },
            context(AppSettings(cloudBuildEnabled = false)),
        )
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("云端编译未开启")
        assertThat(result.content).contains("设置")
    }

    @Test
    fun `cloud_build asks for a repository`() = runTest {
        val result = CloudTools.CloudBuildTool.execute(
            buildJsonObject { },
            context(AppSettings(cloudBuildEnabled = true, cloudRepo = "")),
        )
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("owner/repo")
    }

    @Test
    fun `cloud_build asks for a token`() = runTest {
        val result = CloudTools.CloudBuildTool.execute(
            buildJsonObject { },
            context(AppSettings(cloudBuildEnabled = true, cloudRepo = "o/r")),
        )
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("Token")
    }

    @Test
    fun `cloud_job without a previous run explains what to do`() = runTest {
        val ctx = ToolContext(
            appContext = null,
            workspace = WorkspaceStore(tmp.newFolder("ws2")),
            usage = UsageRepository(tmp.newFolder("usage2"), kotlinx.serialization.json.Json),
            profile = { testProfile() },
            settings = { AppSettings(cloudBuildEnabled = true, cloudRepo = "o/r") },
            cloudRuns = com.dshwatch.domain.agent.cloud.CloudRunStore(tmp.newFolder("cloud")),
            github = com.dshwatch.domain.agent.cloud.GitHubApi { "tk" },
        )
        val result = CloudTools.CloudJobTool.execute(
            buildJsonObject { put("action", "status") },
            ctx,
        )
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("cloud_build")
    }

    @Test
    fun `cloud_job rejects an unknown action`() = runTest {
        val result = CloudTools.CloudJobTool.execute(
            buildJsonObject { put("action", "explode") },
            context(AppSettings(cloudBuildEnabled = true, cloudRepo = "o/r")),
        )
        assertThat(result.ok).isFalse()
        assertThat(result.content).contains("status")
    }

    @Test
    fun `human size is readable`() {
        assertThat(CloudTools.humanSize(512)).isEqualTo("512 B")
        assertThat(CloudTools.humanSize(2048)).contains("KB")
        assertThat(CloudTools.humanSize(5 * 1024 * 1024)).contains("MB")
    }

    @Test
    fun `run summary never claims success for an unfinished run`() {
        val text = CloudTools.formatRun(
            CloudRunRecord(runId = 5, status = "in_progress", startedAt = 1)
        )
        assertThat(text).contains("未结束")
        assertThat(text).doesNotContain("成功")
    }

    @Test
    fun `run summary marks failure`() {
        val text = CloudTools.formatRun(
            CloudRunRecord(runId = 6, status = "completed", conclusion = "failure", startedAt = 1)
        )
        assertThat(text).contains("失败")
    }

    @Test
    fun `step summary shows marks`() {
        val text = CloudTools.formatJobs(
            listOf(
                com.dshwatch.domain.agent.cloud.JobInfo(
                    id = 1,
                    name = "build",
                    status = "completed",
                    conclusion = "failure",
                    steps = listOf(
                        com.dshwatch.domain.agent.cloud.StepInfo("Checkout", "success"),
                        com.dshwatch.domain.agent.cloud.StepInfo("Build", "failure"),
                    ),
                )
            )
        )
        assertThat(text).contains("✓ Checkout")
        assertThat(text).contains("✗ Build")
    }
    @Test
    fun `failure echoes the working directory`() {
        // 真机样本：模型写了 `cd progress/8560e5`，而脚本本来就在那个目录里，
        // 路径变成 .../progress/8560e5/progress/8560e5 → No such file or directory。
        // 它从报错里看不出"我根本不需要 cd"，所以失败的输出里要直接说清楚。
        val out = com.dshwatch.domain.agent.exec.ExecOutput(
            exitCode = 2,
            stdout = "",
            stderr = "cd: /storage/.../progress/8560e5/progress/8560e5: No such file or directory",
        )

        val text = ScriptTools.formatExecOutput("shell", out, "progress/8560e5")

        assertThat(text).contains("progress/8560e5")
        assertThat(text).contains("不需要再 cd")
    }

    @Test
    fun `success does not repeat the directory`() {
        // 成功时不必浪费 token 说这句 —— 模型大概率是对的
        val out = com.dshwatch.domain.agent.exec.ExecOutput(exitCode = 0, stdout = "ok", stderr = "")

        val text = ScriptTools.formatExecOutput("shell", out, "progress/8560e5")

        assertThat(text).doesNotContain("不需要再 cd")
    }

}

private fun testProfile() = DeviceProfile(
    tier = com.dshwatch.domain.DeviceTier.MID,
    screenWidthPx = 410,
    screenHeightPx = 502,
    screenWidthDp = 205,
    screenHeightDp = 251,
    densityDpi = 320,
    cornerInsetDp = 14,
    autoCornerInsetDp = 14,
    totalMemMb = 4096,
    cores = 4,
    isWatch = true,
    sdkInt = 28,
    compact = true,
    squarish = false,
)
