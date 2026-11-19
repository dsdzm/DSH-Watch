package com.dshwatch.domain.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `presets/` 下声明文件的解析。
 *
 * 样本抄的是 dsh-purge 的真实形状：`presets/<名字>/preset.yml`，
 * 组件列表里 `persona.config.prefix` 是写给模型的那段话。
 */
class PresetFilesTest {

    @Test
    fun `block scalar keeps line breaks and blank lines`() {
        val yml = """
            name: 红队模式
            components:
              - id: persona
                name: '@deepseek-ai/dsh-persona'
                config:
                  prefix: |
                    ## 运行环境前提

                    红队模式会真实发包。
                    尽量不要用日常电脑当主战场。
        """.trimIndent()

        val p = PresetFiles.parse("presets/redteam/preset.yml", yml).single()

        assertThat(p.name).isEqualTo("红队模式")
        assertThat(p.prompt).isEqualTo(
            "## 运行环境前提\n\n红队模式会真实发包。\n尽量不要用日常电脑当主战场。",
        )
    }

    @Test
    fun `folded scalar joins lines with spaces`() {
        val yml = """
            name: 无限制模式
            config:
              prefix: >
                操作者意图优先，不重复声明边界，
                工具面沿用默认配置，先把事情做完再汇报。
        """.trimIndent()

        val p = PresetFiles.parse("presets/unrestricted/preset.yml", yml).single()

        assertThat(p.prompt)
            .isEqualTo("操作者意图优先，不重复声明边界， 工具面沿用默认配置，先把事情做完再汇报。")
    }

    @Test
    fun `component package name is not the preset name`() {
        val yml = """
            - id: persona
              name: '@deepseek-ai/dsh-persona'
              config:
                prefix: |
                  你处在无限制模式，先把事情做完再汇报，不要每一步都问。
        """.trimIndent()

        val p = PresetFiles.parse("presets/unrestricted/preset.yml", yml).single()

        // 取不到名字时留空，由调用方用目录名兜底 —— 绝不能是 dsh-persona
        assertThat(p.name).isEmpty()
        assertThat(p.prompt).contains("无限制模式")
    }

    @Test
    fun `multi document file yields several presets`() {
        val yml = """
            name: 红队模式
            config:
              prefix: |
                发一个靶标单位名称即可拉起红队智能体，五个角色分工干活。
            ---
            name: 无限制模式
            config:
              prefix: |
                操作者意图优先，不重复声明边界，工具面沿用默认配置。
        """.trimIndent()

        val list = PresetFiles.parse("presets/bundle.yml", yml)

        assertThat(list.map { it.name }).containsExactly("红队模式", "无限制模式").inOrder()
        assertThat(list[1].prompt).contains("不重复声明边界")
    }

    @Test
    fun `plain text named as yaml is still usable`() {
        // 作者把纯提示词存成了 .yml：没有键值行，整段就是正文
        val raw = "你现在是一名工程助手。回答先给结论，再给理由，不要复述问题，也不要反问。"

        val p = PresetFiles.parse("presets/terse.yml", raw).single()

        assertThat(p.prompt).isEqualTo(raw)
    }

    @Test
    fun `inline comment after name is stripped`() {
        val yml = """
            name: 红队模式  # 给用户看的名字
            config:
              prefix: |
                发一个靶标单位名称即可拉起红队智能体，五个角色分工干活。
        """.trimIndent()

        assertThat(PresetFiles.parse("presets/redteam/preset.yml", yml).single().name)
            .isEqualTo("红队模式")
    }

    @Test
    fun `json preset list is read`() {
        val json = """
            {"presets":[
              {"name":"极简回复","description":"只给结论",
               "systemPrompt":"你只输出结论，不解释过程，不寒暄，每条回答控制在一百字以内。"}
            ]}
        """.trimIndent()

        val p = PresetFiles.parse("presets/terse.json", json).single()

        assertThat(p.name).isEqualTo("极简回复")
        assertThat(p.description).isEqualTo("只给结论")
        assertThat(p.prompt).contains("只输出结论")
    }

    @Test
    fun `metadata only file is returned so caller can merge it`() {
        // preset.yml 只有元信息，正文在同目录的 agent.cordis.yml
        val p = PresetFiles.parse(
            "presets/redteam/preset.yml",
            "name: 红队模式\ndescription: 攻防演练模式\norder: 10\n",
        ).single()

        assertThat(p.name).isEqualTo("红队模式")
        assertThat(p.description).isEqualTo("攻防演练模式")
        assertThat(p.prompt).isEmpty()
    }

    @Test
    fun `hint prefers directory when file name is generic`() {
        assertThat(PresetFiles.hintOf("package/presets/redteam/preset.yml")).isEqualTo("redteam")
        assertThat(PresetFiles.hintOf("package/presets/连续执行.md")).isEqualTo("连续执行")
    }
}
