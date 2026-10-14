package com.dshwatch

import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolRegistry
import com.dshwatch.domain.agent.tools.BuiltinTools
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ToolRegistryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `built-in tool names are unique and non blank`() {
        val names = BuiltinTools.all().map { it.name }
        assertThat(names).hasSize(names.toSet().size)
        assertThat(names.all { it.isNotBlank() }).isTrue()
    }

    @Test
    fun `every tool declares a valid object schema`() {
        BuiltinTools.all().forEach { tool ->
            val schema = tool.parameters
            assertThat(schema["type"]?.jsonPrimitive?.content).isEqualTo("object")
            assertThat(schema["properties"]).isNotNull()
            // required 里出现的字段必须真的在 properties 里声明
            val props = schema["properties"]!!.jsonObject.keys
            schema["required"]?.jsonArray?.forEach { req ->
                assertThat(props).contains(req.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `spec uses openai function calling shape`() {
        val spec = ToolRegistry.specOf(BuiltinTools.CalculatorTool)
        assertThat(spec["type"]?.jsonPrimitive?.content).isEqualTo("function")
        val function = spec["function"]!!.jsonObject
        assertThat(function["name"]?.jsonPrimitive?.content).isEqualTo("calculate")
        assertThat(function["description"]?.jsonPrimitive?.content).contains("副作用")
        assertThat(function["parameters"]!!.jsonObject["type"]?.jsonPrimitive?.content)
            .isEqualTo("object")
    }

    @Test
    fun `every spec survives json round trip`() {
        // 手写 JSON 最容易出漏引号/多逗号这类问题，序列化一遍能立刻暴露
        BuiltinTools.all().forEach { tool ->
            val text = Json.encodeToString(JsonObject.serializer(), ToolRegistry.specOf(tool))
            val back = json.decodeFromString(JsonObject.serializer(), text)
            assertThat(back).isEqualTo(ToolRegistry.specOf(tool))
        }
    }

    @Test
    fun `normalize name tolerates namespaces and casing`() {
        assertThat(ToolRegistry.normalizeName("calculate")).isEqualTo("calculate")
        assertThat(ToolRegistry.normalizeName("Calculate")).isEqualTo("calculate")
        assertThat(ToolRegistry.normalizeName("functions.calculate")).isEqualTo("calculate")
        assertThat(ToolRegistry.normalizeName("  http_fetch  ")).isEqualTo("http_fetch")
        assertThat(ToolRegistry.normalizeName("ns/sub/get_datetime")).isEqualTo("get_datetime")
    }

    @Test
    fun `schema builder produces expected shapes`() {
        val s = Schema.obj(
            properties = mapOf(
                "a" to Schema.str("文字"),
                "n" to Schema.int("数字", min = 1, max = 10),
                "b" to Schema.bool("开关"),
                "e" to Schema.str("枚举", enum = listOf("x", "y")),
            ),
            required = listOf("a"),
        )
        val props = s["properties"]!!.jsonObject
        assertThat(props["a"]!!.jsonObject["type"]?.jsonPrimitive?.content).isEqualTo("string")
        assertThat(props["n"]!!.jsonObject["minimum"]?.jsonPrimitive?.int).isEqualTo(1)
        assertThat(props["n"]!!.jsonObject["maximum"]!!.jsonPrimitive.int).isEqualTo(10)
        assertThat(props["b"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("boolean")
        assertThat(props["e"]!!.jsonObject["enum"]!!.jsonArray).hasSize(2)
        assertThat(s["additionalProperties"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(s["required"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("a")
    }

    @Test
    fun `argument accessors are tolerant`() {
        val args = json.decodeFromString(
            JsonObject.serializer(),
            """{"text":"hi","n":3,"flag":"true","empty":""}"""
        )
        assertThat(args.strOf("text")).isEqualTo("hi")
        assertThat(args.strOf("empty")).isNull()      // 空串按缺失处理
        assertThat(args.strOf("missing")).isNull()
        assertThat(args.intOf("n")).isEqualTo(3)
        assertThat(args.boolOf("flag")).isTrue()
        assertThat(args.boolOf("missing", default = true)).isTrue()
    }

    @Test
    fun `registry lookup works and unknown names return null`() {
        val registry = ToolRegistry(
            context = com.dshwatch.domain.agent.ToolContext(
                appContext = null,
                workspace = com.dshwatch.data.workspace.WorkspaceStore(
                    java.io.File(System.getProperty("java.io.tmpdir"), "dsh-ws-test"),
                ),
                usage = com.dshwatch.data.usage.UsageRepository(
                    java.io.File(System.getProperty("java.io.tmpdir"), "dsh-usage-test"),
                    Json { ignoreUnknownKeys = true },
                ),
                profile = { com.dshwatch.domain.DeviceProfileDefaults.fallback },
            )
        )
        assertThat(registry.get("calculate")).isNotNull()
        assertThat(registry.isKnown("does_not_exist")).isFalse()
        assertThat(registry.specs()).hasSize(registry.tools.size)
    }

    @Test
    fun `tools cover the documented surface`() {
        val names = BuiltinTools.all().map { it.name }.toSet()
        assertThat(names).containsAtLeast(
            "get_datetime",
            "calculate",
            "get_device_info",
            "get_token_usage",
            "http_fetch",
            "set_alarm",
            "list_files",
            "read_file",
            "write_file",
            "append_file",
            "make_dir",
            "delete_file",
            "build_project",
            "move_file",
            "edit_file",
            "glob",
            "grep",
            "ask_user",
            "list_alarms",
            "cancel_alarm",
            "vibrate",
            "get_clipboard",
            "unit_convert",
            "web_search",
            "todo_write",
            "open_file",
            "open_screen",
            "create_event",
        )
        // 剪贴板**写入**是刻意去掉的：产物要落在工作空间里，而不是丢进剪贴板。
        // 读取则保留 —— 手表上打字困难，用户常常先在别处复制好再让 AI 看。
        assertThat(names).doesNotContain("write_clipboard")
        assertThat(names).contains("get_clipboard")
    }
}
