package com.dshwatch.domain.agent.tools

import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.rawStrOf
import com.dshwatch.domain.agent.strOf
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 路径不合法时的统一说辞。
 *
 * 关键是给出**怎么办**，而不是只说「不合法」——
 * 模型收到「路径越界」时会原地重试；收到「改用相对路径，例如 index.html」才会改。
 */
internal fun badWorkspacePath(path: String): ToolResult = ToolResult.fail(
    "路径「" + path + "」不允许使用：不能用绝对路径（如 /data/data/...），" +
        "也不能用 .. 跳出工作目录。请改成当前工作目录下的相对路径，例如 index.html、src/app.js。"
)

/**
 * 本地工作空间的基础文件工具。
 *
 * 这是 agent 在手表上「真正产出东西」的落点：写文件 → 校验 → 打包成可预览产物。
 * 搜索（glob/grep）与构建（build_project）拆在各自文件里，见 [SearchTools] / [BuildTools]。
 *
 * ## 路径约定
 * 工具收到的 path 是**相对于「当前会话的工作目录」**的短路径（如 `index.html`、`src/app.py`）。
 * 每个会话有独立目录（`progress/<会话 id 前缀>`），所以两个会话各写同名文件不会互相覆盖。
 * 拼接与反向转换由 [ToolContext.scope] / [ToolContext.unscope] 完成。
 */
object WorkspaceTools {

    fun all(): List<AgentTool> = listOf(
        ListFilesTool,
        ReadFileTool,
        WriteFileTool,
        EditFileTool,
        AppendFileTool,
        MakeDirTool,
        MoveFileTool,
        DeleteFileTool,
    ) + SearchTools.all() + BuildTools.all()

    // ------------------------------------------------------------------

