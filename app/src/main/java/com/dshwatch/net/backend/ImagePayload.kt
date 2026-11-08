package com.dshwatch.net.backend

import android.util.Base64
import java.io.File

/**
 * 把本地图片文件编成 `data:` URL。
 *
 * 为什么用内联 base64 而不是先上传再引用 URL：
 *  - 上传要多两步（拿上传地址 → PUT → 拿回地址），手表上多一次往返就多一次失败机会；
 *  - `/chat/completions` 对 `image_url` 的 data URL 是标准做法，主流服务端都认。
 *
 * 代价是请求体变大（base64 约 1.33 倍），所以**入库前就该压过**：
 * 附件落在 `files/media/` 时已经缩到长边 ≤1024、JPEG 85，
 * 单图通常 100–300KB，一张图换来的提示词增长可控。
 */
internal object ImagePayload {

    /** 小图上限：超过就放弃内联，避免把手表的请求体撑爆 */
    private const val MAX_BYTES = 6 * 1024 * 1024

    fun dataUrl(path: String): String? {
        if (path.isBlank()) return null
        return runCatching {
            val f = File(path)
            if (!f.isFile || f.length() <= 0 || f.length() > MAX_BYTES) return null
            val mime = when (f.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            "data:$mime;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
