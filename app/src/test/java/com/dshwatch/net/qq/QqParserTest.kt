package com.dshwatch.net.qq

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * OneBot 消息解析。
 *
 * 这一层的每个 case 都对应一个真实踩过的坑：
 *  - `user_id` 有时是数字有时是字符串；
 *  - `message` 有时是数组、有时是 CQ 码字符串；
 *  - 群消息的 `user_id` 是**发话人**，不是会话对象。
 */
class QqParserTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `array message keeps text image and at`() {
        val m = QqParser.message(
            obj(
                """
                {
                  "message_id": 1001,
                  "user_id": 88888,
                  "time": 1700000000,
                  "sender": {"user_id": 88888, "nickname": "小明"},
                  "message": [
                    {"type": "at", "data": {"qq": "12345"}},
                    {"type": "text", "data": {"text": " 这张图里是什么"}},
                    {"type": "image", "data": {"file": "a.jpg", "url": "https://x/a.jpg"}}
                  ]
                }
                """.trimIndent()
            ),
            isGroup = true,
            fallbackPeer = "555",
        )!!

        assertThat(m.id).isEqualTo("1001")
        assertThat(m.senderId).isEqualTo("88888")
        assertThat(m.senderName).isEqualTo("小明")
        assertThat(m.text.trim()).isEqualTo("这张图里是什么")
        assertThat(m.atTargets).containsExactly("12345")
        assertThat(m.imageUrls).containsExactly("https://x/a.jpg")
        assertThat(m.mentions("12345")).isTrue()
        assertThat(m.mentions("999")).isFalse()
    }

    @Test
    fun `group message peer is group id not sender`() {
        // 用 sender 当 peer 会把回复发到陌生人的私聊里 —— 这条专门钉住它
        val m = QqParser.message(
            obj(
                """
                {"message_id":1,"group_id":777,"user_id":888,
                 "message":[{"type":"text","data":{"text":"hi"}}]}
                """.trimIndent()
            ),
            isGroup = true,
        )!!
        assertThat(m.peerId).isEqualTo("777")
        assertThat(m.senderId).isEqualTo("888")
    }

    @Test
    fun `cq string message is parsed`() {
        val m = QqParser.message(
            obj(
                """
                {"message_id":2,"user_id":100,
                 "message":"[CQ:at,qq=42] 你好 [CQ:image,file=b.png,url=https://y/b.png]"}
                """.trimIndent()
            ),
            isGroup = false,
        )!!
        assertThat(m.atTargets).containsExactly("42")
        assertThat(m.text).contains("你好")
        assertThat(m.imageUrls).containsExactly("https://y/b.png")
    }

    @Test
    fun `cq escapes are decoded`() {
        val segs = QqParser.parseCq("a&#91;1&#93;&#44;b")
        assertThat(segs).hasSize(1)
        assertThat(segs[0].text).isEqualTo("a[1],b")
    }

    @Test
    fun `reply segment carries message id`() {
        val m = QqParser.message(
            obj(
                """
                {"message_id":3,"user_id":100,
                 "message":[{"type":"reply","data":{"id":"9000"}},{"type":"text","data":{"text":"继续说"}}]}
                """.trimIndent()
            ),
            isGroup = true,
        )!!
        assertThat(m.replyId).isEqualTo("9000")
    }

    @Test
    fun `numeric and string ids both work`() {
        val a = QqParser.message(obj("""{"message_id":"12","user_id":"34","message":[]}"""), false)!!
        assertThat(a.id).isEqualTo("12")
        assertThat(a.senderId).isEqualTo("34")
    }

    @Test
    fun `message without id is dropped`() {
        // 没有 message_id 就没法去重，宁可丢一条也不能重复回复
        val m = QqParser.message(obj("""{"user_id":1,"message":[]}"""), false)
        assertThat(m).isNull()
    }

    @Test
    fun `outgoing builds text then images`() {
        val segs = QqOut.of("  你好  ", listOf("AAA", "BBB"))
        assertThat(segs.map { it.type }).containsExactly("text", "image", "image").inOrder()
        assertThat(segs[1].file).isEqualTo("base64://AAA")
    }

    @Test
    fun `empty text produces no segment`() {
        assertThat(QqOut.of("   ")).isEmpty()
    }
}
