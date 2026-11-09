package com.dshwatch.domain.agent.tools

import android.content.Intent
import android.content.pm.PackageManager
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.device.DeviceControlHub
import com.dshwatch.service.DeviceA11yService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * 设备操控工具：让模型**看见并操作手表上的其他 App**。
 *
 * ## 为什么是四个工具而不是十三个
 *
 * 手表上工具表每轮都要进上下文（3–4k token），每多一个工具都在抬高成本。
 * dsh-Go 在手机上分了 13 个，是因为它把「读屏 / 收集列表 / 截图点按」都拆开了；
 * 手表屏幕一屏只有几个元素，没有「长列表收集」与「按坐标点」的必要，
 * 所以这里收成四个：看（screen）、动（act）、开（open）、交还（handoff）。
 *
 * ## 闭环
 *
 * 每次 [DeviceActTool] 都返回**动作之后的新屏幕**。模型不需要（也没机会）
 * 凭想象推断点了会怎样 —— 它每一步都拿到事实。
 */
object DeviceControlTools {

    fun all(): List<AgentTool> = listOf(ScreenTool, ActTool, OpenTool, HandoffTool)

    /** 四个工具共用的前置检查。返回非 null 就是不能做，直接当失败结果返回。 */
    private fun gate(ctx: ToolContext): String? {
        if (!ctx.settings().deviceControlEnabled) {
            return "设备操控没有开启。请让用户到「设置 → AI 能力 → 设备操控」打开开关，" +
                "并在系统设置里授予无障碍权限。"
        }
        if (!DeviceA11yService.isConnected()) {
            val enabled = DeviceA11yService.isEnabled(ctx.requireContext())
            return if (enabled) {
                "无障碍服务已开启但还没连上（系统可能正在重启它）。稍等几秒再试。"
            } else {
                "无障碍服务未开启。请让用户在系统设置的「无障碍」里打开 DSH 肥鱼，" +
                    "或从「设置 → AI 能力 → 设备操控」点「去开启」。"
            }
        }
        return DeviceControlHub.abortIfStopped()
    }

    private val noService = "无障碍服务不可用"

    @Suppress("UNCHECKED_CAST")
    private fun service(): DeviceA11yService? = DeviceControlHub.service<DeviceA11yService>()

    // ------------------------------------------------------------------

    object ScreenTool : AgentTool {
        override val name = "device_screen"
        override val description =
            "读取当前屏幕上的控件，返回带编号的列表（例如 [1] 按钮 返回 / [2] 输入框 \"搜索\"）。" +
                "编号只对这一次结果有效，做了动作之后要重新读。" +
                "要操作手表上别的 App，先调它看清楚有什么。detail=true 时附带坐标。"
        override val safety = ToolSafety.READ_ONLY
        override val enabled: (com.dshwatch.domain.model.AppSettings) -> Boolean = { it.deviceControlEnabled }
        override val parameters = Schema.obj(
            properties = mapOf(
                "detail" to Schema.bool("附带每个元素的像素坐标（一般不需要）"),
            ),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            gate(ctx)?.let { return ToolResult.fail(it) }
            val svc = service() ?: return ToolResult.fail(noService)
            val detail = args.boolOf("detail")

            var capture = svc.capture(detail)
            /*
             * 读到自己说明用户此刻正停在对话页上 —— 那不是他要 AI 操作的东西。
             * 把 App 收进后台（露出桌面/上一个应用）再读一次，比让模型
             * 对着自己的对话框分析「哪里能点发送」有用得多。
             *
             * 反过来，如果用户已经在别的 App 上，就不要动画面：
             * 他可能就是想问「我现在这屏是什么」。
             */
            if (capture.packageName == ctx.requireContext().packageName) {
                ctx.uiActions?.minimize?.invoke()
                delay(420)
                capture = svc.capture(detail)
            }

            DeviceControlHub.publish(
                DeviceControlHub.Snapshot(
                    packageName = capture.packageName,
                    appLabel = capture.appLabel,
                    elements = capture.elements,
                    text = capture.text,
                ),
            )
            DeviceControlHub.begin("读取界面 · ${capture.appLabel ?: "当前应用"}")
            return ToolResult.ok(capture.text)
        }

        override fun summarize(args: JsonObject) = "读取当前屏幕"
    }

    // ------------------------------------------------------------------

