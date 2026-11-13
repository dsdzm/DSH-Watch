package com.dshwatch.domain.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * 从 `.tgz` / `.tar.gz` 里取一份声明式清单。
 *
 * ## 为什么要支持压缩包
 *
 * npm 生态里一个插件的分发单位就是 `npm pack` 出来的 tgz。用户拿到手的
 * 往往不是一个能直接看的 JSON，而是 `dsh-plugin-xxx-1.0.0.tgz` ——
 * 让他「解压、找到里面那个 json、复制出来、粘进输入框」四步，等于劝退。
 *
 * 但**能读 tgz 不等于能跑 npm 包**。这两件事在本项目里必须分开：
 *
 * | 包里有什么 | 结果 |
 * |---|---|
 * | `dsh-plugin.json`（我们的声明格式） | 读出来，当普通清单导入 |
 * | 只有 `package.json` + JS 源码（Cordis 插件） | **拒收**，并说明为什么 |
 *
 * 第二种必须明确报错而不是「尽力而为」。一个 Cordis 插件里确实有
 * `apply(ctx)`、`package.json` 和一堆 JS，看起来「内容很全」——
 * 如果静默导入了 package.json 的 name 当作插件，用户会以为它生效了，
 * 然后发现什么也没变。见 [Scan.NpmOnly] 的文案。
 *
 * ## 为什么自己写 tar
 *
 * Android 没有任何 tar 相关的公开 API（`java.util.zip` 只有 zip/gzip）。
 * 引一个 Apache Commons Compress 是 600 KB，而这里要读的只是
 * 「512 字节头 + 内容 + 补零」这一种结构。代价最低的做法是自己走一遍块头。
 *
 * 只读、不落盘、不解到目录 —— 整个包在内存里走一遍，只留下两份 JSON。
 * 这同时消掉了 tar 最经典的那类漏洞：路径穿越（`../../`）、绝对路径、
 * 符号链接指向 /system。因为我们**根本不创建任何文件**。
 */
object PluginArchive {

    /** 压缩包本身的上限。npm 包再大也不会超过这个数，超了多半是选错了文件 */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** 解压后的上限。防 gzip 炸弹：2 MB 能解出几个 GB */
    const val MAX_UNPACKED = 4 * 1024 * 1024

    /** 条目数上限。正常插件包几十个文件，几千个说明不是插件包 */
    private const val MAX_ENTRIES = 512

    /**
     * 单个条目读进内存的上限。
     *
     * 清单本身限 8 000 字符，64 KB 已经宽出八倍；`package.json` 也远小于这个数。
     * 超过的一律当「不是我要的」跳过 —— 不必为了看一个 1 MB 的 README 把它读进来。
     */
    private const val MAX_MEMBER = 64 * 1024

    private const val BLOCK = 512

    /** 包内清单的文件名。位置不限，取路径最短的那一份 */
    const val MANIFEST_NAME = "dsh-plugin.json"

