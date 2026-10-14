package com.dshwatch

import com.dshwatch.net.SseReader
import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.Test

class SseReaderTest {

    private fun readerOf(raw: String) = SseReader(Buffer().writeUtf8(raw))

    @Test
    fun `reads a single data event`() {
        val reader = readerOf("data: {\"a\":1}\n\n")
        val event = reader.read()
        assertThat(event).isNotNull()
        assertThat(event!!.data).isEqualTo("{\"a\":1}")
        assertThat(event.name).isNull()
    }

    @Test
    fun `joins multiple data lines with newline`() {
        val event = readerOf("data: line1\ndata: line2\n\n").read()
        assertThat(event!!.data).isEqualTo("line1\nline2")
    }

    @Test
    fun `exposes event name`() {
        val event = readerOf("event: delta\ndata: hi\n\n").read()
        assertThat(event!!.name).isEqualTo("delta")
        assertThat(event.data).isEqualTo("hi")
    }

    @Test
    fun `ignores comments and heartbeat lines`() {
        val reader = readerOf(": ping\n\ndata: real\n\n")
        assertThat(reader.read()!!.data).isEqualTo("real")
    }

    @Test
    fun `returns null at end of stream`() {
        val reader = readerOf("data: only\n\n")
        assertThat(reader.read()).isNotNull()
        assertThat(reader.read()).isNull()
    }

    @Test
    fun `streams chunk by chunk in order`() {
        val reader = readerOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                "data: [DONE]\n\n"
        )
        val all = generateSequence { reader.read() }.map { it.data }.toList()
        assertThat(all).hasSize(3)
        assertThat(all.last()).isEqualTo("[DONE]")
    }
}
