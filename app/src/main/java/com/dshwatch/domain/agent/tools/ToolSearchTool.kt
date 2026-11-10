package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolCatalog
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.strOf
import kotlinx.serialization.json.JsonObject

/**
 * 按需取工具。
 *
 * 这是渐进式披露的入口：常驻工具（读写改搜跑）之外的那三十多个工具，
 * 平时只以「目录」的形式存在（`【工具目录】B站（16）：视频详情、搜索…`），
 * 不带 schema。模型判断需要某类能力时调它，取到的工具从**下一轮**起
 * 进入 tools 数组，带完整参数。
 *
 * ## 为什么值得多这一轮
 *
 * 一个工具的代价不只是描述，还有 schema（参数名、类型、枚举、说明）：
 * 51 个工具合计 18123 字，每轮都发。二十步的对话就是二十遍。
 * 换成目录之后固定部分降到三千出头，而多数任务只需要搜一两次。
 *
 * ## 文案里的两个约束不能省
 *
 * 1. **「下一轮」**：tools 数组在请求发出时就定了，同一轮里调不到刚搜到的工具。
 *    不写清楚，模型会立刻在同一轮调它，然后收到「没有这个工具」。
 * 2. **「已经带了什么」**：把已加载的工具再报一遍，模型才知道不用重复搜。
 */
object ToolSearchTool : AgentTool {

    override val name = "tool_search"
    override val description =
        "按需取工具。先看【工具目录】，把要用的那组取来（下一轮可直接调用，带完整参数）。" +
            "query 给分类（文件 / 构建 / 网络 / 提醒 / B站 / 设备）或关键词（字幕、闹钟、APK）。"
    override val safety = ToolSafety.READ_ONLY
    override val parameters = Schema.obj(
        properties = mapOf(
            "query" to Schema.str("分类名或关键词，例如 B站 / 构建 / 字幕"),
        ),
        required = listOf("query"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.strOf("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.fail("需要 query：给分类名（B站 / 构建 / 提醒）或关键词。")

        val scope = ctx.toolScope
            ?: return ToolResult.fail(
                "当前没有按需加载机制（所有工具已经全部下发），直接调用即可。",
            )

        val hits = scope.search(query)
        if (hits.isEmpty()) {
            return ToolResult.ok(
                "没有匹配「$query」的工具。也可能是这项能力被关掉了（B 站 / 本机执行 / 设备操控" +
                    "都可以在设置里关）。换个词再搜，或直接告诉用户做不了。",
            )
        }

        /*
         * 一次最多取这么多。
         *
         * 搜「B站」会命中整组 16 个，全塞进 tools 数组就是四千多字 ——
         * 等于把刚省下的又花回去。取 6 个够覆盖一次任务，剩下的换个更具体的词再搜。
         */
        val picked = hits.take(MAX_LOAD)
        val added = scope.load(picked.map { it.name })
        return ToolResult.ok(render(query, picked, added, hits.size))
    }

    /** 单次加载上限：一次任务很少同时用到六个以上的同组工具。 */
    private const val MAX_LOAD = 6

    private fun render(
        query: String,
        picked: List<AgentTool>,
        added: List<String>,
        totalHits: Int,
    ): String = buildString {
        if (added.isEmpty()) {
            append("「").append(query).append("」这组之前就取过，参数已经在 tools 里，直接用。")
        } else {
            append("已取到 ").append(added.size).append(" 个，**下一轮**起可直接调用（本轮还不行）：")
        }
        picked.forEach {
            append("\n- ").append(ToolCatalog.brief(it)).append(' ')
            append(it.description.substringBefore('。').take(36))
        }
        val left = totalHits - picked.size
        if (left > 0) {
            append("\n（同类还有 ").append(left).append(" 个没取：换更具体的词再搜，")
                .append("比如「字幕」「评论」「私信」。）")
        }
    }
}
