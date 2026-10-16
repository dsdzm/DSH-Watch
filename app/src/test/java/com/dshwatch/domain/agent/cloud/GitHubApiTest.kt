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
        val picked = GitHubApi.pickRun(runsBody, "main", since, baselineId = null)
        assertThat(picked?.runId).isEqualTo(222)
    }

    @Test
    fun `ignores runs that started before the trigger`() {
        val since = epoch("2026-10-15T10:30:00Z")
        val picked = GitHubApi.pickRun(runsBody, "main", since, baselineId = null)
        // 11:00 那个仍在窗口内，10:00 那个被排除
        assertThat(picked?.runId).isEqualTo(222)
    }

    @Test
    fun `skips runs from another branch`() {
        val since = epoch("2026-10-15T09:00:00Z")
        val picked = GitHubApi.pickRun(runsBody, "dev", since, baselineId = null)
        assertThat(picked).isNull()
    }

    @Test
    fun `fallback window tolerates clock skew`() {
        // 拿不到基线时的兜底窗口留了 90 秒：设备时钟偏快是常态，
        // 窗口太窄会把刚触发的运行判成"太旧"，表现同样是"找不到记录"
        val since = epoch("2026-10-15T11:00:05Z")
        assertThat(GitHubApi.pickRun(runsBody, "main", since, baselineId = null)?.runId).isEqualTo(222)
    }

    @Test
    fun `returns null instead of throwing on malformed input`() {
        assertThat(GitHubApi.pickRun("not json", "main", 0, null)).isNull()
        assertThat(GitHubApi.pickRun("{}", "main", 0, null)).isNull()
    }

    @Test
    fun `baseline id picks the newer run without relying on the clock`() {
        // 手表时钟不准是常态。设备时间若比服务器慢一小时，时间窗会把刚触发的运行滤掉，
        // 而"比基线 id 大"这个判断完全不看时钟。
        val picked = GitHubApi.pickRun(runsBody, "main", sinceMs = 0L, baselineId = 111L)
        assertThat(picked?.runId).isEqualTo(222)
    }

    @Test
    fun `baseline that already covers the newest run yields nothing`() {
        // 基线就是最新那条 —— 说明我们触发的那次还没出现，调用方应当继续重试
        assertThat(GitHubApi.pickRun(runsBody, "main", 0L, baselineId = 222L)).isNull()
    }

    @Test
    fun `workflow display name and file name may differ completely`() {
        // 真机样本：workflow 文件是 dsh-run.yml，但它的显示名写作 "DSH Run"。
        // 旧实现拿文件名去 contains 显示名 —— 永远匹配不上，
        // 用户看到的就是"已触发 dsh-run.yml，但没能定位到对应的运行记录"。
        // 修法是不再做名字匹配（改查 workflow 专属端点），这条把真实字段钉住。
        val body = """
            {"workflow_runs":[{"id":38850367568,"name":"DSH Run",
             "path":".github/workflows/dsh-run.yml","head_branch":"main","status":"queued",
             "conclusion":null,"created_at":"2026-10-16T04:23:26Z",
             "run_started_at":"2026-10-16T04:23:26Z","updated_at":"2026-10-16T04:23:26Z"}]}
        """.trimIndent()

        val picked = GitHubApi.pickRun(body, "main", 0L, baselineId = 38754402826L)

        assertThat(picked).isNotNull()
        assertThat(picked!!.runId).isEqualTo(38850367568L)
        assertThat(picked.workflow).isEqualTo("DSH Run")
        // path 才是真实文件名，需要"文件"语义时用它
        assertThat(picked.path).isEqualTo(".github/workflows/dsh-run.yml")
    }

    @Test
    fun `parseRuns tolerates a missing path field`() {
        val runs = GitHubApi.parseRuns(runsBody)
        assertThat(runs).hasSize(2)
        // 样本里没有 path → 空串，而不是抛异常
        assertThat(runs.first().path).isEmpty()
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
