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
}
