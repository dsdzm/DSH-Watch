package com.dshwatch.domain.agent.exec

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference

/**
 * 用反射调用 Chaquopy 的 Python 引擎。
 *
 * 为什么全是反射：lite 包里没有 Chaquopy，如果代码里出现 `Python.getInstance()` 这种
 * 直接引用，lite 构建会直接编译失败。反射让同一份源码在两种包里都能编过 ——
 * 代价是写起来啰嗦，收益是不用维护两套源码集。
 *
 * 超时的现实（必须说清楚，不能假装解决了）：
 * Chaquopy 的 Python 与 App **在同一个进程**，没有独立解释器进程可以 kill。
 * 所以：
 *   - Python 侧用 `sys.settrace` 装了死线守卫（见 dsh_runner.py），能拦住纯 Python 死循环；
 *   - Kotlin 侧再等一段时间，等不到就**放弃等待**，并把 [ExecOutput.stillRunning] 标为 true ——
 *     调用方据此决定不放行下一个脚本。
 * C 扩展里的死循环（例如 numpy 卡住）拦不住，这一点在给用户的提示里如实说明。
 */
class ReflectionPythonEngine(private val appContext: Context?) : PythonEngine {

    @Volatile
    private var started = false

    @Volatile
    private var startError: String? = null

    private val startLock = Any()

    override val available: Boolean
        get() = appContext != null && PythonBridge.classPresent() && startError == null

    override fun unavailableReason(): String = when {
        !PythonBridge.classPresent() ->
            "当前安装的是 lite 版，不含 Python 引擎。若要本地跑 Python，请安装 full 版 APK" +
                "（设置页可查看当前版本类型）。"
        appContext == null -> "缺少 Android 上下文，无法启动 Python 引擎。"
        startError != null -> "Python 引擎启动失败：$startError"
        else -> ""
    }

    override suspend fun run(
        script: String,
        workDir: File,
        limits: ExecLimits,
        args: List<String>,
    ): ExecOutput =
        withContext(Dispatchers.IO) {
            if (!available) return@withContext ExecOutput.failure(unavailableReason())
            if (!workDir.isDirectory) {
                return@withContext ExecOutput.failure("工作目录不存在：${workDir.absolutePath}")
            }

            val startedAt = System.currentTimeMillis()
            val holder = AtomicReference<ExecOutput?>(null)

            // Chaquopy 的调用是阻塞且不可取消的，只能丢到独立线程等
            val worker = Thread({
                holder.set(invokeRunner(script, workDir, limits, args))
            }, "dsh-python")
            worker.isDaemon = true
            worker.start()

            // Python 侧有自己的死线，多给 3 秒余量；等不到就放弃等待
            val finished = withTimeoutOrNull(limits.timeoutMs + 3_000L) {
                while (worker.isAlive) delay(50)
                true
            } ?: false

            if (!finished) {
                return@withContext ExecOutput(
                    exitCode = 1,
                    stdout = "",
                    stderr = "Python 执行超过 ${limits.timeoutMs / 1000} 秒未返回，已放弃等待。",
                    timedOut = true,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    stillRunning = worker.isAlive,
                    note = "解释器可能仍在后台运行。在下一次执行被拒绝时，" +
                        "可到「设置 → 本地执行」强制释放执行闸门。",
                )
            }

            holder.get()
                ?: ExecOutput.failure("Python 引擎没有返回结果（可能是启动阶段失败）。")
        }

    /** 真正干活的部分，跑在独立线程上，全程反射 */
    private fun invokeRunner(
        script: String,
        workDir: File,
        limits: ExecLimits,
        args: List<String>,
    ): ExecOutput {
        val t0 = System.currentTimeMillis()
        return try {
            ensureStarted()
            val pyClass = Class.forName(PythonBridge.PYTHON_CLASS)
            val instance = pyClass.getMethod("getInstance").invoke(null)
                ?: return ExecOutput.failure("Python.getInstance() 返回空。")

            val module = instance.javaClass
                .getMethod("getModule", String::class.java)
                .invoke(instance, PythonBridge.RUNNER_MODULE)
                ?: return ExecOutput.failure("找不到内置模块 ${PythonBridge.RUNNER_MODULE}。")

            // PyObject.callAttr(String, Object...) —— 变长参数，反射时要显式打成数组
            val callAttr = module.javaClass
                .getMethod("callAttr", String::class.java, Array<Any>::class.java)
            val raw = callAttr.invoke(
                module,
                "run",
                arrayOf<Any>(
                    script,
                    workDir.absolutePath,
                    limits.timeoutMs / 1000.0,
                    limits.maxChars,
                    // 传 JSON 字符串而不是 ArrayList：见 PythonBridge.argsJson 的说明
                    PythonBridge.argsJson(args),
                ),
            )?.toString().orEmpty()

            val parsed = PythonBridge.parseResult(raw)
            if (parsed.elapsedMs > 0) parsed else parsed.copy(elapsedMs = System.currentTimeMillis() - t0)
        } catch (e: Throwable) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            startError = if (!started) describe(cause) else startError
            ExecOutput.failure("Python 执行失败：${describe(cause)}")
        }
    }

    private fun ensureStarted() {
        if (started) return
        synchronized(startLock) {
            if (started) return
            val ctx = appContext ?: error("没有 Android 上下文")
            val platformClass = Class.forName(PythonBridge.ANDROID_PLATFORM_CLASS)
            val platform = platformClass
                .getConstructor(Context::class.java)
                .newInstance(ctx)

            val pyClass = Class.forName(PythonBridge.PYTHON_CLASS)
            // Chaquopy 的形参是 PyPlatform（父类），反射不会自动匹配子类，两种都试一遍
            val startMethod = runCatching { pyClass.getMethod("start", platformClass) }
                .getOrElse { pyClass.getMethod("start", platformClass.superclass) }
            startMethod.invoke(null, platform)
            started = true
        }
    }

    private fun describe(t: Throwable): String = buildString {
        append(t::class.java.simpleName)
        t.message?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
    }
}
