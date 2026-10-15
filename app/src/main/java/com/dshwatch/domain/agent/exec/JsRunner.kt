package com.dshwatch.domain.agent.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用 Rhino 跑 JavaScript。
 *
 * 引擎为什么是 Rhino：**纯 Java，不需要 NDK、不需要原生库**。
 * 这意味着 lite 包也能带 JS 执行（代价约 1.5MB），而且没有原生崩溃风险 ——
 * 在一个手表 App 里，"不会因为引擎自身崩溃而挂掉"比"跑得快"重要得多。
 *
 * 代价：Rhino 是 ES6 级别的实现，**不支持 async/await**。
 * 这一点如实告诉模型和用户，而不是让它写完再报一句看不懂的语法错误。
 *
 * 超时机制（这是本文件最绕的地方）：
 * Rhino 只有在**解释模式**（optimizationLevel = -1）下才会周期性回调
 * `observeInstructionCount`，观察者里抛异常才能真正打断 `while(true){}`。
 * 默认的编译模式跑的是字节码，观察者根本不会被调用 —— 那样超时是假的。
 */
class JsRunner {

    suspend fun run(
        script: String,
        limits: ExecLimits,
        args: List<String> = emptyList(),
    ): ExecOutput = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val stdout = OutputSink(limits.maxChars)
        val stderr = OutputSink(limits.maxChars)
        val deadlineHit = AtomicBoolean(false)
        val crash = AtomicBoolean(false)
        // 脚本自己报的错（语法/运行时）：必须算作失败。
        // 早期版本只在 stderr 写了消息却把 exitCode 留在 0，工具就会告诉模型「执行成功」——
        // 这正是单元测试抓出来的问题。
        val scriptError = AtomicBoolean(false)

        val factory = DeadlineContextFactory(limits.timeoutMs, deadlineHit)
        val argsLiteral = argsLiteral(args)

        // 单独开线程：Rhino 不响应协程取消，只能靠中断标志 + 指令观察者
        val thread = Thread({
            var cx: Context? = null
            try {
                // 起始时间必须由我们自己记：`factory.call(ContextAction)` 并不保证
                // 走到 doTopCall，早期版本因此让观察者永远看不到 deadline ——
                // 表现是"超时没生效、线程一直空转"。显式记录，不依赖框架的调用路径。
                factory.startedAt = System.currentTimeMillis()
                cx = factory.enterContext()
                val scope = cx.initStandardObjects()
                installConsole(cx, scope, stdout, stderr)
                // 用 var 而不是 const：模型脚本里也可能自己定义 args，重声明不该直接报错
                cx.evaluateString(scope, "var args = $argsLiteral;", "dsh-args", 1, null)
                cx.evaluateString(scope, script, "script.js", 1, null)
            } catch (e: ScriptTimeoutError) {
                stderr.append("执行超时：脚本在 ${limits.timeoutMs} 毫秒内没有结束，已被中止。\n")
                deadlineHit.set(true)
            } catch (e: RhinoException) {
                scriptError.set(true)
                stderr.append(describe(e))
            } catch (e: Throwable) {
                crash.set(true)
                stderr.append("JavaScript 引擎异常：${e.message ?: e::class.simpleName}\n")
            } finally {
                if (cx != null) Context.exit()
            }
        }, "dsh-js")
        thread.isDaemon = true
        thread.start()

        // 等它自己结束；多给一点余量，让指令观察者先有机会抛超时
        val finished = withTimeoutOrNull(limits.timeoutMs + 1_500L) {
            while (thread.isAlive) delay(20)
            true
        } ?: false

        if (!finished) {
            // 两条路都试：中断标志（观察者会读到）+ 再等一小会儿
            deadlineHit.set(true)
            thread.interrupt()
            withTimeoutOrNull(1_000L) { while (thread.isAlive) delay(20) }
        }

        val elapsed = System.currentTimeMillis() - started
        val timedOut = deadlineHit.get() || !finished
        val (outText, outTrunc) = stdout.finalPair()
        val (errText, errTrunc) = stderr.finalPair()
        val stillRunning = thread.isAlive

