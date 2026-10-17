package com.dshwatch.domain.agent.cloud

import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/** GitHub API 返回的业务错误（带状态码，便于给出可执行的下一步） */
class GitHubException(val code: Int, message: String) : IOException(message)

data class ArtifactInfo(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean,
)

data class StepInfo(val name: String, val conclusion: String)

/**
 * 一次探测的结论。
 *
 * [message] 是**给用户看的**，失败时一定包含"下一步该改哪里"——
 * 而不是把 HTTP 状态码丢给用户自己猜。
 */
data class ProbeResult(
    val ok: Boolean,
    val message: String,
    /** 归一化之后的实际取值（探测成功时可用，便于回显） */
    val normalized: String = "",
)

data class JobInfo(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val steps: List<StepInfo>,
)

/**
 * GitHub REST 的一层薄封装，只覆盖 Actions 相关的那几个端点。
 *
 * 只走 `api.github.com`：这是实测唯一稳定的入口（`github.com` 的网页端点在部分网络下
 * 不可达，`git push` 会直接失败，而 API 一直可用）。
 *
 * 解析逻辑全部抽成纯函数放在 [GitHubApi.Companion]，
 * 这样"匹配刚触发的那个 run""取日志尾部"这类容易出错的地方能在 JVM 里用固定样本测住 ——
 * 它们恰恰是云端功能最容易悄悄失效的地方。
 */
