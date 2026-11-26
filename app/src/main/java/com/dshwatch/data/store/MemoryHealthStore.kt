package com.dshwatch.data.store

import com.dshwatch.core.Logx
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 「上一次长期记忆到底走到哪一步」。
 *
 * ## 为什么需要它
 *
 * 用户说「聊了八十句还是没有记忆」时，我们手里没有任何可看的东西：
 * 判定没通过、请求失败、摘要为空、会话 id 丢了 —— 四种情况在界面上长得一模一样，
 * 都是一个空的记忆文档。而 `Logx.i` 在 release 包里被 R8 整段移除，
 * 连 logcat 都看不到。此前只能靠猜。
 *
 * 所以把**一次判定**的结果落盘：未压缩多少条、门槛多少、最后是失败还是写入。
 * 记忆页顶部把它翻成人话，用户自己就能看出断在哪一环，不用再来回试。
 *
 * 只留最近一次，不做历史：这个数字的用途是"现在为什么不记得"，
 * 翻十次旧记录没有意义。
 */
@Serializable
data class MemoryHealth(
    /** 这次判定的时刻 */
    val at: Long = 0L,
    /** 场景：tavern / writing / fish */
    val scene: String = "",
    /** 哪张卡的记忆 —— 酒馆有多张卡，混在一起会让人以为别的卡也没记 */
    val cardId: String = "",
    /** 未压缩的消息条数 */
    val tailCount: Int = 0,
    /** 未压缩部分的估算 token */
    val tailTokens: Int = 0,
    /** 当前生效的 token 门槛 */
    val threshold: Int = 0,
    /** 见 [SKIPPED] 等常量 */
    val outcome: String = "",
    /** 给用户看的一句话原因 */
    val detail: String = "",
    /** 本次写进记忆的条数 */
    val written: Int = 0,
    /**
     * 写入序号，越大越新。
     *
     * 这个字段是给「结果被判定覆盖」这件事收尾的：同一次整理会先写一条
     * [TRIGGERED]（正在整理），再写一条结果（写入 / 失败）。两条写入各自
     * 是一次异步 IO，谁先落盘没有保证 —— 一旦「正在整理」后落盘，
     * 记忆页就会永远停在「正在整理：这段 80 句」，而这正是用户报的那句话。
     * 落盘时比一下序号，旧的直接丢掉。
     */
    val seq: Long = 0,
) {
    companion object {
        /** 还没到门槛，什么都没做（不是错误） */
        const val SKIPPED = "skipped"

        /** 判定通过，摘要请求已发出 */
        const val TRIGGERED = "triggered"

        /** 没有打开的会话，无从取原文 */
        const val NO_SESSION = "no_session"

        /** 内容太少 / 摘要为空，没写 */
        const val EMPTY = "empty"

        /** 请求失败（网络、Key、模型名） */
        const val FAILED = "failed"

        /** 真的写进记忆了 */
        const val WRITTEN = "written"

        /**
         * 走到一半被中断。
         *
         * 用户点了「立即整理」然后又退回上一页、或者系统把进程杀了 —— 请求没了，
         * 结果也就永远不会写。不记这一条的话，磁盘上留着的是 [TRIGGERED]，
         * 于是**以后每次进记忆页都显示「正在整理」**，看起来就是彻底卡死。
         */
        const val INTERRUPTED = "interrupted"

        /**
         * 「正在整理」的有效期。
         *
         * 超过这个时间还没等到结果，说明那次整理没走完 —— 正常路径最长约 65 秒
         * （见 `MemoryCompressor.MANUAL_TIMEOUT_MS`），这里留一倍余量。
         * 有它在，即使进程被直接杀掉、连中断记录都没来得及写，文案也不会骗人。
         */
        const val TRIGGER_ALIVE_MS = 150_000L

        /**
         * 口径与 [com.dshwatch.domain.roleplay.MemoryPolicy] 保持一致的解释。
         *
         * @param now 判定「正在整理」是否已经过期用的当前时刻
         */
        fun describe(h: MemoryHealth, now: Long = System.currentTimeMillis()): String =
            when (h.outcome) {
                SKIPPED -> "还没到整理的时机：这段对话有 ${h.tailCount} 句还没整理"
                TRIGGERED ->
                    if (h.at > 0L && now - h.at > TRIGGER_ALIVE_MS) {
                        "上次整理没走完（被打断或超时了），再点一次试试"
                    } else {
                        "正在整理：这段 ${h.tailCount} 句"
                    }
                NO_SESSION -> "没有正在进行的对话，没法整理"
                EMPTY -> if (h.detail.isNotBlank()) h.detail else "这次没整理出内容"
                FAILED -> "上次整理失败：${h.detail.ifBlank { "请求没成功" }}"
                INTERRUPTED -> "上次整理被打断了：${h.detail.ifBlank { "没等到结果" }}"
                WRITTEN -> "上次整理写入 ${h.written} 条"
                else -> ""
            }
    }
}

/**
 * 体检记录的落盘位置。
 *
 * 和记忆文档放在同一个目录（`memory/_health.json`），导出工作空间时一起带走 ——
 * 排查问题的时候，这个文件比记忆本身更有信息量。
 */
class MemoryHealthStore(
    private val workspaceRoot: () -> File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    /**
     * 同一条链路上「判定」和「结果」会各写一次，两者可能并发。
     * 不加锁时后完成的那次覆盖先完成的，界面上就会出现"写入 3 条"之后
     * 又跳回"正在整理"这种倒序。
     */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 序号发号器。
     *
     * 从当前时刻起跳：重启之后序号仍然比上一次大，不用额外存一个计数器。
     * 只在内存里递增，同一毫秒内的多次写入也能分出先后。
     */
    private val seq = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    /** 取下一个序号。调用方在 `launch` **之前**取，顺序就由调用顺序决定 */
    fun nextSeq(): Long = seq.incrementAndGet()

    private fun file(): File =
        File(File(workspaceRoot(), MemoryStore.DIR), "_health.json")

    suspend fun read(): MemoryHealth? = withContext(io) {
        mutex.withLock {
            val f = file()
            if (!f.exists()) return@withLock null
            runCatching { json.decodeFromString(MemoryHealth.serializer(), f.readText()) }.getOrNull()
        }
    }

    /**
     * 落盘一条体检记录。
     *
     * **序号小的直接丢弃**：并发的两次写入谁先到不一定，但语义上的先后是确定的
     * （「正在整理」在前，结果在后）。没有这道判断时，慢一步的「正在整理」
     * 会在结果之后落盘，界面就永远停在「正在整理」。
     *
     * `seq == 0`（老文件、单测直接构造）不做比较，保持原有行为。
     */
    suspend fun write(health: MemoryHealth): Unit = withContext(io) {
        mutex.withLock {
            val f = file()
            runCatching {
                if (health.seq > 0) {
                    val old = if (f.exists()) {
                        runCatching {
                            json.decodeFromString(MemoryHealth.serializer(), f.readText())
                        }.getOrNull()
                    } else {
                        null
                    }
                    if (old != null && old.seq > health.seq) return@withLock
                }
                f.parentFile?.mkdirs()
                f.writeText(json.encodeToString(MemoryHealth.serializer(), health))
            }.onFailure { Logx.w(TAG, "体检记录写入失败", it) }
        }
    }

    companion object {
        private const val TAG = "MemoryHealth"
    }
}
