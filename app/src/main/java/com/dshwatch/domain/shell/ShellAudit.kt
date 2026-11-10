package com.dshwatch.domain.shell

import android.content.Context
import java.io.File

/**
 * 提权命令的本地审计账本。
 *
 * 有提权就必须有账：用户某天发现手机被改乱了，得能查到「哪条命令、什么时候、
 * 谁批准的」。这不是合规姿态 —— 一个能跑 `pm uninstall` 的 App 如果没有记录，
 * 出问题时连自己都说不清。
 *
 * 落盘格式是最土的 TSV：一行一条，追加写，超长滚动。不引数据库，
 * 因为这个文件最多几百行，用 SQLite 属于给自己找一个会坏的零件。
 */
object ShellAudit {

    private const val FILE = "shell_audit.tsv"
    private const val MAX_LINES = 300

    data class Entry(
        val at: Long,
        val risk: String,
        val category: String,
        val decision: String,
        val exitCode: Int,
        val command: String,
    )

    private fun file(context: Context) = File(context.filesDir, FILE)

    /**
     * 记一条。
     *
     * `decision` 用英文短词（allow_once / allow_always / deny / auto），
     * 避免后续解析时被多语言文案带偏。
     */
    fun record(
        context: Context,
        command: String,
        risk: ShellRisk,
        category: ShellCategory,
        decision: String,
        exitCode: Int,
    ) {
        runCatching {
            val f = file(context)
            val line = listOf(
                System.currentTimeMillis().toString(),
                risk.name.lowercase(),
                category.key,
                decision,
                exitCode.toString(),
                command.replace('\t', ' ').replace('\n', ' ').take(500),
            ).joinToString("\t")

            synchronized(this) {
                if (f.exists() && f.length() > 256 * 1024) {
                    val tail = f.readLines().takeLast(MAX_LINES / 2)
                    f.writeText(tail.joinToString("\n", postfix = "\n"))
                }
                f.appendText(line + "\n")
            }
        }
    }

    fun recent(context: Context, limit: Int = 50): List<Entry> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        f.readLines().asReversed().take(limit).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 6) return@mapNotNull null
            Entry(
                at = p[0].toLongOrNull() ?: 0L,
                risk = p[1],
                category = p[2],
                decision = p[3],
                exitCode = p[4].toIntOrNull() ?: -1,
                command = p.drop(5).joinToString(" "),
            )
        }
    }.getOrDefault(emptyList())
}
