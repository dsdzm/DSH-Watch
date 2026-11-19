package com.dshwatch.domain.qq

import com.dshwatch.domain.model.QqSettings
import com.dshwatch.net.qq.QqMessage
import com.dshwatch.net.qq.QqParser
import com.dshwatch.net.qq.QqSegment
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * 触发判定。
 *
 * 判错一次就是「群里刷屏」或「@ 了不理」，而这两种情况都没有报错可看，
 * 所以每条规则都要有一个反例钉住。
 */
class QqTriggerTest {

    private fun msg(
        text: String = "",
        group: Boolean = false,
        sender: String = "888",
        peer: String = "777",
        ats: List<String> = emptyList(),
        replyTo: String = "",
    ): QqMessage = QqMessage(
        id = "1",
        peerId = peer,
        isGroup = group,
        senderId = sender,
        senderName = "某人",
        timeSec = 1,
        segments = buildList {
            ats.forEach { add(QqSegment("at", qq = it)) }
            if (replyTo.isNotBlank()) add(QqSegment("reply", id = replyTo))
            if (text.isNotBlank()) add(QqSegment("text", text = text))
        },
    )

    private val base = QqSettings(enabled = true, groupPeer = "777", privatePeer = "888")

    @Test
    fun `private message is answered`() {
        assertThat(QqTrigger.shouldReply(msg("在吗"), base, "1")).isTrue()
    }

    @Test
    fun `own message is ignored`() {
        // 机器人自己发出去的消息会出现在历史里，不过滤就会自问自答到天亮
        assertThat(QqTrigger.shouldReply(msg("我刚说过的话", sender = "1"), base, "1")).isFalse()
    }

    @Test
    fun `group needs mention by default`() {
        val cfg = base.copy(groupEnabled = true)
        assertThat(QqTrigger.shouldReply(msg("大家好", group = true), cfg, "1")).isFalse()
        assertThat(QqTrigger.shouldReply(msg("@你 在吗", group = true, ats = listOf("1")), cfg, "1"))
            .isTrue()
    }

    @Test
    fun `mention someone else is not a trigger`() {
        val cfg = base.copy(groupEnabled = true)
        assertThat(QqTrigger.shouldReply(msg("在吗", group = true, ats = listOf("999")), cfg, "1"))
            .isFalse()
    }

    @Test
    fun `prefix triggers without at`() {
        val cfg = base.copy(groupEnabled = true, prefixes = "小D,ds")
        assertThat(QqTrigger.shouldReply(msg("小D 今天几号", group = true), cfg, "1")).isTrue()
        assertThat(QqTrigger.shouldReply(msg("DS，帮我看看", group = true), cfg, "1")).isTrue()
        assertThat(QqTrigger.shouldReply(msg("小明今天几号", group = true), cfg, "1")).isFalse()
    }

    @Test
    fun `quoting my own message counts as a mention`() {
        // 群里很多人不用 @，长按引用我上一条再接话 —— 只认 @ 就会漏掉一半点名
        val cfg = base.copy(groupEnabled = true)
        assertThat(
            QqTrigger.shouldReply(
                msg("这句什么意思", group = true, replyTo = "4444"),
                cfg,
                "1",
                myMessageIds = setOf("4444"),
            )
        ).isTrue()
        // 引用的是别人的话，不该接
        assertThat(
            QqTrigger.shouldReply(
                msg("同意", group = true, replyTo = "5555"),
                cfg,
                "1",
                myMessageIds = setOf("4444"),
            )
        ).isFalse()
        // 没记着任何 id 时（刚重启）退回按 @ / 前缀判断
        assertThat(QqTrigger.shouldReply(msg("随便说", group = true, replyTo = "4444"), cfg, "1"))
            .isFalse()
    }

    @Test
    fun `requireAt off means every group message is answered`() {
        val cfg = base.copy(groupEnabled = true, requireAt = false)
        assertThat(QqTrigger.shouldReply(msg("随便说一句", group = true), cfg, "1")).isTrue()
    }

    @Test
    fun `group disabled never replies`() {
        val cfg = base.copy(groupEnabled = false, requireAt = false)
        assertThat(QqTrigger.shouldReply(msg("在吗", group = true, ats = listOf("1")), cfg, "1"))
            .isFalse()
    }

    @Test
    fun `other group is filtered`() {
        val cfg = base.copy(groupEnabled = true, requireAt = false)
        assertThat(QqTrigger.shouldReply(msg("x", group = true, peer = "999"), cfg, "1")).isFalse()
    }

    @Test
    fun `prefix is stripped with separators`() {
        val p = listOf("小D")
        assertThat(QqTrigger.cleanInput("小D：今天几号", p)).isEqualTo("今天几号")
        assertThat(QqTrigger.cleanInput("小D, 你好", p)).isEqualTo("你好")
        assertThat(QqTrigger.cleanInput("小D", p)).isEmpty()
        assertThat(QqTrigger.cleanInput("无关内容", p)).isEqualTo("无关内容")
    }

    @Test
    fun `prefix matching is case insensitive but keeps body`() {
        val p = listOf("DS")
        assertThat(QqTrigger.cleanInput("ds 在吗", p)).isEqualTo("在吗")
    }

    @Test
    fun `parsed message feeds trigger end to end`() {
        val m = QqParser.message(
            Json.parseToJsonElement(
                """
                {"message_id":9,"group_id":777,"user_id":888,
                 "message":[{"type":"at","data":{"qq":"1"}},
                            {"type":"text","data":{"text":"帮我写个开场白"}}]}
                """.trimIndent()
            ).jsonObject,
            isGroup = true,
        )!!
        val cfg = base.copy(groupEnabled = true)
        assertThat(QqTrigger.shouldReply(m, cfg, "1")).isTrue()
        assertThat(QqTrigger.cleanInput(m.text, cfg.prefixList())).isEqualTo("帮我写个开场白")
    }
}
