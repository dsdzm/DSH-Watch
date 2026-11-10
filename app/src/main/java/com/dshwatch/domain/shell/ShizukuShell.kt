package com.dshwatch.domain.shell

import android.content.Context
import android.content.pm.PackageManager
import com.dshwatch.core.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * 通过 Shizuku 借 adb 权限执行命令。
 *
 * Shizuku 给的是 **shell 用户（uid 2000）** 的权限，不是 root —— 这一点必须在
 * 提示与 UI 里说清楚，否则用户会以为「开了就能删系统」。它能做的是：
 * 装应用、读日志、改屏幕密度、改电池模拟值、`pm grant` 权限，
 * 以及一切 adb shell 能干的普通事情。
 *
 * ## 为什么自己写而不是引 shizuku-shell 之类
 *
 * 只需要「跑一条命令、拿回输出」，不引入第二个进程模型。多一层封装就多一层
 * 不知道什么时候会挂的东西，而这个 App 的定位是「轻」。
 *
 * ## 读输出的坑
 *
 * 必须**并发**读 stdout 和 stderr。先读完 stdout 再读 stderr，一旦 stderr 超过
 * 管道缓冲（64KB）子进程就会阻塞，`waitFor` 永远不返回 —— 表现为「命令跑了
 * 十分钟还没结果」，而设备其实只是在等我们读走那几 KB。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** 申请权限用的 requestCode，设置页与这里共用 */
    const val REQUEST_CODE = 4213

    /** 单次返回给模型的上限：日志类命令动辄几 MB，全塞进上下文等于烧钱 */
    private const val MAX_OUTPUT = 12_000

    const val PACKAGE = "moe.shizuku.manager"

    data class Outcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val error: String = "",
    ) {
        val ok: Boolean get() = error.isEmpty() && !timedOut && exitCode == 0
    }

    enum class State {
        /** 设备上没装 Shizuku */
        NOT_INSTALLED,
        /** 装了但没在运行（用户没启动服务 / 手机重启后没重新激活） */
        NOT_RUNNING,
        /** 服务在跑，但没给本应用授权 */
        NOT_GRANTED,
        /** 可用 */
        READY,
    }

    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun state(context: Context): State = when {
        !installed(context) -> State.NOT_INSTALLED
        !binderAlive() -> State.NOT_RUNNING
        !granted() -> State.NOT_GRANTED
        else -> State.READY
    }

    /**
     * 弹系统授权框。必须在主线程、且 binder 存活时调用。
     *
     * Shizuku 自己弹窗，不需要 Activity —— 这也是不用 ActivityResult 的原因：
     * 手表上弹窗是系统级的，跟谁的窗口无关。
     */
    fun requestPermission(): Boolean = runCatching {
        if (!binderAlive()) return@runCatching false
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrDefault(false)

    /**
     * 执行一条 shell 命令。
     *
     * `command` 里可以带管道、重定向、`&&` —— 一律通过 `sh -c` 交给系统 shell，
     * 与 adb shell 的行为一致。
     */
    suspend fun run(command: String, timeoutMs: Long = 30_000): Outcome =
        withContext(Dispatchers.IO) {
            if (command.isBlank()) return@withContext Outcome(-1, "", "", error = "命令为空")
            if (!granted()) {
                return@withContext Outcome(
                    -1, "", "",
                    error = "没有 Shizuku 授权。请先在设置里安装并启用 Shizuku，再回来授权。",
                )
            }

            val process = runCatching {
                Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            }.getOrElse {
                Logx.e(TAG, "启动进程失败", it)
                return@withContext Outcome(-1, "", "", error = "启动失败：${it.message}")
            }

            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { drain(process.inputStream, out) }.apply { isDaemon = true; start() }
            val t2 = Thread { drain(process.errorStream, err) }.apply { isDaemon = true; start() }

            val finished = runCatching {
                process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)

            if (!finished) {
                runCatching { process.destroy() }
            }
            // 给读线程一点时间把已经到达的尾巴收完；不无限等 —— 进程被 destroy 后
            // 某些 ROM 上流不会立刻 EOF，join 太久等于把超时又拖一遍。
            runCatching { t1.join(800) }
            runCatching { t2.join(800) }

            val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
            Outcome(
                exitCode = code,
                stdout = out.toString(),
                stderr = err.toString(),
                timedOut = !finished,
            )
        }

    private fun drain(stream: java.io.InputStream, sink: StringBuilder) {
        runCatching {
            stream.bufferedReader().use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    if (sink.length < MAX_OUTPUT) {
                        val room = MAX_OUTPUT - sink.length
                        sink.append(buf, 0, minOf(n, room))
                        if (n > room) sink.append("\n…（输出过长，已截断）")
                    }
                }
            }
        }
    }

    /** 拼成给模型看的一段文本 */
    fun render(outcome: Outcome, command: String): String = buildString {
        if (outcome.error.isNotEmpty()) {
            append(outcome.error)
            return@buildString
        }
        append("退出码 ").append(outcome.exitCode)
        if (outcome.timedOut) append("（超时，进程已终止）")
        val stdout = outcome.stdout.trim()
        val stderr = outcome.stderr.trim()
        if (stdout.isNotEmpty()) {
            append("\n── 输出 ──\n").append(stdout)
        } else if (stderr.isEmpty()) {
            append("\n（没有输出）")
        }
        if (stderr.isNotEmpty()) {
            append("\n── 错误 ──\n").append(stderr)
        }
        if (!outcome.timedOut && outcome.exitCode != 0) {
            append("\n（非零退出码说明命令没成功。先读错误信息，不要原样重试。）")
        }
    }
}
