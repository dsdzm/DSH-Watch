package com.dshwatch.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dshwatch.core.Logx
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 自定义背景图：导入、落盘、读取。
 *
 * ## 为什么导入时要拷一份
 *
 * SAF 给的 `content://` URI 只在**本次授权**内有效：重启、清理缓存、或者用户
 * 在相册里把那张图移走之后，这个 URI 就打不开了。如果设置里存 URI，用户会遇到
 * 「昨天还好好的背景，今天变成空白」—— 而且完全不知道为什么。
 *
 * 所以导入时就把图**解码 → 降采样 → 重新压成 JPEG** 存进 `files/background/`。
 * 之后只认自己目录里的文件，跟相册、权限、URI 生命周期都无关。
 *
 * ## 为什么是「解码再编码」而不是直接拷字节
 *
 * 手表内存小，相册里一张 4000×3000 的照片解码出来就是 48 MB —— 直接 OOM。
 * 这里在**解码阶段**就限死尺寸（[ImageDecoder.setTargetSize]），
 * 峰值内存只有目标尺寸那么多。顺带解决另一件事：`ImageDecoder` 会自动应用
 * EXIF 旋转，竖拍的照片不会躺倒。
 *
 * ## 虚化是「读得粗」而不是「画得糊」
 *
 * `customBackgroundFrost` 打开时，读文件用的是很大的 `inSampleSize`：
 * 一张 1080 宽的图被读成 100 多像素，再用双线性放大铺满屏幕，观感就是磨砂。
 * 好处是**每帧成本为零** —— 实时高斯模糊在手表上要整屏离屏渲染，
 * 512MB 的机器上直接掉帧。
 */
object CustomBackground {

    private const val DIR = "background"

    /** 保存时的长边上限。412×502 的屏用不到更大，再大只是白占内存 */
    private const val SAVE_MAX = 1080

    /** 虚化时读进内存的短边像素数。100 上下在 412px 的屏上放大 3~4 倍，边缘全软 */
    private const val FROST_READ_WIDTH = 120

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun exists(context: Context, name: String): Boolean =
        name.isNotBlank() && file(context, name).isFile

    /**
     * 把用户选中的图导入进来。
     *
     * @return 新文件名（不含目录）。失败时抛异常，由调用方翻成人话。
     */
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeBounded(context, uri)
        val target = dir(context).apply { mkdirs() }
        // 文件名带时间戳：换图时是新文件，写成功之后再删旧的 ——
        // 反过来的话「写到一半失败」会让设置指向一个坏文件
        val name = "bg-${System.currentTimeMillis()}.jpg"
        val out = File(target, name)
        FileOutputStream(out).use { stream ->
            // 背景不需要 alpha（JPEG 也不支持），质量 88 在手表上看不出压缩痕迹
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) {
                throw IllegalStateException("图片压缩失败")
            }
        }
        bitmap.recycle()
        if (out.length() <= 0L) throw IllegalStateException("图片是空的")
        name
    }

    /**
     * 解码一张用户选的图，长边不超过 [SAVE_MAX]，并应用 EXIF 旋转。
     *
     * API 28 起有 `ImageDecoder`（本 App 的 minSdk 就是 28），它能按目标尺寸
     * 直接解码 —— 不需要「先整张读进内存再缩」。少数 ROM 上它偶发失败
     * （HEIF、色彩空间怪异的图），所以留一条 BitmapFactory 的退路：
     * 那条路上没有 EXIF 旋转，但至少能用。
     */
    private fun decodeBounded(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longSide = max(w, h)
                    if (longSide > SAVE_MAX) {
                        val scale = SAVE_MAX.toFloat() / longSide
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1),
                        )
                    }
                    // 软件位图：后面要 compress，硬件位图不能直接读像素
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.onFailure { Logx.w(TAG, "ImageDecoder 失败，退回 BitmapFactory：${it.message}") }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IllegalStateException("打不开这张图")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("这不是一张图片")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SAVE_MAX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalStateException("读取图片失败")
    }

    /**
     * 读出用于绘制的位图。
     *
     * @param frost 虚化时用很大的采样率读，尺寸只有 100 多像素，放大后自然发虚
     */
    suspend fun load(
        context: Context,
        name: String,
        frost: Boolean,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = file(context, name)
        if (!f.isFile) return@withContext null

        val want = if (frost) FROST_READ_WIDTH else SAVE_MAX
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return@withContext null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= want) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
    }

    /** 删掉一张背景图。删不掉也无所谓 —— 设置里已经不再指向它了 */
    fun delete(context: Context, name: String) {
        if (name.isBlank()) return
        runCatching { file(context, name).delete() }
    }

    /** 清掉目录里所有旧背景（切图之后调用，避免攒一堆没人用的照片） */
    fun prune(context: Context, keep: String) {
        val files = runCatching { dir(context).listFiles() }.getOrNull() ?: return
        files.forEach { f ->
            if (f.isFile && f.name != keep) runCatching { f.delete() }
        }
    }

    private const val TAG = "CustomBackground"
}
