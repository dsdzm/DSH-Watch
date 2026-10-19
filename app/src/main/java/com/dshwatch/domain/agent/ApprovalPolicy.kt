package com.dshwatch.domain.agent

import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import kotlinx.serialization.json.JsonObject

/**
 * 工具审批策略。
 *
 * 从 [com.dshwatch.domain.ChatEngine] 里抽出来，原因有两个：
 *  1. 这段逻辑是**安全边界**，必须能被单测直接钉住，而不是只能靠 UI 手点；
 *  2. 它需要读工具参数（`run_script` 的 `language` 决定要不要拦），
 *     放在引擎里会让 `needsApproval(settings, tool)` 的签名不断加参数。
 *
 * ## 规则
 *
 * | 工具 | 行为 |
 * |---|---|
 * | `run_script` + `language=shell` | **无论什么模式都要确认**；用户可选「本会话允许」，之后本会话内不再问 |
 * | `run_script` + `js` / `python` | 按 Agent 模式（全自动直接跑） |
 * | `cloud_build` / `cloud_job` | **始终确认** —— 会把代码发到外部仓库，且一次构建要几分钟 |
 * | 其他 | 按模式与安全等级（只读自动模式下只读工具免问） |
 *
 * 为什么 shell 单独拎出来：它是唯一「一条命令就能把设备上的东西改掉/删掉」的入口，
 * 而 js/python 跑在受控执行器里、参数与超时都有边界。把两者混在一个名字下，
 * 结果就是要么全都问（用户被烦死）、要么全都不问（误删风险）。
 */
object ApprovalPolicy {

    /** 无论模式与「总是允许」如何，都必须确认的工具 */
    val ALWAYS_CONFIRM: Set<String> = setOf("cloud_build", "cloud_job")

    private const val RUN_SCRIPT = "run_script"
    private const val SHELL_KEY = "run_script:shell"

    /** 取 `run_script` 的语言；缺省按 shell 处理（更保守） */
    fun languageOf(args: JsonObject): String =
        args.strOf("language")?.trim()?.lowercase() ?: "shell"

    /** 会话级放行的键；非会话级工具返回 null */
    fun sessionKey(toolName: String, args: JsonObject): String? =
        if (toolName == RUN_SCRIPT && languageOf(args) == "shell") SHELL_KEY else null

    /** 这个工具的确认是否属于「本会话允许」语义（决定弹窗显示哪组按钮） */
    fun isSessionScoped(toolName: String, args: JsonObject): Boolean =
        sessionKey(toolName, args) != null

    fun needsApproval(
        settings: AppSettings,
        toolName: String,
        safety: ToolSafety,
        args: JsonObject,
        sessionApproved: Set<String>,
    ): Boolean {
        // shell：永远拦，除非本会话已经放行
        if (toolName == RUN_SCRIPT && languageOf(args) == "shell") {
            return SHELL_KEY !in sessionApproved
        }
        // 云端：发代码到外部 + 耗时操作，始终确认
        if (toolName in ALWAYS_CONFIRM) return true

        if (toolName in settings.autoApprovedTools) return false
        return when (settings.agentMode) {
            AgentMode.OFF, AgentMode.AUTO_ALL -> false
            AgentMode.ASK_EVERY_STEP -> true
            AgentMode.AUTO_READONLY -> safety != ToolSafety.READ_ONLY
        }
    }
}
