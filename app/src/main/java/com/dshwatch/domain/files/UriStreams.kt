package com.dshwatch.domain.files

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * 读一个「用户选中的文件」。
 *
 * ## 为什么要单独一个口子
 *
 * 选文件现在有两条来源：系统选择器给 `content://`，内置浏览器给 `file://`。
 * 下游七个地方都要读它（附件、背景、插件、归档、皮肤、配图……），如果每处
 * 都写 `contentResolver.openInputStream(uri)`，`file://` 这一路就会在部分 ROM 上
 * 出问题：`openInputStream` 对 file scheme 的实现是「转给 ContentProvider」，
 * 而精简固件里的 provider 未必肯接 —— 返回 null，用户看到「读不了这个文件」，
 * 明明文件就在那儿、权限也有。
 *
 * 所以：`file://` 直接开 `File.inputStream()`（我们自己 `canRead()` 验过），
 * 其余 scheme 才交给 ContentResolver。
 *
 * ## 失败返回 null，不抛
 *
 * 调用方全都在 IO 协程里，没有一处能优雅处理异常；而「读不到」在手表上很常见
 * （权限、文件被删、网盘离线）。统一返回 null，由调用方翻成一句人话。
 */
object UriStreams {

    fun open(context: Context, uri: Uri): InputStream? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val f = File(path)
            if (!f.isFile) return null
            return runCatching { f.inputStream() }.getOrNull()
        }
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /**
     * 选中文件的名字。
     *
     * `file://` 直接取文件名；`content://` 要反查 `OpenableColumns.DISPLAY_NAME`
     * （系统选择器不会把文件名放在 URI 里）。查不到就退回 URI 最后一段 ——
     * 界面需要的只是一个能认出来的标签，不是精确的文件名。
     */
    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).name }?.takeIf { it.isNotBlank() }
        }
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
