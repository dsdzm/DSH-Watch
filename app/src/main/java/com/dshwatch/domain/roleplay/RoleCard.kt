package com.dshwatch.domain.roleplay

import kotlinx.serialization.Serializable

/**
 * 使用场景。
 *
 * 三个场景共用同一个 [com.dshwatch.domain.ChatEngine]，差别只在**发给模型什么**：
 *  - [CODING]：完整 Agent（工具、思维链、多步循环）；
 *  - [TAVERN] / [WRITING]：纯对话（`tools` 为空、`agentMode=OFF`），
 *    系统提示由角色卡或写作设定拼出来，且**不显示**思维链与工具卡片。
 *
 * 之所以不做三套引擎：Agent 循环里踩过的坑（tool_calls 配对、取消传递、
 * 流式节流）全都一样，复制一份就等于把那些坑再踩一遍。场景只影响
 * 「提示词」和「界面」，不影响「传输与循环」。
 */
@Serializable
object Scenes {
    const val CODING = "coding"
    const val TAVERN = "tavern"
    const val WRITING = "writing"

    val all = listOf(CODING, TAVERN, WRITING)

    fun of(id: String?): String = if (id in all) id!! else CODING

    fun label(id: String): String = when (of(id)) {
        TAVERN -> "酒馆"
        WRITING -> "写作"
        else -> "编程"
    }

    fun desc(id: String): String = when (of(id)) {
        TAVERN -> "和角色聊天、扮演剧情"
        WRITING -> "设定世界与人物，让模型续写"
        else -> "写代码、跑脚本、做任务"
    }

    /** 这个场景是否需要工具与思维链 */
    fun usesAgent(id: String): Boolean = of(id) == CODING
}

/**
 * 一次角色扮演 / 写作会话的「卡」。
 *
 * 统一成一个数据结构而不是两张表：两者的字段高度重合（名称、描述、
 * 自定义提示、开场白），分开写等于把「导入 / 导出 / 编辑 / 列表」
 * 复制两遍。区别只在于 [kind] 与 [fields] 的语义。
 */
@Serializable
data class RoleCard(
    val id: String = "",
    /** tavern / writing */
    val kind: String = Scenes.TAVERN,
    val name: String = "",
    /** 一句话简介，列表里显示 */
    val tagline: String = "",
    /** 角色设定 / 世界背景。给模型看的正文 */
    val persona: String = "",
    /** 用户扮演的身份（酒馆用；写作时可留空） */
    val userPersona: String = "",
    /** 开场白：第一次对话时以助手身份说出（酒馆）或作为开篇（写作） */
    val greeting: String = "",
    /** 说话风格要求，如「用短句，口语化，不描写心理活动」 */
    val style: String = "",
    /** 结构化字段：年龄 / 性别 / 种族 / 性格 …… 键值对，按顺序渲染 */
    val fields: List<CardField> = emptyList(),
    /** 世界书条目（写作与酒馆共用） */
    val entries: List<LoreEntry> = emptyList(),
    /** 自定义提示词插件 */
    val plugins: List<CardPlugin> = emptyList(),
    /** 是否启用长期记忆（定期压缩上下文并写入记忆文档） */
    val longTermMemory: Boolean = false,
    /** 每轮对话结束后是否自动让模型续写（写作模式用） */
    val autoContinue: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** 内置卡：不允许删除，只能「另存为」再改 */
    val builtin: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { "未命名" }
    val fieldMap: Map<String, String>
        get() = fields.associate { it.key to it.value }
}

@Serializable
data class CardField(
    val key: String = "",
    val value: String = "",
)

/**
 * 世界书条目。
 *
 * [keywords] 为空表示「常驻」—— 每一轮都放进提示；
 * 非空则只在最近的对话里出现这些词时才注入，省 token 也避免无关信息干扰。
 * 手表上模型窗口有限，这一层筛选比手机端更有必要。
 */
@Serializable
data class LoreEntry(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val keywords: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** 注入优先级：数字大的先塞，超出预算时先丢小的 */
    val priority: Int = 0,
) {
    val alwaysOn: Boolean get() = keywords.isEmpty()
}

/**
 * 用户自定义的提示词插件。
 *
 * 与 App 内置插件（风神 / 省 token 等）不是一回事：那些是 `PromptPlugin` 接口的
 * 实现，是代码；这里的插件是**数据**，由用户自己写、自己导入，只往系统提示里
 * 追加一段文字，用来影响生成走向与风格。
 *
 * ⚠️ 类名是 `CardPlugin` 而不是 `PromptPlugin`：后者已经是
 * [com.dshwatch.domain.plugin.PromptPlugin] 那个接口的名字，
 * 同一模块里两个同名顶层类型根本编译不过。
 */
@Serializable
data class CardPlugin(
    val id: String = "",
    val name: String = "",
    val prompt: String = "",
    val enabled: Boolean = true,
    /** 插入位置：system（设定之后）/ last（最后，权重最高） */
    val position: String = POS_SYSTEM,
) {
    companion object {
        const val POS_SYSTEM = "system"
        const val POS_LAST = "last"
    }
}
