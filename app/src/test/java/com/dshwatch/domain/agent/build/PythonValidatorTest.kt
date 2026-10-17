package com.dshwatch.domain.agent.build

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Python 静态校验。
 *
 * ## 为什么这组测试里"不报"的用例比"报"的更重要
 *
 * 校验器的读者是模型：它收到错误就去改代码，而且**没有编译器可以对照**，
 * 所以无法判断是谁错了。因此误报的代价是「把本来正确的代码改坏」——
 * 这比漏报更糟。
 *
 * 真机上就发生过一次：`sum(1 for _ in range(n)` 换行接 `if _ % 2 == 0)` 这种
 * **完全合法**的写法被判成"缺冒号"，模型于是把它改成了另一种写法。
 *
 * 根因很隐蔽：判断"语句是否结束"时看的是**行末**的括号深度，
 * 而括号恰恰是在 `if` 那一行闭合的 —— 于是这一行被当成独立语句。
 * 正确的问题是：**这一行是不是续行**。
 *
 * 所以下面「不报」的用例覆盖了各种跨行形式；同时保留「该报的仍然报」，
 * 免得修过头把检查修成废的。
 */
class PythonValidatorTest {

    private fun issues(src: String) = LanguageValidators.validatePython("main.py", src)

    // ---------------- 不该报的（跨行续写）----------------

    @Test
    fun `generator with a trailing if on its own line`() {
        // 真机样本
        val src = """
            total = sum(1 for _ in range(n)
                        if _ % 2 == 0)
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }

    @Test
    fun `multiline list comprehension with if`() {
        val src = """
            result = [
                x
                for x in items
                if x > 0
            ]
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }

    @Test
    fun `multiline dict comprehension with if`() {
        val src = """
            d = {
                k: v
                for k, v in items
                if v
            }
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }

    @Test
    fun `conditional expression spread over lines`() {
        val src = """
            value = (
                1
                if cond
                else 2
            )
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }

    @Test
    fun `multiline condition still gets its colon on the closing line`() {
        // `if (` 之后换行，冒号在最后一行 —— 中间行不该被判缺冒号
        val src = """
            if (a and
                    b):
                pass
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }

    @Test
    fun `backslash continuation`() {
        val src = """
            if a and \
               b:
                pass
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }

    @Test
    fun `call with a keyword argument named else`() {
        // 关键字出现在参数位置时，连行首都不是
        val src = """
            f(else_=1)
            x = 1 if cond else 2
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }

    // ---------------- 该报的仍然要报 ----------------

    @Test
    fun `real missing colon is still caught`() {
        val src = """
            if x > 0
                print(x)
        """.trimIndent()

        val found = issues(src)
        assertThat(found).hasSize(1)
        assertThat(found.first().line).isEqualTo(1)
        assertThat(found.first().message).contains("冒号")
    }

    @Test
    fun `missing colon after def is still caught`() {
        val src = """
            def f(x)
                return x
        """.trimIndent()

        assertThat(issues(src)).hasSize(1)
    }

    @Test
    fun `missing colon after for is still caught`() {
        val src = """
            for i in range(3)
                print(i)
        """.trimIndent()

        assertThat(issues(src)).hasSize(1)
    }

    // ---------------- 其它检查不受影响 ----------------

    @Test
    fun `brackets inside strings are ignored`() {
        assertThat(issues("""print("(unclosed")""")).isEmpty()
    }

    @Test
    fun `unbalanced bracket is reported`() {
        assertThat(issues("def f(:\n    pass")).isNotEmpty()
    }

    @Test
    fun `mixed tabs and spaces are reported`() {
        assertThat(issues("def f():\n\tx = 1\n    y = 2")).isNotEmpty()
    }

    @Test
    fun `ordinary code is clean`() {
        val src = """
            def add(a, b):
                return a + b


            class Foo:
                def bar(self):
                    if self.x:
                        return 1
                    else:
                        return 2

                def baz(self):
                    for i in range(3):
                        print(i)
                    while self.ready:
                        break
                    with open("f") as fh:
                        pass
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }

    @Test
    fun `triple quoted string full of colons and brackets`() {
        val src = """
            text = '''
            if x
            for y
            { ( [
            '''

            print(text)
        """.trimIndent()

        assertThat(issues(src)).isEmpty()
    }
}
