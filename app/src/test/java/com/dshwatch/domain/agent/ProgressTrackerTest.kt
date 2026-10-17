package com.dshwatch.domain.agent

import com.dshwatch.domain.model.ToolCallRecord
import com.dshwatch.domain.model.ToolStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 无进展检测。
 *
 * 这里最要紧的**不是**"能停下"，而是"**不会误杀**"：
 * 一个要连续改 10 个文件的任务，每一步都是新指纹，必须一直算进展；
 * 而反复调同一个工具同参数，才是真的卡住了。
 */
class ProgressTrackerTest {

    private fun call(
        name: String,
        args: String = "{}",
        status: ToolStatus = ToolStatus.SUCCESS,
        artifact: String = "",
    ) = ToolCallRecord(
        id = "call-$name",
        name = name,
        arguments = args,
        status = status,
        artifact = artifact,
    )

    private val progress = ProgressTracker.Verdict.Progress::class.java
    private val noProgress = ProgressTracker.Verdict.NoProgress::class.java
    private val stalled = ProgressTracker.Verdict.Stalled::class.java

    @Test
    fun `writing different files each step is always progress`() {
        // 长任务不能被误杀：12 步各自写不同文件，全程都算进展
        val tracker = ProgressTracker()
        repeat(12) { i ->
            val verdict = tracker.record(listOf(call("write_file", """{"path":"file$i.txt"}""")))
            assertThat(verdict).isInstanceOf(progress)
        }
    }

    @Test
    fun `same tool with same args stalls after the threshold`() {
        val tracker = ProgressTracker(stallThreshold = 3)
        val batch = listOf(call("read_file", """{"path":"a.txt"}"""))

        assertThat(tracker.record(batch)).isInstanceOf(progress)      // 第一次是新指纹
        assertThat(tracker.record(batch)).isInstanceOf(noProgress)    // 第 1 步无进展
        assertThat(tracker.record(batch)).isInstanceOf(noProgress)    // 第 2 步
        assertThat(tracker.record(batch)).isInstanceOf(stalled)       // 第 3 步 → 停
    }

    @Test
    fun `whitespace-only differences are not progress`() {
        val tracker = ProgressTracker(stallThreshold = 2)
        tracker.record(listOf(call("read_file", """{"path": "a.txt"}""")))
        val verdict = tracker.record(listOf(call("read_file", """{"path":"a.txt"}""")))
        assertThat(verdict).isInstanceOf(noProgress)
    }

    @Test
    fun `two consecutive all-failed steps stall`() {
        val tracker = ProgressTracker()
        val failed = listOf(call("run_script", """{"code":"exit 1"}""", ToolStatus.FAILED))

        tracker.record(failed)  // 第一次：allFailedStreak=1，还没到阈值
        assertThat(tracker.record(failed)).isInstanceOf(stalled)
    }

    @Test
    fun `a brand-new argument on a failed call is still not progress`() {
        // 失败调用不进指纹 —— 否则"换个参数再失败一次"会被当成进展，永远停不下来
        val tracker = ProgressTracker(stallThreshold = 2)
        tracker.record(listOf(call("read_file", """{"path":"a"}""", ToolStatus.SUCCESS)))
        val verdict = tracker.record(listOf(call("read_file", """{"path":"b"}""", ToolStatus.FAILED)))
        assertThat(verdict).isInstanceOf(noProgress)
    }

    @Test
    fun `mixed success and failure counts as progress`() {
        val tracker = ProgressTracker()
        val verdict = tracker.record(
            listOf(
                call("read_file", """{"path":"a"}""", ToolStatus.SUCCESS),
                call("run_script", """{"code":"x"}""", ToolStatus.FAILED),
            )
        )
        assertThat(verdict).isInstanceOf(progress)
    }

    @Test
    fun `a new artifact counts as progress even with identical args`() {
        val tracker = ProgressTracker(stallThreshold = 2)
        tracker.record(listOf(call("build_project", """{"entry":"a"}""", artifact = "dist/a.html")))
        val verdict = tracker.record(
            listOf(call("build_project", """{"entry":"a"}""", artifact = "dist/b.html"))
        )
        assertThat(verdict).isInstanceOf(progress)
    }

    @Test
    fun `empty batch never stalls`() {
        // 一轮里没有任何工具调用时，主循环会走别的分支（模型给了最终答案），不该由这里判停
        val tracker = ProgressTracker(stallThreshold = 1)
        assertThat(tracker.record(emptyList())).isInstanceOf(progress)
    }

    @Test
    fun `refused calls are left to the engine, not judged here`() {
        // 被拒绝时的处理（先回灌一次、再坚持就中止）在 ChatEngine 里，这里不该抢着停
        val tracker = ProgressTracker(stallThreshold = 3)
        val denied = listOf(call("run_script", "{}", ToolStatus.DENIED))
        assertThat(tracker.record(denied)).isInstanceOf(noProgress)
    }
}
