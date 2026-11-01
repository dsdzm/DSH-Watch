package com.dshwatch

import com.dshwatch.domain.qq.chunkedByCodePoint
import com.dshwatch.domain.qq.cleanGroupContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * QQ 群消息的两处文本处理。
 *
 * 这两件事看起来都是「一行 replace」，但都是真机上**看得见**的错误来源：
 * 标签没清干净，模型会学着在回答里带尖括号；emoji 被切成两半，
 * 群里显示成两个问号。协议层别的部分（WS 状态机）没法在这里测，
 * 只能靠真机连沙箱验证。
 */
class QqProtocolTest {

    @Test
    fun `行内 @ 标签被清掉`() {
        assertEquals("你好", cleanGroupContent("<@!123456789> 你好"))
        assertEquals("你好", cleanGroupContent("<@123456789>你好"))
    }

    @Test
    fun `群消息的前导空格不算正文`() {
        // QQ 在 @ 之后固定补一个空格，不清掉会让模型以为用户在故意留白
        assertEquals("/help", cleanGroupContent(" /help"))
    }

    @Test
    fun `只有标签没有正文时得到空串`() {
        assertEquals("", cleanGroupContent("<@!123456789>   "))
    }

    @Test
    fun `切段不会把 emoji 劈开`() {
        val text = "😀".repeat(10)
        val parts = text.chunkedByCodePoint(3)

        assertEquals("分成 4 段（10 个 emoji / 每段 3 个）", 4, parts.size)
        assertEquals("拼回去必须与原文一致", text, parts.joinToString(""))
        parts.forEach { assertFalse("不能出现空段", it.isEmpty()) }
        // 每个 emoji 是两个 UTF-16 单元，3 个一组就是 6
        assertEquals(6, parts[0].length)
    }

    @Test
    fun `不超过上限时原样返回`() {
        assertEquals(listOf("hi"), "hi".chunkedByCodePoint(900))
    }
}
