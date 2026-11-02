package com.dshwatch

import com.dshwatch.domain.agent.build.LanguageValidators
import com.dshwatch.domain.agent.build.Severity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 多语言静态校验。
 *
 * 这些用例的重点不是「能查出错误」，而是**不误报**：
 * 校验器的读者是模型，它收到错误就会去改代码，而它没有编译器可以对照 ——
 * 误报会把本来正确的代码改坏。所以字符串里的括号、注释里的括号、
 * 三引号里的井号这些最容易误判的场景，都被单独钉住了。
 */
class LanguageValidatorsTest {

    private fun errors(path: String, src: String) =
        LanguageValidators.let {
            when (path.substringAfterLast('.')) {
                "py" -> it.validatePython(path, src)
                "sh" -> it.validateShell(path, src)
                "yml", "yaml" -> it.validateYaml(path, src)
                else -> it.validateBraces(path, src, path.substringAfterLast('.'))
            }
        }.filter { it.severity == Severity.ERROR }

    // ---------------- Python ----------------

    @Test
    fun `clean python has no errors`() {
        val src = """
            import os

            def main(name: str) -> None:
                if not name:
                    print("empty")
                    return
                for i in range(3):
                    print(i)

            class Foo:
                def bar(self):
                    try:
                        pass
                    except ValueError:
                        pass
        """.trimIndent()
        assertThat(errors("main.py", src)).isEmpty()
    }

    @Test
    fun `missing colon is reported`() {
        val src = """
            def main()
                print("hi")
        """.trimIndent()
        val found = errors("main.py", src)
        assertThat(found).isNotEmpty()
        assertThat(found.first().message).contains("冒号")
        assertThat(found.first().line).isEqualTo(1)
    }

    @Test
    fun `if without colon is reported`() {
        val src = """
            x = 1
            if x > 0
                print(x)
        """.trimIndent()
        val found = errors("main.py", src)
        assertThat(found.map { it.line }).contains(2)
    }

    @Test
    fun `mixed tabs and spaces are reported`() {
        // 第二行用 tab 缩进，第三行用空格 —— Python 3 会直接抛 TabError
        val src = "def f():\n\tif True:\n        return 1\n"
        val found = errors("main.py", src)
        assertThat(found.any { it.message.contains("制表符") || it.message.contains("缩进") }).isTrue()
    }

    @Test
    fun `unmatched bracket is reported`() {
        val src = """
            def f():
                return (1 + 2
        """.trimIndent()
        assertThat(errors("main.py", src)).isNotEmpty()
    }

    @Test
    fun `hash inside a string is not a comment`() {
        // 这正是逐行正则方案会误报的地方
        val src = """
            color = "#ff0000"
            print(color)
        """.trimIndent()
        assertThat(errors("main.py", src)).isEmpty()
    }

    @Test
    fun `brackets inside a triple quoted string are ignored`() {
        val src = """
            text = '''
            this ( is not code
            neither { is this
            '''
            print(text)
        """.trimIndent()
        assertThat(errors("main.py", src)).isEmpty()
    }

    @Test
    fun `unclosed triple quote is reported`() {
        val src = "text = '''never closed\nprint(1)\n"
        assertThat(errors("main.py", src).any { it.message.contains("三引号") || it.message.contains("闭合") }).isTrue()
    }

    @Test
    fun `multi line condition does not trigger missing colon`() {
        // 条件跨行时行尾没有冒号是正常的，不能报
        val src = """
            if (first_condition and
                    second_condition):
                print("ok")
        """.trimIndent()
        assertThat(errors("main.py", src).map { it.message }).isEmpty()
    }

    @Test
    fun `nested python blocks and decorators are fine`() {
        val src = """
            import functools

            @functools.cache
            def outer(items):
                if items:
                    for item in items:
                        if item:
                            yield item
                elif not items:
                    return
                else:
                    raise ValueError("empty")

            class Box:
                @property
                def size(self):
                    try:
                        return self._size
                    except AttributeError:
                        return 0
        """.trimIndent()
        assertThat(errors("box.py", src)).isEmpty()
    }

