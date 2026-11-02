package com.dshwatch.data.store

import com.google.common.truth.Truth.assertThat
import com.dshwatch.ui.screens.writing.parseLore
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 长期记忆文档的读写。
 *
 * 用户可以在工作空间里直接打开 `memory/<cardId>.md` 编辑它，
 * 所以这里的每一条都等价于"别把用户手写的记忆弄坏"。
 */
class MemoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 用 Unconfined 调度器：store 内部所有 withContext(io) 都会立刻在调用栈上执行，
    // runTest 不需要去等真实 IO 线程 —— 否则虚拟时间不会推进，测试直接挂住。
    private fun store() = MemoryStore(
        workspaceRoot = { tmp.root },
        io = UnconfinedTestDispatcher(),
    )

    @Test
    fun `append creates header once and keeps order`() = runTest {
        val s = store()
        s.append("card1", "第一段摘要", "2026-01-01 10:00")
        s.append("card1", "第二段摘要", "2026-01-02 10:00")

        val text = s.read("card1")
        assertThat(text).contains("# 长期记忆")
        // 标题只出现一次 —— 否则每次压缩都会在文件里堆一层 "> 由 DSH-Watch 自动生成"
        assertThat(Regex("# 长期记忆").findAll(text).count()).isEqualTo(1)
        assertThat(text.indexOf("第一段摘要")).isLessThan(text.indexOf("第二段摘要"))
        assertThat(text).contains("2026-01-02 10:00")
    }

    @Test
    fun `default label is a timestamp header`() = runTest {
        val s = store()
        s.append("c", "内容")
        assertThat(s.read("c")).contains("## ")
    }

    @Test
    fun `read of a card with no memory is empty not null`() = runTest {
        assertThat(store().read("never-seen")).isEmpty()
        assertThat(store().exists("never-seen")).isFalse()
    }

    @Test
    fun `replace overwrites and clear removes the file`() = runTest {
        val s = store()
        s.append("c", "旧的内容", "t")
        s.replace("c", "手改后的内容")
        assertThat(s.read("c")).contains("手改后的内容")
        assertThat(s.read("c")).doesNotContain("旧的内容")

        s.clear("c")
        assertThat(s.exists("c")).isFalse()
        assertThat(s.read("c")).isEmpty()
    }

    @Test
    fun `overflow keeps the newest sections and the header`() = runTest {
        val s = store()
        // 每段 1000 字，塞 12 段一定超 8000 的预算
        repeat(12) { i ->
            s.append("big", "第${i}段" + "字".repeat(1000), "t$i")
        }
        val text = s.read("big")
        assertThat(text.length).isAtMost(MemoryStore.BUDGET_CHARS + 200)
        assertThat(text).contains("# 长期记忆")
        // 留下的应该是最近的，最早的被丢掉
        assertThat(text).contains("第11段")
        assertThat(text).doesNotContain("第0段" + "字".repeat(1000))
    }

    @Test
    fun `file lands under memory dir so users can open it`() = runTest {
        val s = store()
        s.append("abc", "x", "t")
        val f = File(tmp.root, "memory/abc.md")
        assertThat(f.exists()).isTrue()
    }
}

/**
 * 世界书粘贴导入。
 *
 * 用户从不按格式写文档，所以解析器必须"能捞就捞"：
 * 认得 `标题：内容`，也认得「首行标题 + 后文正文」，
 * 剩下的整段当常驻条目，绝不能因为格式不标准就丢掉内容。
 */
class ParseLoreTest {

    @Test
    fun `key value lines become separate entries`() {
        val out = parseLore("天气：常年下雨\n货币：贝壳")
        assertThat(out).hasSize(2)
        assertThat(out[0].title).isEqualTo("天气")
        assertThat(out[0].content).isEqualTo("常年下雨")
        assertThat(out[1].title).isEqualTo("货币")
    }

    @Test
    fun `title line plus body becomes one entry`() {
        val out = parseLore("旧城区\n\n这里三十年前发过大水。\n现在只剩老人。")
        assertThat(out).hasSize(1)
        assertThat(out[0].title).isEqualTo("旧城区")
        assertThat(out[0].content).contains("三十年前发过大水")
    }

    @Test
    fun `plain paragraph is kept as an always-on entry`() {
        val out = parseLore("这个世界没有电，全靠蒸汽和风。")
        assertThat(out).hasSize(1)
        assertThat(out[0].title).isEmpty()
        assertThat(out[0].content).contains("蒸汽和风")
        // 没给关键词 = 常驻，永远进提示
        assertThat(out[0].alwaysOn).isTrue()
    }

    @Test
    fun `blank input yields nothing`() {
        assertThat(parseLore("   \n\n  ")).isEmpty()
    }
}
