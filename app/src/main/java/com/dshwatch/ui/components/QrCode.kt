package com.dshwatch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * 二维码。
 *
 * ## 为什么要画而不是贴图
 *
 * 手表屏幕只有 300~450px，二维码必须**按当前像素密度对齐到整数格**才清晰。
 * 先渲成 Bitmap 再缩放，格线会糊成一团，手机那头就扫不出来。
 * 这里直接按模块画方块，任何尺寸下都是硬边。
 *
 * ## 为什么用 Encoder 而不是 QRCodeWriter
 *
 * `QRCodeWriter` 会把结果缩放到指定的像素尺寸（内部要算整数倍），
 * 传给它的尺寸不合适就会多出白白的一圈或者干脆抛异常。
 * `Encoder` 返回的是**原始模块矩阵**，缩放交给 Canvas —— 想要多大就多大。
 *
 * 静区（quiet zone）固定 4 个模块，由 [paddingRatio] 换算成外边距：
 * B站 的扫码器对这个很敏感，切掉白边经常直接识别失败。
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(content) { buildMatrix(content) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(6.dp),
    ) {
        if (matrix == null) return@Box
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 4 模块静区 + 4 模块边距，占比 = 8 / (n + 8)
            val n = matrix.size
            val cell = size.minDimension / (n + 8f)
            val offset = cell * 4f
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (!matrix.bits[y * n + x]) continue
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offset + x * cell, offset + y * cell),
                        // +0.4 用来盖住相邻格之间的亚像素缝，否则整体会发灰
                        size = Size(cell + 0.4f, cell + 0.4f),
                    )
                }
            }
        }
    }
}

private class QrMatrix(val size: Int, val bits: BooleanArray)

private fun buildMatrix(content: String): QrMatrix? {
    if (content.isBlank()) return null
    return runCatching {
        val qr = Encoder.encode(
            content,
            ErrorCorrectionLevel.M,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                // 二维码内容是一串 URL，用 M（约 15% 容错）足够，
                // 再往上纠错等级会让模块变多、格子在手表上变小。
                EncodeHintType.MARGIN to 0,
            ),
        )
        val m = qr.matrix
        val n = m.width
        val bits = BooleanArray(n * n) { i -> m.get(i % n, i / n).toInt() == 1 }
        QrMatrix(n, bits)
    }.getOrNull()
}
