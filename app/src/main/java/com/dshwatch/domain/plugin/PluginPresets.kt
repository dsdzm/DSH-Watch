package com.dshwatch.domain.plugin

import com.dshwatch.domain.PromptPreset
import com.dshwatch.domain.model.AppSettings

/**
 * 插件带进来的**对话预设**。
 *
 * 对应 dsh-purge 的 `preset install`：那边把一整套提示词写进宿主的 profile，
 * 这边没有 profile 可写 —— 也不该写。做成「插件自带几条预设，用户在预设
 * 选择器里选」，换来两件东西：
 *
 * · **可见**：选择器里能看见它，而不是装完不知道有没有生效；
 * · **可撤销**：关掉插件，预设跟着消失。写进 profile 的预设没有这个退路。
 *
 * ## 只认「已启用」的插件
 *
 * 插件默认关着（见 [ManifestPlugin]），所以导入之后选择器里不会立刻多出
 * 几条陌生的预设。用户打开开关，才看见它们 —— 顺序和「先看再开」一致。
 */
object PluginPresets {

    /**
     * 全局 id 的分隔符。
     *
     * 用 `/` 是因为它不会出现在插件 id 或预设 id 里（两边都只允许
     * 小写字母、数字、下划线与短横线），拼出来不可能撞上内置的那六个 id。
     */
    private const val SEP = "/"

    fun idOf(pluginId: String, presetId: String): String = pluginId + SEP + presetId

    /** 当前启用的插件提供的全部预设，按插件顺序排列 */
    fun of(settings: AppSettings): List<PromptPreset> {
        val enabled = settings.pluginsResolved(PluginRegistry.defaultEnabled())
        if (enabled.isEmpty()) return emptyList()
        return manifests(settings)
            .filter { it.id in enabled }
            .flatMap { manifest ->
                manifest.presets.map { part ->
                    PromptPreset(
                        id = idOf(manifest.id, part.id),
                        name = part.name,
                        description = part.description.trim(),
                        systemPrompt = part.systemPrompt.trim(),
                        source = manifest.id,
                        sourceName = manifest.name,
                        opening = part.opening.trim(),
                    )
                }
            }
    }

    /**
     * 这条预设要自动插入的开场白；不是插件预设、或没写、或插件已经被关掉，
     * 一律返回空串。
     */
    fun openingOf(presetId: String, settings: AppSettings): String =
        of(settings).firstOrNull { it.id == presetId }?.opening.orEmpty()

    /**
     * 同一个 id 可能同时存在于目录扫描与导入两份里。
     *
     * 与 `PluginRegistry.allFor` 保持同一套优先级：导入的那份优先，
     * 否则用户刚粘进来的新版清单会被目录里的旧版覆盖，表现为「导入了却没变」。
     */
    private fun manifests(settings: AppSettings): List<PluginManifest> =
        (settings.importedPlugins + settings.pluginManifests).distinctBy { it.id }
}
