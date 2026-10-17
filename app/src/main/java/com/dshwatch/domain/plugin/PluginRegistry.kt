package com.dshwatch.domain.plugin

import com.dshwatch.core.Logx

/**
 * 按用户开关过滤出**实际生效**的插件，并负责调用它们。
 *
 * 每调用一次 `ChatEngine.send` 就构造一个新实例 —— 用户刚在设置页关掉的插件
 * 必须立刻不再生效，而这个类只持有一个 Set，构造成本可以忽略。
 *
 * ## 失败必须降级，且要降成"上一个结果"而不是"原文"
 *
 * 多个 [ToolResultPlugin] 是**串行**叠加的（save-token 压缩后的文本再交给下一个）。
 * 如果某个插件抛异常，正确做法是保留它前一个插件的输出继续往下走 ——
 * 而不是丢掉整条链路回到原文。回到原文的代价是这次对话的上下文里
 * 突然多出几倍 token，而用户完全不知道发生了什么。
 */
class PluginRegistry(private val enabled: Set<String>) {

    val active: List<Plugin> = ALL.filter { it.id in enabled }

    private val resultPlugins: List<ToolResultPlugin> = active.filterIsInstance<ToolResultPlugin>()
    private val promptPlugins: List<PromptPlugin> = active.filterIsInstance<PromptPlugin>()
    private val routingPlugins: List<RoutingPlugin> = active.filterIsInstance<RoutingPlugin>()

    val hasResultPlugins: Boolean get() = resultPlugins.isNotEmpty()

    /** 依次应用所有结果变换插件；任一环节失败则跳过该环节 */
    suspend fun transformToolResult(input: ToolResultInput): String {
        if (resultPlugins.isEmpty()) return input.content
        var text = input.content
        for (plugin in resultPlugins) {
            text = runCatching { plugin.transform(input.copy(content = text)) }
                .getOrElse { t ->
                    Logx.w(TAG, "插件 ${plugin.id} 处理工具结果失败，已跳过该环节", t)
                    text
                }
        }
        return text
    }

    /** 系统提示的追加段；多段之间空行分隔 */
    fun promptSuffix(ctx: PromptContext): String {
        if (promptPlugins.isEmpty()) return ""
        return promptPlugins.mapNotNull { plugin ->
            runCatching { plugin.contribute(ctx).trim() }
                .getOrElse { t ->
                    Logx.w(TAG, "插件 ${plugin.id} 追加提示失败，已跳过", t)
                    ""
                }
                .takeIf { it.isNotEmpty() }
        }.joinToString("\n\n")
    }

    /**
     * 本轮的覆盖项。
     *
     * 多个 routing 插件时取**第一个非 null**，而不是把它们的 allowedTools 求交集：
     * 交集语义会让两个插件互相把工具面削到空，那种故障表现为"模型突然什么都不会做"，
     * 极难联想到插件。第一个非 null 至少行为可预期。
     */
    fun overlay(ctx: TurnContext): TurnOverlay? {
        if (routingPlugins.isEmpty()) return null
        for (plugin in routingPlugins) {
            val result = runCatching { plugin.overlay(ctx) }
                .getOrElse { t ->
                    Logx.w(TAG, "插件 ${plugin.id} 路由失败，已跳过", t)
                    null
                }
            if (result != null) return result
        }
        return null
    }

    companion object {
        private const val TAG = "PluginRegistry"

        /**
         * 全部内置插件。
         *
         * **顺序 = 叠加顺序，且不能随便换**：TOON 编码必须排在文本裁剪之前。
         * 编码器要求 JSON 结构完整，先把 4000 字符裁成 1800 就必然得到一个
         * 半截 JSON —— `parseToJsonElement` 失败，编码自动放弃，
         * 那个插件就等于白开了。反过来就没问题：先编码（结构完整时压掉三到六成），
         * 剩下的超长部分再交给裁剪兜底。
         */
        val ALL: List<Plugin> = listOf(
            SaveTokenPlugin,
            TokenSaverPlugin,
            WhalePlugin,
            FengshenPlugin,
        )

        fun from(settings: com.dshwatch.domain.model.AppSettings): PluginRegistry =
            PluginRegistry(settings.pluginsResolved(defaultEnabled()))

        /** 出厂默认开启的集合（设置里的初始值） */
        fun defaultEnabled(): Set<String> = ALL.filter { it.defaultEnabled }.map { it.id }.toSet()
    }
}
