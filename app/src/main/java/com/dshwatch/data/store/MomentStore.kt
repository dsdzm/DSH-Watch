package com.dshwatch.data.store

import com.dshwatch.core.Logx
import com.dshwatch.domain.fish.FishMoment
import com.dshwatch.domain.fish.FishMoments
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 她的动态（每天一到两条）。
 *
 * 和 [ProactiveStore] 同一个写法：整份列表放一个 json，读全量、写全量。
 * 一百多条、每条一行字，没有按条查询的需求 —— 上数据库反而多一层要维护的东西。
 *
 * 容量上限在写入时裁剪（[FishMoments.MAX_KEEP]），不做后台清理：
 * 陪伴类的东西突然少一截，比一直多占几十 KB 更让人在意。
 */
class MomentStore(
    dir: File,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val file = File(dir, "fish-moments.json")
    private val mutex = Mutex()
    private var cache: MutableList<FishMoment>? = null

    /** 全部，按时间**倒序**（最新在前，时间线直接照着渲染） */
    suspend fun all(): List<FishMoment> = withContext(io) {
        mutex.withLock { readLocked().sortedByDescending { it.at } }
    }

    suspend fun recent(limit: Int): List<FishMoment> = all().take(limit)

    /** 这一天已经写了几条 —— 用来判断"今天还该不该写" */
    suspend fun countOn(day: Long): Int = withContext(io) {
        val key = FishMoments.dayKey(day)
        mutex.withLock { readLocked().count { FishMoments.dayKey(it.at) == key } }
    }

    suspend fun lastAt(): Long? = withContext(io) {
        mutex.withLock { readLocked().maxOfOrNull { it.at } }
    }

    /** @return 实际写进去的那条；与已有内容完全重复时返回 null */
    suspend fun add(moment: FishMoment): FishMoment? = withContext(io) {
        mutex.withLock {
            val list = readLocked()
            // 同一天里一字不差地又写一遍：模型在没新素材时就会这样。丢掉，不留重复
            if (list.any { it.text.trim() == moment.text.trim() }) return@withLock null
            list.add(moment)
            val kept = list.sortedByDescending { it.at }.take(FishMoments.MAX_KEEP)
                .sortedBy { it.at }
                .toMutableList()
            cache = kept
            persistLocked(kept)
            moment
        }
    }

    suspend fun remove(id: Int): Boolean = withContext(io) {
        mutex.withLock {
            val list = readLocked()
            val before = list.size
            list.removeAll { it.id == id }
            if (list.size == before) return@withLock false
            cache = list
            persistLocked(list)
            true
        }
    }

    suspend fun clear() = withContext(io) {
        mutex.withLock {
            cache = mutableListOf()
            persistLocked(emptyList())
        }
    }

    // ---------------- 内部 ----------------

    private fun readLocked(): MutableList<FishMoment> {
        cache?.let { return it }
        val loaded = if (file.exists()) {
            runCatching {
                json.decodeFromString(ListSerializer(FishMoment.serializer()), file.readText())
            }.getOrElse {
                Logx.e(TAG, "动态解析失败，重建空列表", it)
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        val copy = loaded.toMutableList()
        cache = copy
        return copy
    }

    private fun persistLocked(list: List<FishMoment>) {
        runCatching {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(FishMoment.serializer()), list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Logx.e(TAG, "写入动态失败", it) }
    }

    private companion object {
        const val TAG = "MomentStore"
    }
}
