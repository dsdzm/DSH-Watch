package com.dshwatch.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 工作空间文件的类型判断与导出。
 *
 * 从 `WorkspaceScreen` 里抽出来，因为聊天页的「产物卡片」也要能直接把刚生成的文件
 * 分享出去 —— 用户看到产物的第一反应就是「我要拿到它」，
 * 不该逼他先跳到工作空间页再翻一遍。
 */

/** 真正能用 WebView 渲染的类型（其它文本走代码视图，不算「预览」） */
private val previewableExtensions = setOf("html", "htm", "png", "jpg", "jpeg", "gif", "webp", "svg")

fun extensionOf(path: String): String = path.substringAfterLast('.', "").lowercase()

fun canPreview(path: String): Boolean = extensionOf(path) in previewableExtensions

fun isHtml(path: String): Boolean = extensionOf(path) == "html" || extensionOf(path) == "htm"

/** 图片类型（预览页要按位图加载而不是走 WebView 的 HTML 路径） */
fun isImage(path: String): Boolean =
    extensionOf(path) in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/**
 * MIME 表。
 * 分享出去的 type 决定了接收方能不能正确打开 —— 用错（比如一律 text/plain）
 * 会让微信、邮件客户端把它们当纯文本处理，图片和 HTML 直接乱掉。
 */
fun mimeOf(fileName: String): String = when (extensionOf(fileName)) {
    "html", "htm" -> "text/html"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "css" -> "text/css"
    "js" -> "application/javascript"
    "json" -> "application/json"
    "md" -> "text/markdown"
    "txt" -> "text/plain"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "csv" -> "text/csv"
    "xml" -> "application/xml"
    else -> "*/*"
}

/** 导出结果。至少要让界面能告诉用户「成没成、为什么没成」 */
sealed interface ShareResult {
    data object Ok : ShareResult
    data class Fail(val reason: String) : ShareResult
}

/**
 * 通过 FileProvider 把文件分享给别的 App。
 *
 * 三层保障，缺一层都会有用户遇到「点了没反应」：
 *  1. 优先直接拿 URI（文件在 `files/workspace` 或 `Android/data/<pkg>/files/workspace` 时都能命中，
 *     见 `res/xml/file_paths.xml`）；
 *  2. 拿不到就**复制一份到 `cache/share/`** 再分享 —— 用户手工改过存放位置、
 *     或者文件在别的目录里时，也不至于直接失败；
 *  3. 失败原因一路返回给调用方去提示，不再静默吞掉。
 */
fun shareFile(context: Context, file: File?): ShareResult {
    if (file == null || !file.exists()) return ShareResult.Fail("文件不存在或还没生成")

    val authority = context.packageName + ".files"
    val uri = resolveShareUri(context, authority, file)
        ?: return ShareResult.Fail("无法为「${file.name}」生成分享地址（文件不在可导出目录内）")

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeOf(file.name)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(
            Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ShareResult.Ok
    } catch (e: ActivityNotFoundException) {
        ShareResult.Fail("这台设备没有可以接收分享的应用")
    } catch (t: Throwable) {
        ShareResult.Fail("分享失败：${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}")
    }
}

/** 直接拿 URI；失败则复制到缓存再拿。都失败返回 null。 */
private fun resolveShareUri(context: Context, authority: String, file: File): android.net.Uri? {
    runCatching { FileProvider.getUriForFile(context, authority, file) }
        .getOrNull()
        ?.let { return it }

    val cached = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        target
    }.getOrNull() ?: return null

    return runCatching { FileProvider.getUriForFile(context, authority, cached) }.getOrNull()
}
