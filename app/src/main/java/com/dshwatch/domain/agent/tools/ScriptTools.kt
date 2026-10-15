package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.exec.ExecGuard
import com.dshwatch.domain.agent.exec.ExecLimits
import com.dshwatch.domain.agent.exec.ExecOutput
import com.dshwatch.domain.agent.exec.JsRunner
import com.dshwatch.domain.agent.exec.PythonBridge
import com.dshwatch.domain.agent.exec.ReflectionPythonEngine
import com.dshwatch.domain.agent.exec.ShellRunner
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.rawStrOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.agent.stringListOf
import com.dshwatch.domain.model.AppSettings
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 本地脚本执行。
 *
 * ## 和 `build_project` 的分工
 *
 * `build_project` 做**静态校验**：不上编译器，只看括号配平、缩进、配对关键字。
 * 它是"写完先看一眼"，快、零风险。
 *
 * `run_script` 是**真的把它跑起来**：拿到真实的 stdout、真实的报错、真实的退出码。
 * 前者能抓的都抓了，但"跑一遍"能抓的是另一类问题 ——
 * 变量名打错、索引越界、逻辑写反、读文件路径不对，这些只有执行才暴露。
 *
 * ## 三种语言的现实差异（工具描述里必须说清楚，否则模型会误判）
 *
 * | 语言 | 能力 | 限制 |
 * | --- | --- | --- |
 * | shell | 设备上真实的 `sh`，管道/重定向/`sed`/`grep` 都能用 | 需设备有 `/system/bin/sh` |
 * | js | Rhino 引擎，ES6 大部分语法 | **不支持 async/await**；无 DOM、无 `fetch` |
 * | python | 真 CPython（仅 full 版包含） | 标准库可用，第三方包不可用 |
 *
 * ## 安全
 *
 * 这是**权限最大的一个工具** —— 它能执行任意代码。所以：
 *  - 出厂默认关闭，由用户在设置里显式开启；
 *  - 即使开启，也走 `ALWAYS_CONFIRM_TOOLS`，每次执行都要用户点确认；
 *  - 工作目录锁定在当前会话目录，环境变量清空后重建，绝不注入凭据；
 *  - 全局同时只允许一个脚本，输出有上限，超时强制结束。
 */
object ScriptTools {

    fun all(): List<AgentTool> = listOf(RunScriptTool)

    /** 进程内复用：shell 的探测结果要缓存，Python 解释器只启动一次 */
    @Volatile
    private var shell: ShellRunner? = null

    @Volatile
    private var python: ReflectionPythonEngine? = null

    private val js = JsRunner()

    private fun shellRunner(): ShellRunner = shell ?: synchronized(this) {
        shell ?: ShellRunner().also { shell = it }
    }

    private fun pythonEngine(ctx: ToolContext) = python ?: synchronized(this) {
        python ?: ReflectionPythonEngine(ctx.appContext).also { python = it }
    }

