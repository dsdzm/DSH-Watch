package com.dshwatch.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 思维导图解析。
 *
 * ## 这组用例是怎么来的
 *
 * 1.23.2(84) 在 GLL-AL00 上冷启动闪退，栈顶是 `PatternSyntaxException`，
 * 报错的正则 `^\{(.*)}$` 就出自解析 mermaid 节点形状的那四行 `replace`。
 *
 * 值得记一笔的是：**这个 bug 在 JVM 上跑不出来**。JDK 的 `java.util.regex`
 * 把孤立的 `}` 当字面量放过，所以单元测试、桌面预览一切正常；Android 9 起
 * `java.util.regex` 换成了 ICU 实现，ICU 判它非法，构造 Pattern 时就抛。
 * 也就是说「测试全绿」和「线上崩」可以同时成立，只有真机才会露。
 *
 * 现在这段改成了纯字符串判断（见 `MindMap.stripShape`），不再经过任何正则引擎，
 * 这条用例守的是「别又把正则写回来」。
 */
class MindMapParseTest {

    @Test
    fun `花括号形状的节点能剥壳`() {
        // mermaid 决策节点 `B{是}` —— 就是它触发了那次崩溃
        val root = MindMap.parse("B{是}")
        assertThat(root).isNotNull()
        assertThat(root!!.text).isEqualTo("是")
    }

    @Test
    fun `四种节点形状都剥一层`() {
        assertThat(MindMap.parse("root((圆))")!!.text).isEqualTo("圆")
        assertThat(MindMap.parse("A[方]")!!.text).isEqualTo("方")
        assertThat(MindMap.parse("B{菱}")!!.text).isEqualTo("菱")
        assertThat(MindMap.parse("C(圆角)")!!.text).isEqualTo("圆角")
        assertThat(MindMap.parse("D 裸文本")!!.text).isEqualTo("D 裸文本")
    }

    @Test
    fun `节点 ID 加形状也剥`() {
        // mermaid 的标准写法：`root((mindmap))`、`A[文本]`。
        // 旧实现没处理 ID，用户看到的是整串 `root((mindmap))`。
        assertThat(MindMap.parse("root((mindmap))")!!.text).isEqualTo("mindmap")
        assertThat(MindMap.parse("A[方]")!!.text).isEqualTo("方")
    }

    @Test
    fun `不是 ID 的括号不吃掉`() {
        // 一句普通中文句子里的括号，不能被当成节点形状
        assertThat(MindMap.parse("他说(真的)")!!.text).isEqualTo("他说(真的)")
        assertThat(MindMap.parse("2 (备注)")!!.text).isEqualTo("2 (备注)")
    }

    @Test
    fun `只剥一层，不递归剥`() {
        // 原来的链式 replace 也不会把 [[x]] 剥成 x
        assertThat(MindMap.parse("[[x]]")!!.text).isEqualTo("[x]")
    }

    @Test
    fun `单边括号保持原样`() {
        // 只有左边或只有右边，都不该当成形状壳 —— 否则正文里的括号会被吃掉
        assertThat(MindMap.parse("{只有左")!!.text).isEqualTo("{只有左")
        assertThat(MindMap.parse("只有右}")!!.text).isEqualTo("只有右}")
        assertThat(MindMap.parse("{")!!.text).isEqualTo("{")
        assertThat(MindMap.parse("}")!!.text).isEqualTo("}")
    }

    @Test
    fun `围栏语言行与缩进层级`() {
        val root = MindMap.parse(
            """
            mindmap
              中心
                左
                右
                  孙
            """.trimIndent(),
        )
        assertThat(root).isNotNull()
        assertThat(root!!.text).isEqualTo("中心")
        assertThat(root.children.map { it.text }).containsExactly("左", "右")
        assertThat(root.children[1].children.map { it.text }).containsExactly("孙")
    }

    @Test
    fun `完全空的内容返回 null 而不是抛`() {
        assertThat(MindMap.parse("")).isNull()
        assertThat(MindMap.parse("   \n\t\n  ")).isNull()
    }

    @Test
    fun `畸形输入不会把异常抛给调用方`() {
        // 组合期调用，抛出去就是闪退。这里只要求「不抛、给个结果」
        val weird = listOf(
            "{", "}", "{{}}", "((", "))", "((((",
            "a".repeat(10_000) + "}",
            "} {\n} {\n} {",
            "\u0000{}",
        )
        for (w in weird) {
            MindMap.parse(w) // 不抛就算过
        }
    }
}
