package com.dshwatch.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.dshwatch.domain.device.DeviceControlHub

/**
 * 操控期间常驻的悬浮条。
 *
 * ## 为什么需要它
 *
 * AI 在读屏点按别的 App 时，DSH 本体已经退到后台 —— 用户眼前只剩一个
 * 「屏幕自己在动」的画面。没有提示的话，这跟中了木马没有区别：
 * 不知道现在在做什么、也不知道怎么让它停。
 *
 * 所以这条悬浮条只承担两件事，且必须一眼看懂：
 *  1. **现在在做什么**（一句话，例如「点击 · 发送」）；
 *  2. **怎么停**（右侧的停止按钮，点一下立刻停）。
 *
 * ## 为什么不用 Compose
 *
 * 它不是 Activity 的一部分，没有 ViewTreeLifecycleOwner；为了一个 40dp 的条
 * 去搭 SavedStateRegistry + CompositionLocalProvider，比手写两个 TextView 贵得多。
 * 而且它必须在无障碍服务里存活 —— 服务没有 Compose 的宿主。
 *
 * ## 手势穿透
 *
 * 注入点击时，悬浮条自己也会收到触摸：它一旦吃掉手势，AI 就永远点不到下面。
 * [setPassThrough] 在注入的那几十毫秒里把窗口设成不可触摸，
 * 结束后恢复 —— 代价是这段时间内停止按钮点不动，
 * 但一次注入只有 100–400ms，而 AI 的每一步之间都有间隔。
 */
class DeviceOverlay(private val service: AccessibilityService) : DeviceControlHub.OverlayPort {

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var intentView: TextView? = null
    private var appView: TextView? = null
    private var stopView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private val windowManager: WindowManager? =
        service.getSystemService(WindowManager::class.java)

    val isShown: Boolean get() = root != null

    override fun show() = onMain {
        if (root != null) return@onMain
        val wm = windowManager ?: return@onMain
        val view = build() ?: return@onMain
        val lp = layoutParams()
        runCatching { wm.addView(view, lp) }.onFailure {
            // 某些 ROM 会拒绝无障碍 overlay（尤其省电模式下）。不崩，
            // 只让操控继续 —— 没有悬浮条也能干活，只是少了提示。
            return@onMain
        }
        root = view
        params = lp
    }

    override fun hide() = onMain {
        val view = root ?: return@onMain
        root = null
        params = null
        runCatching { windowManager?.removeView(view) }
    }

    /** 更新「正在做什么」。主线程安全，任意线程可调。 */
    override fun update(intent: String, appLabel: String?) = onMain {
        show()
        intentView?.text = intent.ifBlank { "正在操作设备" }
        appView?.text = appLabel?.takeIf { it.isNotBlank() } ?: ""
        appView?.visibility = if (appLabel.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** 注入手势期间临时让窗口不可触摸，避免自己把点击吃掉。 */
    fun setPassThrough(pass: Boolean) = onMain {
        val wm = windowManager ?: return@onMain
        val view = root ?: return@onMain
        val lp = params ?: return@onMain
        val flags = if (pass) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags == lp.flags) return@onMain
        lp.flags = flags
        runCatching { wm.updateViewLayout(view, lp) }
    }

    /** 停止按钮被按下时的回调，由服务接到 [DeviceControlHub.requestStop]。 */
    var onStop: (() -> Unit)? = null

    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): View? {
        val ctx = service
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(7), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF012161B.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            elevation = dp(4).toFloat()
        }

        // 录制红点 + 「AI 正在操作」的固定标识
        val dot = TextView(ctx).apply {
            text = "●"
            setTextColor(0xFFFF5A5F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(0, 0, dp(8), 0)
        }
        card.addView(dot)

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        intentView = TextView(ctx).apply {
            text = "正在操作设备"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        appView = TextView(ctx).apply {
            text = ""
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        texts.addView(intentView)
        texts.addView(appView)
        card.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // 停止：方块图标。不用矢量图 —— 一个字符就够，还省体积
        stopView = TextView(ctx).apply {
            text = "■"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(0x33FFFFFF)
            }
        }
        stopView?.setOnClickListener {
            // 先给自己一句反馈，再交给中枢；否则用户会觉得点了没反应
            intentView?.text = "正在停止…"
            onStop?.invoke()
        }
        card.addView(
            stopView,
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(8) },
        )

        return card
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val dm = service.resources.displayMetrics
        return WindowManager.LayoutParams(
            // 圆表盘两侧会被裁，宽度留到 82%
            (dm.widthPixels * 0.82f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            /*
             * 只用 TYPE_ACCESSIBILITY_OVERLAY（API 22 起就有），不要退回
             * TYPE_SYSTEM_OVERLAY —— 后者要求 SYSTEM_ALERT_WINDOW，
             * 无障碍服务拿不到那个权限，加窗会直接抛 BadTokenException。
             */
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (18 * dm.density).toInt()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
