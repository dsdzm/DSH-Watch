package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.cloud.ArtifactInfo
import com.dshwatch.domain.agent.cloud.CloudRunRecord
import com.dshwatch.domain.agent.cloud.GitHubApi
import com.dshwatch.domain.agent.cloud.GitHubException
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 云端编译：把手表做不了的事情交给 GitHub Actions。
 *
 * ## 为什么需要它
 *
 * 手表上跑得了 shell / JS / Python，但**跑不了编译** —— 没有 JDK、没有 Kotlin 编译器、
 * 没有 Android SDK，而且物理上也装不下。可模型经常需要"真的编译出东西"：
 * 编一个 apk、跑一次 gradle 测试、用 gcc 生成一个可执行文件。
 *
 * 云端把这件事变得可行，而且**比本机快得多**（服务器 CPU 强几十倍）。
 *
 * ## 代价必须说清楚
 *
 * 这条路会把**代码发到你的 GitHub 仓库**，再跑到 GitHub 的机器上。
 * 所以它默认关闭、每次都要确认，用的也必须是用户自己的仓库。
 * 这不是"数据不出设备"的路子 —— 那句话是本 App 的默认立场，而不是全部立场：
 * 用户显式开启云端、并看到确认框里的目标仓库时，选择权在他手上。
 */
object CloudTools {

    fun all(): List<AgentTool> = listOf(CloudBuildTool, CloudJobTool)

    /** 轮询间隔：Actions 起步就要十几秒，8 秒一次既及时又不会刷爆速率限制 */
    private const val POLL_INTERVAL_MS = 8_000L

    object CloudBuildTool : AgentTool {
        override val name = "cloud_build"
        override val description =
            "在**云端 GitHub Actions** 上运行一个 workflow，用来编译本地做不了的东西" +
                "（Android APK、Gradle/Maven 构建、gcc/g++、Kotlin/Java/Go/Rust 编译等）。" +
                "默认会等待运行结束并把产物下载回当前工作目录（`artifacts/`），返回结论、链接和产物路径。" +
                "用法：先用 write_file 把源码写进工作目录，再用 inputs 把命令传给模板" +
                "（模板 dsh-run.yml 接收 inputs.command；构建本项目 APK 用 dsh-build.yml）。" +
                "注意：**代码会离开设备**发到用户自己的 GitHub 仓库，所以每次调用都会请用户确认。" +
                "运行可能几分钟，期间不要重复触发；要查进度用 cloud_job。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "workflow" to Schema.str("workflow 文件名，如 dsh-run.yml；留空用设置里的默认值"),
                "ref" to Schema.str("分支名，留空用设置里的默认值（通常是 main）"),
                "inputs" to Schema.obj(
                    mapOf(
                        "command" to Schema.str(
                            "交给模板执行的命令（dsh-run.yml 用）。例如 `python main.py`、" +
                                "`gcc main.c -o main && ./main`、`npm test`"
                        ),
                    )
                ),
                "wait" to Schema.bool("是否等待运行结束（默认 true；只想触发不等就用 false）"),
                "timeout_sec" to Schema.int("等待上限秒数，留空用设置里的值", min = 30, max = 3600),
                "collect_artifacts" to Schema.bool("结束后是否下载产物到工作目录（默认 true）"),
            ),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val settings = ctx.settings()
            // 顺序有讲究：先报用户自己**能修**的问题（开关、仓库、令牌），
            // 再说环境问题。反过来的话，用户只开了一半配置时会收到一句
            // 他没发法处理的"不可用"，而真正缺的那一步反而看不到。
            if (!settings.cloudBuildEnabled) {
                return ToolResult.fail(
                    "云端编译未开启。请让用户到「设置 → 云端编译」打开开关并填好仓库与 Token" +
                        "（你也可以调用 open_screen 打开该设置页）。"
                )
            }
            val repo = settings.cloudRepo.trim()
            if (repo.isBlank() || !repo.contains('/')) {
                return ToolResult.fail(
                    "还没有配置目标仓库。请让用户在「设置 → 云端编译」里填写 `owner/repo`（例如 dsdzm/DSH-Watch）。"
                )
            }
            val api = ctx.github
                ?: return ToolResult.fail("云端编译不可用（当前环境没有 GitHub 通道）。")
            if (!api.hasToken()) {
                return ToolResult.fail(
                    "还没有配置 GitHub Token。请让用户在「设置 → 云端编译」里填入有 Actions 权限的令牌。"
                )
            }

            val workflow = args.strOf("workflow") ?: settings.cloudWorkflowFile
            val ref = args.strOf("ref") ?: settings.cloudRef
            val wait = args.boolOf("wait", default = true)
            val collect = args.boolOf("collect_artifacts", default = true)
            val timeoutSec = (args.intOf("timeout_sec") ?: settings.cloudPollTimeoutSec)
                .coerceIn(30, 3_600)
            val inputs = readInputs(args)

