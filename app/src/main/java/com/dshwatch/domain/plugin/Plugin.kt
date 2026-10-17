package com.dshwatch.domain.plugin

import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.model.AppSettings

/**
 * 插件 = 编译进 App 的一段可选行为，用户在设置里逐个开关。
 *
 * 刻意**不做**动态加载（DexClassLoader 那种）：
 * 手表上既没有插件市场，也没有签名校验链，动态加载只会多一个"能执行任意代码"
 * 的入口 —— 而本项目已经有一个需要每次确认的 `run_script` 了，不需要第二个。
 * 这里的"插件"是**开关**，不是安装包。
 *
 * 三个 hook 对应三类干预点，互不重叠：
 *
 * | 接口 | 干预什么 | 谁在用 |
 * | --- | --- | --- |
 * | [ToolResultPlugin] | 工具结果进模型上下文之前的文本 | 省 token 类 |
 * | [PromptPlugin] | 系统提示的追加段 | 风格/人格类 |
 * | [RoutingPlugin] | 某一轮的提示追加 + 工具面 | 首轮路由（风神） |
 *
 * 标题与说明不放在接口里，而是走 `AppStrings.pluginTitle/pluginDesc` ——
 * 项目里所有面向用户的文案只有一个真源，双语不会漏。
 */
interface Plugin {
    val id: String

    /** 出厂默认是否开启。改写内容类的插件必须默认关 */
    val defaultEnabled: Boolean
}

/** 工具结果变换：拿到的已经是截断后的文本（[ToolResultInput.content]） */
interface ToolResultPlugin : Plugin {
    suspend fun transform(input: ToolResultInput): String
}

/** 系统提示追加。返回空串表示不插手 */
interface PromptPlugin : Plugin {
    fun contribute(ctx: PromptContext): String
}

/**
 * 按轮次覆盖。返回 null 表示这一轮不插手。
 *
 * 之所以按轮而不是一次性：风神要在**首轮**收窄工具面，之后就完全放权 ——
 * 一次性注入没法表达"只在首轮生效"。
 */
interface RoutingPlugin : Plugin {
    fun overlay(ctx: TurnContext): TurnOverlay?
}

/**
 * 工具结果的上下文。
 *
 * [content] 是经过长度保护后的文本（见 `ChatEngine.MAX_TOOL_RESULT_CHARS`），
 * 不是原始全量输出 —— 手表上不可能把几十 MB 的输出留在内存里等插件处理。
 * 插件要做"原文可还原"，落盘的是它拿到的这一份。
 */
data class ToolResultInput(
    val toolName: String,
    val content: String,
    /** 工具声明的产物路径（相对会话目录），可能为空 */
    val artifact: String?,
    /** 本会话目录（相对工作空间根），例如 `progress/a1b2c3` */
    val sessionDir: String,
    val workspace: WorkspaceStore,
    val settings: AppSettings,
)

data class PromptContext(
    val settings: AppSettings,
    /** 本会话工作目录（相对工作空间根） */
    val workDir: String,
)

data class TurnContext(
    /** 从 1 开始 */
    val step: Int,
    /** 会话里第一条用户消息 —— 首轮路由靠它判任务类型 */
    val firstUserMessage: String,
    val settings: AppSettings,
)

/**
 * 某一轮的覆盖项。
 *
 * [allowedTools] 为 null = 不限制；非 null 时只把名单里的工具发给模型。
 * 注意这**只是"少给模型看"**，不是安全边界 —— `ToolRegistry` 仍然认全部工具，
 * 模型若幻觉出一个没在名单里的工具名，照常会被执行。收窄的用途是引导注意力，
 * 真正的权限控制走审批流程。
 */
data class TurnOverlay(
    val promptSuffix: String = "",
    val allowedTools: Set<String>? = null,
)
