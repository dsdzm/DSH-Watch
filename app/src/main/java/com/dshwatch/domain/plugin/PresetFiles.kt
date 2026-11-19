package com.dshwatch.domain.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 读 `presets/` 下的**声明文件**（`.yml` / `.yaml` / `.json`）。
 *
 * ## 为什么要单独解一层 YAML
 *
 * dsh-purge 这类插件的预设不是一段纯文本，而是一份**装配单**：
 * 一条预设写成 `presets/redteam/preset.yml`，里面列着要挂哪些组件，
 * 其中 `persona` 组件的 `config.prefix` 才是真正写给模型的那段话：
 *
 * ```yaml
 * - id: persona
 *   name: '@deepseek-ai/dsh-persona'
 *   config:
 *     prefix: |
 *       ## 运行环境前提
 *       红队模式会真实发包……
 * ```
 *
 * 早先只认 `.md`，于是整个 `presets/` 目录被跳过，转译器退回去从 JS 里
 * 猜 —— 猜出来的「## 指挥职责」「当前 Desktop 实例」是源码里的规则说明，
 * 不是预设。**有结构化来源时就不该猜**，这是这个类存在的理由。
 *
 * ## 只解一个子集
 *
 * 完整 YAML 规范（锚点、flow 风格、多行折叠、tag）在这里全是负担：
 * 插件作者写预设只用「缩进 + 键值 + 块标量 `|` / `>`」这三样。
 * 所以这里是两百行的定向扫描器，不是解析器 —— 认不出的写法直接跳过，
 * 而不是抛异常，让整份预设消失。
 *
 * ## 取哪几个键
 *
 * · 名字：`name` / `title` / `displayName` / `label`
 * · 说明：`description` / `desc` / `summary`
 * · 正文：`prefix` / `systemPrompt` / `instructions` / `prompt` / `append` /
 *   `content` / `text` / `body`
 *
 * 名字要过 [looksName]：`'@deepseek-ai/dsh-persona'` 是组件包名，
 * 不是「红队模式」这种给用户看的名字。取错名字比取不到更糟 ——
 * 用户会在选择器里看到一条叫 `dsh-persona` 的预设，然后以为自己选错了。
 */
internal object PresetFiles {

