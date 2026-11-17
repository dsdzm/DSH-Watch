package com.dshwatch.domain.plugin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 Cordis 插件包（`npm pack` 出来的 tgz）**转译**成声明式清单。
 *
 * ## 转译不是模拟
 *
 * 这里不执行任何一行 JS，也不假装执行过。做法是把源码当成**文本**读，
 * 把里面那些「最终会变成提示词」的字符串字面量挑出来，转写成清单里的
 * `prompt` 与 `presets`。原来由 `apply(ctx)` 完成的事（注册工具、读配置、
 * 挂钩子）一件也做不了，只能丢掉 —— 但它们丢得起：
 *
 * | 原插件里的东西 | 转译后 |
 * |---|---|
 * | 一大段破甲 / 人格 / 措辞提示词 | `prompt.append`，或几条 `presets` |
 * | `ctx.tool(...)` 注册的新工具 | 丢弃。手表上没有 Node，也没有它能碰的文件系统 |
 * | 读写 profile、安装预设 | 丢弃。这边预设由用户自己选，关掉插件即消失 |
 * | 放宽审批 | 丢弃，且永远不会被转出来（见 [PluginManifest] 类注释的威胁模型） |
 *
 * ## 为什么必须让用户核对
 *
 * 字符串字面量里什么都可能是：日志、CSS、正则、README 片段。判据是启发式的，
 * 一定会有认错的时候。所以转译结果**不是**「导入完成」，而是一份**草稿**：
 * 界面上先把「从哪个文件、认出几段、丢掉了什么」摆出来，用户看一眼再决定。
 * 一个悄悄生效、又没真正生效的插件，比装不上更难查。
 *
 * ## 为什么值得做
 *
 * npm 上绝大多数有趣插件都是 Cordis 包。让用户「去翻源码、把提示词复制出来、
 * 自己拼成 JSON」，实际通过率接近零 —— 这条路上真正卡住的从来不是能力，
 * 而是四步手工操作。
 */
object CordisTranslator {

    /** 单份源码最大读取量。再大的 `.js` 一般是打包产物，抽出来的也是混淆代码 */
    private const val MAX_FILE = 256 * 1024

    /** 一个包里最多读几份源码。够覆盖 `lib/` 下的模块化写法，再多就是带 node_modules 了 */
    const val MAX_FILES = 24

    /** 所有源码的累计上限。抽字面量要整段进内存，这里给一条总闸 */
    private const val MAX_TOTAL = 1024 * 1024

    /** 一段候选要有多长才可能是提示词。短于这个数基本是键名、类名、日志前缀 */
    private const val MIN_LIT = 40

    /** 认成提示词的最低分。低于它的只在没有任何候选时才拿来兜底 */
    private const val MIN_SCORE = 90

    /** 一条预设的提示词上限，与 [PluginManifest.MAX_PRESET_PROMPT] 对齐 */
    private const val MAX_PRESET = 2_000

    /** 主提示词上限，与 [PluginManifest.MAX_PROMPT] 对齐 */
    private const val MAX_APPEND = 4_000

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 包里的元信息。全部可能缺失 —— 有些包是手打的 tar，没有 package.json */
    data class Meta(
        val name: String = "",
        val version: String = "",
        val description: String = "",
        val homepage: String = "",
    )

    /**
     * 转译输入。
     *
     * [sources] 是「路径 → 文本」，只放 `.js` / `.mjs` / `.cjs` / `.ts`。
     * [truncated] 记的是「因为超限没读进来的文件数」—— 它要出现在核对说明里，
     * 否则用户会以为转译已经把整个包看完了。
     */
    data class Bundle(
        val meta: Meta,
        val sources: List<Source>,
        val truncated: Int = 0,
    )

    data class Source(val path: String, val text: String)

    /** 转译成功：一份草稿清单 + 一段给用户核对的说明 */
    data class Result(val manifest: PluginManifest, val note: String)

