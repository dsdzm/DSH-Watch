package com.dshwatch.domain.plugin

/**
 * `dsh-fengshen`（风神）—— 首轮路由。
 *
 * 思路来自用户在别的工具链上用的同名插件：**读会话第一条消息，判断这是
 * 「修 bug」还是「建功能」，据此决定首轮给模型什么人格、把工具面收窄到哪几个，
 * 第一次工具调用之后完全放权。**
 *
 * ## 为什么值得做
 *
 * 首轮是唯一能低成本"定调"的时机。模型前几轮的注意力会被工具清单摊薄 ——
 * 31 个工具摆在那儿，它容易挑一个不合适的；而收窄到 4 个，它只能走"读→改→跑"这条线。
 * 代价是首轮能做的事件变少，但首轮本来也不该干别的：先看清楚再动手，或者先动手再迭代。
 *
 * ## 为什么判据是本地的
 *
 * 用另一次模型调用来分类要多花一次请求（手表上还慢）。关键词打分是确定性的：
 * 同样的输入永远得到同样的结果，出问题能复现，也不会因为路由请求失败把整轮卡住。
 *
 * ## 工具面为什么是 4 个而不是 2 个
 *
 * 风神原设计在 Claude Code 语境里是 `shell` + `str_replace_editor` 两个工具 ——
 * 那边一个 `str_replace_editor` 既能读也能改（view/create/str_replace 三个命令）。
 * 本项目读写改是三个独立工具，只给两个的话模型会没有读取手段，
 * 只能靠 `run_script` 里的 `cat` 绕过去 —— 那更容易出错。所以映射成
 * `read_file` + `write_file` + `edit_file` + `run_script` 这一组最小闭环。
 */
object FengshenPlugin : RoutingPlugin {

    override val id: String = "dsh-fengshen"

    /** 默认关：它会明显改变首轮的可见行为，由用户主动开 */
    override val defaultEnabled: Boolean = false

    enum class TaskKind { SPEC, REACT }

    /**
     * 首轮工具面。
     *
     * 注意：这只是"少给模型看"，不是安全边界（`ToolRegistry` 仍认全部工具）。
     * 收窄的目的是引导注意力，权限控制仍走审批流程。
     */
    val FIRST_TURN_TOOLS: Set<String> = setOf("read_file", "write_file", "edit_file", "run_script")

    override fun overlay(ctx: TurnContext): TurnOverlay? {
        // 只在首轮插手。之后完全放权 —— 尤其不能一直收窄：
        // 真实任务里经常需要第 2 步就用 grep 或 build_project。
        if (ctx.step > 1) return null
        val kind = classify(ctx.firstUserMessage)
        return TurnOverlay(
            promptSuffix = persona(kind),
            allowedTools = FIRST_TURN_TOOLS,
        )
    }

    /**
     * 关键词打分。
     *
     * 分"强信号"与"弱信号"：强信号是**几乎只在这个场景出现**的词
     * （崩溃、traceback、重构），弱信号是两头都能用的词（修、改、做）。
     * 强 2 分、弱 1 分，平局或都不命中时选 REACT ——
     * 因为"建东西"是更常见的诉求，误判成 REACT 只是让模型更积极动手，
     * 误判成 SPEC 则会让它在那儿反复"先确认再确认"，什么都不做。
     */
    fun classify(message: String): TaskKind {
        if (message.isBlank()) return TaskKind.REACT
        val text = message.lowercase()

        var spec = 0
        var react = 0
        SPEC_STRONG.forEach { if (text.contains(it)) spec += 2 }
        SPEC_WEAK.forEach { if (text.contains(it)) spec += 1 }
        REACT_STRONG.forEach { if (text.contains(it)) react += 2 }
        REACT_WEAK.forEach { if (text.contains(it)) react += 1 }

        return if (spec > react) TaskKind.SPEC else TaskKind.REACT
    }

    private fun persona(kind: TaskKind): String = when (kind) {
        TaskKind.SPEC -> """
            【本轮判定：修复（spec）】
            先定位，再动手：
            1. 先拿到真实证据 —— 读相关文件、跑一下复现。没看到真实报错之前不要猜着改
            2. 找最小改动点。不顺手重构、不动无关格式、不"顺便优化"
            3. 改完说清"为什么这样能解决"，而不是罗列"我改了哪几行"
            如果证据不足以定位，直接问用户要（日志、复现步骤），不要凭印象编一个原因。
        """.trimIndent()

        TaskKind.REACT -> """
            【本轮判定：新建（react）】
            直接动手：
            1. 先看清现有结构（要改的文件、入口在哪），然后开始写
            2. 边写边验证 —— 写完一个文件就 build_project 或 run_script 跑一次，别攒到最后
            3. 少解释，把产物路径和下一步说清楚就够了
            不确定需求时就按最合理的默认做，做出来再让用户看着改。
        """.trimIndent()
    }

    private val SPEC_STRONG = listOf(
        "崩溃", "报错", "异常", "traceback", "stacktrace", "stack trace",
        "不工作", "用不了", "跑不起来", "打不开", "闪退", "白屏",
        "bug", "crash", "exception", "regression", "回归", "复现",
    )

    private val SPEC_WEAK = listOf(
        "修复", "修一下", "修下", "排查", "定位", "为什么", "怎么会",
        "失败", "错误", "不对", "有问题", "fix", "debug", "broken",
    )

    private val REACT_STRONG = listOf(
        "新增", "实现", "重构", "做一个", "开发一个", "写一个", "加一个",
        "implement", "refactor", "build a", "create a", "new feature",
    )

    private val REACT_WEAK = listOf(
        "添加", "支持", "加个", "改成", "换成", "优化",
        "add", "support", "create", "change", "update",
    )
}
