package com.dshwatch.domain.plugin

import com.dshwatch.domain.PromptPresets
import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 插件带进来的对话预设。
 *
 * 这一层要保证的是「装了什么、开了什么、选了什么」三件事能对上：
 * 关掉的插件不能往选择器里塞东西，打开之后又要能一眼看出这条是谁带的，
 * 选中之后系统提示必须真的换成那一条。
 */
class PluginPresetsTest {

    private val sample = """
        {
          "schema": 3,
          "id": "dsh-plugin-purge",
          "name": "Purge 预设",
          "description": "连续执行模式的两种写法",
          "presets": [
            {
              "id": "unrestricted",
              "name": "连续执行",
              "description": "少确认、连续跑",
              "systemPrompt": "你处在连续执行模式。",
              "opening": "已进入连续执行模式，直接说做什么。"
            },
            {
              "id": "minimal",
              "name": "极简",
              "systemPrompt": "只给结论。"
            }
          ]
        }
    """.trimIndent()

    private fun manifest(text: String = sample): PluginManifest {
        val parsed = PluginLoader.parse(text)
        assertThat(parsed).isInstanceOf(PluginLoader.Parsed.Ok::class.java)
        return (parsed as PluginLoader.Parsed.Ok).manifest
    }

    private fun settings(m: PluginManifest, enabled: Boolean): AppSettings = AppSettings(
        enabledPlugins = if (enabled) setOf(m.id) else emptySet(),
        pluginsCustomized = true,
        importedPlugins = listOf(m),
    )

    // ================= 解析 =================

    @Test
    fun `带预设的清单能解析`() {
        val m = manifest()
        assertThat(m.presetCount).isEqualTo(2)
        assertThat(m.presets.map { it.id }).containsExactly("unrestricted", "minimal").inOrder()
        // 只有 presets 段、没有 prompt / tools，也是合法的插件
        assertThat(m.prompt).isNull()
        assertThat(m.tools).isNull()
    }

    @Test
    fun `预设 id 重复会被拒`() {
        val bad = sample.replace("\"id\": \"minimal\"", "\"id\": \"unrestricted\"")
        val parsed = PluginLoader.parse(bad)
        assertThat(parsed).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        assertThat((parsed as PluginLoader.Parsed.Err).message).contains("重名")
    }

    @Test
    fun `空 systemPrompt 会被拒`() {
        val bad = sample.replace("\"systemPrompt\": \"只给结论。\"", "\"systemPrompt\": \"\"")
        val parsed = PluginLoader.parse(bad)
        assertThat(parsed).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        assertThat((parsed as PluginLoader.Parsed.Err).message).contains("不能为空")
    }

    @Test
    fun `超过六条会被拒`() {
        val items = (1..7).joinToString(",") { i ->
            """{"id":"p$i","name":"预设$i","systemPrompt":"第 $i 条"}"""
        }
        val bad = """
            {
              "schema": 3,
              "id": "dsh-plugin-many",
              "name": "太多预设",
              "presets": [$items]
            }
        """.trimIndent()
        val parsed = PluginLoader.parse(bad)
        assertThat(parsed).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        assertThat((parsed as PluginLoader.Parsed.Err).message).contains("最多 6 条")
    }

    // ================= 生效范围 =================

    @Test
    fun `插件关着的时候预设不出现`() {
        val m = manifest()
        assertThat(PluginPresets.of(settings(m, enabled = false))).isEmpty()
    }

    @Test
    fun `插件打开后预设进选择器并标出来源`() {
        val m = manifest()
        val list = PluginPresets.of(settings(m, enabled = true))

        assertThat(list).hasSize(2)
        assertThat(list[0].id).isEqualTo("dsh-plugin-purge/unrestricted")
        assertThat(list[0].source).isEqualTo("dsh-plugin-purge")
        assertThat(list[0].sourceName).isEqualTo("Purge 预设")
        assertThat(list[0].fromPlugin).isTrue()
        // 内置的六条永远在前，插件条目排在后面
        assertThat(PromptPresets.available(settings(m, enabled = true))).hasSize(8)
    }

    @Test
    fun `选中插件预设时系统提示用它`() {
        val m = manifest()
        val s = settings(m, enabled = true)
        assertThat(PromptPresets.compose("dsh-plugin-purge/unrestricted", "", s))
            .isEqualTo("你处在连续执行模式。")
        // 自定义补充仍然拼在后面
        assertThat(PromptPresets.compose("dsh-plugin-purge/unrestricted", "中文回答", s))
            .contains("补充要求：")
    }

    @Test
    fun `插件关掉后选中的预设回落而不是报错`() {
        val m = manifest()
        val s = settings(m, enabled = false)
        // byId 回落第一条内置预设，界面不会因为一个失效 id 崩掉
        assertThat(PromptPresets.byId("dsh-plugin-purge/unrestricted", s).id)
            .isEqualTo(PromptPresets.VIBE_CODING)
    }

    @Test
    fun `开场白只在插件启用时返回`() {
        val m = manifest()
        assertThat(PluginPresets.openingOf("dsh-plugin-purge/unrestricted", settings(m, enabled = true)))
            .startsWith("已进入连续执行模式")
        assertThat(PluginPresets.openingOf("dsh-plugin-purge/unrestricted", settings(m, enabled = false)))
            .isEmpty()
        // 没写 opening 的那条返回空串，新建会话不会凭空插一条消息
        assertThat(PluginPresets.openingOf("dsh-plugin-purge/minimal", settings(m, enabled = true)))
            .isEmpty()
    }
}
