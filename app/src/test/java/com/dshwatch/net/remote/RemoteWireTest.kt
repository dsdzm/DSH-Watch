package com.dshwatch.net.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 隧道线格式的单元测试：分片、信封、prologue。都是纯字节，不碰网络 */
class RemoteWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `小消息不切分片`() {
        val framing = SecureFraming()
        val message = ByteArray(100) { it.toByte() }
        val frames = framing.encode(message)
        assertEquals(1, frames.size)
        assertArrayEquals(message, framing.decode(frames[0]))
    }

    @Test
    fun `大消息切片后能按序拼回来`() {
        val framing = SecureFraming()
        // 刚好越过阈值 1 字节：最容易在 "整除" 上写错边界
        val message = ByteArray(RemoteProtocol.CHUNK_BYTES + 1) { (it % 251).toByte() }
        val frames = framing.encode(message)
        assertEquals(2, frames.size)
        assertNull("第一片还不能返回", framing.decode(frames[0]))
        val restored = framing.decode(frames[1])
        assertNotNull(restored)
        assertArrayEquals(message, restored)
    }

    @Test
    fun `乱序的分片会被拒绝`() {
        val framing = SecureFraming()
        val message = ByteArray(RemoteProtocol.CHUNK_BYTES * 2 + 10)
        val frames = framing.encode(message)
        assertEquals(3, frames.size)
        // 直接跳到最后一片：缺了前两片，不能装作没事把它交出去
        var rejected = false
        try {
            framing.decode(frames[2])
        } catch (e: RemoteError) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `信封缺少 timestamp 会被拒绝`() {
        val wire = RemoteWire(json)
        val bad = """{"v":1,"id":"a","type":"hello","payload":{}}"""
        var rejected = false
        try {
            wire.decode(bad)
        } catch (e: RemoteError) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `prologue 把连接与两端设备绑在一起`() {
        val a = RemoteProtocol.prologue("c1", "h1", "w1")
        val b = RemoteProtocol.prologue("c1", "h1", "w2")
        assertEquals("DSH-REMOTE\u0000v=1\u0000connection=c1\u0000host=h1\u0000client=w1", String(a))
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `隧道地址按协议换 scheme`() {
        assertEquals("wss://remote.example.com/ws/v1/connect", RemoteProtocol.controlUrl("https://remote.example.com"))
        assertEquals("ws://192.168.1.5:8080/ws/v1/connect", RemoteProtocol.controlUrl("http://192.168.1.5:8080"))
    }

    @Test
    fun `同一份 prologue 的 Noise 握手能互通`() {
        val clientPrivate = NoiseIk.newPrivateKey()
        val hostPrivate = NoiseIk.newPrivateKey()
        val prologue = RemoteProtocol.prologue("conn-9", "host-9", "watch-9")
        val client = NoiseIk.initiator(clientPrivate, NoiseIk.publicOf(hostPrivate), prologue = prologue)
        val host = NoiseIk.responder(hostPrivate, NoiseIk.publicOf(clientPrivate), prologue = prologue)

        host.readHandshake(client.writeHandshake())
        client.readHandshake(host.writeHandshake())
        assertTrue(client.complete)
        assertTrue(host.complete)
        assertArrayEquals(
            "审批已允许".toByteArray(),
            host.decrypt(client.encrypt("审批已允许".toByteArray())),
        )
    }

    @Test
    fun `prologue 不同则握手失败`() {
        val clientPrivate = NoiseIk.newPrivateKey()
        val hostPrivate = NoiseIk.newPrivateKey()
        val client = NoiseIk.initiator(
            clientPrivate,
            NoiseIk.publicOf(hostPrivate),
            prologue = RemoteProtocol.prologue("conn-a", "host-9", "watch-9"),
        )
        val host = NoiseIk.responder(
            hostPrivate,
            NoiseIk.publicOf(clientPrivate),
            prologue = RemoteProtocol.prologue("conn-b", "host-9", "watch-9"),
        )
        var failed = false
        try {
            host.readHandshake(client.writeHandshake())
        } catch (e: Throwable) {
            failed = true
        }
        assertTrue("Prologue 不一致时必须失败", failed)
    }
}
