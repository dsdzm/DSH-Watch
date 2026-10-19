package com.dshwatch.domain.agent

import com.dshwatch.domain.DeviceProfileDefaults
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.data.workspace.WorkspaceStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 落盘的**端到端**路径。
 *
 * [ToolResultCompactorTest] 只验摘要文本格式；这里验的是「文件真的写进去了、
 * 路径真的能被读回来」。摘要里给的路径如果打不开，模型照样束手无策 ——
 * 所以这一条比文本格式更重要。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolResultCompactorIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx(root: File, session: String = "progress/sess1") = ToolContext(
        appContext = null,
        workspace = WorkspaceStore(root, UnconfinedTestDispatcher()),
        usage = UsageRepository(
            File(root, "usage"),
            Json { ignoreUnknownKeys = true },
            UnconfinedTestDispatcher(),
        ),
        profile = { DeviceProfileDefaults.fallback },
        sessionDir = session,
        workspaceRoot = { root.absolutePath },
    )

    @Test
    fun `long result is stored and the digest path can be read back`() = runTest {
        val root = tmp.newFolder("ws")
        val c = ctx(root)
        val content = "H".repeat(5_000) + "M".repeat(1_000) + "T".repeat(2_000)

        val out = ToolResultCompactor.compact("read_file", content, c)

        assertThat(out.path).isNotNull()
        assertThat(out.path!!).startsWith("notes/tool-output/")
        assertThat(out.originalChars).isEqualTo(content.length)

        // 摘要里给的路径必须真的能取到全文 —— 否则落盘毫无意义
        val saved = c.workspace.read(c.scope(out.path!!)!!, 100_000)
        assertThat(saved).isEqualTo(content)

        // 中间那段不该进上下文
        assertThat(out.text).doesNotContain("MMMMM")
        assertThat(out.text.length).isLessThan(4_000)
    }

    @Test
    fun `short result writes nothing`() = runTest {
        val root = tmp.newFolder("ws")
        val c = ctx(root)
        val content = "42"

        val out = ToolResultCompactor.compact("calculate", content, c)

        assertThat(out.path).isNull()
        assertThat(out.text).isEqualTo(content)
        assertThat(File(root, "notes").exists()).isFalse()
    }

    @Test
    fun `same content reuses one file instead of piling up copies`() = runTest {
        val root = tmp.newFolder("ws")
        val c = ctx(root)
        val content = "A".repeat(6_000)

        val first = ToolResultCompactor.compact("grep", content, c)
        val second = ToolResultCompactor.compact("grep", content, c)

        assertThat(first.path).isEqualTo(second.path)
        val files = File(root, "progress/sess1/notes/tool-output").listFiles()?.toList().orEmpty()
        assertThat(files).hasSize(1)
    }

    @Test
    fun `results are stored under the session directory`() = runTest {
        val root = tmp.newFolder("ws")
        val c = ctx(root, session = "progress/other-session")
        val content = "B".repeat(6_000)

        val out = ToolResultCompactor.compact("run_script", content, c)

        // 模型看到的是会话内相对路径，换会话不会串
        assertThat(out.path).isEqualTo("notes/tool-output/run_script-${content.hashCode().let { (it.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0') }}.txt")
        assertThat(File(root, "progress/other-session/${out.path}").exists()).isTrue()
    }
}
