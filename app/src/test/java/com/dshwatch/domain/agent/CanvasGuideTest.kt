package com.dshwatch.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 画布说明的可见性。
 *
 * 这段文字存在的唯一理由是「让模型知道它画得出来」。真机上出过的事是：
 * 用户说「用几何画板试试」，模型回「工具列表里没有画板工具」，然后建议用户
 * 去装 GeoGebra —— 而 App 本来就能渲染 geom 围栏。
 *
 * 所以这里的断言不看措辞，只看三件必须成立的事：
 *  1. 三种围栏的名字都写了；
 *  2. 明确说了「不是工具」，否则模型会拿工具表来否定它；
 *  3. 几何给了可直接改的 JSON 示例 —— 没有示例，模型会自己发明字段名。
 */
class CanvasGuideTest {

    private val prompt = CanvasGuide.PROMPT

    @Test
    fun `names all three canvas fences`() {
        assertThat(prompt).contains("```geom")
        assertThat(prompt).contains("```math")
        assertThat(prompt).contains("```mindmap")
    }

    @Test
    fun `states that canvas is an output format rather than a tool`() {
        assertThat(prompt).contains("不是工具")
    }

    @Test
    fun `ships a geometry example with the real field names`() {
        listOf("points", "segments", "circles", "angles").forEach {
            assertThat(prompt).contains("\"$it\"")
        }
    }

    @Test
    fun `mentions the latex subset limits`() {
        assertThat(prompt).contains("\\frac")
        // 文档级命令会让自研解析器整段退化，必须明确禁掉
        assertThat(prompt).contains("\\begin{document}")
    }

    /**
     * 模型画函数图时写的是内联坐标对，不是点名。
     *
     * 它不会读渲染代码，只能读这段说明；说明里只给点名写法，它就会自己发明
     * `segments:[[[-1.5,0.5],…]]` 这种形式 —— 解析器当时正好接不住。
     * 现在两边都支持，说明里也要写清楚，免得它以为只有一种写法。
     */
    @Test
    fun `documents inline coordinate segments`() {
        assertThat(prompt).contains("[[-1.5,0.5]")
        assertThat(prompt).contains("折线")
    }

    @Test
    fun `documents the big delimiter commands the model actually writes`() {
        assertThat(prompt).contains("\\Big(")
        assertThat(prompt).contains("\\Longleftrightarrow")
    }

    @Test
    fun `stays small enough to send on every round`() {
        // 每轮都要付这份 token，超过 1200 字符就说明细节写多了
        assertThat(prompt.length).isLessThan(1200)
    }
}