    object ListFilesTool : AgentTool {
        override val name = "list_files"
        override val description =
            "列出当前工作目录下的所有文件（递归、带大小与修改时间）。" +
                "返回的路径就是后续 read_file / edit_file / build_project 该使用的格式。" +
                "留空表示列出当前工作目录的全部内容。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf(
                "dir" to Schema.str("限定子目录，留空表示当前工作目录"),
            ),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val raw = args.strOf("dir").orEmpty()
            val dir = ctx.scope(raw) ?: return badWorkspacePath(raw)
            val files = ctx.workspace.listFilesRecursive(dir)
            if (files.isEmpty()) {
                val where = if (raw.isBlank()) "" else "（" + raw + "）"
                return ToolResult.ok("当前工作目录" + where + "里还没有文件。可以用 write_file 创建。")
            }
            val total = files.sumOf { it.size }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val head = "工作目录：" + ctx.workDirLabel + "\n" +
                "共 " + files.size + " 个文件，合计 " + (total / 1024) + " KB（路径相对于工作目录）：\n"
            val body = buildString {
                files.take(120).forEach { f ->
                    append("- ").append(ctx.unscope(f.path))
                        .append("  (").append(f.size).append(" B, ")
                        .append(fmt.format(Date(f.updatedAt))).append(")\n")
                }
                if (files.size > 120) append("…（还有 ").append(files.size - 120).append(" 个未列出）")
            }
            return ToolResult.ok(head + body)
        }

        override fun summarize(args: JsonObject) = args.strOf("dir")?.let { "目录 " + it } ?: "全部"
    }

    object ReadFileTool : AgentTool {
        override val name = "read_file"
        override val description =
            "读取当前工作目录里某个文件的文本内容（单次最多 24000 字，超出会截断）。" +
                "大文件可以配合 offset 分段读：offset 是起始字符位置，例如先读 0，再读 8000。" +
                "「工具输出已落盘」的文件通常在 notes/tool-output/ 下，用 offset 或 grep 取中间内容。" +
                "改文件之前先用它看清原文，再把要改的片段原样复制到 edit_file 的 old_string 里。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf(
                "path" to Schema.str("文件的相对路径，例如 index.html 或 src/app.js"),
                "offset" to Schema.int("从第几个字符开始读，默认 0。读长文件的中段时用", 0, 400_000),
                "max_chars" to Schema.int("最多返回多少字符，默认 24000", 100, 24000),
            ),
            required = listOf("path"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.strOf("path") ?: return ToolResult.fail("缺少 path 参数")
            val scoped = ctx.scope(path) ?: return badWorkspacePath(path)
            val max = (args.intOf("max_chars") ?: WorkspaceStore.MAX_READ_CHARS)
                .coerceIn(100, WorkspaceStore.MAX_READ_CHARS)
            val offset = (args.intOf("offset") ?: 0).coerceIn(0, 400_000)
            // WorkspaceStore.read 只支持「从头读 n 个字符」，这里多读 offset 个再切掉前缀。
            // 单文件上限 256KB，多读这一点内存可以接受，也比新增一套 range API 简单。
            val raw = ctx.workspace.read(scoped, (offset + max).coerceAtMost(400_000 + WorkspaceStore.MAX_READ_CHARS))
            if (raw == null) {
                // 路径不合法和「文件不存在」是两回事，必须分开说，否则模型会一直重试同一个错路径
                val why = ctx.workspace.describeIssue(scoped)
                return ToolResult.fail(
                    if (why != null) {
                        "读不到「" + path + "」：" + why
                    } else {
                        "读不到「" + path + "」：文件不存在，或它是一个目录。可以先用 list_files 看有什么。"
                    }
                )
            }
            return ToolResult.ok(if (offset <= 0) raw else raw.drop(offset))
        }

        override fun summarize(args: JsonObject) = args.strOf("path") ?: "—"
    }

    object WriteFileTool : AgentTool {
        override val name = "write_file"
        override val description =
            "在当前工作目录里创建或覆盖一个文件。目录会自动创建。单文件上限 256KB。" +
                "path 用**相对路径**，例如 index.html、src/app.py、styles/main.css。" +
                "**改已有文件请优先用 edit_file**；只有整份重写时才用本工具。" +
                "内容较长时先写骨架再用 append_file 分段追加 —— 单次输出有上限，一次写太长会被截断。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "path" to Schema.str("相对路径，例如 index.html、src/app.py、styles/main.css"),
                "content" to Schema.str("完整文件内容"),
            ),
            required = listOf("path", "content"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.strOf("path") ?: return ToolResult.fail("缺少 path 参数")
            val content = args.strOf("content") ?: return ToolResult.fail("缺少 content 参数")
            val scoped = ctx.scope(path) ?: return badWorkspacePath(path)
            val entry = ctx.workspace.write(scoped, content)
            if (entry == null) {
                val why = ctx.workspace.describeIssue(scoped)
                return ToolResult.fail(
                    if (why != null) {
                        "写入「" + path + "」失败：" + why
                    } else {
                        "写入「" + path + "」失败：单文件超过 256KB，或工作空间已达上限（400 个文件 / 32MB）。"
                    }
                )
            }
            return ToolResult.ok("已写入 " + ctx.unscope(entry.path) + "（" + entry.size + " 字节）")
        }

        override fun summarize(args: JsonObject) =
            (args.strOf("path") ?: "—") + "（" + (args.strOf("content")?.length ?: 0) + " 字）"
    }

    /**
     * 精确字符串替换。
     *
     * 这是**改文件时应该优先用的工具**。以前模型只能 read 全文再 write 回去 ——
     * 在电脑上无所谓，但在手表上这意味着：读一遍花掉一次上下文，再写一遍又花一次，
     * 而且写全文特别容易撞上单次输出上限、参数被截断，整次调用白费。
     * 改一行就只花一行的大小，才是对的选择。
     *
     * 失败时给的都是**可执行的下一步**，而不是「替换失败」四个字。
     */
    object EditFileTool : AgentTool {
        override val name = "edit_file"
        override val description =
            "对已有文件做**精确字符串替换**。改文件优先用它，不要 read 全文再 write 回去 —— " +
                "手表上那样要多花一倍 token，还很容易因为输出被截断而整次失败。" +
                "old_string 必须与文件中现有内容**逐字符完全一致**（含缩进与换行），" +
                "通常从 read_file 的结果里直接复制。" +
                "如果 old_string 在文件里出现多次会失败：要么多带几行上下文让它唯一，要么把 replace_all 设为 true。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "path" to Schema.str("文件相对路径"),
                "old_string" to Schema.str("要被替换掉的原文，必须逐字符一致"),
                "new_string" to Schema.str("替换成的新内容；传空字符串表示删除这段"),
                "replace_all" to Schema.bool("是否替换所有出现位置，默认 false（要求唯一匹配）"),
            ),
            required = listOf("path", "old_string", "new_string"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.strOf("path") ?: return ToolResult.fail("缺少 path 参数")
            val old = args.rawStrOf("old_string")
                ?: return ToolResult.fail("缺少 old_string 参数（要替换掉的原文）")
            val new = args.rawStrOf("new_string")
                ?: return ToolResult.fail("缺少 new_string 参数（替换成的内容；删除这段就传空字符串）")
            if (old.isEmpty()) {
                return ToolResult.fail(
                    "old_string 不能为空。要追加内容请用 append_file；要整份重写请用 write_file。"
                )
            }
            if (old == new) return ToolResult.ok("新旧内容相同，无需修改")

            val scoped = ctx.scope(path) ?: return badWorkspacePath(path)
            val content = ctx.workspace.read(scoped, maxChars = Int.MAX_VALUE)
            if (content == null) {
                val why = ctx.workspace.describeIssue(scoped)
                return ToolResult.fail(
                    if (why != null) {
                        "读不到「" + path + "」：" + why
                    } else {
                        "读不到「" + path + "」：文件不存在。要新建文件请用 write_file。"
                    }
                )
            }

            val hits = content.windowed(old.length, 1).count { it == old }
            if (hits == 0) {
                return ToolResult.fail(
                    "在「" + path + "」里找不到 old_string。最常见的原因是缩进（空格/制表符）" +
                        "或换行与原文不一致 —— 建议先用 read_file 读回来，把要改的那几行**原样**复制进去，" +
                        "不要凭记忆重写。"
                )
            }
            val replaceAll = args.boolOf("replace_all", false)
            if (hits > 1 && !replaceAll) {
                return ToolResult.fail(
                    "old_string 在「" + path + "」里出现了 " + hits + " 次，无法确定改哪一处。" +
                        "请多带几行上下文（前后各留一行通常就够）让它唯一，或者把 replace_all 设为 true。"
                )
            }

            val updated = if (replaceAll) content.replace(old, new) else content.replaceFirst(old, new)
            if (updated == content) return ToolResult.ok("新旧内容相同，无需修改")

            val written = ctx.workspace.write(scoped, updated)
                ?: return ToolResult.fail("改写「" + path + "」失败：可能超过单文件 256KB 上限。")
            return ToolResult.ok(
                "已编辑 " + ctx.unscope(written.path) + "（替换 " + hits + " 处，现在 " +
                    written.size + " 字节）"
            )
        }

        override fun summarize(args: JsonObject): String {
            val del = args.rawStrOf("old_string")?.length ?: 0
            val add = args.rawStrOf("new_string")?.length ?: 0
            return (args.strOf("path") ?: "—") + "（-" + del + "/+" + add + " 字）"
        }
    }

    object AppendFileTool : AgentTool {
        override val name = "append_file"
        override val description =
            "在当前工作目录的文件末尾追加内容。文件不存在时会创建。" +
                "适合分几次写长文件 —— 单次输出有上限，一次写完长文件很容易被截断。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "path" to Schema.str("相对路径"),
                "content" to Schema.str("要追加的内容"),
            ),
            required = listOf("path", "content"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.strOf("path") ?: return ToolResult.fail("缺少 path 参数")
            val content = args.strOf("content") ?: return ToolResult.fail("缺少 content 参数")
            val scoped = ctx.scope(path) ?: return badWorkspacePath(path)
            val entry = ctx.workspace.append(scoped, content)
            if (entry == null) {
                val why = ctx.workspace.describeIssue(scoped)
                return ToolResult.fail(
                    if (why != null) {
                        "追加到「" + path + "」失败：" + why
                    } else {
                        "追加到「" + path + "」失败：追加后会超过 256KB 单文件上限。"
                    }
                )
            }
            return ToolResult.ok(
                "已追加到 " + ctx.unscope(entry.path) + "，现在共 " + entry.size + " 字节"
            )
        }

        override fun summarize(args: JsonObject) =
            (args.strOf("path") ?: "—") + "（+" + (args.strOf("content")?.length ?: 0) + " 字）"
    }

    object MakeDirTool : AgentTool {
        override val name = "make_dir"
        override val description = "在当前工作目录里创建一个目录（含中间层级）。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf("path" to Schema.str("目录相对路径，例如 src/styles")),
            required = listOf("path"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.strOf("path") ?: return ToolResult.fail("缺少 path 参数")
            val scoped = ctx.scope(path) ?: return badWorkspacePath(path)
            return if (ctx.workspace.mkdir(scoped)) {
                ToolResult.ok("已创建目录 " + path)
            } else {
                ToolResult.fail("创建目录「" + path + "」失败：路径不合法，或它已经是个文件。")
            }
        }

        override fun summarize(args: JsonObject) = args.strOf("path") ?: "—"
    }

    object MoveFileTool : AgentTool {
        override val name = "move_file"
        override val description =
            "把文件或目录移动到新位置（也可用于重命名）。目标已存在时会失败，不会覆盖 —— " +
                "确认要覆盖请先 delete_file。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "from" to Schema.str("源路径（相对当前工作目录）"),
                "to" to Schema.str("目标路径（相对当前工作目录）"),
            ),
            required = listOf("from", "to"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val from = args.strOf("from") ?: return ToolResult.fail("缺少 from 参数")
            val to = args.strOf("to") ?: return ToolResult.fail("缺少 to 参数")
            val src = ctx.scope(from) ?: return badWorkspacePath(from)
            val dst = ctx.scope(to) ?: return badWorkspacePath(to)
            if (src == dst) return ToolResult.ok("源和目标相同，无需移动")
            return if (ctx.workspace.move(src, dst)) {
                ToolResult.ok("已移动 " + from + " → " + to)
            } else {
                ToolResult.fail(
                    "移动失败：源「" + from + "」不存在，或目标「" + to + "」已经存在（本工具不覆盖）。" +
                        "可以用 list_files 确认现状。"
                )
            }
        }

        override fun summarize(args: JsonObject) =
            (args.strOf("from") ?: "—") + " → " + (args.strOf("to") ?: "—")
    }

    object DeleteFileTool : AgentTool {
        override val name = "delete_file"
        override val description = "删除当前工作目录里的文件或目录（目录会连同内容一起删除）。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf("path" to Schema.str("要删除的相对路径")),
            required = listOf("path"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.strOf("path") ?: return ToolResult.fail("缺少 path 参数")
            val scoped = ctx.scope(path) ?: return badWorkspacePath(path)
            return if (ctx.workspace.delete(scoped)) {
                ToolResult.ok("已删除 " + path)
            } else {
                ToolResult.fail("删除「" + path + "」失败：文件不存在，或路径不合法。")
            }
        }

        override fun summarize(args: JsonObject) = args.strOf("path") ?: "—"
    }
}
