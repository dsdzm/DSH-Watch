package com.dshwatch.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import com.dshwatch.core.Logx

/**
 * 低版本（Android 12 以下）的高斯模糊。
 *
 * ## 为什么是 RenderScript
 *
 * `Modifier.blur` 走 RenderEffect，API 31 才有；在 26~30 上是**空操作** ——
 * 不报错、不生效，比没有这个功能更难排查。而本项目 minSdk 26，覆盖的手表
 * 里相当一部分停在 Android 9/10。
 *
 * 能选的只有三条路：
 *
 *  - `ScriptIntrinsicBlur`（RenderScript）：系统自带的优化内建核，API 17 起就有。
 *    对 256×256 的位图，手表 CPU 上单次约十几毫秒；
 *  - 自己写 RenderScript / 手写卷积：要么得带 `.rs` 编译链，要么纯 Kotlin 逐像素
 *    跑 5×5 核，256² × 25 次采样在表上要几百毫秒；
 *  - 降采样放大冒充模糊：本项目的 `customBackgroundFrost` 已经在用（读成 120px），
 *    但它只是「读得粗」，画面会留下块状边缘，和真高斯不是一个观感。
 *
 * 所以走第一条。代价是 RenderScript 在 API 31 起被标记废弃 —— 只在 26~30 上调用，
 * 31+ 仍然用 `Modifier.blur`，不会被废弃影响。
 *
 * ## 为什么是「离线算一次」而不是每帧算
 *
 * Compose 拿不到「自己背后已经画了什么」，逐卡实时模糊要求每张卡各一个离屏
 * 缓冲 + RenderEffect，手表上十几张卡直接掉帧。这里的做法是：把背景图在
 * [CustomBackground] 里解码出来时顺手糊掉，之后每帧只是画一张已经糊好的图，
 * 运行时成本为零。这也是它能跑在 512MB 的机器上的原因。
 */
internal object RsBlur {

    private const val TAG = "RsBlur"

    /**
     * `ScriptIntrinsicBlur` 的硬上限。
     *
     * 官方文档写明 0 < radius ≤ 25，超出会抛 `RSIllegalArgumentException`。
     * 需要更大的等效半径时先降采样，而不是硬塞一个更大的数（见调用方）。
     */
    private const val MAX_RADIUS = 25f

    /**
     * 复用一个 RenderScript 上下文。
     *
     * 创建一次要 10~40ms（要加载驱动、编译内建核），比模糊本身还贵。
     * 只在「用户开了开关 + 低版本 + 有背景图」时才惰性创建，常驻内存约 2~3MB。
     * 用户关掉开关后进程里留着也无妨 —— 下次打开不用再付这笔创建开销。
     */
    @Volatile
    private var engine: RenderScript? = null

    private val lock = Any()

    /**
     * 对 [src] 做高斯模糊，返回一张新的 ARGB_8888 位图。
     *
     * @param radiusPx 模糊半径，单位是 **src 自己的像素**。
     * @return 失败时返回 null（调用方退回未模糊的图），**不抛异常**。
     *
     * ## 为什么要吞掉异常
     *
     * RenderScript 依赖厂商驱动。少数 ROM（尤其是精简过的电视/手表固件）上
     * 创建上下文会直接抛 `RSRuntimeException`，甚至 `UnsatisfiedLinkError`。
     * 这类失败发生在「画背景」这条路上 —— 让它冒出去就是整个界面白屏，
     * 而用户真正想要的只是「背景糊一点」。所以这里一律降级。
     */
    @Suppress("DEPRECATION")
    fun blur(context: Context, src: Bitmap, radiusPx: Float): Bitmap? {
        if (radiusPx <= 0.1f) return null
        if (src.width < 2 || src.height < 2) return null
        return runCatching {
            val rs = engineOf(context)
            // ScriptIntrinsicBlur 只接受 ARGB_8888（U8_4）。JPEG 解码在部分机型上
            // 会给出 RGB_565，直接喂进去是未定义行为
            val input = if (src.config == Bitmap.Config.ARGB_8888) {
                src
            } else {
                src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            }
            val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)

            val inAlloc = Allocation.createFromBitmap(
                rs,
                input,
                Allocation.MipmapControl.MIPMAP_NONE,
                Allocation.USAGE_SCRIPT,
            )
            val outAlloc = Allocation.createTyped(rs, inAlloc.type)
            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            try {
                blur.setRadius(radiusPx.coerceIn(0.1f, MAX_RADIUS))
                blur.setInput(inAlloc)
                blur.forEach(outAlloc)
                outAlloc.copyTo(output)
            } finally {
                blur.destroy()
                inAlloc.destroy()
                outAlloc.destroy()
                if (input !== src) input.recycle()
            }
            output
        }.onFailure {
            Logx.w(TAG, "高斯模糊失败，退回未模糊的背景：${it.javaClass.simpleName} ${it.message}")
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun engineOf(context: Context): RenderScript {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            // applicationContext：RenderScript 会持有它，传 Activity 就是一次泄漏
            val created = RenderScript.create(context.applicationContext)
            engine = created
            return created
        }
    }
}
