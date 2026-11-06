package com.dshwatch.domain.roleplay

/**
 * 长期记忆「什么时候该压缩」的判定。
 *
 * ## 为什么单独抽出来
 *
 * 这套判断以前写在 [MemoryCompressor.maybeCompress] 里，只有一行：
 * `contextTokens / contextWindow >= 0.55`。上线后实际一次都没触发过 ——
 * 原因有两个，而且互相掩盖：
 *
 *  1. **分母太大**。`contextWindow` 默认 64000，55% 就是 35200 token。
 *     手表上一段聊了几十轮的角色扮演，撑死也就几千 token，
 *     永远够不到这条线。
 *  2. **分子算错了**。传进来的是「整个会话的消息总量」，而压缩之后
 *     更早的消息已经不再发给模型了（`contextStart` 之后才是有效上下文）。
 *     于是压缩过一次之后，分子仍然停在压缩前的水位 —— 要么永远不再触发，
 *     要么每轮都判定「该压了」，去压一段只有两三条的新内容。
 *
 * 所以判定必须满足两件事：**按未压缩的那一段算**，**阈值要有绝对上限**。
 *
 * ## 现在的规则
 *
 * 未压缩部分（`contextStart` 之后）满足任一条就压：
 *
 *  * 估算 token ≥ `min(窗口 × 55%, 6000)`；
 *  * 条数 ≥ 40 条。
 *
 * 6000 这个上限是按「一次摘要请求的成本」定的：摘要要把原文整段发一遍，
 * 太长就没意义了 —— 用户要的是记住要点，不是把整本书再读一次。
 * 窗口 8k 的小模型仍然按 55%（4400）走，不会因为上限而超窗。
 */
object MemoryPolicy {

    /** 上下文用到模型窗口的多少比例时触发 */
    const val TRIGGER_RATIO = 0.55f

    /**
     * 不论窗口多大，未压缩部分到这个量就该压了。
     *
     * 取 6000 的理由：中文约 1 token/字，6000 大致是 30~60 轮日常对话，
     * 再往后模型的注意力已经开始稀释，摘要的边际收益反而最高。
     */
    const val ABSOLUTE_TOKEN_CAP = 6_000

    /** 条数兜底：极短的来回（"嗯""哦"）凑不满 token，但条数会堆 */
    const val MESSAGE_CAP = 40

    /** 少于这么多条时不压：摘要请求本身也是一次成本，2 条消息压完什么也不剩 */
    const val MIN_MESSAGES = 4

    /** 当前窗口下的实际 token 阈值 */
    fun threshold(contextWindow: Int): Int {
        if (contextWindow <= 0) return ABSOLUTE_TOKEN_CAP
        val byRatio = (contextWindow * TRIGGER_RATIO).toInt()
        return minOf(byRatio, ABSOLUTE_TOKEN_CAP).coerceAtLeast(1)
    }

    /**
     * 该不该压。
     *
     * @param tailTokens 未压缩部分的估算 token 数
     * @param tailCount 未压缩部分的消息条数
     * @param contextWindow 模型窗口；<=0 表示未知，此时只按条数判断
     */
    fun shouldCompress(tailTokens: Int, tailCount: Int, contextWindow: Int): Boolean {
        if (tailCount < MIN_MESSAGES) return false
        if (tailCount >= MESSAGE_CAP) return true
        if (contextWindow <= 0) return false
        return tailTokens >= threshold(contextWindow)
    }

    /** 给界面/帮助用的一句话说明 */
    fun describe(contextWindow: Int): String {
        val t = threshold(contextWindow)
        return "未压缩内容到 $t token 或 ${MESSAGE_CAP} 条时自动整理"
    }
}
