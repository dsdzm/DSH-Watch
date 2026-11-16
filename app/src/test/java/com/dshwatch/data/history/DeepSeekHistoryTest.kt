package com.dshwatch.data.history

import com.dshwatch.core.Jsonx
import com.dshwatch.data.store.ConversationStore
import com.dshwatch.data.store.StoredConversation
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.model.AttachmentKinds
import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 与 DeepSeek 官方历史归档互通。
 *
 * 这里的断言都绕着一件事：**用户的对话不能在搬运途中变形**。
 * 顺序、角色、思考内容、当前分支、图片原件 —— 少一样，导入就只是「看起来成功了」。
 */
class DeepSeekHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(name: String) = ConversationStore(
        dir = File(tmp.root, name).apply { mkdirs() },
        json = Jsonx.lenient,
        io = UnconfinedTestDispatcher(),
    )

    private fun message(
        id: String,
        role: Role,
        text: String,
        at: Long,
        reasoning: String = "",
        attachments: List<Attachment> = emptyList(),
    ) = ChatMessage(
        id = id,
        role = role,
        content = text,
        reasoning = reasoning,
        createdAt = at,
        status = MessageStatus.DONE,
        attachments = attachments,
    )

    private fun conversation(id: String, title: String, at: Long) = Conversation(
        id = id,
        title = title,
        createdAt = at,
        updatedAt = at + 1000,
    )

    @Test
    fun `export then import keeps sessions messages and order`() = runTest {
        val source = store("a")
        source.save(
            StoredConversation(
                meta = conversation("c1", "第一段", 1_700_000_000_000),
                messages = listOf(
                    message("m1", Role.USER, "你好", 1_700_000_000_000),
                    message("m2", Role.ASSISTANT, "你好，有什么可以帮你", 1_700_000_001_000, "先想一下"),
                    message("m3", Role.USER, "讲个笑话", 1_700_000_002_000),
                    message("m4", Role.ASSISTANT, "从前有座山", 1_700_000_003_000),
                ),
            ),
        )
        source.save(
            StoredConversation(
                meta = conversation("c2", "第二段", 1_700_000_010_000),
                messages = listOf(
                    message("n1", Role.USER, "写首诗", 1_700_000_010_000),
                    message("n2", Role.ASSISTANT, "床前明月光", 1_700_000_011_000),
                ),
            ),
        )

        val bytes = ByteArrayOutputStream().also { out ->
            DeepSeekExporter(source).export(out)
        }.toByteArray()

        val target = store("b")
        val outcome = DeepSeekImporter(tmp.root, target).import(ByteArrayInputStream(bytes))

        assertThat(outcome.sessions).isEqualTo(2)
        assertThat(outcome.messages).isEqualTo(6)

        val index = target.index()
        assertThat(index.map { it.title }).containsExactly("第一段", "第二段")

        val first = target.load(index.first { it.title == "第一段" }.id)
        assertThat(first).isNotNull()
        val messages = first!!.messages
        assertThat(messages.map { it.content })
            .containsExactly("你好", "你好，有什么可以帮你", "讲个笑话", "从前有座山").inOrder()
        assertThat(messages.map { it.role })
            .containsExactly(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT).inOrder()
        // 思考内容单独存：混进正文会污染下一次请求的上下文
        assertThat(messages[1].reasoning).isEqualTo("先想一下")
        assertThat(messages[1].content).doesNotContain("先想一下")
    }

    @Test
    fun `import follows current_message_id instead of every branch`() = runTest {
        val zip = zipOf(
            "manifest.json" to """{"export_format_version":3}""",
            "chat_sessions/s1.json" to """
                {
                  "id": "s1", "title": "换过一次回答", "pinned": false,
                  "current_message_id": 3,
                  "inserted_at": "2026-11-01T00:00:00.000Z",
                  "updated_at": "2026-11-01T00:01:00.000Z",
                  "messages": [
                    {"id":1,"parent_id":null,"role":"USER","status":"FINISHED",
                     "thinking_enabled":false,"search_enabled":false,
                     "accumulated_token_usage":0,"inserted_at":"2026-11-01T00:00:00.000Z",
                     "fragments":[{"type":"REQUEST","content":"问题"}]},
                    {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED",
                     "thinking_enabled":false,"search_enabled":false,
                     "accumulated_token_usage":10,"inserted_at":"2026-11-01T00:00:30.000Z",
                     "fragments":[{"type":"RESPONSE","content":"旧回答"}]},
                    {"id":3,"parent_id":1,"role":"ASSISTANT","status":"FINISHED",
                     "thinking_enabled":false,"search_enabled":false,
                     "accumulated_token_usage":10,"inserted_at":"2026-11-01T00:01:00.000Z",
                     "fragments":[{"type":"RESPONSE","content":"新回答"}]}
                  ]
                }
            """.trimIndent(),
        )

        val target = store("c")
        DeepSeekImporter(tmp.root, target).import(ByteArrayInputStream(zip))

        val loaded = target.load(target.index().single().id)!!
        assertThat(loaded.messages.map { it.content }).containsExactly("问题", "新回答").inOrder()
    }

    @Test
    fun `refuses an archive from a newer version`() = runTest {
        val zip = zipOf("manifest.json" to """{"export_format_version":99}""")
        val failure = runCatching {
            DeepSeekImporter(tmp.root, store("d")).import(ByteArrayInputStream(zip))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ArchiveException::class.java)
        assertThat((failure as ArchiveException).code).isEqualTo(ArchiveException.Code.TOO_NEW)
    }

    @Test
    fun `refuses a zip that is not a DeepSeek export`() = runTest {
        val zip = zipOf("notes.txt" to "随手打包的一个 zip")
        val failure = runCatching {
            DeepSeekImporter(tmp.root, store("e")).import(ByteArrayInputStream(zip))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ArchiveException::class.java)
        assertThat((failure as ArchiveException).code)
            .isEqualTo(ArchiveException.Code.NOT_DEEPSEEK)
    }

    @Test
    fun `images travel with the archive`() = runTest {
        val png = ByteArray(64) { it.toByte() }
        val raw = File(tmp.newFolder("src"), "shot.png").apply { writeBytes(png) }
        val source = store("f")
        source.save(
            StoredConversation(
                meta = conversation("c3", "带图的对话", 1_700_000_000_000),
                messages = listOf(
                    message(
                        id = "p1",
                        role = Role.USER,
                        text = "看这张图",
                        at = 1_700_000_000_000,
                        attachments = listOf(
                            Attachment(
                                id = "att1",
                                name = "shot.png",
                                mime = "image/png",
                                sizeBytes = png.size.toLong(),
                                kind = AttachmentKinds.IMAGE,
                                path = raw.absolutePath,
                            ),
                        ),
                    ),
                    message("p2", Role.ASSISTANT, "看到了", 1_700_000_001_000),
                ),
            ),
        )

        val bytes = ByteArrayOutputStream().also { DeepSeekExporter(source).export(it) }.toByteArray()

        val target = store("g")
        val outcome = DeepSeekImporter(tmp.root, target).import(ByteArrayInputStream(bytes))
        assertThat(outcome.images).isEqualTo(1)

        val att = target.load(target.index().single().id)!!.messages.first().attachments.single()
        assertThat(att.kind).isEqualTo(AttachmentKinds.IMAGE)
        assertThat(att.ok).isTrue()
        assertThat(File(att.path).readBytes()).isEqualTo(png)
    }

    @Test
    fun `archive layout stays compatible with the official reader`() = runTest {
        val source = store("h")
        source.save(
            StoredConversation(
                meta = conversation("c4", "结构", 1_700_000_000_000),
                messages = listOf(
                    message("q1", Role.USER, "问", 1_700_000_000_000),
                    message("q2", Role.ASSISTANT, "答", 1_700_000_001_000),
                ),
            ),
        )

        val bytes = ByteArrayOutputStream().also { DeepSeekExporter(source).export(it) }.toByteArray()
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zip.nextEntry
            }
        }

        // manifest 必须第一个 —— 官方靠它判断「这份文件能不能读」
        assertThat(names.first()).isEqualTo("manifest.json")
        assertThat(names).contains("chat_sessions/c4.json")
        assertThat(names).contains("chat_session_index.json")
        // 索引排在会话之后、图片之前
        assertThat(names.indexOf("chat_session_index.json"))
            .isGreaterThan(names.indexOf("chat_sessions/c4.json"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
