package com.dshwatch.domain.agent.tools

import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.DeviceProfileDefaults
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.strOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `attach_file` / `device_screenshot` 的回归测试。
 *
 * 这两个工具解决的是一个具体的能力缺口：模型能读字、看不见画面。
 * 而"看不见"在手表上尤其糟 —— 那种设备往往连系统截图都没有，
 * 只能靠 adb 或脚本截一张图放在某处，然后模型对着路径想象。
 */
class AttachToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ctx(root: File): ToolContext = ToolContext(
        appContext = null, // JVM 里没有 Android Context；非图片分支用不到它
        workspace = WorkspaceStore(root, UnconfinedTestDispatcher()),
        usage = UsageRepository(root, json, UnconfinedTestDispatcher()),
        profile = { DeviceProfileDefaults.fallback },
        workspaceRoot = { root.absolutePath },
    )

    @Test
    fun `绝对路径的文本文件按正文读入`() = runTest {
        val file = tmp.newFile("screen.txt").apply { writeText("第一行\n第二行") }
        val result = AttachTools.AttachFileTool.execute(
            buildJsonObject { put("path", file.absolutePath) },
            ctx(tmp.newFolder()),
        )

        assertThat(result.ok).isTrue()
        assertThat(result.content).contains("第一行")
        assertThat(result.content).contains("第二行")
        // 文本走正文，不该同时占一份图片通道
        assertThat(result.images).isEmpty()
    }

    @Test
    fun `找不到文件时给出可操作的下一步`() = runTest {
        val result = AttachTools.AttachFileTool.execute(
            buildJsonObject { put("path", "/sdcard/Download/nope.png") },
            ctx(tmp.newFolder()),
        )

        assertThat(result.ok).isFalse()
        // 不能只说"失败"：模型要能据此换个做法（先截图、先 list_files）
        assertThat(result.content).contains("找不到")
        assertThat(result.content).contains("list_files")
    }

    @Test
    fun `别的应用私有目录不让读`() {
        val why = AttachTools.checkPath(
            path = "/data/data/com.tencent.mm/files/msg.db",
            selfPackage = "com.dshwatch",
            ownDirs = emptyList(),
        )
        assertThat(why).isNotNull()
        assertThat(why!!).contains("私有目录")
    }

    @Test
    fun `截图落点与自己的目录可读`() {
        assertThat(
            AttachTools.checkPath("/sdcard/Download/shot.png", "com.dshwatch", emptyList()),
        ).isNull()
        assertThat(
            AttachTools.checkPath("/data/local/tmp/s.png", "com.dshwatch", emptyList()),
        ).isNull()
        assertThat(
            AttachTools.checkPath(
                "/data/data/com.dshwatch/files/tool-images/a.png",
                "com.dshwatch",
                listOf("/data/data/com.dshwatch/files"),
            ),
        ).isNull()
        // 系统目录不在这条通道的范围内
        assertThat(
            AttachTools.checkPath("/system/etc/hosts", "com.dshwatch", emptyList()),
        ).isNotNull()
    }

    @Test
    fun `两个工具都进了内置工具表`() {
        val names = BuiltinTools.all().map { it.name }
        assertThat(names).contains("attach_file")
        assertThat(names).contains("device_screenshot")
    }

    /**
     * 截图工具必须在**没有 Shizuku 时不出现**。
     *
     * 工具表每轮都要进上下文；一个注定失败的 schema 常驻，既费 token
     * 又会把模型引到"先试一下截图"这条死路上。
     */
    @Test
    fun `没有 Shizuku 时截图工具不出现`() {
        val tool = AttachTools.DeviceScreenshotTool
        assertThat(
            tool.enabled(com.dshwatch.domain.model.AppSettings(shizukuEnabled = false)),
        ).isFalse()
        assertThat(
            tool.enabled(com.dshwatch.domain.model.AppSettings(shizukuEnabled = true)),
        ).isTrue()
    }

    @Test
    fun `截图走二进制通道而不是当文本读`() {
        // screencap 的 PNG 被当 UTF-8 解一次就废了：必须直接把进程 stdout 写进文件
        val shell = File("src/main/java/com/dshwatch/domain/shell/ShizukuShell.kt").readText()
        assertThat(shell).contains("suspend fun runIntoFile(")
        assertThat(shell).contains("input.copyTo(output)")

        val tool = File("src/main/java/com/dshwatch/domain/agent/tools/AttachTools.kt").readText()
        assertThat(tool).contains("ShizukuShell.runIntoFile(")
        assertThat(tool).contains("screencap -p")
    }
}
