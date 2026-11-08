package com.dshwatch.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dshwatch.core.Ids
import com.dshwatch.domain.attachment.PdfText
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.model.AttachmentKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * 附件的落盘与文本提取。
 *
 * 支持的范围是「大多数聊天场景真会传的东西」：
 *  - 纯文本：txt / md / json / csv / 代码 / 日志…
 *  - Word：docx（zip + word/document.xml，不需要 Apache POI，省 8MB）
 *  - PDF：自己解内容流（见 [PdfText]），扫描件与 CID 字体明确报「提不出文字」
 *  - 图片：原样留存，走多模态输入那条路
 *
 * 不支持的（xlsx / pptx / 老式 doc）给一句「先转成文本或 PDF」——
 * 硬塞二进制进去只会让模型胡说八道。
 */
object AttachmentStore {

    private const val MAX_RAW_BYTES = 8L * 1024 * 1024
    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "yaml", "yml", "toml", "ini",
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs",
        "swift", "rb", "php", "sh", "bash", "zsh", "sql", "html", "htm", "css", "scss", "gradle",
        "properties", "conf", "lua", "dart", "m", "r", "pl", "vim",
    )

    fun root(appFilesDir: File): File = File(appFilesDir, "attachments").apply { mkdirs() }

    /**
     * 把一个 content:// 或 file:// 导入成附件。
     *
     * 全程在 IO 线程，调用方负责切线程（这里也做了 withContext 兜底，
     * 因为导入往往紧接着一次发送，漏切一次就会卡住主线程）。
     */
    suspend fun import(context: Context, appFilesDir: File, uri: Uri): Attachment =
        withContext(Dispatchers.IO) {
            val id = Ids.new()
            val meta = queryMeta(context, uri)
            val name = meta.first.ifBlank { "file" }
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = context.contentResolver.getType(uri).orEmpty()
            val kind = kindOf(ext, mime)

            if (meta.second > MAX_RAW_BYTES) {
                return@withContext failed(id, name, mime, meta.second, kind, "文件超过 8MB，手表上读不动")
            }

            val dir = File(root(appFilesDir), id).apply { mkdirs() }
            val raw = File(dir, safeName(name))
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    input.copyTo(out)
                    out.toByteArray()
                } ?: throw IllegalStateException("无法打开这个文件")
            }.getOrElse {
                return@withContext failed(id, name, mime, meta.second, kind, "读取失败：${it.message.orEmpty().take(60)}")
            }
            raw.writeBytes(bytes)

            val text = runCatching { extract(kind, ext, bytes) }.getOrElse { e ->
                return@withContext Attachment(
                    id = id,
                    name = name,
                    mime = mime,
                    sizeBytes = bytes.size.toLong(),
                    kind = kind,
                    path = raw.absolutePath,
                    error = "提取失败：${e.message.orEmpty().take(80)}",
                )
            }

            if (text == null) {
                // 图片：不提取，原样留着给多模态用
                return@withContext Attachment(
                    id = id,
                    name = name,
                    mime = mime,
                    sizeBytes = bytes.size.toLong(),
                    kind = kind,
                    path = raw.absolutePath,
                )
            }

            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return@withContext Attachment(
                    id = id,
                    name = name,
                    mime = mime,
                    sizeBytes = bytes.size.toLong(),
                    kind = kind,
                    path = raw.absolutePath,
                    error = if (kind == AttachmentKinds.PDF) {
                        "这份 PDF 提不出文字（可能是扫描件，或用了需要 ToUnicode 表的 CID 字体）"
                    } else {
                        "文件里没有可读文本"
                    },
                )
            }

            val capped = if (trimmed.length > AttachmentKinds.MAX_CHARS) {
                trimmed.take(AttachmentKinds.MAX_CHARS) + "\n\n…（已截断，原文件共 ${trimmed.length} 字）"
            } else {
                trimmed
            }
            val textFile = File(dir, "extracted.txt")
            textFile.writeText(capped)
            Attachment(
                id = id,
                name = name,
                mime = mime,
                sizeBytes = bytes.size.toLong(),
                kind = kind,
                path = raw.absolutePath,
                textPath = textFile.absolutePath,
                chars = capped.length,
            )
        }

    /** 发送时读正文。读不到就返回空串，调用方按「附件内容缺失」处理 */
    fun readText(att: Attachment, maxChars: Int = AttachmentKinds.MAX_CHARS): String {
        if (att.textPath.isBlank()) return ""
        val f = File(att.textPath)
        if (!f.exists()) return ""
        return runCatching { f.readText().take(maxChars) }.getOrDefault("")
    }

    fun delete(appFilesDir: File, att: Attachment) {
        runCatching { File(root(appFilesDir), att.id).deleteRecursively() }
    }

    // ------------------------------------------------------------------

    private fun kindOf(ext: String, mime: String): String = when {
        mime.startsWith("image/") -> AttachmentKinds.IMAGE
        ext == "docx" -> AttachmentKinds.DOCX
        ext == "pdf" -> AttachmentKinds.PDF
        ext in TEXT_EXT || mime.startsWith("text/") -> AttachmentKinds.TEXT
        else -> AttachmentKinds.OTHER
    }

    private fun extract(kind: String, ext: String, bytes: ByteArray): String? = when (kind) {
        AttachmentKinds.IMAGE -> null
        AttachmentKinds.DOCX -> docx(bytes)
        AttachmentKinds.PDF -> PdfText.extract(bytes)
        AttachmentKinds.TEXT -> decodeText(bytes)
        else -> throw IllegalStateException(
            if (ext.isBlank()) "不支持的文件类型" else "暂不支持 .$ext，请先转成文本或 PDF",
        )
    }

    /**
     * 文本解码：先按 UTF-8，出现大量替换字符再退回 GBK。
     *
     * 国内用户传 .txt 有相当比例是 GBK（Windows 记事本默认）。只按 UTF-8 读
     * 会得到满屏「锟斤拷」，模型据此回答就是纯幻觉 —— 所以值得多试一次。
     */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        if (bad <= 2 || bad * 100 < utf8.length) return utf8
        val gbk = runCatching { String(bytes, charset("GBK")) }.getOrNull() ?: return utf8
        return if (gbk.count { it == '\uFFFD' } < bad) gbk else utf8
    }

    /**
     * docx 正文提取。
     *
     * docx 就是个 zip，正文全在 `word/document.xml`。按段落和换行标签切一下、
     * 去掉所有标签就够用了 —— 样式、批注、页眉这些对「让模型读懂内容」没有价值。
     */
    private fun docx(bytes: ByteArray): String {
        var xml: String? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
                zip.closeEntry()
            }
        }
        val raw = xml ?: throw IllegalStateException("这个 docx 里没有正文（可能是 .doc 改名）")
        return raw
            .replace("</w:p>", "\n")
            .replace("<w:br/>", "\n")
            .replace("<w:tab/>", "\t")
            .replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    private fun queryMeta(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni).orEmpty()
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name.ifBlank { "file" } to size
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(80).ifBlank { "file" }

    private fun failed(
        id: String,
        name: String,
        mime: String,
        size: Long,
        kind: String,
        msg: String,
    ) = Attachment(
        id = id,
        name = name,
        mime = mime,
        sizeBytes = size,
        kind = kind,
        error = msg,
    )
}