            return try {
                if (!api.workflowExists(repo, workflow)) {
                    return ToolResult.fail(
                        "仓库 $repo 里找不到 workflow `$workflow`。" +
                            "两个常见原因：① 模板还没安装/提交；" +
                            "② 刚提交上去，GitHub 还没索引完（等十几秒再试）。" +
                            "可在「设置 → 云端编译 → 安装 workflow 模板」里一键安装。"
                    )
                }

                val triggeredAt = System.currentTimeMillis()
                val runId = api.dispatch(repo, workflow, ref, inputs)

                // 老版本接口不返回 run id，只能按「时间窗 + 分支 + workflow_dispatch」去找
                val located = if (runId != null) {
                    runCatching { api.getRun(repo, runId) }.getOrNull()
                } else {
                    locateRun(api, repo, ref, workflow, triggeredAt)
                }

                if (located == null) {
                    return ToolResult.fail(
                        "已触发 $workflow，但没能定位到对应的运行记录。" +
                            "稍后用 cloud_job（action=status）能看到最近一次的状态。"
                    )
                }

                ctx.cloudRuns?.put(ctx.sessionDir, located)

                if (!wait) {
                    return ToolResult.ok(
                        "已在云端触发 `$workflow`（分支 $ref）。\n" +
                            formatRun(located) +
                            "\n它正在跑，稍后用 cloud_job(action=status) 查看进度，" +
                            "cloud_job(action=logs) 看日志，cloud_job(action=artifacts) 下载产物。"
                    )
                }

                val record = pollUntilSettled(api, repo, located, timeoutSec)
                ctx.cloudRuns?.put(ctx.sessionDir, record)

                if (!record.settled) {
                    return ToolResult.ok(
                        "等待 ${timeoutSec} 秒后仍未结束，已停止等待（**任务还在云端继续跑**）。\n" +
                            formatRun(record) +
                            "\n稍后用 cloud_job(action=status) 查询结果；" +
                            "如果它跑飞了，用 cloud_job(action=cancel) 取消。"
                    )
                }

                val text = StringBuilder()
                text.append(formatRun(record)).append('\n')

                if (record.conclusion != "success") {
                    val tail = runCatching { latestJobLogTail(api, repo, record.runId, 80) }.getOrNull()
                    if (!tail.isNullOrBlank()) {
                        text.append("\n--- 日志末尾 ---\n").append(tail).append('\n')
                    }
                }

                var artifactPath: String? = null
                if (collect) {
                    val result = collectArtifacts(api, repo, record, ctx)
                    if (result != null) {
                        text.append('\n').append(result.first).append('\n')
                        artifactPath = result.second
                    }
                }

                if (record.conclusion == "success") {
                    ToolResult.ok(text.toString(), artifactPath.orEmpty())
                } else {
                    ToolResult.fail(text.toString())
                }
            } catch (e: GitHubException) {
                ToolResult.fail("云端编译失败：${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("云端编译出错：${e.message ?: e::class.simpleName}")
            }
        }

        private fun readInputs(args: JsonObject): Map<String, String> {
            val obj = runCatching { args["inputs"]?.jsonObject }.getOrNull() ?: return emptyMap()
            return obj.mapNotNull { (key, value) ->
                val prim = value as? JsonPrimitive ?: return@mapNotNull null
                prim.contentOrNull?.let { key to it }
            }.toMap()
        }

