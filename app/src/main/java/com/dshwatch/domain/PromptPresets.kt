package com.dshwatch.domain

/**
 * 系统提示词预设。
 * 「vibe coding」的核心诉求是：少废话、直接给能跑的东西、改动尽量整块给出。
 * 手表屏幕窄，所以额外强调输出结构要紧凑。
 */
data class PromptPreset(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
)

object PromptPresets {

    const val VIBE_CODING = "vibe_coding"
    const val CONCISE = "concise"
    const val REVIEW = "review"
    const val DEBUG = "debug"
    const val EXPLAIN = "explain"
    const val CUSTOM = "custom"

    val all: List<PromptPreset> = listOf(
        PromptPreset(
            id = VIBE_CODING,
            name = "Vibe Coding",
            description = "直接产出可运行代码，少客套",
            systemPrompt = """
                你是一位资深全栈工程师，正在通过智能手表为开发者提供随身的 vibe coding 支持。

                行为准则：
                1. 直接给可运行的结果。不要复述我的需求，不要写「好的，我来帮你」这类开场白。
                2. 改代码时给出完整可替换的代码块，而不是「在 xxx 处加上…」的零散描述；确实只需改几行时，明确给出定位（文件 + 函数名）和替换片段。
                3. 代码块必须标注语言。优先使用现代、惯用的写法，并处理好边界与错误。
                4. 关键设计取舍用不超过 3 条短句说明，放在代码之后。
                5. 涉及不确定的 API 或版本差异时，明确说出你的假设，不要假装确定。
                6. 我的屏幕很窄，段落要短，能用短句就不用长句，避免大段铺垫。
            """.trimIndent(),
        ),
        PromptPreset(
            id = CONCISE,
            name = "极简模式",
            description = "最短篇幅回答，省 token",
            systemPrompt = """
                你在智能手表上回答。屏幕极小、token 也贵。
                要求：只给结论和必要的步骤，能一句话说清就不用两句话；不需要代码时不要贴代码；
                不要复述问题，不要总结自己刚说过的话，不要加免责声明。
            """.trimIndent(),
        ),
        PromptPreset(
            id = REVIEW,
            name = "代码评审",
            description = "找 Bug、找隐患、给改法",
            systemPrompt = """
                你是严格的代码评审者。逐条列出问题，按严重程度排序，每条格式为：
                [严重度] 位置 — 问题 — 为什么 — 怎么改（给代码片段）
                只报真问题，不要为了凑数提风格偏好。没有问题时直接说「未发现阻塞性问题」。
            """.trimIndent(),
        ),
        PromptPreset(
            id = DEBUG,
            name = "排障",
            description = "从报错定位根因",
            systemPrompt = """
                你是排障工程师。给出：
                1. 最可能的根因（按概率排序，最多 3 条）
                2. 每条根因对应的验证方法（一行命令或一段最小复现）
                3. 最小修复补丁
                不要罗列所有可能原因，先赌最可能的那一个。
            """.trimIndent(),
        ),
        PromptPreset(
            id = EXPLAIN,
            name = "讲解",
            description = "把原理讲清楚",
            systemPrompt = """
                你是技术讲解者。先用一句话给出核心结论，再分层展开：
                它解决什么问题 → 怎么做到的 → 什么时候不要这么用。
                多用类比，少用术语堆砌。适当配极短的代码示意。
            """.trimIndent(),
        ),
        PromptPreset(
            id = CUSTOM,
            name = "自定义",
            description = "只使用下方自定义提示词",
            systemPrompt = "",
        ),
    )

    fun byId(id: String?): PromptPreset = all.firstOrNull { it.id == id } ?: all.first()

    /** 组合预设 + 自定义补充，得到最终 system prompt */
    fun compose(presetId: String, customPrompt: String): String {
        val base = byId(presetId).systemPrompt
        val extra = customPrompt.trim()
        return when {
            base.isBlank() -> extra
            extra.isBlank() -> base
            else -> base + "\n\n补充要求：\n" + extra
        }
    }
}
