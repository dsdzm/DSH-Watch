package com.dshwatch.data.store

import com.dshwatch.core.Ids
import com.dshwatch.core.Logx
import com.dshwatch.domain.roleplay.RoleCard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 角色卡 / 写作设定的仓库。
 *
 * ## 为什么不放进 AppSettings
 *
 * 一张卡的正文字段（persona / greeting / 世界书）轻松上千字，世界书再多几条就是几 KB。
 * `SettingsRepository` 是整包 JSON 读写，塞进去会让"改个字号"也重写几 KB 的卡面内容，
 * 而且在 DataStore 里越滚越大。卡是"用户资产"，和"配置"不是一个量级的东西。
 *
 * 布局与 [ConversationStore] 一致：目录下一个卡一个 JSON + 一个 index.json。
 * 这样列表页只读索引，打开某张卡才读全文。
 */
class RoleCardStore(
    private val dir: File,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val indexFile = File(dir, "index.json")
    private val mutex = Mutex()
    private var cache: MutableList<RoleCard>? = null

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** 索引里只存列表需要的字段，正文留空 —— 避免为了画一个列表把几 KB 的卡面都读进内存 */
    suspend fun index(kind: String): List<RoleCard> = withContext(io) {
        mutex.withLock { readIndexLocked().filter { it.kind == kind }.sortedByDescending { it.updatedAt } }
    }

    suspend fun load(id: String): RoleCard? = withContext(io) {
        mutex.withLock {
            readIndexLocked().firstOrNull { it.id == id }?.let { meta ->
                val f = file(id)
                if (!f.exists()) return@let meta
                runCatching { json.decodeFromString(RoleCard.serializer(), f.readText()) }
                    .getOrElse {
                        Logx.w(TAG, "角色卡 ${f.name} 解析失败，回退索引摘要", it)
                        meta
                    }
            }
        }
    }

    suspend fun save(card: RoleCard): RoleCard = withContext(io) {
        val now = System.currentTimeMillis()
        val id = card.id.ifBlank { Ids.new() }
        val stamped = card.copy(id = id, createdAt = card.createdAt.takeIf { it > 0 } ?: now, updatedAt = now)
        mutex.withLock {
            writeAtomic(file(id), json.encodeToString(RoleCard.serializer(), stamped))
            val list = readIndexLocked().toMutableList()
            val at = list.indexOfFirst { it.id == id }
            /*
             * 索引里去掉大字段（正文、开场白、世界书、插件全文），只留列表要用的信息。
             *
             * ⚠️ 世界书与插件**不能**整体清空：列表页要显示"世界书 3 条 · 插件 1 条"，
             * 清成空列表会让这个数字永远是 0。所以保留条目本身、只清掉正文。
             */
            val summary = stamped.copy(
                persona = "",
                greeting = "",
                fields = emptyList(),
                entries = stamped.entries.map { e ->
                    e.copy(
                        content = "",
                        // 关键词保留：它决定"这条算不算常驻"，列表页可以据此提示
                        keywords = if (e.alwaysOn) emptyList() else e.keywords,
                    )
                },
                plugins = stamped.plugins.map { p -> p.copy(prompt = "") },
            )
            if (at >= 0) list[at] = summary else list.add(summary)
            writeIndexLocked(list)
        }
        stamped
    }

    suspend fun delete(id: String): Unit = withContext(io) {
        mutex.withLock {
            file(id).delete()
            val list = readIndexLocked().toMutableList()
            list.removeAll { it.id == id }
            writeIndexLocked(list)
        }
    }

    suspend fun duplicate(id: String): RoleCard? = withContext(io) {
        val src = load(id) ?: return@withContext null
        save(
            src.copy(
                id = "",
                name = src.displayName + " 副本",
                builtin = false,
                createdAt = 0L,
            )
        )
    }

    /**
     * 首次启动时铺一批内置卡。
     *
     * 用"索引为空"判断而不是记一个版本号：用户把内置卡全删了之后，
     * 我们不希望下次启动又给他塞回来。
     */
    suspend fun ensureBuiltins(cards: List<RoleCard>): Unit = withContext(io) {
        val empty = mutex.withLock { readIndexLocked().isEmpty() }
        if (!empty) return@withContext
        cards.forEach { save(it.copy(builtin = true)) }
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun readIndexLocked(): MutableList<RoleCard> {
        cache?.let { return it }
        val loaded = if (indexFile.exists()) {
            runCatching {
                json.decodeFromString(ListSerializer(RoleCard.serializer()), indexFile.readText())
                    .toMutableList()
            }.getOrElse {
                Logx.w(TAG, "角色卡索引解析失败，重建为空", it)
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        cache = loaded
        return loaded
    }

    private fun writeIndexLocked(list: List<RoleCard>) {
        cache = list.toMutableList()
        writeAtomic(indexFile, json.encodeToString(ListSerializer(RoleCard.serializer()), list))
    }

    private fun writeAtomic(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }.onFailure { Logx.e(TAG, "写入 ${target.name} 失败", it) }
    }

    companion object {
        private const val TAG = "RoleCardStore"
    }
}
