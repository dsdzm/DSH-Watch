package com.dshwatch.domain

import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 对话的纯文本导入 / 导出。
 *
 * 这里守的是三件事：
 *  1. 导出的文本必须严格是约定的格式 —— 用户会拿它去对照、去手工拼；
 *  2. 导出再导入必须能还原（除了被主动丢弃的空壳 / 错误消息）；
 *  3. 手工拼的、被输入法折过行的文本也要能读进来，否则这个功能对用户就是坏的。
 */
class ChatTranscriptTest {

    private fun msg(role: Role, text: String, status: MessageStatus = MessageStatus.DONE) =
        ChatMessage(id = "id-" + text.take(4), role = role, content = text, status = status)

    @Test
    fun `encode renders the agreed format exactly`() {
        val text = ChatTranscript.encode(
            listOf(
                msg(Role.USER, "你好"),
                msg(Role.ASSISTANT, "在。"),
            )
        )
        assertThat(text).isEqualTo(
            "#===========================================#\n" +
                "[USER]:\n" +
                "你好\n" +
                "#-------------------------------------------#\n" +
                "[AI]:\n" +
                "在。\n"
        )
    }

    @Test
    fun `each user message opens a new block`() {
        val text = ChatTranscript.encode(
            listOf(
                msg(Role.USER, "一"),
                msg(Role.ASSISTANT, "1"),
                msg(Role.USER, "二"),
                msg(Role.ASSISTANT, "2"),
            )
        )
        // 两个块，块之间空一行；块内只有一个分隔线
        assertThat(text.split("#===========================================#")).hasSize(3)
        assertThat(text.split("#-------------------------------------------#")).hasSize(3)
    }

    @Test
    fun `empty and failed messages are dropped`() {
        val text = ChatTranscript.encode(
            listOf(
                msg(Role.USER, "  "),
                msg(Role.USER, "提问"),
                msg(Role.ASSISTANT, "断在半截", MessageStatus.ERROR),
                msg(Role.ASSISTANT, "回答"),
            )
        )
        assertThat(text).doesNotContain("断在半截")
        assertThat(text).contains("提问")
        assertThat(text).contains("回答")
    }

    @Test
    fun `multi step assistant replies merge into one segment`() {
        val text = ChatTranscript.encode(
            listOf(
                msg(Role.USER, "跑一下"),
                msg(Role.ASSISTANT, "第一步"),
                msg(Role.ASSISTANT, "第二步"),
            )
        )
        assertThat(text.split("[AI]")).hasSize(2)
        assertThat(text).contains("第一步\n\n第二步")
    }

    @Test
    fun `round trip keeps content and order`() {
        val src = listOf(
            msg(Role.USER, "把这段改短\n第二行"),
            msg(Role.ASSISTANT, "改好了：\n- 一\n- 二"),
            msg(Role.USER, "再短点"),
        )
        val turns = ChatTranscript.parse(ChatTranscript.encode(src))
        assertThat(turns.map { it.role })
            .containsExactly(Role.USER, Role.ASSISTANT, Role.USER).inOrder()
        assertThat(turns[0].text).isEqualTo("把这段改短\n第二行")
        assertThat(turns[1].text).isEqualTo("改好了：\n- 一\n- 二")
        assertThat(turns[2].text).isEqualTo("再短点")
    }

    @Test
    fun `parse tolerates hand written markers`() {
        val raw = """
            #===#
            [用户]：
            第一句
            #-----#
            [助手]
            第二句
            ［AI］:第三句
        """.trimIndent()
        val turns = ChatTranscript.parse(raw)
        assertThat(turns.map { it.role })
            .containsExactly(Role.USER, Role.ASSISTANT, Role.ASSISTANT).inOrder()
        assertThat(turns[0].text).isEqualTo("第一句")
        assertThat(turns[1].text).isEqualTo("第二句")
        assertThat(turns[2].text).isEqualTo("第三句")
    }

    @Test
    fun `text without any marker becomes one user message`() {
        val turns = ChatTranscript.parse("一段没有任何标记的设定文字")
        assertThat(turns).hasSize(1)
        assertThat(turns[0].role).isEqualTo(Role.USER)
        assertThat(turns[0].text).isEqualTo("一段没有任何标记的设定文字")
    }

    @Test
    fun `bracketed prose is not mistaken for a marker`() {
        // 正文里出现「[注]:」这类写法时，不能把它当成分段标记
        val turns = ChatTranscript.parse(
            """
            [USER]:
            引用了一句 [注]: 这不是角色标记
            """.trimIndent()
        )
        assertThat(turns).hasSize(1)
        assertThat(turns[0].text).contains("[注]: 这不是角色标记")
    }

    @Test
    fun `title comes from the first user line`() {
        val turns = ChatTranscript.parse(
            """
            [USER]:
            雨下到第九天
            涵洞的水位漫过了标尺
            [AI]:
            他抬头看了看。
            """.trimIndent()
        )
        assertThat(ChatTranscript.titleOf(turns)).isEqualTo("雨下到第九天")
    }

    @Test
    fun `title is clipped for the watch list`() {
        val long = "一".repeat(40)
        val turns = listOf(ChatTranscript.Turn(Role.USER, long))
        assertThat(ChatTranscript.titleOf(turns).length).isAtMost(21)
    }
}