    @Test
    fun `multi line function header is fine`() {
        val src = """
            def add(
                a,
                b,
            ):
                return a + b
        """.trimIndent()
        assertThat(errors("add.py", src)).isEmpty()
    }

    @Test
    fun `docstrings do not affect indentation checks`() {
        val src = """
            def f():
                '''Summary.

                More text here.
                '''
                return 1
        """.trimIndent()
        assertThat(errors("doc.py", src)).isEmpty()
    }

    @Test
    fun `missing indented block is reported`() {
        val src = """
            def f():
            return 1
        """.trimIndent()
        val found = errors("main.py", src)
        assertThat(found.any { it.message.contains("缩进") }).isTrue()
    }

    @Test
    fun `unexpected indent is reported`() {
        val src = """
            x = 1
                y = 2
        """.trimIndent()
        val found = errors("main.py", src)
        assertThat(found.any { it.message.contains("意外") }).isTrue()
    }

    @Test
    fun `dedent to a nonexistent level is reported`() {
        val src = "def f():\n    if True:\n        return 1\n  return 2\n"
        val found = errors("main.py", src)
        assertThat(found.any { it.message.contains("取消缩进") }).isTrue()
    }

    @Test
    fun `else if is reported with elif suggestion`() {
        val src = """
            if x:
                pass
            else if y:
                pass
        """.trimIndent()
        val found = errors("main.py", src)
        assertThat(found.any { it.message.contains("elif") }).isTrue()
    }

    @Test
    fun `python 2 print statement is reported`() {
        fun reportsPy2(src: String) =
            errors("old.py", src).any { it.message.contains("Python 2") }
        assertThat(reportsPy2("print \"hi\"\n")).isTrue()
        assertThat(reportsPy2("print x\n")).isTrue()
        assertThat(reportsPy2("print >>sys.stderr, x\n")).isTrue()
    }

    @Test
    fun `python 3 print is not reported`() {
        val src = """
            print("hi")
            print (x)
            print(*args)
            print()
        """.trimIndent()
        assertThat(errors("new.py", src)).isEmpty()
    }

    // ---------------- 花括号语言 ----------------

    @Test
    fun `clean kotlin has no errors`() {
        val src = """
            package demo

            fun main() {
                val items = listOf(1, 2, 3)
                items.forEach { println(it) }
            }
        """.trimIndent()
        assertThat(errors("Main.kt", src)).isEmpty()
    }

    @Test
    fun `brackets in string and comment are ignored`() {
        val src = """
            fun f() {
                // 这个注释里有 ( 和 { 都不该被算进去
                val s = "字符串里也有 ( 和 {"
                /* 块注释 ( 也不该算 */
                println(s.trim())
            }
        """.trimIndent()
        assertThat(errors("Demo.kt", src)).isEmpty()
    }

    @Test
    fun `unclosed brace is reported`() {
        val src = """
            fun f() {
                if (true) {
                    println("x")
            }
        """.trimIndent()
        assertThat(errors("Demo.kt", src)).isNotEmpty()
    }

    @Test
    fun `unclosed string is reported`() {
        val src = "fun f() {\n    val s = \"never closes\n}\n"
        assertThat(errors("Demo.kt", src).any { it.message.contains("闭合") }).isTrue()
    }

    @Test
    fun `kotlin triple quoted string spanning lines is fine`() {
        val src = "val q = \"\"\"\nline one { (\nline two\n\"\"\"\n"
        assertThat(errors("Demo.kt", src)).isEmpty()
    }

    @Test
    fun `java and c and go share the same checks`() {
        val bad = "class A {\n    void f() {\n        int x = 1;\n}\n"
        assertThat(errors("A.java", bad)).isNotEmpty()
        assertThat(errors("a.c", bad)).isNotEmpty()
        assertThat(errors("a.go", bad)).isNotEmpty()
    }

