package com.dshwatch.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 会话 txt 文件的读取（导入端）。
 *
 * 单独拎出来是因为「读一个 txt」在中文环境下比看上去麻烦：
 * Windows 记事本存的是 GBK、微信/网页复制出来的是 UTF-8（有时带 BOM）、
 * 还有人从 Excel 导出过 GB18030。直接 `File.readText()` 按 UTF-8 硬解，
 * 遇到 GBK 会得到一屏 `锟斤拷`，而用户只会觉得「导入功能坏了」。
 *
 * 判定顺序：**先按 UTF-8 严格解码**（非法字节直接报错），失败再退 GB18030，
 * 最后才是宽松 UTF-8。反过来先试 GBK 是不行的 —— GBK 几乎能解出任何字节序列，
 * 一旦先试它，正常的 UTF-8 文件也会被解成乱码。
 */
object TranscriptFile {

    /** 单个文件上限。手表内存小，4MB 纯文本已经是几十万字，够导一整本书了 */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** 导入对话框里最多列这么多条候选文件，再多滚不完也没意义 */
    const val MAX_LISTED = 8

    private val GB18030: Charset? = runCatching { Charset.forName("GB18030") }.getOrNull()

    /** 按内容猜编码。带不带 BOM 都行，返回值里不含 BOM 字符 */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // UTF-8 BOM：去掉后严格解码一定能过
        val body = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

        strictDecode(body, Charsets.UTF_8)?.let { return it.stripBom() }
        GB18030?.let { cs -> strictDecode(body, cs)?.let { return it.stripBom() } }

        // 两种都解不干净：说明文件本身就是坏的（或编码没覆盖）。用宽松 UTF-8 兜底，
        // 好歹让用户看到大部分内容，替换字符会集中在出问题的那几行
        return String(body, Charsets.UTF_8).stripBom()
    }

    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun String.stripBom(): String =
        if (startsWith('\uFEFF')) substring(1) else this

    /** 读工作空间里的文件；超上限、读失败都返回 null，由调用方给提示 */
    fun read(file: File): String? = runCatching {
        if (!file.isFile) return null
        if (file.length() > MAX_BYTES) return null
        file.readBytes().let { decode(it) }
    }.getOrNull()

    /** 读一个流（系统文件选择器给的 Uri）；超过上限返回 null */
    fun read(input: InputStream, limit: Int = MAX_BYTES): String? = runCatching {
        input.use { stream ->
            val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                // 不接受「截断后照读」：半截的对话导进来比导不进来更让人迷惑
                if (total > limit) return null
                out.write(buf, 0, n)
            }
            decode(out.toByteArray())
        }
    }.getOrNull()

    /** 只认 .txt / .text / .md（大小写无关）。文件选择器放开的是全部类型，这里再筛一次 */
    fun looksLikeText(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".txt") || n.endsWith(".text") || n.endsWith(".md")
    }
}