    object ActTool : AgentTool {
        override val name = "device_act"
        override val description =
            "对 device_screen 给出的编号执行一个动作，并返回动作之后的新屏幕。\n" +
                "action=tap 点击（long=true 长按）；type 在输入框输入（要 text，submit=true 顺带提交）；" +
                "scroll 滚动（要 direction=up/down）；key 按系统键（key=back/home/recents/notifications）。\n" +
                "一次只做一件事。屏幕会变，所以每一步之后都要看返回的新屏幕再决定下一步。\n" +
                "密码、验证码、支付确认框不代填也不代点——遇到就让用户来（device_handoff）。"
        override val safety = ToolSafety.WRITE
        override val enabled: (com.dshwatch.domain.model.AppSettings) -> Boolean = { it.deviceControlEnabled }
        override val parameters = Schema.obj(
            properties = mapOf(
                "action" to Schema.str(
                    "动作类型",
                    listOf("tap", "long", "type", "scroll", "key"),
                ),
                "id" to Schema.int("device_screen 结果里的元素编号（tap / long / type / scroll 用）"),
                "text" to Schema.str("type 时要输入的文字"),
                "submit" to Schema.bool("type 时输入完是否提交 / 发送"),
                "direction" to Schema.str("scroll 的方向", listOf("up", "down")),
                "key" to Schema.str(
                    "key 动作的按键",
                    listOf("back", "home", "recents", "notifications"),
                ),
            ),
            required = listOf("action"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            gate(ctx)?.let { return ToolResult.fail(it) }
            val svc = service() ?: return ToolResult.fail(noService)
            val action = args.strOf("action") ?: return ToolResult.fail("缺少 action")

            val outcome = when (action) {
                "tap" -> {
                    val id = args.intOf("id")
                        ?: return ToolResult.fail("tap 需要 id（先 device_screen 拿编号）")
                    DeviceControlHub.note("点击 · 第 $id 项")
                    svc.tap(id, long = false)
                }

                "long" -> {
                    val id = args.intOf("id") ?: return ToolResult.fail("long 需要 id")
                    DeviceControlHub.note("长按 · 第 $id 项")
                    svc.tap(id, long = true)
                }

                "type" -> {
                    val id = args.intOf("id") ?: return ToolResult.fail("type 需要 id")
                    val text = args.strOf("text") ?: return ToolResult.fail("type 需要 text")
                    DeviceControlHub.note("输入 · ${text.take(20)}")
                    svc.type(id, text, args.boolOf("submit"))
                }

                "scroll" -> {
                    val dir = args.strOf("direction") ?: "down"
                    DeviceControlHub.note("滚动 · ${if (dir == "down") "向下" else "向上"}")
                    svc.scroll(args.intOf("id"), dir)
                }

                "key" -> {
                    val key = args.strOf("key") ?: "back"
                    DeviceControlHub.note("按键 · $key")
                    svc.global(key)
                }

                else -> return ToolResult.fail("不认识的 action：$action")
            }

            if (!outcome.ok) return ToolResult.fail(outcome.message)

            // 界面切换需要时间；等一小会儿再读，比让模型「立刻重读然后读到旧界面」强
            delay(260)
            if (DeviceControlHub.isStopped()) {
                return ToolResult.fail("已执行，但用户刚点了停止。不要再继续操作。")
            }
            val after = svc.capture(detail = false)
            DeviceControlHub.publish(
                DeviceControlHub.Snapshot(
                    packageName = after.packageName,
                    appLabel = after.appLabel,
                    elements = after.elements,
                    text = after.text,
                ),
            )
            DeviceControlHub.note("已${outcome.message}", after.appLabel)
            return ToolResult.ok("${outcome.message}\n\n${after.text}")
        }

        override fun summarize(args: JsonObject) =
            args.strOf("action").orEmpty() + args.intOf("id")?.let { " #$it" }.orEmpty()
    }

    // ------------------------------------------------------------------

