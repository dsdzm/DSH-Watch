package com.dshwatch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dshwatch.domain.device.Bounds
import com.dshwatch.domain.device.DeviceControlHub
import com.dshwatch.domain.device.ScreenDigest
import com.dshwatch.domain.device.UiElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 设备操控的无障碍服务。
 *
 * 只做三件事：**读屏、动手、报状态**。不理解自然语言，也不决定做什么 ——
 * 决策在模型，落点在工具层，这里是最下面那层「手」。
 *
 * ## 为什么必须是无障碍
 *
 * Android 上要「看到别的 App 的界面并点它」，只有两条路：
 *  - 无障碍（读 UI 树 + 注入手势）：免 root、免 Shizuku，用户自己开关；
 *  - Shizuku / ADB：能力更强，但要连电脑或装额外 App。
 * 手表上第二条路基本不现实（没有 USB、也装不了 Shizuku），所以只有无障碍。
 *
 * ## 读取是「按需」的
 *
 * 服务常驻，但**不会持续截取屏幕**。只有工具层调 [capture] 时才读一次，
 * 读完就丢。这既是隐私边界（不落盘、不缓存），也是省电所需。
 */
class DeviceA11yService : AccessibilityService() {

    private var overlay: DeviceOverlay? = null

    /** 最近一次看到的前台包名，用于在读屏前就知道「现在在哪个 App」。 */
    @Volatile
    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DeviceControlHub.attachService(this)
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            // 只要窗口变化事件：内容变化事件在表盘上每秒几十次，纯属耗电
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 120
        }
        overlay = DeviceOverlay(this).also { o ->
            o.onStop = {
                DeviceControlHub.requestStop("用户点了停止")
                o.update("正在停止…", null)
            }
            DeviceControlHub.attachOverlay(o)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank() && pkg != packageName) foregroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        // 系统要求打断的是「语音/振动反馈」，本服务没有这类反馈，
        // 操控任务不受影响 —— 与 dsh-Go 的取舍一致。
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        overlay?.hide()
        overlay?.let { DeviceControlHub.detachOverlay(it) }
        overlay = null
        DeviceControlHub.detachService(this)
        if (instance === this) instance = null
    }

    // ==================================================================
    // 读屏
    // ==================================================================

    /** 一次读屏的完整结果。[nodes] 只在本次动作内有效，不要跨调用持有。 */
    class Capture(
        val packageName: String?,
        val appLabel: String?,
        val elements: List<UiElement>,
        val nodes: Map<Int, AccessibilityNodeInfo>,
        val text: String,
    )

    /**
     * 读当前屏幕。
     *
     * @param detail 是否附坐标。默认不带 —— 模型按编号点就够了，
     *               坐标只在「元素找不到、只能按位置点」时才有意义。
     */
    suspend fun capture(detail: Boolean = false): Capture = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow
        val dm = resources.displayMetrics
        val screen = Bounds(0, 0, dm.widthPixels, dm.heightPixels)
        val pkg = root?.packageName?.toString() ?: foregroundPackage

        val collected = ArrayList<UiElement>(48)
        val nodes = HashMap<Int, AccessibilityNodeInfo>(48)
        var next = 0

        // 手写遍历：不用递归函数是为了能在中途放弃（节点数上限），
        // 也为了把「父节点可点击」这一信息带下去（决定要不要单独列文本）。
        fun walk(node: AccessibilityNodeInfo, depth: Int, inList: Boolean, parentClickable: Boolean) {
            if (depth > 24 || collected.size >= 60) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val b = Bounds(rect.left, rect.top, rect.right, rect.bottom)
            val visible = node.isVisibleToUser && !b.isEmpty() && b.intersects(screen)
            val interactive = node.isClickable || node.isLongClickable ||
                node.isEditable || node.isScrollable || node.isCheckable
            val text = nodeText(node)

            if (visible && (interactive || (text.isNotEmpty() && !parentClickable))) {
                next += 1
                collected += UiElement(
                    id = next,
                    role = roleOf(node),
                    label = if (interactive) text.ifEmpty { mergedText(node) } else text,
                    bounds = b,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    focused = node.isFocused,
                    password = node.isPassword,
                    scrollable = node.isScrollable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    viewId = node.viewIdResourceName?.substringAfterLast('/'),
                    inList = inList,
                )
                nodes[next] = node
            }

            val childInList = inList || node.isScrollable
            val childParentClickable = parentClickable || node.isClickable
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                walk(child, depth + 1, childInList, childParentClickable)
            }
        }

        if (root != null) walk(root, 0, false, false)

        val label = appLabelOf(pkg)
        val keyboard = isKeyboardShown()
        val text = ScreenDigest.format(
            appLabel = label,
            packageName = pkg,
            keyboardShown = keyboard,
            elements = collected,
            detail = detail,
            note = if (collected.isEmpty()) "这一屏读不到控件" else null,
        )
        Capture(pkg, label, collected, nodes, text)
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (d.isNotEmpty()) return d
        return node.hintText?.toString()?.trim().orEmpty()
    }

    /**
     * 合并后代文本。
     *
     * 一个可点击的行（聊天列表里的「张三 / 你好」）通常没有自己的 text，
     * 文字都在几个子 TextView 里。不合并的话，模型只会看到「[4] 按钮」。
     * 合并时跳过可交互后代 —— 那些是行内按钮，属于单独一项。
     */
    private fun mergedText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 3 || sb.length > 90) return
            for (i in 0 until n.childCount) {
                val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
                if (c.isClickable || c.isEditable) continue
                val t = nodeText(c)
                if (t.isNotEmpty() && !sb.contains(t)) {
                    if (sb.isNotEmpty()) sb.append(" · ")
                    sb.append(t)
                }
                walk(c, depth + 1)
            }
        }
        walk(node, 0)
        return sb.toString()
    }

    private fun roleOf(node: AccessibilityNodeInfo): String = when {
        node.isEditable -> "输入框"
        node.isScrollable -> "列表"
        node.isCheckable -> "勾选框"
        node.isClickable || node.isLongClickable -> "按钮"
        else -> "文字"
    }

    private fun isKeyboardShown(): Boolean = runCatching {
        windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
    }.getOrDefault(false)

    private fun appLabelOf(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    // ==================================================================
    // 动作
    // ==================================================================

    class ActResult(val ok: Boolean, val message: String)

    /**
     * 点 / 长按一个编号。
     *
     * 关键安全设计：**编号不直接当索引用**。屏幕可能已经翻页，
     * 同一个编号现在指向另一个按钮 —— 那就点错了。所以先用上次读屏的
     * 指纹（角色 + 文本 + 位置）在新一屏里找回同一个元素，找不到就要求重读。
     */
    suspend fun tap(id: Int, long: Boolean): ActResult = withContext(Dispatchers.Main) {
        val prev = DeviceControlHub.lastScreen
            ?: return@withContext ActResult(false, "还没读屏。先调 device_screen 看一眼当前界面。")
        if (prev.stale()) {
            return@withContext ActResult(false, "上次读屏已经超过 30 秒，屏幕可能变了。先重新读屏。")
        }
        val wanted = prev.byId(id)
            ?: return@withContext ActResult(
                false,
                "编号 $id 不在上次读屏结果里（上次只有 ${prev.elements.size} 项）。",
            )

        val fresh = capture(detail = false)
        val target = fresh.elements.firstOrNull { it.fingerprint() == wanted.fingerprint() }
            ?: return@withContext ActResult(
                false,
                "屏幕已经变了，编号 $id 现在指向别的位置。请重新读屏再决定。",
            )
        val node = fresh.nodes[target.id]

        val performed = when {
            node == null -> false
            long && node.isLongClickable -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            !long && node.isClickable -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else -> clickAncestor(node, target.bounds, long)
        }
        if (performed) return@withContext ActResult(true, (if (long) "已长按" else "已点击") + "「${target.label.ifBlank { target.role }}」")

        // 节点层面没有可点的动作，退化成手势落点
        val p = gesturePoint(target.bounds)
            ?: return@withContext ActResult(false, "「${target.label}」不在可视区域内，点不到。")
        val ok = gestureTap(p.first, p.second, long)
        if (ok) ActResult(true, (if (long) "已长按" else "已点击") + "「${target.label.ifBlank { target.role }}」")
        else ActResult(false, "点击没有送达（可能被系统拦截或界面正在切换）。先读屏看看现在的状态。")
    }

    private fun clickAncestor(
        node: AccessibilityNodeInfo,
        bounds: Bounds,
        long: Boolean,
    ): Boolean {
        var parent = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth < 4) {
            val r = Rect()
            parent.getBoundsInScreen(r)
            val able = if (long) parent.isLongClickable else parent.isClickable
            if (able && r.contains(bounds.centerX, bounds.centerY)) {
                return parent.performAction(
                    if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK
                    else AccessibilityNodeInfo.ACTION_CLICK,
                )
            }
            parent = runCatching { parent?.parent }.getOrNull()
            depth++
        }
        return false
    }

    /** 把元素中心收进屏幕内 —— 半露在边缘的条目，中心点可能在屏幕外。 */
    private fun gesturePoint(bounds: Bounds): Pair<Int, Int>? {
        val dm = resources.displayMetrics
        val screen = Bounds(0, 0, dm.widthPixels, dm.heightPixels)
        val clipped = bounds.intersect(screen)
        if (clipped.isEmpty()) return null
        return clipped.centerX to clipped.centerY
    }

    suspend fun type(id: Int, text: String, submit: Boolean): ActResult = withContext(Dispatchers.Main) {
        val prev = DeviceControlHub.lastScreen
            ?: return@withContext ActResult(false, "先调 device_screen 找到输入框。")
        val wanted = prev.byId(id)
            ?: return@withContext ActResult(false, "编号 $id 不在上次读屏结果里。")
        if (wanted.password) {
            return@withContext ActResult(
                false,
                "这是密码 / 验证码输入框，不代填。请把设备交给用户输入（device_handoff）。",
            )
        }
        val fresh = capture(detail = false)
        val target = fresh.elements.firstOrNull { it.fingerprint() == wanted.fingerprint() }
            ?: return@withContext ActResult(false, "屏幕变了，编号 $id 已经不是那个输入框。重新读屏。")
        val node = fresh.nodes[target.id]
            ?: return@withContext ActResult(false, "找不到这个输入框的控件，可能它只是看起来像输入框。")

        if (!node.isEditable) return@withContext ActResult(false, "编号 $id 不是输入框。")
        if (!node.isFocused) runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        if (!ok) return@withContext ActResult(false, "写入失败，这个输入框可能不接受直接设值。")

        if (submit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            return@withContext ActResult(true, "已输入并提交。")
        }
        ActResult(true, "已输入「${text.take(30)}」。")
    }

    suspend fun scroll(id: Int?, direction: String): ActResult = withContext(Dispatchers.Main) {
        val forward = direction == "down" || direction == "right"
        val prev = DeviceControlHub.lastScreen
        val node = if (id != null && prev != null) {
            val wanted = prev.byId(id)
            if (wanted == null) null else {
                val fresh = capture(detail = false)
                val t = fresh.elements.firstOrNull { it.fingerprint() == wanted.fingerprint() }
                t?.let { fresh.nodes[it.id] }
            }
        } else null

        if (node != null && node.isScrollable) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return@withContext ActResult(true, "已向${if (forward) "下" else "上"}滚动。")
        }

        // 节点滚不动（自绘列表常见）：用手势
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val cx = w / 2
        val cy = h / 2
        val dy = (h * 0.28f).toInt()
        val ok = gestureSwipe(
            cx, cy + if (forward) dy else -dy,
            cx, cy + if (forward) -dy else dy,
        )
        if (ok) ActResult(true, "已向${if (forward) "下" else "上"}滚动。")
        else ActResult(false, "滚动没有生效，可能已经到头了。")
    }

    suspend fun global(key: String): ActResult = withContext(Dispatchers.Main) {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return@withContext ActResult(false, "不认识的按键：$key")
        }
        if (performGlobalAction(action)) ActResult(true, "已按 $key。")
        else ActResult(false, "系统拒绝了 $key。")
    }

    // ---------------- 手势 ----------------

    private suspend fun gestureTap(x: Int, y: Int, long: Boolean): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = if (long) ViewConfiguration.getLongPressTimeout().toLong() + 150 else 50L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 320)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * 发一个手势并等它结束。
     *
     * 发送期间把悬浮条设成不可触摸：否则手势会落在悬浮条上，
     * 表现为「AI 点了但什么都没发生」，而且极难排查。
     */
    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            overlay?.setPassThrough(true)
            /*
             * 用户点停止会把这次调用连协程一起取消，回调就不会再来 ——
             * 不在这里复位的话，悬浮条会永久停在「不可触摸」，
             * 表现为停止按钮再也点不动（只能杀进程）。
             */
            cont.invokeOnCancellation { overlay?.setPassThrough(false) }
            val callback = object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    overlay?.setPassThrough(false)
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    overlay?.setPassThrough(false)
                    if (cont.isActive) cont.resume(false)
                }
            }
            val sent = runCatching { dispatchGesture(gesture, callback, null) }.getOrDefault(false)
            if (!sent) {
                overlay?.setPassThrough(false)
                if (cont.isActive) cont.resume(false)
            }
        }

    companion object {
        @Volatile
        private var instance: DeviceA11yService? = null

        fun isConnected(): Boolean = instance != null

        fun component(context: Context) =
            ComponentName(context.packageName, DeviceA11yService::class.java.name)

        /** 用户在系统设置里打开了这一项没有（服务可能还没绑上）。 */
        fun isEnabled(context: Context): Boolean {
            val raw = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull() ?: return false
            val me = component(context)
            return raw.split(':').any {
                ComponentName.unflattenFromString(it.trim()) == me
            }
        }

        /** 跳到系统的无障碍设置页，让用户自己打开。 */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
