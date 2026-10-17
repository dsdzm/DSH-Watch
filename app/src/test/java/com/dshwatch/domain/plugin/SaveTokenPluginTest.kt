package com.dshwatch.domain.plugin

import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `save-token` 的三条硬约束。
 *
 * 这个插件最危险的地方不是"转错了"，而是**转完模型读不懂**。
 * 所以测试的重点是：不划算时它必须一个字都不改，而且原文必须真的能取回来。
 */
class SaveTokenPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun input(content: String, session: String = "progress/x"): ToolResultInput {
        val root = tmp.newFolder()
        return ToolResultInput(
            toolName = "http_fetch",
            content = content,
            artifact = null,
            sessionDir = session,
            workspace = WorkspaceStore(root),
            settings = AppSettings(),
        )
    }

    /** 40 条用户记录：JSON 里键名要重复 40 次，TOON 只需要写一次表头 */
    private fun bigUsersJson(): String = buildString {
        append("[\n")
        for (i in 1..40) {
            append("""  {"id":$i,"name":"user$i","role":"member","active":true}""")
            if (i < 40) append(",")
            append("\n")
        }
        append("]")
    }

    @Test
    fun `converts a large uniform array and shortens it`() = runTest {
        val original = bigUsersJson()
        val out = SaveTokenPlugin.transform(input(original))

        assertThat(out).contains("[40]{id,name,role,active}:")
        assertThat(out.length).isLessThan(original.length)
        assertThat(out).doesNotContain("\"id\":1")
    }

    @Test
    fun `saves the original and tells the model where it is`() = runTest {
        val original = bigUsersJson()
        val inp = input(original)
        val out = SaveTokenPlugin.transform(inp)

        assertThat(out).contains("_raw/")
        assertThat(out).contains("read_file")

        // 真的能读回来 —— 这是"可还原"唯一有意义的验证方式
        val savedPath = out.substringAfter("已存 ").substringBefore("，")
        val readBack = inp.workspace.read(savedPath)
        assertThat(readBack).isEqualTo(original)
    }

    @Test
    fun `leaves short json alone`() = runTest {
        // 低于 200 字符：表头开销可能比省下的还多，转它没意义
        val small = """{"a":1,"b":2}"""
        assertThat(SaveTokenPlugin.transform(input(small))).isEqualTo(small)
    }

    @Test
    fun `leaves non json alone`() = runTest {
        val text = "这是一段普通文本，".repeat(40)
        assertThat(SaveTokenPlugin.transform(input(text))).isEqualTo(text)
    }

    @Test
    fun `leaves truncated json alone`() = runTest {
        // 上行工具结果可能被截断，半截 JSON 解析必然失败 —— 不能在上面继续做编码
        val broken = bigUsersJson().take(600)
        assertThat(SaveTokenPlugin.transform(input(broken))).isEqualTo(broken)
    }

    @Test
    fun `unsupported shape keeps the original untouched`() = runTest {
        // 字段不一致的数组：编码器放弃 → 插件也必须一个字都不改
        val json = buildString {
            append("[\n")
            for (i in 1..30) {
                append("""  {"id":$i,"extra$i":true}""")
                if (i < 30) append(",")
                append("\n")
            }
            append("]")
        }
        assertThat(SaveTokenPlugin.transform(input(json))).isEqualTo(json)
    }

    @Test
    fun `raw file is written under the session directory`() = runTest {
        val inp = input(bigUsersJson(), session = "progress/abc123")
        val out = SaveTokenPlugin.transform(inp)
        val savedPath = out.substringAfter("已存 ").substringBefore("，")
        assertThat(savedPath).startsWith("progress/abc123/_raw/http_fetch-")
        assertThat(savedPath).endsWith(".json")
    }
}
