package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.TodoItem
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.stringListOf
import kotlinx.serialization.json.JsonObject

/**
 * 任务清单工具。
 *
 * 刻意做成**一次写入整个清单**（而不是 add/complete 之类的细粒度操作）：
 * 每次调用都要花一次往返和一段上下文，而清单本身只有几行 ——
 * 整份重写既便宜又能保证模型看到的就是最新全貌，不会出现「我以为是第 3 步、其实是第 5 步」。
 */
object TodoTools {

    fun all(): List<AgentTool> = listOf(TodoWriteTool)

    object TodoWriteTool : AgentTool {
        override val name = "todo_write"
        override val description =
                        "创建或更新本次任务的待办清单，用户可实时看到进度。**3 步以上的任务先用它列一遍**。" +
            "每次传**完整清单**（不是增量），前缀：\"[x] \" 已完成 / \"[>] \" 进行中 / \"[ ] \" 未完成，其它写法一律按未开始处理。每完成一步更新一次；全部完成后把各项都标 [x] 并保留清单。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf(
                "todos" to Schema.arr(
                    "完整清单，每项一行。已完成写 \"[x] 内容\"，进行中 \"[>] 内容\"，未开始 \"[ ] 内容\""
                ),
            ),
            required = listOf("todos"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val board = ctx.todos ?: return ToolResult.fail("当前环境不支持任务清单")
            val raw = args.stringListOf("todos")
            if (raw.isEmpty()) {
                return ToolResult.fail(
                    "todos 不能为空。至少要给出一步。如果任务确实只有一步，就不需要用这个工具，直接做即可。"
                )
            }

            val items = raw.take(20).map { line ->
                val t = line.trim()
                when {
                    t.startsWith("[x]", true) || t.startsWith("✅") ->
                        TodoItem(t.removePrefix("[x]").removePrefix("[X]").removePrefix("✅").trim(), true)

                    t.startsWith("[>]", true) || t.startsWith("→") ->
                        TodoItem(t.removePrefix("[>]").removePrefix("→").trim(), false)

                    else -> TodoItem(t.removePrefix("[ ]").trim(), false)
                }
            }.filter { it.text.isNotBlank() }

            if (items.isEmpty()) return ToolResult.fail("清单里没有任何有效条目")

            board.set(ctx.sessionDir, items)

            val done = items.count { it.done }
            return ToolResult.ok(
                buildString {
                    append("已更新任务清单（").append(done).append('/').append(items.size).append(" 完成）：\n")
                    items.forEach { item ->
                        append(if (item.done) "- [x] " else "- [ ] ").append(item.text).append('\n')
                    }
                    if (done == items.size) {
                        append("全部完成。可以给用户一个简短总结了。")
                    } else {
                        append("继续做下一项；每完成一步记得回来更新这张清单。")
                    }
                }
            )
        }

        override fun summarize(args: JsonObject): String {
            val n = args.stringListOf("todos").size
            return "$n 项"
        }
    }
}
