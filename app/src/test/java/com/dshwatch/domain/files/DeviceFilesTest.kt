package com.dshwatch.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 内置文件浏览器的读取逻辑。
 *
 * 只测纯逻辑（排序、过滤、截断、三态）—— 真机上「能不能读公共目录」取决于
 * 存储权限与 ROM，JVM 里没有可复现的办法，只能靠真机验收。
 */
class DeviceFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun node(name: String, dir: Boolean = false) = FileNode(
        name = name,
        file = File("/x/$name"),
        isDir = dir,
        size = 0,
        modifiedAt = 0,
    )

    @Test
    fun `folders come before files, each sorted by name`() {
        val sorted = DeviceFiles.sort(
            listOf(
                node("b.txt"),
                node("第二层", dir = true),
                node("A.txt"),
                node("甲目录", dir = true),
            ),
        )
        assertEquals(listOf("第二层", "甲目录", "A.txt", "b.txt"), sorted.map { it.name })
    }

    @Test
    fun `chinese names sort by pinyin, not by code point`() {
        // 按 Unicode 排会得到「张三 李四 王五」（张 U+5F20 最小），
        // 按拼音才是「李四 王五 张三」—— 用户翻目录时认的是后者
        val sorted = DeviceFiles.sort(listOf(node("张三"), node("李四"), node("王五")))
        assertEquals(listOf("李四", "王五", "张三"), sorted.map { it.name })
    }

    @Test
    fun `hidden entries are skipped`() {
        val dir = tmp.newFolder("d")
        File(dir, "a.txt").writeText("a")
        File(dir, ".nomedia").writeText("")
        File(dir, ".thumbnails").mkdirs()

        val listing = DeviceFiles.list(dir) as Listing.Ok
        assertEquals(listOf("a.txt"), listing.nodes.map { it.name })
    }

    @Test
    fun `missing folder is not reported as empty`() {
        assertTrue(DeviceFiles.list(File(tmp.root, "nope")) is Listing.Missing)
    }

    @Test
    fun `large folders are truncated and the remainder is reported`() {
        val dir = tmp.newFolder("many")
        repeat(DeviceFiles.MAX_ITEMS + 7) { File(dir, "f%04d.txt".format(it)).writeText("") }

        val listing = DeviceFiles.list(dir) as Listing.Ok
        assertEquals(DeviceFiles.MAX_ITEMS, listing.nodes.size)
        assertEquals(7, listing.truncated)
    }

    @Test
    fun `canOpen rejects folders and missing files`() {
        val dir = tmp.newFolder("d")
        val file = File(dir, "ok.txt").apply { writeText("hello") }
        assertTrue(DeviceFiles.canOpen(file))
        assertFalse(DeviceFiles.canOpen(dir))
        assertFalse(DeviceFiles.canOpen(File(dir, "gone.txt")))
    }

    @Test
    fun `roots never contains a directory that does not exist`() {
        val workspace = tmp.newFolder("workspace")
        val roots = DeviceFiles.roots(workspace)
        // 工作空间一定在，且排第一：它是唯一不需要任何权限就能读的地方
        assertEquals(RootKind.WORKSPACE, roots.first().kind)
        assertTrue(roots.all { it.dir.isDirectory })
    }
}
