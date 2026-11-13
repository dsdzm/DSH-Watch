package com.dshwatch.domain.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
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
 * 只读、不落盘、不解到目录 —— 工具永远不会创建文件，所以 tar 最经典的那类
 * 漏洞（路径穿越、绝对路径、符号链接指向 /system）在这里无从谈起。
 *
 * ## 1.22.3：上限从「整包 2 MB」改成「流式 + 32 MB」
 *
 * 之前是把整个包读进 `ByteArray` 再解析，于是必须给包大小定个死数。定 2 MB
 * 是我拍脑袋 —— 用户拿了一个 2.01 MB 的包，直接被挡在门外。
 *
 * 但真正的问题不是那个数字小，是**根本不需要把整包读进来**：
 * 我们要的只有 `dsh-plugin.json`（≤ 8 000 字符）和 `package.json`。
 * 现在改成流式：一个一个 tar 条目往前走，遇到要的读进来，不要的直接跳过。
 * 内存占用只和**单条目的读取上限**有关，与包有多大无关 —— 100 MB 的包
 * 里有 99 MB 是图片，也只是「多跳几下」。
 *
 * 剩下的限制只防两件真事：
 *  · 选错了文件（一个 2 GB 的视频）→ 压缩字节 32 MB 就停；
 *  · gzip 炸弹（几 KB 解出几十 GB）→ 解压字节 64 MB 就停。
 */
object PluginArchive {

    /**
     * 压缩数据的上限（也就是磁盘上那个文件的大小）。
     *
     * 32 MB 是「一个带截图、带示例的插件包也够用」的量级。npm 上绝大多数
     * 声明式插件包是几十 KB 到几 MB —— 10 倍余量足够，再大基本就是选错了。
     */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** 解压后的总字节上限。防 gzip 炸弹：2 MB 能解出几十 GB */
    const val MAX_UNPACKED = 64L * 1024 * 1024

    /** 条目数上限。npm 包带上 node_modules 能到几千个，再多就不像插件包了 */
    private const val MAX_ENTRIES = 4096

    /**
     * 单个条目读进内存的上限。
     *
     * 清单本身限 8 000 字符（UTF-8 最多 32 KB），`package.json` 也远小于这个数。
     * 256 KB 是留给「有人把清单写得非常长、或者 JSON 里塞了大段说明」的余量。
     * 超过的一律不读进内存，只记下大小 —— 报错时能说清是「太大」而不是「没有」。
     */
    private const val MAX_MEMBER = 256 * 1024

    /** 裸文本（未压缩 JSON）的读取上限。8 000 字符的清单远小于它 */
    private const val MAX_TEXT = 128 * 1024

    private const val BLOCK = 512

    /** 包内清单的文件名。位置不限，取路径最短的那一份 */
    const val MANIFEST_NAME = "dsh-plugin.json"

    /** 字节数组入口：测试和小文件用，内部同样走流 */
    fun parse(bytes: ByteArray, source: String = ""): PluginLoader.Parsed =
        ByteArrayInputStream(bytes).use { parse(it, source) }

    /**
     * 解析一个包。
     *
     * 也接受裸 JSON：有人会把 `.json` 改成 `.tgz` 传上来，
     * 按内容判断比按扩展名判断可靠。
     *
     * **调用方负责关闭 [input]** —— 这里不 `use`，因为 SAF 的流由上层管生命周期。
     */
    fun parse(input: InputStream, source: String = ""): PluginLoader.Parsed =
        parse(input, source, MAX_BYTES, MAX_UNPACKED)

