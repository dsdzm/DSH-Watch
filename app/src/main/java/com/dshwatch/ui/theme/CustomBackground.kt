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
 * 好处是**每帧成本为零** —— 逐帧高斯模糊在手表上要整屏离屏渲染，
 * 512MB 的机器上直接掉帧。
 *
 * 这条路径和「玻璃高斯模糊」不是一回事：前者是常驻的观感处理，后者是用户
 * 显式打开的选项。低版本（API 26~30）上启用后者时，这里会改读 256px 再用
 * [RsBlur] 糊一次 —— 那是真高斯，只是算在解码阶段而不是每帧。
 */
object CustomBackground {

    private const val DIR = "background"

    /** 保存时的长边上限。412×502 的屏用不到更大，再大只是白占内存 */
    private const val SAVE_MAX = 1080

    /** 虚化时读进内存的短边像素数。100 上下在 412px 的屏上放大 3~4 倍，边缘全软 */
    private const val VEIL_READ_WIDTH = 120

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
     * API 28 起有 `ImageDecoder`（本 App minSdk 26，所以下面要判断版本），它能按目标尺寸
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
     * @param blurRadiusFraction 大于 0 时，读完再用 RenderScript 糊一次
     *   （半径 = 图宽 × 该比例，见 [BLUR_RADIUS_FRACTION]）。
     *
     *   这是**唯一**的模糊路径：`Modifier.blur` 要 API 31 且看 ROM 脸色，
     *   RenderScript 在精简固件上会直接抛异常 —— 两者都会安静地退回原图，
     *   用户看到的就是「开关开了、画面没变」。见 [SoftBlur] 的说明。
     *
     *   代价是**读的宽度要够**（[BG_BLUR_READ_WIDTH]）：拿 120px 的图去糊只会糊成
     *   一团色块，看不出「高斯」的形状；512px 糊完再放大，边缘才是柔和过渡。
     */
    suspend fun load(
        context: Context,
        name: String,
        frost: Boolean,
        blurRadiusFraction: Float = 0f,
        /**
         * 目标读取宽度。0 = 按 [frost] / [blurRadiusFraction] 自动选。
         *
         * 卡片磨砂（[FROST_READ_WIDTH]）传 192：它要的是「糊到没有细节」，
         * 和背景层那个 256 不是同一个用途，不能共用一条自动规则。
         */
        readWidth: Int = 0,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = file(context, name)
        if (!f.isFile) return@withContext null
        val want = when {
            readWidth > 0 -> readWidth
            // 要糊的时候，frost 那个 120px 反而成了障碍：先读细一点
            blurRadiusFraction > 0f -> BG_BLUR_READ_WIDTH
            frost -> VEIL_READ_WIDTH
            else -> SAVE_MAX
        }
        val bitmap = decodeScaled(f, want) ?: return@withContext null
        if (blurRadiusFraction <= 0f) return@withContext bitmap.asImageBitmap()

        /*
         * 半径按**图宽**换算，不是按屏幕 dp。
         *
         * 图最后要被拉满到屏宽，所以「图宽的比例」和「屏幕上的观感」是一回事；
         * 用 dp 反而要在两处各自换算，读宽一改就错位。
         */
        val radius = bitmap.width * blurRadiusFraction
        val blurred = SoftBlur.gaussian(bitmap, radius)
        if (blurred !== bitmap) bitmap.recycle()
        blurred.asImageBitmap()
    }

    /**
     * 按目标宽度解码。
     *
     * 两步而不是一步：[inSampleSize] 只能取 2 的幂，落到 320 时实际会读出 540 ——
     * 而模糊的耗时与像素数成正比，540² 比 320² 贵近三倍。所以先粗降采样，
     * 再精确缩放到 [want]（`createScaledBitmap` 是一次双线性拷贝，几十微秒）。
     *
     * 顺带让半径换算变准：`图宽 × 比例` 只有在图宽真的等于 want 时才可控。
     */
    private fun decodeScaled(f: File, want: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= want) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(f.path, opts) ?: return null
        if (raw.width <= want) return raw

        val h = (raw.height * want.toFloat() / raw.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, want, h, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    /** 删掉一张背景图。删不掉也无所谓 —— 设置里已经不再指向它了 */
    fun delete(context: Context, name: String) {
        if (name.isBlank()) return
        runCatching { file(context, name).delete() }
    }

    private const val TAG = "CustomBackground"
}