    object RunScriptTool : AgentTool {
        override val name = "run_script"
        override val description =
            "在当前工作目录里**真的执行**一段脚本，拿到真实的输出与报错。三种语言：" +
                "- language=shell：设备上真实的 sh，管道、重定向、grep/sed/awk 都能用；" +
                "- language=js：JavaScript（ES6 语法，**不支持 async/await**，无 DOM 与 fetch）；" +
                "- language=python：真 CPython，仅标准库（full 版才有；lite 版会明确告诉你不可用）。" +
                "**重要：shell 里没有 python / node / java / gcc 这些命令** —— 手表上没有系统级运行时，" +
                "三种语言是彼此独立的执行路径，不能互相调用（`sh` 里跑 `python x.py` 一定失败）。" +
                "要跑 Python 就用 language=python，要跑 JS 就用 language=js。" +
                "什么时候用：写完代码想验证**真的能跑通、结果对不对**时用。" +
                "只想快速查语法错误用 build_project（更快、零风险）。" +
                "需要「编译出东西」（apk、gcc、gradle）用 cloud_build —— 那些本机做不到。" +
                "执行结果包含退出码、stdout、stderr；写文件请写到当前工作目录（相对路径即可）。" +
                "注意：每次执行都会请用户确认（这是不可关闭的一道确认）。" +
                "脚本能读写工作目录、能发起网络请求；但没有 root、访问不到其它应用的数据。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "language" to Schema.str(
                    "执行语言",
                    enum = listOf("shell", "js", "python"),
                ),
                "code" to Schema.str(
                    "脚本正文。不要写成文件路径 —— 这里直接放源码；" +
                        "需要多文件就先用 write_file 建好，再用 shell 调用。"
                ),
                "args" to Schema.arr("传给脚本的位置参数（shell 的 $1/$2，js 的全局 args 数组，python 的 sys.argv[1:]）"),
                "timeout_sec" to Schema.int("超时秒数，1..120，默认取设置里的值", min = 1, max = 120),
            ),
            required = listOf("language", "code"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val language = args.strOf("language")?.trim()?.lowercase()
                ?: return ToolResult.fail(
                    "缺少 language 参数。可选值：shell / js / python。" +
                        "如果要跑的是你刚写的文件，用 shell + `python main.py` 或 `node main.js` 这样的命令。"
                )
            if (language !in SUPPORTED) {
                return ToolResult.fail(
                    "不支持的 language：$language。当前支持 shell / js / python。" +
                        "其它语言（Java/Go/Rust 等）请用 cloud_build 交给云端编译。"
                )
            }

            val code = args.rawStrOf("code")?.takeIf { it.isNotBlank() }
                ?: return ToolResult.fail(
                    "缺少 code 参数。这里要放**脚本源码正文**，不是文件路径；" +
                        "如果你只想验证某个已存在的文件，用 language=shell 执行它。"
                )

            val settings = ctx.settings()
            val gate = checkEnabled(language, settings)
            if (gate != null) return ToolResult.fail(gate)

            val scriptArgs = args.stringListOf("args")
            val timeoutSec = (args.intOf("timeout_sec") ?: settings.execTimeoutSec)
                .coerceIn(1, MAX_TIMEOUT_SEC)
            val limits = ExecLimits(
                timeoutMs = timeoutSec * 1000L,
                maxChars = settings.execMaxOutputChars.coerceIn(1_000, 60_000),
            )

            val workDir = File(ctx.workspace.rootDir(), ctx.sessionDir)
            if (!workDir.isDirectory) {
                return ToolResult.fail(
                    "会话工作目录不存在：${ctx.workspace.rootDir().name}/${ctx.sessionDir}。" +
                        "先写一个文件（write_file）会把目录建出来，或者换用工作空间根目录。"
                )
            }

            // 全局只允许一个脚本：手表 CPU 小，两个失控脚本叠加会让设备卡到没法操作
            val guard = ExecGuard.shared
            if (!guard.tryAcquire()) {
                return ToolResult.fail(
                    "上一次脚本仍在运行，本次已拒绝执行。" +
                        "如果确认它已经不会再结束，请到「设置 → 本地执行」强制释放执行闸门。"
                )
            }

            val before = runCatching {
                ctx.workspace.listFilesRecursive(ctx.sessionDir).filter { !it.isDir }.map { it.path }.toSet()
            }.getOrDefault(emptySet())

            var keepGate = false
            try {
                val output = when (language) {
                    "shell" -> shellRunner().run(code, workDir, limits, scriptArgs)
                    "js" -> js.run(code, limits, scriptArgs)
                    else -> pythonEngine(ctx).run(code, workDir, limits, scriptArgs)
                }
                if (output.stillRunning) keepGate = true

                val artifact = if (output.ok) {
                    detectArtifact(ctx, before)
                } else {
                    null
                }
                val text = formatExecOutput(language, output)
                return if (output.ok) {
                    ToolResult.ok(text, artifact.orEmpty())
                } else {
                    ToolResult.fail(text)
                }
            } catch (e: Exception) {
                return ToolResult.fail(
                    "执行 $language 脚本时出错：${e.message ?: e::class.simpleName}。" +
                        "如果反复出现，请把这段报错发给用户。"
                )
            } finally {
                // 超时且脚本可能还在后台跑时，闸门保持占用 —— 宁可挡住新任务，
                // 也不让失控脚本叠加（用户可在设置页强制释放）
                if (!keepGate) guard.release()
            }
        }

        /** 开关检查。返回 null 表示放行，否则是给模型看的失败原因（都带可执行的下一步） */
        private fun checkEnabled(language: String, settings: AppSettings): String? {
            if (!settings.localExecEnabled) {
                return "本地执行未开启，无法运行脚本。请让用户到「设置 → 本地执行」打开总开关" +
                    "（你也可以调用 open_screen 打开设置页），或者改用 build_project 做静态校验、" +
                    "用 cloud_build 交给云端执行。"
            }
            val allowed = when (language) {
                "shell" -> settings.allowShell
                "js" -> settings.allowJs
                "python" -> settings.allowPython
                else -> false
            }
            if (!allowed) {
                val label = when (language) {
                    "shell" -> "Shell"
                    "js" -> "JavaScript"
                    else -> "Python"
                }
                return "$label 执行未开启（总开关已开，但这一项没开）。" +
                    "请让用户到「设置 → 本地执行」勾选「$label」；也可以换用其它已开启的语言。"
            }
            if (language == "python" && !PythonBridge.classPresent()) {
                return pythonEngineUnavailable()
            }
            return null
        }

        private fun pythonEngineUnavailable(): String =
            "当前安装的是 lite 版，不含 Python 引擎 —— 本地跑不了 Python。" +
                "可选：① 用 language=js 或 shell 改写这段逻辑；" +
                "② 让用户安装 full 版 APK 后再试；" +
                "③ 用 cloud_build 把代码交给云端 Python 执行。"

        /** 执行后新出现的文件里挑一个最有代表性的当产物（界面会渲染成可点开的卡片） */
        private suspend fun detectArtifact(ctx: ToolContext, before: Set<String>): String? {
            val after = runCatching {
                ctx.workspace.listFilesRecursive(ctx.sessionDir)
                    .filter { !it.isDir && !it.path.contains(ShellRunner.SCRATCH_DIR) }
            }.getOrNull() ?: return null
            val fresh = after.filter { it.path !in before }
            if (fresh.isEmpty()) return null
            return fresh.maxByOrNull { it.size }?.path
        }

        override fun summarize(args: JsonObject): String {
            val lang = args.strOf("language") ?: "?"
            val lines = args.rawStrOf("code")?.lines()?.size ?: 0
            return "$lang · $lines 行"
        }
    }

    private val SUPPORTED = setOf("shell", "js", "python")
    private const val MAX_TIMEOUT_SEC = 120

    /**
     * 统一的结果文本。
     *
     * 三种语言走同一个格式，模型不需要为每种语言学一套读法；
     * 而"超时/截断/仍在后台"这些**必须出现** —— 少写一句，
     * 模型就会把一次失败的执行当成成功的。
     */
    internal fun formatExecOutput(language: String, out: ExecOutput): String = buildString {
        val label = when (language) {
            "shell" -> "shell"
            "js" -> "JavaScript"
            else -> "Python"
        }
        if (out.ok) {
            append("$label 执行成功（退出码 ${out.exitCode}，用时 ${out.elapsedMs} ms）")
        } else if (out.timedOut) {
            append("$label 执行**超时被中止**（用时 ${out.elapsedMs} ms）")
        } else {
            append("$label 执行失败（退出码 ${out.exitCode}，用时 ${out.elapsedMs} ms）")
        }
        append('\n')

        if (out.stdout.isNotBlank()) {
            append("\n--- stdout ---\n").append(out.stdout.trimEnd()).append('\n')
        }
        if (out.stderr.isNotBlank()) {
            append("\n--- stderr ---\n").append(out.stderr.trimEnd()).append('\n')
        }
        if (out.stdout.isBlank() && out.stderr.isBlank()) {
            append("\n（没有任何输出）\n")
        }
        if (out.truncated) {
            append("\n注意：输出过长已被截断（保留了开头和结尾）。\n")
        }
        if (out.note.isNotBlank()) {
            append("\n").append(out.note).append('\n')
        }
        if (language == "js" && !out.ok && out.stderr.contains("async")) {
            append("\n提示：本地 JS 引擎不支持 async/await，请改写为同步写法" +
                "（这台设备上没有 node，shell 里也没有，所以只能改写）。\n")
        }
        val hint = interpreterHint(language, out)
        if (hint.isNotBlank()) append('\n').append(hint).append('\n')
    }

    /**
     * `sh: python: not found` 这类失败要翻译成"下一步该怎么做"。
     *
     * 这不是锦上添花：模型非常容易假设 shell 里能调 `python main.py` / `node app.js`，
     * 而**这台设备上根本没有这些系统级运行时** —— Python 和 JS 是另一种执行路径
     * （跑在 App 进程内），两者不能互相调用。
     * 不提示的话，模型往往会反复重试同一个死路，或者改去怀疑自己的代码写错了。
     */
    internal fun interpreterHint(language: String, out: ExecOutput): String {
        if (language != "shell" || out.ok) return ""
        val missing = missingCommand(out.stderr) ?: return ""
        return when (missing) {
            "python", "python3", "py", "pip", "pip3" ->
                "提示：shell 里没有 python —— 手表上没有系统级 Python。" +
                    "要跑 Python 请把 language 改成 python（需要 full 版 APK；" +
                    "那条路径能拿到完整的 stdout/stderr，也不受 shell 限制）。"
            "node", "nodejs", "npm", "npx", "deno", "bun" ->
                "提示：shell 里没有 node —— 要跑 JavaScript 请把 language 改成 js。"
            "java", "javac", "kotlinc", "gradle", "gcc", "g++", "clang", "go", "cargo", "rustc", "dotnet", "mvn" ->
                "提示：shell 里没有 $missing —— 手表上没有这些编译器/工具链。" +
                    "这类「真的编译出东西」的需求请用 cloud_build 交给云端（GitHub Actions）。"
            else ->
                "提示：shell 里没有 $missing。可用的是设备自带的 sh 与常见的 toybox 命令" +
                    "（ls/cat/grep/sed/awk/echo 等）；其它运行时请换 language，或改用 cloud_build。"
        }
    }

    /** 从 shell 的报错里认出「哪个命令不存在」。认不出就返回 null，不硬猜 */
    private fun missingCommand(stderr: String): String? {
        val patterns = listOf(
            Regex("""(?:^|\s)([\w.+-]+):\s*(?:command )?not found""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|\s)([\w.+-]+):\s*No such file or directory""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|\s)([\w.+-]+):\s*not found in""", RegexOption.IGNORE_CASE),
        )
        stderr.lines().forEach { line ->
            patterns.forEach { p ->
                p.find(line)?.let { return it.groupValues[1].trim() }
            }
        }
        return null
    }
}
