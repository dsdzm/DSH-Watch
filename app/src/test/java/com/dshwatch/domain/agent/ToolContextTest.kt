package com.dshwatch.domain.agent

import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.agent.cloud.CloudRunStore
import com.dshwatch.domain.agent.cloud.GitHubApi
import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `withSession` 的字段拷贝。
 *
 * 为什么值得单独写一个测试：[ToolContext] 是靠**逐字段手写拷贝**来换会话目录的，
 * 而"新加一个字段忘了同步"这件事**不会编译报错**，症状是
 * "换到某个会话后，工具突然看不到设置/看不到云端记录" —— 静默、偶发、极难定位。
 * 这个测试就是那个报警器。
 */
class ToolContextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun build(sessionDir: String = ""): ToolContext {
        val settings = AppSettings(localExecEnabled = true, cloudRepo = "o/r")
        return ToolContext(
            appContext = null,
            workspace = WorkspaceStore(tmp.newFolder("ws-$sessionDir")),
            usage = UsageRepository(tmp.newFolder("u-$sessionDir"), Json),
            profile = { testProfile() },
            sessionDir = sessionDir,
            workspaceRoot = { "/tmp/ws" },
            settings = { settings },
            cloudRuns = CloudRunStore(tmp.newFolder("c-$sessionDir")),
            github = GitHubApi { "token" },
        )
    }

    @Test
    fun `withSession swaps the session dir`() {
        val ctx = build(sessionDir = "progress/aaa")
        assertThat(ctx.withSession("progress/bbb").sessionDir).isEqualTo("progress/bbb")
    }

    @Test
    fun `withSession keeps the settings provider`() {
        // 用户在设置页刚打开「本地执行」，工具必须马上看到
        val ctx = build()
        val scoped = ctx.withSession("progress/aaa")
        assertThat(scoped.settings().localExecEnabled).isTrue()
        assertThat(scoped.settings().cloudRepo).isEqualTo("o/r")
    }

    @Test
    fun `withSession keeps the cloud run store`() {
        val ctx = build()
        assertThat(ctx.withSession("progress/aaa").cloudRuns).isSameInstanceAs(ctx.cloudRuns)
    }

    @Test
    fun `withSession keeps the github channel`() {
        val ctx = build()
        assertThat(ctx.withSession("progress/aaa").github).isSameInstanceAs(ctx.github)
    }

    @Test
    fun `withSession keeps workspace usage profile and channels`() {
        val ctx = build()
        val scoped = ctx.withSession("progress/aaa")
        assertThat(scoped.workspace).isSameInstanceAs(ctx.workspace)
        assertThat(scoped.usage).isSameInstanceAs(ctx.usage)
        assertThat(scoped.prompter).isSameInstanceAs(ctx.prompter)
        assertThat(scoped.workspaceRoot()).isEqualTo(ctx.workspaceRoot())
    }
}

private fun testProfile() = DeviceProfile(
    tier = DeviceTier.MID,
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
