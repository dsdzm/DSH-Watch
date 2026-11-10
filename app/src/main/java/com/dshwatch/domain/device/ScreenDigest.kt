package com.dshwatch.domain.device

/**
 * 屏幕元素的一个矩形（单位：像素，原点在屏幕左上角）。
 *
 * 刻意不用 android.graphics.Rect：这一层要能在 JVM 单测里跑，
 * 而 android.jar 在单测里只是桩，构造 Rect 会抛 "not mocked"。
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun isEmpty(): Boolean = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** 与另一块区域求交，用于把手势落点收进可视范围。 */
    fun intersect(other: Bounds): Bounds = Bounds(
        maxOf(left, other.left),
        maxOf(top, other.top),
        minOf(right, other.right),
        minOf(bottom, other.bottom),
    )
}

/**
 * 一个可操作（或值得读）的界面元素。
 *
 * 字段都是读屏的产物，动作层用它决定「点哪儿、能不能点」。
 * [fingerprint] 在动作前重新核对一遍：屏幕已经翻页时，
 * 编号会指向另一个东西，照着旧编号点下去就是误操作。
 */
data class UiElement(
    val id: Int,
    val role: String,
    val label: String,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val viewId: String? = null,
    /** 是否位于某个可滚动容器内：格式化时缩进一级，模型据此知道「这是列表里的条目」 */
    val inList: Boolean = false,
) {
    /** 稳定身份：包名不进这里（同一屏内不需要），用角色 + 文本 + 位置。 */
    fun fingerprint(): String = "$role|$label|$left|$top|$right|$bottom"

    val left: Int get() = bounds.left
    val top: Int get() = bounds.top
    val right: Int get() = bounds.right
    val bottom: Int get() = bounds.bottom
}

/**
 * 读屏结果 → 模型能读的紧凑文本。
 *
 * ## 为什么不是截图
 *
 * 手表屏幕 1.4 寸、圆形，截图缩到模型能处理的尺寸后文字基本不可辨；
 * 而 UI 树是结构化的，编号 + 文本反而更准。能看图的模型也不需要截图 ——
 * 它要的是「第 4 项是什么」，不是「像素长什么样」。
 *
 * ## 为什么不是原始 XML
 *
 * 一屏 UI 树的 XML 动辄几十 KB，手表上每轮都要发一遍，
 * token 与延迟都受不了。这里只保留「有文字」或「能操作」的节点，
 * 布局容器全部折叠。
 *
 * ## 格式
 *
 * ```
 * 微信 · 6 项 · 键盘收起
 * [1] 按钮 返回
 * [2] 输入框 "搜索" 已聚焦
 * [3] 列表 可滚动
 *   [4] 张三 · 你好
 * ```
 *
 * 编号只在本屏有效，动作后重新读屏会重新编号 —— 这是刻意的：
 * 编号是「这次看到的东西」的临时句柄，不是持久 ID。
 */
object ScreenDigest {

    /** 单个标签最多这么多字。手表上一行放不下更多，模型也不需要。 */
    const val LABEL_CAP = 40

    /** 整屏文本预算。超出就截断并告诉模型「还有 N 项」。 */
    const val BUDGET = 1600

    /**
     * @param appLabel 前台应用名（读不到包名时退回包名本身）
     * @param elements 已按阅读顺序排好的元素
     * @param detail 是否附坐标。默认不附 —— 坐标只在「按元素点不到、要按坐标点」时才需要
     * @param screen 屏幕矩形。空屏时要用它告诉模型「屏幕多大、中心在哪」，
     *               否则模型想按坐标点也无从估起（它看不到屏幕）
     */
    fun format(
        appLabel: String?,
        packageName: String?,
        keyboardShown: Boolean?,
        elements: List<UiElement>,
        detail: Boolean = false,
        budget: Int = BUDGET,
        note: String? = null,
        screen: Bounds? = null,
    ): String {
        val title = appLabel?.takeIf { it.isNotBlank() }
            ?: packageName?.takeIf { it.isNotBlank() }
            ?: "未知界面"
        val header = buildString {
            append(title)
            append(" · ").append(elements.size).append(" 项")
            if (keyboardShown == true) append(" · 键盘弹出")
            note?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }

        if (elements.isEmpty()) {
            /*
             * 空屏不是「没事可说」—— 它恰恰是模型最需要帮忙的时候：
             * 游戏、自绘 Canvas 读不到控件，唯一的出路是按坐标点。
             * 不给屏幕尺寸和中心点，模型只能瞎猜一个数字。
             */
            val geo = screen?.let {
                "屏幕 ${it.width}×${it.height}，中心点 (${it.centerX}, ${it.centerY})。"
            }.orEmpty()
            return header + "\n（这一屏没有可读的文字或可操作的控件。可能还在加载，" +
                "也可能是游戏 / 自绘界面。$geo" +
                "可以稍后再读一次；要按位置点就用 device_act action=tap 带 x、y，" +
                "没把握就让用户接手。）"
        }

        val lines = ArrayList<String>(elements.size)
        var used = header.length + 1
        var hidden = 0
        for (el in elements) {
            val line = render(el, detail)
            if (used + line.length + 1 > budget && lines.isNotEmpty()) {
                hidden = elements.size - lines.size
                break
            }
            lines += line
            used += line.length + 1
        }
        return buildString {
            append(header)
            lines.forEach { append('\n').append(it) }
            if (hidden > 0) {
                append("\n…另有 ").append(hidden).append(" 项未显示")
                append("（可用 device_act action=scroll 往下翻）")
            }
        }
    }

    private fun render(el: UiElement, detail: Boolean): String = buildString {
        if (el.inList) append("  ")
        append('[').append(el.id).append("] ").append(el.role)
        val text = el.label.trim()
        when {
            el.password -> {
                append(" 🔒（内容不读取，也不代填）")
            }
            text.isNotEmpty() -> {
                if (el.role == "输入框") {
                    append(" \"").append(clip(text)).append('"')
                } else {
                    append(' ').append(clip(text))
                }
                if (el.focused) append(" · 已聚焦")
                if (el.checked != null) append(if (el.checked) " · 已选" else " · 未选")
            }
            else -> {
                if (el.focused) append(" · 已聚焦")
                if (el.checked != null) append(if (el.checked) " · 已选" else " · 未选")
            }
        }
        if (el.scrollable && el.role != "列表") append(" · 可滚动")
        if (detail) {
            append(" (").append(el.left).append(',').append(el.top)
            append('-').append(el.right).append(',').append(el.bottom).append(')')
        }
    }

    fun clip(text: String): String {
        val one = text.replace('\n', ' ').replace(Regex("\\s{2,}"), " ").trim()
        return if (one.length <= LABEL_CAP) one else one.take(LABEL_CAP - 1) + "…"
    }

    /** 元素在不在可视区域内（完全在屏幕外的条目读出来也没用，点了也白点）。 */
    fun visible(bounds: Bounds, screenWidth: Int, screenHeight: Int): Boolean {
        if (bounds.isEmpty()) return false
        return bounds.intersects(Bounds(0, 0, screenWidth, screenHeight))
    }
}
