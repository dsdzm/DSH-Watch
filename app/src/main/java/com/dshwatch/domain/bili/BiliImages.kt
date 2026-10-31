package com.dshwatch.domain.bili

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 评论配图。
 *
 * ## 为什么是「程序画」而不是内置几张图
 *
 * 内置图片要么有版权问题，要么一张几 MB 把 APK 撑大。而评论配图本来就不需要
 * 复杂内容 —— 一张写着话的卡片就够用，还能跟着用户的文案变。
 *
 * ## 为什么固定 720×720
 *
 * B站 评论区的展示宽度在手机上约 360dp，图再大只是白传；720 在 2x 屏上正好清晰，
 * JPEG 压到 85 质量大约 40–80KB，手表流量也能接受。
 */
object BiliImages {

    private const val SIZE = 720
    private const val QUALITY = 85

    /** 用户没写文案时按日期轮换，避免每条评论配图一模一样 */
    private val FALLBACK_TEXTS = listOf(
        "今天也在白吃白喝",
        "我先躺了，你随意",
        "这活我不干了",
        "摸鱼中，勿扰",
        "饭点了，散会",
        "看完了，继续躺",
        "不装了，我在划水",
    )

    fun pickFallback(seed: Int): String = FALLBACK_TEXTS[Math.floorMod(seed, FALLBACK_TEXTS.size)]

    /**
     * 画一张「摸鱼卡」。
     *
     * 手写 Canvas 而不是引 Coil/Glide 之类：这里只有「黑底 + 几行字 + 一条鱼」，
     * 为了它多一个图片库不划算。
     */
    fun card(text: String, footer: String): ByteArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0F1013"))

        // 外框：一圈浅灰细线，像一张小卡片
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#2A2D33")
        }
        canvas.drawRoundRect(RectF(24f, 24f, SIZE - 24f, SIZE - 24f), 36f, 36f, border)

        // 顶部标识
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6F757E")
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        canvas.drawText("DSH-WATCH", 64f, 100f, small)

        drawFish(canvas, 560f, 78f)

        // 正文：自动换行，最多 4 行，超了截断
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2F2F2")
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val lines = wrap(text.ifBlank { pickFallback(0) }, body, SIZE - 128f).take(4)
        var y = SIZE / 2f - (lines.size - 1) * 34f
        lines.forEach { line ->
            val w = body.measureText(line)
            canvas.drawText(line, (SIZE - w) / 2f, y, body)
            y += 68f
        }

        // 底部：日期 / 署名
        val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A9099")
            textSize = 26f
        }
        val fw = foot.measureText(footer)
        canvas.drawText(footer, (SIZE - fw) / 2f, SIZE - 72f, foot)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** 一条很简的鱼：椭圆身子 + 三角尾巴 + 一个白点眼睛 */
    private fun drawFish(canvas: Canvas, cx: Float, cy: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A8CFF")
            style = Paint.Style.FILL
        }
        val body = RectF(cx - 46f, cy - 20f, cx + 34f, cy + 20f)
        canvas.drawOval(body, paint)
        val tail = Path().apply {
            moveTo(cx + 30f, cy)
            lineTo(cx + 62f, cy - 22f)
            lineTo(cx + 62f, cy + 22f)
            close()
        }
        canvas.drawPath(tail, paint)
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F1013") }
        canvas.drawCircle(cx - 30f, cy - 5f, 4f, eye)
    }

    /** 按字符宽度折行。中文没有空格，只能按字量；英文单词会被硬切，但这里基本都是中文 */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val max = maxWidth.toInt().coerceAtLeast(1)
        val out = ArrayList<String>()
        val line = StringBuilder()
        text.replace("\n", " ").forEach { ch ->
            line.append(ch)
            if (paint.measureText(line.toString()) > max) {
                line.deleteCharAt(line.length - 1)
                if (line.isNotEmpty()) out += line.toString()
                line.clear()
                line.append(ch)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    // ------------------------------------------------------------------
    // 用户自己选的那张
    // ------------------------------------------------------------------

    /**
     * 相册图的位置。
     *
     * 放 app 私有目录而不是缓存目录：缓存会被系统清掉，而用户选图是「选一次、
     * 以后每次都带这张」，清掉之后就会静默变成不带图。
     */
    fun localFile(context: Context): File = File(File(context.filesDir, "bili"), "attached.jpg")

    /**
     * 把相册里选中的图落盘。
     *
     * 顺带做一次缩放：用户可能选了张 4000px 的原图，直传要几十秒，
     * 还会被 B站 的图床压缩得比我们自己压的还糊。
     */
    fun saveLocal(context: Context, uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return false

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 1440)
        }
        val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return false
        val scaled = if (src.width > 1440) {
            val h = (src.height * 1440f / src.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, 1440, h, true).also { src.recycle() }
        } else {
            src
        }
        val file = localFile(context)
        file.parentFile?.mkdirs()
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        scaled.recycle()
        true
    }.getOrDefault(false)

    fun loadLocal(context: Context): ByteArray? {
        val f = localFile(context)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var longest = maxOf(w, h)
        while (longest / 2 >= target) {
            sample *= 2
            longest /= 2
        }
        return sample
    }
}
