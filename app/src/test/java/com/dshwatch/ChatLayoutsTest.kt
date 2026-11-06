package com.dshwatch

import com.dshwatch.domain.model.ChatLayouts
import com.dshwatch.domain.model.ScreenShapes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 引导页的两个选择项：屏幕形状与对话布局。
 *
 * 这两处都是「存字符串」的，非法值必须能安全回落 ——
 * 设置整包是从磁盘 JSON 读回来的，老版本或手改过的文件都可能塞进不认识的 id。
 */
class ChatLayoutsTest {

    @Test
    fun `explicit shape choice wins over detection`() {
        assertThat(ScreenShapes.resolve(ScreenShapes.ROUND, detected = false)).isTrue()
        assertThat(ScreenShapes.resolve(ScreenShapes.SQUARE, detected = true)).isFalse()
    }

    @Test
    fun `auto falls back to system detection`() {
        assertThat(ScreenShapes.resolve(ScreenShapes.AUTO, detected = true)).isTrue()
        assertThat(ScreenShapes.resolve(ScreenShapes.AUTO, detected = false)).isFalse()
    }

    @Test
    fun `unknown shape id degrades to auto`() {
        assertThat(ScreenShapes.of("triangle")).isEqualTo(ScreenShapes.AUTO)
        assertThat(ScreenShapes.resolve("triangle", detected = true)).isTrue()
    }

    @Test
    fun `ring layout is gone, both shapes get the same three`() {
        // 环形布局已删除：圆屏 / 方屏都用气泡、卡片、极简三套。
        assertThat(ChatLayouts.available(isRound = true))
            .containsExactly(ChatLayouts.BUBBLE, ChatLayouts.CARD, ChatLayouts.MINIMAL)
        assertThat(ChatLayouts.available(isRound = false)).hasSize(3)
        // 老设置里存过 "ring" 的要落回气泡，不能变成不存在的布局
        assertThat(ChatLayouts.of("ring")).isEqualTo(ChatLayouts.BUBBLE)
    }

    @Test
    fun `unknown layout id degrades to bubble`() {
        assertThat(ChatLayouts.of("fancy")).isEqualTo(ChatLayouts.BUBBLE)
        assertThat(ChatLayouts.of(ChatLayouts.CARD)).isEqualTo(ChatLayouts.CARD)
    }
}
