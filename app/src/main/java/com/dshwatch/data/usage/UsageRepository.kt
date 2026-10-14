package com.dshwatch.data.usage

import com.dshwatch.core.Logx
import com.dshwatch.domain.model.TokenUsage
import com.dshwatch.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UsageRecord(
    val ts: Long,
    val day: String,
    val providerId: String,
    val providerName: String,
    val model: String,
    val prompt: Int = 0,
    val completion: Int = 0,
    val cached: Int = 0,
    /** 按当时配置的单价算出的费用（元） */
    val cost: Double = 0.0,
    val conversationId: String = "",
    /** 该条记录是真实 usage 还是本地估算 */
    val estimated: Boolean = false,
) {
    val total: Int get() = prompt + completion
}

data class ModelUsage(
    val model: String,
    val providerName: String,
    val prompt: Int,
    val completion: Int,
    val cached: Int,
    val cost: Double,
    val requests: Int,
) {
    val total: Int get() = prompt + completion
}

data class DayUsage(
    val day: String,
    val prompt: Int,
    val completion: Int,
    val cost: Double,
    val requests: Int,
) {
    val total: Int get() = prompt + completion
}

data class UsageAggregate(
    val prompt: Int = 0,
    val completion: Int = 0,
    val cached: Int = 0,
    val cost: Double = 0.0,
    val requests: Int = 0,
    val byModel: List<ModelUsage> = emptyList(),
    val byDay: List<DayUsage> = emptyList(),
) {
    val total: Int get() = prompt + completion
    val isEmpty: Boolean get() = requests == 0
}

/**
 * Token 用量仓库。
 *
 * 每条记录包含：时间、供应商、模型、prompt/completion/cached token、折算费用。
 * 数据源优先用 API 返回的 usage；服务端不返回时由 [com.dshwatch.domain.TokenEstimator] 估算，
 * 并在记录上打 `estimated = true`，界面上会明确标注「估算」。
 */
class UsageRepository(
    private val dir: File,
    private val json: Json,
    /** 可注入：单测里换成 TestDispatcher */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val file = File(dir, "usage.json")
    private val mutex = Mutex()

    private val _records = MutableStateFlow<List<UsageRecord>>(emptyList())
    val records: StateFlow<List<UsageRecord>> = _records.asStateFlow()

    private var loaded = false

    suspend fun ensureLoaded() = withContext(io) {
        mutex.withLock {
            if (loaded) return@withLock
            if (!dir.exists()) dir.mkdirs()
            val list = if (file.exists()) {
                runCatching { json.decodeFromString(Serializer, file.readText()) }
                    .getOrElse {
                        Logx.e("Usage", "用量文件损坏，重建", it)
                        emptyList()
                    }
            } else emptyList()
            loaded = true
            _records.value = list
        }
    }

    suspend fun record(record: UsageRecord) = withContext(io) {
        ensureLoaded()
        mutex.withLock {
            var list = _records.value + record
            if (list.size > MAX_RECORDS) list = list.takeLast(RETAIN_RECORDS)
            _records.value = list
            persist(list)
        }
    }

    suspend fun clear() = withContext(io) {
        mutex.withLock {
            loaded = true
            _records.value = emptyList()
            persist(emptyList())
        }
    }

    private fun persist(list: List<UsageRecord>) {
        runCatching {
            val tmp = File(dir, "usage.json.tmp")
            tmp.writeText(json.encodeToString(Serializer, list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Logx.e("Usage", "写入用量失败", it) }
    }

    companion object {
        private const val MAX_RECORDS = 20_000
        private const val RETAIN_RECORDS = 10_000
        private val Serializer = ListSerializer(UsageRecord.serializer())

        fun costOf(usage: TokenUsage, priceInPerMillion: Double, priceOutPerMillion: Double): Double =
            usage.prompt / 1_000_000.0 * priceInPerMillion +
                usage.completion / 1_000_000.0 * priceOutPerMillion

        fun makeRecord(
            usage: TokenUsage,
            providerId: String,
            providerName: String,
            model: String,
            conversationId: String,
            priceInPerMillion: Double,
            priceOutPerMillion: Double,
            estimated: Boolean,
            now: Long = System.currentTimeMillis(),
        ) = UsageRecord(
            ts = now,
            day = Fmt.dayKey(now),
            providerId = providerId,
            providerName = providerName,
            model = model.ifBlank { "unknown" },
            prompt = usage.prompt,
            completion = usage.completion,
            cached = usage.cached,
            cost = costOf(usage, priceInPerMillion, priceOutPerMillion),
            conversationId = conversationId,
            estimated = estimated,
        )

        /** 纯函数聚合，方便单测 */
        fun aggregate(records: List<UsageRecord>, from: Long, to: Long): UsageAggregate {
            val slice = records.filter { it.ts in from until to }
            if (slice.isEmpty()) return UsageAggregate()
            var p = 0; var c = 0; var cached = 0; var cost = 0.0
            val modelMap = LinkedHashMap<String, ModelUsage>()
            val dayMap = LinkedHashMap<String, DayUsage>()
            for (r in slice) {
                p += r.prompt; c += r.completion; cached += r.cached; cost += r.cost
                val mk = r.model + "|" + r.providerId
                val prev = modelMap[mk]
                modelMap[mk] = if (prev == null) {
                    ModelUsage(r.model, r.providerName, r.prompt, r.completion, r.cached, r.cost, 1)
                } else {
                    prev.copy(
                        prompt = prev.prompt + r.prompt,
                        completion = prev.completion + r.completion,
                        cached = prev.cached + r.cached,
                        cost = prev.cost + r.cost,
                        requests = prev.requests + 1,
                    )
                }
                val dp = dayMap[r.day]
                dayMap[r.day] = if (dp == null) {
                    DayUsage(r.day, r.prompt, r.completion, r.cost, 1)
                } else {
                    dp.copy(
                        prompt = dp.prompt + r.prompt,
                        completion = dp.completion + r.completion,
                        cost = dp.cost + r.cost,
                        requests = dp.requests + 1,
                    )
                }
            }
            return UsageAggregate(
                prompt = p,
                completion = c,
                cached = cached,
                cost = cost,
                requests = slice.size,
                byModel = modelMap.values.sortedByDescending { it.total },
                byDay = dayMap.values.sortedBy { it.day },
            )
        }
    }
}
