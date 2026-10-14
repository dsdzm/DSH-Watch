package com.dshwatch.domain.agent.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.getSystemService
import com.dshwatch.data.store.AlarmRecord
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.PendingQuestion
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.agent.stringListOf
import com.dshwatch.net.Http
import com.dshwatch.service.AlarmReceiver
import com.dshwatch.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 与用户、系统交互的工具。
 *
 * 这批工具的共同点是「它们让 agent 从『自言自语』变成『能用起来』」：
 *  - [AskUserTool] 让它在信息不足时**直接问**，而不是猜；
 *  - 闹钟三件套（设置/查看/取消）让它能安排「以后要做的事」；
 *  - [VibrateTool] 给手表这个形态补上唯一的强提醒通道；
 *  - [GetClipboardTool] 把「我刚复制的一段代码」直接喂给模型，省掉在手表上打字的痛苦；
 *  - [UnitConvertTool] / [WebSearchTool] 补上模型最容易出错（算错单位）和最容易编造（过时事实）的两类问题。
 */
object SystemTools {

    fun all(): List<AgentTool> = listOf(
        AskUserTool,
        ListAlarmsTool,
        CancelAlarmTool,
        VibrateTool,
        GetClipboardTool,
        UnitConvertTool,
        WebSearchTool,
    )

    // ------------------------------------------------------------------

    /**
     * 向用户提问。
     *
     * 这是「agent 该有的样子」里最容易被漏掉的一块：模型碰到信息不全时，
     * 默认行为是**编一个合理的假设然后一路做下去** —— 在手表上尤其糟糕，
     * 因为它可能要写一堆文件、打包、最后你才发现方向完全不对。
     *
     * 有了这个工具，它可以选择先停下来问一句，拿到答复再继续，
     * 而且**任务不会中断**（不像把问题写进正文里等你回复，那已经是本轮结束了）。
     */
    object AskUserTool : AgentTool {
        override val name = "ask_user"
        override val description =
            "向用户提问并等待回答。当需求含糊、缺少关键信息、或有两个以上差别很大的方案需要用户选的时候用它，" +
                "**不要自己替用户假设**。尽量给出 2-4 个具体选项，用户也可以自己输入。" +
                "一次只问一个最关键的问题，问完再继续干活。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf(
                "question" to Schema.str("要问用户的问题，一句话说清楚，不要包含多个子问题"),
                "options" to Schema.arr("可选项（2-4 个，每个都是一句完整的话）。留空表示让用户自由回答"),
                "allow_free_text" to Schema.bool("是否允许用户输入选项之外的答案，默认 true"),
            ),
            required = listOf("question"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val question = args.strOf("question") ?: return ToolResult.fail("缺少 question 参数")
            val options = args.stringListOf("options").take(6)
            val allowFree = args["allow_free_text"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.lowercase() != "false"
            } ?: true

            val answer = ctx.prompter.ask(
                PendingQuestion(
                    callId = "",
                    question = question,
                    options = options,
                    allowFreeText = allowFree,
                )
            )
            return when {
                answer == null ->
                    ToolResult.fail(
                        "用户没有回答（界面不在前台，或已取消）。" +
                            "不要再重复提问：如果是无关紧要的细节，请选一个合理默认值继续；" +
                            "如果确实无法继续，就把需要用户确认的事项写进最终回答里。"
                    )

                answer.isBlank() -> ToolResult.ok("用户选择跳过这个问题（没有输入内容）。请自行决定一个合理默认值并继续。")
                else -> ToolResult.ok("用户回答：$answer")
            }
        }