    /**
     * 转译一份包。
     *
     * 返回 [PluginLoader.Parsed.Err] 的场合只有一种：**通篇没有一句像提示词的东西**。
     * 那说明这个插件的能力全在代码里（工具、钩子），转出来会是一份空清单 ——
     * 装了像没装，是最坏的结果。
     */
    fun translate(bundle: Bundle, where: String): PluginLoader.Parsed {
        val meta = bundle.meta
        val all = ArrayList<Lit>()

        for (file in bundle.sources) {
            // 每段带上来源文件名：核对说明要能指到具体文件，否则用户没法复核
            all += literals(file.text).map { it.copy(file = file.path) }
        }

        // 每个文件内部已经合并过相邻片段（见 literals），这里只做筛选
        val joined = all
        val scored = joined
            .filter { it.text.length in MIN_LIT..MAX_APPEND }
            .filter { !looksLikeCode(it.text) }
            .map { it to score(it.text) }

        val strong = scored.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }
        val weak = scored.filter { it.second < MIN_SCORE }.sortedByDescending { it.second }

        /*
         * 预设先于主提示词识别。
         *
         * 原因是两者会抢同一段文字：一个写了三条预设的插件，最长那一段本来
         * 属于某条预设，把它再抄进 append 会让「选不选预设都一样」。
         */
        val presets = pairPresets(joined)
        val usedByPreset = presets.map { it.systemPrompt.trim() }.toSet()

        val main = strong.firstOrNull { it.first.text !in usedByPreset }
            ?: weak.firstOrNull { it.first.text !in usedByPreset }

        if (main == null && presets.isEmpty()) {
            return PluginLoader.Parsed.Err(
                "$where 里没有一句像提示词的东西 —— 它是一段只注册工具或只挂钩子的代码。" +
                    "手表上的插件不能执行代码，所以这个包转不出来。可以试：\n" +
                    "· 问作者有没有提供 $MANIFEST（声明式清单）；\n" +
                    "· 如果它只是想「少弹几个确认」，到「AI 能力 → 审批」里自己调。",
            )
        }

        val id = makeId(meta.name)
        val name = displayName(meta.name, id)
        val desc = meta.description.trim().take(PluginManifest.MAX_DESC)
            .ifBlank { "从 ${meta.name.ifBlank { "npm 包" }} 转译" }

        val prompt = main?.let {
            PromptPart(append = clip(it.first.text, MAX_APPEND))
        }

        // 主提示词与预设二选一：两份都塞进去，等于同一句话发两遍
        val manifest = PluginManifest(
            id = id,
            name = name,
            description = desc,
            version = meta.version,
            prompt = prompt,
            presets = if (presets.isEmpty()) emptyList() else presets,
        )

        manifest.validate()?.let { why ->
            return PluginLoader.Parsed.Err("$where 转译出来的草稿不合法：$why（源包的元信息可能不全）")
        }

