package com.dshwatch.domain.agent

import com.dshwatch.core.Logx
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TodoItem(val text: String, val done: Boolean = false)

/**
 * 任务清单。
 *
 * ## 为什么值得单独做一个工具
 * 手表上的 agent 任务往往要跑好几步（读文件 → 改 → 构建 → 看报错 → 再改），
 * 而**多步任务最容易出的问题不是某一步做错，是做着做着忘了原本要干什么** ——
 * 上下文被工具输出不断挤占，模型会开始在一个局部问题上打转，
 * 或者把已经做完的事又做一遍。
 *
 * 让模型自己把计划写下来、每完成一步就更新，是最便宜也最有效的约束手段：
 * 那份清单会一直躺在上下文里（和最近的工具输出不同，它不会被裁掉），
 * 而且**用户也看得见进度**，不用盯着一堆工具卡片猜它到底在干嘛。
 *
 * ## 存储
 * 按会话目录分键，落在 `files/todos.json`。
 * 用 [state] 暴露成 StateFlow，界面直接订阅，不需要额外的回调链路。
 */
class TodoBoard(
    dir: File,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val file = File(dir, "todos.json")
    private val mutex = Mutex()

    private val _state = MutableStateFlow<Map<String, List<TodoItem>>>(emptyMap())
    val state: StateFlow<Map<String, List<TodoItem>>> = _state.asStateFlow()

    private var loaded = false

    /** 界面用：拿某个会话当前的清单（同步，不等磁盘） */
    fun peek(key: String): List<TodoItem> = _state.value[key].orEmpty()

    suspend fun get(key: String): List<TodoItem> = withContext(io) {
        mutex.withLock {
            ensureLoadedLocked()
            _state.value[key].orEmpty()
        }
    }

    suspend fun set(key: String, items: List<TodoItem>): Unit = withContext(io) {
        mutex.withLock {
            ensureLoadedLocked()
            val next = _state.value.toMutableMap()
            if (items.isEmpty()) next.remove(key) else next[key] = items
            _state.value = next
            persistLocked(next)
        }
    }

    suspend fun clear(key: String): Unit = set(key, emptyList())

    /** 会话被删除时顺手清掉，避免这份账越积越大 */
    suspend fun clearAll(): Unit = withContext(io) {
        mutex.withLock {
            _state.value = emptyMap()
            persistLocked(emptyMap())
        }
    }

    // ---------------- 内部 ----------------

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(TodoItem.serializer())), file.readText())
        }.onSuccess { _state.value = it }
            .onFailure { Logx.e("TodoBoard", "任务清单解析失败，忽略", it) }
    }

    private fun persistLocked(map: Map<String, List<TodoItem>>) {
        runCatching {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(
                json.encodeToString(MapSerializer(String.serializer(), ListSerializer(TodoItem.serializer())), map)
            )
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Logx.e("TodoBoard", "任务清单写入失败", it) }
    }
}
