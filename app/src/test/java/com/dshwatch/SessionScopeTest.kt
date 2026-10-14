package com.dshwatch

import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.workspace.WorkspacePaths
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.agent.build.ProjectBuilder
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 会话级工作目录。
 *
 * 核心诉求：**每个会话的产物互不干扰** —— 上个会话留下的 `index.html`
 * 不该被这个会话的 build 捡走，也不该被它的 list_files 看到。
 */
class SessionScopeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val io = UnconfinedTestDispatcher()

    private fun store(dir: File = tmp.newFolder("ws")) = WorkspaceStore(dir, io)

    private fun convStore() = ConversationStore(tmp.newFolder("conv"), json, io)

    private val root = "/data/data/com.dshwatch/files/workspace"

    // ---------------- 路径作用域 ----------------

    @Test
    fun `relative path is nested under the session dir`() {
        assertThat(WorkspacePaths.resolveUnder("index.html", "progress/a1b2c3", root))
            .isEqualTo("progress/a1b2c3/index.html")
        assertThat(WorkspacePaths.resolveUnder("src/app.js", "progress/a1b2c3", root))
            .isEqualTo("progress/a1b2c3/src/app.js")
    }

    @Test
    fun `absolute path is stripped before nesting`() {
        // 这条是关键回归：必须先剥壳再拼前缀。
        // 顺序反了的话会得到 progress/a1b2c3/data/data/<pkg>/files/workspace/index.html ——
        // 也就是那个曾经让「找不到入口文件」查了半天的 6 层嵌套目录。
        assertThat(
            WorkspacePaths.resolveUnder(
                "data/data/com.dshwatch/files/workspace/index.html",
                "progress/a1b2c3",
                root,
            )
        ).isEqualTo("progress/a1b2c3/index.html")

        assertThat(
            WorkspacePaths.resolveUnder("/data/data/com.dshwatch/files/workspace/a.txt", "progress/x", root)
        ).isEqualTo("progress/x/a.txt")
    }

    @Test
    fun `already scoped path is not double nested`() {
        // 模型可能照抄上一条 list_files 结果里的完整路径
        assertThat(WorkspacePaths.resolveUnder("progress/a1b2c3/index.html", "progress/a1b2c3", root))
            .isEqualTo("progress/a1b2c3/index.html")
    }

    @Test
    fun `escaping the workspace is rejected`() {
        assertThat(WorkspacePaths.resolveUnder("../secret", "progress/x", root)).isNull()
        assertThat(WorkspacePaths.resolveUnder("/etc/passwd", "progress/x", root)).isNull()
    }

    @Test
    fun `blank path resolves to the session dir itself`() {
        assertThat(WorkspacePaths.resolveUnder("", "progress/x", root)).isEqualTo("progress/x")
    }

    @Test
    fun `stripBase is the inverse of resolveUnder`() {
        val scoped = WorkspacePaths.resolveUnder("src/app.js", "progress/x", root)!!
        assertThat(WorkspacePaths.stripBase(scoped, "progress/x")).isEqualTo("src/app.js")
        // 不是自己前缀的路径原样返回，不会被误切
        assertThat(WorkspacePaths.stripBase("progress/other/a.js", "progress/x"))
            .isEqualTo("progress/other/a.js")
    }

    @Test
    fun `empty base keeps the old flat behaviour`() {
        assertThat(WorkspacePaths.resolveUnder("index.html", "", root)).isEqualTo("index.html")
        assertThat(WorkspacePaths.stripBase("index.html", "")).isEqualTo("index.html")
    }

    // ---------------- 会话存储 ----------------

    @Test
    fun `new conversation gets its own progress dir`() {
        val conv = ConversationStore.newConversation("p1", "m1", "preset")
        assertThat(conv.workDir).startsWith(ConversationStore.WORKSPACE_ROOT_DIR + "/")
        assertThat(conv.workDir.removePrefix("progress/")).isEqualTo(conv.id.take(6))
    }

    @Test
    fun `two conversations get different dirs`() {
        val a = ConversationStore.newConversation("p", "m", "x")
        val b = ConversationStore.newConversation("p", "m", "x")
        assertThat(a.workDir).isNotEqualTo(b.workDir)
    }

    @Test
    fun `empty conversations are hidden from the list`() = runTest {
        val s = convStore()
        val empty = ConversationStore.newConversation("p", "m", "x")
        s.create(empty)

        // 这正是用户抱怨的那个问题：点一次「新建」列表里就多一条空的
        assertThat(s.index()).isEmpty()
    }

    @Test
    fun `a conversation shows up once it has a message`() = runTest {
        val s = convStore()
        val conv = ConversationStore.newConversation("p", "m", "x")
        s.create(conv)
        s.save(
            com.dshwatch.data.store.StoredConversation(
                meta = conv,
                messages = listOf(
                    ConversationStore.newMessage(Role.USER, "你好", MessageStatus.DONE),
                ),
            )
        )
        assertThat(s.index()).hasSize(1)
    }

    @Test
    fun `pruneEmpty removes the leftovers`() = runTest {
        val s = convStore()
        repeat(3) { s.create(ConversationStore.newConversation("p", "m", "x")) }
        assertThat(s.pruneEmpty()).isEqualTo(3)
        assertThat(s.pruneEmpty()).isEqualTo(0)
    }

    @Test
    fun `updateMeta can move a conversation to another dir`() = runTest {
        val s = convStore()
        val conv = ConversationStore.newConversation("p", "m", "x")
        s.save(
            com.dshwatch.data.store.StoredConversation(
                meta = conv,
                messages = listOf(ConversationStore.newMessage(Role.USER, "hi", MessageStatus.DONE)),
            )
        )
        s.updateMeta(conv.id) { it.copy(workDir = "progress/mine") }
        assertThat(s.index().single().workDir).isEqualTo("progress/mine")
        assertThat(s.load(conv.id)!!.meta.workDir).isEqualTo("progress/mine")
    }

    // ---------------- 构建隔离 ----------------

    @Test
    fun `two sessions build independent artifacts`() = runTest {
        val s = store()
        s.write("progress/aaa/index.html", "<html><body>AAA</body></html>")
        s.write("progress/bbb/index.html", "<html><body>BBB</body></html>")

        val ra = ProjectBuilder.build(s, entry = "index.html", baseDir = "progress/aaa")
        val rb = ProjectBuilder.build(s, entry = "index.html", baseDir = "progress/bbb")

        assertThat(ra.ok).isTrue()
        assertThat(rb.ok).isTrue()
        assertThat(ra.outputPath).isEqualTo("progress/aaa/dist/index.html")
        assertThat(rb.outputPath).isEqualTo("progress/bbb/dist/index.html")
        assertThat(s.read("progress/aaa/dist/index.html")).contains("AAA")
        assertThat(s.read("progress/bbb/dist/index.html")).contains("BBB")
    }

    @Test
    fun `build does not pick up another session's source files`() = runTest {
        val s = store()
        // bbb 会话里放一个语法错误的文件；aaa 构建时不该被它拖累
        s.write("progress/aaa/index.html", "<html><body>ok</body></html>")
        s.write("progress/bbb/broken.js", "function ( {")

        val report = ProjectBuilder.build(s, entry = "index.html", baseDir = "progress/aaa")
        assertThat(report.ok).isTrue()
        assertThat(report.issues).isEmpty()
    }

    @Test
    fun `python project is validated but produces no artifact`() = runTest {
        val s = store()
        s.write("progress/aaa/main.py", "def main()\n    print(1)\n")

        // 不指定入口：应当自动挑一个可校验的源文件
        val report = ProjectBuilder.build(s, entry = null, baseDir = "progress/aaa")

        // 有语法错（def 后缺冒号）→ 失败并给出文件:行号
        assertThat(report.ok).isFalse()
        assertThat(report.issues.first().path).isEqualTo("main.py")
        assertThat(report.issues.first().line).isEqualTo(1)
    }

    @Test
    fun `clean python project passes without fabricating an artifact`() = runTest {
        val s = store()
        s.write("progress/aaa/main.py", "def main():\n    print(1)\n\nmain()\n")

        val report = ProjectBuilder.build(s, entry = "main.py", baseDir = "progress/aaa")

        assertThat(report.ok).isTrue()
        // 关键：不产出 dist/ 里的假产物。手表上没有 Python 编译器，
        // 造一个「构建成功」的产物只会误导用户。
        assertThat(report.outputPath).isNull()
        assertThat(s.listFilesRecursive("progress/aaa/dist")).isEmpty()
        assertThat(report.toToolText()).contains("校验通过")
    }

    @Test
    fun `kotlin source is checked too`() = runTest {
        val s = store()
        s.write("progress/aaa/Main.kt", "fun main() {\n    println(\"hi\")\n")
        val report = ProjectBuilder.build(s, entry = "Main.kt", baseDir = "progress/aaa")
        assertThat(report.ok).isFalse()
        assertThat(report.issues.map { it.message }.joinToString()).contains("右括号")
    }

    @Test
    fun `entry path is reported relative to the session dir`() = runTest {
        val s = store()
        s.write("progress/aaa/index.html", "<html><body>ok</body></html>")
        val report = ProjectBuilder.build(s, entry = "index.html", baseDir = "progress/aaa")
        // 回灌给模型的文本里不该出现一长串前缀，否则它下次会照抄
        assertThat(report.entry).isEqualTo("index.html")
        assertThat(report.toToolText()).contains("index.html")
        assertThat(report.toToolText()).doesNotContain("progress/aaa/index.html")
    }
}