    /**
     * 上限可注入的版本。
     *
     * 只为测试：验证「超限会停」必须真的读到上限，而 32 MB 的样本既占内存又慢。
     * 传小值就能在几 KB 内走完同一条分支。
     */
    internal fun parse(
        input: InputStream,
        source: String,
        maxPacked: Long,
        maxUnpacked: Long,
    ): PluginLoader.Parsed {
        val where = if (source.isBlank()) "这个文件" else source

        return try {
            val raw = Tallying(input, Tally(maxPacked, Kind.PACKED))
            // 先看前 512 字节决定格式，再把这段接回流的开头 ——
            // mark/reset 在部分 ROM 的 ContentResolver 流上不可靠，自己拼更稳
            val head = ByteArray(BLOCK)
            val n = raw.readUpTo(head)
            if (n == 0) return PluginLoader.Parsed.Err("$where 是空的。")
            val body = SequenceInputStream(ByteArrayInputStream(head, 0, n), raw)

            when {
                isGzip(head, n) -> {
                    val unpacked = GZIPInputStream(body)
                    // 解压后的字节单独计一份账：压缩包小不代表解出来小
                    scan(Tallying(unpacked, Tally(maxUnpacked, Kind.UNPACKED)), where, source)
                }
                isTar(head, n) -> scan(Tallying(body, Tally(maxUnpacked, Kind.UNPACKED)), where, source)
                else -> text(body, where, source)
            }
        } catch (t: TooBig) {
            PluginLoader.Parsed.Err(
                when (t.kind) {
                    Kind.PACKED -> "$where 超过 ${maxPacked / 1024 / 1024} MB 上限。" +
                        "插件包不该这么大 —— 确认一下选的是不是安装包或视频。"
                    else -> "$where 解压后超过 ${maxUnpacked / 1024 / 1024} MB。" +
                        "要么是 gzip 炸弹，要么这根本不是插件包。"
                },
            )
        } catch (t: Throwable) {
            PluginLoader.Parsed.Err("$where 读不动：${t.message.orEmpty().take(80)}。可能已经损坏。")
        }
    }

    // ---------------- 分支 ----------------

