package com.dshwatch.domain.plugin

/**
 * 把一份 [PluginManifest] 变成真正生效的插件。
 *
 * 它只做两件事：按条件判断这一轮是否插手，然后把文本里的变量填掉。
 * 没有反射、没有脚本、没有动态加载 —— 同一份清单在任何设备上、任何版本上
 * 行为都一样。这是「可审计」的前提。
 *
 * ## 默认关闭，且必须由用户主动开
 *
 * 提示词类插件改的是模型的自我认知。出厂默认开等于替用户决定
 * 「你的助手应该是什么样」，而这些清单是用户自己放进来的 ——
 * 他知道自己在装什么，那就让他再点一次开关。
 */
class ManifestPromptPlugin(val manifest: PluginManifest) : PromptPlugin {

    override val id: String = manifest.id

    override val defaultEnabled: Boolean = false

    override val displayName: String? = manifest.name.takeIf { it.isNotBlank() }

    override val displayDesc: String? = manifest.description.takeIf { it.isNotBlank() }

    override fun identity(ctx: PromptContext): String? {
        if (!applies(ctx)) return null
        return fill(manifest.prompt?.identity, ctx).takeIf { it.isNotBlank() }
    }

    override fun contribute(ctx: PromptContext): String {
        if (!applies(ctx)) return ""
        return fill(manifest.prompt?.append, ctx)
    }

    /**
     * 条件不匹配就整份失效，而不是「只跳过条件那一段」。
     *
     * 一份清单是给一个场景写的组合（身份 + 规则 + 措辞），拆开用会得到
     * 一个谁都没设计过的提示词。宁可不生效，也不要半生效。
     */
    private fun applies(ctx: PromptContext): Boolean {
        val model = ctx.settings.activeProvider?.defaultModel.orEmpty()
        return globMatches(manifest.prompt?.condition?.model.orEmpty(), model)
    }

    private fun fill(text: String?, ctx: PromptContext): String =
        ManifestVars.fill(text.orEmpty().trim(), ctx)
}
