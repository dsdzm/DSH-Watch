package com.dshwatch.domain.agent.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.ExpressionEvaluator
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.rawStrOf
import com.dshwatch.domain.agent.stringMapOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.net.CustomHeaders
import com.dshwatch.net.HtmlText
import com.dshwatch.net.Http
import com.dshwatch.service.AlarmReceiver
import com.dshwatch.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 内置工具集。
 *
 * 选型原则：手表上**没有外部存储、也没有本机编译器**，所以拆成三半 ——
 *  - 信息类：查时间、算数、看设备/用量、抓一个 API、定闹钟；
 *  - 产出类：见 [WorkspaceTools]，在**应用私有的工作空间目录**里建文件、
 *    再交给 ProjectBuilder 校验打包成可预览的单文件 HTML；
 *  - 执行类：见 [ScriptTools] 与 [CloudTools]。前者用设备上真实的 sh / JS / Python
 *    把代码跑起来（可选、默认关闭、每次确认）；后者把编译交给云端 GitHub Actions，
 *    这样 Kotlin、APK 这类本机根本做不了的事情也能做。
 *
 * 刻意**不做剪贴板工具**：把文本丢进剪贴板对用户来说是"东西又没了"，
 * 而工作空间里的文件是留在设备上、可读可改可预览的真产物。
 */
object BuiltinTools {

    fun all(): List<AgentTool> = listOf(
        DateTimeTool,
        CalculatorTool,
        DeviceInfoTool,
        UsageTool,
        HttpFetchTool,
        SetAlarmTool,
    ) + WorkspaceTools.all() + SystemTools.all() + TodoTools.all() + DeviceIntegrationTools.all() +
        ScriptTools.all() + CloudTools.all()

    // ------------------------------------------------------------------
    // 1. 时间
    // ------------------------------------------------------------------

