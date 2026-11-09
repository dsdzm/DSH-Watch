package com.dshwatch.ui.canvas

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 几何画板的解析与坐标映射。
 *
 * 这里的用例来自一张真机截图：用户让模型画三角形，画出来只有一条斜线，
 * 三个顶点和 AB/BC/CA 标签却都在正确位置。原因不在解析 —— 解析一直是对的 ——
 * 而是绘制时 `py(a.first)` 把点的 x 当成了 y，导致三条边全部落在 y=x 上。
 *
 * 所以测试分两层：
 *  1. 解析层：模型给的 JSON 要能原样变成三条边（含用户那次的原样输入）；
 *  2. 映射层：水平边的两个端点 y 必须相等 —— 这条能直接抓住上面那个 bug。
 */
class GeometryParseTest {

    /** 用户截图里模型的实际输出，一字未改 */
    private val triangle = """
        {
          "points": [
            {"name": "A", "x": 0, "y": 0},
            {"name": "B", "x": 5, "y": 0},
            {"name": "C", "x": 1.5, "y": 3.5}
          ],
          "segments": [["A", "B"], ["B", "C"], ["C", "A"]],
          "texts": [
            {"x": 2.5, "y": -0.4, "text": "AB"},
            {"x": 3.7, "y": 1.9, "text": "BC"},
            {"x": 0.4, "y": 1.9, "text": "CA"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses all three edges of the reported triangle`() {
        val scene = Geometry.parse(triangle)
        assertThat(scene).isNotNull()
        assertThat(scene!!.segments).hasSize(3)
        // AB 是水平边：两个端点 y 都是 0
        val ab = scene.segments[0]
        assertThat(ab.first).isEqualTo(0f to 0f)
        assertThat(ab.second).isEqualTo(5f to 0f)
    }

    @Test
    fun `keeps vertex coordinates from the json`() {
        val scene = Geometry.parse(triangle)!!
        val c = scene.points.first { it.name == "C" }
        assertThat(c.x).isEqualTo(1.5f)
        assertThat(c.y).isEqualTo(3.5f)
    }

    @Test
    fun `keeps the three edge labels`() {
        val scene = Geometry.parse(triangle)!!
        assertThat(scene.texts.map { it.text }).containsExactly("AB", "BC", "CA")
    }

    /**
     * 回归：水平边的两端必须落在同一水平线上。
     *
     * 修复前 px/py 都拿 `a.first`（x）算，A(0,0)→B(5,0) 会被画成
     * (0,0)→(5,5)，整张图缩成一条对角线。
     */
    @Test
    fun `horizontal segment keeps both endpoints on the same y`() {
        val (start, end) = segmentScreen(
            (0f to 0f) to (5f to 0f),
            px = { it * 10f },
            py = { 400f - it * 10f },
        )
        assertThat(start).isEqualTo(Offset(0f, 400f))
        assertThat(end).isEqualTo(Offset(50f, 400f))
        assertThat(start.y).isEqualTo(end.y)
    }

    @Test
    fun `vertical segment keeps both endpoints on the same x`() {
        val (start, end) = segmentScreen(
            (2f to 0f) to (2f to 3f),
            px = { it * 10f },
            py = { 400f - it * 10f },
        )
        assertThat(start.x).isEqualTo(end.x)
        assertThat(start.y).isEqualTo(400f)
        assertThat(end.y).isEqualTo(370f)
    }

    /**
     * 标签在图形外侧（y 为负）时也要在视野内。
     *
     * 模型标边长时习惯把文字放在边的外侧，只按点算包围盒会把标签裁掉。
     */
    @Test
    fun `viewport includes free text placed outside the shape`() {
        val scene = Geometry.parse(triangle)!!
        assertThat(scene.minY).isLessThan(0f)
    }

    @Test
    fun `returns null for content that is not geometry json`() {
        assertThat(Geometry.parse("这不是 JSON")).isNull()
        assertThat(Geometry.parse("{ }")).isNull()
    }
}
