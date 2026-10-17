package com.dshwatch.domain.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TOON（Token-Oriented Object Notation）编码器 —— 把 JSON 转成更省 token 的等价文本。
 *
 * 规则依据官方 format-overview（https://toonformat.dev/guide/format-overview.html），
 * 四种形式：
 *
 * ```
 * tags[3]: admin,ops,dev                          ← Inline（原语数组）
 * items[2]{sku,qty}:                              ← Tabular（均匀对象数组）
 *   A1,2
 *   B2,1
 * orders[2]{id,customer{name,country},total}:     ← 嵌套字段组折进表头
 *   1,Ada,DK,99
 * users[2:]{age,city}:                            ← Keyed tabular（值是均匀对象的对象）
 *   alice: 30,Berlin
 * bob:                                           ← List（其余数组，形状不规整时）
 *   - id: 1
 * ```
 *
 * ## 只做编码，不做解码
 *
 * 解码器的价值取决于"什么时候要读回来"。本项目的答案是：**不需要**。
 * 原文会落盘（见 [SaveTokenPlugin]），模型真要原始结构时用 `read_file` 取，
 * 那条路是可靠的；而自己写一个解码器，就是在没有任何对照测试的情况下
 * 保证"编码→解码"完全可逆 —— 那是错误的成本分配。
 *
 * ## 不支持的形状直接整体放弃
 *
 * List form（数组元素是对象但字段不规整、数组套数组）技术上可以实现，
 * 但它需要处理"tabular 表头挂在 hyphen 行上、行缩进两层的特例"，
 * 复杂度陡增而收益很小 —— 这种数据本来就更适合 JSON。
 * 遇到就抛 [Unsupported]，上层保持原 JSON 不动。
 *
 * 宁可**整体不转**，也不要转出一份"看起来像 TOON 但语义变了"的文本：
 * 模型不会告诉你数据有问题，它只会照着错的数据下结论。
 */
object ToonEncoder {

    /** 缩进单位。官方默认两个空格 */
    private const val INDENT = "  "

    /**
     * @return 编码结果；形状不受支持时返回 null
     */
    fun encode(root: JsonElement): String? = try {
        when (root) {
            is JsonObject -> if (root.isEmpty()) "" else objectLines(root, 0).joinToString("\n")
            is JsonArray -> rootArray(root)
            else -> primitive(root)
        }
    } catch (_: Unsupported) {
        null
    }

    /** 内部信号：这个形状不转 */
    private class Unsupported : Exception()

    // ---------------- 对象 ----------------

    private fun objectLines(obj: JsonObject, depth: Int): List<String> {
        val pad = INDENT.repeat(depth)
        val out = mutableListOf<String>()
        for ((key, value) in obj) {
            val k = encodeKey(key)
            when (value) {
                is JsonObject -> {
                    val shape = keyedShape(value)
                    when {
                        value.isEmpty() -> out += "$pad$k:"
                        shape != null -> {
                            out += "$pad$k[${value.size}:]{${fieldsText(shape)}}:"
                            value.forEach { (entryKey, entryValue) ->
                                out += INDENT.repeat(depth + 1) + encodeKey(entryKey) + ": " +
                                    cellsText(entryValue as JsonObject, shape)
                            }
                        }
                        else -> {
                            out += "$pad$k:"
                            out += objectLines(value, depth + 1)
                        }
                    }
                }
                is JsonArray -> out += arrayLines(pad, k, value, depth)
                else -> out += "$pad$k: ${primitive(value)}"
            }
        }
        return out
    }

    // ---------------- 数组 ----------------

    private fun arrayLines(pad: String, key: String, arr: JsonArray, depth: Int): List<String> {
        if (arr.isEmpty()) return listOf("$pad$key: []")
        val n = arr.size

        if (arr.all { it is JsonPrimitive }) {
            return listOf("$pad$key[$n]: " + arr.joinToString(",") { primitive(it) })
        }

        val shape = tabularShape(arr) ?: throw Unsupported()
        val lines = mutableListOf("$pad$key[$n]{${fieldsText(shape)}}:")
        val rowPad = INDENT.repeat(depth + 1)
        arr.forEach { element ->
            lines += rowPad + cellsText(element as JsonObject, shape)
        }
        return lines
    }

    private fun rootArray(arr: JsonArray): String {
        if (arr.isEmpty()) return "[]"
        val n = arr.size
        if (arr.all { it is JsonPrimitive }) {
            return "[$n]: " + arr.joinToString(",") { primitive(it) }
        }
        val shape = tabularShape(arr) ?: throw Unsupported()
        val lines = mutableListOf("[$n]{${fieldsText(shape)}}:")
        arr.forEach { element ->
            lines += INDENT + cellsText(element as JsonObject, shape)
        }
        return lines.joinToString("\n")
    }

