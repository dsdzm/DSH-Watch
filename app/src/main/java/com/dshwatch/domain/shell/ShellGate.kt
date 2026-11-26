package com.dshwatch.domain.shell

import android.content.Context
import com.dshwatch.domain.adb.AdbShell

/**
 * shell 通道的统一入口。
 *
 * 上层工具只需要问「能不能跑、跑出来什么」，不需要知道权限是从 Shizuku 来的
 * 还是从 ADB 来的。两条通道给的都是 **shell 用户（uid 2000）**，能力完全一致，
 * 差别只在怎么拿到授权：
 *
 * - Shizuku：binder 推送，延迟低；服务被杀就得重新启动。
 * - ADB：TCP + RSA 认证，手机会弹一次「允许调试」；设备重启后端口关闭，需要重新授权。
 *
 * ## 优先级
 *
 * Shizuku 在前。它是进程内调用，没有网络握手；ADB 每次都要连一次 socket。
 * 只有在 Shizuku 不可用、或者它半途失效（服务被系统杀掉）时才落到 ADB。
 */
object ShellGate {

    enum class Channel { SHIZUKU, ADB, NONE }

    data class Status(val channel: Channel, val detail: String) {
        val ready: Boolean get() = channel != Channel.NONE
    }

    /** 工具是否应该出现在工具表里。两个开关任一打开即可。 */
    fun available(shizukuEnabled: Boolean, adbEnabled: Boolean): Boolean =
        shizukuEnabled || adbEnabled

    fun status(context: Context): Status {
        val shizuku = ShizukuShell.state(context)
        if (shizuku == ShizukuShell.State.READY) {
            return Status(Channel.SHIZUKU, "Shizuku 已授权")
        }
        if (AdbShell.isConfigured(context)) {
            val ep = AdbShell.savedEndpoint(context)
            return Status(Channel.ADB, "ADB ${ep?.first}:${ep?.second}")
        }
        return Status(Channel.NONE, explain(context, shizuku))
    }

    suspend fun run(
        context: Context,
        command: String,
        timeoutMs: Long = 30_000,
    ): ShellOutcome {
        val shizuku = ShizukuShell.state(context)
        if (shizuku == ShizukuShell.State.READY) {
            /*
             * Shizuku 的 binder 调用可能直接抛异常（服务被杀、远程进程死亡），
             * 以前会一路冒到工具层变成一句「内部异常」。这里把它降级成一条
             * 带原因的失败结果，好让下面的 ADB 通道接手。
             */
            val result = runCatching { ShizukuShell.run(command, timeoutMs) }
                .getOrElse { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    ShellOutcome(
                        -1, "", "",
                        error = "Shizuku 通道异常：${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}",
                    )
                }
            if (result.error.isEmpty() || !AdbShell.isConfigured(context)) return result
        }
        if (AdbShell.isConfigured(context)) {
            return AdbShell.run(context, command, timeoutMs)
        }
        return ShellOutcome(-1, "", "", error = explain(context, shizuku))
    }

    suspend fun runIntoFile(
        context: Context,
        command: String,
        outFile: java.io.File,
        timeoutMs: Long = 20_000,
    ): ShellOutcome {
        val shizuku = ShizukuShell.state(context)
        if (shizuku == ShizukuShell.State.READY) {
            val result = runCatching { ShizukuShell.runIntoFile(command, outFile, timeoutMs) }
                .getOrElse { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    ShellOutcome(
                        -1, "", "",
                        error = "Shizuku 通道异常：${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}",
                    )
                }
            if (result.error.isEmpty() || !AdbShell.isConfigured(context)) return result
        }
        if (AdbShell.isConfigured(context)) {
            return AdbShell.runIntoFile(context, command, outFile, timeoutMs)
        }
        return ShellOutcome(-1, "", "", error = explain(context, shizuku))
    }

    /**
     * 没通道时给用户看的原因。
     *
     * 不是一句「不可用」—— 用户需要知道**下一步点哪里**。两条路都写清，
     * 因为其中一条经常在他这台机器上走不通。
     */
    fun explain(context: Context, shizuku: ShizukuShell.State): String = buildString {
        append("没有可用的 shell 通道。")
        when (shizuku) {
            ShizukuShell.State.NOT_INSTALLED -> append("设备未安装 Shizuku；")
            ShizukuShell.State.NOT_RUNNING -> append("Shizuku 已安装但未运行；")
            ShizukuShell.State.NOT_GRANTED -> append("Shizuku 未授权；")
            ShizukuShell.State.READY -> Unit
        }
        append("可在 设置 → 设备操控 → ADB 直连 中连接 5555 端口，")
        append("连接时手机会弹出「允许调试」，点允许即可。")
    }
}
