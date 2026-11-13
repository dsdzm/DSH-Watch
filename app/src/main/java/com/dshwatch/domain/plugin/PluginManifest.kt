package com.dshwatch.domain.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 声明式插件清单。
 *
 * ## 为什么不是「手表上跑 Node」
 *
 * DSH 官方插件是 Cordis 的 npm 包，`apply(ctx)` 里能拿到 `ctx.fs`、`ctx.tools`、
 * 子进程。手表没有 Node 运行时，也没有能让 Node 22+ 跑起来的 ABI ——
 * 硬塞一个 libnode 会把 APK 从 8MB 推到 200MB，还得逐个补 sqlite / sandbox /
 * ripgrep 的 Android 移植。这条路在本项目里不成立。
 *
 * 所以这里做的是**能力降级**：插件从「一段能执行任意代码的程序」退化成
 * 「一份可读、可审计的声明」。
 *
 * ## 一个插件能改什么
 *
 * | 段 | 改写什么 | 对应 DSH 生态里的哪类插件 |
 * | --- | --- | --- |
 * | [prompt] | 模型看到的话（身份、尾部锚点、按模型切规则） | 人格 / 破甲 / 措辞类 |
 * | [tools] | 模型能碰到的工具面与确认强度 | dsh-purge 那类「任务不再被打断」的 |
 *
 * ## 一条不能越的线：只能收紧
 *
 * 权限方向是单向的。清单可以让某个工具**从可用变成不可用**、
 * 从「自动执行变成每次确认」，但没有任何字段能让它反向：
 *
 * · 没有 `autoApprove` / `allow` / `approval` 段；
 * · [ToolsPart.confirm] 只能加严，`ALWAYS_CONFIRM` 里的工具（`cloud_build`、
 *   `cloud_job`）和 `run_script:shell` 永远不会因为一份 JSON 变成免确认。
 *
 * 这不是保守，是**威胁模型决定的**：插件是用户从网上下载、自己都不会逐字读完的
 * 文本。如果它能放宽审批，那么「安装一个插件」就等于「把设备控制权交给
 * 一个陌生人的 JSON」。而收紧永远不会害人 —— 最差是模型干不了活，
 * 用户一眼就能看出来。
 *
 * 写错方向的清单不会被静默忽略：[PluginLoader] 会检测 `autoApprove` 这类
 * 已知的越权字段名并**直接拒收**，因为静默忽略会让用户以为它生效了。
 *
 * ## 版本
 *
 * [SCHEMA_MAX] 现在是 2。[PluginManifest.validate] 同时接受 1 和 2：
 * schema 1 的清单（只有 prompt 段）在新版本里照常可用，
 * 这是「用户写的文件不能因为 App 升级就失效」的底线。
 */
