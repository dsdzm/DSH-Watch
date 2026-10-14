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

@Serializable
data class AlarmRecord(
    /** 同时用作 AlarmManager 的 requestCode —— 取消时必须用同一个值 */
    val id: Int,
    val label: String,
    val triggerAt: Long,
)

/**
 * agent 设过的闹钟记录。
 *
 * 为什么需要单独记一份：AlarmManager 本身**不提供查询接口** ——
 * 你没法问它「现在有哪些闹钟」。所以想支持 list_alarms / cancel_alarm，
 * 必须自己留一份账。记录只用于展示与取消，响铃仍然由系统触发 [com.dshwatch.service.AlarmReceiver]。
 */
class AlarmStore(
    dir: File,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val file = File(dir, "alarms.json")
    private val mutex = Mutex()
    private var cache: MutableList<AlarmRecord>? = null

    /** 未过期的闹钟，按时间升序。顺手把已经响过的清掉。 */
    suspend fun pending(): List<AlarmRecord> = withContext(io) {
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

    suspend fun add(record: AlarmRecord): Unit = withContext(io) {
        mutex.withLock {
            val list = readLocked()
            list.removeAll { it.id == record.id }
            list.add(record)
            cache = list
            persistLocked(list)
        }
    }

    /** @return 被取消的闹钟；null 表示没找到 */
    suspend fun remove(id: Int): AlarmRecord? = withContext(io) {
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

    private fun readLocked(): MutableList<AlarmRecord> {
        cache?.let { return it }
        val loaded = if (file.exists()) {
            runCatching { json.decodeFromString(ListSerializer(AlarmRecord.serializer()), file.readText()) }
                .getOrElse {
                    Logx.e("AlarmStore", "闹钟记录解析失败，重建空列表", it)
                    mutableListOf()
                }
        } else {
            mutableListOf()
        }
        val copy = loaded.toMutableList()
        cache = copy
        return copy
    }

    private fun persistLocked(list: List<AlarmRecord>) {
        runCatching {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(AlarmRecord.serializer()), list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Logx.e("AlarmStore", "写入闹钟记录失败", it) }
    }
}
