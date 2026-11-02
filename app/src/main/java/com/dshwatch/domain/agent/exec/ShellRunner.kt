package com.dshwatch.domain.agent.exec

import kotlinx.coroutines.CancellationException
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
 *    改成文件之后，读线程不会再被孤儿进程拖住；超时时还会按 `/proc` 的 ppid 关系
 *    杀掉整棵进程树（见 `killTree`），不让它们继续占 CPU 烧电。
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
                // stdin 必须显式关掉：默认是 PIPE 且永不关闭，脚本里一句 `cat` / `read x`
                // 就会一直等输入，把整个超时窗口耗光
                .redirectInput(File("/dev/null"))
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
                // destroyForcibly() 只作用于 `sh` 本身，脚本里起的 `sleep 9999 &`、
                // `find /` 会变成孤儿继续跑 —— 在 1200mAh 的手表上足以把电耗光。
                // 先按脚本路径定位到那个 sh，再顺着 /proc 的 ppid 关系把整棵树杀掉。
                runCatching { killTreeByScript(scriptFile.absolutePath) }
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
        } catch (e: CancellationException) {
            // 用户点「停止」走的是协程取消，必须原样抛出去：
            // 被下面的 catch(Exception) 接住的话，工具会被记成「执行失败」，
            // 还会被当成真实失败回灌给模型
            throw e
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

    /**
     * 按脚本路径找到那个 `sh <scriptPath>` 进程，杀掉它和它的整棵子树。
     *
     * 为什么要绕一圈找 pid：Android 的 `java.lang.Process` **没有** `pid()` 方法
     * （编译期就是 Unresolved reference），反射读 `ProcessImpl.pid` 又踩隐藏 API。
     * 这里改成扫 `/proc/<pid>/cmdline`，匹配我们自己的脚本路径 —— 脚本名带 nanoTime，
     * 不会误伤别的进程，也不需要任何系统权限。
     */
    private fun killTreeByScript(scriptPath: String) {
        if (scriptPath.isEmpty()) return
        val roots = runCatching {
            File("/proc").listFiles()
                ?.asSequence()
                ?.filter { dir -> dir.isDirectory && dir.name.all { it.isDigit() } }
                ?.mapNotNull { dir ->
                    val cmdline = runCatching { File(dir, "cmdline").readBytes() }
                        .getOrNull() ?: return@mapNotNull null
                    val text = String(cmdline, Charsets.UTF_8)
                    if (text.contains(scriptPath)) dir.name.toLongOrNull() else null
                }
                ?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        roots.forEach { killTree(it) }
    }

    /**
     * 按 /proc 里的 ppid 关系，把 [rootPid] 的整棵子进程树杀干净。
     *
     * 为什么需要：`Process.destroyForcibly()` 只能杀直接子进程（那个 `sh`），
     * 脚本里 `cmd &` 起来的孙进程会脱离成孤儿继续占 CPU —— 手表上表现为发烫、掉电快。
     * Android 没有进程组语义可用，只能自己爬一次进程树。
     *
     * 只用 `/system/bin/kill`，不依赖 setsid；`guard` 防止进程树异常导致死循环。
     */
    private fun killTree(rootPid: Long) {
        if (rootPid <= 0L) return
        val children: Map<Long, List<Long>> = runCatching {
            File("/proc").listFiles()
                ?.asSequence()
                ?.filter { dir -> dir.isDirectory && dir.name.all { it.isDigit() } }
                ?.mapNotNull { dir ->
                    val stat = runCatching { File(dir, "stat").readText() }.getOrNull()
                        ?: return@mapNotNull null
                    // 格式：pid (comm) state ppid ...  —— comm 里可能有空格和括号，从最后一个 ')' 往后切
                    val close = stat.lastIndexOf(')')
                    if (close < 0) return@mapNotNull null
                    val fields = stat.substring(close + 1).trim().split(' ')
                    val ppid = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                    dir.name.toLongOrNull()?.let { it to ppid }
                }
                ?.groupBy({ it.second }, { it.first })
                ?: emptyMap()
        }.getOrDefault(emptyMap())

        val stack = ArrayDeque<Long>()
        stack.addLast(rootPid)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 512) {
            val pid = stack.removeLast()
            children[pid]?.let { kids -> kids.forEach { stack.addLast(it) } }
            runCatching {
                // 输出丢到 /dev/null：既避免管道写满阻塞，也不用 Java 9 的 Redirect.DISCARD
                // （那是 API 33 才有的，本项目 minSdk 28）
                ProcessBuilder("/system/bin/kill", "-9", pid.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(File("/dev/null"))
                    .start()
                    .waitFor(1, TimeUnit.SECONDS)
            }
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
