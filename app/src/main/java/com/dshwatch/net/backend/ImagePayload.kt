package com.dshwatch.net.backend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import com.dshwatch.core.Logx
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 把本地图片编成 `data:` URL —— **先压缩，再编码**。
 *
 * ## 为什么一定要压
 *
 * 视觉模型的计费按「图被切成的 512×512 块」算，不是按文件大小。一张 1024 长边的
 * 图是 4 块，512 长边是 1 块 —— 也就是同样的画面，**token 差 3 倍**。
 * 而手表屏幕只有 450px 上下，传 1024 是在为不存在的细节付费。
 *
 * 更现实的问题在请求体：base64 会把体积再放大 1.33 倍。原本 300KB 的截图，
 * 发出去就是 400KB；蓝牙共享网络或 eSIM 弱网下单张图要传十几秒，
 * 长对话里每轮都重传一次旧图，慢得像是卡住了。
 *
 * 所以这里统一做两件事：
 *  1. 长边缩到 [LONG_EDGE]（512，正好一个视觉块）；
 *  2. JPEG 质量 80 起，超过 [MAX_ENCODED_BYTES] 就往下调，直到 52 为止。
 *
 * 一张手表截图压缩后通常 20–60KB，是原来的 1/5 左右。
 *
 * ## 为什么要缓存
 *
 * 多轮对话里同一张图会被反复装进请求。压缩本身不贵，但每次都读盘 + 解码 + 缩放
 * 在手表上要几十毫秒，几轮下来就是可感知的电量与卡顿。这里按
 * （路径 + 大小 + 修改时间）缓存最近 3 张，够覆盖一轮里用户连续发的图。
 *
 * ## 为什么不压 GIF
 *
 * 动图压成 JPEG 就只剩第一帧。能看懂第一帧也好过什么都没有 —— 但更常见的情况是
 * 用户发的表情包，压了反而认不出来。所以 GIF 原样内联，只受源体积上限约束。
 */
internal object ImagePayload {

    /** 源文件上限。超过就放弃内联：解码它就可能把进程顶掉 */
    private const val MAX_SOURCE_BYTES = 24L * 1024 * 1024

    /**
     * 送给模型的长边上限。
     *
     * 512 不是随便选的：OpenAI 系视觉模型按 512×512 分块计费，
     * 512 长边正好 1 块；再大就翻倍，再小则文字开始糊。
     */
    internal const val LONG_EDGE = 512

    private const val QUALITY_START = 80
    private const val QUALITY_FLOOR = 52

    /** 压缩后的字节上限。超过就降质量，不降尺寸（尺寸已经很小了） */
    private const val MAX_ENCODED_BYTES = 200 * 1024

    private const val CACHE_SIZE = 3

    private class Packed(val bytes: ByteArray, val mime: String)

    private val cache = object : LinkedHashMap<String, Packed>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Packed>): Boolean =
            size > CACHE_SIZE
    }

    fun dataUrl(path: String): String? {
        if (path.isBlank()) return null
        return runCatching {
            val f = File(path)
            if (!f.isFile || f.length() <= 0 || f.length() > MAX_SOURCE_BYTES) return null

            val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}|$LONG_EDGE"
            val packed = synchronized(cache) { cache[key] } ?: run {
                val fresh = pack(f) ?: return null
                synchronized(cache) { cache[key] = fresh }
                fresh
            }
            "data:${packed.mime};base64," + Base64.encodeToString(packed.bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 压缩
    // ------------------------------------------------------------------

    private fun pack(file: File): Packed? {
        val ext = file.extension.lowercase()

        // 动图不重编码：压完就只剩一帧
        if (ext == "gif") {
            return Packed(file.readBytes(), "image/gif")
        }

        val size = readSize(file) ?: return null
        val longEdge = max(size.first, size.second)

        // 本来就够小：直接用原文件，避免「解码→重编码」的二次损失
        if (longEdge <= LONG_EDGE && file.length() <= MAX_ENCODED_BYTES) {
            val mime = when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            return Packed(file.readBytes(), mime)
        }

        val decoded = decodeSampled(file, size.first, size.second) ?: return null
        var bitmap: Bitmap? = decoded
        try {
            val upright = upright(decoded, file)
            val scaled = scaleTo(upright, LONG_EDGE)
            bitmap = scaled

            var quality = QUALITY_START
            var bytes = encodeJpeg(scaled, quality)
            while (bytes.size > MAX_ENCODED_BYTES && quality > QUALITY_FLOOR) {
                quality -= 12
                bytes = encodeJpeg(scaled, quality)
            }
            Logx.d(
                "Image",
                "压缩 ${file.name}：${file.length() / 1024}KB / ${size.first}×${size.second}" +
                    " → ${bytes.size / 1024}KB / ${scaled.width}×${scaled.height}（q=$quality）",
            )
            return Packed(bytes, "image/jpeg")
        } finally {
            bitmap?.recycle()
        }
    }

    /** 只读尺寸，不分配像素内存。读不出来（不是图 / 图坏了）返回 null。 */
    private fun readSize(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) {
            opts.outWidth to opts.outHeight
        } else {
            null
        }
    }

    /**
     * 按采样率解码。
     *
     * 直接 decode 一张 4000×3000 的照片要 48MB，手表上必 OOM。
     * 先按 2 的幂采样到目标尺寸的 1–2 倍，再做一次精确缩放 ——
     * 采样是解码期完成的，不占额外内存。
     */
    private fun decodeSampled(file: File, width: Int, height: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(width, height, LONG_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    /**
     * 修正 EXIF 方向。
     *
     * 相册里的照片十有八九带旋转标记，BitmapFactory 不认它 ——
     * 不转的话，模型看到的是一张躺着的图，文字全歪着。
     */
    private fun upright(source: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return source
        }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                .also { if (it !== source) source.recycle() }
        }.getOrDefault(source)
    }

    private fun scaleTo(source: Bitmap, target: Int): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= target) return source
        val ratio = target.toFloat() / longEdge
        val w = max(1, (source.width * ratio).roundToInt())
        val h = max(1, (source.height * ratio).roundToInt())
        return runCatching {
            Bitmap.createScaledBitmap(source, w, h, true)
                .also { if (it !== source) source.recycle() }
        }.getOrDefault(source)
    }

    /**
     * 编成 JPEG。
     *
     * PNG 截图常带透明通道，而 JPEG 没有 alpha —— 不铺白底的话，
     * 透明区域会被填成黑色，模型看到的是一张黑底图。
     */
    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val target = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
            }
        } else {
            bitmap
        }
        return try {
            ByteArrayOutputStream().use { out ->
                target.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } finally {
            if (target !== bitmap) target.recycle()
        }
    }

    /**
     * 解码采样率：让采样后的长边落在 [target, 2×target) 之间。
     *
     * 不能只看「大于 target 就除 2」—— 4000px 的图按 1 解码再缩，
     * 中间那一步就已经把内存吃完了。这里一路除到刚好不小于 target 为止。
     */
    internal fun sampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= target) sample *= 2
        return sample
    }
}
