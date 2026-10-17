package com.dshwatch.domain.plugin

import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 插件开关的解析。
 *
 * 这里有一个容易搞错的地方：`enabledPlugins` 是空集合时有两种截然不同的含义 ——
 * "用户把插件全关了"和"老版本升级上来、从没设置过"。前者必须尊重，后者要套默认值。
 * 用一个显式的 `pluginsCustomized` 区分，而不是在空集合上猜。
 */
class PluginRegistryTest {

    @Test
    fun `fresh install uses plugin defaults`() {
        val registry = PluginRegistry.from(AppSettings())

        // token-saver 与 whale 出厂开启；save-token 会改写内容，fengshen 会改变首轮行为
        assertThat(registry.active.map { it.id })
            .containsExactly("dsh-token-saver", "dsh-whale")
    }

    @Test
    fun `customized empty set means user disabled everything`() {
        val registry = PluginRegistry.from(
            AppSettings(enabledPlugins = emptySet(), pluginsCustomized = true)
        )

        assertThat(registry.active).isEmpty()
        assertThat(registry.hasResultPlugins).isFalse()
        assertThat(registry.promptSuffix(PromptContext(AppSettings(), "progress/x"))).isEmpty()
        assertThat(registry.overlay(TurnContext(1, "修复崩溃", AppSettings()))).isNull()
    }

    @Test
    fun `customized selection is respected`() {
        val registry = PluginRegistry.from(
            AppSettings(enabledPlugins = setOf("dsh-fengshen"), pluginsCustomized = true)
        )

        assertThat(registry.active.map { it.id }).containsExactly("dsh-fengshen")
        assertThat(registry.hasResultPlugins).isFalse()
    }

    @Test
    fun `unknown ids are ignored`() {
        // 插件被删掉之后，老设置里可能还留着它的 id
        val registry = PluginRegistry.from(
            AppSettings(
                enabledPlugins = setOf("dsh-token-saver", "dsh-plugin-that-no-longer-exists"),
                pluginsCustomized = true,
            )
        )

        assertThat(registry.active.map { it.id }).containsExactly("dsh-token-saver")
    }

    @Test
    fun `writer plugins are off by default`() {
        // save-token 改写模型看到的形态，fengshen 改写首轮行为 —— 都该由用户主动开
        val defaults = PluginRegistry.defaultEnabled()
        assertThat(defaults).doesNotContain("dsh-plugin-save-token")
        assertThat(defaults).doesNotContain("dsh-fengshen")
    }

    @Test
    fun `overlay returns null when routing plugin is off`() {
        val registry = PluginRegistry.from(AppSettings())
        assertThat(registry.overlay(TurnContext(1, "修复崩溃", AppSettings()))).isNull()
    }
}
