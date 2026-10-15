package com.dshwatch.domain.agent.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 用系统自带的 `sh` 跑脚本。
 *
 * 这是三种本地执行里**最"真"的一种** —— 不是模拟、不是子集，
 * 就是设备上那个真实的 shell：管道、重定向、`sed`/`awk`/`grep` 全都能用。
 *
 * 实现上几处必须这样写的地方：
 *
 * 1. **不执行脚本文件本身**，而是把脚本路径作为参数交给 `sh`。
 *    Android 10 起 targetSdk ≥29 的应用不能再 `execve()` 私有目录里的文件，
 *    但"让系统只读二进制去读一个数据文件"完全允许 —— 这条路绕开了那个限制。
 *
 * 2. **输出重定向到文件，而不是管道**。管道有两个坑：
 *    - 不持续排空会把子进程堵死在写管道上；
 *    - 超时杀掉 `sh` 之后，脚本起的子进程（`sleep 30` 之类）变成孤儿并继续持有写端，
 *      读线程会一直阻塞 —— 而且 JVM 在另一线程阻塞读时 `close()` 要等锁，救不回来。
 *    改成文件之后，孤儿进程爱跑多久跑多久，我们读完自己的文件就走。
 *
 * 3. **环境变量清空后重建**：脚本不该拿到 App 进程的任何变量，更不该拿到凭据。
 */
class ShellRunner(private val shellPath: String = DEFAULT_SHELL) {

    @Volatile
    private var detected: Boolean? = null

    /**
     * 探测本机是否有可用的 shell。
     *
     * 需要探测而不是假设：绝大多数 Android 设备都有 `/system/bin/sh`，
     * 但定制手表 ROM 什么都可能。探测失败时工具要如实说"本机没有 shell"，
     * 而不是抛一个看不懂的异常。
     */
    suspend fun detect(): Boolean {
        detected?.let { return it }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val proc = ProcessBuilder(shellPath, "-c", "echo dsh-shell-ok")
                    .redirectErrorStream(true)
                    .start()
                val text = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor(3, TimeUnit.SECONDS)
                if (proc.isAlive) proc.destroyForcibly()
                text.contains("dsh-shell-ok")
            }.getOrDefault(false)
        }
        detected = ok
        return ok
    }

    suspend fun run(
        script: String,
        workDir: File,
        limits: ExecLimits,
        args: List<String> = emptyList(),
    ): ExecOutput = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        if (!workDir.isDirectory) {
            return@withContext ExecOutput.failure("工作目录不存在：${workDir.absolutePath}")
        }
        if (!detect()) {
            return@withContext ExecOutput.failure(
                "本机没有可用的 shell（$shellPath）。此设备可能限制了命令行访问。"
            )
        }

        val scratch = File(workDir, SCRATCH_DIR).apply { mkdirs() }
        pruneScratch(scratch)

        val id = System.nanoTime()
        val scriptFile = File(scratch, "run-$id.sh")
        val outFile = File(scratch, "out-$id.txt")
        val errFile = File(scratch, "err-$id.txt")
        scriptFile.writeText(script, Charsets.UTF_8)

        var proc: Process? = null
        try {
            val builder = ProcessBuilder(listOf(shellPath, scriptFile.absolutePath) + args)
                .directory(workDir)
                .redirectOutput(outFile)
                .redirectError(errFile)
            builder.environment().apply {
                clear()
                putAll(cleanEnv(workDir, scratch))
            }
            proc = builder.start()
            val process = proc

            var exited = false
            val deadline = System.currentTimeMillis() + limits.timeoutMs
            while (true) {
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    exited = true
                    break
                }
                if (System.currentTimeMillis() >= deadline) break
                // 用户点「停止」时能及时退出，而不是等满超时
                currentCoroutineContext().ensureActive()
            }
            if (!exited) {
                process.destroyForcibly()
                // 收尸，避免僵尸进程；拿不到退出码也没关系
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            }

            val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            val (outText, outTrunc) = OutputFiles.readCapped(outFile, limits.maxChars)
            val (errText, errTrunc) = OutputFiles.readCapped(errFile, limits.maxChars)

            ExecOutput(
                exitCode = if (exited) exitCode else -1,
                stdout = outText,
                stderr = errText,
                truncated = outTrunc || errTrunc,
                timedOut = !exited,
                elapsedMs = System.currentTimeMillis() - started,
                note = if (!exited) "超时 ${limits.timeoutMs / 1000} 秒，已强制结束该进程。" else "",
            )
        } catch (e: Exception) {
            ExecOutput.failure("执行 shell 失败：${e.message ?: e::class.simpleName}")
        } finally {
            // 协程被取消（用户停止）时也要收尸，别留孤儿进程
            proc?.let { if (it.isAlive) it.destroyForcibly() }
            runCatching {
                outFile.delete()
                errFile.delete()
            }
        }
    }

    /**
     * 脚本运行所需的最小环境。
     *
     * 刻意**只放这些**：脚本不该看到 App 进程的任何环境变量
     * （那里面有厂商塞的各种标识，也不该有机会把凭据传下去）。
     */
    private fun cleanEnv(workDir: File, scratch: File): Map<String, String> = mapOf(
        "PATH" to "/system/bin:/system/xbin:/vendor/bin:/bin:/usr/bin",
        "HOME" to workDir.absolutePath,
        "PWD" to workDir.absolutePath,
        "TMPDIR" to scratch.absolutePath,
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
    )

    /** 只保留最近若干个脚本文件，避免工作目录里越堆越多 */
    private fun pruneScratch(scratch: File, keep: Int = 20) {
        runCatching {
            val files = scratch.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: return
            files.drop(keep).forEach { it.delete() }
        }
    }

    companion object {
        const val DEFAULT_SHELL = "/system/bin/sh"

        /** 脚本文件与临时输出放在会话目录下的这个子目录里 */
        const val SCRATCH_DIR = ".dsh-run"

        /** JVM 单测里用这个（Android 上没有 /bin/sh，但桌面上有） */
        fun forTesting(): ShellRunner = ShellRunner(shellPath = "/bin/sh")
    }
}
