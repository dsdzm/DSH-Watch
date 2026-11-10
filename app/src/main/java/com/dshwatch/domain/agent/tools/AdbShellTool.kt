package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.shell.ShellAudit
import com.dshwatch.domain.shell.ShellRisk
import com.dshwatch.domain.shell.ShellRules
import com.dshwatch.domain.shell.ShizukuShell
import kotlinx.serialization.json.JsonObject

/**
 * 以 adb（shell 用户）身份执行命令。
 *
 * 与 [ScriptTools.RunScriptTool] 的区别必须说清楚，否则模型会拿错工具：
 *  - `run_script` 用应用自己的身份跑脚本（写文件、算数、处理文本），不需要额外授权；
 *  - `adb_shell` 借用 Shizuku 的 adb 权限，能碰系统（装应用、读日志、改屏幕、改电池）。
 *
 * `purpose` 是**必填**的，而且是给人看的：审批弹窗最上面那一行就是它。
 * 这不是形式主义 —— 用户按下「允许」前唯一能判断的依据，就是模型说它要干什么，
 * 加上下面那条命令原文是否对得上。
 */
object AdbShellTool : AgentTool {

    override val name = "adb_shell"

    override val description =
        "以 adb（shell 用户，非 root）执行命令，需 Shizuku。" +
            "能装应用、读日志、改屏幕/电池/系统设置、pm grant。" +
            "purpose 必填：一句话说明为什么跑。普通脚本用 run_script。"

    override val safety = ToolSafety.WRITE

    override val enabled: (com.dshwatch.domain.model.AppSettings) -> Boolean = { it.shizukuEnabled }

    override val parameters = Schema.obj(
        properties = mapOf(
            "command" to Schema.str(
                "完整命令，和 adb shell 后面写的一样，支持管道与 &&。例如 pm install -r /sdcard/a.apk",
            ),
            "purpose" to Schema.str("这条命令要达成什么，给用户看的一句话。不要写「执行命令」这种废话"),
        ),
        required = listOf("command", "purpose"),
    )

    override fun summarize(args: JsonObject): String =
        args.strOf("purpose")?.take(60) ?: ShellRules.brief(args.strOf("command").orEmpty())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = ctx.requireContext()
        val command = args.strOf("command")?.trim().orEmpty()
        if (command.isEmpty()) return ToolResult.fail("缺少 command 参数")

        when (ShizukuShell.state(context)) {
            ShizukuShell.State.NOT_INSTALLED -> return ToolResult.fail(
                "设备上没有安装 Shizuku。告诉用户：在设置 → 设备操控 → Shizuku 提权 里可以看安装指引；" +
                    "没有 Shizuku 时只能用 device_* 四个工具做界面操作。",
            )

            ShizukuShell.State.NOT_RUNNING -> return ToolResult.fail(
                "Shizuku 已安装但没有运行。告诉用户：打开 Shizuku，按它提示启动服务" +
                    "（Android 11+ 可用无线调试启动，重启手机后需要重新启动一次）。",
            )

            ShizukuShell.State.NOT_GRANTED -> return ToolResult.fail(
                "Shizuku 在运行，但没给 DSH 授权。让用户到 设置 → 设备操控 → Shizuku 提权 里点「授权」。",
            )

            ShizukuShell.State.READY -> Unit
        }

        val outcome = ShizukuShell.run(command)
        ShellAudit.record(
            context = context,
            command = command,
            risk = ShellRules.classify(command),
            category = ShellRules.categoryOf(command),
            decision = "exec",
            exitCode = outcome.exitCode,
        )

        val text = ShizukuShell.render(outcome, command)
        if (outcome.error.isNotEmpty()) return ToolResult.fail(text)
        return if (outcome.ok) ToolResult.ok(text) else ToolResult.fail(text)
    }
}
