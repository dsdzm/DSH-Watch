package com.dshwatch.domain.plugin

import com.dshwatch.domain.TokenEstimator
import kotlinx.serialization.json.Json

/**
 * `dsh-plugin-save-token` —— 结构感知压缩：JSON → TOON，原文落盘。
 *
 * 省 token 的原理见 [ToonEncoder]：重复的键名只写一次（表头），值按行排。
 * 一个 50 行的 `[{"id":1,"name":"a",...}]` 通常能压掉 30–60%。
 *
 * ## 为什么默认关闭
 *
 * 它**改变了模型看到的数据形态**。绝大多数情况下模型能读懂 TOON（表头 + 行，
 * 语义自明），但这毕竟不是它最熟悉的 JSON —— 对某些模型，转成 TOON 后
 * 它会开始逐行"翻译回 JSON"再处理，反而更慢。
 * 这种取舍应该由用户自己判断，而不是出厂替他决定。
 *
 * ## 三条硬约束
 *
 * 1. **严格更小才采用**：用项目自己的估算器比较，包括尾部那段说明文字。
 *    估算器是本地启发式，但"改完更大还硬用"没有任何辩护空间。
 * 2. **落盘失败就不转换**：TOON 是结构简化，不是无损编码 —— 模型可能在某个时刻
 *    需要原始结构（比如要精确的 null 与缺失字段的区别）。如果原文拿不回来，
 *    这次转换就是单向损失。
 * 3. **解析失败不动手**：内容被上游截断过（残缺 JSON）时 `parseToJsonElement`
 *    会失败，这时保持原样。往一个残缺的 JSON 上做编码只会得到更残缺的东西。
 */
object SaveTokenPlugin : ToolResultPlugin {

    override val id: String = "dsh-plugin-save-token"

    override val defaultEnabled: Boolean = false

    /** 原文存放目录（相对会话目录）。不用点开头：模型需要 `read_file` 时能直接读到 */
    const val RAW_DIR = "_raw"

    /** 低于这个长度不值得转（小对象的表头开销可能比省下的还多） */
    private const val MIN_INPUT_CHARS = 200

    override suspend fun transform(input: ToolResultInput): String {
        val content = input.content
        if (content.length < MIN_INPUT_CHARS) return content

        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return content

        val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return content
        val toon = ToonEncoder.encode(root)?.takeIf { it.isNotBlank() } ?: return content

        val relative = rawPath(input)
        val out = buildString {
            append(toon)
            append("\n\n（原文 ").append(content.length).append(" 字符已存 ").append(relative)
            append("，需要原始结构时用 read_file 读它）")
        }

        // 严格更小才采用 —— 连同尾部说明一起算
        if (TokenEstimator.estimate(out) >= TokenEstimator.estimate(content)) return content

        // 比较通过才落盘：否则会留下一堆没人引用的 _raw 文件
        input.workspace.write(relative, content) ?: return content
        return out
    }

    private fun rawPath(input: ToolResultInput): String {
        val name = "${input.toolName}-${System.currentTimeMillis()}.json"
        return listOf(input.sessionDir.trim().trim('/'), RAW_DIR, name)
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }
}
