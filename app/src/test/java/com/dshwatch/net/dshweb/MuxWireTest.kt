package com.dshwatch.net.dshweb

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * 帧解析的单测。
 *
 * 这些用例的形状全部照 ds-harness-remote v1 的真实字段写：
 * `snapshot` 的历史在 `records[].event`，`assistant/message` 的消息在 `data.message`，
 * 而 `user/message` 的消息就是 `data` 本身 —— 这个不对称最容易写错，
 * 写错的表现是「聊天页只有用户消息、没有回答」，且不报任何错。
 */
class MuxWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(text: String) = MuxWire.parseFollow(json.parseToJsonElement(text).jsonObject)

    @Test
    fun `快照里同时取出用户和助手消息`() {
        val frame = """
            {"type":"snapshot","cursor":12,"hasMore":false,
             "records":[
               {"event":{"seq":10,"time":1700000000000,"type":"user/message",
                         "data":{"role":"user","content":[{"type":"text","text":"你好"}]}}},
               {"event":{"seq":11,"time":1700000000100,"type":"assistant/message",
                         "data":{"role":"assistant","message":{"role":"assistant",
                           "content":[{"type":"text","text":"在的，有什么可以帮你"}]}}}}
             ]}
        """.trimIndent()

        val event = parse(frame) as MuxEvent.Snapshot
        assertThat(event.cursor).isEqualTo(12)
        assertThat(event.messages.map { it.role }).containsExactly("user", "assistant").inOrder()
        assertThat(event.messages.map { it.text }).containsExactly("你好", "在的，有什么可以帮你").inOrder()
    }

    @Test
    fun `工具调用和思考步骤不会变成聊天气泡`() {
        val frame = """
            {"type":"snapshot","cursor":3,
             "records":[
               {"event":{"seq":1,"type":"turn/start","data":{"turn":1}}},
               {"event":{"seq":2,"type":"tool/call","data":{"callId":"c1","name":"read_file","arguments":"{}"}}},
               {"event":{"seq":3,"type":"tool/result","data":{"message":{"role":"tool",
                         "content":[{"type":"text","text":"文件内容"}]}}}}
             ]}
        """.trimIndent()

        val event = parse(frame) as MuxEvent.Snapshot
        assertThat(event.messages).isEmpty()
    }

    @Test
    fun `助手正文增量取 text-delta，思考过程丢掉`() {
        val text = """
            {"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a1","revision":2,"index":1,
             "chunk":{"type":"text-delta","text":"正在"}}}
        """.trimIndent()
        assertThat(parse(text)).isEqualTo(MuxEvent.Delta("正在"))

        val reasoning = """
            {"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a1","revision":3,"index":2,
             "chunk":{"type":"reasoning-delta","text":"想一想"}}}
        """.trimIndent()
        assertThat(parse(reasoning)).isNull()
    }

    @Test
    fun `回合结束能被识别出来`() {
        val frame = """{"type":"event","event":{"seq":9,"type":"turn/end","data":{"turn":1,"reason":"completed"}}}"""
        assertThat(parse(frame)).isEqualTo(MuxEvent.TurnEnded)
    }

    @Test
    fun `空文本不产生气泡`() {
        val frame = """
            {"type":"snapshot","records":[
              {"event":{"seq":1,"type":"user/message","data":{"role":"user","content":[{"type":"text","text":"   "}]}}}
            ]}
        """.trimIndent()
        assertThat((parse(frame) as MuxEvent.Snapshot).messages).isEmpty()
    }
}
