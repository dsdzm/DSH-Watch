package com.dshwatch.data.history

import com.dshwatch.core.Ids
import com.dshwatch.core.Jsonx
import com.dshwatch.core.Logx
import com.dshwatch.data.attachment.AttachmentStore
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.store.StoredConversation
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.model.AttachmentKinds
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 一次导入的结果，直接拿去拼提示文案 */
data class ImportOutcome(
    val sessions: Int,
    val messages: Int,
    val images: Int,
    /** 有消息但一条都映射不出来、被整条跳过的会话 */
    val skipped: Int,
    /** 图片原件体积，0 表示这份归档里没有图 */
    val imageBytes: Long,
)

/**
 * 把 DeepSeek 官方导出的 `deepseek_data-*.zip` 读成本地会话。
 *
 * ## 为什么要做成「导入」而不是「同步」
 *
 * 同步要账号、要令牌、要处理增量与冲突；导入只要一个文件，用户自己掌握节奏，
 * 也不会因为我们这边出问题而影响官方账号。而且官方导出本身就是一份完整快照，
 * 天然适合做迁移的边界。
 *
 * ## 顺序为什么是「先收集、后解析」
 *
 * 归档里图片条目排在会话后面（`export_path` 却写在会话 JSON 里），
 * 用 ZipInputStream 顺序读时，读到会话那一刻还不知道图在哪。
 * 所以：会话 JSON 先原样收进内存（每条几十 KB），图片先落临时目录，
 * 全部读完再统一解析。反过来「边读边存」会要求两次遍历，得先把整个 zip 复制一遍。
 */
