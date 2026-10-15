package com.dshwatch.domain.agent.exec

import java.io.File
import java.io.RandomAccessFile

/**
 * 有界地读一个可能很大的输出文件：**保头 + 保尾，内存恒定**。
 *
 * 为什么不用内存里的缓冲：把子进程的输出重定向到文件之后，
 * 读的时候文件可能已经有几百 MB（脚本失控打印了大量内容）。
 * 一次性 `readText()` 会在手表上直接把内存吃光。
 *
 * 为什么两头都要读：头告诉模型"它在干什么"，尾告诉模型"它怎么挂的"。
 * 报错和 `Traceback` 永远在最后。
 */
object OutputFiles {

    /** 小于这个体积就整读（放宽了 UTF-8 中文 3 字节/字符的情况） */
    private const val SMALL_FILE_FACTOR = 4

    fun readCapped(file: File, maxChars: Int): Pair<String, Boolean> {
        if (!file.isFile) return "" to false
        if (maxChars <= 0) return "" to (file.length() > 0)

        val length = file.length()
        if (length == 0L) return "" to false

        // 小文件：整读后按同一套规则截断
        if (length <= maxChars.toLong() * SMALL_FILE_FACTOR) {
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            return truncateHeadTail(text, maxChars)
        }

        val head = readHead(file, maxChars)
        val tail = readTail(file, maxChars)
        val dropped = (length - head.toByteArray(Charsets.UTF_8).size - tail.toByteArray(Charsets.UTF_8).size)
            .coerceAtLeast(0)
        return (head + "\n…（文件较大，中间省略约 $dropped 字节）…\n" + tail) to true
    }

    private fun readHead(file: File, maxChars: Int): String {
        val sb = StringBuilder()
        runCatching {
            file.reader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                while (sb.length < maxChars) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val room = maxChars - sb.length
                    sb.append(buf, 0, minOf(room, n))
                }
            }
        }
        return sb.toString()
    }

    private fun readTail(file: File, maxChars: Int): String {
        // 中文按 3 字节估，多读一点再往回对齐
        val tailBytes = (maxChars.toLong() * 3).coerceAtMost(file.length())
        val start = (file.length() - tailBytes).coerceAtLeast(0)
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray((file.length() - start).coerceAtMost(tailBytes).toInt())
                raf.readFully(bytes)
                decodeAligned(bytes, fromTail = true)
            }
        }.getOrDefault("")
    }

    /**
     * 从任意字节偏移解码 UTF-8，先在边界上对齐。
     *
     * 随机 seek 很可能落在某个汉字的中间 —— 直接解码会得到一串替换字符（乱码），
     * 而且会让模型以为文件内容就是坏的。往前/往后挪到合法起始字节再解。
     */
    internal fun decodeAligned(bytes: ByteArray, fromTail: Boolean): String {
        var start = 0
        var end = bytes.size

        if (fromTail) {
            // 跳过开头的续字节（10xxxxxx），它们是上一个字符的尾巴
            while (start < end && (bytes[start].toInt() and 0xC0) == 0x80) start++
        }
        // 结尾可能被截断在多字节字符中间，把不完整的尾巴去掉
        var probe = end - 1
        var back = 0
        while (probe >= start && (bytes[probe].toInt() and 0xC0) == 0x80 && back < 3) {
            probe--
            back++
        }
        if (probe >= start) {
            val lead = bytes[probe].toInt() and 0xFF
            val expected = when {
                lead and 0x80 == 0 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            if (probe + expected > end) end = probe
        }

        if (start >= end) return ""
        return String(bytes, start, end - start, Charsets.UTF_8)
    }
}
