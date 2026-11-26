package com.dshwatch.domain.shell

/**
 * 一条 shell 命令的执行结果。
 *
 * 放在这里而不是放在某个具体通道里，是因为通道有两条（Shizuku、ADB），
 * 上层工具只关心「退出码、输出、有没有超时」，不该为了换通道改一遍调用点。
 */
data class ShellOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val error: String = "",
) {
    val ok: Boolean get() = error.isEmpty() && !timedOut && exitCode == 0
}
