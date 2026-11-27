package com.dshwatch.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Random

/**
 * 磨砂玻璃的「颗粒」。
 *
 * ## 为什么需要它
 *
 * 玻璃底下真糊了之后，问题反而变得更明显：卡片的**面**是一块完全均匀的
 * 半透明色块。人眼判断「这是磨砂玻璃」不靠模糊 —— 模糊在卡片外面也能看到，
 * 它靠的是**面上那层细微的不均匀**。缺了颗粒，糊得再重也只是一块半透明塑料。
 *
 * 真玻璃的磨砂面是无数微小的凸起：受光处微微发亮、背光处微微发暗，
 * 相邻像素亮度差在 2~4/255 这个量级。所以这里就照这个量级铺一张噪点。
 *
 * ## 为什么是「一张小图平铺」
 *
 * 逐像素在 DrawScope 里算是不现实的（每帧几万个点）。预生成 64×64 的
 * ARGB 位图 + `TileMode.Repeated`，GPU 只做一次采样，成本等于一次普通填充。
 * 64 是肉眼分辨不出重复的阈值：在 400px 的表屏上是 6 个循环，
 * 噪点本身又没有形状，看不出接缝。
 *
 * 种子写死：每次启动的颗粒必须一模一样。随机种子会让「同一块卡片」
 * 在两次启动之间长得不一样，用户会觉得界面在闪。
 */
internal object GlassGrain {

    private const val SIZE = 64

    /** 想让它「看得出毛」就调这个：最大 12/255 ≈ 4.7% */
    private const val MAX_ALPHA = 12

    /**
     * 惰性构建：不进玻璃主题的用户一次都不会创建这张位图。
     *
     * 16 KB（64×64×4），比任何一次资源解码都便宜。
     */
    val brush: Brush by lazy {
        val rnd = Random(20261127L)
        val pixels = IntArray(SIZE * SIZE)
        for (i in pixels.indices) {
            /*
             * 三档分布，而不是连续随机：
             *  · 46% 完全透明 —— 玻璃本身已经有一层色，全铺满会显得脏
             *  · 30% 微亮（白）—— 受光的凸起
             *  · 24% 微暗（黑）—— 背光的凹陷
             *
             * 白多于黑，整体会亮一点点；这正好和「磨砂玻璃比清玻璃白一档」
             * 的观感一致，不需要再单独加一层白。
             */
            val r = rnd.nextInt(100)
            pixels[i] = when {
                r < 46 -> 0
                r < 76 -> ((rnd.nextInt(MAX_ALPHA) + 1) shl 24) or 0x00FFFFFF
                else -> (rnd.nextInt(MAX_ALPHA) + 1) shl 24
            }
        }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        Tiled(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }

    /**
     * 把一张固定尺寸的 Shader 直接当成 Brush 用。
     *
     * `Brush` 的公开工厂只覆盖线性/径向/扫描三种渐变，位图平铺要用
     * `ShaderBrush` 自己接一层。`createShader` 里返回同一个实例 ——
     * 平铺的缩放与位置由 Shader 自己决定，不需要按绘制尺寸重建。
     */
    private class Tiled(private val shader: Shader) : ShaderBrush() {
        override fun createShader(size: Size): Shader = shader
    }
}
