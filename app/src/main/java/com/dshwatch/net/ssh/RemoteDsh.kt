package com.dshwatch.net.ssh

/**
 * 在远端把 `dsh web` 弄起来，并把它打印的启动地址拿回来。
 *
 * ## 为什么要「复用」而不是每次都起新的
 *
 * `dsh web --port 0` 每次都会挑一个新端口、发一个新令牌，而 DSH 的会话是
 * 存在服务端进程里的：每次连都起新进程，用户看到的就是「历史会话全没了」。
 * 所以脚本先看 pid 文件，进程还活着就复用它的地址。
 *
 * ## 为什么整段逻辑放在 shell 里
 *
 * 手表到电脑之间只有一条 SSH，每次 exec 都是一次往返。拆成「探测 / 启动 /
 * 轮询」三次调用，在手表这种网络下会让连接慢到 5 秒以上。一次跑完，
 * 中间过程用 `DSHW ` 前缀打标记回传，解析放在 Kotlin 里（可单测）。
 */
object RemoteDsh {

    private const val TAG = "DSHW"

    /** 远端状态目录。与 dsh-app 的分开，避免两边的 pid 文件互相覆盖 */
    const val STATE_DIR = "\$HOME/.dsh-watch"

    /**
     * 启动脚本。
     *
     * @param waitSeconds 等 `dsh web` 打印地址的上限。冷启动（要加载插件）可能十几秒
     */
    fun startScript(target: SshTarget, waitSeconds: Int = 25): String {
        val cmd = target.command.ifBlank { "dsh web" }
        val cwd = target.cwd.trim()
        val cdLine = if (cwd.isEmpty()) "" else "cd ${shellQuote(cwd)} 2>/dev/null || printf '$TAG nocwd\\n'\n"
        val wait = waitSeconds.coerceIn(5, 120)
        return buildString {
            appendLine("D=\"$STATE_DIR\"")
            appendLine("mkdir -p \"\$D\" 2>/dev/null || { printf '$TAG nodir\\n'; exit 70; }")
            appendLine("chmod 700 \"\$D\" 2>/dev/null")
            // 非交互 shell 的 PATH 常常没有 nvm/volta 那一套，先补几个常见位置
            appendLine("if ! command -v ${cmd.substringBefore(' ')} >/dev/null 2>&1; then")
            appendLine("  F=\"\"")
            appendLine("  for c in \"\$HOME/.local/bin\" \"\$HOME/.npm-global/bin\" \"\$HOME/.volta/bin\" \"\$HOME/.bun/bin\" \\")
            appendLine("           \"\$HOME/.local/share/pnpm\" /usr/local/bin /opt/homebrew/bin \"\$HOME\"/.nvm/versions/node/*/bin; do")
            appendLine("    if [ -x \"\$c/${cmd.substringBefore(' ')}\" ]; then F=\"\$c\"; fi")
            appendLine("  done")
            appendLine("  if [ -n \"\$F\" ]; then PATH=\"\$F:\$PATH\"; export PATH; fi")
            appendLine("fi")
            appendLine("if ! command -v ${cmd.substringBefore(' ')} >/dev/null 2>&1; then printf '$TAG nodsh\\n'; exit 127; fi")
            appendLine("printf '$TAG home %s\\n' \"\$HOME\"")
            // 复用：pid 活着、日志里还有地址，就直接用它
            appendLine("if [ -f \"\$D/web.pid\" ]; then")
            appendLine("  P=\$(cat \"\$D/web.pid\" 2>/dev/null)")
            appendLine("  if [ -n \"\$P\" ] && kill -0 \"\$P\" 2>/dev/null; then")
            appendLine("    L=\$(grep -E 'https?://[^ ]*[?&]token=' \"\$D/web.log\" 2>/dev/null | tail -n 1)")
            appendLine("    if [ -n \"\$L\" ]; then printf '$TAG url %s\\n' \"\$L\"; printf '$TAG reused %s\\n' \"\$P\"; exit 0; fi")
            appendLine("  fi")
            appendLine("fi")
            appendLine(cdLine.trimEnd())
            appendLine(": > \"\$D/web.log\"")
            appendLine("NO_COLOR=1; export NO_COLOR")
            appendLine("N=\"\"; if command -v nohup >/dev/null 2>&1; then N=nohup; fi")
            appendLine("\$N $cmd --host 127.0.0.1 --port 0 --no-open </dev/null >>\"\$D/web.log\" 2>&1 &")
            appendLine("P=\$!")
            appendLine("printf '%s\\n' \"\$P\" > \"\$D/web.pid\"")
            appendLine("printf '$TAG pid %s\\n' \"\$P\"")
            appendLine("i=0")
            appendLine("while [ \$i -lt $wait ]; do")
            appendLine("  L=\$(grep -E 'https?://[^ ]*[?&]token=' \"\$D/web.log\" 2>/dev/null | tail -n 1)")
            appendLine("  if [ -n \"\$L\" ]; then printf '$TAG url %s\\n' \"\$L\"; exit 0; fi")
            appendLine("  if ! kill -0 \"\$P\" 2>/dev/null; then")
            appendLine("    printf '$TAG exited\\n'; tail -n 20 \"\$D/web.log\" 2>/dev/null | sed \"s/^/$TAG log /\"; exit 1")
            appendLine("  fi")
            appendLine("  sleep 1; i=\$((i+1))")
            appendLine("done")
            appendLine("printf '$TAG timeout\\n'; tail -n 20 \"\$D/web.log\" 2>/dev/null | sed \"s/^/$TAG log /\"; exit 1")
        }
    }