    /** 未压缩的文本：把它当清单直接解析 */
    private fun text(body: InputStream, where: String, source: String): PluginLoader.Parsed {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < MAX_TEXT) {
            val n = body.read(buf)
            if (n < 0) break
            total += n
            out.write(buf, 0, n)
        }
        val str = out.toByteArray().toString(Charsets.UTF_8).trim()
        if (str.startsWith("{")) return PluginLoader.parse(str, source)
        return PluginLoader.Parsed.Err(
            "$where 认不出来：既不是 .tgz / .tar.gz，也不是 JSON 清单。" +
                "插件包装成 gzip，或者直接放一份 .json。",
        )
    }

    private fun scan(input: Tallying, where: String, source: String): PluginLoader.Parsed {
        return when (val found = walk(input)) {
            is Scan.Found -> PluginLoader.parse(found.text, source.ifBlank { found.path })
            is Scan.Oversized -> PluginLoader.Parsed.Err(
                "$where 里的 ${found.path} 有 ${found.size / 1024} KB。" +
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

    // ---------------- 扫描 ----------------

    private sealed interface Scan {
        data class Found(val path: String, val text: String) : Scan

        /** 找到了清单，但大得离谱 —— 与「没有清单」区分开，否则提示会指错方向 */
        data class Oversized(val path: String, val size: Int) : Scan

        /** 包里有 package.json，但没有我们的清单 —— 最可能是官方 Cordis 插件 */
        data class NpmOnly(val name: String) : Scan

        data object Nothing : Scan
    }

    private fun walk(input: Tallying): Scan {
        val manifests = ArrayList<Pair<String, ByteArray>>()
        var pkgName: String? = null
        var oversized: Pair<String, Int>? = null

        val header = ByteArray(BLOCK)
        var entries = 0
        // pax / GNU 长文件名：只对**紧随其后**的那个条目生效，用完即清
        var pendingPath: String? = null
        var pendingLong: String? = null

        while (entries < MAX_ENTRIES) {
            if (!input.readFully(header)) break
            if (isZeroBlock(header)) break

            val rawName = readString(header, 0, 100)
            val size = readOctal(header, 124, 12)
            val type = header[156].toInt()
            val prefix = if (readString(header, 257, 6) == "ustar") readString(header, 345, 155) else ""

            if (size < 0) break
            val padding = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()

            // 只读需要的两种文件，其余整段跳过 —— 这是「包可以很大」的关键：
            // 一个 5 MB 的截图条目在这里只花一次 skip，不进内存
            val name0 = pendingPath ?: pendingLong ?: prefix.let {
                if (it.isEmpty()) rawName else "$it/$rawName"
            }
            val isFile = type == '0'.code || type == 0
            val isMeta = type == 'x'.code || type == 'L'.code
            val base = if (isFile) name0.substringAfterLast('/') else ""
            /*
             * 只读三类内容：pax/GNU 长名字头、清单、package.json。
             *
             * 这是「包可以很大」的关键：一个 5 MB 的截图条目在这里只花一次 skip，
             * 不进内存。长名字头本身很小，但**必须读** —— 跳过它就等于丢掉
             * 下一个条目的真实路径，pax 格式的 npm 包会整个认不出来。
             */
            val wanted = size <= MAX_MEMBER && (
                isMeta ||
                    (isFile && (base == MANIFEST_NAME || base == "package.json"))
                )

            val body = if (wanted) {
                val b = ByteArray(size.toInt())
                if (!input.readFully(b)) break
                b
            } else {
                input.skipFully(size)
                null
            }
            input.skipFully(padding.toLong())

            when (type) {
                'L'.code -> if (body != null) pendingLong = body.toString(Charsets.UTF_8).trimEnd('\u0000')
                'x'.code -> if (body != null) pendingPath = paxPath(body.toString(Charsets.UTF_8))
                'g'.code -> Unit // 全局 pax 头，只写默认值，这里用不到
            }

            if (isFile) {
                entries++
                val name = name0
                pendingPath = null
                pendingLong = null

                if (isSafe(name)) {
                    val baseName = name.substringAfterLast('/')
                    when {
                        baseName == MANIFEST_NAME && body != null -> manifests += name to body
                        baseName == MANIFEST_NAME -> if (oversized == null) oversized = name to size.toInt()
                        baseName == "package.json" && body != null -> if (pkgName == null) {
                            pkgName = packageName(body)
                        }
                    }
                }
            } else if (type != 'x'.code && type != 'L'.code) {
                // 目录、符号链接等：长名字只对紧随其后的文件条目有效，不留给下一个
                pendingPath = null
                pendingLong = null
            }
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

    // ---------------- 字节工具 ----------------

    private enum class Kind { PACKED, UNPACKED }

    private class TooBig(val kind: Kind) : Exception()

    private class Tally(val limit: Long, private val kind: Kind) {
        private var used = 0L
        fun add(n: Int) {
            used += n
            if (used > limit) throw TooBig(kind)
        }
    }

    /**
     * 边读边记账的流。
     *
     * 两处用途，两本账：原始字节（防「选错文件」）与解压字节（防「gzip 炸弹」）。
     * 后者不能等解压完再算 —— 那时候内存已经被吃掉了。
     */
    private class Tallying(
        private val src: InputStream,
        private val tally: Tally,
    ) : InputStream() {

        /*
         * 继承 InputStream，而不是「包一层 + 自己暴露 read 方法」。
         *
         * 同一个对象要同时出现在两个位置：`SequenceInputStream` 的第二个参数
         * （必须是 InputStream），以及 tar 扫描的 readFully / skipFully。
         * 做成子类两边都能用，也不会出现「某条路径漏记账」的洞 ——
         * 漏一笔账就等于给 gzip 炸弹开了个后门。
         */

        override fun read(): Int {
            val v = src.read()
            if (v >= 0) tally.add(1)
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len)
            if (n > 0) tally.add(n)
            return n
        }

        /** 读到哪算哪，返回实际读到的字节数（可能小于 buf.size） */
        fun readUpTo(buf: ByteArray): Int {
            var off = 0
            while (off < buf.size) {
                val n = src.read(buf, off, buf.size - off)
                if (n < 0) break
                tally.add(n)
                off += n
            }
            return off
        }

        fun readFully(buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = src.read(buf, off, buf.size - off)
                if (n < 0) return false
                tally.add(n)
                off += n
            }
            return true
        }

        fun skipFully(n: Long) {
            var left = n
            val buf = ByteArray(8 * 1024)
            while (left > 0) {
                val want = minOf(left, buf.size.toLong()).toInt()
                val got = src.read(buf, 0, want)
                if (got < 0) throw IllegalStateException("包被截断了")
                tally.add(got)
                left -= got
            }
        }
    }

    // ---------------- 格式判断 ----------------

    private fun isGzip(b: ByteArray, n: Int): Boolean =
        n >= 2 && (b[0].toInt() and 0xff) == 0x1f && (b[1].toInt() and 0xff) == 0x8b

    private fun isTar(b: ByteArray, n: Int): Boolean =
        n >= 262 && String(b, 257, 5, Charsets.US_ASCII) == "ustar"

    private fun isZeroBlock(b: ByteArray): Boolean {
        for (v in b) if (v.toInt() != 0) return false
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

    /**
     * 路径安全检查。
     *
     * 这里其实不写盘，所以穿越路径伤不到文件系统；但**一个含 `..` 的条目名
     * 说明这个包不是正常工具打出来的**，也就没有理由从里面取清单。
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
