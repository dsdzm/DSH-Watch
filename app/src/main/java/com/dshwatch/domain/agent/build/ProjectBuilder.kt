package com.dshwatch.domain.agent.build

import com.dshwatch.core.Logx
import com.dshwatch.data.workspace.WorkspacePaths
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.data.workspace.WorkspaceStore.Entry
import java.util.Base64

/**
 * 工作空间的「编译」步骤。
 *
 * 手表上没有 Kotlin/Java 编译器，所以「编译」在这里被定义为两件**确实有意义**的事：
 *
 *  1. **静态校验** —— 把所有源文件过一遍 [SourceValidator]，把语法/结构错误找出来。
 *     有错误就**不产出**任何东西，直接返回问题清单让模型去修。这是真正的把关。
 *
 *  2. **打包** —— 把入口 HTML 里引用的本地 CSS / JS / 图片**内联**成单文件，
 *     输出到 `dist/`。手表上没有浏览器、没有相对路径文件服务，
 *     不内联的话产物根本打不开，这一步等于把它变成"能直接看的东西"。
 */
object ProjectBuilder {

    data class BuildReport(
        val ok: Boolean,
        val entry: String,
        val outputPath: String?,
        val outputBytes: Long,
        val inlined: List<String>,
        val skipped: List<String>,
        val issues: List<Issue>,
        /** 本次构建前自动修复的历史脏文件数量（>0 时回显，用来确认修复是否真的生效） */
        val healed: Int = 0,
        /** 本次构建所属的会话工作目录；用来把全局路径显示成「模型视角」的相对路径 */
        val baseDir: String = "",
    ) {
        private val errors get() = issues.count { it.severity == Severity.ERROR }
        private val warnings get() = issues.count { it.severity == Severity.WARNING }

        /** 回灌给模型的文本，要短、要可执行 */
        fun toToolText(): String = buildString {
            append(if (ok) "构建成功" else "构建失败")
            append("｜入口 ").append(entry)
            if (healed > 0) append("｜已自动修复 ").append(healed).append(" 个位置错误的文件")
            if (ok && outputPath != null) {
                // 产物路径回显成「相对当前工作目录」，避免模型下次照抄一长串前缀
                append("｜产物 ").append(WorkspacePaths.stripBase(outputPath, baseDir))
                    .append("（").append(outputBytes / 1024).append(" KB）")
                append("｜内联 ").append(inlined.size).append(" 个外部文件")
            } else if (ok) {
                append("｜静态校验通过（本机无该语言的编译器，未产出可执行文件）")
            }
            append('\n')
            if (errors > 0) append("错误 ").append(errors).append(" 个；")
            if (warnings > 0) append("警告 ").append(warnings).append(" 个；")
            if (issues.isEmpty()) append("未发现问题；")
            append('\n')
            issues.take(12).forEach { append(it.render()).append('\n') }
            if (issues.size > 12) append("…还有 ").append(issues.size - 12).append(" 条未列出\n")
            if (skipped.isNotEmpty()) {
                append("跳过的文件：").append(skipped.joinToString(", ")).append('\n')
            }
            if (ok && outputPath != null) {
                append("产物已写入工作空间，用户可以在 App 的「工作空间」页里预览。")
            } else if (ok) {
                append("源码通过了静态校验。手表上没有 Python/Kotlin/Java 等语言的编译器，")
                append("无法在本机运行或产出可执行文件 —— 需要真正运行请把源码导出到电脑。")
            } else {
                append("请修正上面的错误后重新调用 build_project。")
            }
        }
    }

