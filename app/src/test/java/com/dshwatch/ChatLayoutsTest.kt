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
    fun `ring layout is round only`() {
        assertThat(ChatLayouts.available(isRound = true))
            .containsExactly(ChatLayouts.BUBBLE, ChatLayouts.CARD, ChatLayouts.MINIMAL, ChatLayouts.RING)
        assertThat(ChatLayouts.available(isRound = false)).doesNotContain(ChatLayouts.RING)
        assertThat(ChatLayouts.available(isRound = false)).hasSize(3)
    }

    @Test
    fun `unknown layout id degrades to bubble`() {
        assertThat(ChatLayouts.of("fancy")).isEqualTo(ChatLayouts.BUBBLE)
        assertThat(ChatLayouts.of(ChatLayouts.CARD)).isEqualTo(ChatLayouts.CARD)
    }
}
