package com.dshwatch.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 模糊**真的把像素抹开了**。
 *
 * ## 为什么要测到像素这一层
 *
 * 这个功能连续两个版本「开关打开、画面不动」，而日志里一切正常：旧的模糊路径
 * 全部包在 try/catch 里，失败时安静地退回原图。也就是说**「没生效」和「生效了」
 * 在调用方看来完全一样**，只有像素能区分。
 *
 * 所以这里的断言不看「有没有调用模糊」，而是看「模糊之后那片像素变成了什么」。
 * 只要 [SoftBlur.blurPixels] 的行为还在，换任何调用方式都不可能再退化成原图。
 */
class SoftBlurTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** 中间一条 1px 竖线，其余全黑 */
    private fun lineOnBlack(w: Int, h: Int, x: Int): IntArray =
        IntArray(w * h) { i -> if (i % w == x) white else black }

    private fun red(px: IntArray, i: Int): Int = (px[i] ushr 16) and 0xFF

    @Test
    fun `单色图糊完还是同一个颜色`() {
        val px = IntArray(16 * 16) { 0xFF3366CC.toInt() }
        SoftBlur.blurPixels(px, 16, 16, 3)
        // 边缘用 clamp-to-edge 续，所以整幅图不该出现任何偏色/暗边
        val first = px[0]
        assertThat(px.all { it == first }).isTrue()
        assertThat(red(px, 0)).isEqualTo(0x33)
    }

    @Test
    fun `一条竖线被抹成一片渐变`() {
        val w = 32
        val h = 8
        val px = lineOnBlack(w, h, w / 2)
        // 未处理时：只有第 16 列是亮的
        assertThat((0 until w).count { red(px, w * 4 + it) > 0 }).isEqualTo(1)

        SoftBlur.blurPixels(px, w, h, 4)

        // 处理后：中线两侧都渗到了亮色，而且中心比两侧亮（真正的过渡，不是硬阶梯）
        val row = (0 until w).map { red(px, w * 4 + it) }
        assertThat(row.count { it > 2 }).isGreaterThan(4)
        assertThat(row[w / 2]).isGreaterThan(row[w / 2 + 2])
        assertThat(row[w / 2 + 2]).isGreaterThan(0)
    }

    @Test
    fun `半径越大，硬边的过渡带越宽`() {
        val w = 160
        // h 不能太小：blurPixels 会把半径夹到短边的 1/3，h=8 时上限只有 2，
        // 半径 3 和 6 会被夹成同一个值，看起来像「调大没用」
        val h = 48
        /*
         * 用「左黑右白」的硬边来量：糊完中间会出现一段 0 < v < 255 的过渡带，
         * 带宽就是模糊半径的直接度量。
         *
         * 不用「中心有多亮」：那条线只有 1px 宽，半径一过 2 就铺满整个视窗，
         * 中心亮度立刻饱和成同一个数 —— 看起来像「半径不起作用」。
         */
        fun band(radius: Int): Int {
            val px = IntArray(w * h) { i -> if (i % w < w / 2) black else white }
            SoftBlur.blurPixels(px, w, h, radius)
            return (0 until w).count {
                val v = red(px, w * (h / 2) + it)
                v in 1..254
            }
        }
        val r1 = band(1)
        val r3 = band(3)
        val r6 = band(6)
        assertThat(r1).isGreaterThan(0)
        assertThat(r3).isGreaterThan(r1)
        assertThat(r6).isGreaterThan(r3)
    }

    @Test
    fun `能量摊开而不是消失`() {
        // 盒式滤波是加权平均：中心那点亮必须出现在周围，不能凭空蒸发
        val w = 32
        val h = 8
        val px = lineOnBlack(w, h, w / 2)
        assertThat(red(px, w * 4 + w / 2)).isEqualTo(255)
        SoftBlur.blurPixels(px, w, h, 3)
        val row = (0 until w).map { red(px, w * 4 + it) }
        assertThat(row[w / 2]).isLessThan(255)
        // 两侧 6 列以内必须真的被点亮了
        assertThat(row[w / 2 - 4]).isGreaterThan(0)
        assertThat(row[w / 2 + 4]).isGreaterThan(0)
    }

    @Test
    fun `半径为零时不动像素`() {
        val px = lineOnBlack(16, 16, 8)
        val copy = px.copyOf()
        SoftBlur.blurPixels(px, 16, 16, 0)
        assertThat(px.toList()).isEqualTo(copy.toList())
    }

    @Test
    fun `半径超过短边三分之一时按上限夹住，不会越界`() {
        val w = 12
        val h = 12
        val px = IntArray(w * h) { white }
        // 不抛异常、不出现下标越界，就是这条要证明的全部
        SoftBlur.blurPixels(px, w, h, 500)
        assertThat(px[0]).isEqualTo(white)
    }
}
