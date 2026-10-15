package com.dshwatch.domain.agent.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.UiActionChannel
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.ui.components.mimeOf
import com.dshwatch.util.Fmt
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

/**
 * 与设备/系统打交道的工具。
 *
 * 共性：它们**不结束任务**，只是把用户送到下一步该去的地方。
 * 手表屏幕小，让用户自己去翻菜单找一个设置项的成本很高 ——
 * 能一步跳过去就跳过去。
 */
object DeviceIntegrationTools {

    fun all(): List<AgentTool> = listOf(OpenFileTool, OpenScreenTool, CreateEventTool)

    // ------------------------------------------------------------------

    /**
     * 打开工作空间里的文件。
     *
     * 两种走向，取决于界面接没接上：
     *  1. 接上了（正常情况）→ 走 App 内的预览页。这是手表上最可靠的路径，
     *     因为很多国产表**根本没有浏览器**，用系统 Intent 打开 HTML 会直接失败。
     *  2. 没接上 → 退化成系统 `ACTION_VIEW`，交给设备上能处理该类型的 App。
     */
    object OpenFileTool : AgentTool {
        override val name = "open_file"
        override val description =
            "在用户设备上打开工作空间里的文件（HTML 走内置预览、图片走图片查看）。" +
                "用户说「打开看看 / 让我看看效果」时用它，比让他自己去工作空间页翻要快。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf("path" to Schema.str("工作空间内的相对路径，例如 index.html 或 dist/demo.html")),
            required = listOf("path"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.strOf("path") ?: return ToolResult.fail("缺少 path 参数")
            val scoped = ctx.scope(path) ?: return badPath(path)
            val file = ctx.workspace.resolveForUi(scoped)
                ?: return ToolResult.fail("找不到「$path」，可以先用 list_files 看看有哪些文件。")
            if (!file.exists()) {
                return ToolResult.fail("「$path」还不存在。如果是要看构建产物，先调用 build_project。")
            }

            // 优先走 App 内预览
            val channel = ctx.uiActions
            if (channel?.previewFile != null) {
                channel.previewFile?.invoke(scoped)
                return ToolResult.ok("已在用户的设备上打开「${ctx.unscope(scoped)}」的预览。")
            }

            val context = ctx.requireContext()
            val uri = runCatching {
                FileProvider.getUriForFile(context, context.packageName + ".files", file)
            }.getOrNull() ?: return ToolResult.fail(
                "无法为「$path」生成可打开的地址（文件不在可导出目录内），请让用户到「工作空间」页查看。"
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult.ok("已请求系统打开「${ctx.unscope(scoped)}」。")
            } catch (e: ActivityNotFoundException) {
                ToolResult.fail(
                    "这台设备没有能打开该类型文件的应用。请让用户到 App 的「工作空间」页里预览。"
                )
            } catch (t: Throwable) {
                ToolResult.fail("打开失败：${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}")
            }
        }

        override fun summarize(args: JsonObject) = args.strOf("path") ?: "—"

        private fun badPath(path: String) = ToolResult.fail("路径「$path」不合法，请用相对路径。")
    }

    /**
     * 打开 App 内页面。
     *
     * 这是「AI 说『请去设置里配一下 API Key』」和「AI 直接把设置页打开」的区别。
     * 手表上后者省掉的是：返回 → 找到设置 → 在列表里滚动 → 找到那一项。
     */
    object OpenScreenTool : AgentTool {
        override val name = "open_screen"
        override val description =
            "打开 App 内的某个页面，把用户直接送到该去的地方。" +
                "当你需要用户手动做某件事时（配置 API Key、查看用量、看工作空间产物、跑连接诊断、" +
                "开启本地执行或云端编译），调用它，而不是只在回答里描述路径。" +
                "可用页面：settings（设置）、providers（供应商配置）、usage（用量统计）、" +
                "workspace（工作空间文件）、sessions（历史会话）、diagnostics（连接诊断）、" +
                "about（关于）、exec（执行与编译：本地脚本执行 / 云端编译的开关与配置）。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf(
                "screen" to Schema.str(
                    "要打开的页面",
                    UiActionChannel.allScreens,
                ),
            ),
            required = listOf("screen"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val raw = args.strOf("screen")?.lowercase() ?: return ToolResult.fail("缺少 screen 参数")
            val screen = normalize(raw)
                ?: return ToolResult.fail(
                    "没有名为「$raw」的页面。可用：" + UiActionChannel.allScreens.joinToString(", ")
                )
            val handler = ctx.uiActions?.openScreen
                ?: return ToolResult.fail("当前界面不在前台，无法跳转。请直接告诉用户该怎么操作。")
            handler.invoke(screen)
            return ToolResult.ok("已为用户打开「${label(screen)}」页面。")
        }

        override fun summarize(args: JsonObject) = args.strOf("screen") ?: "—"

        /** 容忍模型给的各种写法：中文名、带空格、大写、复数 */
        private fun normalize(raw: String): String? {
            val cleaned = raw.trim().lowercase().replace(" ", "").replace("_", "").replace("-", "")
            return when (cleaned) {
                "settings", "setting", "设置" -> UiActionChannel.SCREEN_SETTINGS
                "providers", "provider", "供应商", "供应商配置", "apikey" -> UiActionChannel.SCREEN_PROVIDERS
                "usage", "用量", "用量统计", "统计" -> UiActionChannel.SCREEN_USAGE
                "workspace", "工作空间", "文件" -> UiActionChannel.SCREEN_WORKSPACE
                "sessions", "session", "会话", "历史会话" -> UiActionChannel.SCREEN_SESSIONS
                "diagnostics", "diagnostic", "诊断", "连接诊断" -> UiActionChannel.SCREEN_DIAGNOSTICS
                "about", "关于" -> UiActionChannel.SCREEN_ABOUT
                "exec", "execution", "执行", "本地执行", "云端编译", "执行与编译" ->
                    UiActionChannel.SCREEN_EXEC
                else -> null
            }
        }

