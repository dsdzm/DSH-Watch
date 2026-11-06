package com.dshwatch.domain.roleplay

import com.dshwatch.core.Logx
import com.dshwatch.data.store.MemoryStore
import com.dshwatch.domain.ChatEngine
import com.dshwatch.domain.ResponseProbe
import com.dshwatch.domain.TokenEstimator
import com.dshwatch.domain.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 长期记忆：定期把上下文压成一段摘要写进记忆文档，然后**忘掉**前面的对话。
 *
 * ## 用户到底要什么
 *
 * 原话是「模型就会定期压缩上下文，并且将概括写进本地的记忆文档里面，
 * 然后模糊掉以前的上下文，重新开新对话（用户和模型的对话不重新开，
 * 仅仅只是重置的模型的上下文），加载人物，并读取记忆」。
 *
 * 拆成三件事：
 *  1. **对话记录不动** —— 用户往上翻还是能看到全部聊天，一条不少；
 *  2. **发给模型的上下文** 从某个下标开始（`Conversation.tavernTurn`），更早的丢掉；
 *  3. **丢掉的内容** 变成记忆文档里的一段，下一轮随系统提示一起重新注入。
 *
 * 所以「压缩」不是删除，而是**换一种更省的表示继续带着走**。
 *
 * ## 什么时候触发
 *
 * 估算 token 超过 [triggerRatio] × 模型窗口时。用估算而不是轮数，
 * 因为长回复和短回复差一个数量级；用比例而不是固定值，因为手表上
 * 用户可能接的是 8k 窗口的小模型，也可能是 128k 的。
 *
 * 压缩本身要花一次请求的钱，所以只在**一轮对话结束后**触发，且失败不影响对话。
 */
class MemoryCompressor(
    private val scope: CoroutineScope,
    private val engine: ChatEngine,
    private val memory: MemoryStore,
    private val loadCard: suspend (String) -> RoleCard?,
) {

    /**
     * 一轮结束后调用。
     *
     * 判定用 [MemoryPolicy]，输入是**未压缩的那一段**（`contextStart` 之后），
     * 不是整个会话。细节见 [MemoryPolicy] 的注释：以前用全量算，
     * 分母又默认 64k，于是永远够不到阈值，一次都没触发过。
     *
     * @param conversationId 当前会话
     * @param cardId 这张卡（记忆按卡存，不同会话用同一张卡会共享记忆）
     * @param scene tavern / writing
     * @param contextWindow 模型窗口；<=0 表示未知
     * @param force 用户手动点「立即压缩」
     */
    fun maybeCompress(
        conversationId: String,
        cardId: String,
        scene: String,
        contextWindow: Int,
        force: Boolean = false,
    ) {
        if (cardId.isBlank() || conversationId.isBlank()) return

        val tail = uncompressedTail()
        val tokens = tail.sumOf { TokenEstimator.estimatePair(it.role.wire, it.content) }
        if (!force && !MemoryPolicy.shouldCompress(tokens, tail.size, contextWindow)) return

        /*
         * 记一条 info 级的日志：这是「到底有没有触发」唯一能看见的地方。
         * 之前的实现没有这一行，用户说「从来没触发过」时，
         * 我们连"判定没通过"和"判定通过但请求失败"都分不出来。
         */
        Logx.i(
            TAG,
            "长期记忆触发：未压缩 ${tail.size} 条 / 约 $tokens token" +
                "（阈值 ${MemoryPolicy.threshold(contextWindow)}）" + if (force) " · 手动" else "",
        )

        scope.launch {
            runCatching { compress(conversationId, cardId, scene) }
                .onFailure { Logx.w(TAG, "长期记忆压缩失败，本轮跳过", it) }
        }
    }

    /** 还没被压缩进记忆的那一段（下一次请求真正会带上的内容） */
    private fun uncompressedTail(): List<ChatMessage> {
        val all = engine.snapshotMessages()
        val from = engine.contextStart().coerceIn(0, all.size)
        return all.subList(from, all.size)
    }

    /**
     * 真正的压缩。
     *
     * 1. 取当前会话里「还没被压缩过」的全部消息；
     * 2. 要求模型只输出一段第三人称摘要（`tools` 为空，走 `ResponseProbe` 这条最轻的通道）；
     * 3. 追加进记忆文档；
     * 4. 把 `tavernTurn` 推到当前末尾 —— 下次请求只带这一句之后的内容。
     */
    suspend fun compress(conversationId: String, cardId: String, scene: String) {
        val all = engine.snapshotMessages()
        val from = engine.contextStart()
        // 少于 MIN_MESSAGES 条就不值得花一次请求：摘要发出去的内容还没提示词长
        if (all.size - from < MemoryPolicy.MIN_MESSAGES) return

        val pending = all.subList(from.coerceIn(0, all.size), all.size)
        val transcript = renderTranscript(pending)
        if (transcript.isBlank()) return

        val card = loadCard(cardId)
        val summary = engine.probe(
            ResponseProbe(
                systemPrompt = TavernPrompt.compressInstruction(scene),
                userText = transcript.take(TRANSCRIPT_BUDGET),
                temperature = 0.3f,
                maxTokens = 800,
            )
        ).trim()

        if (summary.isBlank()) {
            Logx.w(TAG, "摘要为空，放弃本次压缩")
            return
        }

        // 记忆文档的段落里留一行"这是谁的对话"，用户翻文件时才认得出
        val who = card?.displayName?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        memory.append(cardId, "### 对话摘要$who\n$summary")
        engine.markContextCut()
    }

    /** 把消息列表拍平成"谁说了什么"，过滤掉工具与空消息 */
    private fun renderTranscript(messages: List<ChatMessage>): String = buildString {
        messages.forEach { m ->
            val text = m.content.trim()
            if (text.isEmpty()) return@forEach
            val who = when (m.role) {
                com.dshwatch.domain.model.Role.USER -> "用户"
                com.dshwatch.domain.model.Role.ASSISTANT -> "角色"
                else -> return@forEach
            }
            append(who).append("：").append(text).append('\n')
        }
    }

    companion object {
        private const val TAG = "MemoryCompressor"

        /** 送去压缩的原文上限：太长就只给后半段，成本可控且信息量最大 */
        private const val TRANSCRIPT_BUDGET = 12_000
    }
}
