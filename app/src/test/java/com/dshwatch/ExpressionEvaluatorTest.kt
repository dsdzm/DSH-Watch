package com.dshwatch

import com.dshwatch.domain.agent.ExpressionEvaluator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpressionEvaluatorTest {

    @Test
    fun `basic arithmetic respects precedence`() {
        assertThat(ExpressionEvaluator.evaluateToText("1+2*3")).isEqualTo("7")
        assertThat(ExpressionEvaluator.evaluateToText("(1+2)*3")).isEqualTo("9")
        assertThat(ExpressionEvaluator.evaluateToText("10-4-3")).isEqualTo("3")
    }

    @Test
    fun `integer results lose the decimal point`() {
        assertThat(ExpressionEvaluator.evaluateToText("12/4")).isEqualTo("3")
        assertThat(ExpressionEvaluator.evaluateToText("7/2")).isEqualTo("3.5")
    }

    @Test
    fun `power is right associative`() {
        // 2^(3^2) = 2^9 = 512，而不是 (2^3)^2 = 64
        assertThat(ExpressionEvaluator.evaluateToText("2^3^2")).isEqualTo("512")
    }

    @Test
    fun `unary minus and plus`() {
        assertThat(ExpressionEvaluator.evaluateToText("-5+3")).isEqualTo("-2")
        assertThat(ExpressionEvaluator.evaluateToText("-(2+3)")).isEqualTo("-5")
        assertThat(ExpressionEvaluator.evaluateToText("--5")).isEqualTo("5")
    }

    @Test
    fun `modulo works`() {
        assertThat(ExpressionEvaluator.evaluateToText("17%5")).isEqualTo("2")
    }

    @Test
    fun `functions`() {
        assertThat(ExpressionEvaluator.evaluateToText("sqrt(144)")).isEqualTo("12")
        assertThat(ExpressionEvaluator.evaluateToText("max(3, 9)")).isEqualTo("9")
        assertThat(ExpressionEvaluator.evaluateToText("min(3, 9)")).isEqualTo("3")
        assertThat(ExpressionEvaluator.evaluateToText("pow(2, 10)")).isEqualTo("1024")
        assertThat(ExpressionEvaluator.evaluateToText("round(2.6)")).isEqualTo("3")
        assertThat(ExpressionEvaluator.evaluateToText("floor(2.9)")).isEqualTo("2")
        assertThat(ExpressionEvaluator.evaluateToText("ceil(2.1)")).isEqualTo("3")
        assertThat(ExpressionEvaluator.evaluateToText("abs(-7)")).isEqualTo("7")
    }

    @Test
    fun `constants`() {
        val pi = ExpressionEvaluator.evaluate("pi")
        assertThat(pi).isWithin(1e-9).of(Math.PI)
        assertThat(ExpressionEvaluator.evaluateToText("e*0")).isEqualTo("0")
    }

    @Test
    fun `whitespace is ignored`() {
        assertThat(ExpressionEvaluator.evaluateToText("  1  +   2 * 3 ")).isEqualTo("7")
    }

    @Test
    fun `scientific notation is parsed`() {
        assertThat(ExpressionEvaluator.evaluateToText("1e3")).isEqualTo("1000")
        assertThat(ExpressionEvaluator.evaluateToText("2.5e2")).isEqualTo("250")
    }

    @Test
    fun `division by zero is rejected with a readable message`() {
        val failure = runCatching { ExpressionEvaluator.evaluate("1/0") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure!!.message).contains("除数")
    }

    @Test
    fun `unknown function is rejected`() {
        val failure = runCatching { ExpressionEvaluator.evaluate("frobnicate(1)") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure!!.message).contains("不认识的函数")
    }

    @Test
    fun `unknown identifier is rejected`() {
        val failure = runCatching { ExpressionEvaluator.evaluate("foo+1") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure!!.message).contains("不认识的常量")
    }

    @Test
    fun `unbalanced parentheses are rejected`() {
        assertThat(runCatching { ExpressionEvaluator.evaluate("(1+2") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { ExpressionEvaluator.evaluate("1+2)") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `empty expression is rejected`() {
        assertThat(runCatching { ExpressionEvaluator.evaluate("") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `wrong argument count is rejected`() {
        val failure = runCatching { ExpressionEvaluator.evaluate("pow(2)") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure!!.message).contains("需要 2 个参数")
    }

    @Test
    fun `cannot execute arbitrary kotlin or java`() {
        // 这不是 shell，也不是脚本引擎：任何标识符都会被当成常量/函数名拒绝
        for (evil in listOf(
            "Runtime.getRuntime()",
            "System.exit(0)",
            "java.lang.System.exit(0)",
            "1; System.exit(0)",
        )) {
            assertThat(runCatching { ExpressionEvaluator.evaluate(evil) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `deep nesting still works`() {
        assertThat(ExpressionEvaluator.evaluateToText("((((1+2))))*2")).isEqualTo("6")
    }
}