    object OpenTool : AgentTool {
        override val name = "device_open"
        override val description =
            "打开手表上的一个 App 或网页。会先把 DSH 退到后台，让用户看着目标应用。" +
                "只给 query 时，列出名字或包名匹配的应用而不打开。" +
                "打开不等于任务完成——之后要 device_screen 看它到底停在哪一屏。"
        override val safety = ToolSafety.WRITE
        override val enabled: (com.dshwatch.domain.model.AppSettings) -> Boolean = { it.deviceControlEnabled }
        override val parameters = Schema.obj(
            properties = mapOf(
                "app" to Schema.str("应用名或包名，例如 微信 / com.tencent.mm"),
                "url" to Schema.str("网址或深链（与 app 二选一）"),
                "query" to Schema.str("只列出匹配的应用，不打开"),
            ),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            gate(ctx)?.let { return ToolResult.fail(it) }
            val context = ctx.requireContext()
            val app = args.strOf("app")
            val url = args.strOf("url")
            val query = args.strOf("query")

            if (query != null && app == null && url == null) {
                return ToolResult.ok(listApps(context, query))
            }

            val intent = when {
                url != null -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                app != null -> resolveApp(context, app)
                    ?: return ToolResult.fail(
                        "没找到「$app」。可以先用 device_open query=关键词 看看装了哪些应用。",
                    )

                else -> return ToolResult.fail("需要 app 或 url 之一")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // 先退到后台：用户得看见「屏幕上发生了什么」，而不是盯着 DSH 的对话页
            ctx.uiActions?.minimize?.invoke()
            delay(420)

            return try {
                context.startActivity(intent)
                val svc = service()
                delay(900)
                val capture = svc?.capture(detail = false)
                if (capture != null) {
                    DeviceControlHub.publish(
                        DeviceControlHub.Snapshot(
                            packageName = capture.packageName,
                            appLabel = capture.appLabel,
                            elements = capture.elements,
                            text = capture.text,
                        ),
                    )
                    DeviceControlHub.begin("打开 · ${capture.appLabel ?: app ?: url}")
                    ToolResult.ok("已打开。当前界面：\n\n${capture.text}")
                } else {
                    DeviceControlHub.begin("打开 · ${app ?: url}")
                    ToolResult.ok("已请求打开，但读不到当前界面（无障碍可能刚被系统断开）。")
                }
            } catch (e: Exception) {
                ToolResult.fail(
                    "打开失败：${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}。" +
                        "这台设备可能没装能处理它的应用。",
                )
            }
        }

        override fun summarize(args: JsonObject) =
            args.strOf("app") ?: args.strOf("url") ?: ("查：" + args.strOf("query").orEmpty())

        private fun resolveApp(context: android.content.Context, name: String): Intent? {
            val pm = context.packageManager
            // 直接给包名
            if (name.contains('.')) {
                pm.getLaunchIntentForPackage(name)?.let { return it }
            }
            val launchable = launchableApps(context)
            val hit = launchable.firstOrNull {
                it.label.equals(name, ignoreCase = true) || it.packageName.equals(name, ignoreCase = true)
            } ?: launchable.firstOrNull {
                it.label.contains(name, ignoreCase = true) || it.packageName.contains(name, ignoreCase = true)
            } ?: return null
            return pm.getLaunchIntentForPackage(hit.packageName)
        }

        private fun listApps(context: android.content.Context, query: String): String {
            val all = launchableApps(context)
            val hits = if (query.isBlank()) all else all.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            if (hits.isEmpty()) return "没有名字匹配「$query」的应用。"
            return buildString {
                append("匹配「").append(query).append("」的应用（").append(hits.size).append(" 个）：")
                hits.take(20).forEach { append("\n· ").append(it.label).append("（").append(it.packageName).append("）") }
                if (hits.size > 20) append("\n…还有 ").append(hits.size - 20).append(" 个")
            }
        }

        private data class AppEntry(val label: String, val packageName: String)

        private fun launchableApps(context: android.content.Context): List<AppEntry> {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }.getOrDefault(emptyList())
            return resolved.mapNotNull { info ->
                val ai = info.activityInfo ?: return@mapNotNull null
                val pkg = ai.applicationInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull() ?: pkg
                AppEntry(label, pkg)
            }.distinctBy { it.packageName }.sortedBy { it.label }
        }
    }

    // ------------------------------------------------------------------

    object HandoffTool : AgentTool {
        override val name = "device_handoff"
        override val description =
            "把设备交还给用户：登录、短信验证码、支付确认、或者你连续两次没把握的时候用它。" +
                "调用后悬浮条会收起，用户操作完你再继续。"
        override val safety = ToolSafety.READ_ONLY
        override val enabled: (com.dshwatch.domain.model.AppSettings) -> Boolean = { it.deviceControlEnabled }
        override val parameters = Schema.obj(
            properties = mapOf("reason" to Schema.str("请用户做什么，一句话")),
            required = listOf("reason"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val reason = args.strOf("reason") ?: "需要你接手一下"
            // 收起悬浮条：这时候屏幕上应该是用户自己在操作，一条「AI 正在操作」的条
            // 只会让人以为还在被控制
            DeviceControlHub.end()
            return ToolResult.ok(
                "已把设备交给用户：$reason\n" +
                    "已经在等用户操作。用户说继续之前，不要再调用 device_act。",
            )
        }

        override fun summarize(args: JsonObject) = args.strOf("reason").orEmpty()
    }
}
