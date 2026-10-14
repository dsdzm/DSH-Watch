package com.dshwatch

import com.dshwatch.data.workspace.WorkspaceStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 工作空间测试。
 *
 * 重点在**安全边界**：文件路径来自模型，属于不可信输入。
 * 一条 `../../databases/xxx` 就能写到应用私有目录甚至系统目录，
 * 所以路径校验必须有测试兜着。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(root: File) = WorkspaceStore(root, UnconfinedTestDispatcher())

    @Test
    fun `write and read round trip creates parent directories`() = runTest {
        val s = store(tmp.newFolder("ws"))
        val entry = s.write("src/styles/main.css", "body{color:red}")
        assertThat(entry).isNotNull()
        assertThat(entry!!.path).isEqualTo("src/styles/main.css")
        assertThat(s.read("src/styles/main.css")).isEqualTo("body{color:red}")
    }

    @Test
    fun `append accumulates content`() = runTest {
        val s = store(tmp.newFolder("ws"))
        s.write("a.txt", "1")
        s.append("a.txt", "2")
        s.append("a.txt", "3")
        assertThat(s.read("a.txt")).isEqualTo("123")
    }

    @Test
    fun `list returns files with relative paths`() = runTest {
        val s = store(tmp.newFolder("ws"))
        s.write("index.html", "<html></html>")
        s.write("dist/out.html", "x")
        val paths = s.listFilesRecursive().map { it.path }
        assertThat(paths).containsExactly("dist/out.html", "index.html")
    }

    @Test
    fun `mkdir and delete work`() = runTest {
        val s = store(tmp.newFolder("ws"))
        assertThat(s.mkdir("assets/img")).isTrue()
        s.write("assets/img/a.txt", "x")
        assertThat(s.exists("assets/img/a.txt")).isTrue()
        assertThat(s.delete("assets")).isTrue()
        assertThat(s.exists("assets/img/a.txt")).isFalse()
    }

    // ---------------- 安全边界 ----------------

    @Test
    fun `parent traversal is rejected`() = runTest {
        val root = tmp.newFolder("ws")
        val s = store(root)
        for (evil in listOf(
            "../outside.txt",
            "../../etc/passwd",
            "a/../../b.txt",
            "a/b/../../../c.txt",
            "./../x",
        )) {
            assertThat(s.write(evil, "pwned")).isNull()
            assertThat(s.read(evil)).isNull()
            assertThat(s.mkdir(evil)).isFalse()
            assertThat(s.delete(evil)).isFalse()
        }
        // 沙盒外确实什么都没被创建
        assertThat(File(root.parentFile, "outside.txt").exists()).isFalse()
    }

    @Test
    fun `absolute paths are rejected`() = runTest {
        val s = store(tmp.newFolder("ws"))
        assertThat(s.write("/data/data/com.dshwatch/files/x", "pwned")).isNull()
        assertThat(s.write("/sdcard/x.txt", "pwned")).isNull()
        assertThat(s.read("/etc/hosts")).isNull()
    }

    @Test
    fun `empty and dot paths resolve to the root`() = runTest {
        val s = store(tmp.newFolder("ws"))
        assertThat(s.list("").isEmpty()).isTrue()
        assertThat(s.list(".").isEmpty()).isTrue()
        // 但不允许删除根本身
        assertThat(s.delete("")).isFalse()
        assertThat(s.delete(".")).isFalse()
    }

    @Test
    fun `control characters are rejected`() = runTest {
        val s = store(tmp.newFolder("ws"))
        assertThat(s.write("bad\u0000name.txt", "x")).isNull()
        assertThat(s.write("bad\nname.txt", "x")).isNull()
    }

    @Test
    fun `oversized single file is rejected`() = runTest {
        val s = store(tmp.newFolder("ws"))
        val big = "a".repeat((WorkspaceStore.MAX_FILE_BYTES + 10).toInt())
        assertThat(s.write("big.txt", big)).isNull()
        assertThat(s.exists("big.txt")).isFalse()
    }

    @Test
    fun `overwriting does not double count against the quota`() = runTest {
        val s = store(tmp.newFolder("ws"))
        val chunk = "a".repeat(WorkspaceStore.MAX_FILE_BYTES.toInt())
        // 反复覆盖同一个文件不应该因为总量超限而失败
        repeat(3) {
            assertThat(s.write("same.txt", chunk)).isNotNull()
        }
    }

    @Test
    fun `path with unusual characters is still contained`() = runTest {
        val s = store(tmp.newFolder("ws"))
        // 这类名字在真实文件系统上应当只是普通文件名，不能逃出沙盒
        val weird = "a b'c\"d\$e.txt"
        val entry = s.write(weird, "x")
        assertThat(entry).isNotNull()
        assertThat(entry!!.path).isEqualTo(weird)
    }

    @Test
    fun `total bytes and file count are reported`() = runTest {
        val s = store(tmp.newFolder("ws"))
        s.write("a.txt", "12345")
        s.write("b.txt", "123")
        assertThat(s.totalBytes()).isEqualTo(8L)
        assertThat(s.fileCount()).isEqualTo(2)
    }

    @Test
    fun `read truncates very long content`() = runTest {
        val s = store(tmp.newFolder("ws"))
        s.write("long.txt", "x".repeat(50_000))
        assertThat(s.read("long.txt", maxChars = 100)!!.length).isEqualTo(100)
    }

    // ---------------- 路径归一化（真机回归） ----------------

    @Test
    fun `full sandbox absolute path lands in the right place`() = runTest {
        val root = tmp.newFolder("ws")
        val s = store(root)
        // 模型把工作空间的真实位置当参数传了进来：应当被归一化成相对路径
        val entry = s.write(root.absolutePath + "/index.html", "<html></html>")
        assertThat(entry).isNotNull()
        assertThat(entry!!.path).isEqualTo("index.html")
        assertThat(File(root, "index.html").exists()).isTrue()
    }

    @Test
    fun `package style path without leading slash does not create nested dirs`() = runTest {
        val root = tmp.newFolder("ws")
        val s = store(root)
        // 真机上踩到的那个 bug：这串没有前导斜杠，语法上是合法相对路径，
        // 早期版本会真的建出 <root>/data/data/<pkg>/files/workspace/index.html
        val buggy = "data/data/com.dshwatch.debug/files/workspace/index.html"
        val entry = s.write(buggy, "<html></html>")
        assertThat(entry!!.path).isEqualTo("index.html")
        assertThat(File(root, "data").exists()).isFalse()
    }

    @Test
    fun `files workspace prefix is stripped`() = runTest {
        // 目录名取 workspace，和真机上的根目录名一致 —— 「workspace/ 前缀」这条规则
        // 靠的就是根目录名匹配
        val s = store(tmp.newFolder("workspace"))
        assertThat(s.write("files/workspace/a.txt", "x")!!.path).isEqualTo("a.txt")
        assertThat(s.write("workspace/b.txt", "x")!!.path).isEqualTo("b.txt")
    }

    @Test
    fun `describe issue names the real mistake`() = runTest {
        val s = store(tmp.newFolder("ws"))
        assertThat(s.describeIssue("index.html")).isNull()
        assertThat(s.describeIssue("src/app.js")).isNull()
        // 归一化能救回来的写法不算问题
        assertThat(s.describeIssue("data/data/com.dshwatch.debug/files/workspace/x.txt")).isNull()
        assertThat(s.describeIssue("/etc/passwd")).contains("绝对路径")
        assertThat(s.describeIssue("../x")).contains("..")
    }

    // ---------------- 脏数据自愈 ----------------

    @Test
    fun `self heal lifts files out of the nested shell`() = runTest {
        val root = tmp.newFolder("ws")
        val s = store(root)
        // 手工造出早期版本写歪的目录结构
        val nested = File(root, "data/data/com.dshwatch.debug/files/workspace")
        nested.mkdirs()
        File(nested, "index.html").writeText("<html></html>")
        File(nested, "src").mkdirs()
        File(nested, "src/app.js").writeText("console.log(1)")

        val moved = s.selfHeal()

        assertThat(moved).isEqualTo(2)
        assertThat(s.read("index.html")).isEqualTo("<html></html>")
        assertThat(s.read("src/app.js")).isEqualTo("console.log(1)")
        // 空壳目录被清理干净
        assertThat(File(root, "data").exists()).isFalse()
    }

    @Test
    fun `self heal never overwrites the correct file`() = runTest {
        val root = tmp.newFolder("ws")
        val s = store(root)
        s.write("index.html", "correct")
        val nested = File(root, "data/data/com.dshwatch.debug/files/workspace")
        nested.mkdirs()
        File(nested, "index.html").writeText("stale")

        s.selfHeal()

        assertThat(s.read("index.html")).isEqualTo("correct")
    }

    @Test
    fun `self heal is a no-op on a clean workspace`() = runTest {
        val s = store(tmp.newFolder("ws"))
        s.write("index.html", "x")
        s.write("dist/out.html", "y")
        assertThat(s.selfHeal()).isEqualTo(0)
    }

    @Test
    fun `list heals lazily without an explicit selfHeal call`() = runTest {
        // 真机上「启动时自愈」这个前提并不成立：覆盖安装后进程可能不重建，
        // 用户也可能在自愈跑完前就点了构建。所以自愈必须挂在每个访问入口上。
        val root = tmp.newFolder("ws")
        val s = store(root)
        val nested = File(root, "data/data/com.dshwatch.debug/files/workspace")
        nested.mkdirs()
        File(nested, "index.html").writeText("legacy")

        // 不显式调 selfHeal，直接列目录 —— 结果里就该是干净路径
        assertThat(s.listFilesRecursive().map { it.path }).containsExactly("index.html")
        assertThat(File(root, "index.html").exists()).isTrue()
    }

    @Test
    fun `a real data directory is not mistaken for the legacy shell`() = runTest {
        // 模型合法地建了一个 data/ 子目录时，不能被自愈逻辑误伤
        val s = store(tmp.newFolder("ws"))
        s.write("data/config.json", """{"a":1}""")
        assertThat(s.selfHeal()).isEqualTo(0)
        assertThat(s.read("data/config.json")).isEqualTo("""{"a":1}""")
        assertThat(s.listFilesRecursive().map { it.path }).containsExactly("data/config.json")
    }

    // ---------------- 读取容错（自愈还没跑到时也能读） ----------------

    @Test
    fun `read finds files still stuck in the legacy location`() = runTest {
        val root = tmp.newFolder("ws")
        val s = store(root)
        val nested = File(root, "data/data/com.dshwatch.debug/files/workspace")
        nested.mkdirs()
        File(nested, "index.html").writeText("legacy")

        // 两种写法都要能读到，否则用户点预览就是「文件不存在」
        assertThat(s.read("index.html")).isEqualTo("legacy")
        assertThat(s.read("data/data/com.dshwatch.debug/files/workspace/index.html")).isEqualTo("legacy")
    }

    // ---------------- 存放位置可切换 ----------------

    @Test
    fun `relocate moves everything to the new root`() = runTest {
        val first = tmp.newFolder("ws-a")
        val second = tmp.newFolder("ws-b")
        val s = store(first)
        s.write("index.html", "hi")
        s.write("src/app.js", "x")

        val moved = s.relocate(second)

        assertThat(moved).isEqualTo(2)
        assertThat(s.rootPath()).isEqualTo(second.absolutePath)
        assertThat(s.read("index.html")).isEqualTo("hi")
        assertThat(s.read("src/app.js")).isEqualTo("x")
        assertThat(File(first, "index.html").exists()).isFalse()
    }

    @Test
    fun `relocate to the same root is a no-op`() = runTest {
        val root = tmp.newFolder("ws")
        val s = store(root)
        s.write("a.txt", "x")
        assertThat(s.relocate(root)).isEqualTo(0)
        assertThat(s.read("a.txt")).isEqualTo("x")
    }
}
