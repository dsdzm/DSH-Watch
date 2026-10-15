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

    /** workflow 是否可用（必须已经在默认分支上、且被 GitHub 索引过） */
    suspend fun workflowExists(repo: String, file: String): Boolean = withContext(Dispatchers.IO) {
        val resp = call(request("GET", "/repos/$repo/actions/workflows/$file"), allowFailure = true)
        resp.code == 200
    }

    /** 找刚触发的那次运行：event=workflow_dispatch + 分支 + 时间窗 */
    suspend fun findRun(repo: String, ref: String, sinceMs: Long): CloudRunRecord? =
        withContext(Dispatchers.IO) {
            val body = call(
                request("GET", "/repos/$repo/actions/runs?event=workflow_dispatch&branch=$ref&per_page=20")
            ).body
            pickRun(body, ref, sinceMs)
        }

    suspend fun getRun(repo: String, runId: Long): CloudRunRecord = withContext(Dispatchers.IO) {
        val body = call(request("GET", "/repos/$repo/actions/runs/$runId")).body
        parseRun(body)
    }

    suspend fun listArtifacts(repo: String, runId: Long): List<ArtifactInfo> = withContext(Dispatchers.IO) {
        val body = call(request("GET", "/repos/$repo/actions/runs/$runId/artifacts")).body
        parseArtifacts(body)
    }

    suspend fun listJobs(repo: String, runId: Long): List<JobInfo> = withContext(Dispatchers.IO) {
        val body = call(request("GET", "/repos/$repo/actions/runs/$runId/jobs?filter=latest")).body
        parseJobs(body)
    }

    /** 拉某个 job 的日志。日志可能几十 MB，只取尾部 */
    suspend fun jobLog(repo: String, jobId: Long, maxLines: Int): String = withContext(Dispatchers.IO) {
        val text = call(request("GET", "/repos/$repo/actions/jobs/$jobId/logs")).body
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
                    if (!resp.isSuccessful) throw httpError(resp.code, "下载产物失败")
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
        val payload = buildJsonObject {
            put("ref", ref)
            if (inputs.isNotEmpty()) {
                putJsonObject("inputs") { inputs.forEach { (k, v) -> put(k, v) } }
            }
        }
        val resp = call(
            request("POST", "/repos/$repo/actions/workflows/$file/dispatches")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
        )
        // 204 无 body 是正常返回；有 body 时里面带 workflow_run_id
        resp.body.takeIf { it.isNotBlank() }
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
        val existingSha = runCatching {
            val body = call(request("GET", "/repos/$repo/contents/$path" + (branch?.let { "?ref=$it" } ?: "")))
                .body
            json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        val payload = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
            branch?.let { put("branch", it) }
            existingSha?.let { put("sha", it) }
        }
        val resp = call(
            request("PUT", "/repos/$repo/contents/$path")
                .header("Content-Type", "application/json")
                .put(payload.toString().toRequestBody(JSON_MEDIA))
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
    ): Resp {
        Http.client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && !allowFailure) throw httpError(resp.code, body)
            return Resp(resp.code, body)
        }
    }

    private fun httpError(code: Int, raw: String): GitHubException {
        val detail = raw.take(300)
        val hint = when (code) {
            401 -> "Token 无效或已过期，请到设置里重新填写 GitHub Token。"
            403 -> "Token 权限不足或触发了速率限制。触发/取消运行需要 Actions 读写权限，写文件需要 Contents 写权限。"
            404 -> "找不到目标（仓库名、workflow 文件名或 run id 不对；也可能是模板还没提交到默认分支）。"
            409 -> "状态冲突（该运行可能已经结束）。"
            422 -> "请求被拒绝（通常是分支名不存在，或 workflow 的 inputs 与模板不匹配）。"
            else -> "GitHub 返回 HTTP $code。"
        }
        return GitHubException(code, "$hint\n原始响应：$detail")
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
                ref = obj["head_branch"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                startedAt = parseEpoch(obj["run_started_at"]) ?: parseEpoch(obj["created_at"]) ?: 0L,
                updatedAt = parseEpoch(obj["updated_at"]) ?: 0L,
                status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

        /**
         * 从一页运行里挑出"刚刚触发的那一个"。
         *
         * 三个条件缺一不可：event 已经由查询参数限定、分支要一致、时间要落在窗口内。
         * 不能用 run_number 之类的序号去猜 —— 别的会话/别人同时触发时会串到别人的运行上，
         * 那会导致模型读着别人的日志下结论。
         */
        fun pickRun(body: String, ref: String, sinceMs: Long): CloudRunRecord? {
            val runs = runCatching {
                json.parseToJsonElement(body).jsonObject["workflow_runs"]?.jsonArray
            }.getOrNull() ?: return null

            // 允许一点时钟偏差（服务端时间可能比本地快/慢几秒）
            val threshold = sinceMs - CLOCK_SKEW_MS
            return runs.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val record = parseRun(obj.toString())
                if (ref.isNotBlank() && record.ref.isNotBlank() && record.ref != ref) return@mapNotNull null
                if (record.startedAt < threshold) return@mapNotNull null
                record
            }.maxByOrNull { it.startedAt }
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

        private const val CLOCK_SKEW_MS = 10_000L

        private fun parseEpoch(element: JsonElement?): Long? {
            val raw = element?.jsonPrimitive?.contentOrNull ?: return null
            // 形如 2026-10-15T12:00:00Z
            return runCatching {
                java.time.Instant.parse(raw).toEpochMilli()
            }.getOrNull()
        }
    }
}
