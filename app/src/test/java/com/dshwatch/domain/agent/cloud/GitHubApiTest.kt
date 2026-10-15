package com.dshwatch.domain.agent.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * GitHub 响应解析。
 *
 * 全部是纯函数 + 固定样本：这些逻辑（匹配刚触发的 run、取日志尾部、解析产物列表）
 * 一旦错了，表现是"云端明明成功了，手表却报告失败"或者"读到别人的日志"，
 * 而这两种错都**不会抛异常**，只会在真机上一次一次地骗人。所以必须钉住。
 */
class GitHubApiTest {

    private val runsBody = """
        {"total_count":2,"workflow_runs":[
          {"id":111,"name":"DSH Run","head_branch":"main","status":"completed","conclusion":"success",
           "html_url":"https://github.com/o/r/actions/runs/111",
           "run_started_at":"2026-10-15T10:00:00Z","updated_at":"2026-10-15T10:02:00Z",
           "repository":{"full_name":"o/r"}},
          {"id":222,"name":"DSH Run","head_branch":"main","status":"queued","conclusion":null,
           "html_url":"https://github.com/o/r/actions/runs/222",
           "run_started_at":"2026-10-15T11:00:00Z","updated_at":"2026-10-15T11:00:00Z",
           "repository":{"full_name":"o/r"}}
        ]}
    """.trimIndent()

    private fun epoch(iso: String) = java.time.Instant.parse(iso).toEpochMilli()

    @Test
    fun `picks the newest run inside the time window`() {
        val since = epoch("2026-10-15T10:59:00Z")
        val picked = GitHubApi.pickRun(runsBody, "main", since)
        assertThat(picked?.runId).isEqualTo(222)
    }

    @Test
    fun `ignores runs that started before the trigger`() {
        val since = epoch("2026-10-15T10:30:00Z")
        val picked = GitHubApi.pickRun(runsBody, "main", since)
        // 11:00 那个仍在窗口内，10:00 那个被排除
        assertThat(picked?.runId).isEqualTo(222)
    }

    @Test
    fun `skips runs from another branch`() {
        val since = epoch("2026-10-15T09:00:00Z")
        val picked = GitHubApi.pickRun(runsBody, "dev", since)
        assertThat(picked).isNull()
    }

    @Test
    fun `tolerates small clock skew`() {
        // 服务端时间可能比本地快几秒，窗口必须留出余量，否则刚触发的运行会被判为"太旧"
        val since = epoch("2026-10-15T11:00:05Z")
        assertThat(GitHubApi.pickRun(runsBody, "main", since)?.runId).isEqualTo(222)
    }

    @Test
    fun `returns null instead of throwing on malformed input`() {
        assertThat(GitHubApi.pickRun("not json", "main", 0)).isNull()
        assertThat(GitHubApi.pickRun("{}", "main", 0)).isNull()
    }

    @Test
    fun `parses a single run`() {
        val record = GitHubApi.parseRun(
            """{"id":7,"name":"Build APK","head_branch":"main","status":"completed","conclusion":"failure",
                "html_url":"https://x/7","run_started_at":"2026-10-15T10:00:00Z","updated_at":"2026-10-15T10:05:00Z"}"""
        )
        assertThat(record.runId).isEqualTo(7)
        assertThat(record.conclusion).isEqualTo("failure")
        assertThat(record.settled).isTrue()
        assertThat(record.htmlUrl).isEqualTo("https://x/7")
    }

    @Test
    fun `queued run is not settled and has no conclusion`() {
        val record = GitHubApi.parseRun("""{"id":9,"status":"in_progress"}""")
        assertThat(record.settled).isFalse()
        assertThat(record.conclusion).isEmpty()
    }

    @Test
    fun `parses artifact list`() {
        val list = GitHubApi.parseArtifacts(
            """{"total_count":2,"artifacts":[
                {"id":1,"name":"apk","size_in_bytes":5300000,"expired":false},
                {"id":2,"name":"logs","size_in_bytes":1024,"expired":true}
             ]}"""
        )
        assertThat(list).hasSize(2)
        assertThat(list[0].name).isEqualTo("apk")
        assertThat(list[1].expired).isTrue()
    }

    @Test
    fun `artifact without id is dropped`() {
        assertThat(GitHubApi.parseArtifacts("""{"artifacts":[{"name":"x"}]}""")).isEmpty()
    }

    @Test
    fun `parses job steps`() {
        val jobs = GitHubApi.parseJobs(
            """{"jobs":[{"id":5,"name":"build","status":"completed","conclusion":"failure",
                "steps":[{"name":"Checkout","conclusion":"success"},{"name":"Build","conclusion":"failure"}]}]}"""
        )
        assertThat(jobs).hasSize(1)
        assertThat(jobs[0].steps.map { it.conclusion }).containsExactly("success", "failure")
    }

    @Test
    fun `tail keeps the end of a long log`() {
        val log = (1..500).joinToString("\n") { "line$it" }
        val tail = GitHubApi.tailLines(log, 3)
        assertThat(tail).contains("line500")
        assertThat(tail).doesNotContain("line1\n")
        assertThat(tail).contains("仅显示最后 3 行")
    }

    @Test
    fun `short log is returned untouched`() {
        val log = "a\nb\nc"
        assertThat(GitHubApi.tailLines(log, 10)).isEqualTo(log)
    }

    @Test
    fun `zero lines yields empty`() {
        assertThat(GitHubApi.tailLines("a\nb", 0)).isEmpty()
    }
}
