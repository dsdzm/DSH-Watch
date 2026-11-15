package com.dshwatch.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.dshwatch.core.Logx
import com.dshwatch.domain.theme.MonetColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 种子的来源。
 *
 * ## 为什么手表上「跟随壁纸」需要一个兜底
 *
 * `WallpaperManager` 在 Wear OS 上经常是空的：表盘不是壁纸，很多 ROM 直接
 * 返回 null 或抛 `SecurityException`（读壁纸要存储权限，手表上根本没人给过）。
 * 如果只认壁纸，用户打开这个开关会看到**界面一点没变** —— 功能"存在但无效"，
 * 比没有更糟。
 *
 * 所以这里按顺序找，并且把"最后用的是谁"明确告诉界面：
 *
 *  1. **自定义背景图**（用户自己塞进来的照片，最贴合"跟着我的图变"）；
 *  2. **系统壁纸**（手机/少数支持壁纸的表上有效）；
 *  3. **都不用** —— 界面显示默认种子，并且文案说清楚为什么。
 *
 * ## 为什么读 48×48 就够
 *
 * 取色要的是"这张图整体是什么色调"，不是细节。缩到 48×48 之后一次分析
 * 只有 2300 个像素，Lab 转换加起来不到 1 毫秒；而读原图在手表上会直接
 * OOM（一张 4000×3000 的照片解码就是 48MB）。
 */
object SeedSource {

    /** 分析用的缩略图边长。48 已足够稳定，再大只是浪费 CPU */
    private const val THUMB = 48

    enum class Source {
        /** 用户设置的自定义背景图 */
        BACKGROUND,

        /** 系统壁纸 */
        WALLPAPER,

        /** 都没取到 */
        NONE,
    }

    data class Resolution(val seed: Int?, val source: Source) {
        val ok: Boolean get() = seed != null
    }

    /**
     * 解析当前应该用的种子。
     *
     * @param background 设置里的自定义背景文件名（空串 = 没设）
     */
    suspend fun resolve(context: Context, background: String): Resolution =
        withContext(Dispatchers.IO) {
            if (background.isNotBlank()) {
                fromBackground(context, background)?.let {
                    return@withContext Resolution(it, Source.BACKGROUND)
                }
            }
            fromWallpaper(context)?.let {
                return@withContext Resolution(it, Source.WALLPAPER)
            }
            Resolution(null, Source.NONE)
        }

    /** 自定义背景图的主色 */
    fun fromBackground(context: Context, name: String): Int? = runCatching {
        val f = CustomBackground.file(context, name)
        if (!f.isFile) return null
        val bmp = decodeThumb(f) ?: return null
        val seed = MonetColors.seedFromPixels(pixels(bmp))
        bmp.recycle()
        seed
    }.onFailure { Logx.w(TAG, "背景取色失败：${it.message}") }.getOrNull()

    /**
     * 系统壁纸的主色。
     *
     * 三种失败都要吞掉：没有壁纸（null）、没权限（SecurityException）、
     * ROM 返回一张 1×1 的占位图（像素全透明 → `seedFromPixels` 返回 null）。
     */
    fun fromWallpaper(context: Context): Int? = runCatching {
        val wm = WallpaperManager.getInstance(context)
        val drawable = wm.drawable ?: return null
        val bmp = Bitmap.createBitmap(THUMB, THUMB, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, THUMB, THUMB)
        drawable.draw(canvas)
        val seed = MonetColors.seedFromPixels(pixels(bmp))
        bmp.recycle()
        seed
    }.onFailure { Logx.w(TAG, "壁纸取色失败：${it.message}") }.getOrNull()

    /**
     * 按采样率读一张小图。
     *
     * 用 `inSampleSize` 而不是先整张解码再缩 —— 后者在手表上就是 OOM 的同义词。
     */
    private fun decodeThumb(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= THUMB * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        // 采样后可能仍有几百像素：再画一次到 48×48，保证分析量恒定
        if (bmp.width <= THUMB && bmp.height <= THUMB) return bmp
        val small = Bitmap.createScaledBitmap(bmp, THUMB, THUMB, true)
        if (small !== bmp) bmp.recycle()
        return small
    }

    private fun pixels(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        return out
    }

    private const val TAG = "SeedSource"
}

/**
 * 种子的一次性缓存。
 *
 * 为什么需要：取色要在 IO 线程做，而主题是**每次重组都会读**的地方。
 * 没有缓存的话，每次切换明暗、每次改字号都会重新解码一张图 —— 手表上
 * 那就是肉眼可见的一卡。
 *
 * 只记一份（当前背景）。用户换背景图的概率远低于重组次数，
 * 用「背景文件名」当 key 就够了；壁纸的变化（换表盘壁纸）只能靠重启感知，
 * 这在手表上恰好是常态 —— 没人会开着 App 换壁纸。
 */
internal object SeedCache {

    private var key: String = "\u0000"
    private var seed: Int? = null

    /** 只有和当前背景对得上时才返回缓存，否则 null（让调用方去算） */
    fun remembered(background: String): Int? =
        if (background == key) seed else null

    fun put(background: String, value: Int?, source: SeedSource.Source) {
        key = background
        seed = value
        lastSource = source
    }

    /** 上次真正用到的来源。设置页拿它写「正在跟随…」的说明 */
    var lastSource: SeedSource.Source = SeedSource.Source.NONE
        private set
}
