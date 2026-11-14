package com.dshwatch.net.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Noise IK 的自证测试。
 *
 * 没有真服务器可用，所以这里让「发起方」和「响应方」跑一遍完整握手：
 * 两边独立推导，只有全部按协议规定来，最後的会话密钥才会一致。
 * 这不能证明对端是 dshgo，但能证明**没有把字节顺序、HKDF 段、nonce 编码搞错**
 * —— 那些错误在单边实现里是看不出来的。
 */
class NoiseIkTest {

    private val alice = NoiseIk.newPrivateKey()      // 客户端（发起方）
    private val bob = NoiseIk.newPrivateKey()        // 主机（响应方）
    private val alicePub = NoiseIk.publicOf(alice)
    private val bobPub = NoiseIk.publicOf(bob)

    @Test
    fun `完整握手之后两边密钥一致且能双向通信`() {
        val client = NoiseIk.initiator(alice, bobPub)
        val host = NoiseIk.responder(bob, alicePub)

        val msg1 = client.writeHandshake()
        val payload1 = host.readHandshake(msg1)
        assertTrue(payload1.isEmpty())

        val msg2 = host.writeHandshake()
        val payload2 = client.readHandshake(msg2)
        assertTrue(payload2.isEmpty())

        assertTrue(client.complete)
        assertTrue(host.complete)

        val fromClient = client.encrypt("你好，电脑".toByteArray())
        assertArrayEquals("你好，电脑".toByteArray(), host.decrypt(fromClient))

        val fromHost = host.encrypt("收到".toByteArray())
        assertArrayEquals("收到".toByteArray(), client.decrypt(fromHost))
    }

    @Test
    fun `握手消息长度符合 IK 的固定结构`() {
        val client = NoiseIk.initiator(alice, bobPub)
        val host = NoiseIk.responder(bob, alicePub)

        // -> e(32) , encrypted_s(32+16) , payload(0+16)
        val msg1 = client.writeHandshake()
        assertEquals(96, msg1.size)

        // <- e(32) , payload(0+16)
        host.readHandshake(msg1)
        val msg2 = host.writeHandshake()
        assertEquals(48, msg2.size)
    }

    @Test
    fun `同一对密钥与同一临时密钥得到逐字节相同的握手消息`() {
        val seed = ByteArray(32) { (it * 7 + 1).toByte() }
        val a = NoiseIk.initiator(alice, bobPub, ephemeralSeed = seed).writeHandshake()
        val b = NoiseIk.initiator(alice, bobPub, ephemeralSeed = seed).writeHandshake()
        assertArrayEquals(a, b)
    }

    @Test
    fun `篡改一个字节就会被拒绝`() {
        val client = NoiseIk.initiator(alice, bobPub)
        val host = NoiseIk.responder(bob, alicePub)
        val msg1 = client.writeHandshake()
        // 改密文区（跳过临时公钥 32 字节）—— 正常实现应该认证失败
        msg1[40] = (msg1[40].toInt() xor 0x01).toByte()
        try {
            host.readHandshake(msg1)
            fail("篡改过的握手消息不该被接受")
        } catch (expected: NoiseError) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `对端身份不符时拒绝握手`() {
        val mallory = NoiseIk.newPrivateKey()
        val client = NoiseIk.initiator(mallory, bobPub)
        // 主机以为来的是 alice，实际是 mallory
        val host = NoiseIk.responder(bob, alicePub)
        try {
            host.readHandshake(client.writeHandshake())
            fail("静态公钥不匹配时必须中止")
        } catch (expected: NoiseError) {
            assertTrue(expected.message.orEmpty().contains("身份"))
        }
    }

    @Test
    fun `握手完成前不能发数据`() {
        val client = NoiseIk.initiator(alice, bobPub)
        assertFalse(client.complete)
        try {
            client.encrypt(byteArrayOf(1, 2, 3))
            fail("握手没完成就不该允许加密")
        } catch (expected: IllegalStateException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `密文重放会被拒绝`() {
        val client = NoiseIk.initiator(alice, bobPub)
        val host = NoiseIk.responder(bob, alicePub)
        host.readHandshake(client.writeHandshake())
        client.readHandshake(host.writeHandshake())

        val once = client.encrypt("a".toByteArray())
        assertArrayEquals("a".toByteArray(), host.decrypt(once))
        try {
            host.decrypt(once)
            fail("同一条密文解密两次必须失败：nonce 已经前进")
        } catch (expected: NoiseError) {
            assertTrue(expected.message.orEmpty().contains("认证"))
        }
    }

    @Test
    fun `身份密钥的 base64url 长度符合服务端校验`() {
        val key = NoiseIk.publicOf(alice).toBase64Url()
        assertEquals(43, key.length)
        // 服务端规则：只有最后一位是 4 位一组的收尾，因而取值受限
        assertTrue(Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$").matches(key))
    }

    @Test
    fun `指纹稳定且可读`() {
        val a = NoiseIk.fingerprintOf(alicePub)
        val b = NoiseIk.fingerprintOf(alicePub)
        assertEquals(a, b)
        assertEquals(16, a.length)
    }
}