    /**
     * 解析一个包。
     *
     * 也接受裸 JSON：有人会把 `.json` 改名成 `.tgz` 传上来，
     * 按内容判断比按扩展名判断可靠。
     */
    fun parse(bytes: ByteArray, source: String = ""): PluginLoader.Parsed {
        val where = if (source.isBlank()) "这个文件" else source
        if (bytes.isEmpty()) return PluginLoader.Parsed.Err("$where 是空的。")
        if (bytes.size > MAX_BYTES) {
            return PluginLoader.Parsed.Err(
                "$where 有 ${bytes.size / 1024} KB，超过 ${MAX_BYTES / 1024 / 1024} MB 上限。" +
                    "插件包不该这么大 —— 确认一下选的是不是安装包或视频。",
            )
        }

        val tar = when {
            isGzip(bytes) -> gunzip(bytes) ?: return PluginLoader.Parsed.Err(
                "$where 解压失败，或者解压后超过 ${MAX_UNPACKED / 1024 / 1024} MB。" +
                    "文件可能损坏了，重新下一次。",
            )
            isTar(bytes) -> bytes
            else -> {
                // 没压缩的 JSON：按文本走原来的通道
                val text = String(bytes, Charsets.UTF_8).trim()
                if (text.startsWith("{")) return PluginLoader.parse(text, source)
                return PluginLoader.Parsed.Err(
                    "$where 认不出来：既不是 .tgz / .tar.gz，也不是 JSON 清单。" +
                        "插件包装成 gzip，或者直接放一份 .json。",
                )
            }
        }

        return when (val found = scan(tar)) {
            is Scan.Found -> PluginLoader.parse(found.text, source.ifBlank { found.path })
            is Scan.Oversized -> PluginLoader.Parsed.Err(
                "$where 里的 ${found.path} 解出来有 ${found.size / 1024} KB。" +
                    "清单上限是 ${PluginManifest.MAX_CHARS} 个字符 —— 这不是一份声明，是一篇文章。",
            )
            is Scan.NpmOnly -> PluginLoader.Parsed.Err(
                "$where 里只有 package.json，没有 $MANIFEST_NAME —— 这是要在 Node 里跑的 " +
                    "Cordis 插件（${found.name}）。手表上没有 Node 运行时，装不了。" +
                    "如果作者另外提供了声明式清单，让他把 $MANIFEST_NAME 一起打进包里。",
            )
            Scan.Nothing -> PluginLoader.Parsed.Err(
                "$where 里没找到 $MANIFEST_NAME。声明式插件的包长这样：" +
                    "`package/$MANIFEST_NAME`（可再附 package.json、README.md）。" +
                    "如果你手上是一份 .json，直接在「选文件」里选它，不用打包。",
            )
        }
    }

    // ---------------- 格式判断 ----------------

    private fun isGzip(b: ByteArray): Boolean =
        b.size >= 2 && (b[0].toInt() and 0xff) == 0x1f && (b[1].toInt() and 0xff) == 0x8b

    private fun isTar(b: ByteArray): Boolean =
        b.size >= 262 && String(b, 257, 5, Charsets.US_ASCII) == "ustar"

