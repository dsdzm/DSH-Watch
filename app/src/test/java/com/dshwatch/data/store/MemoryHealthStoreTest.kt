package com.dshwatch.data.store

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 记忆体检记录。
 *
 * 它存在的理由只有一个：用户说「聊了八十句还是没有记忆」时，界面上要能
 * 把「没到门槛 / 请求失败 / 摘要为空 / 没有会话」分开。所以这里只测两件事：
 * 写得进去读得出来，坏文件不许把记忆页带崩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryHealthStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `写进去能读出来`() = runTest {
        val dir = tmp.newFolder()
        val store = MemoryHealthStore(
            workspaceRoot = { dir },
            io = UnconfinedTestDispatcher(testScheduler),
            json = json,
        )

        store.write(
            MemoryHealth(
                at = 1_700_000_000_000,
                scene = "fish",
                cardId = "builtin-tavern-feiyu",
                tailCount = 13,
                tailTokens = 640,
                threshold = 1200,
                outcome = MemoryHealth.WRITTEN,
                written = 3,
            )
        )

        val h = store.read()
        assertEquals(MemoryHealth.WRITTEN, h?.outcome)
        assertEquals(13, h?.tailCount)
        assertEquals(3, h?.written)
        assertEquals("builtin-tavern-feiyu", h?.cardId)
    }

    @Test
    fun `文件坏了返回空而不是抛错`() = runTest {
        val dir = tmp.newFolder()
        File(File(dir, MemoryStore.DIR), "_health.json").let { f ->
            f.parentFile.mkdirs()
            f.writeText("{ not json")
        }
        val store = MemoryHealthStore(
            workspaceRoot = { dir },
            io = UnconfinedTestDispatcher(testScheduler),
            json = json,
        )

        assertNull(store.read())
    }

    /** 每种结果都有一句人话 */
    @Test
    fun `每种结果都有一句人话`() {
        val base = MemoryHealth(at = 1, scene = "fish", cardId = "c", tailCount = 6)

        assertTrue(
            MemoryHealth.describe(base.copy(outcome = MemoryHealth.SKIPPED))
                .contains("6 句")
        )
        assertTrue(
            MemoryHealth.describe(
                base.copy(outcome = MemoryHealth.FAILED, detail = "HTTP 401")
            ).contains("HTTP 401")
        )
        assertTrue(
            MemoryHealth.describe(base.copy(outcome = MemoryHealth.WRITTEN, written = 4))
                .contains("4 条")
        )
        // 不知名的结果不许吐出空白以外的噪音
        assertEquals("", MemoryHealth.describe(base.copy(outcome = "???")))
    }

    /**
     * 过期的「正在整理」不能说成正在整理。
     *
     * 用户报的现象就是「一直显示正在整理」：页面读的是磁盘上那条记录，
     * 而上一次整理被打断（关页面、进程被杀）就再也没人改它。
     */
    @Test
    fun `过期的正在整理改说成没走完`() {
        val now = 1_700_000_000_000
        val h = MemoryHealth(
            at = now - MemoryHealth.TRIGGER_ALIVE_MS - 1,
            scene = "tavern",
            cardId = "c",
            tailCount = 80,
            outcome = MemoryHealth.TRIGGERED,
        )
        val line = MemoryHealth.describe(h, now)
        assertTrue(line.contains("没走完"))

        // 刚点的还在有效期内，就该说正在整理
        assertTrue(
            MemoryHealth.describe(h.copy(at = now), now).contains("正在整理")
        )
    }

    /**
     * 结果不能倒回「正在整理」。
     *
     * 同一次整理会先写 trigger、再写结果，两条是各自异步落盘的。
     * 没有序号保护时，慢一步的 trigger 会盖掉结果 —— 界面就永远停在正在整理。
     */
    @Test
    fun `旧序号盖不掉新结果`() = runTest {
        val dir = tmp.newFolder()
        val store = MemoryHealthStore(
            workspaceRoot = { dir },
            io = UnconfinedTestDispatcher(testScheduler),
            json = json,
        )

        val trigger = MemoryHealth(
            at = 1_700_000_000_000,
            scene = "tavern",
            cardId = "c",
            outcome = MemoryHealth.TRIGGERED,
            seq = store.nextSeq(),
        )
        val done = MemoryHealth(
            at = 1_700_000_000_500,
            scene = "tavern",
            cardId = "c",
            outcome = MemoryHealth.WRITTEN,
            written = 3,
            seq = store.nextSeq(),
        )

        // 故意让"先发生的"晚落盘
        store.write(done)
        store.write(trigger)

        assertEquals(MemoryHealth.WRITTEN, store.read()?.outcome)
        assertEquals(3, store.read()?.written)
    }
}