    /** 一份文件解析出来的预设。一个文件通常只有一条，多文档时才会多 */
    data class Parsed(
        val name: String,
        val description: String,
        val prompt: String,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 按扩展名分派。`.json` 走结构化遍历，其余按 YAML 子集扫 */
    fun parse(path: String, text: String): List<Parsed> =
        if (path.lowercase().endsWith(".json")) fromJson(text) else fromYaml(text)

    // ---------------- YAML ----------------

    private fun fromYaml(text: String): List<Parsed> {
        val docs = splitDocs(text)
        val out = ArrayList<Parsed>()
        for (doc in docs) {
            val parsed = oneDoc(doc) ?: continue
            out += parsed
        }
        return out
    }

    /** `---` 分隔的多文档。单独一行才算，正文里的 `---` 不拆 */
    private fun splitDocs(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (line in text.lines()) {
            if (line.trim() == "---") {
                if (cur.isNotBlank()) out += cur.toString()
                cur.setLength(0)
            } else {
                cur.append(line).append('\n')
            }
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    private fun oneDoc(doc: String): Parsed? {
        val lines = doc.lines()
        val blocks = ArrayList<String>()
        var name: String? = null
        var desc: String? = null
        var sawKey = false
        var i = 0

        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) {
                i++
                continue
            }
            val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }
            val trimmed = raw.substring(indent)
            if (trimmed.startsWith("#")) {
                i++
                continue
            }

            // 列表项的 `- ` 只是排版，键名紧跟在它后面
            val bare = if (trimmed.startsWith("- ")) trimmed.substring(2).trimStart() else trimmed
            val colon = colonOf(bare)
            if (colon <= 0) {
                i++
                continue
            }
            sawKey = true
            val key = bare.substring(0, colon).trim().trim('"', '\'').lowercase()
            val value = bare.substring(colon + 1).trim()

            /*
             * 块标量：`|` 保留换行，`>` 折叠成一行。
             *
             * 预设在九成情况下用 `|` —— 提示词里的列表、空行、加粗都是有意义的，
             * 折叠会把「## 标题」和下一段粘成一句。只有在 `>` 时折，且按空行分段折。
             */
            if (value.startsWith("|") || value.startsWith(">")) {
                val body = collectBlock(lines, i + 1, indent)
                val t = if (value.startsWith(">")) fold(body.text) else body.text
                if (t.isNotBlank()) blocks += t.trim()
                i = body.next
                continue
            }

            val clean = unquote(value)
            when (key) {
                "name", "title", "displayname", "label" ->
                    if (name == null && looksName(clean)) name = clean
                "description", "desc", "summary", "intro" ->
                    if (desc == null && clean.length in 4..PluginManifest.MAX_DESC) desc = clean
                "prefix", "systemprompt", "system_prompt", "instructions",
                "prompt", "append", "content", "text", "body" ->
                    if (clean.length >= 20) blocks += clean
            }
            i++
        }

        /*
         * 通篇没有一个「键: 值」→ 这其实是一份纯文本（作者只是把它命名成 .yml）。
         * 整段当正文，别让它因为扩展名而消失。
         */
        if (!sawKey) {
            val t = doc.trim()
            if (t.length < 20) return null
            return Parsed("", "", t)
        }

        val prompt = blocks.joinToString("\n\n").trim()
        /*
         * 只有元信息、没有正文也返回。
         *
         * dsh-purge 把一条预设拆成两个文件：`preset.yml` 只有
         * `name` / `description` / `order`，正文在同目录的 `agent.cordis.yml`。
         * 这里把「没正文」直接丢掉，那条预设就永远拿不到名字 ——
         * 调用方会把同一目录的几个文件合并成一条。
         */
        if (prompt.length < 20 && name == null && desc == null) return null
        return Parsed(name.orEmpty(), desc.orEmpty(), prompt)
    }

    private data class Block(val text: String, val next: Int)