        override fun summarize(args: JsonObject) = args.strOf("question")?.take(40) ?: "—"
    }

    // ------------------------------------------------------------------

    object ListAlarmsTool : AgentTool {
        override val name = "list_alarms"
        override val description = "列出你（agent）在手表上设置过的、还没到点的闹钟。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(emptyMap())

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val store = ctx.alarms ?: return ToolResult.fail("当前环境不支持闹钟管理")
            val list = store.pending()
            if (list.isEmpty()) return ToolResult.ok("当前没有待响的闹钟。")
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val now = System.currentTimeMillis()
            return ToolResult.ok(
                buildString {
                    append("共 ").append(list.size).append(" 个待响闹钟：\n")
                    list.forEach { a ->
                        val minutes = ((a.triggerAt - now) / 60_000).coerceAtLeast(0)
                        append("- id=").append(a.id).append("  「").append(a.label).append("」  ")
                            .append(fmt.format(Date(a.triggerAt)))
                            .append("（约 ").append(minutes).append(" 分钟后）\n")
                    }
                    append("需要取消请用 cancel_alarm，把上面的 id 传进去。")
                }
            )
        }

        override fun summarize(args: JsonObject) = "列出闹钟"
    }

    object CancelAlarmTool : AgentTool {
        override val name = "cancel_alarm"
        override val description =
            "取消一个已设置的闹钟。参数用 list_alarms 给出的 id；不确定 id 时可以先调 list_alarms。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "id" to Schema.int("list_alarms 返回的闹钟 id"),
            ),
            required = listOf("id"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args.intOf("id") ?: return ToolResult.fail("缺少 id 参数，可以先用 list_alarms 查看")
            val store = ctx.alarms ?: return ToolResult.fail("当前环境不支持闹钟管理")
            val removed = store.remove(id) ?: return ToolResult.fail("没有 id=$id 的闹钟，可以先用 list_alarms 看看现有的。")

            val context = ctx.requireContext()
            runCatching {
                val am = context.getSystemService<AlarmManager>()
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    action = AlarmReceiver.ACTION_FIRE
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    removed.id,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (pi != null) am?.cancel(pi)
            }
            return ToolResult.ok("已取消闹钟「${removed.label}」（${Fmt.dateTime(removed.triggerAt)}）")
        }

        override fun summarize(args: JsonObject) = "取消 id=${args.intOf("id") ?: "?"}"
    }

    // ------------------------------------------------------------------

    /**
     * 震动。
     * 手表上唯一的「强提醒」通道 —— 通知横幅容易被忽略，震动不会。
     * 典型用法：长任务做完了震一下提醒用户抬头看。
     */
    object VibrateTool : AgentTool {
        override val name = "vibrate"
        override val description =
            "让手表震动一下，用于提醒用户（比如长任务完成）。可指定时长与次数。不要频繁使用。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "duration_ms" to Schema.int("单次震动时长（毫秒），默认 400", 50, 3000),
                "times" to Schema.int("震动次数，默认 1", 1, 5),
            ),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val duration = (args.intOf("duration_ms") ?: 400).coerceIn(50, 3000)
            val times = (args.intOf("times") ?: 1).coerceIn(1, 5)
            val vibrator = ctx.requireContext().getSystemService<Vibrator>()
                ?: return ToolResult.fail("这台设备没有可用的振动器")
            val pattern = LongArray(times * 2) { i -> if (i % 2 == 0) 0L else duration.toLong() }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }.onFailure { return ToolResult.fail("震动失败：${it.message}") }
            return ToolResult.ok("已震动 ${times} 次（每次 ${duration}ms）")
        }

        override fun summarize(args: JsonObject) = "震动 ${args.intOf("times") ?: 1} 次"
    }

    /**
     * 读系统剪贴板。
     *
     * 只读，不写 —— 写入剪贴板会把用户原本复制的东西顶掉，而且「拿到内容」这个诉求
     * 用工作空间文件解决更彻底（见 [WorkspaceTools]）。
     * 但**读**是另一回事：手表上打字非常难受，用户常常先在别处复制好再让 AI 看。
     */
    object GetClipboardTool : AgentTool {
        override val name = "get_clipboard"
        override val description =
            "读取用户当前复制到剪贴板的文本。用户说「我复制了一段代码/链接」时用它获取内容。" +
                "注意：部分系统只在 App 处于前台时允许读取，读不到时不要反复重试。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(emptyMap())

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val cm = ctx.requireContext().getSystemService<ClipboardManager>()
                ?: return ToolResult.fail("这台设备没有剪贴板服务")
            val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx.requireContext())?.toString() }
                .getOrNull()
            if (text.isNullOrBlank()) {
                return ToolResult.fail(
                    "剪贴板是空的，或系统不允许读取（部分 ROM 只在 App 前台时放行）。" +
                        "请用户直接把内容粘贴到输入框发过来。"
                )
            }
            val trimmed = text.take(20_000)
            return ToolResult.ok(
                buildString {
                    append("剪贴板内容（").append(text.length).append(" 字")
                    if (text.length > trimmed.length) append("，已截断")
                    append("）：\n").append(trimmed)
                }
            )
        }

        override fun summarize(args: JsonObject) = "读剪贴板"
    }

    // ------------------------------------------------------------------

    /**
     * 单位换算。
     *
     * 为什么值得单独做一个工具：单位换算正是模型「看起来很懂、其实经常算错」的典型场景，
     * 而它算错的时候语气非常自信。交给确定性代码做，比信任生成结果可靠得多。
     */
    object UnitConvertTool : AgentTool {
        override val name = "unit_convert"
        override val description =
            "在同类单位之间换算。支持：长度(mm/cm/m/km/inch/ft/yard/mile)、" +
                "重量(g/kg/ton/lb/oz)、温度(c/f/k)、存储(B/KB/MB/GB/TB)、" +
                "时间(ms/s/min/h/day)、速度(m/s、km/h、mph、节)。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(
            properties = mapOf(
                "value" to Schema.num("要换算的数值"),
                "from" to Schema.str("源单位，例如 km"),
                "to" to Schema.str("目标单位，例如 mile"),
            ),
            required = listOf("value", "from", "to"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val raw = args.strOf("value") ?: return ToolResult.fail("缺少 value 参数")
            val value = raw.toDoubleOrNull() ?: return ToolResult.fail("value「$raw」不是数字")
            val from = args.strOf("from")?.lowercase() ?: return ToolResult.fail("缺少 from 参数")
            val to = args.strOf("to")?.lowercase() ?: return ToolResult.fail("缺少 to 参数")

            val f = Units.resolve(from) ?: return ToolResult.fail(
                "不认识的单位「$from」。支持：" + Units.names().joinToString(", ")
            )
            val t = Units.resolve(to) ?: return ToolResult.fail(
                "不认识的单位「$to」。支持：" + Units.names().joinToString(", ")
            )
            if (f.dimension != t.dimension) {
                return ToolResult.fail(
                    "「$from」和「$to」不是同一类量（${f.dimension} vs ${t.dimension}），无法换算。"
                )
            }
            val result = Units.fromBase(t, Units.toBase(f, value))
            return ToolResult.ok(
                "$raw $from = ${formatNumber(result)} $to" +
                    if (f.dimension == "温度") {
                        ""
                    } else {
                        "（1 $from = ${formatNumber(Units.fromBase(t, Units.toBase(f, 1.0)))} $to）"
                    }
            )
        }

        override fun summarize(args: JsonObject) =
            "${args.strOf("value")}${args.strOf("from")}→${args.strOf("to")}"

        private fun formatNumber(v: Double): String {
            if (abs(v) < 1e-9) return "0"
            if (v == v.toLong().toDouble() && abs(v) < 1e15) return v.toLong().toString()
            val abs0 = abs(v)
            // 大数用千分位、中数保留 4 位有效、极小数用科学计数法 —— 换算结果可读性优先
            return when {
                abs0 >= 1e15 || abs0 < 1e-4 ->
                    String.format(Locale.US, "%.4e", v)
                abs0 >= 1000 -> String.format(Locale.US, "%,.2f", v).trimEnd('0').trimEnd('.')
                else -> String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
            }
        }
    }

    /** 单位表：每个单位给出「换算到基准单位」的系数；温度是仿射变换，单独处理 */
    private object Units {

        data class Unit(val name: String, val dimension: String, val scale: Double)

        private fun u(name: String, dimension: String, scale: Double) = name to Unit(name, dimension, scale)

        private val table: Map<String, Unit> = buildMap {
            // 长度（基准：米）
            listOf(
                u("mm", "长度", 0.001), u("cm", "长度", 0.01),
                u("m", "长度", 1.0), u("km", "长度", 1000.0),
                u("inch", "长度", 0.0254), u("in", "长度", 0.0254),
                u("ft", "长度", 0.3048), u("foot", "长度", 0.3048),
                u("yard", "长度", 0.9144), u("yd", "长度", 0.9144),
                u("mile", "长度", 1609.344), u("mi", "长度", 1609.344),
                u("里", "长度", 500.0), u("丈", "长度", 3.3333333),
                u("尺", "长度", 0.3333333), u("寸", "长度", 0.03333333),
                // 重量（基准：克）
                u("mg", "重量", 0.001), u("g", "重量", 1.0),
                u("kg", "重量", 1000.0), u("ton", "重量", 1_000_000.0),
                u("lb", "重量", 453.59237), u("oz", "重量", 28.349523),
                u("斤", "重量", 500.0), u("两", "重量", 50.0),
                // 存储（基准：字节，1024 进制）
                u("b", "存储", 1.0), u("kb", "存储", 1024.0),
                u("mb", "存储", 1024.0 * 1024), u("gb", "存储", 1024.0 * 1024 * 1024),
                u("tb", "存储", 1024.0 * 1024 * 1024 * 1024),
                // 时间（基准：秒）
                u("ms", "时间", 0.001), u("s", "时间", 1.0),
                u("min", "时间", 60.0), u("h", "时间", 3600.0),
                u("hour", "时间", 3600.0), u("day", "时间", 86400.0),
                // 速度（基准：米/秒）
                u("m/s", "速度", 1.0), u("km/h", "速度", 1.0 / 3.6),
                u("kmh", "速度", 1.0 / 3.6), u("mph", "速度", 0.44704),
                u("节", "速度", 0.514444), u("knot", "速度", 0.514444),
                // 温度（基准：摄氏度，仿射变换）
                u("c", "温度", 1.0), u("°c", "温度", 1.0),
                u("f", "温度", 1.0), u("°f", "温度", 1.0),
                u("k", "温度", 1.0),
            ).forEach { (k, v) -> put(k, v) }
        }

        fun resolve(name: String): Unit? = table[name.trim().lowercase()]

        fun names(): List<String> = table.keys.sorted()

        /** 转成基准单位 */
        fun toBase(u: Unit, v: Double): Double = when {
            u.dimension != "温度" -> v * u.scale
            // 温度是仿射变换，没有「倍率」可言，只能按单位名分别处理
            u.name == "f" || u.name == "°f" -> (v - 32.0) * 5.0 / 9.0
            u.name == "k" -> v - 273.15
            else -> v
        }

        /** 从基准单位转出 */
        fun fromBase(u: Unit, v: Double): Double = when {
            u.dimension != "温度" -> v / u.scale
            u.name == "f" || u.name == "°f" -> v * 9.0 / 5.0 + 32.0
            u.name == "k" -> v + 273.15
            else -> v
        }
    }

    // ------------------------------------------------------------------

    /**
     * 网页搜索。
     *
     * 走 DuckDuckGo 的 lite 端点（无需 API Key、无 JS）。**它依赖外网可达**，
     * 在部分网络环境下会失败 —— 失败时明确告诉模型「改用 http_fetch 直接抓已知网址」，
     * 而不是让它反复重试。
     */
    object WebSearchTool : AgentTool {
        override val name = "web_search"
        override val description =
            "联网搜索关键词，返回标题 + 链接 + 摘要的列表。用于获取你不知道或可能已过时的信息。" +
                "需要精确抓取某个已知网页的内容时，直接用 http_fetch 更可靠。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "query" to Schema.str("搜索关键词"),
                "max_results" to Schema.int("最多返回多少条，默认 6", 1, 10),
            ),
            required = listOf("query"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val query = args.strOf("query") ?: return ToolResult.fail("缺少 query 参数")
            val limit = (args.intOf("max_results") ?: 6).coerceIn(1, 10)
            val url = "https://lite.duckduckgo.com/lite/?q=" + java.net.URLEncoder.encode(query, "UTF-8")

            // 用「异常 → 局部变量」而不是让 withContext 同时返回 String 和 ToolResult：
            // 后者的公共父类型是 Any，拿回来还得强转，既啰嗦又容易漏判。
            var failure: ToolResult? = null
            val html = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                        )
                        .header("Accept", "text/html")
                        .get()
                        .build()
                    Http.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            failure = ToolResult.fail("搜索失败：HTTP ${resp.code}（可能被网络环境拦截）")
                            ""
                        } else {
                            resp.body?.string().orEmpty()
                        }
                    }
                }.getOrElse {
                    failure = ToolResult.fail(
                        "搜索失败：${it.javaClass.simpleName} ${it.message.orEmpty().take(100)}。" +
                            "这台设备可能访问不了该搜索服务，请改用 http_fetch 直接抓已知网址，" +
                            "或基于已有知识回答并说明不确定性。"
                    )
                    ""
                }
            }
            failure?.let { return it }

            val hits = parse(html, limit)
            if (hits.isEmpty()) {
                return ToolResult.ok("没有搜到「$query」的结果（页面结构可能变了，或该关键词无结果）。")
            }
            return ToolResult.ok(
                buildString {
                    append("搜索「").append(query).append("」的结果：\n")
                    hits.forEachIndexed { i, h ->
                        append(i + 1).append(". ").append(h.title).append('\n')
                        append("   ").append(h.url).append('\n')
                        if (h.snippet.isNotBlank()) append("   ").append(h.snippet).append('\n')
                    }
                    append("（如需完整内容，用 http_fetch 抓上面某个链接）")
                }
            )
        }

        override fun summarize(args: JsonObject) = "搜 ${args.strOf("query")}"

        private data class Hit(val title: String, val url: String, val snippet: String)

        private val ANCHOR = Regex("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAGS = Regex("<[^>]+>")

        private fun parse(html: String, limit: Int): List<Hit> {
            val out = ArrayList<Hit>()
            val seen = HashSet<String>()
            ANCHOR.findAll(html).forEach { m ->
                if (out.size >= limit) return@forEach
                var href = m.groupValues[1]
                val title = unescape(TAGS.replace(m.groupValues[2], "")).trim()

                // lite 端点会包一层跳转：//duckduckgo.com/l/?uddg=<编码后的真实地址>
                if (href.contains("uddg=")) {
                    href = runCatching {
                        val enc = href.substringAfter("uddg=").substringBefore('&')
                        java.net.URLDecoder.decode(enc, "UTF-8")
                    }.getOrDefault(href)
                } else if (href.startsWith("//")) {
                    href = "https:$href"
                }
                if (!href.startsWith("http")) return@forEach
                if (href.contains("duckduckgo.com")) return@forEach
                if (title.isBlank() || title.length < 3) return@forEach
                if (!seen.add(href)) return@forEach
                out += Hit(title, href, "")
            }
            return out
        }

        private fun unescape(s: String): String = s
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    }
}
