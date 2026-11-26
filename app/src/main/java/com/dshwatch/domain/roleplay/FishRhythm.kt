package com.dshwatch.domain.roleplay

import kotlin.random.Random

/**
 * 「养鱼」的聊天节奏。
 *
 * ## 为什么需要这一层
 *
 * 模型的输出是"一次给完"的：网络一回来，整段文字已经在内存里了。
 * 如果直接铺到屏幕上，不管内容多口语，观感都是打印机 —— 真人不会这样。
 *
 * 真人发消息有四个可观察的阶段：
 *  1. **读**：看到你的消息，先花一点时间看懂（你写得越长，读得越久）；
 *  2. **想**：组织语言。这段时间屏幕上什么都没有，你会以为她在忙；
 *  3. **打字**：`正在输入…` 出现，按字数一点点往外蹦；
 *  4. **发**：一条一条发，条与条之间还停顿一下。
 *
 * 这四个阶段都可以用"与字数相关的函数 + 上限"近似。不需要真的限制模型速度：
 * 文字已经全部拿到了，我们只是**决定什么时候让它出现**。
 *
 * 数值取的是手机上中速打字的水平（约每字 110ms，10 个字一条要一秒多）。
 * 上限很关键：不封顶的话，一段两百字的回复要播四十秒，用户会以为卡死。
 */
object FishRhythm {

    /** 每字打字耗时 */
    const val PER_CHAR_MS = 110L

    /** 读消息：基础 320ms，每字多给 25ms，最多 2.2 秒 */
    fun readDelay(userText: String): Long =
        (320L + userText.length * 25L).coerceAtMost(2_200L)

    /** 组织语言：至少 550ms（再快就像在等脚本），按整条回复的字数递增，最多 2.6 秒 */
    fun thinkDelay(reply: String): Long =
        (400L + reply.length * 15L).coerceIn(550L, 2_600L)

    /** 一条气泡"打出来"的时间：20 字约 2.2 秒，上下都留了界限 */
    fun typingDelay(bubble: String): Long =
        (bubble.length * PER_CHAR_MS).coerceIn(260L, 2_600L)

    /** 条与条之间的停顿：350~810ms，随机一点才不机械 */
    fun bubbleGap(random: Random = Random.Default): Long =
        350L + random.nextLong(460L)

    /**
     * 沉默。
     *
     * 模型偶尔会只输出这个标记表示"看见了，但这次不想接"。
     * 客户端据此不发气泡，只留一个已读回执 —— 这是"可以不回话"的落地方式。
     */
    const val SILENT = "[已读]"

    fun isSilent(text: String): Boolean {
        val t = text.trim().trim('（', '）', '(', ')')
        return t == "已读" || t == "沉默" || text.trim() == SILENT
    }
}
