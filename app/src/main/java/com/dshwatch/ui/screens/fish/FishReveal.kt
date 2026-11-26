package com.dshwatch.ui.screens.fish

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.dshwatch.domain.LiveDelta
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.FishBubbles
import com.dshwatch.domain.roleplay.FishRhythm
import kotlinx.coroutines.delay

/**
 * 「养鱼」的节奏状态机。
 *
 * ## 为什么从页面里抽出来
 *
 * 这套逻辑一开始写在 `FishScreen` 里，和界面（气泡、输入条、顶栏）混在一起。
 * 现在手表和手机是两套完全不同的排版（M3E 卡片 vs QQ 双列气泡），
 * 但「读 → 想 → 打字 → 分条发」以及「被打断就收尾」是同一件事。
 *
 * 复制一份的代价不是多几十行，而是**两份会走偏**：调了手机上的打字速度、
 * 忘了手表那边，同一个角色在两块屏上说话节奏不一样，比不调更怪。
 *
 * ## 谁决定「一条消息显示几个气泡」
 *
 * 文字其实早就到内存里了（模型一次给完），这里只记录每条消息**已经发出去几条**。
 * 界面按 [count] 取前 n 条渲染，多出来的部分在打字的那条上逐字长出来。
 *
 * 历史消息不在这张表里 —— 重新打开会话时不该把昨天的对话再播一遍。
 */
@Stable
class FishReveal {

    /** 每条助手消息已经"发出去"几个气泡 */
    internal val revealed = mutableStateMapOf<String, Int>()

    /** 正在打字的那条消息：渲染层会为它多显示一个还没打完的气泡 */
    var typingId by mutableStateOf<String?>(null)
        internal set

    /** 打断计数：变化时，当前这条立刻收尾，不再等节奏 */
    var flushToken by mutableIntStateOf(0)
        internal set

    /**
     * 让屏幕上正在播的那条立刻收尾。
     *
     * 只放已经生成出来的部分 —— 不会凭空多话，也不会把剩下的吞掉（它还在历史里）。
     */
    fun interrupt() {
        flushToken++
    }

    /**
     * 这条消息现在该显示几个气泡。
     *
     * - 历史（不在表里）：全显示；
     * - 正在打字的那条：已发出的 + 一个正在增长的；
     * - 其余：已发出的。
     */
    fun count(id: String, total: Int): Int {
        val sent = revealed[id] ?: return total
        return if (typingId == id) minOf(sent + 1, total) else minOf(sent, total)
    }

    /**
     * 播完了。
     *
     * **从表里删掉，而不是写一个"最终条数"。**
     *
     * 写条数的前提是"还能算出对的总数"，而这里做不到：真正在流式期间，
     * 正文并不在消息对象上（`ChatMessage.content` 到 finalize 才写），
     * 效果协程在收尾时读到的往往是一份已经过期的快照 —— 算出来 0 条，
     * 于是 `count()` 把已经显出来的气泡全部藏起来。
     * 用户看到的就是「闪一下，然后没了」。
     *
     * 删掉之后这条消息回到"不在表里"的分支 —— 也就是按历史消息处理：
     * 正文有多少显示多少。这是唯一不会错的方向。
     */
    internal fun settle(id: String) {
        revealed.remove(id)
        if (typingId == id) typingId = null
    }

    /**
     * 她有没有真的说出话 —— 看"已经发出去的气泡"，而不是"消息里有没有字"。
     *
     * 流式刚开始时文字已经在 live 里了，但按节奏还一个字都没发；这时候如果
     * 撤掉开场白，屏幕上会只剩用户自己那句，中间空一大块。等她发出第一条，
     * 开场白再让位。
     */
    fun hasVoice(messages: List<ChatMessage>): Boolean = messages.any { m ->
        if (m.role != Role.ASSISTANT) return@any false
        val sent = revealed[m.id]
        if (sent != null) {
            sent > 0
        } else {
            /*
             * 不在表里：历史消息，或者刚刚 settle 掉的那条。
             *
             * 这里不再看 `status` —— settle 发生在 finalize 之前一帧时，
             * 状态仍是 STREAMING，会误判成"她还没说话"，开场白闪回来一下。
             * 正文非空就已经说明她开口了。
             */
            m.content.isNotBlank()
        }
    }
}

/**
 * 挂上节奏调度器。
 *
 * 挂在「最后一条消息的 id」上：引擎在流式开始时会先追加一条空的助手消息，
 * 这一下正好触发。历史消息不进这里（条件里的 `STREAMING`）。
 *
 * 用 `rememberUpdatedState` 读流式文本与状态 —— LaunchedEffect 的闭包是启动时
 * 快照，不这样读的话它会一直盯着最初那几个字。
 */
