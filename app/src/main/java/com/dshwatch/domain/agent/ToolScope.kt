package com.dshwatch.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 本次会话里「按需加载过」的工具集合。
 *
 * ## 为什么要它
 *
 * 51 个工具的描述 + schema 是 18123 字，而它是**每一轮**都随请求重发的固定成本。
 * 一轮对话二十步，就是二十遍。可模型在大多数任务里只用得到其中五六个 ——
 * 剩下的四十多个 schema 是纯粹的上下文税。
 *
 * 解决办法是渐进式披露：常驻一小撮高频工具，其余只给一份**目录**
 * （分类 + 能做什么，不给 schema），模型真要用时先 `tool_search`，
 * 取到的工具从**下一轮**起进 tools 数组，带完整参数。
 *
 * ## 为什么下一轮才生效
 *
 * tools 数组是随请求发出去的，请求发出后无法追加。所以 `tool_search` 只能
 * 把名字记在这里，由引擎在下一轮拼 specs 时读 [snapshot]。这也是为什么
 * `tool_search` 的返回文案必须明确说「下一轮可直接调用」—— 否则模型会
 * 在同一轮里就调那个还没下发的工具。
 */
class ToolScope(private val all: () -> List<AgentTool>) {

    private val loaded = LinkedHashSet<String>()
    private val lock = Any()

    /** 当前已加载的工具名。引擎每轮拼 tools 数组时读一次。 */
    fun snapshot(): Set<String> = synchronized(lock) { loaded.toSet() }

    /** 记下一批工具名，返回**本次新增**的那些（已经加载过的不重复报）。 */
    fun load(names: Collection<String>): List<String> = synchronized(lock) {
        names.filter { loaded.add(it) }
    }

    fun search(query: String): List<AgentTool> {
        val hits = ToolCatalog.search(all(), query)
        // 已经加载过的排到后面：否则反复搜同一组，每次都在前 N 个里打转，取不到新工具
        val snap = snapshot()
        return hits.sortedBy { if (it.name in snap) 1 else 0 }
    }
}

/**
 * 工具的分类目录与检索。
 *
 * 分组是按「用户会怎么说」定的，不是按代码目录：说「帮我订个闹钟」的人
 * 不会想到那属于 `SystemTools`。所以组的名字直接用作检索词。
 */
object ToolCatalog {

    /**
     * 常驻工具：不进目录、任何时候都带完整参数。
     *
     * 挑选标准是「没有它模型会立刻卡住」而不是「常用」——
     * 编程任务里读、写、改、搜、跑是循环本身，少一个都要多一轮。
     */
    val CORE: Set<String> = linkedSetOf(
        // 入口本身必须常驻：模型看不见它，就永远取不到任何长尾工具
        "tool_search",
        "read_file", "write_file", "edit_file", "append_file", "list_files",
        "grep", "glob", "run_script",
        "ask_user", "todo_write",
        "calculate", "get_datetime",
    )

    const val GROUP_FILE = "文件"
    const val GROUP_BUILD = "构建"
    const val GROUP_NET = "网络"
    const val GROUP_REMIND = "提醒"
    const val GROUP_BILI = "B站"
    const val GROUP_DEVICE = "设备"
    const val GROUP_OTHER = "其他"

    private val FILE_TOOLS = setOf(
        "move_file", "make_dir", "delete_file", "open_file", "open_screen", "get_clipboard",
    )
    private val BUILD_TOOLS = setOf("build_project", "cloud_build", "cloud_job")
    private val NET_TOOLS = setOf("http_fetch", "web_search")
    private val REMIND_TOOLS = setOf("set_alarm", "list_alarms", "cancel_alarm", "create_event", "vibrate")
    private val OTHER_TOOLS =
        setOf("unit_convert", "get_device_info", "get_token_usage", "adb_shell")