    /**
     * 解压，并且**边解边数**。
     *
     * 不能先 `readBytes()` 再判长度：gzip 炸弹在那一步就已经把内存吃掉了。
     * 每读 8 KB 检查一次总量，超了立刻放弃。
     */
    private fun gunzip(bytes: ByteArray): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_UNPACKED) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()

    // ---------------- 扫描 ----------------

    private sealed interface Scan {
        data class Found(val path: String, val text: String) : Scan

        /** 找到了清单，但大得离谱 —— 与「没有清单」区分开，否则提示会指错方向 */
        data class Oversized(val path: String, val size: Int) : Scan

        /** 包里有 package.json，但没有我们的清单 —— 最可能是官方 Cordis 插件 */
        data class NpmOnly(val name: String) : Scan

        data object Nothing : Scan
    }

    private fun scan(tar: ByteArray): Scan {
        val manifests = ArrayList<Pair<String, ByteArray>>()
        var pkgName: String? = null
        var oversized: Pair<String, Int>? = null

        var pos = 0
        var entries = 0
        // pax / GNU 长文件名：只对**紧随其后**的那个条目生效，用完即清
        var pendingPath: String? = null
        var pendingLong: String? = null

        while (pos + BLOCK <= tar.size && entries < MAX_ENTRIES) {
            if (isZeroBlock(tar, pos)) break

            val rawName = readString(tar, pos, 100)
            val size = readOctal(tar, pos + 124, 12)
            val type = tar[pos + 156].toInt()
            val magic = readString(tar, pos + 257, 6)
            val prefix = if (magic == "ustar") readString(tar, pos + 345, 155) else ""
            val dataStart = pos + BLOCK

            // 截断或荒谬的长度：不要再往下走了，剩下的全是垃圾
            if (size < 0 || dataStart.toLong() + size > tar.size) break

            val dataLen = size.toInt()
            val body = if (dataLen in 1..MAX_MEMBER) {
                tar.copyOfRange(dataStart, dataStart + dataLen)
            } else {
                null
            }

            when (type) {
                'L'.code -> pendingLong = body?.toString(Charsets.UTF_8)?.trimEnd('\u0000')
                'x'.code -> pendingPath = body?.toString(Charsets.UTF_8)?.let(::paxPath)
                'g'.code -> Unit // 全局 pax 头，只会写默认值，这里用不到
            }

            if (type == '0'.code || type == 0) {
                entries++
                val name = pendingPath ?: pendingLong ?: prefix.let {
                    if (it.isEmpty()) rawName else "$it/$rawName"
                }
                pendingPath = null
                pendingLong = null

                if (isSafe(name)) {
                    val base = name.substringAfterLast('/')
                    when {
                        base == MANIFEST_NAME && body != null -> manifests += name to body
                        base == MANIFEST_NAME -> if (oversized == null) oversized = name to dataLen
                        base == "package.json" && body != null -> if (pkgName == null) {
                            pkgName = packageName(body)
                        }
                    }
                }
            } else if (type != 'x'.code && type != 'L'.code) {
                // 目录、符号链接等：长名字只对紧随其后的文件条目有效，不留给下一个
                pendingPath = null
                pendingLong = null
            }

            pos = dataStart + roundUp(dataLen)
        }

        /*
         * 有多份清单时取**路径最短**的那一份（层级最浅）。
         *
         * npm 包的结构是 `package/dsh-plugin.json`，而有些作者会在
         * `examples/` 或 `test/fixtures/` 下再放几份示例清单。取最短路径
         * 等于取「包根那一份」，这也符合打包人的本意。
         */
        manifests.minByOrNull { it.first.length }?.let { (path, data) ->
            return Scan.Found(path, String(data, Charsets.UTF_8))
        }
        oversized?.let { (path, size) -> return Scan.Oversized(path, size) }
        if (pkgName != null) return Scan.NpmOnly(pkgName)
        return Scan.Nothing
    }

    // ---------------- tar 细节 ----------------

    private fun isZeroBlock(b: ByteArray, off: Int): Boolean {
        for (i in off until off + BLOCK) if (b[i].toInt() != 0) return false
        return true
    }

    /** 定长字段：读到 NUL 为止 */
    private fun readString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = minOf(off + len, b.size)
        while (end < limit && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /**
     * 八进制数字段。
     *
     * 实际文件里这段经常是「空格补齐 + 尾部 NUL」，也可能整个字段是空的（表示 0）。
     * 遇到非八进制字符就停下，不抛异常 —— 一个坏头不该让整个包读不了。
     */
    private fun readOctal(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        val limit = minOf(off + len, b.size)
        for (i in off until limit) {
            val c = b[i].toInt() and 0xff
            if (c == 0 || c == ' '.code) {
                if (v != 0L) break else continue
            }
            if (c < '0'.code || c > '7'.code) break
            v = v * 8 + (c - '0'.code)
        }
        return v
    }

    private fun roundUp(size: Int): Int = ((size + BLOCK - 1) / BLOCK) * BLOCK

    /**
     * 路径安全检查。
     *
     * 这里其实不写盘，所以穿越路径伤不到文件系统；但**一个含 `..` 的条目名
     * 说明这个包不是正常工具打出来的**，也就没有理由从里面取清单。
     * 顺手挡掉绝对路径与 Windows 分隔符，免得后面的最短路径比较被带偏。
     */
    private fun isSafe(name: String): Boolean {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains('\\')) return false
        return name.split('/').none { it == ".." }
    }

    /** pax 头：每行是 `<长度> <key>=<value>\n`，长度包含自身 */
    private fun paxPath(text: String): String? {
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0) break
            val end = minOf(i + len, text.length)
            val rec = text.substring(sp + 1, end)
            val eq = rec.indexOf('=')
            if (eq > 0 && rec.substring(0, eq) == "path") {
                return rec.substring(eq + 1).trimEnd('\n', '\u0000')
            }
            i += len
        }
        return null
    }

    /** 从 package.json 里抠 name —— 只为了把错误提示写具体，不解析整个 JSON */
    private fun packageName(b: ByteArray): String? = runCatching {
        val text = String(b, Charsets.UTF_8)
        Regex("\"name\"\\s*:\\s*\"([^\"]{1,80})\"").find(text)?.groupValues?.get(1)
    }.getOrNull()
}
