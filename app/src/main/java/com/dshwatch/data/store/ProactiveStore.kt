package com.dshwatch.data.store

import com.dshwatch.core.Logx
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 她给自己约的一次「待会儿想跟你说句话」。
 *
 * 只记时间与一句自述（[note]），不记成品文案 —— 到点应当是**重新生成**，
 * 而不是把几小时前写好的稿子背出来。那段时间里对话已经往前走过了，
 * 照稿念必然接不上。
 */
@Serializable
data class ProactivePlan(
    /** 同时用作 AlarmManager 的 requestCode：取消时必须用同一个值 */
    val id: Int,
    val triggerAt: Long,
    /** 她当时写的理由 / 想说的话，到点会作为提示喂回去 */
    val note: String = "",
)

/**
 * 主动发言的一次性计划。
 *
 * 和 [AlarmStore] 是同一个理由：AlarmManager 查不到自己有哪些闹钟，
 * 更不能回答「这个点是她约的还是每天例行的」。所以自己留账。
 *
 * 每天例行的四个时间点**不在这里** —— 那是设置（`AppSettings.fishProactiveTimes`），
 * 用户随时可改；这里只放模型自己约的、一次性消耗掉的那些。
 */
class ProactiveStore(
    dir: File,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val file = File(dir, "fish-proactive.json")
    private val mutex = Mutex()
    private var cache: MutableList<ProactivePlan>? = null

    /** 还没到点的计划，按时间升序。顺手清掉已经过期的（比如关机期间错过的） */
    suspend fun pending(): List<ProactivePlan> = withContext(io) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val list = readLocked()
            val alive = list.filter { it.triggerAt > now }
            if (alive.size != list.size) {
                cache = alive.toMutableList()
                persistLocked(alive)
            }
            alive.sortedBy { it.triggerAt }
        }
    }

    /** 全部（含过期的），只给设置页展示用 */
    suspend fun all(): List<ProactivePlan> = withContext(io) {
        mutex.withLock { readLocked().sortedBy { it.triggerAt } }
    }

    suspend fun add(plan: ProactivePlan): Unit = withContext(io) {
        mutex.withLock {
            val list = readLocked()
            /*
             * 同一分钟内只留一条。
             *
             * 模型偶尔会连着两轮都调一次「十分钟后提醒我」，两条计划的
             * 时间只差几秒。真触发时就会在同一分钟连发两次 —— 用户看到的是
             * 「她抽风了」。合并成一条，后来的 note 覆盖前一条。
             */
            list.removeAll { kotlin.math.abs(it.triggerAt - plan.triggerAt) < 60_000L }
            list.removeAll { it.id == plan.id }
            list.add(plan)
            // 上限之外丢最早的？不 —— 丢最晚的。约得最远的那条最可能是重复登记
            while (list.size > com.dshwatch.domain.fish.FishProactive.MAX_PLANS) {
                list.removeAt(list.indices.maxByOrNull { list[it].triggerAt } ?: 0)
            }
            cache = list
            persistLocked(list)
        }
    }

    /** @return 被删掉的计划；null 表示没找到 */
    suspend fun remove(id: Int): ProactivePlan? = withContext(io) {
        mutex.withLock {
            val list = readLocked()
            val hit = list.firstOrNull { it.id == id } ?: return@withLock null
            list.removeAll { it.id == id }
            cache = list
            persistLocked(list)
            hit
        }
    }

    suspend fun removeAll(): Int = withContext(io) {
        mutex.withLock {
            val n = readLocked().size
            cache = mutableListOf()
            persistLocked(emptyList())
            n
        }
    }

    // ---------------- 内部 ----------------

    private fun readLocked(): MutableList<ProactivePlan> {
        cache?.let { return it }
        val loaded = if (file.exists()) {
            runCatching {
                json.decodeFromString(ListSerializer(ProactivePlan.serializer()), file.readText())
            }.getOrElse {
                Logx.e("ProactiveStore", "计划解析失败，重建空列表", it)
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        val copy = loaded.toMutableList()
        cache = copy
        return copy
    }

    private fun persistLocked(list: List<ProactivePlan>) {
        runCatching {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(ProactivePlan.serializer()), list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Logx.e("ProactiveStore", "写入计划失败", it) }
    }
}
