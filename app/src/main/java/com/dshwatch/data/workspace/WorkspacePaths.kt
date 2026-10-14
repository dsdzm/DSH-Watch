package com.dshwatch.data.workspace

/**
 * 工作空间路径归一化。
 *
 * ## 为什么需要这个类
 *
 * 模型并不真的知道自己在什么样的沙盒里，于是经常把「文件应该放哪」表达成绝对路径：
 *
 * ```
 * write_file(path = "data/data/com.dshwatch.debug/files/workspace/index.html")
 * ```
 *
 * 关键点：这串**没有前导斜杠**，所以它不是绝对路径，而是一个「看起来很长但语法合法」的相对路径。
 * 如果直接拼到工作空间根目录下，会真的建出
 * `<root>/data/data/com.dshwatch.debug/files/workspace/index.html` 这种 6 层嵌套，
 * 接着 `build_project(entry = "index.html")` 就找不到入口，报错里列出的
 * 「工作空间里的文件」也变成那一长串 —— 现象非常费解。
 *
 * 这个类负责把各种「其实指的是工作空间里某个文件」的写法收敛成标准相对路径，
 * 顺便让 [WorkspaceStore.selfHeal] 能把已经写歪的文件搬回正确位置。
 *
 * 纯函数，方便单测。
 *
 * ## 支持的形态
 *
 * | 输入 | 归一化结果 |
 * | --- | --- |
 * | `index.html` | `index.html` |
 * | `./src/app.js` | `src/app.js` |
 * | `src//app.js` | `src/app.js` |
 * | `/data/data/<pkg>/files/workspace/index.html` | `index.html` |
 * | `data/data/<pkg>/files/workspace/index.html` | `index.html` |
 * | `/data/user/0/<pkg>/files/workspace/index.html` | `index.html` |
 * | `Android/data/<pkg>/files/workspace/x.txt` | `x.txt` |
 * | `files/workspace/index.html` | `index.html` |
 * | `workspace/index.html` | `index.html` |
 * | `/etc/passwd` | 拒绝（null） |
 * | `../secret` | 拒绝（null） |
 */
object WorkspacePaths {

    /**
     * 归一化成工作空间内的相对路径。
     *
     * @return 相对路径（空串表示工作空间根）；**null 表示这个路径不允许使用**。
     */
    fun normalize(raw: String, rootAbs: String): String? {
        val text = raw.trim().replace('\\', '/')
        if (text.isEmpty()) return ""
        if (text.any { it.isISOControl() }) return null

        val rootSegs = segments(rootAbs)
        val segs = segments(text)
        if (segs.isEmpty()) return ""

        // 能剥掉「工作空间根前缀」或「files/workspace 标记」的，说明它其实指向沙盒内部
        val stripped = stripRootPrefix(segs, rootSegs) ?: stripMarker(segs)

        if (text.startsWith("/")) {
            // 绝对路径：只有确实指向本沙盒才接受，否则一律拒绝（挡住 /etc/passwd 这类）
            return if (stripped == null) null else finish(stripped)
        }

        // 相对路径：既可能是干净的（src/app.js），也可能带了伪前缀
        val tail = stripped ?: stripRootName(segs, rootSegs) ?: segs
        return finish(tail)
    }

    /**
     * 把模型给的路径解析成「工作空间内」的全局相对路径，并保证落在 [base] 之下。
     *
     * 每个会话有自己的工作目录（`progress/<id>`），模型却只会写 `index.html` 这样的
     * 短路径 —— 这个方法负责把两者接起来。之所以要**先归一化再拼前缀**，是因为
     * 顺序反了就会出问题：模型偶尔会把带包名的绝对路径（`/data/data/<pkg>/files/workspace/x`）
     * 传进来，如果先拼前缀，它会被当成普通目录名，又长出一层嵌套。
     *
     * 反过来的情况也容错：模型从 `list_files` 里看到过 `progress/abc/index.html`
     * 这种完整路径，下次直接照抄 —— 这时候不再重复拼前缀。
     *
     * @param base 会话工作目录（相对工作空间根），空串表示直接用根目录
     * @return 工作空间内的相对路径；null 表示该路径不允许使用
     */
    fun resolveUnder(raw: String, base: String, rootAbs: String): String? {
        val b = base.trim().trim('/')
        val normalized = normalize(raw, rootAbs)
        if (normalized == null) return if (raw.isBlank()) b else null
        if (normalized.isEmpty()) return if (raw.isBlank()) b else null
        if (b.isEmpty()) return normalized
        if (normalized == b || normalized.startsWith("$b/")) return normalized
        return "$b/$normalized"
    }

    /** [resolveUnder] 的反向操作：把全局相对路径转回「相对 [base] 的路径」，给模型看 */
    fun stripBase(globalPath: String, base: String): String {
        val b = base.trim().trim('/')
        if (b.isEmpty()) return globalPath
        if (globalPath == b) return ""
        return globalPath.removePrefix("$b/")
    }

    /**
     * 剥掉「嵌套壳」，用于修复历史脏数据。
     *
     * @param relPath 相对于工作空间根的路径（如 `data/data/<pkg>/files/workspace/index.html`）
     * @param rootAbs 工作空间根的绝对路径
     * @return 剥离后应该存在的相对路径；null 表示不需要搬迁
     */
    fun stripNestedShell(relPath: String, rootAbs: String): String? {
        val segs = segments(relPath.trim().replace('\\', '/'))
        if (segs.isEmpty()) return null
        val rootSegs = segments(rootAbs)
        val stripped = stripRootPrefix(segs, rootSegs)
            ?: stripMarker(segs)
            ?: stripRootName(segs, rootSegs)
            ?: return null
        if (stripped.isEmpty()) return null
        return finish(stripped)
    }

    // ---------------- 内部 ----------------

    private fun segments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /** 在段序列里定位工作空间根的段序列，砍掉它以及之前的部分 */
    private fun stripRootPrefix(segs: List<String>, rootSegs: List<String>): List<String>? {
        if (rootSegs.isEmpty() || segs.size < rootSegs.size) return null
        for (i in 0..(segs.size - rootSegs.size)) {
            if (segs.subList(i, i + rootSegs.size) == rootSegs) {
                return segs.subList(i + rootSegs.size, segs.size)
            }
        }
        return null
    }

    /** 定位 `files/workspace` 标记 —— 不同 ROM / 不同包名下这段是稳定的 */
    private fun stripMarker(segs: List<String>): List<String>? {
        for (i in 0 until segs.size - 1) {
            if (segs[i] == "files" && segs[i + 1] == "workspace") {
                return segs.subList(i + 2, segs.size)
            }
        }
        return null
    }

    /** 去掉开头的根目录名，例如 `workspace/index.html` */
    private fun stripRootName(segs: List<String>, rootSegs: List<String>): List<String>? {
        val rootName = rootSegs.lastOrNull() ?: return null
        if (segs.size > 1 && segs[0] == rootName) return segs.subList(1, segs.size)
        return null
    }

    private fun finish(segs: List<String>): String? {
        val out = ArrayList<String>(segs.size)
        for (seg in segs) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                // 归一化之后仍然出现 ..，说明它想跳出工作空间
                seg == ".." -> return null
                seg.any { it.isISOControl() } -> return null
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }
}
