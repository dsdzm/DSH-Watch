package com.dshwatch.domain.agent.mcp

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import kotlinx.serialization.json.JsonObject

/**
 * 把一个 MCP 工具包成项目内的 [AgentTool]。
 *
 * 适配几乎是直译：MCP 的 `tools/list` 返回 `{name, description, inputSchema}`，
 * 而本项目要的正是 `{name, description, parameters}` —— 两者用的都是 JSON Schema，
 * 所以 `inputSchema` **原样透传**，不做任何转换。这一点能成立，是当初选
 * 「自研客户端」而不是「接一层中间层」的意外收获。
 *
 * ## 名字要加前缀，而且要能被信任地反解
 *
 * 两台 server 都可能有一个叫 `search` 的工具。前缀 `mcp_<server>_` 既避免撞名，
 * 也让模型在工具卡片上一眼看出"这是外部的"。调用时用 [originalName]，
 * 不用解析出来的名字 —— 从带前缀的名字反推原名是有损的（原名里可能本来就有下划线）。
 */
class McpToolAdapter(
    private val client: McpClient,
    private val serverSlug: String,
    private val info: McpToolInfo,
) : AgentTool {

    /** MCP 里的原始工具名，调用时必须用它 */
    val originalName: String = info.name

    override val name: String = buildToolName(serverSlug, info.name)

    /**
     * 描述末尾标注来源。
     *
     * 这句不是装饰：模型会从工具清单里挑工具，看到"来自 MCP server「x」"
     * 才知道这个工具会把数据发到外部，从而在不确定时先问一句。
     */
    override val description: String = buildString {
        append(info.description.ifBlank { "MCP 工具 ${info.name}" })
        append("（来自 MCP server「").append(client.serverName).append("」，数据会发到该 server）")
    }

    /**
     * 一律按**写操作**对待。
     *
     * 远程工具能干什么，客户端无从判断 —— 名字叫 `search` 也可能在服务端改数据。
     * 在「只读自动」策略下，这样设置会让每次调用都要求确认。
     * 宁可多确认一次，也不要在用户以为"只是查一下"时把远端改了。
     */
    override val safety: ToolSafety = ToolSafety.WRITE

    override val parameters: JsonObject = info.inputSchema

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val result = client.callTool(originalName, args)
            if (result.isError) {
                ToolResult.fail(result.text.ifBlank { "server 返回了错误，但没有说明原因" })
            } else {
                ToolResult.ok(result.text.ifBlank { "(无输出)" })
            }
        } catch (e: McpException) {
            ToolResult.fail("MCP 调用失败：${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolResult.fail("MCP 调用异常：${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        }
    }

    companion object {
        /**
         * OpenAI 的 function name 只允许 `[a-zA-Z0-9_-]` 且最长 64 字符。
         * 超过或含其它字符时服务端会直接 400，而错误信息不会告诉你"是某个工具名的锅"。
         */
        fun buildToolName(serverSlug: String, toolName: String): String {
            val raw = "mcp_${slug(serverSlug)}_${slug(toolName)}"
            return if (raw.length <= 64) raw else raw.take(64)
        }

        fun slug(value: String): String = buildString {
            for (ch in value) {
                append(
                    if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
                )
            }
        }.trim('_').ifBlank { "server" }
    }
}