        return PluginLoader.Parsed.Ok(manifest, note(manifest, bundle, main, presets, scored.size))
    }

    /** 核对说明。用户看完这段才决定要不要开开关 */
    private fun note(
        manifest: PluginManifest,
        bundle: Bundle,
        main: Pair<Lit, Int>?,
        presets: List<PresetPart>,
        candidates: Int,
    ): String {
        val lines = ArrayList<String>()
        val files = bundle.sources.size
        lines += "读入 $files 份源码" +
            if (bundle.truncated > 0) "（另有 ${bundle.truncated} 份太大没读）" else "" +
            "，挑出 $candidates 段候选文本。"

        main?.let { (lit, sc) ->
            val word = if (sc >= MIN_SCORE) "认成主提示词" else "没有更合适的，退而取最长的一段"
            lines += "· ${lit.file} 里 ${lit.text.length} 字那段$word，已放进「追加到系统提示」。"
        }
        if (presets.isNotEmpty()) {
            lines += "· 认出 ${presets.size} 条预设：${presets.joinToString("、") { it.name }}。"
        }
        lines += "丢掉的部分：工具注册、配置读写、钩子 —— 手表上这些跑不了，" +
            "转译只保留它会写进系统提示的那些话。"
        lines += "**导入后请先读一遍正文**，确认不是把日志或注释当成了提示词。"
        return lines.joinToString("\n")
    }

    // ---------------- 字面量 ----------------

    internal data class Lit(
        val text: String,
        val start: Int,
        /** 引号闭合后的位置。合并相邻片段时要在原文里看两段之间到底是什么 */
        val end: Int = 0,
        /** 模板字符串里带 `${}` 的段：多半是拼日志，不是提示词 */
        val interpolated: Boolean = false,
        val file: String = "",
    )

    /**
     * 扫出一段源码里的字符串字面量，并把被 `+` 拆开的相邻片段合回去。
     *
     * 手写而不是上正则，因为要跳过注释：注释里的中文说明往往又长又像提示词，
     * 用正则会把它当正文抽出来 —— 然后用户看到的系统提示里混进一句
     * 「// 这里改成你自己的名字」。注释是给人看的，不该进提示词。
     */
    internal fun literals(src: String): List<Lit> = mergeAdjacent(rawLiterals(src), src)

    private fun rawLiterals(src: String): List<Lit> {
        val out = ArrayList<Lit>()
        val n = src.length
        var i = 0
        while (i < n) {
            when (src[i]) {
                '/' -> {
                    when {
                        i + 1 < n && src[i + 1] == '/' -> {
                            val nl = src.indexOf('\n', i)
                            i = if (nl < 0) n else nl + 1
                        }
                        i + 1 < n && src[i + 1] == '*' -> {
                            val end = src.indexOf("*/", i + 2)
                            i = if (end < 0) n else end + 2
                        }
                        else -> i++
                    }
                }

                '\'', '"', '`' -> {
                    val quote = src[i]
                    val sb = StringBuilder()
                    var interpolated = false
                    var j = i + 1
                    var closed = false
                    while (j < n) {
                        val c = src[j]
                        when {
                            c == '\\' && j + 1 < n -> {
                                sb.append(unescape(src[j + 1]))
                                j += 2
                            }
                            c == quote -> {
                                closed = true
                                j++
                                break
                            }
                            // 单双引号字符串不能跨行：遇到换行说明是未闭合的引号，
                            // 这一段不是字符串（可能是正则或注释里的撇号），直接放弃
                            c == '\n' && quote != '`' -> break
                            c == '$' && quote == '`' && j + 1 < n && src[j + 1] == '{' -> {
                                interpolated = true
                                sb.append(c)
                                j++
                            }
                            else -> {
                                sb.append(c)
                                j++
                            }
                        }
                    }
                    if (closed && sb.isNotEmpty()) {
                        out += Lit(sb.toString(), i, j, interpolated)
                    }
                    i = j
                }

                else -> i++
            }
        }
        return out
    }

    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        else -> c
    }

    /**
     * 拼接被 `+` 拆开的相邻片段。
     *
     * `'你是' + '工程助手'` 这种写法在 JS 里很常见（为了不超行宽），
     * 不合并就会得到一堆 30 字以下的碎片，全部被长度门槛滤掉。
     *
     * **间隔里必须只剩 `+` 和空白**。这条限制是必须的：对象字面量里
     * `name: '破甲', prompt: '…'` 两段的间隔只隔了十几个字符，一旦允许
     * 「离得近就合并」，名字与正文会被粘成一段，配对逻辑就再也认不出预设，
     * 而抽出来的东西看起来还很像一段正常的提示词 —— 错得毫无痕迹。
     */
    private fun mergeAdjacent(inList: List<Lit>, src: String): List<Lit> {
        if (inList.isEmpty()) return inList
        val out = ArrayList<Lit>()
        var acc: Lit? = null

        for (lit in inList) {
            val prev = acc
            if (prev == null) {
                acc = lit
                continue
            }
            val gap = if (lit.start > prev.end && prev.end <= src.length && lit.start <= src.length) {
                src.substring(prev.end, lit.start)
            } else {
                ""
            }
            val concat = gap.isNotEmpty() && gap.length < 400 && gap.all { it == '+' || it.isWhitespace() }
            if (concat) {
                acc = Lit(
                    text = prev.text + lit.text,
                    start = prev.start,
                    end = lit.end,
                    interpolated = prev.interpolated || lit.interpolated,
                    file = prev.file,
                )
            } else {
                out += prev
                acc = lit
            }
        }
        acc?.let { out += it }
        return out
    }

    /** 明显是代码/路径/URL 的段，不该进提示词 */
    private fun looksLikeCode(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return true
        if (s.startsWith("http://") || s.startsWith("https://")) return true
        if (s.startsWith("./") || s.startsWith("../") || s.startsWith("/")) return true
        if (s.startsWith("data:")) return true
        // 选择器、正则、格式化串：符号密度极高
        val symbols = s.count { it in "{}[]<>;=\\$|&" }
        if (symbols * 10 > s.length) return true
        if (s.contains("=>") || s.contains("function(") || s.contains("require(")) return true
        if (s.contains("ctx.") && s.length < 200) return true
        return false
    }

    /**
     * 给一段文本打分：像不像「写给模型的指令」。
     *
     * 判据来自提示词本身的样子，而不是它出现在哪个位置 ——
     * 位置（比如 `ctx.systemPrompt = ...`）在这些包里千变万化，
     * 靠位置只能覆盖一两种写法，靠文本特征几乎都能覆盖。
     */
    private fun score(t: String): Int {
        val s = t.trim()
        var v = 0

        // 中文密度：面向中文用户的提示词几乎一定是大段中文
        val cjk = s.count { it.code in 0x4E00..0x9FFF }
        v += cjk * 3

        // 句子结构：有句号、分号、空行，说明是成段的说明而不是一个名字
        v += s.count { it in "。！？；" } * 8
        v += (s.windowed(2).count { it == "\n\n" }).coerceAtMost(4) * 10

        // 祈使与第二人称：提示词在对模型说话
        val markers = listOf(
            "你是", "你必须", "你应该", "你需要", "不要", "禁止", "始终", "永远",
            "务必", "记住", "回答", "回复", "输出格式", "作为",
            "You are", "You must", "Do not", "Never", "Always", "must ", "Respond",
        )
        v += markers.count { s.contains(it, ignoreCase = true) } * 25

        // 长度：太短的信息量不足，太长多半是文档
        v += when {
            s.length < 80 -> -40
            s.length < 200 -> 10
            s.length < 1_200 -> 30
            else -> 0
        }

        // 带插值的模板串：通常是拼日期的日志或欢迎语
        if (s.contains("\${")) v -= 40
        return v
    }

    // ---------------- 预设配对 ----------------

    /**
     * 从候选里配出「名字 + 正文」的成对关系。
     *
     * 没有做真正的语法解析 —— 那需要一整个 JS parser。用的是排版上的一个
     * 稳定规律：一条预设的字面量顺序几乎总是「短名字 → 可选的说明 → 长正文」，
     * 且三者距离很近（同在一个对象字面量里）。
     *
     * 误配的代价可控：配错的名字只是标题难看，正文仍然是对的；而漏配的
     * 代价是整段提示词变成一条常驻的 append。所以这里偏向「宁可少配」——
     * 只认「短段 ≤ 24 字、长段 ≥ 120 字、距离 ≤ 600 字符」这一种最干净的形态。
     */
    private fun pairPresets(all: List<Lit>): List<PresetPart> {
        // 不排序：all 已经是「按文件顺序、文件内按出现位置」，排序会把不同文件的
        // 偏移量混在一起，配出跨文件的错误组合
        val shuffled = all
        val out = ArrayList<PresetPart>()
        val used = HashSet<Int>()
        val ids = HashSet<String>()

        for (i in shuffled.indices) {
            if (out.size >= PluginManifest.MAX_PRESETS) break
            if (i in used) continue
            val head = shuffled[i]
            val headText = head.text.trim()
            /*
             * 先把「像名字」的段挑出来。
             *
             * 只按长度筛是不够的：`'fs'`、`'deploy'` 都是 2–7 个字符，正好落在
             * 名字的区间里，而后面的正文随便一段都能凑成「名字 + 正文」。
             * 实测这条误配会把一段本该进 append 的提示词吃掉，插件装上了却
             * 什么都不生效 —— 比装不上难查得多。
             */
            if (!looksLikeName(headText)) continue

            // 往后看两段，找那段长的
            for (k in (i + 1)..minOf(i + 2, shuffled.lastIndex)) {
                if (k in used) continue
                val body = shuffled[k]
                if (body.file != head.file) break
                if (body.start - head.start > 600) break
                /*
                 * 60 字符而不是 120。
                 *
                 * 一条预设的正文按中文算，60 字已经是一小段话；设成 120 会把
                 * 「三句话讲清风格」的短预设全部漏掉，而它们恰恰是最常见的一类。
                 * 真正防误配的是下面那条 score 判断，长度只是先筛掉键名与日志。
                 */
                if (body.text.length < 60) continue
                if (looksLikeCode(body.text)) continue
                if (body.text.length > MAX_PRESET) continue
                // 正文自己也得像提示词，否则「短名字 + 长日志」也会被配上
                if (score(body.text) < MIN_SCORE) continue

                val pid = presetId(headText, ids)
                out += PresetPart(
                    id = pid,
                    name = headText.take(PluginManifest.MAX_PRESET_NAME),
                    description = "由 ${body.file} 转译",
                    systemPrompt = body.text.trim(),
                )
                used += i
                used += k
                break
            }
        }
        return out
    }

    /**
     * 预设 id。必须是合法标识符，而名字往往是中文 —— 一个字都转不出来。
     *
     * 所以这里不追求「像名字」：转不出来就用 `preset`、`preset-2`。
     * 早先的写法会得到 `p-` 这种残渣，两条中文预设还会撞成 `p--2`，
     * 用户在选择器里看不到 id 没关系，但它在设置里存着，难看且难排查。
     */
    /**
     * 这段文本像不像一条预设的名字。
     *
     * 判据是「人给东西起名」的样子：中文短语，或者首字母大写的英文词组。
     * `'fs'`、`'deploy'`、`'./lib/a.js'` 都长得不像 —— 排除它们不损失什么，
     * 名字本来就该是给用户看的。
     */
    private fun looksLikeName(t: String): Boolean {
        if (t.length !in 2..24) return false
        if (t.any { it in "/\\.(){}[]=;:$<>|&*\"'`\n" }) return false
        if (t.any { it in "。！？；" }) return false

        if (t.any { it.code in 0x4E00..0x9FFF }) return true

        val words = t.split(' ').filter { it.isNotBlank() }
        return words.size in 1..3 &&
            t[0].isUpperCase() &&
            t.all { it.isLetter() || it == ' ' || it == '-' }
    }

    private fun presetId(name: String, seen: MutableSet<String>): String {
        val base = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(18)

        val candidate = if (base.length >= 2) base else "preset"
        var id = candidate
        var n = 2
        while (!seen.add(id)) {
            id = "$candidate-$n"
            n++
        }
        return id
    }

    // ---------------- 元信息 ----------------

    /** 从 package.json 原文里取元信息。解析失败就当没有 —— 不影响转译本身 */
    fun metaOf(packageJson: String?): Meta {
        val raw = packageJson?.trim().orEmpty()
        if (!raw.startsWith("{")) return Meta()
        val obj = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return Meta()
        fun str(key: String): String =
            runCatching { obj[key]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
        return Meta(
            name = str("name"),
            version = str("version"),
            description = str("description"),
            homepage = str("homepage"),
        )
    }

    /**
     * 包名 → 清单 id。
     *
     * 两条硬约束：必须以 `dsh-plugin-` 开头（见 [PluginManifest.ID_PATTERN]），
     * 且只能用小写字母、数字、`.`、`-`。npm 的 scope（`@acme/foo`）和作者
     * 爱用的下划线都得抹平 —— 否则用户拿到的是「id 不合法」，
     * 而他什么也没写错，错的是包的命名习惯。
     */
    internal fun makeId(pkgName: String): String {
        val bare = pkgName.trim()
            .substringAfterLast('/')
            .lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .ifBlank { "cordis" }
        val withPrefix = if (bare.startsWith("dsh-plugin-")) bare else "dsh-plugin-$bare"
        // ID_PATTERN 上限：前缀之后最多 47 个字符
        return withPrefix.take("dsh-plugin-".length + 47).trimEnd('-', '.')
    }

    private fun displayName(pkgName: String, id: String): String {
        val bare = pkgName.trim().ifBlank { id.removePrefix("dsh-plugin-") }
        return bare.take(PluginManifest.MAX_NAME)
    }

    private fun clip(text: String, max: Int): String {
        val t = text.trim()
        if (t.length <= max) return t
        return t.take(max - 12).trimEnd() + "\n\n（转译时截断）"
    }

    private const val MANIFEST = PluginArchive.MANIFEST_NAME
}
