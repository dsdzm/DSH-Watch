package com.dshwatch.domain.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 + MCP 的报文结构。
 *
 * 刻意**不用** kotlinx.serialization 的 `@Serializable` data class 来做整包解析：
 * JSON-RPC 的 `id` 允许是数字或字符串，`result` 的形态随方法变化，
 * 而 `error` 与 `result` 互斥。用 data class 会写出大量 `@SerialName` 和可空字段，
 * 反不如手工读 JsonObject 清楚 —— 反正字段只有五个。
 */
internal object JsonRpc {

    /** 一条报文解析后的样子。`method` 非空表示这是 server 发来的通知/请求，不是响应 */
    data class Message(
        val id: Long?,
        val method: String?,
        val result: JsonElement?,
        val error: JsonRpcError?,
    ) {
        val isResponse: Boolean get() = id != null || error != null
    }

    data class JsonRpcError(val code: Int, val message: String, val data: String?)

    fun request(id: Long, method: String, params: JsonObject?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    fun notification(method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    fun parse(text: String): Message? {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val error = obj["error"]?.let { element ->
            val e = runCatching { element.jsonObject }.getOrNull() ?: return@let null
            JsonRpcError(
                code = e["code"]?.jsonPrimitive?.intOrNull ?: 0,
                message = e["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                data = e["data"]?.let { runCatching { it.toString() }.getOrNull() },
            )
        }
        return Message(
            id = parseId(obj["id"]),
            method = obj["method"]?.jsonPrimitive?.contentOrNull,
            result = obj["result"],
            error = error,
        )
    }

    /** id 可以是数字也可以是字符串，两种都要认 —— 有些 server 用字符串 id */
    private fun parseId(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.toLongOrNull()
    }

    /** `tools/list` 的返回 → 工具清单 */
    fun parseTools(result: JsonElement?): List<McpToolInfo> {
        val obj = result as? JsonObject ?: return emptyList()
        val array = obj["tools"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.isBlank()) return@mapNotNull null
            McpToolInfo(
                name = name,
                description = item["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = item["inputSchema"] as? JsonObject ?: EMPTY_SCHEMA,
            )
        }
    }

    /**
     * `tools/call` 的返回 → 一段文本。
     *
     * content 是数组，元素可能是 text / image / audio / resource。
     * 这里只拼 text 并把其它类型标成占位符 —— 手表上没法把图片塞进模型上下文，
     * 但**必须让模型知道"这里本来有张图"**，否则它会以为工具什么都没返回。
     */
    fun parseCallResult(result: JsonElement?): McpCallResult {
        val obj = result as? JsonObject
            ?: return McpCallResult(text = result?.toString().orEmpty(), isError = false)
        val isError = (obj["isError"]?.jsonPrimitive?.contentOrNull) == "true"
        val content = obj["content"] as? JsonArray
        if (content == null) {
            return McpCallResult(text = result.toString(), isError = isError)
        }
        val text = content.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> item["text"]?.jsonPrimitive?.contentOrNull
                "image" -> "[图片 ${item["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()}]"
                "audio" -> "[音频]"
                "resource" -> {
                    val res = item["resource"] as? JsonObject
                    val uri = res?.get("uri")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val body = res?.get("text")?.jsonPrimitive?.contentOrNull
                    if (body != null) body else "[资源 $uri]"
                }
                else -> item.toString()
            }
        }.joinToString("\n")
        return McpCallResult(text = text, isError = isError)
    }

    val EMPTY_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }
}

/** MCP server 上的一个工具（尚未包装成 AgentTool） */
data class McpToolInfo(
    val name: String,
    val description: String,
    /** JSON Schema，直接透传给 AgentTool.parameters */
    val inputSchema: JsonObject,
)

data class McpCallResult(val text: String, val isError: Boolean)