    // ---------------- 形状 ----------------

    /** 表头里的一个位置：叶子，或嵌套字段组 */
    private sealed interface Field {
        val name: String

        data class Leaf(override val name: String) : Field

        data class Group(override val name: String, val children: List<Field>) : Field
    }

    /**
     * 一个对象的"可折叠形状"。
     *
     * null = 这个对象不能进表头：空对象、或含有数组字段（数组没法折成单元格）。
     * 允许任意深度的嵌套对象 —— 它只是把表头写长一点，行内按深度优先展开。
     */
    private fun shapeOf(obj: JsonObject): List<Field>? {
        if (obj.isEmpty()) return null
        return obj.map { (key, value) ->
            when (value) {
                is JsonPrimitive -> Field.Leaf(key)
                is JsonObject -> Field.Group(key, shapeOf(value) ?: return null)
                else -> return null
            }
        }
    }

    /** 均匀对象数组 → 表头形状；不满足条件返回 null */
    private fun tabularShape(arr: JsonArray): List<Field>? {
        if (arr.isEmpty()) return null
        val first = arr[0] as? JsonObject ?: return null
        val shape = shapeOf(first) ?: return null
        for (i in 1 until arr.size) {
            val obj = arr[i] as? JsonObject ?: return null
            // 字段集必须完全一致（顺序无关）。data class 的 equals 正好是递归比较
            if (shapeOf(obj) != shape) return null
        }
        return shape
    }

    /**
     * 值是均匀对象的对象 → keyed tabular。
     * 官方要求至少 2 个 entry，单个 entry 保持嵌套写法（否则 `[1:]` 比嵌套还长）。
     */
    private fun keyedShape(obj: JsonObject): List<Field>? {
        if (obj.size < 2) return null
        val first = obj.values.first() as? JsonObject ?: return null
        val shape = shapeOf(first) ?: return null
        for (value in obj.values) {
            val child = value as? JsonObject ?: return null
            if (shapeOf(child) != shape) return null
        }
        return shape
    }

    private fun fieldsText(fields: List<Field>): String = fields.joinToString(",") { field ->
        when (field) {
            is Field.Leaf -> field.name
            is Field.Group -> "${field.name}{${fieldsText(field.children)}}"
        }
    }

    /** 行内单元格：按表头的深度优先顺序展开 */
    private fun cellsText(obj: JsonObject, shape: List<Field>): String {
        val cells = mutableListOf<String>()
        collectCells(obj, shape, cells)
        return cells.joinToString(",")
    }

    private fun collectCells(obj: JsonObject, shape: List<Field>, out: MutableList<String>) {
        for (field in shape) {
            when (field) {
                is Field.Leaf -> out += primitive(obj[field.name] ?: JsonNull)
                is Field.Group -> collectCells(obj[field.name] as? JsonObject ?: JsonObject(emptyMap()), field.children, out)
            }
        }
    }

    // ---------------- 标量与引号 ----------------

    private fun primitive(element: JsonElement): String {
        val p = element as? JsonPrimitive ?: throw Unsupported()
        if (p is JsonNull) return "null"
        if (!p.isString) return p.content
        val value = p.content
        return if (needsQuote(value)) "\"${escape(value)}\"" else value
    }

    private fun encodeKey(key: String): String =
        if (needsQuote(key)) "\"${escape(key)}\"" else key

    /**
     * 必须加引号的情形，逐条对应官方规则。
     *
     * 少加一条的后果不是"格式略丑"，而是**解码出别的值**：
     * 比如字符串 `"true"` 不加引号就变成布尔 true，`"05"` 变成数字 5。
     * 所以这里宁可多加（多一对引号只多一两个 token），不能漏。
     */
    private fun needsQuote(s: String): Boolean {
        if (s.isEmpty()) return true
        if (s != s.trim()) return true
        if (s == "true" || s == "false" || s == "null") return true
        if (looksNumeric(s)) return true
        if (s.startsWith("#")) return true
        if (s == "-" || s.startsWith("-")) return true
        for (ch in s) {
            // 分隔符、结构字符、冒号：都会改变解析结果
            if (ch == ':' || ch == ',' || ch == '"' || ch == '\\') return true
            if (ch == '[' || ch == ']' || ch == '{' || ch == '}') return true
            if (ch.code < 0x20) return true
        }
        return false
    }

    /**
     * "看起来像数字"的判定。
     *
     * 这里刻意比 JSON 的数字语法**宽**：`05`、`+1`、`.5` 在 JSON 里非法，
     * 但解码器遇到它们会尝试当数字，所以字符串 `"05"` 必须加引号才不会丢前导零。
     */
    private fun looksNumeric(s: String): Boolean = NUMERIC.matches(s)

    private val NUMERIC = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }
}
