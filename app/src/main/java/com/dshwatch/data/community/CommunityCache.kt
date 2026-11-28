package com.dshwatch.data.community

import com.dshwatch.core.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 一页列表的缓存快照：`items` 是**已经加载过的所有条**，不是单页 */
@Serializable
data class CachedPage(
    /** 写入时间（毫秒），用来判断新鲜度与淘汰顺序 */
    val at: Long,
    val hasMore: Boolean,
    val items: List<CommunityCard>,
    /** 已加载到第几页（0 起），下次「加载更多」从 +1 开始 */
    val pages: Int,
)

/** 一张社区卡的全文缓存 */
@Serializable
data class CachedDetail(
    val at: Long,
    val meta: CommunityCard,
    val card: com.dshwatch.domain.roleplay.RoleCard,
    /**
     * 我打的分、赞没赞过、这张卡是不是我投的。
     *
     * 也进缓存，否则作者在 30 分钟 TTL 内点进自己的卡会看不到删除按钮 ——
     * 那是个一眼就能发现的功能缺失。旧缓存没有这两个字段，读出来是 0 / false，
     * 下次联网刷新就补上了。
     *
     * [myScore] / [liked] 是"一次性状态"：点下去就本地先写，再等服务端确认。
     * 写进缓存是为了杀进程再进来，按钮仍然是灰的，不会让人以为还能再点一次。
     */
    val myScore: Int = 0,
    val liked: Boolean = false,
    val mine: Boolean = false,
)

@Serializable
private data class Snapshot(
    val v: Int = 1,
    val pages: Map<String, CachedPage> = emptyMap(),
    val details: Map<String, CachedDetail> = emptyMap(),
)

/**
 * 社区数据的本地缓存。
 *
 * ## 为什么必须有
 *
 * 社区主机在公网，一次 `?a=list` 往返几百毫秒到两秒不等。不缓存的话，
 * 每次进社区、每次切「酒馆 / 写作 / 最热」都要重新请求 —— 界面上就是
 * 一片空白加一行「正在从社区取卡…」，手指再快也只能等着。这些数据
 * 本来就不怎么变（审核过的卡按天计更新），没有任何理由每次重拉。
 *
 * ## 两层
 *
 * - **内存**：容器里常驻，切筛选、进出页面都是 O(1) 命中，不发请求；
 * - **磁盘**：`community/cache.json`，冷启动第一帧就能画出上次的内容。
 *
 * ## 策略：先给旧的，再偷偷换新的
 *
 * TTL 内直接返回缓存、一个请求都不发；TTL 外**仍然先返回缓存**，
 * 由界面决定要不要后台静默刷新。这样永远不会出现"有数据但白屏"。
 * 用户想立刻要最新的，有显式的「刷新」按钮。
 *
 * ## 上限
 *
 * 详情里带世界书，一张卡可能几十 KB。列表最多留 [MAX_PAGES] 组筛选、
 * 详情最多留 [MAX_DETAILS] 张，超出按写入时间淘汰最旧的 —— 缓存目录
 * 长到几 MB 对 128MB 的主机是小事，对手机存储也不该无限膨胀。
 */