    /** 每组「能做什么」。目录里给这一句，比列 16 个 `bili_*` 名字便宜得多。 */
    private val BLURB = mapOf(
        GROUP_FILE to "移动 / 新建目录 / 删除 / 打开文件或页面 / 读剪贴板",
        GROUP_BUILD to "把工作空间打包成可预览页面、云端编译 APK 并查看构建进度",
        GROUP_NET to "抓取网页正文、网页搜索",
        GROUP_REMIND to "定闹钟 / 列闹钟 / 取消闹钟 / 建日程 / 震动",
        GROUP_BILI to "视频详情、搜索、字幕、评论、私信、收藏、动态、点赞、投币、数据报表",
        GROUP_DEVICE to "读屏、点击输入翻页、打开应用、交还用户（需在设置里开启）",
        GROUP_OTHER to "单位换算、设备信息、用量统计",
    )

    /** 检索别名：用户/模型嘴里的词 → 组名。 */
    private val ALIAS = mapOf(
        "file" to GROUP_FILE, "files" to GROUP_FILE, "folder" to GROUP_FILE,
        "目录" to GROUP_FILE, "文件" to GROUP_FILE, "剪贴板" to GROUP_FILE,
        "build" to GROUP_BUILD, "apk" to GROUP_BUILD, "编译" to GROUP_BUILD,
        "构建" to GROUP_BUILD, "预览" to GROUP_BUILD, "cloud" to GROUP_BUILD,
        "web" to GROUP_NET, "http" to GROUP_NET, "网络" to GROUP_NET,
        "搜索" to GROUP_NET, "网页" to GROUP_NET, "fetch" to GROUP_NET,
        "alarm" to GROUP_REMIND, "闹钟" to GROUP_REMIND, "日程" to GROUP_REMIND,
        "提醒" to GROUP_REMIND, "震动" to GROUP_REMIND, "calendar" to GROUP_REMIND,
        "bili" to GROUP_BILI, "b站" to GROUP_BILI, "视频" to GROUP_BILI,
        "弹幕" to GROUP_BILI, "字幕" to GROUP_BILI, "动态" to GROUP_BILI,
        "device" to GROUP_DEVICE, "设备" to GROUP_DEVICE, "操控" to GROUP_DEVICE,
        "点击" to GROUP_DEVICE, "无障碍" to GROUP_DEVICE, "屏幕" to GROUP_DEVICE,
        "换算" to GROUP_OTHER, "单位" to GROUP_OTHER, "用量" to GROUP_OTHER,
    )

    /**
     * 精确词 → 具体工具。
     *
     * 有些词既是分类词、又指向组里某一个工具（「字幕」属于 B 站，但只有
     * `bili_subtitle` 干这个）。一次只取前六个，排错了就等于没搜到 ——
     * 所以这里直接给最可能的那个点个名，剩下的按组顺序跟在后面。
     */
    private val TOOL_HINTS = mapOf(
        "字幕" to "bili_subtitle",
        "评论" to "bili_comments",
        "私信" to "bili_dm",
        "收藏" to "bili_fav",
        "动态" to "bili_dyn",
        "点赞" to "bili_like",
        "投币" to "bili_coin",
        "报表" to "bili_report",
        "视频" to "bili_video",
        "闹钟" to "set_alarm",
        "日程" to "create_event",
        "震动" to "vibrate",
        "剪贴板" to "get_clipboard",
        "编译" to "cloud_build",
        "预览" to "build_project",
        "换算" to "unit_convert",
    )

    /**
     * 返回 null 表示「这不在目录体系里」。
     *
     * 未知工具一律**照常下发**，而不是归进「其他」隐藏起来：插件、MCP、
     * 单测里注入的工具名不可预知，把它们默默藏掉会变成「工具凭空消失」
     * 这种最难查的 bug。宁可多花一点 token。
     */
    fun groupOf(name: String): String? = when {
        name.startsWith("bili_") -> GROUP_BILI
        name.startsWith("device_") -> GROUP_DEVICE
        name in FILE_TOOLS -> GROUP_FILE
        name in BUILD_TOOLS -> GROUP_BUILD
        name in NET_TOOLS -> GROUP_NET
        name in REMIND_TOOLS -> GROUP_REMIND
        name in OTHER_TOOLS -> GROUP_OTHER
        else -> null
    }

