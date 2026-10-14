package com.dshwatch

import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.agent.build.ProjectBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「构建」这一步的测试。
 *
 * 手表上没有编译器，所以 build 的职责被定义为
 * **先校验、再打包成单文件**。这两件事都必须可测。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = WorkspaceStore(tmp.newFolder("ws"), UnconfinedTestDispatcher())

    @Test
    fun `missing entry fails with a helpful message`() = runTest {
        val s = store()
        s.write("other.html", "<html></html>")
        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isFalse()
        assertThat(report.issues.first().message).contains("找不到入口文件")
        assertThat(report.issues.first().message).contains("other.html")
    }

    @Test
    fun `syntax error blocks the build and reports file and line`() = runTest {
        val s = store()
        s.write("index.html", "<html><body><div>x</body></html>")
        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isFalse()
        assertThat(report.outputPath).isNull()
        assertThat(report.issues.any { it.severity == com.dshwatch.domain.agent.build.Severity.ERROR }).isTrue()
        assertThat(report.toToolText()).contains("构建失败")
        assertThat(report.toToolText()).contains("index.html")
    }

    @Test
    fun `clean project builds into dist`() = runTest {
        val s = store()
        s.write("index.html", "<html><body><h1>hi</h1></body></html>")
        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isTrue()
        assertThat(report.outputPath).isEqualTo("dist/index.html")
        assertThat(report.outputBytes).isGreaterThan(0)
        assertThat(s.exists("dist/index.html")).isTrue()
        assertThat(report.toToolText()).contains("构建成功")
    }

    @Test
    fun `external css and js are inlined into a single file`() = runTest {
        val s = store()
        s.write(
            "index.html",
            """
            <html>
              <head><link rel="stylesheet" href="styles/main.css"></head>
              <body><h1>hi</h1><script src="js/app.js"></script></body>
            </html>
            """.trimIndent(),
        )
        s.write("styles/main.css", "body{color:#0af}")
        s.write("js/app.js", "console.log('hi')")

        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isTrue()
        assertThat(report.inlined).containsExactly("styles/main.css", "js/app.js")

        val built = s.read("dist/index.html")!!
        assertThat(built).contains("body{color:#0af}")
        assertThat(built).contains("console.log('hi')")
        // 单文件产物里不应该还残留本地相对引用
        assertThat(built).doesNotContain("href=\"styles/main.css\"")
        assertThat(built).doesNotContain("src=\"js/app.js\"")
    }

    @Test
    fun `images are inlined as data uris`() = runTest {
        val s = store()
        s.write("index.html", """<html><body><img src="img/logo.png"></body></html>""")
        // 造一个最小的 PNG 头，够验证 base64 内联即可
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
        )
        s.resolveForUi("img/logo.png")!!.apply {
            parentFile?.mkdirs()
            writeBytes(png)
        }

        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isTrue()
        assertThat(report.inlined).contains("img/logo.png")
        assertThat(s.read("dist/index.html")!!).contains("data:image/png;base64,")
    }

    @Test
    fun `remote urls are never treated as workspace files`() = runTest {
        val s = store()
        s.write(
            "index.html",
            """
            <html><head>
              <link rel="stylesheet" href="https://cdn.example.com/a.css">
            </head><body><script src="//cdn.example.com/b.js"></script></body></html>
            """.trimIndent(),
        )
        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isTrue()
        assertThat(report.inlined).isEmpty()
        val built = s.read("dist/index.html")!!
        assertThat(built).contains("https://cdn.example.com/a.css")
        assertThat(built).contains("//cdn.example.com/b.js")
    }

    @Test
    fun `custom output name is honoured and kept inside the out dir`() = runTest {
        val s = store()
        s.write("pages/demo.html", "<html><body>ok</body></html>")
        val report = ProjectBuilder.build(s, "pages/demo.html", outName = "my/demo.html")
        assertThat(report.ok).isTrue()
        assertThat(report.outputPath).isEqualTo("dist/demo.html")
    }

    @Test
    fun `previous artifacts are not re-validated`() = runTest {
        val s = store()
        // dist 里放一个故意写坏的 html，不应该让后续构建失败
        s.write("dist/broken.html", "<html><body><div></body></html>")
        s.write("index.html", "<html><body>fine</body></html>")
        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isTrue()
    }

    @Test
    fun `json and css are validated as part of the build`() = runTest {
        val s = store()
        s.write("index.html", "<html><body><link rel=\"stylesheet\" href=\"a.css\"></body></html>")
        s.write("a.css", "body { color: red; ")   // 少一个 }
        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isFalse()
        assertThat(report.issues.any { it.path == "a.css" }).isTrue()
    }

    @Test
    fun `report text is compact enough for a tool result`() = runTest {
        val s = store()
        s.write("index.html", "<html><body>" + "<div>".repeat(40) + "</body></html>")
        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isFalse()
        // 回灌给模型的内容要短，否则会把上下文撑爆
        assertThat(report.toToolText().length).isLessThan(2000)
    }

    // ---------------- 路径归一化（真机回归） ----------------

    @Test
    fun `entry given as an absolute path is still resolved`() = runTest {
        val root = tmp.newFolder("ws")
        val s = WorkspaceStore(root, UnconfinedTestDispatcher())
        s.write("index.html", "<html><body>ok</body></html>")

        // 真机上模型就是这么传的：带包名的完整路径，且没有前导斜杠
        val report = ProjectBuilder.build(
            s,
            "data/data/com.dshwatch.debug/files/workspace/index.html",
        )

        assertThat(report.ok).isTrue()
        assertThat(report.entry).isEqualTo("index.html")
        assertThat(report.outputPath).isEqualTo("dist/index.html")
    }

    @Test
    fun `build self heals files stuck in the nested shell`() = runTest {
        val root = tmp.newFolder("ws")
        val s = WorkspaceStore(root, UnconfinedTestDispatcher())
        // 早期版本写歪的位置：文件在，但埋在深层嵌套里
        val nested = File(root, "data/data/com.dshwatch.debug/files/workspace")
        nested.mkdirs()
        File(nested, "index.html").writeText("<html><body>ok</body></html>")

        val report = ProjectBuilder.build(s, "index.html")

        // 构建前会自动把文件搬回正确位置，所以这次能成功
        assertThat(report.ok).isTrue()
        assertThat(File(root, "index.html").exists()).isTrue()
    }

    @Test
    fun `build reports how many files it had to repair`() = runTest {
        val root = tmp.newFolder("ws")
        val s = WorkspaceStore(root, UnconfinedTestDispatcher())
        val nested = File(root, "data/data/com.dshwatch.debug/files/workspace")
        nested.mkdirs()
        File(nested, "index.html").writeText("<html><body>ok</body></html>")

        val report = ProjectBuilder.build(s, "index.html")

        assertThat(report.healed).isEqualTo(1)
        assertThat(report.toToolText()).contains("已自动修复 1")
    }

    @Test
    fun `entry inside a project subdirectory builds fine`() = runTest {
        // 「自定义文件存放位置」的第三种形态：工作空间内按项目分目录
        val s = store()
        s.write("mysite/index.html", "<html><body>ok</body></html>")
        s.write("mysite/app.css", "body{color:red}")
        s.write("mysite/index.html", "<html><head><link rel=\"stylesheet\" href=\"app.css\"></head><body>ok</body></html>")

        val report = ProjectBuilder.build(s, "mysite/index.html")

        assertThat(report.ok).isTrue()
        assertThat(report.outputPath).isEqualTo("dist/index.html")
        // 子目录里的相对引用也要能正确内联
        assertThat(report.inlined).containsExactly("mysite/app.css")
    }

    @Test
    fun `missing entry message explains the expected path format`() = runTest {
        val s = store()
        s.write("other.html", "<html></html>")
        val report = ProjectBuilder.build(s, "index.html")
        assertThat(report.ok).isFalse()
        val text = report.issues.first().message
        assertThat(text).contains("找不到入口文件")
        assertThat(text).contains("other.html")
        assertThat(text).contains("相对于当前工作目录")
        // 必须点名「不要带前缀」—— 这是历史上踩过的坑（见 WorkspacePaths 的注释）
        assertThat(text).contains("/data/data/")
    }

    @Test
    fun `illegal entry path is reported instead of silently missing`() = runTest {
        val s = store()
        val report = ProjectBuilder.build(s, "/etc/passwd")
        assertThat(report.ok).isFalse()
        assertThat(report.issues.first().message).contains("入口路径不合法")
    }

    // ---------------- 产物目录可自定义 ----------------

    @Test
    fun `custom output dir is honoured`() = runTest {
        val s = store()
        s.write("index.html", "<html><body>ok</body></html>")
        val report = ProjectBuilder.build(s, "index.html", outName = "demo.html", outDir = "site")
        assertThat(report.ok).isTrue()
        assertThat(report.outputPath).isEqualTo("site/demo.html")
        assertThat(s.exists("site/demo.html")).isTrue()
    }

    @Test
    fun `artifacts in a custom out dir are not re-validated`() = runTest {
        val s = store()
        // site 里放一个故意写坏的 html，它是产物不是源码，不该让构建失败
        s.write("site/broken.html", "<html><body><div></body></html>")
        s.write("index.html", "<html><body>fine</body></html>")
        val report = ProjectBuilder.build(s, "index.html", outDir = "site")
        assertThat(report.ok).isTrue()
    }

    @Test
    fun `illegal out dir is rejected`() = runTest {
        val s = store()
        s.write("index.html", "<html><body>ok</body></html>")
        val report = ProjectBuilder.build(s, "index.html", outDir = "/sdcard/out")
        assertThat(report.ok).isFalse()
        assertThat(report.issues.first().message).contains("产物目录不合法")
    }
}