class DeepSeekImporter(
    private val appFilesDir: File,
    private val store: ConversationStore,
) {

    suspend fun import(input: InputStream): ImportOutcome = withContext(Dispatchers.IO) {
        val workspace = File(appFilesDir, "history_import").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val collected = collect(input, workspace)
            persist(collected, workspace)
        } finally {
            // 图片已经复制进附件目录，临时文件没有保留价值
            workspace.deleteRecursively()
        }
    }

    // ---------------- 第一步：把 zip 拆成「会话文本 + 图片文件」 ----------------

    private class Collected(
        val sessions: List<Pair<String, String>>,
        val images: Map<String, File>,
        val imageBytes: Long,
    )

    private fun collect(input: InputStream, workspace: File): Collected {
        val sessions = ArrayList<Pair<String, String>>()
        val images = HashMap<String, File>()
        var imageBytes = 0L
        var entries = 0

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = runCatching { zip.nextEntry }.getOrNull()
            while (entry != null) {
                entries++
                val name = entry.name

                // 第一个条目必须是 manifest —— 这是官方的约定，也是「拿错文件」最快的判据
                if (entries == 1 && name != DeepSeekFormat.MANIFEST) {
                    throw ArchiveException(
                        ArchiveException.Code.NOT_DEEPSEEK,
                        "这不是 DeepSeek 导出的历史对话文件",
                    )
                }

                when {
                    entry.isDirectory -> Unit

                    name == DeepSeekFormat.MANIFEST -> checkManifest(readText(zip))

                    // chat_session_index.json 只是清单，真正的数据在每个会话文件里，跳过
                    name == DeepSeekFormat.INDEX -> Unit

                    name.startsWith(DeepSeekFormat.SESSION_DIR) && name.endsWith(".json") ->
                        sessions.add(name to readText(zip))

                    // 图片原件：chat_sessions/{会话}_files/{文件id}_{文件名}
                    name.contains("_files/") && !name.endsWith("/") -> {
                        /*
                         * 这里不能信 `entry.size`。
                         *
                         * 导出方用 DEFLATE 压缩时，ZipInputStream 在读之前拿不到
                         * 解压后的长度（是 -1）。按它做「跳过超大文件」的判断，
                         * 会把**每一张图**都跳过 —— 症状是导入「成功」但一张图都没有。
                         * 改成边读边数，超限就停。
                         */
                        val target = safeFile(workspace, name)
                        if (target == null || imageBytes >= DeepSeekFormat.MAX_IMAGE_BYTES) {
                            zip.skip(Long.MAX_VALUE)
                        } else {
                            target.parentFile?.mkdirs()
                            val written = copyLimited(zip, target, DeepSeekFormat.MAX_ONE_IMAGE)
                            if (written > 0 &&
                                imageBytes + written <= DeepSeekFormat.MAX_IMAGE_BYTES
                            ) {
                                images[name] = target
                                imageBytes += written
                            } else {
                                target.delete()
                            }
                        }
                    }

                    else -> Unit
                }

                zip.closeEntry()
                entry = runCatching { zip.nextEntry }.getOrNull()
            }
        }

        if (sessions.isEmpty()) {
            throw ArchiveException(ArchiveException.Code.EMPTY, "文件里没有任何对话")
        }
        return Collected(sessions, images, imageBytes)
    }

    private fun checkManifest(text: String) {
        val root = runCatching { Jsonx.lenient.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw ArchiveException(ArchiveException.Code.NOT_DEEPSEEK, "manifest 读不出来")
        val version = root.num("export_format_version")
            ?: throw ArchiveException(ArchiveException.Code.BAD_VERSION, "文件版本无效，无法导入")
        if (version > DeepSeekFormat.VERSION) {
            throw ArchiveException(
                ArchiveException.Code.TOO_NEW,
                "文件版本过新（v$version），请升级应用后再导入",
            )
        }
        if (version.toInt() != DeepSeekFormat.VERSION) {
            throw ArchiveException(
                ArchiveException.Code.BAD_VERSION,
                "只支持 v${DeepSeekFormat.VERSION} 的导出文件，请到 DeepSeek 重新导出",
            )
        }
    }

    /**
     * 读一个条目并限长。
     *
     * 不直接用 `readBytes()`：zip 里放一个几十 MB 的 txt 就能把内存打满，
     * 而这条路的输入是用户随手选的文件，不能信任。
     */
    private fun readText(zip: ZipInputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > DeepSeekFormat.MAX_ENTRY_BYTES) {
                throw ArchiveException(ArchiveException.Code.BAD_VERSION, "文件内容异常，无法导入")
            }
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /**
     * 把一个条目写进文件，超过 [limit] 就停手并返回 -1。
     *
     * 存在的理由是 zip 里那条条目的长度在解压前不可信（见上面图片分支的注释），
     * 只能边写边数。
     */
    private fun copyLimited(zip: ZipInputStream, target: File, limit: Long): Long {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        target.outputStream().use { out ->
            while (true) {
                val n = zip.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) return -1L
                out.write(buf, 0, n)
            }
        }
        return total
    }

    /**
     * 条目名 → 临时目录下的安全路径。
     *
     * `../` 这类名字会把文件写到临时目录外面去（zip slip），必须挡掉；
     * 文件名里带 `/` 的只取最后一段，反正我们只按完整条目名索引。
     */
    private fun safeFile(root: File, entryName: String): File? {
        val clean = entryName.replace('\\', '/').trimStart('/')
        if (clean.split('/').any { it == ".." }) return null
        val target = File(root, clean)
        return target.takeIf {
            runCatching { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
                .getOrDefault(false)
        }
    }

    // ---------------- 第二步：解析成会话 ----------------

    private suspend fun persist(collected: Collected, workspace: File): ImportOutcome {
        var sessions = 0
        var messages = 0
        var images = 0
        var skipped = 0

        for ((name, text) in collected.sessions) {
            val mapped = runCatching { mapSession(name, text, collected.images) }
                .getOrElse {
                    Logx.e("HistoryImport", "会话 $name 解析失败，跳过", it)
                    null
                }
            if (mapped == null || mapped.messages.isEmpty()) {
                skipped++
                continue
            }
            store.save(mapped)
            sessions++
            messages += mapped.messages.size
            images += mapped.messages.sumOf { msg ->
                msg.attachments.count { it.kind == AttachmentKinds.IMAGE }
            }
        }

        if (sessions == 0) {
            throw ArchiveException(
                ArchiveException.Code.EMPTY,
                if (skipped > 0) "文件里的对话都读不出来，可能已损坏" else "文件里没有任何对话",
            )
        }
        return ImportOutcome(sessions, messages, images, skipped, collected.imageBytes)
    }

    private fun mapSession(
        entryName: String,
        text: String,
        images: Map<String, File>,
    ): StoredConversation? {
        val root = runCatching { Jsonx.lenient.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return null
        val rawMessages = root.arr("messages") ?: return null
        val nodes = rawMessages.mapNotNull { it as? JsonObject }
        if (nodes.isEmpty()) return null

        val linear = linearize(nodes, root.num("current_message_id"))

        val converted = ArrayList<ChatMessage>(linear.size)
        var lastUserId = ""
        for (node in linear) {
            val role = when (node.str("role")) {
                "USER" -> Role.USER
                "ASSISTANT" -> Role.ASSISTANT
                else -> continue
            }
            val built = build(node, role, images, lastUserId) ?: continue
            // 空回答（没正文、没思考、没附件）在界面上只是一块空白，不留
            if (role == Role.ASSISTANT && built.content.isBlank() &&
                built.reasoning.isBlank() && built.attachments.isEmpty()
            ) {
                continue
            }
            if (role == Role.USER) lastUserId = built.id
            converted.add(built)
        }
        if (converted.isEmpty()) return null

        val first = DeepSeekFormat.time(root.str("inserted_at"))
        val last = DeepSeekFormat.time(root.str("updated_at"))
        val createdAt = if (first > 0) first else converted.first().createdAt
        // 归档里有标题就用它；没有的话用第一句提问开头 ——
        // 几十条会话全叫「导入的对话」时，列表还不如不显示
        val title = root.str("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: converted.firstOrNull { it.role == Role.USER }
                ?.content
                ?.replace('\n', ' ')
                ?.trim()
                ?.take(18)
                ?.takeIf { it.isNotEmpty() }
            ?: "导入的对话"
        val meta = Conversation(
            id = Ids.new(),
            title = title,
            createdAt = createdAt,
            updatedAt = if (last > 0) last else converted.last().createdAt,
            messageCount = converted.size,
            pinned = root.flag("pinned"),
        )
        return StoredConversation(meta = meta, messages = converted)
    }

    /**
     * 消息树 → 一条线。
     *
     * 归档里每条消息带 `parent_id`，分叉就是「同一个提问下的多个回答」。
     * 我们这边的记录是线性的（重新生成直接覆盖旧的），所以只取
     * `current_message_id` 所在的那条路径 —— 也就是用户在官方 App 里
     * 最后停在的那一支。其余分支在界面上本来就没有位置，硬塞会把对话读乱。
     */
    private fun linearize(nodes: List<JsonObject>, currentId: Long?): List<JsonObject> {
        val byId = HashMap<Long, JsonObject>(nodes.size)
        for (node in nodes) {
            val id = node.num("id") ?: continue
            byId[id] = node
        }
        var cursor = currentId?.takeIf { byId.containsKey(it) }
            ?: byId.keys.maxOrNull()
        val path = ArrayDeque<JsonObject>()
        val seen = HashSet<Long>()
        while (cursor != null && seen.add(cursor)) {
            val node = byId[cursor] ?: break
            path.addFirst(node)
            cursor = node.num("parent_id")
        }
        // 树结构坏了（父链断开、id 重复）时退化成「按 id 排」，至少不丢内容
        return if (path.isEmpty()) byId.entries.sortedBy { it.key }.map { it.value } else path.toList()
    }

    private fun build(
        node: JsonObject,
        role: Role,
        images: Map<String, File>,
        parentUserId: String,
    ): ChatMessage? {
        val id = Ids.new()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val attachments = ArrayList<Attachment>()

        for (fragment in node.arr("fragments").orEmpty()) {
            val f = fragment as? JsonObject ?: continue
            when (f.str("type")) {
                "REQUEST", "RESPONSE" -> content.append(f.str("content").orEmpty())

                // 思考过程单独存：这边有折叠区，混进正文会污染上下文
                "THINK" -> reasoning.append(f.str("content").orEmpty())

                // 服务端的「过载/超长/拒绝」模板：正文照留，状态按异常处理
                "TEMPLATE_RESPONSE" -> if (content.isEmpty()) {
                    content.append(f.str("content").orEmpty())
                }

                "FILE" -> for (item in f.arr("files").orEmpty()) {
                    val file = item as? JsonObject ?: continue
                    attachments.add(fileAttachment(file, images))
                }

                // 搜索、打开网页、页内查找这些步骤在这边没有对应展示，跳过
                else -> Unit
            }
        }

        val status = statusOf(node.str("status"), role)
        val at = DeepSeekFormat.time(node.str("inserted_at"))
        return ChatMessage(
            id = id,
            role = role,
            content = content.toString().trim(),
            reasoning = reasoning.toString().trim(),
            createdAt = if (at > 0) at else System.currentTimeMillis(),
            status = status,
            // 模型与供应商留空：导入的历史不一定来自当前配置，
            // 直接写死会让「模型」那一栏显示一个这台机器上并不存在的名字
            model = "",
            providerId = "",
            error = if (status == MessageStatus.ERROR) "这条回答在导出时没有正常结束" else "",
            parentId = if (role == Role.ASSISTANT) parentUserId else "",
            attachments = attachments,
        )
    }

    private fun statusOf(raw: String?, role: Role): MessageStatus = when (raw) {
        "FINISHED" -> MessageStatus.DONE
        // 官方把中止、未完成都归到这两类：这边同样显示成「已停止」，可以重新生成
        "INCOMPLETE", "WIP" -> MessageStatus.STOPPED
        "FAILED", "TIMEOUT", "CONTEXT_LENGTH_EXCEEDED", "CONTENT_FILTER" -> MessageStatus.ERROR
        else -> if (role == Role.USER) MessageStatus.DONE else MessageStatus.DONE
    }

    /**
     * 附件元信息 → 本地附件。
     *
     * 图片有原件就复制进附件目录；非图片（PDF、Word…）在官方导出里**本来就只有元数据**，
     * 正文不带。这时候给一条带 `error` 的附件，用户至少看得见「当时传过这个文件」，
     * 而不是一条消息凭空少了东西。
     */
    private fun fileAttachment(item: JsonObject, images: Map<String, File>): Attachment {
        val name = item.str("file_name")?.takeIf { it.isNotBlank() } ?: "文件"
        val size = item.num("file_size") ?: 0L
        val isImage = item.flag("is_image")
        val exportPath = item.str("export_path")
        val source = exportPath?.let { images[it] }

        if (isImage && source != null && source.exists()) {
            val bytes = runCatching { source.readBytes() }.getOrNull()
            if (bytes != null) {
                return AttachmentStore.importBytes(
                    appFilesDir = appFilesDir,
                    name = name,
                    mime = mimeOf(name),
                    bytes = bytes,
                )
            }
        }

        return Attachment(
            id = Ids.new(),
            name = name,
            mime = if (isImage) mimeOf(name) else "",
            sizeBytes = size,
            kind = if (isImage) AttachmentKinds.IMAGE else AttachmentKinds.OTHER,
            error = if (isImage) "原图没有随这份文件导出" else "只导出了文件信息，内容不在归档里",
        )
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "bmp" -> "image/bmp"
        else -> "image/*"
    }
}
