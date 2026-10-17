package com.dshwatch.domain.plugin

import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `token-saver` 的裁剪边界。
 *
 * 它是默认开启的插件 —— 一旦裁得不合适，**每一次**工具调用都会受影响，
 * 所以这里的用例偏向"不该动的时候必须一个字都不动"。
 */
class TokenSaverPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun input(tool: String, content: String): ToolResultInput = ToolResultInput(
        toolName = tool,
        content = content,
        artifact = null,
        sessionDir = "progress/x",
        workspace = WorkspaceStore(tmp.newFolder()),
        settings = AppSettings(),
    )

    @Test
    fun `short content is untouched`() = runTest {
        val text = "ok"
        assertThat(TokenSaverPlugin.transform(input("http_fetch", text))).isEqualTo(text)
    }

    @Test
    fun `long line list is folded keeping head and tail`() = runTest {
        // 200 行 grep 结果：模型要的是"命中了多少、在哪"，不是每一行
        val text = (1..200).joinToString("\n") { "src/file$it.kt:$it:some code here" }
        val out = TokenSaverPlugin.transform(input("grep", text))

        assertThat(out).contains("中间省略")
        assertThat(out).contains("src/file1.kt")
        assertThat(out).contains("src/file200.kt")   // 尾部必须留
        assertThat(out.length).isLessThan(TokenSaverPlugin.TARGET_CHARS + 100)
    }

    @Test
    fun `long prose keeps head and tail, not just head`() = runTest {
        // 散文形态：行很长，不能按行折叠 —— 只能头尾切
        val text = "开头部分。" + "中间内容。".repeat(600) + "末尾结论：失败原因是权限不足。"
        val out = TokenSaverPlugin.transform(input("http_fetch", text))

        assertThat(out).contains("中间省略")
        assertThat(out).startsWith("开头部分。")
        assertThat(out).contains("末尾结论：失败原因是权限不足。")
    }

    @Test
    fun `read_file is never trimmed`() = runTest {
        // 模型主动要的内容，裁它等于让它白读一次，还得再读一遍（更贵）
        val text = (1..500).joinToString("\n") { "val line$it = $it" }
        assertThat(TokenSaverPlugin.transform(input("read_file", text))).isEqualTo(text)
    }

    @Test
    fun `formatted json is not folded as a line list`() = runTest {
        // 格式化的 JSON 行数很多但每行都不短，折叠会把结构切碎
        val text = buildString {
            append("{\n")
            for (i in 1..80) {
                append("""  "key$i": "value $i with a bit of text to make the line longer",""")
                append("\n")
            }
            append("}")
        }
        val out = TokenSaverPlugin.transform(input("http_fetch", text))
        // 关键区别：走的是"省略 N 个字符"（头尾切），不是"省略 N 行"（按行折）
        assertThat(out).doesNotContain("行）")
        assertThat(out).contains("个字符")
    }

    @Test
    fun `emoji at the cut boundary is not broken`() = runTest {
        // 在代理对中间切断会得到半个字符，模型看到的是乱码而不是"数据被截断"
        val text = "🎉".repeat(3_000)
        val out = TokenSaverPlugin.transform(input("http_fetch", text))
        // 逐字符检查：不应出现孤立的代理项
        var i = 0
        while (i < out.length) {
            val ch = out[i]
            if (Character.isHighSurrogate(ch)) {
                assertThat(Character.isLowSurrogate(out[i + 1])).isTrue()
                i += 2
            } else {
                assertThat(Character.isLowSurrogate(ch)).isFalse()
                i++
            }
        }
    }

    @Test
    fun `output stays within target`() = runTest {
        val text = "x".repeat(20_000)
        val out = TokenSaverPlugin.transform(input("http_fetch", text))
        assertThat(out.length).isAtMost(TokenSaverPlugin.TARGET_CHARS + 40)
    }
}
