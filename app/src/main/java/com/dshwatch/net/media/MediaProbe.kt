package com.dshwatch.net.media

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 读一下媒体文件的元信息。
 *
 * 只为了在气泡上写「1280×704 · 3.2 MB」这种一行字 —— 手表上用户没法点开属性面板，
 * 这行字是他判断「这条视频值不值得点开播放」的唯一依据。
 *
 * 全程 runCatching：某些 ROM 的 MediaMetadataRetriever 对非常规封装的 mp4 会抛
 * RuntimeException（不是 IOException），不该因为读不出时长就让整条消息变成失败。
 */
object MediaProbe {

    data class Info(
        val width: Int = 0,
        val height: Int = 0,
        val durationMs: Long = 0L,
        val mime: String = "",
    )

    fun of(file: File): Info {
        if (!file.isFile) return Info()
        val name = file.name.lowercase()
        return when {
            name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mov") ->
                video(file)
            else -> image(file)
        }
    }

    private fun image(file: File): Info = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        Info(
            width = opts.outWidth.coerceAtLeast(0),
            height = opts.outHeight.coerceAtLeast(0),
            mime = opts.outMimeType.orEmpty(),
        )
    }.getOrElse { Info() }

    private fun video(file: File): Info = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            Info(
                width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                mime = "video/mp4",
            )
        } finally {
            runCatching { r.release() }
        }
    }.getOrElse { Info(mime = "video/mp4") }
}
