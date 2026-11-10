package com.dshwatch.domain.agent

import com.dshwatch.domain.agent.tools.CloudTools
import com.dshwatch.domain.agent.tools.DeviceControlTools
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * 工具描述与 schema 的一致性。
 *
 * 这类 bug 特别难查：模型照着描述传参数，schema 里没有那个键 → 被丢弃 →
 * 动作静默退化，工具还报成功。用户看到的是「AI 说做了，但没做」。
 *
 * 所以把「描述里承诺的能力」逐条钉在 schema 上：
 *  - 描述说长按 → `long` 必须是 action 的枚举值，不能是个不存在的布尔参数；
 *  - 实现支持横向翻页 → direction 枚举必须放行 right / left；
 *  - 描述说能按坐标点 → x / y 必须在 schema 里；
 *  - `inputs` 说能传 variant / extra_tasks → 不能写成只放行 command 的死 schema。
 */
class ToolSchemaTest {

    private fun params(tool: AgentTool) = tool.parameters
        .jsonObject["properties"]!!.jsonObject

    private fun enumOf(tool: AgentTool, key: String) =
        params(tool)[key]!!.jsonObject["enum"]?.jsonArray?.map { it.jsonPrimitive.content }

    @Test
    fun `device_act long press is an action value, not a boolean flag`() {
        val act = DeviceControlTools.ActTool
        assertThat(enumOf(act, "action")).containsAtLeast("tap", "long", "type", "scroll", "key")
        // 描述里曾经写 long=true，但实现读的是 action="long"，传布尔会静默失效
        assertThat(params(act)).doesNotContainKey("long")
    }

    @Test
    fun `device_act scroll allows horizontal directions the implementation supports`() {
        assertThat(enumOf(DeviceControlTools.ActTool, "direction"))
            .containsExactly("down", "up", "right", "left")
    }

    @Test
    fun `device_act exposes x and y for coordinate taps`() {
        assertThat(params(DeviceControlTools.ActTool)).containsKey("x")
        assertThat(params(DeviceControlTools.ActTool)).containsKey("y")
    }

    @Test
    fun `cloud_build inputs accepts arbitrary string keys`() {
        /*
         * Schema.obj 会把 additionalProperties 写成 false，于是只有列出的键合法。
         * dsh-build.yml 要 variant / extra_tasks —— 那样模型只能出默认 debug 包，
         * 而描述里明明让人传。
         */
        val inputs = params(CloudTools.CloudBuildTool)["inputs"]!!.jsonObject
        assertThat(inputs["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            .isEqualTo("string")
    }
}
