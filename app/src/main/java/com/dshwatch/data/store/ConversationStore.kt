package com.dshwatch.data.store

import com.dshwatch.core.Ids
import com.dshwatch.core.Logx
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredConversation(
    val meta: Conversation,
    val messages: List<ChatMessage> = emptyList(),
)

/**
 * 会话持久化。
 *
 * 选型说明：手表上不引入 Room（注解处理器 + 生成的 DAO 会让 APK 与构建时间都变重），
 * 改用「一个会话一个 JSON 文件 + 一个 index.json」的极简方案：
 *  - 列表页只读 index.json（几十 KB，一次读入内存缓存）；
 *  - 打开某个会话才读它的完整消息；
 *  - 写入走 临时文件 + rename，避免掉电/被杀进程时写坏文件。
 */
class ConversationStore(
    private val dir: File,
    private val json: Json,
    /** 可注入：单测里换成 TestDispatcher，保证断言时机确定 */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val indexFile = File(dir, "index.json")
    private val mutex = Mutex()

    private var indexCache: MutableList<Conversation>? = null

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /**
     * 会话列表。
     *
     * ⚠️ 这里会**过滤掉一条消息都没有的会话**。
     * 旧版本每点一次「新建对话」就立刻落盘一条空会话，用户连着点几下，
     * 列表里就堆满了一串同名的「新对话」，还全都点不出内容 ——
     * 列表就不再是「聊过什么」的记录，而是「点过几次加号」的记录。
     *
     * 配合 [ChatEngine.startNew] 的延迟落盘：真正的会话在第一条消息写入时才进列表。
     * 这里再兜一层，是为了让**升级前就已经存在的空会话**也不再显示。
     */
    suspend fun index(): List<Conversation> = withContext(io) {
        mutex.withLock { readIndexLocked().filter { it.messageCount > 0 }.toList() }
    }

    /**
     * 物理清掉没有任何消息的空会话（连同它的 json 文件）。
     * 在 App 启动时调用一次，把升级前堆积的空会话清干净。
     *
     * @return 清理掉的条数
     */
    suspend fun pruneEmpty(): Int = withContext(io) {
        mutex.withLock {
            val idx = readIndexLocked()
            val empties = idx.filter { it.messageCount <= 0 }
            if (empties.isEmpty()) return@withLock 0
            empties.forEach { runCatching { file(it.id).delete() } }
            idx.removeAll { it.messageCount <= 0 }
            writeIndexLocked(idx)
            empties.size
        }
    }

    suspend fun load(id: String): StoredConversation? = withContext(io) {
        mutex.withLock {
            val f = file(id)
            if (!f.exists()) return@withLock null
            runCatching { json.decodeFromString(StoredConversation.serializer(), f.readText()) }
                .getOrElse {
                    Logx.e("ConvStore", "会话 $id 解析失败", it)
                    null
                }
        }
    }

    suspend fun save(conv: StoredConversation): Unit = withContext(io) {
        mutex.withLock {
            val meta = conv.meta.copy(
                messageCount = conv.messages.size,
                updatedAt = if (conv.meta.updatedAt == 0L) System.currentTimeMillis() else conv.meta.updatedAt,
            )
            val trimmed = conv.copy(
                meta = meta,
                messages = if (conv.messages.size > MAX_MESSAGES_PER_CONVERSATION) {
                    conv.messages.takeLast(MAX_MESSAGES_PER_CONVERSATION)
                } else conv.messages,
            )
            writeAtomic(file(conv.meta.id), json.encodeToString(StoredConversation.serializer(), trimmed))
            val idx = readIndexLocked()
            val pos = idx.indexOfFirst { it.id == meta.id }
            if (pos >= 0) idx[pos] = meta else idx.add(meta)
            writeIndexLocked(idx)
        }
    }

    suspend fun create(conversation: Conversation): Unit = withContext(io) {
        mutex.withLock {
            val idx = readIndexLocked()
            idx.add(0, conversation)
            writeIndexLocked(idx)
        }
    }

    suspend fun rename(id: String, title: String): Unit = withContext(io) {
        mutex.withLock {
            val idx = readIndexLocked()
            val pos = idx.indexOfFirst { it.id == id }
            if (pos < 0) return@withLock
            idx[pos] = idx[pos].copy(title = title, updatedAt = System.currentTimeMillis())
            writeIndexLocked(idx)
            val stored = file(id)
            if (stored.exists()) {
                runCatching {
                    val sc = json.decodeFromString(StoredConversation.serializer(), stored.readText())
                    writeAtomic(
                        stored,
                        json.encodeToString(
                            StoredConversation.serializer(),
                            sc.copy(meta = sc.meta.copy(title = title))
                        )
                    )
                }
            }
        }
    }

    /** 改会话的元信息（标题除外，标题走 rename）。主要用于切换工作目录。 */
    suspend fun updateMeta(id: String, transform: (Conversation) -> Conversation): Unit =
        withContext(io) {
            mutex.withLock {
                val idx = readIndexLocked()
                val pos = idx.indexOfFirst { it.id == id }
                if (pos < 0) return@withLock
                val updated = transform(idx[pos])
                idx[pos] = updated
                writeIndexLocked(idx)
                val stored = file(id)
                if (stored.exists()) {
                    runCatching {
                        val sc = json.decodeFromString(StoredConversation.serializer(), stored.readText())
                        writeAtomic(
                            stored,
                            json.encodeToString(
                                StoredConversation.serializer(),
                                sc.copy(meta = updated.copy(messageCount = sc.messages.size)),
                            ),
                        )
                    }.onFailure { Logx.e("ConvStore", "更新会话 $id 元信息失败", it) }
                }
            }
        }

    /** 列出某个目录下的子目录名（用于「选择文件位置」时给候选） */
    suspend fun listSubDirs(parent: String): List<String> = withContext(io) {
        mutex.withLock {
            val base = if (parent.isBlank()) dir else File(dir, parent)
            base.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean): Unit = withContext(io) {
        mutex.withLock {
            val idx = readIndexLocked()
            val pos = idx.indexOfFirst { it.id == id }
            if (pos < 0) return@withLock
            idx[pos] = idx[pos].copy(pinned = pinned)
            writeIndexLocked(idx)
        }
    }

    suspend fun delete(id: String): Unit = withContext(io) {
        mutex.withLock {
            val idx = readIndexLocked()
            idx.removeAll { it.id == id }
            writeIndexLocked(idx)
            file(id).delete()
        }
    }

    suspend fun clearAll(): Unit = withContext(io) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
            indexCache = mutableListOf()
        }
    }

    // ---------------- 内部 ----------------

    private fun file(id: String) = File(dir, "$id.json")

    private fun readIndexLocked(): MutableList<Conversation> {
        indexCache?.let { return it }
        val loaded = if (indexFile.exists()) {
            runCatching { json.decodeFromString(ListSerializerConversation, indexFile.readText()) }
                .getOrElse {
                    Logx.e("ConvStore", "index 解析失败，重建空索引", it)
                    mutableListOf()
                }
        } else {
            mutableListOf()
        }
        indexCache = loaded.toMutableList()
        return indexCache!!
    }

    private fun writeIndexLocked(list: List<Conversation>) {
        // 置顶的排前面，其余按 updatedAt 倒序
        val sorted = list.sortedWith(
            compareByDescending<Conversation> { it.pinned }
                .thenByDescending { it.updatedAt }
        )
        indexCache = sorted.toMutableList()
        writeAtomic(indexFile, json.encodeToString(ListSerializerConversation, sorted))
    }

    private fun writeAtomic(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }.onFailure { Logx.e("ConvStore", "写入 ${target.name} 失败", it) }
    }

    companion object {
        /** 单会话最多保留的消息条数，防止 JSON 无限膨胀（手表存储紧张） */
        const val MAX_MESSAGES_PER_CONVERSATION = 400

        private val ListSerializerConversation =
            kotlinx.serialization.builtins.ListSerializer(Conversation.serializer())

        /** 会话工作目录的上级目录名 */
        const val WORKSPACE_ROOT_DIR = "progress"

        /**
         * 新会话的默认工作目录：`progress/<id 前 6 位>`。
         *
         * 用 id 而不是标题来命名 —— 标题随时会改、还可能带中文和空格，
         * 拿它当目录名既不稳定也不好看；而 id 前缀一次生成就再也不变。
         */
        fun defaultWorkDir(id: String): String = "$WORKSPACE_ROOT_DIR/" + id.take(6)

        fun newConversation(
            providerId: String,
            model: String,
            presetId: String,
            title: String = "新对话",
        ): Conversation {
            val now = System.currentTimeMillis()
            val id = Ids.new()
            return Conversation(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                providerId = providerId,
                model = model,
                presetId = presetId,
                workDir = defaultWorkDir(id),
            )
        }

        fun newMessage(
            role: Role,
            content: String,
            status: MessageStatus = MessageStatus.DONE,
            model: String = "",
            providerId: String = "",
            parentId: String = "",
        ): ChatMessage = ChatMessage(
            id = Ids.new(),
            role = role,
            content = content,
            createdAt = System.currentTimeMillis(),
            status = status,
            model = model,
            providerId = providerId,
            parentId = parentId,
        )
    }
}