class GitHubApi(private val tokenProvider: () -> String?) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun hasToken(): Boolean = !tokenProvider().orEmpty().isBlank()

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 仓库能不能访问。
     *
     * 单独一步的意义在于**消除 404 的二义性**：workflow 不存在、仓库名不对、
     * 令牌看不到这个仓库，这三种情况 GitHub 给的响应体**一模一样**（都是通用 `{"message":"Not Found"}`）。
     * 先确认仓库能访问，后面那个 404 就能唯一地解释成"workflow 文件名不对或还没索引"。
     */
    suspend fun probeRepo(repo: String): ProbeResult = withContext(Dispatchers.IO) {
        val slug = CloudTargets.normalizeRepo(repo)
            ?: return@withContext ProbeResult(
                false,
                "「目标仓库」要写成 owner/repo，例如 dsdzm/DSH-Watch —— " +
                    "不要带 https:// 、github.com 前缀或 .git 后缀。",
            )

        val resp = call(request("GET", "/repos/$slug"), allowFailure = true, scope = CloudErrors.Scope.REPO)
        if (resp.code != 200) {
            return@withContext ProbeResult(
                false,
                CloudErrors.explain(resp.code, resp.body, CloudErrors.Scope.REPO),
                slug,
            )
        }

        val obj = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()
        val fullName = obj?.get("full_name")?.jsonPrimitive?.contentOrNull ?: slug
        val branch = obj?.get("default_branch")?.jsonPrimitive?.contentOrNull.orEmpty()
        val canPush = obj?.get("permissions")?.jsonObject
            ?.get("push")?.jsonPrimitive?.booleanOrNull
        val permText = when (canPush) {
            true -> "可写"
            false -> "只读（安装 workflow 模板需要写权限）"
            null -> "未知"
        }
        ProbeResult(
            ok = true,
            message = "仓库 $fullName 可访问 · 默认分支 ${branch.ifBlank { "?" }} · 令牌权限 $permText",
            normalized = fullName,
        )
    }

    /** workflow 是否可用（必须已经在默认分支上、且被 GitHub 索引过） */
    suspend fun probeWorkflow(repo: String, file: String): ProbeResult = withContext(Dispatchers.IO) {
        val slug = CloudTargets.normalizeRepo(repo)
            ?: return@withContext ProbeResult(
                false,
                "「目标仓库」要写成 owner/repo，例如 dsdzm/DSH-Watch。",
            )
        val name = CloudTargets.normalizeWorkflow(file)
            ?: return@withContext ProbeResult(
                false,
                "「默认 workflow」要写文件名，例如 dsh-run.yml（不要写 .github/workflows/ 完整路径）。",
            )

        val resp = call(
            request("GET", "/repos/$slug/actions/workflows/$name"),
            allowFailure = true,
            scope = CloudErrors.Scope.WORKFLOW,
        )
        if (resp.code == 200) {
            ProbeResult(true, "$name 已就绪", name)
        } else {
            ProbeResult(false, CloudErrors.explain(resp.code, resp.body, CloudErrors.Scope.WORKFLOW), name)
        }
    }

    /**
     * 读这个 workflow 的 `workflow_dispatch.inputs` 声明了哪些键。
     *
     * 直接读仓库里的 YAML 文件（contents API），而不是从 Actions 元数据取 ——
     * GitHub 的 workflow 元数据里**不含 inputs 定义**，只有文件里才有。
     *
     * 返回 `null` 表示读不到（文件太大 / 权限不足 / YAML 看不懂），调用方按"不限制"处理。
     */
    suspend fun workflowInputs(
        repo: String,
        file: String,
        ref: String,
    ): Set<String>? = withContext(Dispatchers.IO) {
        val slug = CloudTargets.normalizeRepo(repo) ?: return@withContext null
        val name = CloudTargets.normalizeWorkflow(file) ?: return@withContext null
        val resp = call(
            request("GET", "/repos/$slug/contents/.github/workflows/$name?ref=$ref"),
            allowFailure = true,
            scope = CloudErrors.Scope.WORKFLOW,
        )
        if (resp.code != 200) return@withContext null
        val encoded = runCatching {
            json.parseToJsonElement(resp.body).jsonObject["content"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
        // contents API 的 base64 带换行，必须用 MIME 解码器
        val yaml = runCatching {
            String(Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return@withContext null
        CloudTargets.parseDispatchInputs(yaml)
    }

    /** 只要一个布尔值时的便捷包装（内部同样会归一化字段） */
    suspend fun workflowExists(repo: String, file: String): Boolean = probeWorkflow(repo, file).ok

    /**
     * 找刚触发的那次运行。
     *
     * 用 **workflow 专属的 runs 端点**（`/actions/workflows/{file}/runs`），
     * 而不是仓库级的 `/actions/runs`。后者返回所有 workflow 的运行，
     * 得在客户端再按名字筛一遍 —— 而"文件名"与"显示名"经常不一样
     * （本项目就是：文件叫 `dsh-run.yml`，显示名叫 `DSH Run`），
     * 用文件名去 contains 显示名，一次也匹配不上。
     * 专属端点自带过滤，从根上不需要这个判断。
     *
     * 定位优先用 [baselineId]（触发前的最大 run id）比较：GitHub 的 run id 全局递增，
     * 这样完全不依赖设备时钟 —— 手表的时钟未必准。
     */
    suspend fun findRun(
        repo: String,
        ref: String,
        workflowFile: String,
        baselineId: Long?,
        sinceMs: Long,
    ): CloudRunRecord? = withContext(Dispatchers.IO) {
        val slug = CloudTargets.normalizeRepo(repo) ?: return@withContext null
        val name = CloudTargets.normalizeWorkflow(workflowFile) ?: return@withContext null
        val query = buildString {
            append("/repos/$slug/actions/workflows/$name/runs?event=workflow_dispatch&per_page=20")
            if (ref.isNotBlank()) append("&branch=${URLEncoder.encode(ref, "UTF-8")}")
        }
        val body = call(request("GET", query), scope = CloudErrors.Scope.RUN).body
        pickRun(body, ref, sinceMs, baselineId)
    }

    /** 触发前的基线：该 workflow 当前最新一次运行的 id */
    suspend fun latestRunId(repo: String, workflowFile: String): Long? =
        recentRuns(repo, workflowFile, 1).firstOrNull()?.runId

    /** 该 workflow 的最近几次运行。定位失败时用来告诉用户"到底有没有新运行" */
    suspend fun recentRuns(
        repo: String,
        workflowFile: String,
        limit: Int,
    ): List<CloudRunRecord> = withContext(Dispatchers.IO) {
        val slug = CloudTargets.normalizeRepo(repo) ?: return@withContext emptyList()
        val name = CloudTargets.normalizeWorkflow(workflowFile) ?: return@withContext emptyList()
        val body = call(
            request(
                "GET",
                "/repos/$slug/actions/workflows/$name/runs?per_page=${limit.coerceIn(1, 30)}",
            ),
            scope = CloudErrors.Scope.RUN,
        ).body
        parseRuns(body)
    }

    suspend fun getRun(repo: String, runId: Long): CloudRunRecord = withContext(Dispatchers.IO) {
        val body = call(request("GET", "/repos/$repo/actions/runs/$runId"), scope = CloudErrors.Scope.RUN).body
        parseRun(body)
    }

    suspend fun listArtifacts(repo: String, runId: Long): List<ArtifactInfo> = withContext(Dispatchers.IO) {
        val body = call(
            request("GET", "/repos/$repo/actions/runs/$runId/artifacts"),
            scope = CloudErrors.Scope.RUN,
        ).body
        parseArtifacts(body)
    }

    suspend fun listJobs(repo: String, runId: Long): List<JobInfo> = withContext(Dispatchers.IO) {
        val body = call(
            request("GET", "/repos/$repo/actions/runs/$runId/jobs?filter=latest"),
            scope = CloudErrors.Scope.RUN,
        ).body
        parseJobs(body)
    }

    /** 拉某个 job 的日志。日志可能几十 MB，只取尾部 */
    suspend fun jobLog(repo: String, jobId: Long, maxLines: Int): String = withContext(Dispatchers.IO) {
        val text = call(
            request("GET", "/repos/$repo/actions/jobs/$jobId/logs"),
            scope = CloudErrors.Scope.RUN,
        ).body
        tailLines(text, maxLines)
    }

    /**
     * 下载 artifact（zip）。
     *
     * 这个端点会 302 到 Azure 的预签名地址，且**那个地址 1 分钟就过期** ——
     * 所以拿到就立刻写盘，绝不缓存 URL。
     * OkHttp 默认跟随重定向，跨主机时会自动去掉 Authorization（正确：blob 地址自带签名）。
     */
    suspend fun downloadArtifact(repo: String, artifactId: Long, dest: File): Long =
        withContext(Dispatchers.IO) {
            // 产物可能几十 MB，给一个更宽松的读超时；派生 client 共享连接池，不算新建客户端
            val client = Http.client.newBuilder()
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
            client.newCall(request("GET", "/repos/$repo/actions/artifacts/$artifactId/zip").build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) throw httpError(resp.code, "下载产物失败", CloudErrors.Scope.RUN)
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
                    dest.length()
                }
        }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 触发 workflow_dispatch。返回 run id（新版接口会直接给，老版本没有 body） */
    suspend fun dispatch(
        repo: String,
        file: String,
        ref: String,
        inputs: Map<String, String>,
    ): Long? = withContext(Dispatchers.IO) {
        val slug = CloudTargets.normalizeRepo(repo)
            ?: throw GitHubException(400, "「目标仓库」要写成 owner/repo，例如 dsdzm/DSH-Watch。")
        val name = CloudTargets.normalizeWorkflow(file)
            ?: throw GitHubException(400, "「默认 workflow」要写文件名，例如 dsh-run.yml。")

        // 422 兜底：即使调用方尽力按 workflow 的声明过滤，也可能因为
        // YAML 写法特殊而漏判。GitHub 的报错里直接列出了多余的键名，
        // 那就去掉它们重试一次 —— 比让用户对着 "Unexpected inputs provided" 猜要好。
        var current = inputs
        var retried = false
        while (true) {
            try {
                return@withContext dispatchOnce(slug, name, ref, current)
            } catch (e: GitHubException) {
                if (e.code != 422 || retried) throw e
                val rejected = CloudTargets.parseRejectedInputs(e.message.orEmpty())
                if (rejected.isEmpty() || current.keys.none { it in rejected }) throw e
                current = current.filterKeys { it !in rejected }
                retried = true
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun dispatchOnce(
        slug: String,
        name: String,
        ref: String,
        inputs: Map<String, String>,
    ): Long? {
        val payload = buildJsonObject {
            put("ref", ref)
            if (inputs.isNotEmpty()) {
                putJsonObject("inputs") { inputs.forEach { (k, v) -> put(k, v) } }
            }
        }
        val resp = call(
            request("POST", "/repos/$slug/actions/workflows/$name/dispatches")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA)),
            scope = CloudErrors.Scope.WORKFLOW,
        )
        // 204 无 body 是正常返回；有 body 时里面带 workflow_run_id
        return resp.body.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject["workflow_run_id"]?.jsonPrimitive?.longOrNull }.getOrNull() }
    }

    suspend fun cancel(repo: String, runId: Long): Boolean = withContext(Dispatchers.IO) {
        val resp = call(
            request("POST", "/repos/$repo/actions/runs/$runId/cancel").post(EMPTY_BODY),
            allowFailure = true,
        )
        resp.code == 202 || resp.code == 204
    }

    /** 往仓库写文件（用于安装 workflow 模板）。已存在时先取 sha 再更新 */
    suspend fun putFile(
        repo: String,
        path: String,
        content: String,
        message: String,
        branch: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val slug = CloudTargets.normalizeRepo(repo)
            ?: throw GitHubException(400, "「目标仓库」要写成 owner/repo，例如 dsdzm/DSH-Watch。")

        // 取现有 sha：文件不存在时这里就是 404，属于正常分支，所以要用 runCatching 吞掉
        val existingSha = runCatching {
            val refQuery = branch?.let { "?ref=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            val body = call(request("GET", "/repos/$slug/contents/$path$refQuery")).body
            json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        val payload = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
            branch?.let { put("branch", it) }
            existingSha?.let { put("sha", it) }
        }
        val resp = call(
            request("PUT", "/repos/$slug/contents/$path")
                .header("Content-Type", "application/json")
                .put(payload.toString().toRequestBody(JSON_MEDIA)),
            scope = CloudErrors.Scope.CONTENTS,
        )
        resp.code in 200..299
    }

    // ------------------------------------------------------------------
    // 底层
    // ------------------------------------------------------------------

    private data class Resp(val code: Int, val body: String)

    private fun request(method: String, path: String): Request.Builder {
        val token = tokenProvider().orEmpty()
        val builder = Request.Builder()
            .url(API + path)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (method == "GET") builder.get()
        return builder
    }

    private fun call(
        builder: Request.Builder,
        allowFailure: Boolean = false,
        scope: CloudErrors.Scope = CloudErrors.Scope.REPO,
    ): Resp {
        Http.client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && !allowFailure) throw httpError(resp.code, body, scope)
            return Resp(resp.code, body)
        }
    }

    /**
     * 把 HTTP 错误包成异常。文案全部来自 [CloudErrors] ——
     * 上一版在这里把**所有** 404 都解释成"workflow 文件名不对或模板没提交"，
     * 用户的实际故障（仓库字段填成了 URL）被这条提示带偏，重装了几次模板都没用。
     */
    private fun httpError(code: Int, raw: String, scope: CloudErrors.Scope): GitHubException {
        val detail = raw.take(300)
        return GitHubException(code, "${CloudErrors.explain(code, raw, scope)}\n原始响应：$detail")
    }

    companion object {
        const val API = "https://api.github.com"
        const val API_VERSION = "2022-11-28"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(JSON_MEDIA)

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        // ---------------- 以下为纯函数，单测直接覆盖 ----------------

        fun parseRun(body: String): CloudRunRecord {
            val obj = json.parseToJsonElement(body).jsonObject
            return CloudRunRecord(
                runId = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                repo = obj["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                workflow = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                // path 才是 workflow 的真实文件名（如 .github/workflows/dsh-run.yml）；
                // name 是显示名，两者可以完全不同，所以不能拿一个去匹配另一个
                path = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ref = obj["head_branch"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                startedAt = parseEpoch(obj["run_started_at"]) ?: parseEpoch(obj["created_at"]) ?: 0L,
                updatedAt = parseEpoch(obj["updated_at"]) ?: 0L,
                status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

        fun parseRuns(body: String): List<CloudRunRecord> {
            val array = runCatching {
                json.parseToJsonElement(body).jsonObject["workflow_runs"]?.jsonArray
            }.getOrNull() ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                parseRun(obj.toString())
            }
        }

        /**
         * 从一页运行里挑出"刚刚触发的那一个"。
         *
         * 优先用 [baselineId] 比较：GitHub 的 run id 是全局递增的，取"比基线大的最新一个"
         * 就够 —— 完全不依赖设备时钟（手表的时钟未必准，靠时间窗匹配是脆弱的）。
         * [sinceMs] 只在拿不到基线时兜底。
         *
         * 分支一致这一条始终保留：多会话或多人同时触发时，避免读到别人的运行。
         */
        fun pickRun(body: String, ref: String, sinceMs: Long, baselineId: Long?): CloudRunRecord? {
            val candidates = parseRuns(body).filter { record ->
                ref.isBlank() || record.ref.isBlank() || record.ref == ref
            }
            if (candidates.isEmpty()) return null

            if (baselineId != null) {
                return candidates.filter { it.runId > baselineId }.maxByOrNull { it.runId }
            }

            val threshold = sinceMs - FALLBACK_WINDOW_MS
            return candidates.filter { it.startedAt >= threshold }.maxByOrNull { it.startedAt }
        }

        fun parseArtifacts(body: String): List<ArtifactInfo> {
            val array = runCatching {
                json.parseToJsonElement(body).jsonObject["artifacts"]?.jsonArray
            }.getOrNull() ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                ArtifactInfo(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    sizeBytes = obj["size_in_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    expired = obj["expired"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        }

        fun parseJobs(body: String): List<JobInfo> {
            val array = runCatching {
                json.parseToJsonElement(body).jsonObject["jobs"]?.jsonArray
            }.getOrNull() ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                JobInfo(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    steps = (obj["steps"] as? JsonArray)?.mapNotNull { step ->
                        val s = step as? JsonObject ?: return@mapNotNull null
                        StepInfo(
                            name = s["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            conclusion = s["conclusion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                    }.orEmpty(),
                )
            }
        }

        /** 只留最后几行。Actions 日志开头是每个步骤的元信息，出问题的地方在末尾 */
        fun tailLines(text: String, maxLines: Int): String {
            if (maxLines <= 0) return ""
            val lines = text.lines()
            if (lines.size <= maxLines) return text
            return "…（仅显示最后 $maxLines 行，共 ${lines.size} 行）\n" +
                lines.takeLast(maxLines).joinToString("\n")
        }

        /**
         * 拿不到基线时的兜底时间窗。
         *
         * 给到 90 秒而不是几秒：设备时钟可能明显偏快，窗口太窄会把刚触发的运行滤掉
         * （表现为"明明触发了却找不到记录"）。因为还要满足分支一致 + event 已由端点限定，
         * 放宽窗口带来的误匹配风险很小。
         */
        private const val FALLBACK_WINDOW_MS = 90_000L

        private fun parseEpoch(element: JsonElement?): Long? {
            val raw = element?.jsonPrimitive?.contentOrNull ?: return null
            // 形如 2026-10-15T12:00:00Z
            return runCatching {
                java.time.Instant.parse(raw).toEpochMilli()
            }.getOrNull()
        }
    }
}
