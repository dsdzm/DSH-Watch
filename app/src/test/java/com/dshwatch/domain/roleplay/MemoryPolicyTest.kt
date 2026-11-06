package com.dshwatch.domain.roleplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 长期记忆的触发判定。
 *
 * 这里守的是「到底会不会触发」这件事本身 —— 上一版因为阈值按全量 token
 * 和 64k 窗口算，线上一次都没压过。所以用例的重点不是边界值，
 * 而是**日常用量必须能触发**，以及**压缩完之后不能立刻又触发**。
 */
class MemoryPolicyTest {

    @Test
    fun `64k 窗口这类大窗口按绝对上限触发，不会干等 35k`() {
        assertThat(MemoryPolicy.threshold(64_000)).isEqualTo(MemoryPolicy.ABSOLUTE_TOKEN_CAP)
        assertThat(MemoryPolicy.threshold(128_000)).isEqualTo(MemoryPolicy.ABSOLUTE_TOKEN_CAP)
        // 6000 token 大约是几十轮日常对话，到这个量就该整理了
        assertThat(MemoryPolicy.shouldCompress(6_000, 30, 64_000)).isTrue()
        assertThat(MemoryPolicy.shouldCompress(5_999, 30, 64_000)).isFalse()
    }

    @Test
    fun `小窗口仍按比例走，不会撑爆上下文`() {
        // 8k 窗口：55% = 4400，小于绝对上限，取 4400
        assertThat(MemoryPolicy.threshold(8_000)).isEqualTo(4_400)
        assertThat(MemoryPolicy.shouldCompress(4_400, 12, 8_000)).isTrue()
        assertThat(MemoryPolicy.shouldCompress(4_399, 12, 8_000)).isFalse()
    }

    @Test
    fun `消息条数到上限也触发，不看 token`() {
        // 全是「嗯」「哦」的对话，token 很少但条数已经很多
        assertThat(MemoryPolicy.shouldCompress(200, MemoryPolicy.MESSAGE_CAP, 64_000)).isTrue()
        assertThat(MemoryPolicy.shouldCompress(200, MemoryPolicy.MESSAGE_CAP - 1, 64_000)).isFalse()
    }

    @Test
    fun `太短的对话不压缩`() {
        // 3 条就算 token 再多也不压：一次摘要请求的成本换不回信息量
        assertThat(MemoryPolicy.shouldCompress(9_999, 3, 64_000)).isFalse()
        assertThat(MemoryPolicy.shouldCompress(0, 10, 64_000)).isFalse()
    }

    @Test
    fun `窗口未知时不按 token 判断，只认条数`() {
        assertThat(MemoryPolicy.shouldCompress(99_999, 10, 0)).isFalse()
        assertThat(MemoryPolicy.shouldCompress(10, MemoryPolicy.MESSAGE_CAP, 0)).isTrue()
    }

    @Test
    fun `压缩完之后未压缩部分归零，不会立刻重复触发`() {
        // 压完 contextStart 推到末尾，tail 清空 —— 这正是"用全量算"时坏掉的地方：
        // 旧的分子不归零，下一轮又会判定该压，压的却是两三条新消息。
        assertThat(MemoryPolicy.shouldCompress(0, 0, 64_000)).isFalse()
        assertThat(MemoryPolicy.shouldCompress(120, 2, 64_000)).isFalse()
    }

    @Test
    fun `阈值至少是 1，畸形窗口不会算出 0 或负数`() {
        assertThat(MemoryPolicy.threshold(1)).isAtLeast(1)
        assertThat(MemoryPolicy.threshold(-5)).isAtLeast(1)
    }
}
