package com.dshwatch.data.history

import com.dshwatch.core.Jsonx
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.domain.model.AttachmentKinds
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出结果：给「已导出 N 个对话」的提示用 */
data class ExportOutcome(
    val sessions: Int,
    val messages: Int,
    val images: Int,
    val bytes: Long,
)

/**
 * 把本地会话写成 DeepSeek 官方的历史归档格式。
 *
 * ## 为什么要能被官方读
 *
 * 只做「导入」是单向的：用户搬进来，就再也搬不出去。按同一套格式写，
 * 这份文件既能发给别人、也能放回官方 demo 打开 —— 备份才叫备份，
 * 否则只是把数据换了个地方锁起来。
 *
 * ## 与导入相反的几个取舍
 *
 * - 消息树退化成一条线：我们本来就没有分支，`parent_id` 按顺序串起来即可；
 * - 非图片附件只写元数据、不写正文，和官方导出保持一致（图片是唯一带原件的）；
 * - 系统提示词不写：它是设置的一部分，不是这段对话的内容。
 */
class DeepSeekExporter(private val store: ConversationStore) {

    suspend fun export(out: OutputStream): ExportOutcome = withContext(Dispatchers.IO) {
        val counter = CountingOutputStream(out)
        var sessions = 0
        var messages = 0
        var images = 0

        ZipOutputStream(counter.buffered()).use { zip ->
            // manifest 必须是第一个条目，导入方据此判断「这是不是一份能读的归档」
            zip.putText(
                DeepSeekFormat.MANIFEST,
                buildJsonObject { put("export_format_version", DeepSeekFormat.VERSION) }.toString(),
            )

            val index = store.index()
            val pending = ArrayList<Triple<Conversation, JsonObject, List<ZipEntryImage>>>()

            for (conv in index) {
                val stored = store.load(conv.id) ?: continue
                val kept = linearize(stored.messages)
                if (kept.isEmpty()) continue

                val built = sessionObject(stored.meta, kept)
                pending.add(Triple(stored.meta, built.first, built.second))
                sessions++
                messages += kept.size
            }

            // 会话 → 索引 → 图片。官方导入也接受交错顺序，但按它自己导出的顺序最稳
            for ((meta, obj, _) in pending) {
                zip.putText("${DeepSeekFormat.SESSION_DIR}${meta.id}.json", obj.toString())
            }
            zip.putText(DeepSeekFormat.INDEX, indexArray(pending).toString())

            for ((_, _, files) in pending) {
                for (image in files) {
                    val bytes = runCatching { image.file.readBytes() }.getOrNull() ?: continue
                    zip.putNextEntry(ZipEntry(image.entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                    images++
                }
            }
        }

        ExportOutcome(sessions, messages, images, counter.count)
    }

    // ---------------- 组装 ----------------

    private class ZipEntryImage(val entryName: String, val file: File)

    /**
     * 归档要求「根是提问、角色交替」。本地可能存在系统消息、以助手开头的会话
     * （导入别家数据、或早期版本留下的），这里做一次规整：
     * 丢掉系统消息与开头的助手，连续同角色只留第一条 —— 少一点内容，
     * 好过整份文件在对面被判定为损坏。
     */
    private fun linearize(messages: List<ChatMessage>): List<ChatMessage> {
        val kept = ArrayList<ChatMessage>(messages.size)
        for (m in messages) {
            if (m.role == Role.SYSTEM) continue
            if (kept.isEmpty() && m.role == Role.ASSISTANT) continue
            if (kept.isNotEmpty() && kept.last().role == m.role) continue
            kept.add(m)
        }
        return kept
    }

    private fun sessionObject(
        meta: Conversation,
        messages: List<ChatMessage>,
    ): Pair<JsonObject, List<ZipEntryImage>> {
        val images = ArrayList<ZipEntryImage>()
        val array = buildJsonArray {
            var parentId = 0
            messages.forEachIndexed { index, msg ->
                val id = index + 1
                add(messageObject(meta, msg, id, if (index == 0) null else parentId, images))
                parentId = id
            }
        }
        val obj = buildJsonObject {
            put("id", meta.id)
            put("title", meta.title)
            put("pinned", meta.pinned)
            put("current_message_id", messages.size)
            put("inserted_at", DeepSeekFormat.stamp(meta.createdAt))
            put("updated_at", DeepSeekFormat.stamp(meta.updatedAt))
            put("messages", array)
        }
        return obj to images
    }

    private fun messageObject(
        meta: Conversation,
        msg: ChatMessage,
        id: Int,
        parentId: Int?,
        images: MutableList<ZipEntryImage>,
    ): JsonObject {
        val fragments = buildJsonArray {
            if (msg.role == Role.USER) {
                add(buildJsonObject { put("type", "REQUEST"); put("content", msg.content) })
            } else {
                if (msg.reasoning.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", "THINK")
                            put("content", msg.reasoning)
                            put("elapsed_secs", JsonNull)
                        },
                    )
                }
                add(buildJsonObject { put("type", "RESPONSE"); put("content", msg.content) })
            }

            /*
             * 附件块与角色无关：这边把用户传的图挂在**提问**上（那是它的来源），
             * 而官方格式里 FILE 也常出现在提问片段里。只给助手写，
             * 导出的图就会整批丢掉 —— 而图片恰恰是唯一带原件的附件。
             */
            val files = msg.attachments
            if (files.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("type", "FILE")
                        put(
                            "files",
                            buildJsonArray {
                                files.forEach { att ->
                                    val isImage = att.kind == AttachmentKinds.IMAGE
                                    val file = if (isImage) File(att.path) else null
                                    val usable = file != null && file.exists() &&
                                        file.length() in 1..DeepSeekFormat.MAX_ONE_IMAGE
                                    val entryName = if (usable) {
                                        val safe = att.name.replace(
                                            Regex("[\\\\/:*?\"<>|]"),
                                            "_",
                                        ).take(80).ifBlank { "image" }
                                        "${DeepSeekFormat.SESSION_DIR}${meta.id}_files/${att.id}_$safe"
                                    } else {
                                        null
                                    }
                                    if (usable && entryName != null) {
                                        images.add(ZipEntryImage(entryName, file!!))
                                    }
                                    add(
                                        buildJsonObject {
                                            put("id", att.id)
                                            put("file_name", att.name)
                                            put("file_size", att.sizeBytes)
                                            put("token_usage", JsonNull)
                                            put("inserted_at", DeepSeekFormat.stamp(msg.createdAt))
                                            if (entryName != null) {
                                                put("export_path", entryName)
                                            } else {
                                                put("export_path", JsonNull)
                                            }
                                            put("is_image", isImage)
                                            put("width", JsonNull)
                                            put("height", JsonNull)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

        return buildJsonObject {
            put("id", id)
            if (parentId == null) put("parent_id", JsonNull) else put("parent_id", parentId)
            put("role", if (msg.role == Role.USER) "USER" else "ASSISTANT")
            put("status", statusOf(msg))
            put("thinking_enabled", msg.reasoning.isNotBlank())
            put("search_enabled", false)
            put("accumulated_token_usage", msg.usage?.total ?: 0)
            put("inserted_at", DeepSeekFormat.stamp(msg.createdAt))
            put("fragments", fragments)
        }
    }

    /** 官方只认这几种状态；这边的 PENDING / STREAMING 一律当「没写完」 */
    private fun statusOf(msg: ChatMessage): String = when (msg.status) {
        MessageStatus.DONE -> "FINISHED"
        MessageStatus.STOPPED -> "INCOMPLETE"
        MessageStatus.ERROR -> "FAILED"
        MessageStatus.PENDING, MessageStatus.STREAMING -> "INCOMPLETE"
    }

    private fun indexArray(pending: List<Triple<Conversation, JsonObject, List<ZipEntryImage>>>): JsonArray =
        buildJsonArray {
            pending.forEach { (meta, obj, _) ->
                add(
                    buildJsonObject {
                        put("id", meta.id)
                        put("title", meta.title)
                        put("pinned", meta.pinned)
                        put("message_count", (obj["messages"] as? JsonArray)?.size ?: 0)
                        put("inserted_at", DeepSeekFormat.stamp(meta.createdAt))
                        put("updated_at", DeepSeekFormat.stamp(meta.updatedAt))
                    },
                )
            }
        }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** 只为拿到「写了多少字节」，UI 上显示一个体积比显示「已完成」有用 */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        /** 文件名照官方来，方便在同一批导出文件里一眼认出新旧 */
        fun fileName(): String {
            val day = java.time.LocalDate.now().toString()
            return "deepseek_data-$day.zip"
        }
    }
}
