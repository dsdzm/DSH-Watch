package com.dshwatch.domain

import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.ToolCallRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 上下文构成的算法约定。
 *
 * 这块的价值全在「数字对不对」上：用户看它是为了决定要不要开新会话。
 * 算小了会撞 400（请求被服务端拒），算大了会让人白白重开一个会话、
 * 把前面的上下文全丢掉。所以这里把每一段的归属都钉死。
 */
class ContextMeterTest {

    private fun user(text: String) = ChatTurn(role = "user", content = text)

    private fun assistant(text: String) = ChatTurn(role = "assistant", content = text)

    private fun toolCall(id: String, name: String, args: String = "{}") =
        ChatTurn(
            role = "assistant",
            content = "",
            toolCalls = listOf(ToolCallRecord(id = id, name = name, arguments = args)),
        )

    private fun toolResult(id: String, content: String) =
        ChatTurn(role = "tool", content = content, toolCallId = id)

    private fun analyze(
        system: String = "",
        turns: List<ChatTurn>,
        window: Int = 64_000,
        maxOutput: Int = 8_192,
    ) = ContextMeter.analyze(system, turns, window, maxOutput)

    @Test
    fun `空会话里只有系统提示，五段都算得出来`() {
        val i = analyze(system = "你是助手", turns = emptyList())
        assertThat(i.parts.map { it.section }).containsExactly(
            ContextMeter.Section.SYSTEM,
            ContextMeter.Section.USER,
            ContextMeter.Section.ASSISTANT,
            ContextMeter.Section.TOOL_ARGS,
            ContextMeter.Section.TOOL_RESULTS,
        ).inOrder()
        assertThat(i.total).isEqualTo(i.parts.sumOf { it.tokens })
        assertThat(i.turns).isEqualTo(0)
        assertThat(i.heaviest?.section).isEqualTo(ContextMeter.Section.SYSTEM)
    }

    @Test
    fun `用户消息与助手回复各归各段`() {
        val i = analyze(turns = listOf(user("写个排序"), assistant("好")))
        val userTokens = i.parts.first { it.section == ContextMeter.Section.USER }.tokens
        val assistantTokens = i.parts.first { it.section == ContextMeter.Section.ASSISTANT }.tokens
        assertThat(userTokens).isGreaterThan(0)
        assertThat(assistantTokens).isGreaterThan(0)
        // 两段都只来自各自那一轮，不串
        assertThat(userTokens).isEqualTo(TokenEstimator.estimatePair("user", "写个排序"))
        assertThat(assistantTokens).isEqualTo(TokenEstimator.estimatePair("assistant", "好"))
    }

    @Test
    fun `工具参数和工具结果分开计，不混进助手正文`() {
        val i = analyze(
            turns = listOf(
                toolCall("c1", "grep", """{"pattern":"TODO"}"""),
                toolResult("c1", "a.kt:1 TODO\nb.kt:2 TODO"),
            ),
        )
        val args = i.parts.first { it.section == ContextMeter.Section.TOOL_ARGS }.tokens
        val results = i.parts.first { it.section == ContextMeter.Section.TOOL_RESULTS }.tokens
        val body = i.parts.first { it.section == ContextMeter.Section.ASSISTANT }.tokens
        assertThat(args).isGreaterThan(0)
        assertThat(results).isGreaterThan(0)
        // 这一轮 assistant 的 content 是空串，只剩 role + 开销
        assertThat(body).isLessThan(results)
    }

    @Test
    fun `read_file 不计入可折叠，因为它读的就是内容本身`() {
        val big = "x".repeat(20_000)
        val i = analyze(
            turns = listOf(
                toolCall("c1", "read_file"),
                toolResult("c1", big),
            ),
        )
        assertThat(i.reclaimable).isEqualTo(0)
        // 但它照样占上下文，该显示还得显示
        assertThat(i.parts.first { it.section == ContextMeter.Section.TOOL_RESULTS }.tokens)
            .isGreaterThan(1_000)
    }

    @Test
    fun `grep 这类超长结果算作可折叠`() {
        val big = "x".repeat(20_000)
        val i = analyze(
            turns = listOf(
                toolCall("c1", "grep"),
                toolResult("c1", big),
            ),
        )
        assertThat(i.reclaimable).isGreaterThan(1_000)
    }

    @Test
    fun `短结果没有可折叠的量`() {
        val i = analyze(
            turns = listOf(
                toolCall("c1", "grep"),
                toolResult("c1", "只有一行"),
            ),
        )
        assertThat(i.reclaimable).isEqualTo(0)
    }

    @Test
    fun `可折叠不会超过工具结果本身`() {
        val big = "x".repeat(50_000)
        val i = analyze(
            turns = listOf(
                toolCall("c1", "grep"),
                toolResult("c1", big),
            ),
        )
        val results = i.parts.first { it.section == ContextMeter.Section.TOOL_RESULTS }.tokens
        assertThat(i.reclaimable).isAtMost(results)
    }

    @Test
    fun `档位按预算算，不按窗口算`() {
        // 窗口 10k、预留输出 4k → 预算 6k。占用 5k 看似只到窗口一半，
        // 实际已经 83%，必须进 WARN 而不是 OK
        val body = "x".repeat(19_000) // 约 4.75k token
        val i = analyze(turns = listOf(user(body)), window = 10_000, maxOutput = 4_000)
        assertThat(i.usable).isEqualTo(6_000)
        assertThat(i.level).isAnyOf(ContextMeter.Level.WARN, ContextMeter.Level.HOT)
    }

    @Test
    fun `超预算就是 OVER，比例可以大于一`() {
        val body = "x".repeat(40_000) // 约 10k token，预算只有 6k
        val i = analyze(turns = listOf(user(body)), window = 10_000, maxOutput = 4_000)
        assertThat(i.level).isEqualTo(ContextMeter.Level.OVER)
        assertThat(i.ratio).isGreaterThan(1f)
    }

    @Test
    fun `预算为零时不能除出 NaN`() {
        val i = analyze(turns = listOf(user("hi")), window = 1_000, maxOutput = 1_000)
        assertThat(i.usable).isEqualTo(0)
        assertThat(i.ratio).isEqualTo(0f)
        assertThat(i.level).isEqualTo(ContextMeter.Level.OVER)
    }

    @Test
    fun `占比之和为一`() {
        val i = analyze(
            system = "规则若干",
            turns = listOf(user("问题"), assistant("回答")),
        )
        val sum = ContextMeter.Section.entries.sumOf { i.share(it).toDouble() }
        assertThat(sum).isWithin(1e-6).of(1.0)
    }

    @Test
    fun `图片按张数计入用户段`() {
        val withImage = ChatTurn(role = "user", content = "看这张", images = listOf("/a.jpg"))
        val plain = ChatTurn(role = "user", content = "看这张")
        val a = analyze(turns = listOf(withImage))
        val b = analyze(turns = listOf(plain))
        val diff = a.total - b.total
        assertThat(diff).isEqualTo(ContextMeter.IMAGE_TOKEN_ESTIMATE)
    }
}
