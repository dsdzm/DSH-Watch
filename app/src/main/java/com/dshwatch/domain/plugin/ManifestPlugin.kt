package com.dshwatch.domain.plugin

import com.dshwatch.domain.model.AppSettings

/**
 * 把一份 [PluginManifest] 变成真正生效的插件。
 *
 * 它只做三件事：按条件判断这一轮是否插手、把文本里的变量填掉、
 * 把工具名单原样交给引擎。没有反射、没有脚本、没有动态加载 ——
 * 同一份清单在任何设备上、任何版本上行为都一样。这是「可审计」的前提。
 *
 * ## 默认关闭，且必须由用户主动开
 *
 * 插件改的是模型的自我认知与可用能力。出厂默认开等于替用户决定
 * 「你的助手应该是什么样、能做什么」，而这些清单是用户自己放进来或导入的 ——
 * 他知道自己在装什么，那就让他再点一次开关。
 *
 * 导入后默认关闭还有一层作用：**用户可以先看效果再决定**。
 * 打开之后模型话变少了、工具不见了，他能在下一句话里立刻联回到这一个开关。
 */
class ManifestPlugin(val manifest: PluginManifest) : PromptPlugin, GuardPlugin {

    override val id: String = manifest.id

    override val defaultEnabled: Boolean = false

    override val displayName: String? = manifest.name.takeIf { it.isNotBlank() }

    /**
     * 说明里拼上「它动了什么」。
     *
     * 这一页真正的风险不是点错，而是开了却不知道开了什么。用户过两周发现
     * 「模型怎么不写文件了」，很难想到是某个插件的 `tools.hide` 里有 `write_file`。
     * 把它写进开关下面那行字，是唯一不依赖用户记性的做法。
     */
    override val displayDesc: String?
        get() {
            val base = manifest.description.takeIf { it.isNotBlank() }.orEmpty()
            val impacts = mutableListOf<String>()
            val t = manifest.tools
            if (t != null) {
                if (t.hide.isNotEmpty()) impacts += "禁用 ${t.hide.size} 个工具"
                if (t.only.isNotEmpty()) impacts += "只保留 ${t.only.size} 个工具"
                if (t.confirm.isNotEmpty()) impacts += "${t.confirm.size} 个改为每次确认"
            }
            if (impacts.isEmpty()) return base.takeIf { it.isNotEmpty() }
            val tail = impacts.joinToString("、")
            return if (base.isEmpty()) tail else "$base · $tail"
        }

    // ---------------- 提示词 ----------------

    override fun identity(ctx: PromptContext): String? {
        if (!applies(ctx.settings)) return null
        return fill(manifest.prompt?.identity, ctx).takeIf { it.isNotBlank() }
    }

    override fun contribute(ctx: PromptContext): String {
        if (!applies(ctx.settings)) return ""
        return fill(manifest.prompt?.append, ctx)
    }

    // ---------------- 工具面 ----------------

    override fun denyTools(settings: AppSettings): Set<String> {
        if (!applies(settings)) return emptySet()
        return manifest.tools?.hide.orEmpty().toSet()
    }

    override fun allowOnly(settings: AppSettings): Set<String>? {
        if (!applies(settings)) return null
        return manifest.tools?.only.orEmpty().takeIf { it.isNotEmpty() }?.toSet()
    }

    override fun confirmTools(settings: AppSettings): Set<String> {
        if (!applies(settings)) return emptySet()
        return manifest.tools?.confirm.orEmpty().toSet()
    }

    /**
     * 条件不匹配就整份失效，而不是「只跳过条件那一段」。
     *
     * 一份清单是给一个场景写的组合（身份 + 规则 + 工具面），拆开用会得到
     * 一个谁都没设计过的状态：换了模型之后模型还是那个人格，但工具已经
     * 换成另一套。宁可不生效，也不要半生效。
     *
     * `tools.when` 优先级低于 `prompt.when`：两者都在时不取交集 ——
     * 交集会让「A 段给 deepseek、B 段给 qwen」这种写法变成两边都不生效，
     * 而用户的本意通常是给整个人设换个适用范围。取 prompt 的那份更符合直觉。
     */
    private fun applies(settings: AppSettings): Boolean {
        val model = settings.activeProvider?.defaultModel.orEmpty()
        val cond = manifest.prompt?.condition ?: manifest.tools?.condition
        return globMatches(cond?.model.orEmpty(), model)
    }

    private fun fill(text: String?, ctx: PromptContext): String =
        ManifestVars.fill(text.orEmpty().trim(), ctx)
}
