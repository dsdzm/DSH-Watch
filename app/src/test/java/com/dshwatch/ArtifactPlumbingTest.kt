package com.dshwatch

import com.dshwatch.core.Jsonx
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.DeviceProfileDefaults
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.tools.BuildTools
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 产物要能从工具层一路带到界面。
 *
 * 界面靠 `ToolCallRecord.artifact` 渲染「产物卡片」（点开预览 / 导出）。
 * 这个字段一旦断了，用户就只能在聊天里看到一句「构建成功」，
 * 然后自己去「工作空间」页翻 —— 那正是这次要消灭的体验。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactPlumbingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctxFor(root: File, io: TestDispatcher): ToolContext = ToolContext(
        appContext = null,
        workspace = WorkspaceStore(root, io),
        usage = UsageRepository(File(root, "usage"), Jsonx.lenient, io),
        profile = { DeviceProfileDefaults.fallback },
    )

    @Test
    fun `build_project reports the artifact path`() = runTest {
        val root = tmp.newFolder("ws")
        val ctx = ctxFor(root, UnconfinedTestDispatcher(testScheduler))
        ctx.workspace.write("index.html", "<html><body>hello</body></html>")

        val result = BuildTools.BuildProjectTool.execute(
            buildJsonObject { put("entry", "index.html") },
            ctx,
        )

        assertThat(result.ok).isTrue()
        assertThat(result.artifact).isEqualTo("dist/index.html")
    }

    @Test
    fun `failed build carries no artifact`() = runTest {
        val root = tmp.newFolder("ws")
        val ctx = ctxFor(root, UnconfinedTestDispatcher(testScheduler))

        val result = BuildTools.BuildProjectTool.execute(
            buildJsonObject { put("entry", "missing.html") },
            ctx,
        )

        assertThat(result.ok).isFalse()
        assertThat(result.artifact).isEmpty()
    }

    @Test
    fun `custom out dir is reflected in the artifact path`() = runTest {
        val root = tmp.newFolder("ws")
        val ctx = ctxFor(root, UnconfinedTestDispatcher(testScheduler))
        ctx.workspace.write("index.html", "<html><body>hi</body></html>")

        val result = BuildTools.BuildProjectTool.execute(
            buildJsonObject {
                put("entry", "index.html")
                put("out_dir", "site")
            },
            ctx,
        )

        assertThat(result.ok).isTrue()
        assertThat(result.artifact).isEqualTo("site/index.html")
    }
}
