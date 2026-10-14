package com.dshwatch.data.workspace

import com.dshwatch.core.Logx
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Agent 的本地工作空间。
 *
 * 手表上没有 shell、也没有可直接写的公共存储，所以给 agent 一块**应用私有的目录**
 * 来创建、读取、修改文件，再由 [com.dshwatch.domain.agent.build.ProjectBuilder]
 * 做校验和打包 —— 这样模型就能在手腕上真正「产出东西」，而不是只能写剪贴板。
 *
 * ## 安全边界（这个类的核心职责）
 *
 * 模型给出的路径是不可信输入，必须挡住：
 *  1. `../` 向上穿越到应用目录甚至系统目录；
 *  2. 指向沙盒之外的绝对路径（`/etc/passwd`）；
 *  3. 通过符号链接跳出沙盒；
 *  4. 单个文件 / 文件总数 / 总容量无上限，把 64GB 表盘写满。
 *
 * 实现上：先用 [WorkspacePaths] 归一化，再用 `canonicalPath` 做前缀校验
 * （能同时挡住符号链接），最后对大小和数量做硬上限。
 *
 * ## 历史脏数据的自愈（[healLocked]）
 *
 * 早期版本会把模型给的 `data/data/<pkg>/files/workspace/index.html`
 * （没有前导斜杠，语法上是合法相对路径）真的写成深层嵌套目录。
 * 现在这个自愈逻辑挂在**每一个访问入口**上，而不是只在启动时跑一次 ——
 * 因为「启动时跑过了」这个前提在真机上并不成立（覆盖安装后进程可能不重建、
 * 也可能用户在自愈完成前就点了构建）。
 */
