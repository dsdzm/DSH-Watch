package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.cloud.ArtifactInfo
import com.dshwatch.domain.agent.cloud.CloudPayload
import com.dshwatch.domain.agent.cloud.CloudRunRecord
import com.dshwatch.domain.agent.cloud.CloudTargets
import com.dshwatch.domain.agent.cloud.GitHubApi
import com.dshwatch.domain.agent.cloud.GitHubException
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.agent.stringListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            "在**云端 GitHub Actions** 上运行一个 workflow，做本地做不到的事（编 APK、Gradle/Maven、" +
                "gcc/g++、Kotlin/Java/Go/Rust 编译等）。工作目录里的文件会自动打包上传，" +
                "命令里直接引用文件名即可（如 `python main.py`）。\n" +
                "**两个模板分工不同，选错会白跑一次**：\n" +
                "· `dsh-run.yml`（跑任意命令）—— 参数 `inputs.command`；环境是**基础 Ubuntu，" +
                "没有 JDK / Android SDK**；产物写到 `dsh-out/`（为空时会自动从 app/build/outputs、" +
                "build、dist、target/release 等目录收集常见产物）。\n" +
                "· `dsh-build.yml`（构建本项目 APK）—— 自带 JDK 17 + Android SDK；参数是 " +
                "`inputs.variant=debug|release`（可选 `with_python`），**它不接受 command**，传了会被拒绝。\n" +
                "所以：**要编 Android 就用 dsh-build.yml，不要用 dsh-run.yml 跑 gradle**。" +
                "返回结论、链接、日志尾部与产物路径。运行要几分钟，别重复触发；查进度用 cloud_job。" +
                "注意：代码会离开设备发到用户自己的仓库，每次都会请用户确认。"
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
                "files" to Schema.arr(
                    "要一起上传的工作目录文件（相对路径，如 main.py、src/app.py）。" +
                        "留空则自动打包整个工作目录（已排除工具自建的临时目录）。" +
                        "打包后超出云端长度上限时，用这个参数只挑必要的文件。"
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
            // 这里再兜一次归一化：设置里可能还留着旧格式（用户升级了但没重新点保存）
            val repo = CloudTargets.normalizeRepo(settings.cloudRepo)
                ?: return ToolResult.fail(
                    "「目标仓库」填写有误：`${settings.cloudRepo.ifBlank { "（空）" }}`。" +
                        "要写成 owner/repo，例如 dsdzm/DSH-Watch —— 不要带 https:// 、github.com 前缀或 .git 后缀。" +
                        "请让用户在「设置 → 执行与编译」里改好（也可以调用 open_screen 直接把他送过去）。"
                )
            val api = ctx.github
                ?: return ToolResult.fail("云端编译不可用（当前环境没有 GitHub 通道）。")
            if (!api.hasToken()) {
                return ToolResult.fail(
                    "还没有配置 GitHub Token。请让用户在「设置 → 云端编译」里填入有 Actions 权限的令牌。"
                )
            }

            val workflow = CloudTargets
                .normalizeWorkflow(args.strOf("workflow") ?: settings.cloudWorkflowFile)
                ?: return ToolResult.fail(
                    "workflow 要写文件名，例如 dsh-run.yml（不要写 .github/workflows/ 完整路径）。"
                )
            val ref = args.strOf("ref") ?: settings.cloudRef
            val wait = args.boolOf("wait", default = true)
            val collect = args.boolOf("collect_artifacts", default = true)
            val timeoutSec = (args.intOf("timeout_sec") ?: settings.cloudPollTimeoutSec)
                .coerceIn(30, 3_600)
            val inputs = readInputs(args).toMutableMap()
            val command = inputs["command"].orEmpty()

            // 先问清楚这个 workflow 到底接受哪些参数。
            //
            // 不同模板的参数完全不同：dsh-run.yml 要 command，dsh-build.yml 只认
            // variant / with_python。不看声明就一律塞 command，会得到
            // 422 `Unexpected inputs provided: ["command"]` —— 而这句话不会告诉你
            // "这个模板压根不接命令"，看起来像"分支名不对"（真机上就是这么撞的）。
            val accepted = runCatching { api.workflowInputs(repo, workflow, ref) }.getOrNull()
            val acceptsCommand = accepted == null || "command" in accepted

            if (!acceptsCommand) {
                if (command.isNotBlank()) {
                    return ToolResult.fail(
                        "workflow `$workflow` 不接受 `command` 参数" +
                            "（它接受：${accepted.orEmpty().sorted().joinToString("、").ifBlank { "无" }}）。\n" +
                            "要跑任意命令，把 workflow 换成 dsh-run.yml；" +
                            "这个模板有自己的参数，按它的 inputs 传即可 —— " +
                            "例如构建本项目 APK 用 dsh-build.yml，只需 inputs.variant=debug 或 release。"
                    )
                }
            } else {
                if (command.isBlank()) {
                    return ToolResult.fail(
                        "缺少命令。请在 inputs.command 里给出要执行的 shell 命令" +
                            "（例如 `python main.py`、`gcc main.c -o main && ./main`）。"
                    )
                }

                // 工作目录里的文件要一起带上：云端是在一个空仓库里执行命令的，
                // 手表上 write_file 写出来的文件不会自己过去 —— 真机上就踩了这一条，
                // 云端报 `python: can't open file '.../main.py'`，模型只好自己
                // 把源码 heredoc 内联进命令，还在回答里提醒用户"记住这个坑"
                val workDir = File(ctx.workspace.rootDir(), ctx.sessionDir)
                when (val payload = CloudPayload.pack(workDir, args.stringListOf("files"))) {
                    is CloudPayload.Outcome.Ok -> {
                        inputs["command"] = CloudPayload.wrap(command, payload.packed)
                    }
                    // 目录里没文件：命令照原样执行，不引入任何额外脚本
                    CloudPayload.Outcome.Nothing -> Unit
                    is CloudPayload.Outcome.TooLarge -> return ToolResult.fail(
                        "工作目录打包后超出云端能接收的长度上限" +
                            "（${payload.fileCount} 个文件、压缩前 ${payload.rawBytes / 1024} KB，" +
                            "内联后需要 ${payload.needChars} 字符，上限 ${CloudPayload.MAX_BASE64_CHARS}）。\n" +
                            "两种办法：① 用 files 参数只挑必要的文件（例如 files=[\"main.py\"]）；" +
                            "② 把关键源码内联进 command（用 heredoc 写文件再执行）。"
                    )
                    is CloudPayload.Outcome.Failed -> return ToolResult.fail(payload.message)
                }
            }

            // 只保留模板真的接受的键。多传的必须丢掉 —— GitHub 遇到未声明的 input
            // 不是"忽略"，是直接 422 拒绝整个请求。
            val finalInputs = if (accepted == null) inputs else inputs.filterKeys { it in accepted }
            val droppedKeys = inputs.keys - finalInputs.keys

            return try {
                // 分两步探：仓库 → workflow。
                // 合成一步的话这两种失败在 GitHub 那边给的响应体一模一样，
                // 只能挤出一句含糊的"找不到"，把排查带到错的方向（真机就是这么踩的）
                val repoProbe = api.probeRepo(repo)
                if (!repoProbe.ok) {
                    return ToolResult.fail(repoProbe.message)
                }
                val workflowProbe = api.probeWorkflow(repo, workflow)
                if (!workflowProbe.ok) {
                    return ToolResult.fail(
                        "仓库 $repo 的 workflow 不可用：${workflowProbe.message}" +
                            "若模板还没安装，可在「设置 → 执行与编译 → 安装 workflow 模板」里一键安装。"
                    )
                }

                // 触发前先记下基线 run id。
                // 实测：GitHub 的 dispatch 返回 204 且没有 body（不给 run id），
                // 所以"把刚触发的那次找回来"是主路径而不是兜底，必须可靠。
                // 用 id 比较而不是时间窗 —— 手表的时钟未必准。
                val baselineId = runCatching { api.latestRunId(repo, workflow) }.getOrNull()

                val triggeredAt = System.currentTimeMillis()
                val runId = api.dispatch(repo, workflow, ref, finalInputs)

                val located = if (runId != null) {
                    runCatching { api.getRun(repo, runId) }.getOrNull()
                } else {
                    locateRun(api, repo, ref, workflow, baselineId, triggeredAt)
                }

                if (located == null) {
                    return ToolResult.fail(locateFailedMessage(api, repo, workflow, baselineId))
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
                if (droppedKeys.isNotEmpty()) {
                    text.append("（已忽略该 workflow 不接受的参数：")
                        .append(droppedKeys.sorted().joinToString("、"))
                        .append("）\n")
                }

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
                    } else if (record.conclusion == "success") {
                        // "跑成功了但什么都没有"是最容易让人懵的状态：
                        // 用户看到绿勾，却在工作空间里找不到任何文件。
                        // 直接说清模板只上传 dsh-out/，并指出 Android 构建该换模板
                        text.append("\n云端运行成功，但**没有可下载的产物**。\n")
                        text.append("模板只上传 `dsh-out/` 目录（为空时会自动收集 build/dist 等常见目录）。\n")
                        text.append("如果命令本该产出文件：把它写到 `dsh-out/` 再跑一次；\n")
                        text.append("如果是要构建 Android APK：改用 `dsh-build.yml` —— ")
                        text.append("`dsh-run.yml` 的环境里没有 JDK / Android SDK，gradle 跑不起来。\n")
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
            val repo = CloudTargets.normalizeRepo(settings.cloudRepo)
                ?: return ToolResult.fail(
                    "「目标仓库」填写有误：`${settings.cloudRepo.ifBlank { "（空）" }}`。" +
                        "要写成 owner/repo，例如 dsdzm/DSH-Watch。"
                )

            // 本会话没有记录时，退回到"该 workflow 最近的一次运行"——
            // 这样即使 cloud_build 那一步定位失败了，用户仍然能接着看状态和日志，
            // 而不是只能干等（旧版本就是在这里又报一次"找不到记录"）
            val runId = args.strOf("run_id")?.trim()?.toLongOrNull()
                ?: ctx.cloudRuns?.latest(ctx.sessionDir)?.runId
                ?: runCatching { api.latestRunId(repo, settings.cloudWorkflowFile) }.getOrNull()
                ?: return ToolResult.fail(
                    "找不到可用的运行记录：本会话没有触发过，该 workflow 也没有任何历史运行。" +
                        "先用 cloud_build 触发一次，或用 run_id 明确指定。"
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

    /**
     * dispatch 之后把刚触发的那次运行找回来。
     *
     * 这里曾经有一个**必然失败**的判断：用 workflow 文件名（`dsh-run`）去
     * `contains` 运行的显示名（`DSH Run`）—— 文件名叫 `dsh-run.yml`，显示名却写作 `DSH Run`，
     * 于是永远匹配不上，用户看到的就是"触发成功但查不到记录"。
     * 现在改成查 **workflow 专属的 runs 端点**（GitHub 那边就过滤好了），
     * 客户端不再做任何名字判断。
     *
     * 重试 8 次 × 2.5 秒：实测运行在触发后约 1 秒出现，但网络抖动时留足余量。
     */
    private suspend fun locateRun(
        api: GitHubApi,
        repo: String,
        ref: String,
        workflow: String,
        baselineId: Long?,
        sinceMs: Long,
    ): CloudRunRecord? {
        repeat(8) { attempt ->
            runCatching { api.findRun(repo, ref, workflow, baselineId, sinceMs) }
                .getOrNull()
                ?.let { return it }
            if (attempt < 7) delay(2_500)
        }
        return null
    }

    /**
     * 定位失败的诊断信息。
     *
     * 旧版本这里只回一句"没能定位到对应的运行记录"，用户和模型都只能猜 ——
     * 真机上模型当场开始猜"是不是仓库/令牌/分支配错了"，而配置其实完全正常，
     * 猜的方向全错。所以这里把该 workflow 最近的运行直接列出来：
     * **「一条运行都没有」和「有几条但对不上」是两种完全不同的故障**，
     * 前者说明触发请求没被真正执行，后者说明匹配逻辑出了问题。
     */
    private suspend fun locateFailedMessage(
        api: GitHubApi,
        repo: String,
        workflow: String,
        baselineId: Long?,
    ): String {
        val recent = runCatching { api.recentRuns(repo, workflow, 5) }.getOrDefault(emptyList())
        val detail = if (recent.isEmpty()) {
            "该 workflow 下**没有任何运行记录**，说明触发请求虽然被接受，但运行没有建立。" +
                "常见原因：workflow 的 `on: workflow_dispatch` 与请求的 inputs 不匹配，" +
                "或该 workflow 被停用。"
        } else {
            buildString {
                append("该 workflow 最近的运行（触发前基线 run id = ${baselineId ?: "未知"}）：\n")
                recent.forEach { r ->
                    append("  · #${r.runId} 分支=${r.ref} 状态=${r.status}")
                    if (r.conclusion.isNotBlank()) append("/${r.conclusion}")
                    append(" ${formatTime(r.startedAt)}\n")
                }
            }
        }
        return "已触发 $workflow，但没能定位到对应的运行记录。\n$detail" +
            "可以用 cloud_job(action=status) 查看该 workflow 最近一次的状态。"
    }

    /** 手表上显示不了长日期，够用即可 */
    private fun formatTime(ms: Long): String =
        if (ms <= 0) "时间未知"
        else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

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
