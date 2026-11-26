package com.dshwatch.data.store

import com.dshwatch.core.Logx
import com.dshwatch.domain.roleplay.EmotionScore
import com.dshwatch.domain.roleplay.MemoryEntry
import com.dshwatch.domain.roleplay.MemorySimilarity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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
 * 也能被导出。内容按「## 时间戳」分段，最新的在最下面。
 *
 * ## 每条记忆是一个独立事件，不是一个时间段
 *
 * 1.24 之前一次压缩写一段 400 字摘要，里面混着七八件事，没法单独衰减。
 * 现在拆成一句一件，各自带强度：有人忘得快、有人记得牢，靠的就是这个粒度。
 *
 * 元数据放在标题下一行的 HTML 注释里（`<!--m h2 e0.80-->`），
 * markdown 渲染时不可见，纯文本查看时也不碍事，注释被用户删掉则退化成
 * 「按时间戳重算」—— 不会因为手改而崩。
 */
class MemoryStore(
    private val workspaceRoot: () -> File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val mutex = Mutex()

    /**
     * 记忆文件的相对路径（相对工作空间根）。
     * 空 id 时退化成共享文件，避免调用方每次都判空。
     */
    fun pathOf(cardId: String): String = "$DIR/" + (cardId.ifBlank { "_shared" }) + ".md"

    /** 原始 markdown —— 给编辑器/工作空间用，不做任何过滤 */
    suspend fun read(cardId: String): String = withContext(io) {
        mutex.withLock { readRaw(file(cardId)) }
    }

    /** 解析后的条目，按时间正序。给界面和提示词渲染用 */
    suspend fun entries(cardId: String): List<MemoryEntry> = withContext(io) {
        mutex.withLock { parse(readRaw(file(cardId))) }
    }

    /**
     * 追加一段记忆。
     *
     * 保留 1.24 之前的签名：`text` 可以是一整段多行文本。
     * 新代码请用 [appendEvents]，它才能做「同一件事就加深印象」的合并。
     *
     * [label] 是段落标题（时间戳）。传它就等于"我知道这段是什么时候的事"，
     * 不传则取当前时刻。标题原文会被完整保留 —— 单测靠它构造确定的段序，
     * 用户手写的标题也不该被规范化掉。
     */
    suspend fun append(
        cardId: String,
        text: String,
        label: String = "",
        emotion: Float = -1f,
    ): Unit = withContext(io) {
        val body = text.trim()
        if (body.isEmpty()) return@withContext
        val at = parseStamp(label) ?: clock()
        val entry = MemoryEntry(
            at = at,
            text = body,
            // <0 表示"按字面估"；写作的前情提要会显式给一个较高值，
            // 免得几百字的剧情脉络因为没人提就淡成一句「记不太清了」
            emotion = if (emotion >= 0f) emotion else EmotionScore.of(body),
            label = label,
        )
        mutex.withLock {
            val f = file(cardId)
            val existing = parse(readRaw(f))
            writeEntries(f, existing + entry)
        }
    }

    /**
     * 写入一批事件，并把「说的同一件事」合并进旧条目。
     *
     * 这就是用户说的「多次提到会加深印象」：这次对话又提到了上周那件事，
     * 不该多出一条重复记忆，而该让旧的那条 `hits + 1`、寿命变长。
     *
     * [merges] 是压缩时由模型指认的「这就是第 N 条」—— 准确，优先用。
     * 没有指认的再走本地近重复兜底（[MemorySimilarity] 阈值很高，
     * 只合并几乎一字不差的）。
     */
    suspend fun appendEvents(
        cardId: String,
        events: List<MemoryEntry>,
        merges: List<Pair<MemoryEntry, MemoryEntry>> = emptyList(),
    ): Unit = withContext(io) {
        if (events.isEmpty() && merges.isEmpty()) return@withContext
        val now = clock()
        mutex.withLock {
            val f = file(cardId)
            val existing = parse(readRaw(f)).toMutableList()
            mergeInto(existing, events, merges, now)
            writeEntries(f, existing)
        }
    }

    /**
     * 合并逻辑。
     *
     * 分三步：
     *  1. 模型指认的 `[=N]` 先落 —— 按 `at + text` 定位，找不到就退回第 2 步；
     *  2. 剩下的新事件找本地近重复（几乎一字不差），命中就强化；
     *  3. 都不是就是真新事，追加。
     */
    private fun mergeInto(
        existing: MutableList<MemoryEntry>,
        events: List<MemoryEntry>,
        merges: List<Pair<MemoryEntry, MemoryEntry>>,
        now: Long,
    ) {
        val used = HashSet<Int>()

        merges.forEach { (target, incoming) ->
            val idx = existing.indexOfFirst { it.at == target.at && it.text == target.text }
            if (idx >= 0) {
                existing[idx] = existing[idx].reinforce(incoming, now)
                used.add(idx)
            }
            // 找不到目标（期间被用户删掉了）：这条就不落，
            // 它对应的 incoming 内容会在 events 里以新事件出现（模型两边都写了）
        }

        events.forEach { raw ->
            val fresh = raw.copy(
                emotion = maxOf(raw.emotion, EmotionScore.of(raw.text)),
            ).consolidated()

            val matchAt = existing.indices
                .filter { it !in used && existing[it].at != fresh.at }
                .map { it to MemorySimilarity.score(existing[it].text, fresh.text) }
                .filter { it.second >= MemorySimilarity.THRESHOLD }
                .maxByOrNull { it.second }
                ?.first

            if (matchAt != null) {
                existing[matchAt] = existing[matchAt].reinforce(fresh, now)
                used.add(matchAt)
            } else {
                existing.add(fresh)
            }
        }
    }

    /**
     * 渲染成给提示词的一段。
     *
     * ## 分两档，而不是按保留度排序混排
     *
     * 「记得清楚」和「只剩模糊印象」是两种不同的认知状态，混在一起模型读不出
     * 区别。分开写，模型才会在提到模糊的事时用「好像……是不是……」的语气 ——
     * 这正是用户要的"像人"。
     *
     * 预算上给模糊档只留 [BLUR_SHARE]，因为模糊记忆的价值在于"有这回事"，
     * 不在于细节。
     */
    suspend fun render(cardId: String, now: Long = clock()): String = withContext(io) {
        mutex.withLock {
            val f = file(cardId)
            val raw = readRaw(f)
            val list = parse(raw)
            if (list.isNotEmpty() && needsMigration(raw)) {
                /*
                 * 旧文件升级：把「上次想起」统一推到现在。
                 *
                 * 不这么做的话，一位用了三个月、有几十条摘要的老用户，
                 * 升级当天几乎所有记忆都算「三个月没想起」——直接淡光，
                 * 体验上就是"更新完记忆全没了"。整份文档算作刚刚回忆起，
                 * 之后按各自的强度正常衰减，用户有完整的一个周期看到它们在变淡。
                 */
                val migrated = list.map { it.copy(lastHit = now) }
                writeEntries(f, migrated)
                Logx.i(TAG, "记忆文件升级到衰减模型：${migrated.size} 条从当前时刻起算")
                renderEntries(migrated, now)
            } else {
                renderEntries(list, now)
            }
        }
    }

    /** 老格式没有 v2 标记；新写的文件头一定带它 */
    private fun needsMigration(raw: String): Boolean =
        raw.isNotBlank() && !raw.contains(MIGRATION_MARK)

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

    /**
     * 忘掉一条。
     *
     * 用 `at + text` 定位而不是下标：列表是按保留度排过序的，
     * 下标和文件里的顺序对不上，删错一条比删不掉更糟。
     */
    suspend fun forget(cardId: String, at: Long, text: String): Unit = withContext(io) {
        mutex.withLock {
            val f = file(cardId)
            val list = parse(readRaw(f)).filterNot { it.at == at && it.text == text }
            writeEntries(f, list)
        }
    }

    /**
     * 手动「想起来一次」。
     *
     * 用户翻记忆文档时点一下某条，本身就是一次回忆 —— 和压缩时又提到这件事
     * 效果一样。这是最直接的"加深印象"入口，也方便用户自己纠偏：
     * 觉得该记牢的，点两下就固化了。
     */
    suspend fun touch(cardId: String, at: Long, text: String): Unit = withContext(io) {
        val now = clock()
        mutex.withLock {
            val f = file(cardId)
            val list = parse(readRaw(f)).map {
                if (it.at == at && it.text == text) it.reinforce(it, now) else it
            }
            writeEntries(f, list)
        }
    }

    /** 改写一条的正文 */
    suspend fun update(cardId: String, at: Long, text: String, newText: String): Unit =
        withContext(io) {
            val body = newText.trim()
            mutex.withLock {
                val f = file(cardId)
                val list = parse(readRaw(f)).map {
                    if (it.at == at && it.text == text) {
                        it.copy(text = body, emotion = maxOf(it.emotion, EmotionScore.of(body)))
                    } else {
                        it
                    }
                }.filter { it.text.isNotEmpty() }
                writeEntries(f, list)
            }
        }

    suspend fun exists(cardId: String): Boolean = withContext(io) {
        mutex.withLock { file(cardId).exists() }
    }

    // ---- 内部实现 ----

    private fun renderEntries(all: List<MemoryEntry>, now: Long): String {
        if (all.isEmpty()) return ""
        val alive = all.filter { !it.isFaded(now) }
        if (alive.isEmpty()) return ""

        val clear = alive.filter { !it.isBlurred(now) }
        val blurred = alive.filter { it.isBlurred(now) }

        val clearBudget = (PROMPT_BUDGET * (1 - BLUR_SHARE)).toInt()
        val blurBudget = (PROMPT_BUDGET * BLUR_SHARE).toInt()

        // 每组都从**新到旧**挑：最近的事更有用，同一档里旧的自然被挤掉
        val pickedClear = pickWithin(clear, clearBudget, now)
        val pickedBlur = pickWithin(blurred, blurBudget, now)

        return buildString {
            if (pickedClear.isNotEmpty()) {
                append("（记得很清楚）\n")
                pickedClear.sortedBy { it.at }.forEach { e ->
                    append("· ").append(shortDate(e.at, now))
                    append(e.text.trim())
                    if (e.permanent) append("（一直记着）")
                    append('\n')
                }
            }
            if (pickedBlur.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("（只剩模糊印象的）\n")
                pickedBlur.sortedBy { it.at }.forEach { e ->
                    append("· ").append(shortDate(e.at, now)).append(e.blurredText()).append('\n')
                }
            }
        }.trim()
    }

    /** 从新到旧累计，装不下就停 */
    private fun pickWithin(list: List<MemoryEntry>, budget: Int, now: Long): List<MemoryEntry> {
        val sorted = list.sortedWith(
            compareByDescending<MemoryEntry> { it.permanent }.thenByDescending { it.at }
        )
        val out = ArrayList<MemoryEntry>()
        var used = 0
        for (e in sorted) {
            val cost = e.text.length + 16
            if (used + cost > budget) {
                // 永久记忆不因为预算被挤掉：宁可超一点
                if (e.permanent) {
                    out.add(e)
                    used += cost
                    continue
                }
                break
            }
            out.add(e)
            used += cost
        }
        return out
    }

    private fun writeEntries(f: File, list: List<MemoryEntry>) {
        f.parentFile?.mkdirs()
        val now = clock()
        /*
         * 标题解析不出日期（用户手写的 "## 一些事"）时把 at 补成现在。
         *
         * 不补的话 at = 0，`now - lastHit` 是五十年，这条记忆会当场淡掉 ——
         * 而用户刚写下的东西恰恰是最该记住的。
         */
        val normalized = list.map {
            if (it.at <= 0) it.copy(at = now, lastHit = now) else it
        }
        val kept = trim(normalized, now)
        val text = buildString {
            append(header())
            kept.sortedBy { it.at }.forEach { e ->
                append("## ").append(stampOf(e)).append('\n')
                val meta = metaOf(e)
                if (meta.isNotEmpty()) append("<!--m ").append(meta).append("-->\n")
                append(e.text.trim()).append("\n\n")
            }
        }
        runCatching { f.writeText(text) }
            .onFailure { Logx.e(TAG, "写入记忆失败：${f.name}", it) }
    }

    /**
     * 文件级裁剪。
     *
     * 提示词那边有自己的一套预算（[renderEntries]），这里管的是**文件**别无限长大。
     * 优先级：永久 > 保留度高 > 时间新。真正忘了的（[MemoryEntry.isDropped]）
     * 排最后，先被清掉。
     */
    private fun trim(list: List<MemoryEntry>, now: Long): List<MemoryEntry> {
        val total = list.sumOf { it.text.length + 24 }
        if (total <= FILE_BUDGET_CHARS) return list

        val ordered = list.sortedWith(
            compareByDescending<MemoryEntry> { it.permanent }
                .thenByDescending { !it.isDropped(now) }
                .thenByDescending { it.retention(now) }
                .thenByDescending { it.at }
        )
        val kept = ArrayList<MemoryEntry>()
        var used = 0
        for (e in ordered) {
            val cost = e.text.length + 24
            if (used + cost > FILE_BUDGET_CHARS && !e.permanent) continue
            kept.add(e)
            used += cost
        }
        Logx.i(TAG, "记忆文件超预算，清理 ${list.size - kept.size} 条")
        return kept
    }

    /** 解析 markdown。认得带 `<!--m -->` 元数据的新格式，也认得纯旧格式 */
    private fun parse(content: String): List<MemoryEntry> {
        if (content.isBlank()) return emptyList()
        val out = ArrayList<MemoryEntry>()
        // 按 "## " 切段；第一段是文件头，丢掉
        val parts = content.split("\n## ")
        for (i in 1 until parts.size) {
            val body = parts[i]
            val nl = body.indexOf('\n')
            val title = if (nl < 0) body.trim() else body.substring(0, nl).trim()
            var rest = if (nl < 0) "" else body.substring(nl + 1)

            var hits = 0
            var emotion = 0f
            var lastHit: Long? = null
            var permanent = false

            // 元数据行紧跟在标题下面
            val metaMatch = META_HEAD.find(rest)
            if (metaMatch != null && metaMatch.range.first <= 2) {
                val meta = metaMatch.groupValues[1]
                parseMeta(meta).let { m ->
                    hits = m.hits
                    emotion = m.emotion
                    lastHit = m.lastHit
                    permanent = m.permanent
                }
                rest = rest.substring(metaMatch.range.last + 1)
            }

            val text = rest.trim().let { t ->
                // 旧版压缩写过 "### 对话摘要（名字）" 这类小标题，剥掉它，
                // 否则卡片上第一行全是标题。只认清这几个已知前缀，
                // 用户自己写的小标题要留着
                if (LEGACY_TITLES.any { t.startsWith(it) }) {
                    val nl2 = t.indexOf('\n')
                    if (nl2 > 0) t.substring(nl2 + 1).trim() else t
                } else {
                    t
                }
            }
            if (text.isEmpty()) continue
            val at = parseStamp(title) ?: 0L
            out.add(
                MemoryEntry(
                    at = at,
                    text = text,
                    hits = hits,
                    emotion = if (emotion > 0f) emotion else EmotionScore.of(text),
                    lastHit = lastHit ?: at,
                    permanent = permanent,
                    label = title,
                )
            )
        }
        return out
    }

    private data class Meta(
        val hits: Int = 0,
        val emotion: Float = 0f,
        val lastHit: Long? = null,
        val permanent: Boolean = false,
    )

    private fun parseMeta(s: String): Meta {
        var hits = 0
        var emotion = 0f
        var lastHit: Long? = null
        var permanent = false
        s.trim().split(' ').forEach { token ->
            when {
                token.startsWith("h") -> token.drop(1).toIntOrNull()?.let { hits = it }
                token.startsWith("e") -> token.drop(1).toFloatOrNull()?.let { emotion = it }
                token.startsWith("l") -> lastHit = parseStamp(token.drop(1).replace('T', ' '))
                token.startsWith("p") -> permanent = true
            }
        }
        return Meta(hits, emotion, lastHit, permanent)
    }

    /** 只写非默认值，让文件尽量干净 */
    private fun metaOf(e: MemoryEntry): String = buildString {
        if (e.hits > 0) append("h").append(e.hits).append(' ')
        if (e.emotion >= 0.05f) {
            append("e").append(String.format(Locale.US, "%.2f", e.emotion)).append(' ')
        }
        if (e.lastHit > e.at) {
            append("l").append(stampCompact(e.lastHit)).append(' ')
        }
        if (e.permanent) append("p ")
    }.trim()

    /**
     * 段落标题。
     *
     * 用户手写过、且**写得像一个时间**的就原样保留（`## t0` 这种自定义标题
     * 会在写入时被换成真实日期 —— 保留它会让下次解析又回到"没有时间"）。
     */
    private fun stampOf(e: MemoryEntry): String =
        if (e.label.isNotBlank() && parseStamp(e.label) != null) {
            e.label
        } else {
            if (e.at > 0) stamp(e.at) else stamp()
        }

    /**
     * 给提示词用的短日期：只到日，省字。
     *
     * 判断"是否今年"要用**渲染基准时间** [now]，不能用真实当前时间 ——
     * 否则用户翻看一份旧对话时，同一年的事件会全带上年份，平白多吃几百字。
     */
    private fun shortDate(at: Long, now: Long): String {
        if (at <= 0) return ""
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val nowCal = Calendar.getInstance().apply {
            timeInMillis = if (now > 0) now else System.currentTimeMillis()
        }
        val sameYear = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        return if (sameYear) {
            "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 "
        } else {
            "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 "
        }
    }

    private fun file(cardId: String): File = File(workspaceRoot(), pathOf(cardId))

    private fun readRaw(f: File): String =
        if (!f.exists()) "" else runCatching { f.readText() }.getOrDefault("")

    private fun header(): String = buildString {
        append("# 长期记忆\n\n")
        // 迁移标记：有它就不再重算 lastHit。用户手改删掉也无妨，
        // 大不了下次把这批记忆当成"刚刚想起"，只会更持久，不会丢内容
        append(MIGRATION_MARK).append('\n')
        append("> 由 DSH-Watch 自动生成的对话摘要。可以直接编辑或删除，")
        append("下次对话会以这里的内容为准。\n")
        append("> 每段标题下面那行 `<!--m ...-->` 是记忆强度，删掉不影响使用，")
        append("只是这条会按时间重新估算。\n\n")
    }

    private fun stamp(at: Long = clock()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))

    private fun stampCompact(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).format(Date(at))

    /** 认几种常见写法；失败返回 null，调用方决定用什么兜底 */
    private fun parseStamp(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val patterns = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "MM-dd HH:mm",
            "M月d日",
        )
        for (p in patterns) {
            val fmt = SimpleDateFormat(p, Locale.getDefault()).apply { isLenient = true }
            val d = runCatching { fmt.parse(t) }.getOrNull() ?: continue
            // 只有月日的格式补上今年，否则会落到 1970
            if (p == "MM-dd HH:mm" || p == "M月d日") {
                val cal = Calendar.getInstance().apply { time = d }
                cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
                return cal.timeInMillis
            }
            return d.time
        }
        return null
    }

    companion object {
        private const val TAG = "MemoryStore"
        const val DIR = "memory"

        /** 记忆文件的上限：约 3 万字。超了按「永久 > 保留度高」清理 */
        const val FILE_BUDGET_CHARS = 30_000

        /** 进提示词的字数上限 */
        const val PROMPT_BUDGET = 6_000

        /** 其中留给「模糊印象」的比例 */
        const val BLUR_SHARE = 0.28f

        private val META_HEAD = Regex("""^[\s]*<!--m\s+(.*?)-->""")

        /** 衰减模型的版本标记，写在文件头 */
        const val MIGRATION_MARK = "<!--v2-->"

        /** 旧版压缩写的小标题前缀，解析时剥掉 */
        private val LEGACY_TITLES = listOf("### 对话摘要", "### 前情提要", "### 记忆")
    }
}