class CommunityCache(
    private val dir: File,
    private val json: Json,
) {
    private val mutex = Mutex()

    /** 用 LinkedHashMap 保持插入顺序，淘汰时从头丢 */
    private var pages = LinkedHashMap<String, CachedPage>()
    private var details = LinkedHashMap<Long, CachedDetail>()

    private val file: File get() = File(dir, FILE_NAME)

    private var loaded = false

    /**
     * 第一次真的要用缓存时才读盘。
     *
     * 不放在构造函数里：容器是在 Application.onCreate 里建的，那会儿读一个
     * 几百 KB 的 JSON 只会拖慢启动，而绝大多数启动根本不会进社区。
     * 挪到"用户点进社区"这一刻，几十毫秒相对一次网络往返（几百毫秒起步）
     * 完全可以忽略。
     */
    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { loadLocked() }
            .onFailure { Logx.w(TAG, "社区缓存读取失败，按空处理", it) }
    }

    // ---------------------------------------------------------------- 列表

    /** 同步读内存，Composable 首帧直接用 */
    fun page(key: String): CachedPage? {
        ensureLoaded()
        return pages[key]
    }

    suspend fun putPage(key: String, page: CachedPage) = mutex.withLock {
        ensureLoaded()
        pages.remove(key)
        pages[key] = page
        while (pages.size > MAX_PAGES) {
            val oldest = pages.keys.firstOrNull() ?: break
            pages.remove(oldest)
        }
        saveLocked()
    }

    // ---------------------------------------------------------------- 详情

    fun detail(id: Long): CachedDetail? {
        ensureLoaded()
        return details[id]
    }

    suspend fun putDetail(id: Long, detail: CachedDetail) = mutex.withLock {
        ensureLoaded()
        details.remove(id)
        details[id] = detail
        while (details.size > MAX_DETAILS) {
            val oldest = details.keys.firstOrNull() ?: break
            details.remove(oldest)
        }
        saveLocked()
    }

    /**
     * 就地改一条详情里的元信息（点赞数、下载数）。
     *
     * 不整条重拉：改完这两个数就作废缓存，等于把「别每次都 GET」的初衷还回去。
     */
    suspend fun patchDetail(id: Long, block: (CommunityCard) -> CommunityCard) = mutex.withLock {
        ensureLoaded()
        val old = details[id] ?: return@withLock
        details[id] = old.copy(at = System.currentTimeMillis(), meta = block(old.meta))
        // 列表里的同一张卡也跟着改，否则退回列表会看到旧数字
        val next = LinkedHashMap<String, CachedPage>(pages.size)
        pages.forEach { (k, v) ->
            next[k] = v.copy(items = v.items.map { if (it.id == id) block(it) else it })
        }
        pages = next
        saveLocked()
    }

    // ---------------------------------------------------------------- 其它

    /** 登录态变了、投稿过审了：列表内容可能整体不同，全部作废 */
    suspend fun clear() = mutex.withLock {
        pages.clear()
        details.clear()
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Logx.w(TAG, "社区缓存清理失败", it) }
    }

    /**
     * 只丢「我的投稿」。
     *
     * 刚投完稿，用户十有八九就是想去看看那张卡在不在队列里；
     * 但社区列表不受影响（审核通过前根本不会出现在列表里），没必要一起作废。
     */
    suspend fun dropMine() = mutex.withLock {
        ensureLoaded()
        val keys = pages.keys.filter { it.startsWith("mine|") }
        if (keys.isEmpty()) return@withLock
        keys.forEach { pages.remove(it) }
        saveLocked()
    }

    /** 数据是否还在 TTL 内 */
    fun isFresh(at: Long, ttlMillis: Long): Boolean =
        System.currentTimeMillis() - at in 0 until ttlMillis

    private fun loadLocked() {
        if (!file.exists()) return
        if (file.length() > MAX_FILE_BYTES) {
            // 状态异常（比如旧版本写坏了），直接丢掉重建，别在启动路径上解析几十 MB
            runCatching { file.delete() }
            return
        }
        val snap = json.decodeFromString(Snapshot.serializer(), file.readText())
        pages = LinkedHashMap(snap.pages)
        val loaded = LinkedHashMap<Long, CachedDetail>(snap.details.size)
        snap.details.forEach { (k, v) ->
            val id = k.toLongOrNull() ?: return@forEach
            if (id > 0) loaded[id] = v
        }
        details = loaded
    }

    private suspend fun saveLocked() = withContext(Dispatchers.IO) {
        val snap = Snapshot(
            pages = pages.toMap(),
            details = details.entries.associate { it.key.toString() to it.value },
        )
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            // 先写临时文件再改名：写到一半被杀进程，留下的是旧文件而不是半截 JSON
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(Snapshot.serializer(), snap))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Logx.w(TAG, "社区缓存写入失败", it) }
    }

    companion object {
        private const val TAG = "CommunityCache"
        private const val FILE_NAME = "cache.json"
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_PAGES = 8
        private const val MAX_DETAILS = 12

        /** 列表 5 分钟内不重复请求：社区审核是人工的，这段时间不会有新卡 */
        const val LIST_TTL = 5 * 60 * 1000L

        /** 详情 30 分钟：作者改稿不频繁，导入时本来就会强制重拉一次 */
        const val DETAIL_TTL = 30 * 60 * 1000L

        /**
         * 我的投稿 60 秒。
         *
         * 比列表短得多：审核是有人手动点的，刚过审的卡用户恨不得立刻看见。
         * 但也不能没有 —— 从详情退回来那一下不该再转一次圈。
         */
        const val MINE_TTL = 60 * 1000L

        /** 列表缓存的 key：筛选条件决定内容，排序也算在内 */
        fun listKey(kind: String?, sort: String): String = "${kind ?: "all"}|$sort"

        /** 我的投稿：不同账号内容不同，用户名要进 key */
        fun mineKey(username: String): String = "mine|${username.ifBlank { "-" }}"
    }
}
