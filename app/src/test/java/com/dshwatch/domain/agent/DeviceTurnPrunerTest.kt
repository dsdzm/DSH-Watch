package com.dshwatch.domain.agent

import com.dshwatch.domain.model.ChatTurn
import com.dshwatch.domain.model.ToolCallRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 设备会话的历史折叠。
 *
 * 这里的断言守的是「模型还能不能正确接着做」：
 * 最近几屏必须完整（编号要对得上），更早的只能留动作那一行（知道自己做过什么），
 * 非设备工具的内容一个字都不能动（那是它读过的文件、跑过的命令）。
 */
class DeviceTurnPrunerTest {

    private fun pair(callId: String, name: String, result: String) = listOf(
        ChatTurn(role = "assistant", toolCalls = listOf(ToolCallRecord(id = callId, name = name))),
        ChatTurn(role = "tool", content = result, toolCallId = callId),
    )

    private fun screen(n: Int) = "微信 · 8 项\n" + (1..8).joinToString("\n") {
        "[$it] 按钮 会话 $n-$it 的一条比较长的名字用来把正文撑过折叠阈值"
    }

    @Test
    fun `fewer than keep screens are left alone`() {
        val turns = (1..3).flatMap { pair("c$it", "device_screen", screen(it)) }
        val pruned = DeviceTurnPruner.prune(turns, keep = 3)
        assertThat(pruned).isEqualTo(turns)
    }

    @Test
    fun `older screens are folded and the newest stay intact`() {
        val turns = (1..6).flatMap { pair("c$it", "device_screen", screen(it)) }
        val pruned = DeviceTurnPruner.prune(turns, keep = 3)

        // 前三条被折成一行，后三条原样
        assertThat(pruned[1].content).contains("已过时")
        assertThat(pruned[1].content.length).isLessThan(100)
        assertThat(pruned[9].content).isEqualTo(screen(5))
        assertThat(pruned[11].content).isEqualTo(screen(6))
    }

    @Test
    fun `action line survives so the model still knows what it did`() {
        val turns = (1..5).flatMap {
            pair("c$it", "device_act", "已点击「发送」\n\n" + screen(it))
        }
        val pruned = DeviceTurnPruner.prune(turns, keep = 3)
        // 动作结果要留，只砍屏幕正文
        assertThat(pruned[1].content).startsWith("已点击「发送」")
        assertThat(pruned[1].content).contains("已过时")
        assertThat(pruned[1].content).doesNotContain("会话 1-")
    }

    @Test
    fun `non device tools are never touched`() {
        val big = "文件内容".repeat(300)
        val turns = (1..5).flatMap { pair("c$it", "device_screen", screen(it)) } +
            pair("f1", "read_file", big)
        val pruned = DeviceTurnPruner.prune(turns, keep = 2)
        assertThat(pruned.last().content).isEqualTo(big)
    }

    @Test
    fun `short results are not worth a placeholder`() {
        val turns = (1..5).flatMap { pair("c$it", "device_screen", "空屏 $it") }
        val pruned = DeviceTurnPruner.prune(turns, keep = 2)
        assertThat(pruned).isEqualTo(turns)
    }

    @Test
    fun `pruning does not mutate the input list`() {
        val turns = (1..5).flatMap { pair("c$it", "device_screen", screen(it)) }
        val copy = turns.toList()
        DeviceTurnPruner.prune(turns, keep = 1)
        assertThat(turns).isEqualTo(copy)
    }

    @Test
    fun `pruning twice changes nothing more`() {
        // 每轮请求前都会走一次折叠，输入是上一轮折过的 turns —— 必须幂等，
        // 否则已经折成一行的那条会被再折一次，连动作那一行都留不住
        val turns = (1..6).flatMap { pair("c$it", "device_act", "已点击「发送」\n\n" + screen(it)) }
        val once = DeviceTurnPruner.prune(turns, keep = 3)
        val twice = DeviceTurnPruner.prune(once, keep = 3)
        assertThat(twice).isEqualTo(once)
    }
}
