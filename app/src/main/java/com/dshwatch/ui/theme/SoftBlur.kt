package com.dshwatch.ui.theme

import android.graphics.Bitmap

/**
 * 纯 Kotlin 的高斯模糊（3 次盒式滤波近似）。
 *
 * ## 为什么不用系统那两条路
 *
 * 这个功能前后走过三条路，前两条都**在真机上静默失效**：
 *
 * | 做法 | 结果 |
 * |---|---|
 * | `Modifier.blur`（RenderEffect） | 只在 API 31+ 有效，且部分手表 ROM 的合成器根本不支持 —— 不报错、画面不动 |
 * | `ScriptIntrinsicBlur`（RenderScript） | 依赖厂商驱动。精简过的 Wear 固件里 `RenderScript.create()` 直接抛，被 catch 之后退回**原图**，用户看到的就是"开关开了、什么都没变" |
 * | 纯 Kotlin 盒式滤波 | 没有任何外部依赖，只吃 CPU |
 *
 * 用户连着两个版本报"没变化"，根子就在这里：模糊是"尽力而为"，失败时安静地
 * 退回原图，界面上没有任何迹象。功能要么生效、要么明确不生效，不能靠运气。
 *
 * ## 为什么是盒式而不是真高斯核
 *
 * 一次盒式（均值）滤波把阶跃边缘变成一个方窗；**连做三次**，按中心极限定理，
 * 结果已经非常接近高斯（这也是 Stack Blur / 移动端的通行做法）。
 * 而盒式可以用「滑窗累加」做到每像素 O(1)：不管半径多大，单次遍历的加法次数
 * 都是固定的。真高斯核 5×5 就是 25 倍采样，320² 的图上直接上百毫秒。
 *
 * ## 成本
 *
 * 320×400 的图，3 次 × 2 方向 = 6 遍，约 77 万像素加法，手表 CPU 上 10~25ms。
 * 而且**只在解码时算一次**：运行时每帧只是 `drawImage` 一张已经糊好的图，
 * 零额外开销 —— 这是它能跑在 512MB 机器上的原因。
 */
internal object SoftBlur {

    /** 三次盒式 ≈ 一次高斯。两次太"方"，四次收益已经看不见 */
    private const val PASSES = 3

    /**
     * 对 [src] 做高斯模糊。
     *
     * @param radiusPx 半径，单位是 **src 自己的像素**。
     * @return 新位图；半径过小或图太小时**原样返回 [src]**（调用方不要 recycle）。
     */
    fun gaussian(src: Bitmap, radiusPx: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3 || radiusPx < 1f) return src

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        blurPixels(px, w, h, radiusPx.toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 就地对一片 ARGB 像素做模糊。
     *
     * 单独抽出来是为了**能在 JVM 上验证**：`Bitmap` 在普通单元测试里造不出来，
     * 而「模糊到底有没有把像素抹开」恰恰是这个功能唯一需要证明的事 ——
     * 前两版就是因为没法测，才会带着「静默返回原图」的 bug 发出去两次。
     */
    internal fun blurPixels(px: IntArray, w: Int, h: Int, radius: Int) {
        if (w < 3 || h < 3 || radius < 1) return
        val r = radius.coerceIn(1, (minOf(w, h) / 3).coerceAtLeast(1))
        val back = IntArray(w * h)
        repeat(PASSES) {
            horizontal(px, back, w, h, r)
            vertical(back, px, w, h, r)
        }
    }

    /** 横向一趟。窗口滑到图外时用边缘像素续（等价于 clamp-to-edge） */
    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0
            var rr = 0
            var gg = 0
            var bb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += (p ushr 24) and 0xFF
                rr += (p ushr 16) and 0xFF
                gg += (p ushr 8) and 0xFF
                bb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] =
                    ((a / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val into = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += ((into ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                rr += ((into ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                gg += ((into ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                bb += (into and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** 纵向一趟。索引跨度是 w，其余与 [horizontal] 一致 */
    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0
            var rr = 0
            var gg = 0
            var bb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                a += (p ushr 24) and 0xFF
                rr += (p ushr 16) and 0xFF
                gg += (p ushr 8) and 0xFF
                bb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] =
                    ((a / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val into = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += ((into ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                rr += ((into ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                gg += ((into ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                bb += (into and 0xFF) - (out and 0xFF)
            }
        }
    }
}
