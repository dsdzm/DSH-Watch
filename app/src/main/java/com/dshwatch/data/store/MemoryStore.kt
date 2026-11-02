package com.dshwatch.data.store

import com.dshwatch.core.Logx
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长期记忆文档。
 *
 * ## 为什么是 markdown，而不是塞回对话历史
 *
 * 用户要的是「压缩上下文 → 写进本地的记忆文档 → 模糊掉以前的上下文」。
 * 记忆必须**能在文件管理器里看见**：手表上想核对"它记得对不对"，
 * 打开文件比翻聊天记录快得多；而且用户可以直接编辑它来纠偏 ——
 * 这是这类功能里最实用的一条腿。
 *
 * 文件放在工作空间内（`memory/<卡 id>.md`），所以能被「工作空间」页打开、
 * 也能被导出。内容按「## 时间戳」追加分段，最新的在最下面。
 */
class MemoryStore(
    private val workspaceRoot: () -> File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    /**
     * 记忆文件的相对路径（相对工作空间根）。
     * 空 id 时退化成共享文件，避免调用方每次都判空。
     */
    fun pathOf(cardId: String): String = "$DIR/" + (cardId.ifBlank { "_shared" }) + ".md"

    suspend fun read(cardId: String): String = withContext(io) {
        mutex.withLock {
            val f = file(cardId)
            if (!f.exists()) "" else runCatching { f.readText() }.getOrDefault("")
        }
    }

    /**
     * 追加一段记忆。
     *
     * 同一时间戳的多次追加会合并到同一个段落下 ——
     * 压缩本身是"攒够一轮再压"，同一分钟内连压两次只可能是手动触发，
     * 分开写反而让文档更难读。
     *
     * [label] 是段落标题（时间戳）。传它就等于"我知道这段是什么时候的事"，
     * 不传则取当前时刻。
     */
    suspend fun append(cardId: String, text: String, label: String = ""): Unit = withContext(io) {
        val body = text.trim()
        if (body.isEmpty()) return@withContext
        // 段落标题默认取当前时刻；压缩时调用方可以给一个更贴合内容的标题。
        // 单测靠它构造确定的段序，否则同一秒内追加会全并成一段。
        val stampKey = label.ifBlank { stamp() }
        mutex.withLock {
            val f = file(cardId)
            f.parentFile?.mkdirs()
            val existing = if (f.exists()) runCatching { f.readText() }.getOrDefault("") else header()
            val merged = if (existing.endsWith("## $stampKey\n")) {
                existing + body + "\n\n"
            } else {
                existing + "## " + stampKey + "\n" + body + "\n\n"
            }
            runCatching { f.writeText(trimToBudget(merged)) }
                .onFailure { Logx.e(TAG, "写入记忆失败：${f.name}", it) }
        }
    }

    /** 手动编辑（用户在界面里改「它记得什么」） */
    suspend fun replace(cardId: String, content: String): Unit = withContext(io) {
        mutex.withLock {
            val f = file(cardId)
            f.parentFile?.mkdirs()
            runCatching { f.writeText(content) }
                .onFailure { Logx.e(TAG, "覆盖记忆失败：${f.name}", it) }
        }
    }

    suspend fun clear(cardId: String): Unit = withContext(io) {
        mutex.withLock { runCatching { file(cardId).delete() } }
    }

    suspend fun exists(cardId: String): Boolean = withContext(io) {
        mutex.withLock { file(cardId).exists() }
    }

    /**
     * 截断到预算。
     *
     * 从**最新**往前留：记忆的价值随时间衰减，最近发生了什么比三个月前重要得多。
     * 标题行（文件头）永远保留，否则文件失去自解释性。
     */
    private fun trimToBudget(content: String): String {
        if (content.length <= BUDGET_CHARS) return content
        val sections = content.split("\n## ")
        if (sections.size <= 1) return content
        val head = sections.first()
        // 从最旧往新累积，放不下就停 —— 结果是"保留最新的若干段"
        val kept = ArrayList<String>()
        var total = head.length
        for (i in sections.indices.reversed()) {
            if (i == 0) break
            val sec = sections[i]
            if (total + sec.length + 4 > BUDGET_CHARS) break
            total += sec.length + 4
            kept.add(sec)
        }
        if (kept.isEmpty()) return head
        return buildString {
            append(head)
            kept.asReversed().forEach { append("\n## ").append(it) }
        }
    }

    private fun file(cardId: String): File = File(workspaceRoot(), pathOf(cardId))

    private fun header(): String = buildString {
        append("# 长期记忆\n\n")
        append("> 由 DSH-Watch 自动生成的对话摘要。可以直接编辑或删除，")
        append("下次对话会以这里的内容为准。\n\n")
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    companion object {
        private const val TAG = "MemoryStore"
        const val DIR = "memory"

        /** 单个记忆文件的上限：约 6 千字，超出后先丢最旧的段落 */
        const val BUDGET_CHARS = 8_000
    }
}
