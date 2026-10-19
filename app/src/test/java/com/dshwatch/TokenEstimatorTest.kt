package com.dshwatch

import com.dshwatch.domain.TokenEstimator
import com.dshwatch.domain.model.ChatTurn
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun `empty string costs nothing`() {
        assertThat(TokenEstimator.estimate("")).isEqualTo(0)
    }

    @Test
    fun `chinese characters are roughly one token each`() {
        val text = "这是一段中文测试文本"
        val tokens = TokenEstimator.estimate(text)
        assertThat(tokens).isEqualTo(text.length)
    }

    @Test
    fun `latin text is roughly four chars per token`() {
        val text = "a".repeat(40)
        assertThat(TokenEstimator.estimate(text)).isEqualTo(10)
    }

    @Test
    fun `mixed text is more expensive than pure latin of same length`() {
        val latin = TokenEstimator.estimate("abcdefghijklmnop")
        val mixed = TokenEstimator.estimate("abcdefghijklmnop中文内容在这里")
        assertThat(mixed).isGreaterThan(latin)
    }

    @Test
    fun `trim keeps recent messages within budget`() {
        val history = (1..40).map {
            ChatTurn(role = "user", content = "这是一条比较长的消息，用来把上下文撑满。".repeat(12))
        }
        val kept = TokenEstimator.trimToBudget(
            systemTokens = 200,
            history = history,
            contextWindow = 8_000,
            maxOutputTokens = 1_000,
        )
        assertThat(kept).isNotEmpty()
        assertThat(kept.size).isLessThan(history.size)
        // 必须保留最新的那条
        assertThat(kept.last()).isEqualTo(history.last())
    }

    @Test
    fun `trim always keeps at least the last turn when budget is tiny`() {
        val history = listOf(
            ChatTurn(role = "user", content = "hello"),
            ChatTurn(role = "assistant", content = "hi"),
        )
        val kept = TokenEstimator.trimToBudget(
            systemTokens = 100_000,
            history = history,
            contextWindow = 8_000,
            maxOutputTokens = 4_000,
        )
        assertThat(kept).isNotEmpty()
    }

    @Test
    fun `trim keeps the original task message even when the middle is dropped`() {
        val task = ChatTurn(role = "user", content = "目标：把云端编译修好，并验证能出包")
        val middle = (1..40).map {
            ChatTurn(role = "user", content = "中间步骤的详细输出。".repeat(40))
        }
        val latest = ChatTurn(role = "assistant", content = "最新一步的结论")
        val history = listOf(task) + middle + listOf(latest)

        val kept = TokenEstimator.trimToBudget(
            systemTokens = 200,
            history = history,
            contextWindow = 8_000,
            maxOutputTokens = 1_000,
        )

        // 从后往前装会先把第一条丢掉 —— 长任务里那等于「忘了要做什么」
        assertThat(kept.first()).isEqualTo(task)
        assertThat(kept.last()).isEqualTo(latest)
    }

    @Test
    fun `first task can be dropped when the caller opts out`() {
        val task = ChatTurn(role = "user", content = "目标：一个很长的任务描述".repeat(30))
        val middle = (1..40).map {
            ChatTurn(role = "user", content = "填充内容。".repeat(40))
        }
        val history = listOf(task) + middle

        val kept = TokenEstimator.trimToBudget(
            systemTokens = 200,
            history = history,
            contextWindow = 4_000,
            maxOutputTokens = 1_000,
            keepFirstUserTurn = false,
        )

        assertThat(kept).doesNotContain(task)
    }
}