    /**
     * 收一段块标量。
     *
     * 归属判据是**缩进**：比键更深（严格大于）的行属于它，空行也算 ——
     * 提示词里的空行是分段，丢掉了整段会变成一坨。遇到不比键更深的非空行
     * 就停，那一行属于下一个键。
     */
    private fun collectBlock(lines: List<String>, start: Int, keyIndent: Int): Block {
        val body = ArrayList<String>()
        var j = start
        while (j < lines.size) {
            val l = lines[j]
            if (l.isBlank()) {
                body += ""
                j++
                continue
            }
            val ind = l.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) l.length else it }
            if (ind <= keyIndent) break
            body += l
            j++
        }
        // 尾部空行不属于这段（它是与下一个键之间的间隔）
        while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
        return Block(dedent(body), j)
    }

    /** 去掉整段共有的缩进。YAML 的块标量缩进只是排版，不该进提示词 */
    private fun dedent(lines: List<String>): String {
        val min = lines.filter { it.isNotBlank() }
            .map { it.indexOfFirst { c -> !c.isWhitespace() } }
            .filter { it >= 0 }
            .minOrNull() ?: 0
        return lines.joinToString("\n") { if (it.length >= min) it.substring(min) else it.trimStart() }
    }

    /** `>` 折叠：相邻非空行用空格连，空行保留为段落分隔 */
    private fun fold(text: String): String {
        val out = StringBuilder()
        var blank = false
        for (line in text.lines()) {
            if (line.isBlank()) {
                blank = true
                continue
            }
            if (out.isNotEmpty()) out.append(if (blank) "\n\n" else " ")
            out.append(line.trim())
            blank = false
        }
        return out.toString()
    }

    /**
     * 第一个「像键值分隔」的冒号。
     *
     * 不能简单 `indexOf(':')`：`description: 攻防演练：发送一个靶标` 里
     * 第二个冒号在值里；而 `https://…` 的冒号后面没有空格。
     * 只认「后面是空白或行尾」的冒号，并且跳过引号内的内容。
     */
    private fun colonOf(s: String): Int {
        var quote = ' '
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '\'' || c == '"' -> quote = c
                c == ':' && (i + 1 >= s.length || s[i + 1].isWhitespace()) -> return i
            }
            i++
        }
        return -1
    }

    private fun unquote(v: String): String {
        val t = v.trim()
        if (t.length >= 2) {
            val q = t.first()
            if ((q == '\'' || q == '"') && t.last() == q) return t.substring(1, t.length - 1)
        }
        // 行尾注释：`name: 红队模式  # 这是名字`
        val hash = t.indexOf(" #")
        return if (hash > 0) t.substring(0, hash).trim() else t
    }

    /**
     * 像不像「人给预设起的名字」。
     *
     * 要挡的是组件包名：`'@deepseek-ai/dsh-persona'`、`dsh-persona`、`./lib/a.js`。
     * 判据取「给人看的样子」：含中文，或含空格，或首字母大写的单词。
     * 全小写带短横线的标识符一律不算 —— 那是一个 id，不是名字。
     */
    private fun looksName(t: String): Boolean {
        val s = t.trim()
        if (s.length !in 2..PluginManifest.MAX_PRESET_NAME) return false
        if (s.startsWith("#")) return false
        if (s.any { it in "@/\\{}[]=;:$<>|*\"`\n" }) return false
        if (s.endsWith(".yml") || s.endsWith(".yaml") || s.endsWith(".json") || s.endsWith(".js")) return false
        if (s.any { it.code in 0x4E00..0x9FFF }) return true
        if (s.contains(' ')) return true
        return s[0].isUpperCase() && s.all { it.isLetter() || it == '-' }
    }

    // ---------------- JSON ----------------

    /**
     * `.json` 预设文件。
     *
     * 形态不固定：可能是一个数组、可能是 `{ "presets": [...] }`、
     * 也可能是一条 `{ "name": "…", "systemPrompt": "…" }`。
     * 所以递归找「对象里同时有名字字段和正文字段」的节点，找到就不再往下钻。
     */
    private fun fromJson(text: String): List<Parsed> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Parsed>()
        walk(root, out)
        return out
    }

    private fun walk(el: JsonElement, out: ArrayList<Parsed>) {
        when (el) {
            is JsonObject -> {
                val name = str(el, "name") ?: str(el, "title") ?: str(el, "displayName")
                val body = str(el, "systemPrompt") ?: str(el, "system_prompt")
                    ?: str(el, "prompt") ?: str(el, "prefix") ?: str(el, "instructions")
                if (name != null && looksName(name) && body != null && body.trim().length >= 20) {
                    out += Parsed(name, str(el, "description").orEmpty(), body.trim())
                } else {
                    el.values.forEach { walk(it, out) }
                }
            }
            is JsonArray -> el.forEach { walk(it, out) }
            else -> Unit
        }
    }

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    // ---------------- 路径 → 名字 ----------------

    /**
     * 文件里没写名字时的兜底：`presets/redteam/preset.yml` → `redteam`。
     *
     * 优先目录名，因为「一条预设一个目录」是这类包的通行写法，而文件名
     * 往往统一叫 `preset.yml` —— 拿它当名字会让四条预设全叫「preset」。
     */
    fun hintOf(path: String): String {
        val parts = path.split('/').filter { it.isNotBlank() }
        val file = parts.lastOrNull().orEmpty().substringBeforeLast('.')
        val generic = file.lowercase() in setOf("preset", "presets", "index", "config", "agent", "persona", "default")
        val raw = if (generic) parts.getOrNull(parts.size - 2).orEmpty() else file
        return raw.replace('-', ' ').replace('_', ' ').trim()
    }
}
