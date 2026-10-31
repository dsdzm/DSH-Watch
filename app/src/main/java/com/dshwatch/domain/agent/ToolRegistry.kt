package com.dshwatch.domain.agent

import com.dshwatch.domain.agent.tools.BuiltinTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 工具注册表。
 *
 * 每次请求都会用 [specs] 生成一份 OpenAI function-calling 格式的 tools 数组。
 * spec 里带上 safety 说明（写进 description 末尾），让模型自己也知道哪些操作有副作用，
 * 这样在「只读自动」策略下它会更倾向于把写操作合并成一次调用。
 */
class ToolRegistry(
    private val context: ToolContext,
    tools: List<AgentTool> = BuiltinTools.all(),
) {

    private val allTools: List<AgentTool> = tools

    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    /**
     * 当前设置下真正可用的工具。
     *
     * 每次读都按 [AgentTool.enabled] 现算，而不是构造时筛一次 ——
     * 用户在设置里关掉「B站工具」之后，下一次请求就该少七个 schema，
     * 不该等到 App 重启或 MCP 重连才生效。
     */
    val tools: List<AgentTool>
        get() {
            val s = context.settings()
            return allTools.filter { it.enabled(s) }
        }

    val toolContext: ToolContext get() = context

    /** 只返回**当前可用**的工具：关掉的能力即使模型幻觉出工具名也执行不了 */
    fun get(name: String): AgentTool? =
        byName[name]?.takeIf { it.enabled(context.settings()) }

    fun isKnown(name: String): Boolean = byName.containsKey(name)

    /** OpenAI 兼容的 tools 数组 */
    fun specs(): List<JsonObject> = tools.map(::specOf)

    companion object {

        fun specOf(tool: AgentTool): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", tool.name)
                put("description", "${tool.description}（副作用：${tool.safety.label}）")
                put("parameters", tool.parameters)
            }
        }

        /**
         * 把模型返回的工具名归一化。
         * 有些模型会带上命名空间（如 functions.calculate）或大小写不一致，
         * 直接匹配会全部失败，所以这里做一次宽容处理。
         */
        fun normalizeName(raw: String): String {
            val trimmed = raw.trim()
            val last = trimmed.substringAfterLast('.').substringAfterLast('/')
            return last.lowercase()
        }

        /** 供设置页展示的工具清单 */
        fun describe(tool: AgentTool): String = "${tool.name} · ${tool.safety.label} · ${tool.description}"
    }
}
