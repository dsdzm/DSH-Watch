package com.dshwatch.domain.roleplay

import com.dshwatch.core.Logx
import com.dshwatch.data.store.MemoryStore
import com.dshwatch.domain.ChatEngine
import com.dshwatch.domain.ResponseProbe
import com.dshwatch.domain.TokenEstimator
import com.dshwatch.domain.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

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
    /** 体检记录。为空时（单测）只是不落盘，判定逻辑不受影响 */
    private val health: com.dshwatch.data.store.MemoryHealthStore? = null,
) {

    /**
     * 同一时刻只允许一次压缩。
     *
     * 触发点是「流式结束」，而这个条件在一轮里可能被求值两次
     * （streaming 与 messages.size 都是 key）。两次压缩并发时：
     * 后启动的那次会基于同一个旧切点再总结一遍，先完成的那次把切点推到 12:00，
     * 后完成的推到 12:20 —— 顺序反过来切点就回退，等于把已经带上的上下文又扔了。
     *
     * `tryLock` 而不是 `withLock`：正在压的时候不该排队，直接跳过这一次即可，
     * 下一轮结束会重新判定。
     */
    private val gate = Mutex()

    /**
     * 一次整理的结果。
     *
     * ## 为什么要返回值
     *
     * 以前这条路是"发出去就不管"：界面点完只能 `delay(5_000)` 再读一眼记忆文件，
     * 猜有没有写成。猜错的代价很具体 —— 网络慢就是「一直正在整理」，
     * 写失败就是「点了没反应」，而用户分不出这两件事。
     *
     * 现在每一步都有话说：卡在判定、卡在网络、还是写成功了。
     */
    data class CompressResult(
        val ok: Boolean,
        /** 新写进记忆的条数（成功时 > 0） */
        val added: Int = 0,
        /** 给用户看的一句话：成功说写了几条，失败说卡在哪 */
        val message: String = "",
        /** 这次摘要覆盖了多少条消息 */
        val covered: Int = 0,
    )

    /**
     * 用户当场点「立即整理」时用这个。
     *
     * 和 [maybeCompress] 的三点不同，每一点都对应一个"点了没反应"：
     *
     *  1. **等锁**而不是 tryLock —— 上一次整理可能还在跑（比如刚聊完自动触发了一次），
     *     立刻点第二次时旧实现直接 `return`，界面收不到任何回调，看起来就是卡住。
     *     这里最多等 [lockWaitMs]，等不到就明确说"还有一次在跑"。
     *  2. **有超时** —— 网络不回话时不会无限期挂着。
     *  3. **返回结果** —— 界面不必再 `delay(5s)` 猜，也就不会"猜完还停在正在整理"。
     */
    suspend fun compressNow(
        conversationId: String,
        cardId: String,
        scene: String,
        contextWindow: Int,
        providerId: String = "",
        model: String = "",
        lockWaitMs: Long = 3_000,
        timeoutMs: Long = MANUAL_TIMEOUT_MS,
    ): CompressResult {
        if (cardId.isBlank() || conversationId.isBlank()) {
            return CompressResult(ok = false, message = "还没有正在进行的对话")
        }
        val got = withTimeoutOrNull(lockWaitMs) {
            gate.lock()
            true
        } ?: return CompressResult(
            ok = false,
            message = "上一次整理还没结束，等它跑完再点",
        )
        /*
         * 先把「这次会打给谁」算出来。
         *
         * 失败文案里必须带上具体模型名 —— 用户报的就是「显示模型的 API 或模型名
         * 不对」，只说「模型没有返回内容」他还是不知道该去改哪一项。
         * 配置本身不成立（没供应商 / 没选模型 / 没填 Key）也会在这里就抛出来，
         * 不用等一次网络往返。
         */
        val target = runCatching { engine.briefTarget(providerId, model) }.getOrNull()
        try {
            note(
                com.dshwatch.data.store.MemoryHealth.TRIGGERED,
                scene,
                cardId,
                detail = "手动整理",
            )
            return withTimeoutOrNull(timeoutMs) {
                compress(conversationId, cardId, scene, providerId, model)
            } ?: run {
                note(
                    com.dshwatch.data.store.MemoryHealth.FAILED,
                    scene,
                    cardId,
                    detail = "整理超时（${timeoutMs / 1000} 秒没有结果）",
                )
                CompressResult(
                    ok = false,
                    message = "整理超时：${timeoutMs / 1000} 秒没有结果。多半是接口不回话，检查一下供应商地址与模型名",
                )
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            /*
             * 页面被关掉 / 用户退出了，这次整理就算断了。
             *
             * 一定要把这条写下去：磁盘上留着的是上面那条「正在整理」，
             * 而界面每次进页面都读它 —— 用户看到的就是**永远卡在正在整理**。
             * 用 NonCancellable 是因为此时协程已经是取消状态，普通挂起写不进去。
             */
            writeFinal(
                com.dshwatch.data.store.MemoryHealth.INTERRUPTED,
                scene,
                cardId,
                detail = "页面关掉了，这次没整理完",
            )
            throw ce
        } catch (e: Throwable) {
            Logx.w(TAG, "手动整理失败", e)
            // 后端给的是 DshError（HTTP 401 / 鉴权失败 / 网络不可达…），
            // 带 hint 的那句话比 exception.message 有用得多。
            // 取前两行：「配置有误」+「供应商『X』还没有选择模型」 ——
            // 只取第一行会把真正的原因丢掉。
            val text = (e as? com.dshwatch.net.backend.DshError)
                ?.describeForUser()
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.take(2)
                ?.joinToString("：")
                ?: e.message?.take(120).orEmpty().ifBlank { e.javaClass.simpleName }
            note(
                com.dshwatch.data.store.MemoryHealth.FAILED,
                scene,
                cardId,
                detail = text.take(120),
            )
            val who = target?.let { "（${it.label}）" }.orEmpty()
            return CompressResult(ok = false, message = "整理失败$who：$text")
        } finally {
            gate.unlock()
        }
    }

    /**
     *
     * 判定用 [MemoryPolicy]，输入是**未压缩的那一段**（`contextStart` 之后），
     * 不是整个会话。细节见 [MemoryPolicy] 的注释：以前用全量算，
     * 分母又默认 64k，于是永远够不到阈值，一次都没触发过。
     *
     * @param conversationId 当前会话
     * @param cardId 这张卡（记忆按卡存，不同会话用同一张卡会共享记忆）
     * @param scene tavern / writing / fish
     * @param contextWindow 模型窗口；<=0 表示未知
     * @param force 用户手动点「立即压缩」
     * @param providerId 指定用哪家模型做摘要（空 = 当前活动供应商）。
     *   养鱼可能单独配了一家 API（见 `fishProviderId`），摘要也得跟着走那家 ——
     *   用 activeProvider 时，用户只配了养鱼那家的情况下会直接返回空摘要。
     * @param model 指定摘要用哪个模型（空 = 供应商的默认模型）。
     *   传会话自己的模型：聊天用一个、摘要偷偷回落到另一个，是「她记得的事」
     *   与「她说话的样子」不一致的来源之一。
     */
    fun maybeCompress(
        conversationId: String,
        cardId: String,
        scene: String,
        contextWindow: Int,
        force: Boolean = false,
        providerId: String = "",
        model: String = "",
    ) {
        if (cardId.isBlank() || conversationId.isBlank()) {
            note(
                com.dshwatch.data.store.MemoryHealth.NO_SESSION,
                scene,
                cardId,
                detail = "没有正在进行的对话",
            )
            return
        }

        val tail = uncompressedTail()
        val tokens = tail.sumOf { TokenEstimator.estimatePair(it.role.wire, it.content) }
        val fish = scene == Scenes.FISH
        val threshold = MemoryPolicy.threshold(contextWindow, fish)
        if (!force && !MemoryPolicy.shouldCompress(tokens, tail.size, contextWindow, fish)) {
            note(
                com.dshwatch.data.store.MemoryHealth.SKIPPED,
                scene,
                cardId,
                tailCount = tail.size,
                tailTokens = tokens,
                threshold = threshold,
            )
            return
        }

        note(
            com.dshwatch.data.store.MemoryHealth.TRIGGERED,
            scene,
            cardId,
            tailCount = tail.size,
            tailTokens = tokens,
            threshold = threshold,
        )

        scope.launch {
            if (!gate.tryLock()) {
                Logx.i(TAG, "上一次压缩还在跑，跳过本次")
                return@launch
            }
            try {
                /*
                 * 这里**不加**协程超时。
                 *
                 * 网络那一段已经有硬上限了（`Http.brief`：每次尝试 45 秒，
                 * 最多两次），而 `finally` 保证锁一定释放 —— 这才是"卡住"
                 * 真正的解法。再叠一层 `withTimeout` 只会让虚拟时间的单测
                 * 一进去就判超时（`advanceUntilIdle` 会把时钟直接推过去）。
                 */
                runCatching { compress(conversationId, cardId, scene, providerId, model) }
                    .onFailure { e ->
                        // 取消（切会话、退出）不算失败，也不该留一条「正在整理」在盘上
                        if (e is kotlinx.coroutines.CancellationException) {
                            writeFinal(
                                com.dshwatch.data.store.MemoryHealth.INTERRUPTED,
                                scene,
                                cardId,
                                detail = "整理到一半被打断了",
                            )
                            return@onFailure
                        }
                        Logx.w(TAG, "长期记忆压缩失败，本轮跳过", e)
                        note(
                            com.dshwatch.data.store.MemoryHealth.FAILED,
                            scene,
                            cardId,
                            tailCount = tail.size,
                            tailTokens = tokens,
                            threshold = threshold,
                            detail = e.message?.take(120).orEmpty()
                                .ifBlank { e.javaClass.simpleName },
                        )
                    }
            } finally {
                gate.unlock()
            }
        }
    }

    /**
     * 记一次判定/结果。
     *
     * 落到 `memory/_health.json`，记忆页把它翻成一句人话 —— 用户说
     * 「聊了八十句还是没有记忆」时，这行字能直接指出是"没到门槛"、
     * "请求失败"还是"摘要为空"。没有它，四种情况在界面上完全一样。
     */
    private fun note(
        outcome: String,
        scene: String,
        cardId: String,
        tailCount: Int = 0,
        tailTokens: Int = 0,
        threshold: Int = 0,
        detail: String = "",
        written: Int = 0,
    ) {
        val store = health ?: return
        val h = com.dshwatch.data.store.MemoryHealth(
            at = System.currentTimeMillis(),
            scene = scene,
            cardId = cardId,
            tailCount = tailCount,
            tailTokens = tailTokens,
            threshold = threshold,
            outcome = outcome,
            detail = detail,
            written = written,
            // 序号在 launch 之前取：写入的先后由调用顺序决定，
            // 不会因为 IO 快慢把「正在整理」盖到结果上面
            seq = store.nextSeq(),
        )
        scope.launch { store.write(h) }
    }

    /**
     * 取消状态下也要写下去的一条结果。
     *
     * `scope.launch` 此时是取消状态，任务根本排不进去；用 NonCancellable
     * 直接在当前协程里写完再往外抛取消。只给「被打断」这条路用。
     */
    private suspend fun writeFinal(
        outcome: String,
        scene: String,
        cardId: String,
        detail: String = "",
    ) {
        val store = health ?: return
        val h = com.dshwatch.data.store.MemoryHealth(
            at = System.currentTimeMillis(),
            scene = scene,
            cardId = cardId,
            outcome = outcome,
            detail = detail,
            seq = store.nextSeq(),
        )
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { store.write(h) }
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
     * 4. 把 `tavernTurn` 推到**摘要实际覆盖到的末尾** —— 不是当前末尾。
     *
     * 第 4 步的下标必须是快照末尾（[cutAt]）：摘要是网络请求，返回时
     * 用户很可能又聊了几轮，那些内容不在摘要里，切掉就再也找不回来。
     */
    suspend fun compress(
        conversationId: String,
        cardId: String,
        scene: String,
        providerId: String = "",
        model: String = "",
    ): CompressResult {
        /*
         * 先确定「这次打给哪家、哪个模型」。
         *
         * 放在最前面有两个原因：配置不成立（没供应商 / 没选模型 / 没填 Key）
         * 立刻抛出，不用等网络；以及后面所有提示都能带上具体的模型名，
         * 用户才知道该去改什么。
         */
        val target = engine.briefTarget(providerId, model)
        val all = engine.snapshotMessages()
        val from = engine.contextStart()
        // 少于 MIN_MESSAGES 条就不值得花一次请求：摘要发出去的内容还没提示词长
        if (all.size - from < MemoryPolicy.MIN_MESSAGES) {
            note(
                com.dshwatch.data.store.MemoryHealth.EMPTY,
                scene,
                cardId,
                tailCount = (all.size - from).coerceAtLeast(0),
                detail = "内容太少（${(all.size - from).coerceAtLeast(0)} 句），先不整理",
            )
            return CompressResult(
                ok = false,
                message = "内容太少（${(all.size - from).coerceAtLeast(0)} 句），先不整理",
            )
        }

        val pending = all.subList(from.coerceIn(0, all.size), all.size)

        /*
         * 只压「最早的一段」：预算放不下时，超出的部分留在上下文里，下次再压。
         *
         * 以前是 `renderTranscript(pending).take(12_000)` —— 取的是**前** 12000 字，
         * 而切点推到全部末尾。一旦未压缩内容超过预算（英文、代码比中文更容易超），
         * 后面那些内容既没进摘要、又被切点排除，和"突然忘掉最近二十分钟"
         * 是同一个毛病。压缩必须严格等于"切点之前的那一段"。
         */
        val takeCount = coverCount(pending, TRANSCRIPT_BUDGET)
        if (takeCount < 2) {
            note(
                com.dshwatch.data.store.MemoryHealth.EMPTY,
                scene,
                cardId,
                tailCount = pending.size,
                detail = "待整理的内容太少",
            )
            return CompressResult(ok = false, message = "待整理的内容太少")
        }

        val covered = pending.subList(0, takeCount)
        val transcript = renderTranscript(covered)
        if (transcript.isBlank()) {
            note(
                com.dshwatch.data.store.MemoryHealth.EMPTY,
                scene,
                cardId,
                tailCount = pending.size,
                detail = "这段里没有可整理的正文",
            )
            return CompressResult(ok = false, message = "这段里没有可整理的正文")
        }

        // 摘要能覆盖到哪儿，切点就只能到哪儿。这个值在下面 probe 之前定下来，
        // 之后无论用户又聊了多少句都不再改变。
        val cutAt = from + takeCount

        val card = loadCard(cardId)

        /*
         * 把现有记忆的前 12 条编号给模型，让它自己判断「这句是不是在说同一件事」。
         *
         * 这是「多次提到会加深印象」能准的关键：字面匹配分不清
         * 「她喜欢猫」和「她喜欢狗」，模型分得清。成本只是 prompt 多三百字，
         * 请求次数不变。
         */
        val remembered = if (scene == Scenes.WRITING) {
            emptyList()
        } else {
            memory.entries(cardId).sortedByDescending { it.at }.take(12)
        }

        val summary = engine.probe(
            ResponseProbe(
                systemPrompt = TavernPrompt.compressInstruction(
                    scene,
                    remembered.map { it.text.take(30) },
                ),
                userText = transcript.take(TRANSCRIPT_BUDGET),
                temperature = 0.3f,
                maxTokens = if (scene == Scenes.WRITING) 800 else 400,
            ),
            providerId = target.provider.id,
            modelOverride = target.model,
        ).trim()

        if (summary.isBlank()) {
            Logx.w(TAG, "摘要为空，放弃本次压缩")
            // 说清楚是哪个模型不说话 —— 「模型名或 Key 不对」不给名字等于没说
            val why = "${target.label} 没有返回内容（模型名不对，或者它不支持这种一次性请求）"
            note(
                com.dshwatch.data.store.MemoryHealth.EMPTY,
                scene,
                cardId,
                tailCount = pending.size,
                detail = why,
            )
            return CompressResult(ok = false, message = why)
        }

        // 摘要往返期间用户可能已经切走。这时写进记忆只会让同一段被重复总结
        // （下次在新会话里还会再压一遍），直接丢弃更干净。
        if (engine.conversation.value?.id != conversationId) {
            Logx.w(TAG, "摘要返回时会话已切换，丢弃本次结果")
            note(
                com.dshwatch.data.store.MemoryHealth.EMPTY,
                scene,
                cardId,
                tailCount = pending.size,
                detail = "摘要返回时你已经切走了，这次先不留",
            )
            return CompressResult(ok = false, message = "摘要返回时你已经切走了，这次先不留")
        }

        // 记忆文档的段落里留一行"这是谁的对话"，用户翻文件时才认得出
        val who = card?.displayName?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        var writtenCount = 1
        if (scene == Scenes.WRITING) {
            // 写作要连贯的前情提要，不能拆碎 —— 续写靠的是情节脉络，不是事件清单。
            // 给一个中等偏高的情绪分：剧情脉络没人提也不该两周就变成"记不太清了"
            memory.append(cardId, "### 前情提要$who\n$summary", emotion = 0.55f)
        } else {
            /*
             * 酒馆走「一条一件事」。
             *
             * 事件时间取这一段对话里第一条消息的发出时刻，而不是压缩完成的时刻：
             * 用户可能聊完了隔天才触发压缩，用当前时间会把"三天前的事"记成
             * "今天的事"，衰减节奏就全错了。
             */
            val eventAt = covered.firstOrNull { it.createdAt > 0 }?.createdAt
                ?: System.currentTimeMillis()
            val parsed = parseEvents(summary, eventAt, remembered)
            if (parsed.fresh.isEmpty() && parsed.merges.isEmpty()) {
                Logx.w(TAG, "解析不出事件，退回整段写入")
                memory.append(cardId, "### 对话摘要$who\n$summary")
            } else {
                memory.appendEvents(cardId, parsed.fresh, parsed.merges)
                Logx.i(
                    TAG,
                    "写入记忆：新增 ${parsed.fresh.size} 条，加深 ${parsed.merges.size} 条",
                )
                writtenCount = parsed.fresh.size + parsed.merges.size
            }
        }
        note(
            com.dshwatch.data.store.MemoryHealth.WRITTEN,
            scene,
            cardId,
            tailCount = pending.size,
            written = writtenCount,
        )
        Logx.i(TAG, "压缩完成：摘要覆盖第 $from~$cutAt 条（待压 ${pending.size} 条），切点推至 $cutAt")
        // 用 cutAt 而不是默认的"当前末尾"：probe 往返期间新聊的内容
        // 不在这段摘要里，一起切掉就是用户说的"刚才二十分钟全忘了"
        engine.markContextCut(upTo = cutAt, onlyIfConversation = conversationId)
        return CompressResult(
            ok = true,
            added = writtenCount,
            message = "整理完成，记下 $writtenCount 条",
            covered = covered.size,
        )
    }

    /**
     * 预算内从头能覆盖多少条。
     *
     * 必须从头（更早的）开始数：切点只能从前往后推，压缩的一定是最早那一段。
     * 从尾部挑就会出现「压了新的、旧的反被切掉」的错位。
     */
    private fun coverCount(messages: List<ChatMessage>, budget: Int): Int {
        var used = 0
        var count = 0
        for (m in messages) {
            // 角色前缀 + 换行，粗略按 8 个字符的固定开销
            val len = m.content.length + 8
            // 第一条无论多长都收，否则一条超长消息会把压缩永久卡死
            if (count > 0 && used + len > budget) break
            used += len
            count++
        }
        return count
    }

    /** 把消息列表拍平成"谁说了什么"，过滤掉工具与空消息 */
    private fun renderTranscript(messages: List<ChatMessage>): String = buildString {        messages.forEach { m ->
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

        /**
         * 送去压缩的原文上限。
         *
         * 这里必须从头取（见 [coverCount]），不能"只给后半段" ——
         * 切点只能从前往后推，压了哪段就只能切到哪段。
         */
        private const val TRANSCRIPT_BUDGET = 12_000

        /**
         * 手动整理的上限：用户正盯着屏幕，不能让他一直等。
         *
         * 单次请求 45 秒（`Http.brief`），失败后还会走一次流式重试，所以这里
         * 按「一次完整尝试 + 半次重试」留：到点就掐断，并且**掐得动** ——
         * 协程取消会立刻 cancel 掉那条 OkHttp 连接（见 `OpenAiCompatBackend`），
         * 不会出现「超时了还要再等一个读超时」。
         */
        private const val MANUAL_TIMEOUT_MS = 70_000L

        /** 一次最多产出几条事件。再多就是流水账，反而挤占提示词预算 */
        private const val MAX_EVENTS = 5

        /** 单条事件字数上限。模型偶尔会写一整段，这里硬截 */
        private const val MAX_EVENT_CHARS = 80

        private val MARKER = Regex("""^\[([*!~-])\]\s*(.+)$""")

        /** `[=3] 内容`：这是第 3 条的补充 */
        private val MERGE = Regex("""^\[=(\d+)\]\s*(.+)$""")

        internal data class ParsedEvents(
            val fresh: List<MemoryEntry>,
            /** 模型指认的合并：目标条目 → 新的合并后内容 */
            val merges: List<Pair<MemoryEntry, MemoryEntry>>,
        )

        /**
         * 把模型输出解析成事件。
         *
         * 严格格式是 `[标记] 内容` / `[=编号] 内容`，但模型不总听话 ——
         * 常见退化是加一层 `记忆：` 前缀、或者干脆写成一段话。所以这里有两级：
         * 认得出标记就用标记（情绪由标记决定），认不出就按句子切
         * （情绪交给本地启发式）。
         *
         * 宁可多解析出一条，也不要因为格式不对就整段丢掉 ——
         * 丢掉的是用户真实聊过的内容。
         */
        internal fun parseEvents(
            raw: String,
            at: Long,
            remembered: List<MemoryEntry>,
        ): ParsedEvents {
            val fresh = ArrayList<MemoryEntry>()
            val merges = ArrayList<Pair<MemoryEntry, MemoryEntry>>()

            raw.lines().forEach { line ->
                val t = line.trim().removePrefix("- ").trim()
                if (t.isEmpty()) return@forEach

                // [=3] 这是第 3 条的补充 —— 不新增，加深旧条目
                val merge = MERGE.find(t)
                if (merge != null) {
                    val body = merge.groupValues[2].trim().trim('-', '—', '·', ' ', '：', ':')
                    if (body.length < 4) return@forEach
                    val incoming = MemoryEntry(at = at, text = body.take(MAX_EVENT_CHARS))
                    val target = merge.groupValues[1].toIntOrNull()
                        ?.let { remembered.getOrNull(it - 1) }
                    // 编号越界（模型乱标）就当新事件，至少不丢内容
                    if (target != null) merges.add(target to incoming) else fresh.add(incoming)
                    return@forEach
                }

                val m = MARKER.find(t)
                if (m != null) {
                    val body = m.groupValues[2].trim().trim('-', '—', '·', ' ', '：', ':')
                    if (body.length >= 4) {
                        fresh.add(
                            MemoryEntry(
                                at = at,
                                text = body.take(MAX_EVENT_CHARS),
                                emotion = EmotionScore.fromMarker(m.groupValues[1][0]),
                            )
                        )
                    }
                }
            }

            if (fresh.isEmpty() && merges.isEmpty()) {
                return ParsedEvents(fallbackSplit(raw, at), emptyList())
            }
            return ParsedEvents(fresh.take(MAX_EVENTS), merges.take(MAX_EVENTS))
        }

        /** 模型没按格式输出时的兜底：按句子切成几条 */
        internal fun fallbackSplit(raw: String, at: Long): List<MemoryEntry> {
            val text = raw
                .replace(Regex("""^#{1,6}\s.*$""", RegexOption.MULTILINE), " ")
                .replace(Regex("""^[-*·]\s""", RegexOption.MULTILINE), "")
                .replace('\n', ' ')
                .trim()
            if (text.isEmpty()) return emptyList()
            val sentences = text.split('。', '！', '？', '.', '!', '?')
                .map { it.trim() }
                .filter { it.length >= 6 }
            if (sentences.isEmpty()) {
                return listOf(
                    MemoryEntry(
                        at = at,
                        text = text.take(MAX_EVENT_CHARS),
                        emotion = EmotionScore.of(text),
                    )
                )
            }
            return sentences.take(MAX_EVENTS).map {
                MemoryEntry(at = at, text = it.take(MAX_EVENT_CHARS))
            }
        }
    }
}