class WorkspaceStore(
    initialRoot: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    /**
     * 当前根目录。
     * 用 `@Volatile` 是因为 [relocate] 会在运行时替换它，而读取方散落在 IO 线程与界面线程。
     */
    @Volatile
    private var root: File = initialRoot

    data class Entry(
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val updatedAt: Long,
    )

    fun rootDir(): File = root

    fun rootPath(): String = root.absolutePath

    // ---------------- 查询 ----------------

    suspend fun list(dir: String = ""): List<Entry> = withContext(io) {
        mutex.withLock {
            healLocked()
            val target = resolve(dir) ?: return@withLock emptyList()
            if (!target.isDirectory) return@withLock emptyList()
            target.listFiles()
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                ?.map { it.toEntry() }
                ?: emptyList()
        }
    }

    /** 递归列出所有文件（不含目录） */
    suspend fun listFilesRecursive(dir: String = ""): List<Entry> = withContext(io) {
        mutex.withLock {
            // 列目录是最常用的入口（构建、界面都用它），在这里自愈性价比最高：
            // 模型看到的、用户看到的，永远都是修好之后的干净路径。
            healLocked()
            val target = resolve(dir) ?: return@withLock emptyList()
            if (!target.isDirectory) return@withLock emptyList()
            target.walkTopDown()
                .onEnter { it == target || !it.name.startsWith(".") } // 跳过隐藏目录（如 .tmp）
                .filter { it.isFile }
                .take(MAX_FILES)
                .map { it.toEntry() }
                .sortedBy { it.path }
                .toList()
        }
    }

    suspend fun exists(path: String): Boolean = withContext(io) {
        mutex.withLock { resolveForRead(path)?.exists() == true }
    }

    suspend fun read(path: String, maxChars: Int = MAX_READ_CHARS): String? = withContext(io) {
        mutex.withLock {
            healLocked()
            val file = resolveForRead(path) ?: return@withLock null
            if (!file.isFile) return@withLock null
            runCatching {
                if (file.length() > MAX_FILE_BYTES) {
                    // 超限文件截断返回而不是直接拒绝，否则模型对它完全没法处理。
                    // 注意这里没法用 InputStream.readNBytes —— 那是 API 33 才有的。
                    readHead(file, MAX_FILE_BYTES.toInt()).take(maxChars)
                } else {
                    file.readText().take(maxChars)
                }
            }.onFailure { Logx.e(TAG, "读取 $path 失败", it) }.getOrNull()
        }
    }

    suspend fun write(path: String, content: String): Entry? = withContext(io) {
        mutex.withLock {
            healLocked()
            val file = resolve(path) ?: return@withLock null
            if (file.isDirectory) return@withLock null
            val bytes = content.toByteArray()
            if (bytes.size > MAX_FILE_BYTES) return@withLock null
            if (!ensureBudget(bytes.size.toLong(), replacing = if (file.exists()) file.length() else 0L)) {
                return@withLock null
            }
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(bytes)
                moveFile(tmp, file, overwrite = true)
                file.toEntry()
            }.onFailure { Logx.e(TAG, "写入 $path 失败", it) }.getOrNull()
        }
    }

    suspend fun append(path: String, content: String): Entry? = withContext(io) {
        mutex.withLock {
            healLocked()
            val file = resolve(path) ?: return@withLock null
            val existing = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
            val merged = existing + content
            if (merged.toByteArray().size > MAX_FILE_BYTES) return@withLock null
            if (!ensureBudget(merged.toByteArray().size.toLong(), replacing = existing.toByteArray().size.toLong())) {
                return@withLock null
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(merged)
                file.toEntry()
            }.onFailure { Logx.e(TAG, "追加 $path 失败", it) }.getOrNull()
        }
    }

    suspend fun mkdir(path: String): Boolean = withContext(io) {
        mutex.withLock {
            healLocked()
            val dir = resolve(path) ?: return@withLock false
            runCatching { dir.mkdirs() || dir.isDirectory }.getOrDefault(false)
        }
    }

    /**
     * 移动 / 重命名。目标父目录会自动创建。
     * 目标已存在时**拒绝**（不覆盖）—— 覆盖是破坏性操作，让模型显式先删再移更安全。
     */
    suspend fun move(from: String, to: String): Boolean = withContext(io) {
        mutex.withLock {
            healLocked()
            val src = resolve(from) ?: return@withLock false
            if (!src.exists()) return@withLock false
            val dst = resolve(to) ?: return@withLock false
            if (dst.exists()) return@withLock false
            dst.parentFile?.mkdirs()
            moveFile(src, dst, overwrite = false)
        }
    }

    suspend fun delete(path: String): Boolean = withContext(io) {
        mutex.withLock {
            healLocked()
            val file = resolve(path) ?: return@withLock false
            if (file == root) return@withLock false // 不允许删根
            runCatching { file.deleteRecursively() }.getOrDefault(false)
        }
    }

    suspend fun totalBytes(): Long = withContext(io) {
        mutex.withLock {
            if (!root.exists()) return@withLock 0L
            var total = 0L
            root.walkTopDown().forEach { if (it.isFile) total += it.length() }
            total
        }
    }

    suspend fun fileCount(): Int = withContext(io) {
        mutex.withLock { if (root.exists()) root.walkTopDown().count { it.isFile } else 0 }
    }

    /**
     * 给界面/外部用的路径 → File。
     *
     * 先按「存在」解析（含历史脏数据容错），解析不出来再退回规范化后的目标位置 ——
     * 调用方有时需要的是「这个文件应该在哪」而不是「这个文件在不在」。
     */
    /**
     * 列出某个目录下的子目录名。
     * 「选择会话文件位置」时用它给候选 —— 用户能看见已经有哪些项目目录，而不是盲填。
     */
    suspend fun listDirs(parent: String): List<String> = withContext(io) {
        mutex.withLock {
            healLocked()
            val base = if (parent.isBlank()) root else resolve(parent) ?: return@withLock emptyList()
            if (!base.isDirectory) return@withLock emptyList()
            base.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        }
    }

    fun resolveForUi(path: String): File? = resolveForRead(path) ?: resolve(path)

    // ---------------- 路径诊断 ----------------

    /**
     * 给出路径不合法的**具体原因**。
     *
     * 之前工具失败时只会说「路径越界，不允许 ../ 或绝对路径」，而模型实际写的是
     * `data/data/<pkg>/files/workspace/index.html` —— 既没有 `../` 也不以 `/` 开头，
     * 于是模型完全不知道自己错在哪，只能反复重试。
     *
     * @return null 表示路径合法
     */
    fun describeIssue(path: String): String? {
        val text = path.trim().replace('\\', '/')
        if (text.isEmpty()) return null
        if (text.any { it.isISOControl() }) return "路径里含有控制字符"
        if (WorkspacePaths.normalize(path, root.absolutePath) != null) return null

        return when {
            text.startsWith("/") ->
                "不允许使用绝对路径「$text」。请改成相对于工作空间的路径，例如 index.html 或 src/app.js"

            text.split('/').any { it == ".." } ->
                "不允许用 .. 向上跳出工作空间（$text）"

            else -> "路径非法：$text"
        }
    }

    // ---------------- 维护 ----------------

    /**
     * 修复历史脏数据（对外入口，自带锁）。
     *
     * @return 搬迁的文件数
     */
    suspend fun selfHeal(): Int = withContext(io) {
        mutex.withLock { healLocked() }
    }

    /**
     * 切换工作空间的存放位置，并把已有文件搬过去。
     *
     * @return 搬迁的文件数；`0` 表示目标与当前相同；`-1` 表示目标不可用（保持原位置）
     */
    suspend fun relocate(newRoot: File): Int = withContext(io) {
        mutex.withLock {
            if (newRoot.absolutePath == root.absolutePath) return@withLock 0

            // 外部存储可能没挂载、或者处于只读。
            // 目标写不进去就干脆别切 —— 否则 root 会指向一个不能写的目录，
            // 之后所有文件类工具体调用全部失败，比留在原位置糟糕得多。
            if (!isUsable(newRoot)) {
                Logx.w(TAG, "目标目录不可写，保持当前位置：${newRoot.absolutePath}")
                return@withLock -1
            }

            val oldRoot = root
            var moved = 0
            if (oldRoot.exists()) {
                runCatching {
                    oldRoot.walkTopDown()
                        .onEnter { it == oldRoot || !it.name.startsWith(".") }
                        .filter { it.isFile }
                        .forEach { file ->
                            val rel = file.relativeTo(oldRoot).path
                            val dst = File(newRoot, rel)
                            if (dst.exists()) return@forEach
                            if (moveFile(file, dst, overwrite = false)) moved++
                        }
                }.onFailure { Logx.e(TAG, "迁移文件失败", it) }
            }

            root = newRoot
            root.mkdirs()
            pruneEmptyDirsOf(oldRoot)
            Logx.i(TAG, "工作空间已迁移到 ${newRoot.absolutePath}，搬迁 $moved 个文件")
            moved
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 自愈的核心实现 —— **不加锁**，只能在已持有 [mutex] 时调用。
     *
     * 早期版本会把模型给的 `data/data/<pkg>/files/workspace/index.html`
     * 当成相对路径，真的写进 `<root>/data/data/<pkg>/files/workspace/` 这种深层嵌套。
     * 于是文件明明在，`build_project(entry = "index.html")` 却报「找不到入口文件」，
     * 报错里还列出一条匪夷所思的长路径。
     *
     * 这里把这些文件搬回它们本来该在的位置，再删掉空壳目录。幂等，没有脏数据时只做一次
     * 轻量的目录存在性判断。
     *
     * @return 搬迁的文件数
     */
    private fun healLocked(): Int {
        if (!root.exists()) return 0
        // 快速路径：脏数据的特征就是根目录下凭空多了一层 `data/`。
        // 没有它就直接返回 —— 正因如此，这个函数才能放心地挂到每一个访问入口上。
        if (!File(root, LEGACY_SHELL_TOP).exists()) return 0

        val rootAbs = root.absolutePath
        val moves = ArrayList<Pair<File, File>>()
        runCatching {
            root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(root).path.replace('\\', '/')
                    val target = WorkspacePaths.stripNestedShell(rel, rootAbs) ?: return@forEach
                    if (target.isEmpty() || target == rel) return@forEach
                    moves += file to File(root, target)
                }
        }.onFailure { Logx.e(TAG, "扫描历史脏数据失败", it) }

        // 注意：这里**不能**无条件清理空目录 —— 根下真的存在一个用户自建的
        // 空目录（比如刚 make_dir 出来的）时，那样会把它删掉。
        if (moves.isEmpty()) return 0

        var moved = 0
        moves.forEach { (src, dst) ->
            // 同名文件已存在时保留原有文件，不覆盖用户/模型后来写对的那份
            if (dst.exists()) return@forEach
            if (moveFile(src, dst, overwrite = false)) moved++
        }
        pruneEmptyDirs()
        Logx.i(TAG, "自愈完成：搬迁 $moved 个文件")
        return moved
    }

    /** 规范化后的沙盒内文件；null 表示路径不允许 */
    private fun resolve(path: String): File? {
        val rel = WorkspacePaths.normalize(path, root.absolutePath) ?: return null
        return toSafeFile(rel)
    }

    /**
     * 读取专用的容错解析。
     *
     * 归一化路径不存在时依次尝试：
     *  1. 「原样相对路径」—— 早期版本把绝对路径当相对路径写歪后的真实位置；
     *  2. 在嵌套壳里找「剥壳后正好等于目标」的文件。
     *
     * **写操作绝不会走这条回退**，否则会继续产生嵌套目录。
     */
    private fun resolveForRead(path: String): File? {
        val rel = WorkspacePaths.normalize(path, root.absolutePath)
        if (rel != null) {
            toSafeFile(rel)?.let { if (it.exists()) return it }
        }

        val legacy = path.trim().replace('\\', '/').trimStart('/')
        if (legacy.isNotEmpty() && legacy.split('/').none { it == ".." }) {
            toSafeFile(legacy)?.let { if (it.exists()) return it }
        }

        if (rel != null) findInNestedShell(rel)?.let { return it }
        return null
    }

    /** 在历史脏数据的嵌套壳里找「剥壳后等于 [rel]」的文件 */
    private fun findInNestedShell(rel: String): File? {
        if (!root.exists()) return null
        val rootAbs = root.absolutePath
        return runCatching {
            root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .firstOrNull { file ->
                    if (!file.isFile) return@firstOrNull false
                    val relChild = file.relativeTo(root).path.replace('\\', '/')
                    WorkspacePaths.stripNestedShell(relChild, rootAbs) == rel
                }
        }.getOrNull()
    }

    private fun toSafeFile(rel: String): File? {
        if (!root.exists()) root.mkdirs()
        val candidate = if (rel.isEmpty()) root else File(root, rel)
        return runCatching {
            val rootCanonical = root.canonicalFile
            val targetCanonical = candidate.canonicalFile
            val rootPrefix = rootCanonical.path + File.separator
            // canonicalPath 已经解开了符号链接，所以这一步同时挡住了软链跳出
            if (targetCanonical.path != rootCanonical.path && !targetCanonical.path.startsWith(rootPrefix)) {
                Logx.w(TAG, "拒绝越界路径：$rel")
                null
            } else {
                targetCanonical
            }
        }.getOrNull()
    }

    /** 目标目录能不能真的写东西（外部存储可能未挂载 / 只读） */
    private fun isUsable(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return@runCatching false
        val probe = File(dir, ".dsh-probe")
        probe.writeText("ok")
        val ok = probe.readText() == "ok"
        probe.delete()
        ok
    }.getOrDefault(false)

    /**
     * 移动文件。
     * 内部存储 → 外部存储属于跨挂载点，`renameTo` 会失败，这时退回「复制 + 删除」。
     */
    private fun moveFile(src: File, dst: File, overwrite: Boolean): Boolean {
        if (dst.exists() && !overwrite) return false
        if (runCatching { src.renameTo(dst) }.getOrDefault(false)) return true
        return runCatching {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = overwrite)
            src.delete()
            true
        }.onFailure { Logx.e(TAG, "移动 ${src.path} → ${dst.path} 失败", it) }
            .getOrDefault(false)
    }

    private fun pruneEmptyDirs() = pruneEmptyDirsOf(root)

    private fun pruneEmptyDirsOf(base: File) {
        if (!base.exists()) return
        runCatching {
            base.walkBottomUp()
                .filter { it.isDirectory && it != base }
                .forEach { dir ->
                    val children = dir.listFiles()
                    if (children != null && children.isEmpty()) dir.delete()
                }
        }
    }

    private fun ensureBudget(incoming: Long, replacing: Long): Boolean {
        val files = if (root.exists()) root.walkTopDown().count { it.isFile } else 0
        if (files >= MAX_FILES && replacing == 0L) {
            Logx.w(TAG, "工作空间文件数已达上限 $MAX_FILES")
            return false
        }
        var total = 0L
        if (root.exists()) root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        val projected = total - replacing + incoming
        if (projected > MAX_TOTAL_BYTES) {
            Logx.w(TAG, "工作空间容量超限：$projected > $MAX_TOTAL_BYTES")
            return false
        }
        return true
    }

    /** 只读文件开头 n 个字节（API 28 安全，不用 readNBytes） */
    private fun readHead(file: File, n: Int): String {
        val buf = ByteArray(n)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < buf.size) {
                val r = input.read(buf, total, buf.size - total)
                if (r <= 0) break
                total += r
            }
            total
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun File.toEntry(): Entry {
        val rel = runCatching {
            path.removePrefix(root.absolutePath).removePrefix(File.separator)
        }.getOrDefault(name)
        return Entry(
            path = rel,
            isDir = isDirectory,
            size = if (isFile) length() else 0L,
            updatedAt = lastModified(),
        )
    }

    companion object {
        private const val TAG = "Workspace"

        /** 历史脏数据在根目录下多出来的那层壳，用来做零成本的快速判断 */
        private const val LEGACY_SHELL_TOP = "data"

        /** 单文件上限 256KB —— 手表写代码够用，也防止把内部存储写爆 */
        const val MAX_FILE_BYTES = 256 * 1024L

        /** 工作空间总容量上限 */
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

        const val MAX_FILES = 400

        /** 单次 read_file 返回给模型的最大字符数 */
        const val MAX_READ_CHARS = 24_000
    }
}