    private val imageMime = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
    )

    private const val MAX_INLINE_BYTES = 256 * 1024

    /** 默认产物目录；可通过 build_project 的 out_dir 参数改成别的相对路径 */
    const val DEFAULT_OUT_DIR = "dist"

    /**
     * 没指定入口时自动挑一个。
     *
     * 顺序是有讲究的：先 HTML（只有它能打包成可预览产物），
     * 再任意可校验的源文件（那样至少能替用户跑一遍静态检查）。
     * 都不满足就返回 null，由调用方给出「这里没有可校验的文件」的说明。
     */
    private fun pickEntry(candidates: Collection<String>): String? {
        val list = candidates.sorted()
        return list.firstOrNull { it == "index.html" }
            ?: list.firstOrNull { it.endsWith(".html", true) || it.endsWith(".htm", true) }
            ?: list.firstOrNull { SourceValidator.isCheckable(it) }
    }

    private fun simpleFailure(entry: String, message: String, base: String = ""): BuildReport =
        BuildReport(
            ok = false,
            entry = entry,
            outputPath = null,
            outputBytes = 0,
            inlined = emptyList(),
            skipped = emptyList(),
            issues = listOf(Issue(entry, 1, message)),
            baseDir = base,
        )

    suspend fun build(
        store: WorkspaceStore,
        entry: String?,
        outName: String? = null,
        outDir: String? = null,
        baseDir: String = "",
    ): BuildReport {
        // 构建前先自愈：早期版本会把模型给的绝对路径当成相对路径，写进
        // data/data/<pkg>/files/workspace/ 这种嵌套目录。文件其实在，只是位置不对，
        // 不搬回来的话用户会一直看到「找不到入口文件」，而且完全猜不到原因。
        // 修复数量会回显到报告里，方便确认「修复到底有没有生效」。
        val healed = store.selfHeal()
        return buildInner(store, entry, outName, outDir, baseDir).copy(healed = healed)
    }

    /**
     * @param baseDir 会话的工作目录（相对工作空间根）。所有相对路径都相对它解析，
     *   产出的 `dist/` 也落在它下面，这样多个会话的产物互不干扰。
     */
    private suspend fun buildInner(
        store: WorkspaceStore,
        entry: String?,
        outName: String? = null,
        outDir: String? = null,
        baseDir: String = "",
    ): BuildReport {
        val rootPath = store.rootPath()
        val base = baseDir.trim().trim('/')

        val rawEntry = entry?.trim().orEmpty()
        // 入口路径要走「归一化 + 落到 base 之下」：模型既可能写 index.html，
        // 也可能写带包名的绝对路径，还可能照抄上一条工具结果里的 progress/xxx/index.html。
        // 留空表示自动挑（见 pickEntry）—— 非 HTML 项目（Python / Kotlin …）常常没有「入口」这个概念。
        val entryLocal: String? = if (rawEntry.isEmpty()) {
            null
        } else {
            val g = WorkspacePaths.resolveUnder(rawEntry, base, rootPath)
                ?: return simpleFailure(
                    entry = rawEntry,
                    message = "入口路径不合法：「$rawEntry」。请使用当前工作目录下的**相对路径**，" +
                        "例如 index.html 或 mysite/index.html，不要带 /data/data/... 这类绝对路径前缀。",
                    base = base,
                )
            WorkspacePaths.stripBase(g, base)
        }

        val rawOutDir = outDir.orEmpty()
        val outDirLocal = if (rawOutDir.isBlank()) {
            DEFAULT_OUT_DIR
        } else {
            val g = WorkspacePaths.resolveUnder(rawOutDir, base, rootPath)
                ?: return simpleFailure(
                    entry = entryLocal ?: "（未指定）",
                    message = "产物目录不合法：「$rawOutDir」。请使用相对路径，例如 dist。",
                    base = base,
                )
            WorkspacePaths.stripBase(g, base).ifBlank { DEFAULT_OUT_DIR }
        }
        val outDirGlobal = if (base.isEmpty()) outDirLocal else "$base/$outDirLocal"

        // 只扫本会话目录，不会把别的会话的文件当成自己的源码
        val scopedFiles = store.listFilesRecursive(base).mapNotNull { e ->
            val local = WorkspacePaths.stripBase(e.path, base)
            if (base.isNotEmpty() && local == e.path) null else local to e
        }
        val byLocal = LinkedHashMap<String, Entry>()
        scopedFiles.forEach { (local, entry2) -> byLocal[local] = entry2 }

        // 兜底：万一自愈没能生效（例如目标目录只读），只要工作空间里存在
        // 「剥壳后正好等于入口」的文件，就直接拿它去构建 ——
        // 用户的观感应该是「构建成功了」，而不是卡在一条看不懂的报错上。
        val effectiveLocal = if (entryLocal != null) {
            // 兜底：万一自愈没能生效（例如目标目录只读），只要工作空间里存在
            // 「剥壳后正好等于入口」的文件，就直接拿它去构建 ——
            // 用户的观感应该是「构建成功了」，而不是卡在一条看不懂的报错上。
            byLocal.keys.firstOrNull { it == entryLocal }
                ?: byLocal.keys.firstOrNull { WorkspacePaths.stripNestedShell(it, rootPath) == entryLocal }
        } else {
            pickEntry(byLocal.keys)
        }

        if (effectiveLocal == null) {
            val hint = byLocal.keys.take(8).joinToString(", ")
            if (entryLocal == null) {
                return simpleFailure(
                    entry = "（无）",
                    message = "当前工作目录里没有可校验的文件。可以先用 list_files 看看有什么，" +
                        "或者用 write_file 创建源码后再调用本工具。",
                    base = base,
                )
            }
            return simpleFailure(
                entry = entryLocal ?: "（未指定）",
                message = buildString {
                    append("找不到入口文件「").append(entryLocal).append("」。")
                    append("当前工作目录")
                    append(if (base.isEmpty()) "" else "（$base）")
                    append("里的文件：").append(hint.ifBlank { "（空）" })
                    if (byLocal.isNotEmpty()) {
                        append("。路径必须是相对于当前工作目录的，且不要带 /data/data/... ")
                        append("这类绝对路径前缀，也不要带 files/workspace/ 前缀。")
                    }
                },
                base = base,
            )
        }

        // ---------- 1) 校验 ----------
        val issues = ArrayList<Issue>()
        val skipped = ArrayList<String>()
        val textByPath = HashMap<String, String>()

        byLocal.forEach { (local, file) ->
            if (local.startsWith("$outDirLocal/")) return@forEach // 产物自身不参与校验
            if (!SourceValidator.isCheckable(local)) return@forEach
            if (file.size > WorkspaceStore.MAX_FILE_BYTES) {
                skipped += "$local（超过 256KB）"
                return@forEach
            }
            val content = store.read(file.path, maxChars = Int.MAX_VALUE) ?: run {
                skipped += "$local（读取失败）"
                return@forEach
            }
            if (SourceValidator.looksBinary(content)) {
                skipped += "$local（二进制）"
                return@forEach
            }
            textByPath[local] = content
        }
        textByPath.forEach { (path, content) -> issues += SourceValidator.validate(path, content) }

        val hasErrors = issues.any { it.severity == Severity.ERROR }
        if (hasErrors) {
            return BuildReport(
                ok = false,
                entry = entryLocal ?: "（未指定）",
                outputPath = null,
                outputBytes = 0,
                inlined = emptyList(),
                skipped = skipped,
                issues = issues,
                baseDir = base,
            )
        }

        // 非 HTML 入口：**只出校验结果，不产出「产物」**。
        // 手表上没有 Python / Kotlin / Java 的编译器，硬塞一个 dist/xxx 进去只会误导用户，
        // 让他以为真编译过了。这里如实说明，比造一个假产物有价值。
        val entryIsHtml = effectiveLocal.endsWith(".html", true) || effectiveLocal.endsWith(".htm", true)
        if (!entryIsHtml) {
            return BuildReport(
                ok = true,
                entry = effectiveLocal,
                outputPath = null,
                outputBytes = 0,
                inlined = emptyList(),
                skipped = skipped,
                issues = issues,
                baseDir = base,
            )
        }

        // ---------- 2) 打包（内联外部资源） ----------
        // 先把所有文件读进内存：这样 inlineAssets 是纯函数，方便单测，
        // 也避免同样的文件被反复读盘（手表 IO 很慢）。
        val textFiles = HashMap<String, String>(textByPath)
        val binaryFiles = HashMap<String, ByteArray>()
        byLocal.forEach { (local, file) ->
            if (local.startsWith("$outDirLocal/")) return@forEach
            if (file.size > WorkspaceStore.MAX_FILE_BYTES) return@forEach
            val ext = local.substringAfterLast('.', "").lowercase()
            if (ext in imageMime) {
                runCatching { store.resolveForUi(file.path)?.readBytes() }
                    .getOrNull()
                    ?.let { binaryFiles[local] = it }
            } else if (!textFiles.containsKey(local)) {
                store.read(file.path, maxChars = Int.MAX_VALUE)?.let { textFiles[local] = it }
            }
        }

        val entryHtml = textFiles[effectiveLocal].orEmpty()
        val inlined = ArrayList<String>()
        val bundled = inlineAssets(
            entryPath = effectiveLocal,
            html = entryHtml,
            textFiles = textFiles,
            binaryFiles = binaryFiles,
            inlined = inlined,
        )

        val name = (
            outName?.ifBlank { null }
                ?: effectiveLocal.substringAfterLast('/').substringBeforeLast('.').ifBlank { "index" } + ".html"
            ).substringAfterLast('/')
        val outputLocal = "$outDirLocal/$name"
        val outputGlobal = "$outDirGlobal/$name"

        val written = store.write(outputGlobal, bundled)
        if (written == null) {
            return BuildReport(
                ok = false,
                entry = entryLocal ?: "（未指定）",
                outputPath = null,
                outputBytes = 0,
                inlined = inlined,
                skipped = skipped,
                issues = issues + Issue(
                    outputLocal,
                    1,
                    "写入产物失败：可能是工作空间容量已满（上限 32MB）或单文件超过 256KB",
                ),
                baseDir = base,
            )
        }
        Logx.d("Builder", "构建完成 $outputGlobal ${written.size} bytes")

        return BuildReport(
            ok = true,
            entry = entryLocal ?: "（未指定）",
            outputPath = outputGlobal,
            outputBytes = written.size,
            inlined = inlined,
            skipped = skipped,
            issues = issues,
            baseDir = base,
        )
    }

    /**
     * 内联入口 HTML 引用的本地资源。
     *
     * 只处理**本地相对路径**：`http(s)://`、`data:`、`//cdn...` 一律原样保留，
     * 否则会把外链错误地当成工作空间文件去找。
     */
    fun inlineAssets(
        entryPath: String,
        html: String,
        textFiles: Map<String, String>,
        binaryFiles: Map<String, ByteArray>,
        inlined: MutableList<String>,
    ): String {
        val baseDir = entryPath.substringBeforeLast('/', "")

        fun resolve(ref: String): String? {
            val clean = ref.trim().substringBefore('?').substringBefore('#')
            if (clean.isEmpty()) return null
            if (clean.startsWith("data:") || clean.startsWith("http://") || clean.startsWith("https://")) return null
            if (clean.startsWith("//")) return null
            return normalizePath(if (baseDir.isEmpty()) clean else "$baseDir/$clean")
        }

        var result = html

        // <link rel=stylesheet href=...>
        result = LINK_TAG.replace(result) { m ->
            val tag = m.value
            val attrs = parseAttributes(tag)
            val rel = attrs["rel"]?.lowercase()
            val href = attrs["href"]
            if (rel != "stylesheet" || href == null) return@replace tag
            val path = resolve(href) ?: return@replace tag
            val css = textFiles[path] ?: return@replace tag
            inlined += path
            "\n<style>\n/* inlined from $path */\n$css\n</style>\n"
        }

        // <script src=...></script>
        result = SCRIPT_TAG.replace(result) { m ->
            val tag = m.value
            val attrs = parseAttributes(tag)
            val src = attrs["src"] ?: return@replace tag
            val path = resolve(src) ?: return@replace tag
            val js = textFiles[path] ?: return@replace tag
            inlined += path
            val typeAttr = attrs["type"]?.let { " type=\"$it\"" }.orEmpty()
            val moduleAttr = if (attrs.containsKey("module")) " type=\"module\"" else typeAttr
            "\n<script$moduleAttr>\n/* inlined from $path */\n$js\n</script>\n"
        }

        // <img src=...>
        result = IMG_TAG.replace(result) { m ->
            val tag = m.value
            val attrs = parseAttributes(tag)
            val src = attrs["src"] ?: return@replace tag
            val path = resolve(src) ?: return@replace tag
            val ext = path.substringAfterLast('.', "").lowercase()
            val mime = imageMime[ext] ?: return@replace tag
            val bytes = binaryFiles[path] ?: return@replace tag
            if (bytes.size > MAX_INLINE_BYTES) return@replace tag
            inlined += path
            val dataUri = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
            tag.replaceFirst(src, dataUri)
        }

        return result
    }

    // ---------------- 工具 ----------------

    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_TAG = Regex("<script\\b[^>]*>\\s*</script>", RegexOption.IGNORE_CASE)
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val BARE_ATTR = Regex("""(^|\s)([A-Za-z_:][-A-Za-z0-9_:.]*)(?=\s|/?>)""")

    private fun parseAttributes(tag: String): Map<String, String> {
        val map = HashMap<String, String>()
        ATTR.findAll(tag).forEach { m ->
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
            map[m.groupValues[1].lowercase()] = value
        }
        BARE_ATTR.findAll(tag).forEach { m ->
            map.putIfAbsent(m.groupValues[2].lowercase(), "")
        }
        return map
    }

    /** 规范化相对路径：折叠 `.`、抵消 `..`；越界（跑到根以上）时返回空 */
    fun normalizePath(raw: String): String {
        val parts = raw.trim().replace('\\', '/').split('/')
        val out = ArrayList<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) return "" else out.removeAt(out.lastIndex)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }
}
