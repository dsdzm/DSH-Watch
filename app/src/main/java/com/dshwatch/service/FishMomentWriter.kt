package com.dshwatch.service

import android.content.Context
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Logx
import com.dshwatch.domain.ResponseProbe
import com.dshwatch.domain.fish.FishMoment
import com.dshwatch.domain.fish.FishMoments
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.BuiltinCards
import com.dshwatch.domain.roleplay.Scenes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 让她每天写一条动态。
 *
 * ## 什么时候写
 *
 * 不挂在闹钟上，而是「今天第一次有人打开养鱼页 / 她主动说完一句」时补写。
 * 理由是动态的内容来自**当天真的聊过什么** —— 凌晨三点闹钟响的时候，
 * 今天还什么都没发生，写出来只能是「今天也要加油呀」那种空话。
 *
 * 判据只有一条：今天写过的条数 < [FishMoments.AUTO_PER_DAY]，且今天有话说。
 * 一天只会真的写一次，之后每次进来都是空跑（一次内存里的计数）。
 *
 * ## 为什么失败要安静
 *
 * 这是背景任务：没网、欠费、模型返回一句"抱歉我无法"——都不该在界面上
 * 留任何痕迹。用户只会发现"今天没更新"，而那是可以接受的；
 * 弹一条「动态生成失败」则完全没有意义。
 */
object FishMomentWriter {

    private const val TAG = "FishMoment"

    private val gate = Mutex()

    /**
     * 到点就写一条。
     *
     * @param force 用户手动点了「让她写一条」：允许今天多写一条，
     *   也允许没有对话素材（只凭记忆和心情写）。
     * @return 新写下的那条；没写（不需要 / 失败 / 重复）时返回 null
     */
    suspend fun writeIfDue(context: Context, force: Boolean = false): FishMoment? {
        val app = context.applicationContext as? com.dshwatch.DshWatchApp ?: return null
        val c = app.container

        if (!gate.tryLock()) {
            Logx.i(TAG, "上一次还在写，跳过")
            return null
        }
        try {
            return withContext(Dispatchers.IO) { writeLocked(c, force) }
        } finally {
            gate.unlock()
        }
    }

    private suspend fun writeLocked(c: AppContainer, force: Boolean): FishMoment? {
        val now = System.currentTimeMillis()
        val cardId = BuiltinCards.feiyu().id

        val count = runCatching { c.momentStore.countOn(now) }.getOrDefault(0)
        val cap = if (force) FishMoments.MAX_PER_DAY else FishMoments.AUTO_PER_DAY
        if (count >= cap) return null

        val settings = c.settingsRepo.current()
        /*
         * 养鱼没开启就不写。
         *
         * 动态是**后台联网生成**的东西。用户没同意用这个模式、或者明确关掉了，
         * 就不该因为"打开过养鱼页"而在某天多出一次请求与一条记录。
         * 手动催更（force）是用户当场点的，算明确同意。
         */
        if (!settings.fishEnabled && !force) {
            Logx.i(TAG, "养鱼没开启，不写动态")
            return null
        }
        val card = runCatching { c.roleCardStore.load(cardId) }.getOrNull()
            ?: BuiltinCards.feiyu()

        // 今天的对话。自动写必须有素材；手动催更允许空手写（她可以只写心情）
        val today = runCatching { todayTranscript(c, cardId, now) }.getOrDefault("")
        if (today.isBlank() && !force) {
            Logx.i(TAG, "今天还没聊过，不写")
            return null
        }

        val memory = runCatching { c.memoryStore.render(cardId) }.getOrDefault("")

        val system = buildString {
            append("你在写她自己的小号动态。这不是给谁看的帖子，是她自己记下来的一句话。\n\n")
            append("【她是谁】\n")
            append(card.persona.trim().take(700)).append('\n')
            if (card.style.isNotBlank()) {
                append("\n【她说话的样子】\n").append(card.style.trim().take(300)).append('\n')
            }
            if (memory.isNotBlank()) {
                append("\n【她记得的事】\n").append(memory.take(700)).append('\n')
            }
            if (today.isNotBlank()) {
                append("\n【今天聊到的】\n").append(today.take(1_800)).append('\n')
            }
            append(
                """

                【怎么写】
                - 只写一条，20~50 字，第一人称，像随手打下来的一句碎碎念。
                - 写今天真实发生的某一件事，或者由它引出的一个具体念头。抓一件事写，不要罗列。
                - 不总结这一天，不升华，不讲道理，不给自己打气，不写"希望明天"这类话。
                - 对方一律称"他"。不要出现"用户""对方""AI"。
                - 不用表情符号、不用话题标签、不加引号、不加序号、不换行，只输出这一条本身。
                """.trimIndent(),
            )
        }

        val raw = runCatching {
            c.engine.probe(
                ResponseProbe(
                    systemPrompt = system,
                    userText = "写今天的动态。",
                    temperature = 0.95f,
                    maxTokens = 220,
                ),
                providerId = settings.fishProviderId,
            )
        }.getOrElse {
            Logx.w(TAG, "生成失败", it)
            return null
        }

        val text = FishMoments.parse(raw, limit = 1).firstOrNull()
        if (text.isNullOrBlank()) {
            Logx.i(TAG, "模型没有给出可用的一条")
            return null
        }

        val moment = FishMoment(
            id = (now / 60_000L).toInt() * 31 + text.hashCode(),
            at = now,
            text = text,
        )
        val saved = runCatching { c.momentStore.add(moment) }.getOrNull() ?: return null
        Logx.i(TAG, "写下一条动态：${text.take(20)}…")
        return saved
    }

    /** 今天这个会话里说过的话，拍平成「他：… / 她：…」 */
    private suspend fun todayTranscript(c: AppContainer, cardId: String, now: Long): String {
        val conv = c.engine.conversation.value
            ?.takeIf { Scenes.of(it.scene) == Scenes.FISH }
        val stored = conv?.let { runCatching { c.conversationStore.load(it.id) }.getOrNull() }
            ?: runCatching { c.conversationStore.latestFor(Scenes.FISH, cardId) }
                .getOrNull()
                ?.let { runCatching { c.conversationStore.load(it.id) }.getOrNull() }
            ?: return ""

        val from = FishMoments.startOfDay(now)
        return stored.messages
            .asSequence()
            .filter { it.createdAt >= from }
            .filter { it.status == com.dshwatch.domain.model.MessageStatus.DONE }
            .mapNotNull { m ->
                val text = m.content.trim()
                if (text.isBlank()) return@mapNotNull null
                when (m.role) {
                    Role.USER -> "他：$text"
                    Role.ASSISTANT -> "她：$text"
                    else -> null
                }
            }
            .joinToString("\n")
    }
}
