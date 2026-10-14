package com.dshwatch

import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.ToolCallRecord
import com.dshwatch.net.backend.TurnSanitizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 出站消息序列的协议约束测试。
 *
 * 这一组用例存在的理由很直接：真机上出现过
 * > An assistant message with 'tool_calls' must be followed by tool messages
 * > responding to each 'tool_call.id'
 * 的 400。本地跑起来完全看不出来，只有真机请求才会炸。
 * 所以把「任何输入经过 sanitize 之后都必须合法」变成可执行的断言。
 */
class TurnSanitizerTest {

    private fun call(id: String, name: String = "echo") =
        ToolCallRecord(id = id, name = name, arguments = "{}")

    private fun tool(id: String, content: String = "ok") =
        ChatTurn(role = "tool", content = content, toolCallId = id)

    private fun assistant(vararg ids: String) =
        ChatTurn(role = "assistant", toolCalls = ids.map { call(it) })

    private fun user(text: String) = ChatTurn(role = "user", content = text)

    @Test
    fun `well formed sequence passes validation untouched`() {
        val input = listOf(user("hi"), assistant("c1"), tool("c1"), assistant("c2"), tool("c2"))
        assertThat(TurnSanitizer.validate(input)).isNull()
        assertThat(TurnSanitizer.sanitize(input)).isEqualTo(input)
    }

    @Test
    fun `missing tool result gets a placeholder so the request stays valid`() {
        val input = listOf(user("hi"), assistant("c1", "c2"), tool("c1"))
        assertThat(TurnSanitizer.validate(input)).isNotNull() // 原样是非法的

        val fixed = TurnSanitizer.sanitize(input)
        assertThat(TurnSanitizer.validate(fixed)).isNull()
        // c2 被补了一条占位结果
        assertThat(fixed.filter { it.toolCallId.isNotBlank() }.map { it.toolCallId })
            .containsExactly("c1", "c2").inOrder()
    }

    @Test
    fun `assistant with tool calls and no results at all still becomes valid`() {
        val input = listOf(user("hi"), assistant("c1", "c2"))
        val fixed = TurnSanitizer.sanitize(input)
        assertThat(TurnSanitizer.validate(fixed)).isNull()
        assertThat(fixed).hasSize(4) // user + assistant + 2 个占位
    }

    @Test
    fun `orphan tool message is dropped`() {
        val input = listOf(user("hi"), tool("ghost"), ChatTurn(role = "assistant", content = "done"))
        val fixed = TurnSanitizer.sanitize(input)
        assertThat(TurnSanitizer.validate(fixed)).isNull()
        assertThat(fixed.none { it.toolCallId.isNotBlank() }).isTrue()
        assertThat(fixed).hasSize(2)
    }

    @Test
    fun `order is preserved and assistant stays immediately before its tool results`() {
        val input = listOf(user("a"), assistant("c1"), tool("c1"), user("b"), assistant("c2"), tool("c2"))
        val fixed = TurnSanitizer.sanitize(input)
        assertThat(fixed.map { it.role }).containsExactly(
            "user", "assistant", "tool", "user", "assistant", "tool"
        ).inOrder()
        assertThat(TurnSanitizer.validate(fixed)).isNull()
    }

    @Test
    fun `reversed pair is repaired`() {
        // 这正是 trimToBudget 曾经制造出来的错误顺序：tool 跑到了 assistant 前面
        val input = listOf(user("a"), tool("c1"), assistant("c1"))
        val fixed = TurnSanitizer.sanitize(input)
        assertThat(TurnSanitizer.validate(fixed)).isNull()
        assertThat(fixed.first().role).isEqualTo("user")
        assertThat(fixed[1].role).isEqualTo("assistant")
        assertThat(fixed[2].role).isEqualTo("tool")
    }

    @Test
    fun `duplicate tool results are collapsed to one`() {
        val input = listOf(user("a"), assistant("c1"), tool("c1", "first"), tool("c1", "second"))
        val fixed = TurnSanitizer.sanitize(input)
        assertThat(TurnSanitizer.validate(fixed)).isNull()
        assertThat(fixed.count { it.toolCallId == "c1" }).isEqualTo(1)
    }

    @Test
    fun `validate reports a readable reason`() {
        val problem = TurnSanitizer.validate(listOf(assistant("c1")))
        assertThat(problem).isNotNull()
        assertThat(problem!!).contains("缺少 tool 消息")

        assertThat(TurnSanitizer.validate(listOf(tool("x")))).contains("孤儿")
    }

    @Test
    fun `empty history is valid`() {
        assertThat(TurnSanitizer.validate(emptyList())).isNull()
        assertThat(TurnSanitizer.sanitize(emptyList())).isEmpty()
    }

    @Test
    fun `sanitize is idempotent`() {
        // 修复过一次之后，再修一次不应该有任何变化 —— 否则说明逻辑有抖动
        val input = listOf(user("a"), assistant("c1", "c2"), tool("c2"), tool("ghost"))
        val once = TurnSanitizer.sanitize(input)
        val twice = TurnSanitizer.sanitize(once)
        assertThat(twice).isEqualTo(once)
        assertThat(TurnSanitizer.validate(once)).isNull()
    }
}
