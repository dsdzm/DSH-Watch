package com.dshwatch

import com.dshwatch.data.workspace.WorkspacePaths
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 路径归一化测试。
 *
 * 这个类存在的唯一原因是真机上踩到的坑：
 * 模型把 `data/data/<包名>/files/workspace/index.html`（**没有前导斜杠**）
 * 当成 path 参数传了进来。语法上它是合法的相对路径，于是文件被写进了 6 层深的嵌套目录，
 * 接着 `build_project` 报「找不到入口文件」，现象极难排查。
 */
class WorkspacePathsTest {

    /** 模拟真机：应用内部私有目录 */
    private val rootAbs = "/data/data/com.dshwatch.debug/files/workspace"

    private fun norm(p: String) = WorkspacePaths.normalize(p, rootAbs)

    // ---------------- 正常路径不该被改动 ----------------

    @Test
    fun `plain relative paths pass through`() {
        assertThat(norm("index.html")).isEqualTo("index.html")
        assertThat(norm("src/app.js")).isEqualTo("src/app.js")
        assertThat(norm("mysite/index.html")).isEqualTo("mysite/index.html")
        assertThat(norm("dist/out.html")).isEqualTo("dist/out.html")
    }

    @Test
    fun `dot segments and duplicate slashes are collapsed`() {
        assertThat(norm("./index.html")).isEqualTo("index.html")
        assertThat(norm("src//app.js")).isEqualTo("src/app.js")
        assertThat(norm("src/./app.js")).isEqualTo("src/app.js")
        assertThat(norm("src\\app.js")).isEqualTo("src/app.js")
    }

    @Test
    fun `empty path means the workspace root`() {
        assertThat(norm("")).isEqualTo("")
        assertThat(norm(" ")).isEqualTo("")
        assertThat(norm(".")).isEqualTo("")
    }

    // ---------------- 各种「其实指向沙盒」的写法 ----------------

    @Test
    fun `absolute path inside the sandbox is stripped to a relative one`() {
        assertThat(norm("$rootAbs/index.html")).isEqualTo("index.html")
        assertThat(norm("$rootAbs/src/app.js")).isEqualTo("src/app.js")
    }

    @Test
    fun `leading-slash-less absolute path is the real-world bug`() {
        // 真机上模型就是这么写的：没有前导斜杠，绕过「绝对路径」检查
        assertThat(norm("data/data/com.dshwatch.debug/files/workspace/index.html"))
            .isEqualTo("index.html")
        assertThat(norm("data/data/com.dshwatch.debug/files/workspace/src/app.js"))
            .isEqualTo("src/app.js")
    }

    @Test
    fun `data user form is handled`() {
        assertThat(norm("/data/user/0/com.dshwatch.debug/files/workspace/index.html"))
            .isEqualTo("index.html")
        assertThat(norm("data/user/10/com.dshwatch.debug/files/workspace/a.txt"))
            .isEqualTo("a.txt")
    }

    @Test
    fun `external private dir form is handled`() {
        assertThat(norm("/storage/emulated/0/Android/data/com.dshwatch.debug/files/workspace/index.html"))
            .isEqualTo("index.html")
        assertThat(norm("Android/data/com.dshwatch.debug/files/workspace/x.txt"))
            .isEqualTo("x.txt")
    }

    @Test
    fun `bare markers are stripped`() {
        assertThat(norm("files/workspace/index.html")).isEqualTo("index.html")
        assertThat(norm("/files/workspace/index.html")).isEqualTo("index.html")
        assertThat(norm("workspace/index.html")).isEqualTo("index.html")
    }

    @Test
    fun `another install path still resolves thanks to the stable marker`() {
        // 拿一个完全不同的根：靠 files/workspace 这段稳定标记仍能剥掉前缀。
        // release 包名与 debug 包名互串时就是这个场景。
        val other = "/data/data/com.dshwatch/files/workspace"
        assertThat(WorkspacePaths.normalize("data/data/com.dshwatch/files/workspace/index.html", other))
            .isEqualTo("index.html")
    }

    @Test
    fun `works on a plain temp directory root too`() {
        // 单测/临时目录下没有 files/workspace 这段，靠 root 段序列匹配
        val plain = "/tmp/junit-123/ws"
        assertThat(WorkspacePaths.normalize("/tmp/junit-123/ws/index.html", plain)).isEqualTo("index.html")
        assertThat(WorkspacePaths.normalize("index.html", plain)).isEqualTo("index.html")
        assertThat(WorkspacePaths.normalize("/tmp/other/x.txt", plain)).isNull()
    }

    // ---------------- 必须被拒绝 ----------------

    @Test
    fun `absolute paths outside the sandbox are rejected`() {
        assertThat(norm("/etc/passwd")).isNull()
        assertThat(norm("/sdcard/x.txt")).isNull()
        assertThat(norm("/data/data/com.dshwatch/databases/app.db")).isNull()
    }

    @Test
    fun `parent traversal is rejected`() {
        assertThat(norm("../outside.txt")).isNull()
        assertThat(norm("a/../../b.txt")).isNull()
        assertThat(norm("$rootAbs/../secret")).isNull()
    }

    @Test
    fun `control characters are rejected`() {
        assertThat(norm("bad\u0000name.txt")).isNull()
        assertThat(norm("bad\nname.txt")).isNull()
    }

    // ---------------- 嵌套壳剥离（自愈用） ----------------

    @Test
    fun `strip nested shell lifts files out`() {
        assertThat(
            WorkspacePaths.stripNestedShell(
                "data/data/com.dshwatch.debug/files/workspace/index.html",
                rootAbs,
            )
        ).isEqualTo("index.html")

        assertThat(
            WorkspacePaths.stripNestedShell(
                "data/data/com.dshwatch.debug/files/workspace/src/app.js",
                rootAbs,
            )
        ).isEqualTo("src/app.js")
    }

    @Test
    fun `clean paths have no shell to strip`() {
        assertThat(WorkspacePaths.stripNestedShell("index.html", rootAbs)).isNull()
        assertThat(WorkspacePaths.stripNestedShell("src/app.js", rootAbs)).isNull()
        assertThat(WorkspacePaths.stripNestedShell("dist/out.html", rootAbs)).isNull()
    }
}
