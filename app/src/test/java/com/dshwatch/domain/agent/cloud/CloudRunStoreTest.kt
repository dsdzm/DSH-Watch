package com.dshwatch.domain.agent.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudRunStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = CloudRunStore(tmp.root)

    private fun record(id: Long, at: Long, status: String = "completed") = CloudRunRecord(
        runId = id,
        repo = "o/r",
        workflow = "dsh-run.yml",
        startedAt = at,
        status = status,
        conclusion = if (status == "completed") "success" else "",
    )

    @Test
    fun `unknown session yields empty list`() = runTest {
        assertThat(store().list("progress/aaa")).isEmpty()
        assertThat(store().latest("progress/aaa")).isNull()
    }

    @Test
    fun `records are scoped per session`() = runTest {
        val s = store()
        s.put("progress/aaa", record(1, 100))
        s.put("progress/bbb", record(2, 200))

        assertThat(s.list("progress/aaa").map { it.runId }).containsExactly(1L)
        assertThat(s.list("progress/bbb").map { it.runId }).containsExactly(2L)
    }

    @Test
    fun `latest returns the newest by start time`() = runTest {
        val s = store()
        s.put("s", record(1, 100))
        s.put("s", record(2, 300))
        s.put("s", record(3, 200))
        assertThat(s.latest("s")?.runId).isEqualTo(2L)
    }

    @Test
    fun `put overwrites the same run id instead of duplicating`() = runTest {
        val s = store()
        s.put("s", record(1, 100, status = "in_progress"))
        s.put("s", record(1, 100, status = "completed"))
        assertThat(s.list("s")).hasSize(1)
        assertThat(s.list("s").first().status).isEqualTo("completed")
    }

    @Test
    fun `update transforms an existing record`() = runTest {
        val s = store()
        s.put("s", record(1, 100, status = "in_progress"))
        val updated = s.update("s", 1) { it.copy(conclusion = "failure", status = "completed") }
        assertThat(updated?.conclusion).isEqualTo("failure")
        assertThat(s.findByRunId("s", 1)?.conclusion).isEqualTo("failure")
    }

    @Test
    fun `update on a missing run returns null`() = runTest {
        assertThat(store().update("s", 42) { it }).isNull()
    }

    @Test
    fun `corrupt json degrades to empty instead of crashing`() = runTest {
        tmp.newFile("progress_aaa.json").writeText("{ this is not json")
        assertThat(store().list("progress/aaa")).isEmpty()
    }

    @Test
    fun `list is capped so a long session does not grow forever`() = runTest {
        val s = store()
        repeat(CloudRunStore.MAX_PER_SESSION + 5) { i -> s.put("s", record(i.toLong(), i.toLong())) }
        assertThat(s.list("s")).hasSize(CloudRunStore.MAX_PER_SESSION)
        // 保留的是最近的，最老的被裁掉
        assertThat(s.list("s").first().runId).isGreaterThan(0L)
        assertThat(s.list("s").none { it.runId == 0L }).isTrue()
    }

    @Test
    fun `empty session dir maps to the root key`() = runTest {
        val s = store()
        s.put("", record(9, 1))
        assertThat(s.list("").map { it.runId }).containsExactly(9L)
    }
}