        private fun label(screen: String) = when (screen) {
            UiActionChannel.SCREEN_SETTINGS -> "设置"
            UiActionChannel.SCREEN_EXEC -> "执行与编译"
            UiActionChannel.SCREEN_PROVIDERS -> "供应商配置"
            UiActionChannel.SCREEN_USAGE -> "用量统计"
            UiActionChannel.SCREEN_WORKSPACE -> "工作空间"
            UiActionChannel.SCREEN_SESSIONS -> "历史会话"
            UiActionChannel.SCREEN_DIAGNOSTICS -> "连接诊断"
            else -> "关于"
        }
    }

    // ------------------------------------------------------------------

    /**
     * 新建日历日程。
     *
     * 走系统 `ACTION_INSERT`，也就是**打开日历 App 的新建界面并预填好字段**，
     * 由用户点一下保存 —— 而不是我们直接写数据库。
     *
     * 这么选有两个具体原因：
     *  1. 直接写 `CalendarContract` 需要 `READ_CALENDAR` / `WRITE_CALENDAR`，
     *     这俩都是**危险权限**，得走运行时申请。为了「加个日程」弹一次权限框，
     *     在手表上是很重的打扰。
     *  2. 很多国产手表 ROM 的日历 Provider 不完整甚至不存在，
     *     直接写库会抛异常；而 `ACTION_INSERT` 找不到日历 App 时只是失败，
     *     我们能据此给出明确的提示。
     */
    object CreateEventTool : AgentTool {
        override val name = "create_event"
        override val description =
            "在日历里新建一个日程。会打开系统的日历编辑界面并预填好标题与时间，由用户确认保存。" +
                "用户说「帮我安排 / 提醒我某天做某事」时用它。相对时间用 date + time 表达。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "title" to Schema.str("日程标题，例如「牙医」"),
                "time" to Schema.str("开始时间，24 小时制 HH:mm，例如 15:00"),
                "date" to Schema.str("日期：今天 / 明天 / 后天 / YYYY-MM-DD。留空按今天算"),
                "duration_minutes" to Schema.int("时长（分钟），默认 60", 5, 1440),
                "location" to Schema.str("地点，可留空"),
                "notes" to Schema.str("备注，可留空"),
            ),
            required = listOf("title", "time"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val title = args.strOf("title") ?: return ToolResult.fail("缺少 title 参数")
            val time = args.strOf("time") ?: return ToolResult.fail("缺少 time 参数")
            val begin = parseWhen(args.strOf("date"), time)
                ?: return ToolResult.fail(
                    "时间「${args.strOf("date") ?: ""} $time」看不懂。" +
                        "请用 time=\"15:00\" 加上可选 date=\"今天/明天/后天/2026-10-20\"。"
                )
            val minutes = (args.intOf("duration_minutes") ?: 60).coerceIn(5, 1440)
            val end = begin + minutes * 60_000L

            val context = ctx.requireContext()
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                args.strOf("location")?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                args.strOf("notes")?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            return try {
                context.startActivity(intent)
                ToolResult.ok(
                    "已打开系统日历的新建界面，预填好了「$title」${Fmt.dateTime(begin)}（$minutes 分钟）。" +
                        "请用户在界面上确认保存 —— 日程最终由系统日历写入，我们无法代他确认。"
                )
            } catch (e: ActivityNotFoundException) {
                ToolResult.fail("这台设备上没有可用的日历应用，无法新建日程。可以改用 set_alarm 设一个闹钟。")
            } catch (t: Throwable) {
                ToolResult.fail("打开日历失败：${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}")
            }
        }

        override fun summarize(args: JsonObject) =
            "${args.strOf("title")} @ ${args.strOf("date") ?: "今天"} ${args.strOf("time")}"

        /**
         * 解析「日期 + 时刻」。
         * 「今天」但时间已过的话顺延到明天 —— 否则用户说「下午 3 点提醒我」
         * 而现在是 4 点，会得到一个已经过去的日程。
         */
        private fun parseWhen(date: String?, time: String): Long? {
            val parts = time.trim().split(':', '：')
            if (parts.size != 2) return null
            val hour = parts[0].trim().toIntOrNull() ?: return null
            val minute = parts[1].trim().toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null

            val cal = Calendar.getInstance()
            val d = date?.trim().orEmpty()
            when {
                d.isEmpty() || d == "今天" || d.equals("today", true) -> Unit
                d == "明天" || d.equals("tomorrow", true) -> cal.add(Calendar.DAY_OF_YEAR, 1)
                d == "后天" -> cal.add(Calendar.DAY_OF_YEAR, 2)
                else -> {
                    val p = d.split('-', '/')
                    if (p.size != 3) return null
                    val y = p[0].toIntOrNull() ?: return null
                    val mo = p[1].toIntOrNull() ?: return null
                    val day = p[2].toIntOrNull() ?: return null
                    if (mo !in 1..12 || day !in 1..31) return null
                    cal.set(Calendar.YEAR, y)
                    cal.set(Calendar.MONTH, mo - 1)
                    cal.set(Calendar.DAY_OF_MONTH, day)
                }
            }
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            val relative = d.isEmpty() || d == "今天" || d.equals("today", true)
            if (relative && cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
    }
}
