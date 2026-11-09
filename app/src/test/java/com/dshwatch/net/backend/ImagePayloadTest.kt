package com.dshwatch.net.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片压缩的纯逻辑部分。
 *
 * 真正的缩放与编码要走 android.graphics，只能在设备上跑；这里守住最容易算错的一处：
 * 解码采样率。它算小了会 OOM（手表堆只有 128–192MB），算大了会让模型拿到一张糊图。
 */
class ImagePayloadTest {

    @Test
    fun `长边正好等于目标时不采样`() {
        assertEquals(1, ImagePayload.sampleSize(512, 512, 512))
        assertEquals(1, ImagePayload.sampleSize(512, 300, 512))
    }

    @Test
    fun `小图不放大`() {
        assertEquals(1, ImagePayload.sampleSize(200, 120, 512))
        assertEquals(1, ImagePayload.sampleSize(1, 1, 512))
    }

    @Test
    fun `四千万像素的照片按 2 的幂降到目标量级`() {
        // 4000×3000：/4 后是 1000×750，落在 [512, 1024) 区间
        assertEquals(4, ImagePayload.sampleSize(4000, 3000, 512))
    }

    @Test
    fun `采样后仍然不小于目标`() {
        val cases = listOf(3000 to 4000, 1920 to 1080, 1080 to 1920, 720 to 720, 513 to 513)
        for ((w, h) in cases) {
            val s = ImagePayload.sampleSize(w, h, 512)
            val longEdge = maxOf(w, h) / s
            // 采样后长边一定 >= 目标（否则细节被白扔），且 < 2×目标（否则内存吃太多）
            assertTrue("$w×$h 采样 $s 后长边 $longEdge < 512", longEdge >= 512)
            assertTrue("$w×$h 采样 $s 后长边 $longEdge >= 1024", longEdge < 1024)
        }
    }

    @Test
    fun `异常尺寸不崩`() {
        assertEquals(1, ImagePayload.sampleSize(0, 0, 512))
        assertEquals(1, ImagePayload.sampleSize(-1, 100, 512))
        assertEquals(1, ImagePayload.sampleSize(100, 100, 0))
    }

    @Test
    fun `长边上限是 512 一个视觉块`() {
        assertEquals(512, ImagePayload.LONG_EDGE)
    }
}