    object DateTimeTool : AgentTool {
        override val name = "get_datetime"
        override val description = "获取设备当前的本地日期、时间、星期与时区，以及 Unix 时间戳。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(emptyMap())

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            val week = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now))
            return ToolResult.ok(
                buildString {
                    append("本地时间：").append(Fmt.dateTime(now)).append('\n')
                    append("星期：").append(week).append('\n')
                    append("时区：").append(java.util.TimeZone.getDefault().id)
                        .append("（UTC").append(offsetString(cal)).append("）\n")
                    append("Unix 毫秒：").append(now)
                }
            )
        }

        private fun offsetString(cal: Calendar): String {
            val offset = cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)
            val hours = offset / 3_600_000
            val minutes = (offset % 3_600_000) / 60_000
            return if (minutes == 0) {
                (if (hours >= 0) "+" else "") + hours
            } else {
                String.format(Locale.US, "%+03d:%02d", hours, minutes)
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. 计算
    // ------------------------------------------------------------------

    object CalculatorTool : AgentTool {
        override val name = "calculate"
        override val description =
            "计算数学表达式，支持 + - * / % ^ 与括号，以及 sqrt/abs/round/floor/ceil/ln/log10/sin/cos/tan/pow/min/max 和常量 pi、e。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf("expression" to Schema.str("要计算的表达式，例如 (12+8)*3/2 或 sqrt(144)")),
            required = listOf("expression"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val expr = args.strOf("expression") ?: return ToolResult.fail("缺少 expression 参数")
            return runCatching { ExpressionEvaluator.evaluateToText(expr) }
                .fold(
                    onSuccess = { ToolResult.ok("$expr = $it") },
                    onFailure = { ToolResult.fail("表达式无法计算：${it.message}") },
                )
        }

        override fun summarize(args: JsonObject) = args.strOf("expression") ?: "—"
    }

    // ------------------------------------------------------------------
    // 3. 设备信息
    // ------------------------------------------------------------------

    object DeviceInfoTool : AgentTool {
        override val name = "get_device_info"
        override val description = "读取当前手表的性能档位、电量、剩余存储、内存、屏幕与系统版本。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(emptyMap())

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val c = ctx.requireContext()
            val profile = ctx.profile()
            return ToolResult.ok(
                buildString {
                    append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append("系统：Android ").append(Build.VERSION.RELEASE)
                        .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
                    append("屏幕：").append(profile.summary).append('\n')
                    append("CPU：").append(profile.cores).append(" 核 · 总内存 ")
                        .append(profile.totalMemMb).append(" MB\n")
                    append("性能档位：").append(profile.tier.name).append('\n')
                    append("电量：").append(batteryText(c)).append('\n')
                    append("存储：").append(storageText(c)).append('\n')
                    append("内存：").append(memoryText(c))
                }
            )
        }

        private fun batteryText(context: Context): String {
            val bm = context.getSystemService<android.os.BatteryManager>() ?: return "未知"
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val charging = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                ?.let {
                    it == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        it == android.os.BatteryManager.BATTERY_STATUS_FULL
                } ?: false
            return if (level in 0..100) "$level%${if (charging) "（充电中）" else ""}" else "未知"
        }

        private fun storageText(context: Context): String = runCatching {
            val stat = android.os.StatFs(context.filesDir.absolutePath)
            val free = stat.availableBytes / (1024.0 * 1024 * 1024)
            val total = stat.totalBytes / (1024.0 * 1024 * 1024)
            String.format(Locale.US, "可用 %.1f GB / 共 %.1f GB", free, total)
        }.getOrDefault("未知")

        private fun memoryText(context: Context): String = runCatching {
            val am = context.getSystemService<android.app.ActivityManager>() ?: return "未知"
            val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            String.format(
                Locale.US,
                "可用 %.0f MB / 共 %.0f MB",
                info.availMem / 1048576.0,
                info.totalMem / 1048576.0,
            )
        }.getOrDefault("未知")
    }

    // ------------------------------------------------------------------
    // 4. 用量
    // ------------------------------------------------------------------

    object UsageTool : AgentTool {
        override val name = "get_token_usage"
        override val description = "查询本机记录的 token 用量与估算花费（今天 / 近 7 天 / 全部）。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(emptyMap())

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            ctx.usage.ensureLoaded()
            val records = ctx.usage.records.value
            if (records.isEmpty()) return ToolResult.ok("还没有任何用量记录。")
            val now = System.currentTimeMillis()
            val today = com.dshwatch.data.usage.UsageRepository.aggregate(
                records, Fmt.startOfDay(now), now + 1
            )
            val week = com.dshwatch.data.usage.UsageRepository.aggregate(
                records, now - 7 * 86_400_000L, now + 1
            )
            val all = com.dshwatch.data.usage.UsageRepository.aggregate(records, 0L, Long.MAX_VALUE)
            fun line(label: String, a: com.dshwatch.data.usage.UsageAggregate) =
                "$label：${Fmt.tokens(a.total)} tokens（输入 ${Fmt.tokens(a.prompt)} / 输出 ${Fmt.tokens(a.completion)}）· " +
                    "${a.requests} 次请求 · ${Fmt.money(a.cost)}"

            return ToolResult.ok(
                buildString {
                    append(line("今天", today)).append('\n')
                    append(line("近 7 天", week)).append('\n')
                    append(line("全部", all)).append('\n')
                    if (all.byModel.isNotEmpty()) {
                        append("按模型：")
                        append(all.byModel.take(3).joinToString("；") { "${it.model} ${Fmt.tokens(it.total)}" })
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 9. 抓网页 / 调接口
    // ------------------------------------------------------------------

    /**
     * 联网获取。
     *
     * 相比旧版做了两件**明显省 token** 的事：
     *
     *  1. **HTML 自动转纯文本**。一个普通网页的 HTML 九成以上是标签、脚本和内联样式，
     *     原样回灌等于把一次工具调用的开销浪费在 `<div class="nav-wrapper">` 上。
     *     实测一篇新闻页从 90KB 压到 3KB 左右，而模型要的信息一个没少。
     *  2. **支持 POST**。旧版只能 GET，于是「调用一个 API」这件事只能靠那些
     *     恰好接受 query string 的接口，稍微正规一点的（要 JSON body 的）就做不了。
     */
    object HttpFetchTool : AgentTool {
        override val name = "http_fetch"
        override val description =
            "抓取一个 URL 的内容。返回网页时会**自动转成纯文本**（去掉标签/脚本/样式），" +
                "所以很适合读文章、查文档、调返回 JSON 的公开 API。" +
                "支持 GET 与 POST（POST 可带 body）。只支持 http/https。" +
                "需要登录态、Referer、特定 User-Agent 或 API Key 时，用 headers 传自定义请求头" +
                "（键值对，例如 {\"Authorization\":\"Bearer xxx\",\"Referer\":\"https://example.com\"}）。" +
                "要搜索（不知道具体网址）请用 web_search。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "url" to Schema.str("完整 URL，必须带 http:// 或 https://"),
                "method" to Schema.str("GET 或 POST，默认 GET", listOf("GET", "POST")),
                "body" to Schema.str("POST 的请求体，通常是 JSON 字符串"),
                "content_type" to Schema.str("POST 的 Content-Type，默认 application/json"),
                "headers" to Schema.strMap(
                    "自定义请求头（键值对，值必须是字符串）。可覆盖默认的 User-Agent / Accept；" +
                        "Host、Content-Length、Connection 等由传输层管理的头会被忽略"
                ),
                "max_chars" to Schema.int("最多返回多少字符，默认 4000，上限 16000", 200, 16000),
                "format" to Schema.str(
                    "auto=网页转纯文本、JSON 原样（默认）；raw=原样返回不转换",
                    listOf("auto", "raw"),
                ),
            ),
            required = listOf("url"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val url = args.strOf("url") ?: return@withContext ToolResult.fail("缺少 url 参数")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return@withContext ToolResult.fail("URL 必须以 http:// 或 https:// 开头")
                }
                val method = args.strOf("method")?.uppercase() ?: "GET"
                val max = (args.intOf("max_chars") ?: 4000).coerceIn(200, 16000)
                val raw = args.strOf("format") == "raw"
                val custom = CustomHeaders.sanitize(args.stringMapOf("headers"))

                val request = runCatching {
                    val builder = Request.Builder()
                        .url(url)
                        .header("Accept", "*/*")
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                        )
                    // 自定义头放在默认头之后：同名会替换默认值（removeHeader + header），
                    // 所以模型可以改 UA / Accept / Referer / Authorization。
                    CustomHeaders.apply(builder, custom.values)
                    if (method == "POST") {
                        val payload = args.rawStrOf("body").orEmpty()
                        if (payload.isBlank()) {
                            return@withContext ToolResult.fail("POST 请求需要提供 body")
                        }
                        val ctype = args.strOf("content_type") ?: "application/json"
                        builder.post(
                            payload.toRequestBody(ctype.toMediaTypeOrNull())
                        )
                    } else {
                        builder.get()
                    }
                    builder.build()
                }.getOrElse { return@withContext ToolResult.fail("请求构造失败：${it.message}") }

                runCatching {
                    Http.client.newCall(request).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        val ctype = resp.header("Content-Type").orEmpty()
                        val looksHtml = !raw && HtmlText.isHtml(ctype, body)
                        val rendered = if (looksHtml) HtmlText.extract(body) else body
                        val trimmed = rendered.take(max)

                        val text = buildString {
                            append("HTTP ").append(resp.code).append('\n')
                            if (custom.rejected.isNotEmpty()) {
                                append(CustomHeaders.rejectedNote(custom.rejected)).append('\n')
                            }
                            if (ctype.isNotBlank()) append("Content-Type: ").append(ctype).append('\n')
                            if (looksHtml) {
                                append("已把 HTML 转为纯文本（原 ")
                                    .append(body.length).append(" 字 → ")
                                    .append(rendered.length).append(" 字）")
                            } else {
                                append("正文 ").append(body.length).append(" 字")
                            }
                            if (rendered.length > max) append("，已截断到 ").append(max)
                            append("：\n").append(trimmed)
                            if (rendered.length > max) {
                                append("\n…（内容被截断。要更多可以调大 max_chars，")
                                append("或先用 grep 在你已经存到工作空间的副本里找目标内容）")
                            }
                        }
                        if (resp.isSuccessful) ToolResult.ok(text) else ToolResult.fail(text)
                    }
                }.getOrElse {
                    ToolResult.fail(
                        "请求失败：${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}。" +
                            "如果是 DNS/连接错误，说明这台设备访问不了该地址。"
                    )
                }
            }

        override fun summarize(args: JsonObject) =
            (args.strOf("method") ?: "GET") + " " + (args.strOf("url") ?: "—")
    }

    // ------------------------------------------------------------------
    // 10. 闹钟
    // ------------------------------------------------------------------

    object SetAlarmTool : AgentTool {
        override val name = "set_alarm"
        override val description = "在手表上设置一个闹钟。时间格式 HH:mm（24 小时制），如果该时刻今天已过则自动顺延到明天。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "time" to Schema.str("闹钟时间，24 小时制，格式 HH:mm，例如 07:30"),
                "label" to Schema.str("闹钟标签，例如「开会」"),
            ),
            required = listOf("time"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val raw = args.strOf("time") ?: return@withContext ToolResult.fail("缺少 time 参数")
                val label = args.strOf("label") ?: "闹钟"
                val parts = raw.trim().split(':', '：')
                if (parts.size != 2) return@withContext ToolResult.fail("时间格式应为 HH:mm，收到「$raw」")
                val hour = parts[0].trim().toIntOrNull()
                    ?: return@withContext ToolResult.fail("小时部分不是数字：「${parts[0]}」")
                val minute = parts[1].trim().toIntOrNull()
                    ?: return@withContext ToolResult.fail("分钟部分不是数字：「${parts[1]}」")
                if (hour !in 0..23 || minute !in 0..59) {
                    return@withContext ToolResult.fail("时间超出范围：$hour:$minute")
                }

                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }

                val triggerAt = cal.timeInMillis
                val intent = Intent(ctx.requireContext(), AlarmReceiver::class.java).apply {
                    action = AlarmReceiver.ACTION_FIRE
                    putExtra(AlarmReceiver.EXTRA_LABEL, label)
                    putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, triggerAt)
                }
                // 这个 hash 同时是 PendingIntent 的 requestCode 和闹钟记录的 id ——
                // 取消时必须用同一个值，所以两处都从这里取，不能各算一遍。
                val alarmId = (label + triggerAt).hashCode()
                val pending = PendingIntent.getBroadcast(
                    ctx.requireContext(),
                    alarmId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                val am = ctx.requireContext().getSystemService<AlarmManager>()
                    ?: return@withContext ToolResult.fail("系统没有闹钟服务")

                val scheduled = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                        am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                        "非精确"
                    } else {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                        "精确"
                    }
                }.getOrElse {
                    return@withContext ToolResult.fail("设置闹钟失败：${it.message}")
                }

                // AlarmManager 查不到自己有哪些闹钟，所以这里留一份账，
                // 否则用户问「你设了几个闹钟」时模型只能瞎猜。
                ctx.alarms?.add(
                    com.dshwatch.data.store.AlarmRecord(id = alarmId, label = label, triggerAt = triggerAt)
                )

                val delayMin = ((triggerAt - System.currentTimeMillis()) / 60_000.0).roundToInt()
                ToolResult.ok(
                    "已设置${scheduled}闹钟「$label」→ ${Fmt.dateTime(triggerAt)}" +
                        "（约 ${delayMin} 分钟后，id=$alarmId，可用 cancel_alarm 取消）"
                )
            }

        override fun summarize(args: JsonObject) =
            "${args.strOf("time")} ${args.strOf("label").orEmpty()}".trim()
    }
}
