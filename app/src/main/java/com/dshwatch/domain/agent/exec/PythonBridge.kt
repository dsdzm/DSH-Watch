package com.dshwatch.domain.agent.exec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Python 引擎的抽象。
 *
 * 存在的理由很实际：**lite 包里根本没有 Python 引擎**。
 * 插件（Chaquopy）是按构建入口条件应用的，lite 构建里 `com.chaquo.python` 这个类
 * 压根不存在。所以这里必须是个接口 —— 工具层只看到"能不能用"，
 * 不关心背后是真实引擎还是"这个版本没有"。
 */
interface PythonEngine {

    val available: Boolean

    /**
     * 引擎不可用时给用户的说明（要能直接照做，而不是"出错了"）。
     */
    fun unavailableReason(): String

    /**
     * 执行一段 Python 代码。
     *
     * 注意：这个方法**可能超时后仍在后台运行**（同进程内无法安全终止 CPython），
     * 所以返回值里的 [ExecOutput.stillRunning] 必须被调用方正确处理。
     */
    suspend fun run(
        script: String,
        workDir: File,
        limits: ExecLimits,
        args: List<String> = emptyList(),
    ): ExecOutput
}

/**
 * Python 引擎入口。
 *
 * 这里刻意只用**反射**，不 `import com.chaquo.python.*` —— 那是整套双包方案的前提：
 * 同一份源码要能在"有 Chaquopy"和"没有 Chaquopy"两种构建下都通过编译。
 */
object PythonBridge {

    /** Chaquopy 的核心类名。用字符串而不是类型引用，这是设计的一部分。 */
    const val PYTHON_CLASS = "com.chaquo.python.Python"
    const val ANDROID_PLATFORM_CLASS = "com.chaquo.python.android.AndroidPlatform"

    /** 随包携带的 Python 执行器模块名（源码在 app/src/main/python/dsh_runner.py） */
    const val RUNNER_MODULE = "dsh_runner"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 本包是否包含 Python 引擎。
     * 纯探测，不依赖 Android —— 单测里天然返回 false，正好覆盖 lite 路径。
     */
    fun classPresent(): Boolean = runCatching { Class.forName(PYTHON_CLASS) }.isSuccess

    /**
     * 把参数列表编成 JSON 字符串。
     *
     * 为什么不用 Java List 直接传：Chaquopy 把 Java 集合当**对象**交给 Python，
     * 它不是 Python 的 list，`for a in args` 会报 `TypeError: 'ArrayList' object is not iterable`
     * —— 而且空列表同样会炸（Python 里任意对象都判真，`args or []` 拿到的还是那个对象）。
     * 跨语言边界只传字符串，是这个项目里反复验证过的做法。
     */
    fun argsJson(args: List<String>): String =
        buildJsonArray { args.forEach { add(it) } }.toString()

    /**
     * 解析 Python 执行器返回的 JSON。
     *
     * 这是整条链路上最容易出错的一环（跨语言边界），所以单独抽出来做成纯函数，
     * 用固定样本钉住 —— 真机上出问题时，先看这段解析是不是对的。
     */
    fun parseResult(raw: String): ExecOutput {
        val text = raw.trim()
        if (!text.startsWith("{")) {
            return ExecOutput.failure(
                "Python 执行器没有返回预期结果：" + text.take(500)
            )
        }
        return runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            val timedOut = obj["timed_out"]?.jsonPrimitive?.booleanOrNull ?: false
            val stillRunning = obj["still_running"]?.jsonPrimitive?.booleanOrNull ?: false
            ExecOutput(
                exitCode = obj["exit"]?.jsonPrimitive?.intOrNull ?: 0,
                stdout = obj["stdout"]?.jsonPrimitive?.contentOrEmpty().orEmpty(),
                stderr = obj["stderr"]?.jsonPrimitive?.contentOrEmpty().orEmpty(),
                truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
                timedOut = timedOut,
                elapsedMs = obj["elapsed_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                stillRunning = stillRunning,
                note = buildString {
                    if (timedOut) append("超时，已中止脚本。")
                    if (stillRunning) {
                        if (isNotEmpty()) append(' ')
                        append("解释器可能仍在后台运行（Python 与 App 同进程，无法强制终止）。")
                    }
                },
            )
        }.getOrElse {
            ExecOutput.failure("Python 结果解析失败：${it.message}\n原始内容：" + text.take(300))
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrEmpty(): String =
        runCatching { content }.getOrDefault("")
}
