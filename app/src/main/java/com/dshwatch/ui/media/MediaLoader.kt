package com.dshwatch.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 会话里的图片加载。
 *
 * 为什么不用 Coil：
 *  - 这个项目只在一处需要「把本地文件或一个 URL 变成缩略图」，引入一个图片库
 *    （连同它的 okio、dispatcher、生命周期感知）换来的功能这里全用不上；
 *  - 手表上最贵的是内存，自己写反而能把「缓存几张、多大、什么时候回收」掐死在手里。
 *
 * 三条策略：
 *  1. **按目标尺寸降采样**：一张 1280×704 的图直接解出来是 3.6MB，
 *     解到 240px 宽只要 130KB。列表里滚动时这个差别就是流畅与掉帧的差别。
 *  2. **内存 LRU 只留 8 张**：再多会把会话页顶到 OOM（`largeHeap=false`）。
 *  3. **解码在 IO 线程**：`BitmapFactory` 是同步阻塞的，放主线程必然卡首帧。
 */
object MediaLoader {

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** 键是「来源@目标尺寸」，同一个文件在列表和全屏时是两份不同尺寸的缓存 */
    private val memory = object : LruCache<String, ImageBitmap>(8) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    /** 同一张图可能被列表和查看器同时请求，合并成一次解码 */
    private val inFlight = mutableMapOf<String, Any>()

    suspend fun load(source: String, targetPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        if (source.isBlank()) return@withContext null
        val key = "$source@$targetPx"
        memory.get(key)?.let { return@withContext it }

        val lock = synchronized(inFlight) { inFlight.getOrPut(key) { Any() } }
        synchronized(lock) {
            memory.get(key)?.let { return@withContext it }
            val bmp = runCatching {
                val raw = if (source.startsWith("http")) {
                    fetchBytes(source)
                } else {
                    val f = File(source)
                    if (f.isFile) f.readBytes() else null
                } ?: return@withContext null
                decodeScaled(raw, targetPx)
            }.getOrNull()

            synchronized(inFlight) { inFlight.remove(key) }
            bmp?.let { memory.put(key, it) }
            bmp
        }
    }

    /**
     * 取视频首帧当封面。
     *
     * 手表上没有"自动播放"这回事：真播起来 5 秒视频掉电比看 5 分钟文字还狠。
     * 所以列表里只画一帧 + 一个播放角标，点了才交给系统播放器。
     */
    suspend fun videoFrame(path: String, targetPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        val key = "$path@frame@$targetPx"
        memory.get(key)?.let { return@withContext it }
        val bmp = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                // 取 0.1s 而不是 0s：很多编码器首帧是纯黑
                val frame = r.getFrameAtTime(100_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: r.frameAtTime
                frame?.let { scale(it, targetPx) }
            } finally {
                runCatching { r.release() }
            }
        }.getOrNull()
        bmp?.let { memory.put(key, it) }
        bmp
    }

    /** 预清空（切换会话时调用，避免上一会话的图占着缓存） */
    fun clear() {
        memory.evictAll()
    }

    private fun fetchBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            // 上限 12MB：再大就不是这个界面该处理的东西了
            val body = resp.body ?: return null
            if (body.contentLength() > 12L * 1024 * 1024) return null
            return body.bytes()
        }
    }

    private fun decodeScaled(bytes: ByteArray, targetPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > targetPx * 2) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // 手表上没有透明度需求，省一半内存
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return scale(decoded, targetPx)
    }

    private fun scale(src: Bitmap, targetPx: Int): ImageBitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= targetPx) return src.asImageBitmap()
        val ratio = targetPx.toFloat() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled.asImageBitmap()
    }
}
