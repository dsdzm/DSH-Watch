package com.dshwatch.domain.agent.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * workflow 的 `inputs` 声明解析。
 *
 * 这个解析器存在的理由是一次真机故障：用户用 `dsh-build.yml` 构建 APK，
 * 而工具无条件往 inputs 里塞 `command` —— 那个模板只认 `variant` / `with_python`，
 * 于是 GitHub 返回 422 `Unexpected inputs provided: ["command"]`。
 * 提示文案还说"通常是分支名不存在"，把人带到完全错误的方向。
 *
 * 测试直接用仓库里的两个真实模板，这样模板哪天改了 input 名，这里会先炸。
 */
class CloudTargetsInputsTest {

    private fun asset(name: String): String {
        val candidates = listOf(
            File("src/main/assets/cloud/$name"),
            File("app/src/main/assets/cloud/$name"),
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `run template declares command and artifact options`() {
        val inputs = CloudTargets.parseDispatchInputs(asset("dsh-run.yml"))

        assertThat(inputs).containsAtLeast(
            "command", "artifact_name", "artifact_path",
        )
        // command 必须是其中之一 —— 打包内联就是往它里面塞的
        assertThat(inputs).contains("command")
    }

    @Test
    fun `build template does NOT declare command`() {
        // 这条就是那次故障的核心：两个模板的参数集合完全不同
        val inputs = CloudTargets.parseDispatchInputs(asset("dsh-build.yml"))

        assertThat(inputs).containsAtLeast("variant", "with_python")
        assertThat(inputs).doesNotContain("command")
    }

    @Test
    fun `single-line trigger has no inputs`() {
        // `on: workflow_dispatch` 合法，但它没法带 inputs —— 返回 null 表示"看不懂"
        val yaml = """
            name: X
            on: workflow_dispatch
            jobs:
              a:
                runs-on: ubuntu-latest
        """.trimIndent()

        assertThat(CloudTargets.parseDispatchInputs(yaml)).isNull()
    }

    @Test
    fun `dispatch without inputs block returns empty set`() {
        // 空集 = "明确声明了没有 inputs"，与 null（看不懂）必须区分：
        // 前者传任何参数都会 422，后者只是我们没看懂、不该拦
        val yaml = """
            on:
              workflow_dispatch:
            jobs:
              a:
                runs-on: ubuntu-latest
        """.trimIndent()

        assertThat(CloudTargets.parseDispatchInputs(yaml)).isEmpty()
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val yaml = """
            on:
              workflow_dispatch:
                inputs:
                  # 这一行是注释
                  alpha:
                    description: '第一个'

                  beta:
                    description: '第二个'
        """.trimIndent()

        assertThat(CloudTargets.parseDispatchInputs(yaml))
            .containsExactly("alpha", "beta")
    }

    @Test
    fun `multi-line description does not leak nested keys`() {
        // description 用 `|` 是常见写法，块里的内容缩进更深，不能被当成 input 名
        val yaml = """
            on:
              workflow_dispatch:
                inputs:
                  command:
                    description: |
                      say:
                        hello
                      world:
                    required: true
                  other:
                    description: 'x'
        """.trimIndent()

        assertThat(CloudTargets.parseDispatchInputs(yaml))
            .containsExactly("command", "other")
    }

    @Test
    fun `inputs block ends at the next top-level key`() {
        val yaml = """
            on:
              workflow_dispatch:
                inputs:
                  command:
                    required: true
            permissions:
              contents: read
        """.trimIndent()

        val inputs = CloudTargets.parseDispatchInputs(yaml)
        assertThat(inputs).containsExactly("command")
        // "permissions" 属于顶层，绝不能混进来
        assertThat(inputs).doesNotContain("permissions")
        assertThat(inputs).doesNotContain("contents")
    }

    @Test
    fun `rejected inputs are parsed from the real 422 message`() {
        // 这段就是用户真机上报的原文
        val message = "请求被拒绝（通常是分支名不存在，或 workflow 的 inputs 与模板不匹配）。\n" +
            """原始响应：{"message":"Unexpected inputs provided: [\"command\"]",""" +
            """"documentation_url":"https://docs.github.com/rest/actions/workflows#create-a-workflow-dispatch-event","status":"422"}"""

        assertThat(CloudTargets.parseRejectedInputs(message)).containsExactly("command")
    }

    @Test
    fun `multiple rejected inputs are all parsed`() {
        val message = """{"message":"Unexpected inputs provided: [\"a\", \"b\"]"}"""
        assertThat(CloudTargets.parseRejectedInputs(message)).containsExactly("a", "b")
    }

    @Test
    fun `message without the marker yields nothing`() {
        assertThat(CloudTargets.parseRejectedInputs("""{"message":"Not Found"}""")).isEmpty()
    }
}
