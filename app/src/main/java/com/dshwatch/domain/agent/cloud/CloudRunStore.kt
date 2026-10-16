package com.dshwatch.domain.agent.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一次云端（GitHub Actions）运行的记录。
 *
 * 为什么要记下来：Actions 跑一次动辄几十秒到几分钟，而手表会熄屏、用户会切走。
 * 只把 run_id 留在模型的上下文里并不稳（上下文会被裁剪），
 * 落盘之后 `cloud_job` 才能在不重新触发的前提下查状态、拉日志、下载产物。
 */
@Serializable
data class CloudRunRecord(
    val runId: Long,
    val repo: String = "",
    val workflow: String = "",
    /** workflow 的真实文件名（`.github/workflows/xxx.yml`）；与显示名 [workflow] 常常不同 */
    val path: String = "",
    val ref: String = "main",
    val htmlUrl: String = "",
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** queued / in_progress / completed */
    val status: String = "",
    /** success / failure / cancelled / timed_out / skipped（completed 之前为空） */
    val conclusion: String = "",
    /** 已下载到工作空间的产物名 */
    val artifacts: List<String> = emptyList(),
) {
    val settled: Boolean get() = status == "completed"
}

/**
 * 按会话保存云端运行记录。
 *
 * 落盘位置：`<工作空间>/../cloud/<会话目录>.json`（放在工作空间之外的 `files/cloud/`，
 * 避免这些内部状态混进用户能看到的工作空间列表里）。
 */
class CloudRunStore(
    private val dir: File,
    private val json: Json = DEFAULT_JSON,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun list(sessionDir: String): List<CloudRunRecord> = withContext(io) {
        readLocked(sessionDir)
    }

    suspend fun latest(sessionDir: String): CloudRunRecord? = withContext(io) {
        readLocked(sessionDir).maxByOrNull { it.startedAt }
    }

    suspend fun findByRunId(sessionDir: String, runId: Long): CloudRunRecord? = withContext(io) {
        readLocked(sessionDir).firstOrNull { it.runId == runId }
    }

    /** 写入或覆盖同 runId 的记录 */
    suspend fun put(sessionDir: String, record: CloudRunRecord): Unit = withContext(io) {
        val existing = readLocked(sessionDir)
            .filterNot { it.runId == record.runId } + record
        writeLocked(sessionDir, existing.sortedBy { it.startedAt }.takeLast(MAX_PER_SESSION))
    }

    suspend fun update(
        sessionDir: String,
        runId: Long,
        transform: (CloudRunRecord) -> CloudRunRecord,
    ): CloudRunRecord? = withContext(io) {
        val list = readLocked(sessionDir)
        val current = list.firstOrNull { it.runId == runId } ?: return@withContext null
        val updated = transform(current)
        writeLocked(sessionDir, list.map { if (it.runId == runId) updated else it })
        updated
    }

    private fun fileFor(sessionDir: String): File {
        val key = sessionDir.trim().trim('/').ifBlank { ROOT_KEY }
            // 会话目录形如 progress/a1b2c3，摊平成一层文件名
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .take(80)
        return File(dir, "$key.json")
    }

    private fun readLocked(sessionDir: String): List<CloudRunRecord> {
        val file = fileFor(sessionDir)
        if (!file.isFile) return emptyList()
        // 坏 JSON 一律当作空，不让一条脏记录把整个会话的云端功能弄挂
        return runCatching {
            json.decodeFromString(ListSerializerOfCloudRunRecord, file.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(sessionDir: String, list: List<CloudRunRecord>) {
        runCatching {
            dir.mkdirs()
            fileFor(sessionDir).writeText(json.encodeToString(ListSerializerOfCloudRunRecord, list))
        }
    }

    companion object {
        const val MAX_PER_SESSION = 20
        private const val ROOT_KEY = "root"

        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        private val ListSerializerOfCloudRunRecord =
            kotlinx.serialization.builtins.ListSerializer(CloudRunRecord.serializer())
    }
}