    /** 本轮真正下发的工具：常驻 + 已加载。顺序沿用注册表，便于缓存命中。 */
    fun visible(tools: List<AgentTool>, loaded: Set<String>): List<AgentTool> =
        tools.filter { it.name in CORE || it.name in loaded || groupOf(it.name) == null }

    /**
     * 目录段。
     *
     * 只列**没下发**的那些：已经在 tools 数组里的工具再列一遍是重复收费。
     * 每行给「组名（数量）：能做什么」，不给工具名 —— 名字对构造参数没有帮助，
     * 而模型要的正是「这组里有没有我要的能力」。
     */
    fun catalogPrompt(tools: List<AgentTool>, loaded: Set<String>): String {
        val hidden = tools.filter {
            it.name !in CORE && it.name !in loaded && groupOf(it.name) != null
        }
        if (hidden.isEmpty()) return ""
        val byGroup = hidden.groupBy { groupOf(it.name).orEmpty() }
        return buildString {
            append("\n\n【工具目录】这些工具没有带参数，用之前先 tool_search（query 给分类或关键词），")
            append("取到的工具**下一轮**就能直接调用。")
            byGroup.forEach { (group, list) ->
                val blurb = BLURB[group].orEmpty()
                append("\n· ").append(group).append('（').append(list.size).append("）：").append(blurb)
                // 「其他」组没有公共主题，把名字列出来，否则模型无从猜起
                if (group == GROUP_OTHER) {
                    append(" —— ").append(list.joinToString(" ") { it.name })
                }
            }
        }
    }

    /**
     * 检索。
     *
     * 先按组匹配（分类与别名），没命中再按工具名/描述做子串匹配。
     * 空结果不返回空列表而是返回整组兜底 —— 让模型拿到「没有这个能力」的
     * 明确回执，比让它反复换关键词重搜省得多。
     */
    fun search(tools: List<AgentTool>, query: String): List<AgentTool> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // 精确词先点名，再补同类：保证「字幕」第一发就是 bili_subtitle
        TOOL_HINTS[q]?.let { hint ->
            val target = tools.firstOrNull { it.name == hint }
            if (target != null) {
                val siblings = tools.filter {
                    groupOf(it.name) == groupOf(hint) && it.name != hint
                }
                return listOf(target) + siblings
            }
        }

        val group = ALIAS[q] ?: GROUP_NAMES.firstOrNull { it.lowercase() == q }
            ?: GROUP_NAMES.firstOrNull { it.contains(q) || q.contains(it.lowercase()) }
        if (group != null) {
            // 组内按相关性排：搜「字幕」时 bili_subtitle 要在最前面 ——
            // 一次只取前 6 个，排错了就等于没搜到
            return tools.filter { groupOf(it.name) == group }
                .sortedByDescending {
                    if (it.name.lowercase().contains(q) || it.description.contains(query.trim())) 1 else 0
                }
        }
        return tools.filter {
            it.name.lowercase().contains(q) || it.description.contains(query.trim())
        }.take(10)
    }

    private val GROUP_NAMES = listOf(
        GROUP_FILE, GROUP_BUILD, GROUP_NET, GROUP_REMIND, GROUP_BILI, GROUP_DEVICE, GROUP_OTHER,
    )

    /**
     * 一行参数速览：`bili_video(bvid, quality*)`。
     *
     * 只用来告诉模型「这个工具要哪些参数」，真正的 schema 下一轮随 tools 数组下发。
     * 带 `*` 的是必填。
     */
    fun brief(tool: AgentTool): String {
        val props = tool.parameters["properties"] as? JsonObject ?: return tool.name
        val required = (tool.parameters["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        val keys = props.keys.map { if (it in required) "$it*" else it }
        return if (keys.isEmpty()) tool.name else "${tool.name}(${keys.joinToString(", ")})"
    }
}