        ExecOutput(
            exitCode = if (timedOut || crash.get() || scriptError.get()) 1 else 0,
            stdout = outText,
            stderr = errText,
            truncated = outTrunc || errTrunc,
            timedOut = timedOut,
            elapsedMs = elapsed,
            note = buildString {
                if (stillRunning) {
                    append("脚本线程可能仍在运行（JavaScript 引擎无法被强制终止，会随应用一起退出）。")
                }
                if (crash.get()) {
                    if (isNotEmpty()) append(' ')
                    append("可尝试改用云端执行（cloud_build）验证同一段代码。")
                }
            },
        )
    }

    private fun describe(e: RhinoException): String = buildString {
        append("JavaScript 错误：")
        append(e.details().orEmpty().ifBlank { e.message.orEmpty() })
        if (e.lineNumber() > 0) append("（第 ${e.lineNumber()} 行）")
        append('\n')
    }

    /** 把参数列表编成 JS 字面量，注入成全局 `args` */
    private fun argsLiteral(args: List<String>): String =
        buildJsonArray { args.forEach { add(it) } }.toString()

    /** 让 `print` 与 `console.*` 都能用 —— 模型写的 JS 两种风格都会出现 */
    private fun installConsole(cx: Context, scope: ScriptableObject, out: OutputSink, err: OutputSink) {
        val logFn = SinkFunction(out)
        val errFn = SinkFunction(err)
        ScriptableObject.putProperty(scope, "print", logFn)

        val console = cx.newObject(scope)
        ScriptableObject.putProperty(console, "log", logFn)
        ScriptableObject.putProperty(console, "info", logFn)
        ScriptableObject.putProperty(console, "debug", logFn)
        ScriptableObject.putProperty(console, "warn", errFn)
        ScriptableObject.putProperty(console, "error", errFn)
        ScriptableObject.putProperty(scope, "console", console)
    }

    private class SinkFunction(private val sink: OutputSink) : BaseFunction() {
        override fun call(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<Any?>,
        ): Any? {
            sink.append(args.joinToString(" ") { format(cx, scope, it) } + "\n")
            return Undefined.instance
        }
    }

    /**
     * 让 `print(obj)` 打印出**内容**而不是 `[object Object]`。
     *
     * 这条不是锦上添花：模型调完工具只看得到这段文本，
     * 打印一个对象却得到 `[object Object]`，等于这次执行白跑了。
     * 所以对象一律走 JSON，取不到再退回默认字符串。
     */

    /**
     * 超时用的 ContextFactory。
     *
     * 每次执行**新建一个实例**（各自带自己的 deadline），
     * 不用 `ContextFactory.initGlobal` —— 那是全局状态，多个会话会互相踩。
     */
    private class DeadlineContextFactory(
        private val deadlineMs: Long,
        private val deadlineHit: AtomicBoolean,
    ) : ContextFactory() {

        /** 由执行线程在开始前写入。放在工厂上而不是 Context 上，避免依赖 doTopCall 的调用时机 */
        @Volatile
        var startedAt: Long = 0L

        /** 只是个合法的构造入口：Context(ContextFactory) 是 protected，外部类无法直接 new */
        private class DeadlineContext(factory: ContextFactory) : Context(factory)

        override fun makeContext(): Context {
            val cx = DeadlineContext(this)
            // 必须 -1：编译模式下解释器不会回调指令观察者，超时就是假的
            cx.setOptimizationLevel(-1)
            cx.setInstructionObserverThreshold(10_000)
            cx.setLanguageVersion(Context.VERSION_ES6)
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val start = startedAt
            if (start > 0 && System.currentTimeMillis() - start > deadlineMs) {
                deadlineHit.set(true)
                throw ScriptTimeoutError("脚本执行超过 ${deadlineMs}ms")
            }
            if (Thread.currentThread().isInterrupted) {
                deadlineHit.set(true)
                throw ScriptTimeoutError("脚本被中止")
            }
        }
    }

    companion object {
        /**
         * 用 Error 而不是 Exception：脚本自己的 `try { } catch (e) {}`
         * 常常捕获 Exception 并继续跑，那样超时就形同虚设。
         */
        internal class ScriptTimeoutError(message: String) : Error(message)

        /** 数值、字符串、对象都要能打印出来，不能因为一个 null 就整段失败 */
        internal fun safeToString(value: Any?): String = runCatching { Context.toString(value) }
            .getOrElse { value?.toString() ?: "null" }

        /**
         * 让 `print(obj)` 打印出**内容**而不是 `[object Object]`。
         *
         * 这条不是锦上添花：模型调完工具只看得到这段文本，
         * 打印一个对象却得到 `[object Object]`，等于这次执行白跑了。
         * 所以对象一律走 JSON，取不到（函数、循环引用）再退回默认字符串。
         */
        internal fun format(cx: Context, scope: Scriptable, value: Any?): String {
            if (value is Scriptable && value !is Wrapper) {
                val json = runCatching { NativeJSON.stringify(cx, scope, value, null, null) }.getOrNull()
                if (json is String && json != "undefined") return json
            }
            return safeToString(value)
        }
    }
}
