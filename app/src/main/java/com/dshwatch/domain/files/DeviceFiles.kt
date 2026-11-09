package com.dshwatch.domain.files

import android.os.Environment
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * 一个可以进去的位置（根）。
 *
 * 用枚举而不是写死中文标签：标签要跟着界面语言变，而「有哪些根」
 * 由这台机器上真实存在哪些目录决定 —— 两件事分开，测试里才好只测后者。
 */
enum class RootKind { WORKSPACE, DOWNLOAD, DOCUMENTS, PICTURES, DCIM, MUSIC, STORAGE }

data class FileRoot(val kind: RootKind, val dir: File)

/** 列表里的一项。`size` 对目录恒为 0 —— 算目录总大小在手表上要遍历整棵树，不值得 */
data class FileNode(
    val name: String,
    val file: File,
    val isDir: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

/**
 * 目录读取结果。
 *
 * 为什么要三态而不是「空列表」：**「这个文件夹是空的」和「我没权限看」是两件事**。
 * 混成一个空列表，用户会以为文件丢了；而这个功能存在的意义恰恰是
 * 「系统没有文件管理器时，我得知道文件到底在不在」。
 */
sealed interface Listing {
    /** [truncated] > 0 表示这个目录里还有那么多项没显示（见 [DeviceFiles.MAX_ITEMS]） */
    data class Ok(val nodes: List<FileNode>, val truncated: Int = 0) : Listing
    data class Denied(val path: String) : Listing
    data object Missing : Listing
}

/**
 * 内置文件浏览器用的目录读取。
 *
 * ## 为什么要有这个东西
 *
 * 一部分手表 ROM 根本没装文件管理器，`ACTION_OPEN_DOCUMENT` 抛
 * `ActivityNotFoundException`，`ActivityResultContracts.OpenDocument()` 直接
 * 拿不到结果 —— 用户看到的是「点＋文件，什么都没发生」。附件功能因此完全不可用。
 *
 * 这里给一条不依赖任何第三方 App 的读取通路：用 `java.io.File` 直接列目录，
 * 选中的文件走 `Uri.fromFile` 进 [com.dshwatch.data.attachment.AttachmentStore]。
 *
 * ## 权限的现实（这一点别自欺欺人）
 *
 * | Android | 能列 `/sdcard` 的条件 |
 * |---|---|
 * | 8 / 9（API 28–29） | `READ_EXTERNAL_STORAGE` 运行时权限 |
 * | 10（29） | 同上 + `requestLegacyExternalStorage` |
 * | 11+（30+） | **只有**「所有文件访问」(`MANAGE_EXTERNAL_STORAGE`) |
 *
 * Android 11 起，按路径读别的应用写进 `Download` 的非媒体文件会被 FUSE 直接拒绝，
 * 连列目录都是空的。所以本模块只负责「读」，能不能读由 [StorageAccess] 回答，
 * 读不到就老实返回 [Listing.Denied]，让界面去引导授权 —— 而不是假装目录是空的。
 *
 * 应用自己的目录（工作空间）永远可读，不需要任何权限，这是保底通路。
 */
object DeviceFiles {

    /**
     * 单次最多列这么多项。
     *
     * 手表上一个列表滚过 400 张卡片已经是灾难，而 `Download` 里堆几百个文件
     * 很常见。截断比卡死好，界面会明确写「还有 N 项未显示」。
     */
    const val MAX_ITEMS = 400

    /** 中文按拼音、英文按字母，大小写不敏感；系统语言无关，固定用中文排序规则 */
    private val collator: Collator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }

    /**
     * 这台机器上值得摆出来的起点。
     *
     * 顺序即重要性：工作空间（永远可读）→ 下载（文件最常落这里）→ 文档 → 图片 →
     * 相机 → 音乐 → 存储根。不存在的目录直接跳过 —— 摆一堆点进去是空的入口
     * 比不摆更糟。
     *
     * @param workspace 应用的工作空间目录，由调用方给（可能是内部也可能是外部私有）
     */
    fun roots(workspace: File?): List<FileRoot> = buildList {
        workspace?.let { dir ->
            /*
             * 工作空间目录是**用到才建**的（`WorkspaceStore.rootPath()` 不负责 mkdir，
             * 第一次写文件时才创建）。新装的应用如果还没让 agent 干过活，
             * 这里不补一次就会少了唯一一个不需要权限就能进的起点 ——
             * 列表空着一个入口，用户会以为文件浏览器坏了。
             */
            if (!dir.isDirectory) runCatching { dir.mkdirs() }
            if (dir.isDirectory) add(FileRoot(RootKind.WORKSPACE, dir))
        }
        val external = externalRoot()
        if (external != null) {
            fun add(kind: RootKind, name: String) {
                val dir = File(external, name)
                if (dir.isDirectory) add(FileRoot(kind, dir))
            }
            add(RootKind.DOWNLOAD, Environment.DIRECTORY_DOWNLOADS)
            add(RootKind.DOCUMENTS, Environment.DIRECTORY_DOCUMENTS)
            add(RootKind.PICTURES, Environment.DIRECTORY_PICTURES)
            add(RootKind.DCIM, Environment.DIRECTORY_DCIM)
            add(RootKind.MUSIC, Environment.DIRECTORY_MUSIC)
            add(FileRoot(RootKind.STORAGE, external))
        }
    }

    /**
     * 外部存储根。
     *
     * `getExternalStorageDirectory()` 从 API 29 起标了 deprecated，理由是多用户 /
     * 可移动存储场景下应该用 `getExternalFilesDirs()`。但我们这里要的恰恰是
     * **公共**那一片（用户插数据线看到的那一层），没有替代 API。另外主存储被
     * 摘掉（罕见的 OTG 场景）时它会返回一个不存在的路径，所以还要 isDirectory 兜一下。
     */
    @Suppress("DEPRECATION")
    fun externalRoot(): File? = runCatching {
        Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory }
    }.getOrNull()

    /**
     * 列一个目录。
     *
     * `listFiles()` 在无权限 / IO 错误时返回 null（不是抛异常），而
     * `SecurityException` 在个别 ROM 上仍会出现，两条都要接。
     */
    fun list(dir: File): Listing {
        if (!dir.exists()) return Listing.Missing
        val children = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            null
        } ?: return Listing.Denied(dir.absolutePath)

        val nodes = children.asSequence()
            // 隐藏文件（.nomedia / .thumbnails / .trashed-*）对用户没有意义，
            // 列出来只会把真正的文件挤到第二屏
            .filter { !it.name.startsWith(".") }
            .mapNotNull { f ->
                runCatching {
                    FileNode(
                        name = f.name,
                        file = f,
                        isDir = f.isDirectory,
                        size = if (f.isDirectory) 0L else f.length(),
                        modifiedAt = f.lastModified(),
                    )
                }.getOrNull()
            }
            .toList()

        val sorted = sort(nodes)
        return if (sorted.size > MAX_ITEMS) {
            Listing.Ok(sorted.take(MAX_ITEMS), truncated = sorted.size - MAX_ITEMS)
        } else {
            Listing.Ok(sorted)
        }
    }

    /** 目录在前、同类按名称排。抽成纯函数是为了能在 JVM 单测里钉住顺序 */
    fun sort(nodes: List<FileNode>): List<FileNode> = nodes.sortedWith(
        compareByDescending<FileNode> { it.isDir }.thenBy(collator) { it.name },
    )

    /**
     * 这个文件能不能真的读出字节。
     *
     * 只判断 `canRead()` 会骗人：Android 11+ 上 FUSE 的拒绝发生在 `open()`，
     * `canRead()` 仍返回 true。真开一次流才算数 —— 但只对 32KB 以内的小文件做，
     * 挑一张 2GB 的视频不该先读一遍。
     */
    fun canOpen(file: File): Boolean = runCatching {
        if (!file.isFile) return false
        if (file.length() > 32L * 1024) return file.canRead()
        file.inputStream().use { it.read() }
        true
    }.getOrDefault(false)
}