@Serializable
data class PluginManifest(
    val schema: Int = SCHEMA_MAX,

    /** 全局唯一 id，同时是设置里的开关键。必须带 `dsh-plugin-` 前缀以免和内置撞名 */
    val id: String = "",

    /** 展示名，设置页直接显示 */
    val name: String = "",

    /** 一句话说明它改了什么 —— 这一页的风险是「开了却不知道开了什么」 */
    val description: String = "",

    val version: String = "",

    /** 作者署名。留空也行，但导入时会被展示出来，写的人自己权衡 */
    val author: String = "",

    val prompt: PromptPart? = null,

    /** schema 2 新增。没有这一段的就是纯提示词插件 */
    val tools: ToolsPart? = null,
) {

    /**
     * 校验并返回第一条错误。null 表示可用。
     *
     * 文案是给用户看的，所以每条都写清「怎么改」而不是「哪里不合法」。
     */
    fun validate(): String? {
        if (schema !in SUPPORTED) {
            return "清单版本是 $schema，这个 App 只认 ${SUPPORTED.sorted().joinToString(" 和 ")}。" +
                "升级 App，或者把 schema 改小。"
        }
        if (!ID_PATTERN.matches(id)) {
            return "id 要以 dsh-plugin- 开头，前缀后面再写 3–47 个字符，" +
                "只能用小写字母、数字、点和短横线（例如 dsh-plugin-purge）。"
        }
        if (id in BUILTIN_IDS) {
            return "id「$id」和内置插件重名了，换一个。"
        }
        if (name.isBlank()) return "name 不能为空 —— 设置页要靠它显示。"
        if (name.length > MAX_NAME) return "name 太长（最多 $MAX_NAME 字）。"
        if (description.length > MAX_DESC) return "description 太长（最多 $MAX_DESC 字）。"
        if (author.length > MAX_AUTHOR) return "author 太长（最多 $MAX_AUTHOR 字）。"

        if (prompt == null && tools == null) {
            return "prompt 和 tools 两段都是空的，装进来什么都不会发生。"
        }

        prompt?.let { p ->
            /*
             * 一段空的 prompt 加一个空的 tools 段，等于一份什么都不做的清单。
             * 用户会以为它生效了，然后在别处找原因 —— 这种「装了像没装」
             * 的状态比报错更难排查。
             */
            if (p.append.isBlank() && p.identity.isBlank() && tools == null) {
                return "prompt.identity 和 prompt.append 都是空的，插件不会产生任何效果。"
            }
            if (p.append.length > MAX_PROMPT) {
                return "prompt.append 太长（${p.append.length} 字符，上限 $MAX_PROMPT）。" +
                    "系统提示每轮都要发，太长会明显拖慢首字。"
            }
            if (p.identity.length > MAX_PROMPT) {
                return "prompt.identity 太长（${p.identity.length} 字符，上限 $MAX_PROMPT）。"
            }
            p.condition?.let { c ->
                if (c.model.length > 64) return "prompt.when.model 太长（上限 64 字符）。"
            }
        }

        tools?.let { t ->
            if (t.hide.isEmpty() && t.only.isEmpty() && t.confirm.isEmpty() && prompt == null) {
                return "tools 段是空的，插件不会产生任何效果。"
            }
            /*
             * 黑名单和白名单同时写，语义就取决于实现者先看哪个 ——
             * 这种「读代码才知道会发生什么」的清单不该被接受。
             */
            if (t.hide.isNotEmpty() && t.only.isNotEmpty()) {
                return "tools.hide 和 tools.only 只能写一个：" +
                    "hide 是黑名单（禁用这几个），only 是白名单（只留这几个）。"
            }
            for ((label, list) in listOf(
                "tools.hide" to t.hide,
                "tools.only" to t.only,
                "tools.confirm" to t.confirm,
            )) {
                if (list.size > MAX_TOOLS) return "$label 最多写 $MAX_TOOLS 个工具。"
                list.firstOrNull { !TOOL_NAME.matches(it) }?.let { bad ->
                    return "$label 里的「$bad」不是合法的工具名" +
                        "（小写字母开头，只含小写字母、数字、下划线，例如 run_script）。"
                }
            }
            t.condition?.let { c ->
                if (c.model.length > 64) return "tools.when.model 太长（上限 64 字符）。"
            }
        }
        return null
    }

    /** 这份清单会不会改变工具面 —— 设置页要据此写「会影响哪些能力」 */
    val touchesTools: Boolean
        get() = tools != null && (tools.hide.isNotEmpty() || tools.only.isNotEmpty())

    companion object {
        /** 当前最高版本。新清单写这个数 */
        const val SCHEMA_MAX = 2

        /** 能读的版本。老的必须一直能读，见类注释 */
        val SUPPORTED = setOf(1, 2)

        /** 一份清单的字符上限。超过这个数就不像「声明」而像「塞进来的一篇文章」了 */
        const val MAX_CHARS = 8_000

        const val MAX_PROMPT = 4_000
        const val MAX_NAME = 40
        const val MAX_DESC = 120
        const val MAX_AUTHOR = 40
        const val MAX_TOOLS = 64

        /**
         * id 前缀是硬约束，不是风格建议。
         *
         * 插件 id 会出现在 `enabledPlugins` 里，而那个集合同时装着内置插件的 id。
         * 允许任意 id 的话，一份清单可以通过重名把内置插件顶掉 ——
         * 用户以为自己关掉的是「结果裁剪」，实际关掉的是别的东西。
         */
        val ID_PATTERN = Regex("^dsh-plugin-[a-z0-9][a-z0-9._-]{2,47}$")

        /** 工具名。与 `AgentTool.name` 的命名保持一致 */
        val TOOL_NAME = Regex("^[a-z][a-z0-9_]{1,39}$")

        val BUILTIN_IDS = setOf(
            "dsh-token-saver",
            "dsh-plugin-save-token",
            "dsh-whale",
            "dsh-fengshen",
        )
    }
}

