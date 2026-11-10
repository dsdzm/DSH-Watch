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
                /*
                 * 顺序很重要：先把操控会话开起来（后台保活服务随之上线），
                 * 再把自己收进后台。反过来的话，就是「App 已在后台 → 才去启动
                 * 前台服务」，Android 12+ 会直接拒绝 —— 而这正是操作期间
                 * 进程最容易被杀的那段窗口。
                 */
                DeviceControlHub.begin("读取界面 · 当前应用")
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
            "对 device_screen 的编号执行动作，返回动作后的新屏幕。" +
                "action=tap 点击 / long 长按（都用 id；读不到控件时可给 x、y 按坐标点）/" +
                "type 输入（要 text，submit=true 顺带提交）/" +
                "scroll 翻页（direction 默认 down，可 up/right/left；id 指向列表更准）/" +
                "key 系统键（back/home/recents/notifications）。" +
                "一次只做一件事，每步看返回的新屏幕再决定。" +
                "翻页用 scroll，不要连点或反复按 back。" +
                "密码、验证码、支付确认不代填不代点 —— 让用户来（device_handoff）。"
        override val safety = ToolSafety.WRITE
        override val enabled: (com.dshwatch.domain.model.AppSettings) -> Boolean = { it.deviceControlEnabled }
        override val parameters = Schema.obj(
            properties = mapOf(
                "action" to Schema.str(
                    "动作类型",
                    listOf("tap", "long", "type", "scroll", "key"),
                ),
                "id" to Schema.int("device_screen 里的编号（tap/long/type/scroll）"),
                "x" to Schema.int("无 id 时按坐标点：x"),
                "y" to Schema.int("y"),
                "text" to Schema.str("type 时要输入的文字"),
                "submit" to Schema.bool("type 时输入完是否提交 / 发送"),
                "direction" to Schema.str("scroll 的方向", listOf("down", "up", "right", "left")),
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

            /*
             * 判断「界面变没变」要有可信的基准：拿 30 秒前的快照比，任何界面都算「变了」，
             * 那句「没有变化」的提示就永远不会出现。快照不新鲜时先读一次。
             */
            val before = DeviceControlHub.lastScreen
                ?.takeUnless { it.stale() }
                ?.text
                ?: svc.capture(detail = false).text
            val outcome = when (action) {
                "tap", "long" -> {
                    val long = action == "long"
                    val verb = if (long) "长按" else "点击"
                    val id = args.intOf("id")
                    if (id != null) {
                        DeviceControlHub.note("$verb · 第 $id 项")
                        svc.tap(id, long)
                    } else {
                        val x = args.intOf("x")
                        val y = args.intOf("y")
                        if (x == null || y == null) {
                            return ToolResult.fail(
                                "$action 需要 id；读不到控件时改给 x、y 坐标" +
                                    "（先 device_screen detail=true 看坐标）。",
                            )
                        }
                        DeviceControlHub.note("$verb · ($x, $y)")
                        svc.tapAt(x, y, long)
                    }
                }

                "type" -> {
                    val id = args.intOf("id") ?: return ToolResult.fail("type 需要 id")
                    val text = args.strOf("text") ?: return ToolResult.fail("type 需要 text")
                    DeviceControlHub.note("输入 · ${text.take(20)}")
                    svc.type(id, text, args.boolOf("submit"))
                }

                "scroll" -> {
                    val dir = args.strOf("direction") ?: "down"
                    val word = when (dir) {
                        "up" -> "向前"
                        "right" -> "向右"
                        "left" -> "向左"
                        else -> "向后"
                    }
                    DeviceControlHub.note("翻页 · $word")
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
            if (DeviceControlHub.isStopped()) {
                return ToolResult.fail("已执行，但用户刚点了停止。不要再继续操作。")
            }

            /*
             * 等界面「变了」，而不是干等一个固定毫秒数。
             *
             * 固定 260ms 是个赌：微信用 500ms 才画出首屏，那时读到的还是旧界面，
             * 模型据此判断「点了没反应」，于是重复点。滑动更慢 —— 280ms 的手势
             * 结束后列表还在惯性滚动，要等更久才能读到稳定位置。
             */
            val after = svc.settle(
                before = before,
                timeoutMs = if (action == "scroll") 1100 else 900,
                // 滑动要等惯性停下来，否则读到的是滑到一半的位置
                requireStable = action == "scroll",
            )
            if (DeviceControlHub.isStopped()) {
                return ToolResult.fail("已执行，但用户刚点了停止。不要再继续操作。")
            }
            DeviceControlHub.publish(
                DeviceControlHub.Snapshot(
                    packageName = after.packageName,
                    appLabel = after.appLabel,
                    elements = after.elements,
                    text = after.text,
                ),
            )
            // message 本身已经是「已点击「发送」」，不要再拼一个「已」
            DeviceControlHub.note(outcome.message, after.appLabel)
            /*
             * 界面没变时**不重发整屏**。
             *
             * 这是高频路径：点了一个不响应的按钮、输入完没有提交、弹了个 Toast
             * 就消失 —— 每出现一次都是一整屏控件白白进上下文，而模型手上
             * 已经有上一屏，再发一遍同样的内容只是把它自己搞糊涂。
             * 只给结论 + 项数，需要细节它会自己重读。
             */
            if (after.text == before) {
                return ToolResult.ok(
                    "${outcome.message}\n\n（界面没有变化，仍是上一屏的 ${after.elements.size} 项。" +
                        "要么动作没生效，要么效果不在读屏里（比如只弹了个提示）。" +
                        "别重复同一个动作：换个做法，或先 device_screen 重读。）",
                )
            }
            return ToolResult.ok("${outcome.message}\n\n${after.text}")
        }

        override fun summarize(args: JsonObject): String {
            val action = args.strOf("action").orEmpty()
            val id = args.intOf("id")
            if (id != null) return "$action #$id"
            val x = args.intOf("x")
            val y = args.intOf("y")
            return if (x != null && y != null) "$action ($x, $y)" else action
        }
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
            val context = ctx.requireContext()
            val app = args.strOf("app")
            val url = args.strOf("url")
            val query = args.strOf("query")

            /*
             * 查询分支必须走在这道闸**前面**。
             *
             * 它不碰屏幕、不需要无障碍，而且它本身就是「无障碍没开时」最该能用的那条路：
             * 用户问「我装了哪些应用」，模型先被闸拦下，只能回答「请先开权限」——
             * 而正确的做法恰恰是先列出应用，再顺带告诉用户哪些能开。
             */
            if (query != null && app == null && url == null) {
                return ToolResult.ok(listApps(context, query))
            }
            gate(ctx)?.let { return ToolResult.fail(it) }

            val intent = when {
                url != null -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                app != null -> resolveApp(context, app)
                    ?: return ToolResult.fail(
                        "没找到「$app」。可以先用 device_open query=关键词 看看装了哪些应用。",
                    )

                else -> return ToolResult.fail("需要 app 或 url 之一")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // 先退到后台：用户得看见「屏幕上发生了什么」，而不是盯着 DSH 的对话页。
            // 但保活要先就位 —— 见 ScreenTool 里同一处的说明。
            DeviceControlHub.begin("打开 · ${app ?: url}")
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