        override fun summarize(args: JsonObject): String =
            (args.strOf("workflow") ?: "默认模板") + " · " + (args.strOf("ref") ?: "默认分支")
    }

    object CloudJobTool : AgentTool {
        override val name = "cloud_job"
        override val description =
            "管理云端运行：status 看状态与各步骤结论 / logs 看日志末尾 / artifacts 下载产物 / cancel 取消。" +
                "run_id 留空时用当前会话最近一次触发的运行。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "action" to Schema.str("要做什么", enum = listOf("status", "logs", "artifacts", "cancel")),
                "run_id" to Schema.str("运行 id；留空用本会话最近一次"),
                "tail_lines" to Schema.int("logs 时返回末尾多少行，默认 100，上限 500", min = 10, max = 500),
            ),
            required = listOf("action"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val action = args.strOf("action")?.lowercase()
                ?: return ToolResult.fail("缺少 action 参数，可选：status / logs / artifacts / cancel。")
            // 先校验动作，再去找 run —— 参数写错时应该立刻说清楚，
            // 而不是先报一句"找不到运行记录"把人带偏
            if (action !in ACTIONS) {
                return ToolResult.fail("不支持的 action：$action。可选：status / logs / artifacts / cancel。")
            }

            val api = ctx.github ?: return ToolResult.fail("云端编译不可用（当前环境没有 GitHub 通道）。")
            val settings = ctx.settings()
            val repo = settings.cloudRepo.trim()
            if (repo.isBlank() || !repo.contains('/')) {
                return ToolResult.fail("还没有配置目标仓库，请到「设置 → 云端编译」填写 `owner/repo`。")
            }

            val runId = args.strOf("run_id")?.trim()?.toLongOrNull()
                ?: ctx.cloudRuns?.latest(ctx.sessionDir)?.runId
                ?: return ToolResult.fail(
                    "找不到可用的运行记录。先调用 cloud_build 触发一次，" +
                        "或者用 run_id 明确指定（也可在本会话里先触发一次）。"
                )

            return try {
                when (action) {
                    "status" -> {
                        val record = api.getRun(repo, runId)
                        ctx.cloudRuns?.put(ctx.sessionDir, record)
                        val jobs = runCatching { api.listJobs(repo, runId) }.getOrDefault(emptyList())
                        ToolResult.ok(formatRun(record) + "\n" + formatJobs(jobs))
                    }

                    "logs" -> {
                        val lines = (args.intOf("tail_lines") ?: 100).coerceIn(10, 500)
                        val jobs = api.listJobs(repo, runId)
                        if (jobs.isEmpty()) {
                            ToolResult.fail("这次运行还没有 job（可能仍在排队）。稍后再试。")
                        } else {
                            val target = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.first()
                            val log = api.jobLog(repo, target.id, lines)
                            ToolResult.ok("job：${target.name}\n$log")
                        }
                    }

                    "artifacts" -> {
                        val record = ctx.cloudRuns?.findByRunId(ctx.sessionDir, runId)
                            ?: api.getRun(repo, runId)
                        val result = collectArtifacts(api, repo, record, ctx)
                        if (result == null) {
                            ToolResult.fail("这次运行没有可下载的产物（可能失败了、或者模板没有上传产物）。")
                        } else {
                            ToolResult.ok(result.first, result.second.orEmpty())
                        }
                    }

                    "cancel" -> {
                        val record = api.getRun(repo, runId)
                        if (record.settled) {
                            ToolResult.ok("这次运行已经结束（${record.conclusion}），无需取消。\n" + formatRun(record))
                        } else if (api.cancel(repo, runId)) {
                            ToolResult.ok("已请求取消运行 $runId。云端会在几秒内停止。")
                        } else {
                            ToolResult.fail("取消请求没有被接受（可能已经结束了）。用 cloud_job(action=status) 确认。")
                        }
                    }

                    else -> ToolResult.fail("不支持的 action：$action。")
                }
            } catch (e: GitHubException) {
                ToolResult.fail("查询失败：${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("查询出错：${e.message ?: e::class.simpleName}")
            }
        }

        override fun summarize(args: JsonObject): String =
            (args.strOf("action") ?: "?") + " · " + (args.strOf("run_id") ?: "最近一次")
    }

    // ------------------------------------------------------------------
    // 内部流程
    // ------------------------------------------------------------------

    /** dispatch 之后可能拿不到 run id，靠「分支 + 时间窗」找回来 */
    private suspend fun locateRun(
        api: GitHubApi,
        repo: String,
        ref: String,
        workflow: String,
        sinceMs: Long,
    ): CloudRunRecord? {
        repeat(6) { attempt ->
            val found = runCatching { api.findRun(repo, ref, sinceMs) }.getOrNull()
            if (found != null && (workflow.isBlank() || found.workflow.isBlank() ||
                    found.workflow.contains(workflow.substringBefore('.'), ignoreCase = true))
            ) {
                return found
            }
            if (attempt < 5) delay(2_000)
        }
        return null
    }

    private suspend fun pollUntilSettled(
        api: GitHubApi,
        repo: String,
        initial: CloudRunRecord,
        timeoutSec: Int,
    ): CloudRunRecord {
        var record = initial
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (!record.settled && System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            record = runCatching { api.getRun(repo, record.runId) }.getOrElse { record }
        }
        return record
    }

    private suspend fun latestJobLogTail(api: GitHubApi, repo: String, runId: Long, lines: Int): String? {
        val jobs = runCatching { api.listJobs(repo, runId) }.getOrDefault(emptyList())
        val target = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.firstOrNull() ?: return null
        return runCatching { api.jobLog(repo, target.id, lines) }.getOrNull()
    }

    /**
     * 下载并解压产物。
     *
     * 返回「给模型看的文本」和「界面产物卡片用的路径」。
     * 产物解压到当前会话的 `artifacts/<名字>/` 下 —— 和本地构建的 `dist/` 并列，
     * 用户在工作空间页能看到，也能直接导出。
     */
    private suspend fun collectArtifacts(
        api: GitHubApi,
        repo: String,
        record: CloudRunRecord,
        ctx: ToolContext,
    ): Pair<String, String?>? {
        val artifacts = api.listArtifacts(repo, record.runId)
        if (artifacts.isEmpty()) return null

        val sessionDir = File(ctx.workspace.rootDir(), ctx.sessionDir)
        val baseDir = File(sessionDir, ARTIFACT_DIR)
        val text = StringBuilder("产物：\n")
        var bestFile: String? = null
        var bestSize = -1L

        withContext(Dispatchers.IO) {
            artifacts.forEach { artifact ->
                if (artifact.expired) {
                    text.append("- ${artifact.name}（已过期，GitHub 上保留期到了）\n")
                    return@forEach
                }
                val zip = File(baseDir, "${artifact.name}.zip")
                runCatching {
                    api.downloadArtifact(repo, artifact.id, zip)
                }.onFailure { e ->
                    text.append("- ${artifact.name}：下载失败（${e.message ?: "未知原因"}）\n")
                    return@onFailure
                }

                val outDir = File(baseDir, artifact.name)
                val files = runCatching { unzip(zip, outDir) }.getOrDefault(emptyList())
                zip.delete()
                text.append("- ${artifact.name}（${files.size} 个文件，${humanSize(artifact.sizeBytes)}）")
                text.append(" → 已放到 `$ARTIFACT_DIR/${artifact.name}/`\n")

                files.filter { it.isFile }.maxByOrNull { it.length() }?.let { f ->
                    if (f.length() > bestSize) {
                        bestSize = f.length()
                        bestFile = workspaceRelative(ctx, f)
                    }
                }
            }
        }
        return text.toString() to bestFile
    }

    /** 绝对路径 → 工作空间内的相对路径（界面产物卡片要的是后者） */
    internal fun workspaceRelative(ctx: ToolContext, file: File): String = runCatching {
        ctx.workspace.rootDir().canonicalFile.toPath()
            .relativize(file.canonicalFile.toPath())
            .toString()
            .replace(File.separatorChar, '/')
    }.getOrDefault("")

    /**
     * 解压 zip。
     *
     * 目标路径做一次显式校验：虽然 Android 14 起 `ZipInputStream.getNextEntry()`
     * 已经会对 `..` / 绝对路径抛异常，但显式检查能让行为在所有版本上一致 ——
     * 这是"路径穿越"的经典入口，不能靠版本差异兜底。
     */
    internal fun unzip(zip: File, destDir: File): List<File> {
        val written = mutableListOf<File>()
        val canonicalRoot = destDir.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val target = File(destDir, entry.name)
                val canonical = target.canonicalFile
                if (!canonical.path.startsWith(canonicalRoot.path + File.separator) &&
                    canonical.path != canonicalRoot.path
                ) {
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else {
                    canonical.parentFile?.mkdirs()
                    canonical.outputStream().use { out -> zis.copyTo(out) }
                    written += canonical
                }
                zis.closeEntry()
            }
        }
        return written
    }

    internal fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    internal fun formatRun(record: CloudRunRecord): String = buildString {
        append("运行 #${record.runId}")
        if (record.workflow.isNotBlank()) append("（${record.workflow}）")
        append('\n')
        append("状态：")
        append(
            when {
                !record.settled -> "${record.status.ifBlank { "排队中" }}（未结束）"
                record.conclusion == "success" -> "成功 ✅"
                record.conclusion == "cancelled" -> "已取消"
                record.conclusion == "timed_out" -> "超时"
                else -> "失败 ❌（${record.conclusion.ifBlank { "未知原因" }}）"
            }
        )
        append('\n')
        if (record.ref.isNotBlank()) append("分支：${record.ref}\n")
        if (record.htmlUrl.isNotBlank()) append("链接：${record.htmlUrl}\n")
    }

    internal fun formatJobs(jobs: List<com.dshwatch.domain.agent.cloud.JobInfo>): String {
        if (jobs.isEmpty()) return "（没有 job 信息）"
        return buildString {
            append("步骤：\n")
            jobs.forEach { job ->
                append("- ${job.name}：${job.conclusion.ifBlank { job.status }}\n")
                job.steps.forEach { step ->
                    val mark = when (step.conclusion) {
                        "success" -> "✓"
                        "failure" -> "✗"
                        "skipped" -> "–"
                        else -> "…"
                    }
                    append("    $mark ${step.name}\n")
                }
            }
        }
    }

    internal const val ARTIFACT_DIR = "artifacts"

    private val ACTIONS = setOf("status", "logs", "artifacts", "cancel")
}