    @Test
    fun `clean java file is fine`() {
        val src = """
            package demo;

            import java.util.List;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("hi");
                }

                static class Helper {
                    void run() { }
                }
            }
        """.trimIndent()
        assertThat(errors("Main.java", src)).isEmpty()
    }

    @Test
    fun `java public class name must match file name`() {
        val src = "public class Solution {\n    int f() { return 1; }\n}\n"
        val found = errors("Main.java", src)
        assertThat(found.any { it.message.contains("Solution") }).isTrue()
        // 同名时不报
        assertThat(errors("Solution.java", src)).isEmpty()
    }

    @Test
    fun `nested public class is not checked against file name`() {
        val src = """
            public class Outer {
                public static class Inner { }
            }
        """.trimIndent()
        assertThat(errors("Outer.java", src)).isEmpty()
    }

    @Test
    fun `java package and import need semicolons`() {
        val src = "package demo\nimport java.util.List\n\nclass A { }\n"
        val found = errors("A.java", src)
        assertThat(found.any { it.message.contains("分号") }).isTrue()
    }

    @Test
    fun `java type declaration inside string or comment is ignored`() {
        val src = """
            public class Main {
                // public class Wrong
                String s = "public class Str";
            }
        """.trimIndent()
        assertThat(errors("Main.java", src)).isEmpty()
    }

    @Test
    fun `clean cpp preprocessor is fine`() {
        val src = """
            #include <iostream>
            #include "lib.h"

            int main() {
            #ifdef DEBUG
                std::cout << "debug";
            #elif RELEASE
                std::cout << "release";
            #else
                std::cout << "other";
            #endif
                return 0;
            }
        """.trimIndent()
        assertThat(errors("main.cpp", src)).isEmpty()
    }

    @Test
    fun `unclosed cpp if is reported`() {
        val src = "#ifdef DEBUG\nint main() { return 0; }\n"
        val found = errors("main.cpp", src)
        assertThat(found.any { it.message.contains("#endif") }).isTrue()
    }

    @Test
    fun `cpp else without if is reported`() {
        val src = "#else\n#endif\nint main() { return 0; }\n"
        val found = errors("main.cpp", src)
        assertThat(found.any { it.message.contains("没有对应的 #if") }).isTrue()
    }

    @Test
    fun `unclosed cpp include is reported`() {
        assertThat(errors("a.c", "#include <stdio.h\n").map { it.message })
            .contains("#include <...> 没有闭合：缺少结尾的 `>`。")
        assertThat(errors("a.c", "#include \"stdio.h\n").map { it.message })
            .contains("#include \"...\" 没有闭合：缺少结尾的引号。")
    }

    @Test
    fun `cpp directive inside comment is ignored`() {
        val src = """
            /*
            #endif
            */
            // #if 0
            int main() { return 0; }
        """.trimIndent()
        assertThat(errors("main.c", src)).isEmpty()
    }

    // ---------------- Shell ----------------

    @Test
    fun `clean shell has no errors`() {
        val src = """
            #!/bin/bash
            for f in *.txt; do
                echo "${'$'}f"
            done
            if [ -f a ]; then
                echo yes
            fi
        """.trimIndent()
        assertThat(errors("run.sh", src)).isEmpty()
    }

    @Test
    fun `missing fi is reported`() {
        val src = "if [ -f a ]; then\n    echo yes\n"
        val found = errors("run.sh", src)
        assertThat(found).isNotEmpty()
        assertThat(found.first().message).contains("fi")
    }

    @Test
    fun `hash in quotes is not a comment for shell`() {
        val src = "echo \"# not a comment\"\n"
        assertThat(errors("run.sh", src)).isEmpty()
    }

    // ---------------- YAML ----------------

    @Test
    fun `yaml with tab indentation is reported`() {
        val src = "key:\n\tsub: 1\n"
        assertThat(errors("a.yml", src).map { it.message }).isNotEmpty()
    }

    @Test
    fun `yaml with spaces is fine`() {
        val src = "key:\n  sub: 1\n  other:\n    - a\n    - b\n"
        assertThat(errors("a.yml", src)).isEmpty()
    }
}