@Composable
fun rememberFishReveal(messages: List<ChatMessage>, live: LiveDelta): FishReveal {
    val reveal = remember { FishReveal() }

    val liveText by rememberUpdatedState(live.text)
    val liveId by rememberUpdatedState(live.messageId)
    /*
     * 消息列表的**最新**值。
     *
     * 效果协程是长跑：启动时拿到的 `messages` 只是那一刻的快照，
     * 里面那条占位消息的 `content` 永远是空串。流式结束、引擎把正文写进
     * 消息之后，若还读快照，就一个字都看不到 —— 而 `live` 此刻已经被清空，
     * 两条路同时断掉。
     */
    val latestMessages by rememberUpdatedState(messages)
    /*
     * 用消息自己的状态判断"还在生成"，而不是 streaming 这个 Flow。
     *
     * 引擎先追加一条 STREAMING 的占位消息、再切 streaming 标志，两者差一帧；
     * 拿 Flow 做启动条件会因为读到 false 直接 return，而 key 之后不再变化 ——
     * 表现就是"字在流，屏幕上一个都不出"。
     */
    val flush by rememberUpdatedState(reveal.flushToken)
    val lastMessage = messages.lastOrNull()

    LaunchedEffect(lastMessage?.id) {
        val msg = lastMessage ?: return@LaunchedEffect
        if (msg.role != Role.ASSISTANT) return@LaunchedEffect
        if (msg.status != MessageStatus.STREAMING) return@LaunchedEffect
        if (reveal.revealed.containsKey(msg.id)) return@LaunchedEffect

        val token = flush
        reveal.typingId = null
        reveal.revealed[msg.id] = 0

        fun textNow(): String {
            // 流式期间正文只在 live 里（消息对象还是空的占位）
            if (liveId == msg.id) {
                val live = liveText
                if (live.isNotBlank()) return live
            }
            // 生成结束：live 被清空，正文已经写进列表里那条消息
            val fresh = latestMessages.firstOrNull { it.id == msg.id }?.content.orEmpty()
            if (fresh.isNotBlank()) return fresh
            return msg.content
        }

        fun stillLive(): Boolean =
            latestMessages.firstOrNull { it.id == msg.id }?.status == MessageStatus.STREAMING

        try {
            // 1. 读你的消息
            val userLine = messages.lastOrNull { it.role == Role.USER }?.content.orEmpty()
            if (!pause(FishRhythm.readDelay(userLine)) { reveal.flushToken != token }) {
                return@LaunchedEffect
            }

            // 2. 组织语言。这一段屏幕上什么都没有 —— 真人在想的时候你也看不到
            if (!pause(FishRhythm.thinkDelay(textNow())) { reveal.flushToken != token }) {
                return@LaunchedEffect
            }

            // 3. 一条条打出来
            reveal.typingId = msg.id
            var sent = 0
            while (true) {
                if (reveal.flushToken != token) break
                val all = FishBubbles.split(textNow())
                if (sent < all.size) {
                    // 这一条正在"打"：渲染层会多显示一个气泡，内容随 token 增长
                    if (!pause(FishRhythm.typingDelay(all[sent])) { reveal.flushToken != token }) break
                    sent++
                    reveal.revealed[msg.id] = sent
                    if (sent < all.size || stillLive()) {
                        if (!pause(FishRhythm.bubbleGap()) { reveal.flushToken != token }) break
                    }
                } else if (!stillLive()) {
                    break
                } else {
                    // 她还在生成下一句，等它出现
                    if (!pause(90L) { reveal.flushToken != token }) break
                }
            }
        } finally {
            // 收尾：不再由协程算"应该有几种"，直接交还给消息本身
            reveal.settle(msg.id)
        }
    }

    return reveal
}

/**
 * 可打断的等待。
 *
 * `delay(2200)` 一旦开始就睡满 2.2 秒：用户中途打断，最坏要等两秒才收尾。
 * 切成 60ms 一片，取消信号最多延迟一帧就能被看见 —— 而 60ms 的精度对
 * "打字停一下"这种节奏完全够用，不会看出是分片睡的。
 *
 * @return true 表示睡满了；false 表示中途被打断
 */
internal suspend fun pause(ms: Long, cancelled: () -> Boolean): Boolean {
    var left = ms
    while (left > 0) {
        if (cancelled()) return false
        val step = minOf(60L, left)
        delay(step)
        left -= step
    }
    return !cancelled()
}

/** 时间戳间隔：超过 5 分钟才插一条居中时间，和聊天软件一致 */
internal const val TIME_GAP_MS = 5 * 60 * 1000L

/** 同一人、间隔很短 → 不重复头像 */
internal fun sameSpeaker(prev: ChatMessage?, current: ChatMessage, gap: Long): Boolean =
    prev != null && prev.role == current.role && gap in 0 until TIME_GAP_MS