    /** 只探测、不启动：用于「连接测试」里区分「没装 dsh」和「装但没起来」 */
    fun probeScript(target: SshTarget): String = buildString {
        appendLine("if ! command -v ${target.command.ifBlank { "dsh web" }.substringBefore(' ')} >/dev/null 2>&1; then printf '$TAG nodsh\\n'; exit 127; fi")
        appendLine("printf '$TAG home %s\\n' \"\$HOME\"")
        appendLine("printf '$TAG ok\\n'")
    }

    /**
     * 解析脚本回传。
     *
     * 只认 `DSHW ` 开头的行：登录 banner、`~/.bashrc` 里的 echo 都不该被当成结果，
     * 这是 dshgo 脚本里踩过的坑（不认前缀，会把用户 shell 的欢迎语当地址）。
     */
    fun parse(stdout: String): RemoteDshResult {
        var home: String? = null
        var pid: Int? = null
        var url: String? = null
        var reused = false
        var noDsh = false
        var noCwd = false
        var noDir = false
        val logs = mutableListOf<String>()
        var failure: String? = null

        for (raw in stdout.lineSequence()) {
            val line = raw.trimEnd()
            if (!line.startsWith("$TAG ")) continue
            val rest = line.removePrefix("$TAG ")
            val key = rest.substringBefore(' ')
            val value = if (rest.contains(' ')) rest.substringAfter(' ') else ""
            when (key) {
                "home" -> home = value.ifBlank { null }
                "pid" -> pid = value.trim().toIntOrNull() ?: pid
                "url" -> url = value.trim().ifBlank { null }
                "reused" -> {
                    reused = true
                    pid = value.trim().toIntOrNull() ?: pid
                }
                "nodsh" -> noDsh = true
                "nocwd" -> noCwd = true
                "nodir" -> noDir = true
                "exited" -> failure = "dsh web 启动后立刻退出了"
                "timeout" -> failure = "等了一段时间，dsh web 还没打印启动地址"
                "log" -> logs += value
            }
        }
        if (keyIsReused(stdout)) reused = true

        return when {
            noDsh -> RemoteDshResult.MissingDsh
            noDir -> RemoteDshResult.Failed("远端家目录不可写（无法创建 ~/.dsh-watch）", logs)
            url != null -> RemoteDshResult.Started(url!!, pid, reused, home, noCwd)
            failure != null -> RemoteDshResult.Failed(failure!!, logs)
            else -> RemoteDshResult.Failed("远端没有返回可用的启动地址", logs)
        }
    }

    private fun keyIsReused(stdout: String): Boolean =
        stdout.lineSequence().any { it.startsWith("$TAG reused ") }

    /** POSIX 单引号转义：路径里可能有空格 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

sealed class RemoteDshResult {
    /** 远端根本没有 dsh 命令 */
    data object MissingDsh : RemoteDshResult()

    /** 起来了（或复用了已有的）：[launchUrl] 形如 `http://127.0.0.1:3080/?token=…` */
    data class Started(
        val launchUrl: String,
        val pid: Int?,
        val reused: Boolean,
        val home: String?,
        /** 用户配的 cwd 在远端不存在（不致命，用登录目录继续） */
        val cwdMissing: Boolean,
    ) : RemoteDshResult()

    data class Failed(val reason: String, val logs: List<String>) : RemoteDshResult() {
        /** 给用户看的完整描述：原因 + 远端日志尾部 */
        fun describe(): String =
            if (logs.isEmpty()) reason else reason + "：\n" + logs.takeLast(6).joinToString("\n")
    }
}
