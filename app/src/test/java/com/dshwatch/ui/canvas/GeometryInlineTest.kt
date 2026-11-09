package com.dshwatch.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 内联坐标写法。
 *
 * 用例来自真机截图：模型画函数图象时给的是
 * `"segments":[[[-1.5,0.5],[-0.5,3.25]], …]` —— 完全没有 points 数组，
 * 每条边自己带坐标对。解析器当时把 `arr[0].jsonPrimitive` 强转成基本类型，
 * 而 arr[0] 是数组，直接抛 ClassCastException，界面上就是
 * 「几何图解析失败，原文」加一整屏 JSON。
 */
class GeometryInlineTest {

    /** 用户截图里模型的实际输出，一字未改 */
    private val curve = """
        {
          "axes": true,
          "segments": [
            [[-1.5, 0.5], [-0.5, 3.25]],
            [[-0.5, 3.25], [0, 2]],
            [[0, 2], [0.5, 1.25]],
            [[0.5, 1.25], [1, 1]],
            [[1, 1], [1.5, 1.25]],
            [[1.5, 1.25], [2, 2]],
            [[2, 2], [2.5, 3.25]],
            [[2.5, 3.25], [3, 5]]
          ],
          "texts": [
            {"x": 1.4, "y": 1.6, "text": "f(t) 永远在 t 轴上方"},
            {"x": 0.9, "y": 0.4, "text": "最小值 ≥ 0"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses segments written as raw coordinate pairs`() {
        val scene = Geometry.parse(curve)
        assertThat(scene).isNotNull()
        assertThat(scene!!.segments).hasSize(8)
        assertThat(scene.segments.first()).isEqualTo((-1.5f to 0.5f) to (-0.5f to 3.25f))
    }

    /**
     * 没有 points 的图也要能画。
     *
     * 视图层原来额外判了 `scene.points.isEmpty()`，内联坐标的图恰好没有点池，
     * 于是一张完全有效的图被判成解析失败。
     */
    @Test
    fun `a scene with inline coordinates but no points is still valid`() {
        val scene = Geometry.parse(curve)!!
        assertThat(scene.points).isEmpty()
        assertThat(scene.segments).isNotEmpty()
        assertThat(scene.texts).hasSize(2)
    }

    /**
     * 开了坐标系就得看见原点。
     *
     * 本例 y 从 0.4 起，只看数据的话 y=0 在画布外，横轴与刻度全被裁掉 ——
     * 用户打开图看到一条悬空的曲线，不知道横轴在哪。
     */
    @Test
    fun `axes keeps the origin inside the viewport`() {
        val scene = Geometry.parse(curve)!!
        assertThat(scene.axes).isTrue()
        assertThat(scene.minY).isAtMost(0f)
        assertThat(scene.minX).isAtMost(0f)
        assertThat(scene.maxX).isAtLeast(0f)
    }

    /** 三点以上的一段是折线，要拆成连续的多条边 */
    @Test
    fun `a polyline becomes a chain of segments`() {
        val scene = Geometry.parse(
            """{"segments": [[[0,0],[1,1],[2,0]]]}""",
        )!!
        assertThat(scene.segments).hasSize(2)
        assertThat(scene.segments[0]).isEqualTo((0f to 0f) to (1f to 1f))
        assertThat(scene.segments[1]).isEqualTo((1f to 1f) to (2f to 0f))
    }

    /** 坐标也可能写成对象，别只认数组 */
    @Test
    fun `accepts coordinate objects`() {
        val scene = Geometry.parse(
            """{"segments": [{"from":{"x":0,"y":0},"to":{"x":2,"y":3}}]}""",
        )
        // 这种写法在 segments 里不常见，至少不能崩
        assertThat(scene).isNotNull()
    }

    /** 只有文字没有图元时不算空场景 */
    @Test
    fun `text only scene is valid`() {
        val scene = Geometry.parse("""{"texts":[{"x":0,"y":0,"text":"结论"}]}""")
        assertThat(scene).isNotNull()
        assertThat(scene!!.texts).hasSize(1)
    }
}
