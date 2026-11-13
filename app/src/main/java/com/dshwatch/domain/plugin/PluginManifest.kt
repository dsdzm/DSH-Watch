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
 * 「一份可读、可审计的声明」。它只能改模型看到什么，不能碰设备。
 *
 * ## 为什么字段这么少
 *
 * 每加一个字段，就离「一门小型语言」近一步，也离「用户读不懂自己在装什么」近一步。
 * dsh-purge 那类项目之所以要打二进制补丁，是因为宿主没给扩展点；
 * 我们的宿主是自己写的，把最常用的两个扩展点直接开出来就够了：
 *
 * | 字段 | 对应 DSH 的什么 |
 * | --- | --- |
 * | [PromptPart.append] | 系统提示词尾部锚点（dsh-purge 的 tail anchor） |
 * | [PromptPart.identity] | 替换内置身份段（它的「破甲」核心） |
 * | [PromptPart.condition] | 按模型切规则集（它的 per-model prompt） |
 *
 * 审批规则、沙箱策略**故意不做**。理由见 [PluginManifest] 的类注释之外那段：
 * 声明式插件只能加严，不能放宽 —— 放宽审批等于把「每次确认」这道唯一的
 * 安全门交给一份用户不会逐字读的 JSON。
 *
 * ## 版本
 *
 * [schema] 现在是 1。将来加字段时，老清单必须继续能读：
 * 未知字段直接忽略（`ignoreUnknownKeys`），缺字段用默认值补。
 * 这是「用户写的文件不能因为 App 升级就失效」的底线。
 */
@Serializable
data class PluginManifest(
    val schema: Int = SCHEMA,

    /** 全局唯一 id，同时是设置里的开关键。必须带 `dsh-plugin-` 前缀以免和内置撞名 */
    val id: String = "",

    /** 展示名，设置页直接显示 */
    val name: String = "",

    /** 一句话说明它改了什么 —— 这一页的风险是「开了却不知道开了什么」 */
    val description: String = "",

    val version: String = "",

    val prompt: PromptPart? = null,
) {

    /**
     * 校验并返回第一条错误。null 表示可用。
     *
     * 文案是给用户看的，所以每条都写清「怎么改」而不是「哪里不合法」。
     */
    fun validate(): String? {
        if (schema != SCHEMA) {
            return "清单版本是 $schema，这个 App 只认 $SCHEMA。升级 App 或换一份清单。"
        }
        if (!ID_PATTERN.matches(id)) {
            return "id 只能用小写字母、数字、点和短横线，3–48 个字符，" +
                "并且以 dsh-plugin- 开头（例如 dsh-plugin-purge）。"
        }
        if (id in BUILTIN_IDS) {
            return "id「$id」和内置插件重名了，换一个。"
        }
        if (name.isBlank()) return "name 不能为空 —— 设置页要靠它显示。"
        if (name.length > MAX_NAME) return "name 太长（最多 $MAX_NAME 字）。"
        if (description.length > MAX_DESC) return "description 太长（最多 $MAX_DESC 字）。"

        val p = prompt ?: return "至少要有一个 prompt 段，否则这个插件什么都不做。"
        if (p.append.isBlank() && p.identity.isBlank()) {
            return "prompt.append 和 prompt.identity 都是空的，插件不会产生任何效果。"
        }
        if (p.append.length > MAX_PROMPT) {
            return "prompt.append 太长（${p.append.length} 字符，上限 $MAX_PROMPT）。" +
                "系统提示每轮都要发，太长会明显拖慢首字。"
        }
        if (p.identity.length > MAX_PROMPT) {
            return "prompt.identity 太长（${p.identity.length} 字符，上限 $MAX_PROMPT）。"
        }
        p.condition?.let { c ->
            if (c.model.length > 64) return "condition.model 太长（上限 64 字符）。"
        }
        return null
    }

    companion object {
        const val SCHEMA = 1

        /** 一份清单的字符上限。超过这个数就不像「声明」而像「塞进来的一篇文章」了 */
        const val MAX_CHARS = 8_000

        const val MAX_PROMPT = 4_000
        const val MAX_NAME = 40
        const val MAX_DESC = 120

        /**
         * id 前缀是硬约束，不是风格建议。
         *
         * 插件 id 会出现在 `enabledPlugins` 里，而那个集合同时装着内置插件的 id。
         * 允许任意 id 的话，一份清单可以通过重名把内置插件顶掉 ——
         * 用户以为自己关掉的是「结果裁剪」，实际关掉的是别的东西。
         */
        val ID_PATTERN = Regex("^dsh-plugin-[a-z0-9][a-z0-9._-]{2,47}$")

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