/**
 * 提示词段。
 *
 * [identity] 与 [append] 是**两个不同的位置**，不能合并：
 *
 * · 身份段在最前面，模型用它决定「我是谁」。替换它是最有效的一招，
 *   也最容易把模型带偏 —— 所以它替换的是内置身份那句，不是整段系统提示。
 * · 尾部锚点在最后，模型对末尾指令的服从度更高。dsh-purge 1.1.84 的
 *   「尾部锚点」就是这件事，只是它靠打补丁，我们靠拼字符串。
 */
@Serializable
data class PromptPart(
    /** 替换内置的身份描述。空 = 不替换 */
    val identity: String = "",

    /** 追加到系统提示末尾。空 = 不追加 */
    val append: String = "",

    @SerialName("when")
    val condition: Condition? = null,
)

/**
 * 工具面段。schema 2 新增。
 *
 * ## 三个字段，方向全是「更严」
 *
 * | 字段 | 效果 | 为什么这样设计 |
 * | --- | --- | --- |
 * | [hide] | 这几个工具模型看不到，也执行不了 | 直接关掉一条能力 |
 * | [only] | 只留这几个，其余全关 | 白名单比黑名单难写错 |
 * | [confirm] | 这几个工具每次都要用户点确认 | 加严，不是放宽 |
 *
 * ## 「减少打断」在这里怎么成立
 *
 * dsh-purge 的卖点是「任务不再被确认弹窗打断」，它靠放宽审批做到。
 * 我们反过来：**把会弹窗的那几个工具整个关掉**。模型不会去调它，
 * 自然也不会弹窗 —— 用户的体验同样是「一路跑完」，但代价从
 * 「危险命令可能被执行」变成「模型干不了这件事，会直接告诉你」。
 *
 * 这个交换是划算的：手表上用户看不见键盘，也看不清一长串命令，
 * 「看不懂就别让它跑」比「弹个窗让用户点同意」更负责。
 */
@Serializable
data class ToolsPart(
    /** 黑名单。这些工具不给模型看，也拒绝执行 */
    val hide: List<String> = emptyList(),

    /** 白名单。只把列出的工具给模型，其余全关 */
    val only: List<String> = emptyList(),

    /** 加严：这些工具每次都问，无视「总是允许」 */
    val confirm: List<String> = emptyList(),

    @SerialName("when")
    val condition: Condition? = null,
)

/**
 * 生效条件。目前只按模型匹配。
 *
 * 为什么支持 glob 而不是精确值：用户手上的模型名带版本号和日期
 * （`deepseek-chat-2026-08`、`qwen3-32b-instruct`），写死一个全名
 * 意味着换模型就得改清单。`*` 通配能覆盖绝大多数场景，而且规则简单到
 * 用户看一眼就懂 —— 正则表达式在这里只会让人写错。
 */
@Serializable
data class Condition(
    /** 空 = 所有模型。支持 `*`，例如 `deepseek-*`、`*reasoner*` */
    val model: String = "",
)

/** 变量插值：`{{model}}` / `{{provider}}` / `{{today}}` / `{{weekday}}` */
object ManifestVars {

    fun fill(text: String, ctx: PromptContext): String {
        if (!text.contains("{{")) return text
        val provider = ctx.settings.activeProvider
        val today = runCatching { java.time.LocalDate.now() }.getOrNull()
        return text
            .replace("{{model}}", provider?.defaultModel.orEmpty())
            .replace("{{provider}}", provider?.name.orEmpty())
            .replace("{{today}}", today?.toString().orEmpty())
            .replace("{{weekday}}", today?.dayOfWeek?.let(::weekdayOf).orEmpty())
    }

    private fun weekdayOf(day: java.time.DayOfWeek): String = when (day) {
        java.time.DayOfWeek.MONDAY -> "周一"
        java.time.DayOfWeek.TUESDAY -> "周二"
        java.time.DayOfWeek.WEDNESDAY -> "周三"
        java.time.DayOfWeek.THURSDAY -> "周四"
        java.time.DayOfWeek.FRIDAY -> "周五"
        java.time.DayOfWeek.SATURDAY -> "周六"
        java.time.DayOfWeek.SUNDAY -> "周日"
    }
}

/** glob 匹配，只认 `*`。空 pattern 视为「全部匹配」 */
internal fun globMatches(pattern: String, value: String): Boolean {
    val p = pattern.trim()
    if (p.isEmpty()) return true
    if (!p.contains('*')) return p.equals(value, ignoreCase = true)
    val regex = p.split('*').joinToString(".*") { Regex.escape(it) }
    return Regex("^$regex$", RegexOption.IGNORE_CASE).matches(value)
}
