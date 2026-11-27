package com.dshwatch.ui.screens.chat

import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.model.ToolCallRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 工作流程的还原顺序。
 *
 * 这里钉的是一个具体的 bug：以前只取最后一条消息的 `reasoning`，
 * 十步的任务只显示一条「思维链」，而且工具全排在思考后面。
 * 一旦有人把 `workStepsOf` 改回「取最后一条」，这几条会立刻红。
 */
class WorkStepTest {

    private fun message(
        id: String,
        reasoning: String = "",
        calls: List<ToolCallRecord> = emptyList(),
    ) = ChatMessage(
        id = id,
        role = Role.ASSISTANT,
        content = "",
        reasoning = reasoning,
        toolCalls = calls,
    )

    private fun call(id: String) = ToolCallRecord(id = id, name = "run_command")

    @Test
    fun `思考与工具按消息顺序交替排列`() {
        val steps = workStepsOf(
            listOf(
                message("m1", reasoning = "先看看目录", calls = listOf(call("t1"), call("t2"))),
                message("m2", reasoning = "再跑一次", calls = listOf(call("t3"))),
            ),
        )

        assertThat(steps.map { it.key }).containsExactly(
            "m1-think-0",
            "m1-tool-0",
            "m1-tool-1",
            "m2-think-1",
            "m2-tool-0",
        ).inOrder()

        // 三段思考都要在，不能只剩最后一段
        assertThat(steps.filterIsInstance<WorkStep.Thinking>().map { it.text })
            .containsExactly("先看看目录", "再跑一次").inOrder()
    }

    @Test
    fun `没有思考的消息只产出工具步骤`() {
        val steps = workStepsOf(listOf(message("m1", calls = listOf(call("t1")))))
        assertThat(steps).hasSize(1)
        assertThat(steps.single()).isInstanceOf(WorkStep.Tool::class.java)
    }

    @Test
    fun `流式中的最后一条用 live 思考覆盖已落盘的旧值`() {
        val steps = workStepsOf(
            messages = listOf(
                message("m1", reasoning = "旧的"),
                message("m2", reasoning = "落盘"),
            ),
            liveId = "m2",
            liveReasoning = "还在写…",
        )

        assertThat(steps.filterIsInstance<WorkStep.Thinking>().map { it.text })
            .containsExactly("旧的", "还在写…").inOrder()
    }

    @Test
    fun `空白思考不占一行`() {
        val steps = workStepsOf(
            listOf(
                message("m1", reasoning = "   "),
                message("m2", reasoning = "有内容"),
            ),
        )
        assertThat(steps).hasSize(1)
        assertThat((steps.single() as WorkStep.Thinking).text).isEqualTo("有内容")
    }

    /**
     * 纯思考轮次（没有工具调用）必须能还原出一段思考。
     *
     * 之前调用方只传 `listOf(message)`、不传 live 的那份 reasoning，
     * 流式中 `message.reasoning` 还是空的 —— 于是卡片在、里面是空白，
     * 用户看到的就是「没调工具时思维链展不开」。
     */
    @Test
    fun `没有工具时 live 思考也能落成一步`() {
        val steps = workStepsOf(
            messages = listOf(message("m1")),
            liveId = "m1",
            liveReasoning = "先想一下这句话怎么接",
        )
        assertThat(steps).hasSize(1)
        assertThat((steps.single() as WorkStep.Thinking).text)
            .isEqualTo("先想一下这句话怎么接")
    }

    @Test
    fun `live 思考为 null 时退回已落盘内容，不清空`() {
        val steps = workStepsOf(
            messages = listOf(message("m1", reasoning = "已经写下的")),
            liveId = "m1",
            liveReasoning = null,
        )
        assertThat((steps.single() as WorkStep.Thinking).text).isEqualTo("已经写下的")
    }
}
