package com.dshwatch.domain.plugin

import com.dshwatch.core.Logx
import com.dshwatch.domain.model.AppSettings

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
class PluginRegistry(
    private val enabled: Set<String>,
    all: List<Plugin> = ALL,
) {

    val active: List<Plugin> = all.filter { it.id in enabled }

    private val resultPlugins: List<ToolResultPlugin> = active.filterIsInstance<ToolResultPlugin>()
    private val promptPlugins: List<PromptPlugin> = active.filterIsInstance<PromptPlugin>()
    private val routingPlugins: List<RoutingPlugin> = active.filterIsInstance<RoutingPlugin>()
    private val guardPlugins: List<GuardPlugin> = active.filterIsInstance<GuardPlugin>()

    val hasResultPlugins: Boolean get() = resultPlugins.isNotEmpty()

    // ---------------- 工具面 ----------------

    /**
     * 本会话禁用的工具：所有 guard 插件的并集。
     *
     * 多个插件都禁同一个工具时并集没有歧义；而「一个插件禁、另一个插件
     * 只在白名单里没列」也不会互相抵消 —— 见 [allowOnlyTools] 取交集的说明。
     */
    fun deniedTools(settings: AppSettings): Set<String> {
        if (guardPlugins.isEmpty()) return emptySet()
        return guardPlugins.flatMap { plugin ->
            runCatching { plugin.denyTools(settings) }.getOrElse { t ->
                Logx.w(TAG, "插件 ${plugin.id} 计算禁用工具失败，已跳过", t)
                emptySet()
            }
        }.toSet()
    }

    /**
     * 白名单：多个插件时取**交集**。
     *
     * 这里和 [overlay] 的「取第一个」不一样，是故意的：
     *
     * · 白名单是准入名单，交集只会更严，而更严不会制造安全事故；
     * · 两个插件各自想「只留只读工具」时，交集正好是双方都能接受的；
     * · 并集则会悄悄多放行一批双方都没打算开的能力 —— 这才是危险的。
     */
    fun allowOnlyTools(settings: AppSettings): Set<String>? {
        if (guardPlugins.isEmpty()) return null
        val sets = guardPlugins.mapNotNull { plugin ->
            runCatching { plugin.allowOnly(settings) }.getOrElse { t ->
                Logx.w(TAG, "插件 ${plugin.id} 计算工具白名单失败，已跳过", t)
                null
            }
        }
        if (sets.isEmpty()) return null
        return sets.reduce { a, b -> a intersect b }
    }

    /** 本会话必须逐次确认的工具：并集（加严，多一个不会出事） */
    fun confirmTools(settings: AppSettings): Set<String> {
        if (guardPlugins.isEmpty()) return emptySet()
        return guardPlugins.flatMap { plugin ->
            runCatching { plugin.confirmTools(settings) }.getOrElse { t ->
                Logx.w(TAG, "插件 ${plugin.id} 计算加严工具失败，已跳过", t)
                emptySet()
            }
        }.toSet()
    }

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
     * 身份覆盖段。多个插件时取**第一个**非空，而不是拼接。
     *
     * 身份是单选题：「你是一位严谨的代码审查员」和「你是一位红队测试员」
     * 拼在一起只会得到一个人格分裂的模型。第一个插件说了算，行为可预期。
     */
    fun identityOverride(ctx: PromptContext): String? {
        if (promptPlugins.isEmpty()) return null
        for (plugin in promptPlugins) {
            val text = runCatching { plugin.identity(ctx)?.trim() }
                .getOrElse { t ->
                    Logx.w(TAG, "插件 ${plugin.id} 覆盖身份失败，已跳过", t)
                    null
                }
            if (!text.isNullOrEmpty()) return text
        }
        return null
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

        fun from(settings: AppSettings): PluginRegistry =
            PluginRegistry(
                enabled = settings.pluginsResolved(defaultEnabled()),
                all = allFor(settings),
            )

        /** 出厂默认开启的集合（设置里的初始值） */
        fun defaultEnabled(): Set<String> = ALL.filter { it.defaultEnabled }.map { it.id }.toSet()

        /**
         * 同时存在的声明式插件上限。
         *
         * 目录 12 个 + 导入 12 个，本来可以不设这个数；但插件是**每一轮**
         * 都要参与计算的东西（提示词拼接、工具面求交），装到几十个之后
         * 用户自己都记不清哪个在起作用。16 是「够用且还能一屏看完」的量级。
         */
        const val MAX_MANIFESTS = 16

        /**
         * 当前这台设备上「存在」的全部插件：内置 + 用户清单。
         *
         * 两个来源合并：`pluginManifests` 来自工作空间 `plugins/` 目录扫描，
         * [AppSettings.importedPlugins] 来自剪贴板或链接导入。分开存是因为
         * 重新扫描目录会把前者整个替换掉 —— 如果导入的也塞在同一个列表里，
         * 用户点一次「重新扫描」就会把刚导入的插件全抹掉。
         *
         * 同 id 时导入的那份优先：用户手动粘进来的意图更明确。
         */
        fun allFor(settings: AppSettings): List<Plugin> {
            val manifests = (settings.importedPlugins + settings.pluginManifests)
                .distinctBy { it.id }
                .take(MAX_MANIFESTS)
            if (manifests.isEmpty()) return ALL
            /*
             * 解析失败静默跳过：能走到这里说明它已经在校验阶段被挡过一次，
             * 剩下的是旧版本写下的、字段对不上的清单。为一个坏插件让整轮对话
             * 崩掉，代价远大于收益。
             */
            val custom = manifests.mapNotNull { manifest ->
                runCatching { ManifestPlugin(manifest) }.getOrNull()
            }
            return ALL + custom
        }
    }
}
