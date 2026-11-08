package com.dshwatch.domain.attachment

import java.util.zip.Inflater

/**
 * 极简 PDF 文本提取。
 *
 * ## 为什么不引 pdfbox
 *
 * pdfbox-android 装进来大约是 8–10MB。这个应用整个 APK 才 8MB，
 * 而 PDF 在手表上的使用频率远低于「随手传个 txt / docx」。
 * 为了一个偶尔用到的格式把包翻一倍，不划算。
 *
 * ## 能做到什么
 *
 * 能读：**文本型 PDF**（Word 导出、LaTeX 导出、网页打印），包括 FlateDecode 压缩的。
 * 读不了：扫描件（本来就是图）、用了 Identity-H/CID 编码且带 ToUnicode 映射的中文 PDF。
 *
 * 后者不是偷懒 —— 那需要解析字体资源里的 CMap 表，把 CID 逐个映射回 Unicode，
 * 工作量比 pdfbox 的一半还多。所以这两类明确返回空串，由 [com.dshwatch.data.attachment.AttachmentStore]
 * 给出「提不出文字，请转成文本」的提示，而不是丢一堆乱码给模型。
 */
object PdfText {

    fun extract(bytes: ByteArray): String {
        val sb = StringBuilder()
        var cursor = 0
        while (cursor < bytes.size) {
            val streamAt = indexOf(bytes, STREAM, cursor) ?: break
            var start = streamAt + STREAM.size
            // stream 后面必须跟 EOL，PDF 规范允许 \r\n、\n、\r 三种
            if (bytes.getOrNull(start)?.toInt() == '\r'.code) start++
            if (bytes.getOrNull(start)?.toInt() == '\n'.code) start++
            val endAt = indexOf(bytes, END_STREAM, start) ?: break
            if (endAt <= start) {
                cursor = endAt + END_STREAM.size
                continue
            }

            val chunk = bytes.copyOfRange(start, endAt)
            // 字典在 stream 之前，通常不超过几百字节；取一段够判断过滤器即可
            val dictFrom = (streamAt - 512).coerceAtLeast(0)
            val dict = String(bytes, dictFrom, streamAt - dictFrom, Charsets.ISO_8859_1)
            val data = if (dict.contains("FlateDecode")) inflate(chunk) ?: chunk else chunk
            sb.append(textOf(data))
            cursor = endAt + END_STREAM.size
        }
        return sb.toString().trim()
    }

    /**
     * 从内容流里抽出文本。
     *
     * 只认 `Tj` / `TJ` / `'` / `"` 四种显示操作符附近的字符串字面量 ——
     * 这是 PDF 里「真的有字要显示」的唯一信号。其余括号（比如 `(Adobe)` 版权注释）
     * 会被长度与可读性两道过滤挡掉。
     */
    private fun textOf(data: ByteArray): String {
        val s = String(data, Charsets.ISO_8859_1)
        if (!s.contains("Tj") && !s.contains("TJ")) return ""
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '(' -> {
                    val sb = StringBuilder()
                    i++
                    var depth = 1
                    while (i < s.length && depth > 0) {
                        val c = s[i]
                        when {
                            c == '\\' -> {
                                val next = s.getOrNull(i + 1)
                                when (next) {
                                    'n' -> sb.append('\n')
                                    'r' -> sb.append('\r')
                                    't' -> sb.append('\t')
                                    'b' -> sb.append('\b')
                                    'f' -> sb.append('\u000C')
                                    '(', ')', '\\' -> sb.append(next)
                                    else -> if (next != null && next.isDigit()) {
                                        // 八进制 \{1,3}
                                        val oct = s.substring(i + 1, minOf(i + 4, s.length))
                                            .takeWhile { it in '0'..'7' }
                                        if (oct.isNotEmpty()) {
                                            sb.append(oct.toInt(8).toChar())
                                            i += oct.length - 1
                                        }
                                    } else {
                                        sb.append(next ?: "")
                                    }
                                }
                                i += 2
                            }
                            c == '(' -> {
                                depth++
                                sb.append(c)
                                i++
                            }
                            c == ')' -> {
                                depth--
                                if (depth > 0) sb.append(c)
                                i++
                            }
                            else -> {
                                sb.append(c)
                                i++
                            }
                        }
                    }
                    val piece = decodeLiteral(sb.toString())
                    if (piece.isNotBlank()) out.append(piece)
                }

                // 十六进制串 <...>：常见于 CID 字体，直接跳过；真需要时也是乱码
                else -> i++
            }
        }
        return out.toString().let { txt ->
            if (txt.isBlank()) "" else txt + "\n"
        }
    }

    /**
     * PDF 字符串用单字节存，中文通常走 CID（两个字节一个码位），这里按 Latin-1 解。
     * 如果一段里可打印字符占比太低，说明是二进制/CID，宁可丢掉也不要制造乱码。
     */
    private fun decodeLiteral(raw: String): String {
        if (raw.isEmpty()) return ""
        val printable = raw.count { it.code in 32..126 || it == '\n' || it == '\t' }
        if (printable * 10 < raw.length * 7) return ""
        return raw
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(buf, 0, n)
            }
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty()) return from
        var i = from.coerceAtLeast(0)
        val last = hay.size - needle.size
        while (i <= last) {
            var j = 0
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return null
    }

    private val STREAM = "stream".toByteArray(Charsets.ISO_8859_1)
    private val END_STREAM = "endstream".toByteArray(Charsets.ISO_8859_1)
}
