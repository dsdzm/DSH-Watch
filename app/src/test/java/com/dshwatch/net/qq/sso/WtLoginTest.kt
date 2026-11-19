package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

/**
 * 登录包结构回归。
 *
 * 期望值不是「我觉得应该这样」，而是把 MiraiGo 的 `buildQRCodeLoginPacket`
 * 用同一套固定设备参数跑出来的真实字节 —— 24 条 TLV 的 tag 与长度、
 * 以及 0x144 解密后的明文，都逐字节对过。
 *
 * 为什么盯得这么死：这些长度错一位，服务端不会报错，只会安静地返回一个
 * 结果码，从客户端完全看不出是哪条错了。
 */
class WtLoginTest {

    private val device = WatchDevice(
        guid = ByteArray(16) { it.toByte() },
        imei = "861234567890123",
    )

    private val profile = DeviceProfile(device)

    private val body = WtLogin.body(
        device = device,
        profile = profile,
        uin = 878_461_730,
        tmpPwd = "TMPPWD".toByteArray(),
        noPicSig = "NOPICSIG".toByteArray(),
        tgtQr = "TGTQR".toByteArray(),
        seq = 4321,
        random = 0,
    )

    @Test
    fun `正文长度与官方一致`() {
        assertThat(body.size).isEqualTo(1007)
    }

    @Test
    fun `24 条 TLV 的 tag 与长度逐条一致`() {
        val expected = listOf(
            0x0018 to 22, 0x0001 to 20, 0x0106 to 6, 0x0116 to 14, 0x0100 to 22, 0x0107 to 6,
            0x0142 to 22, 0x0144 to 376, 0x0145 to 16, 0x0147 to 29, 0x016A to 8, 0x0154 to 4,
            0x0141 to 28, 0x0008 to 8, 0x0511 to 211, 0x0187 to 16, 0x0188 to 16, 0x0194 to 16,
            0x0191 to 1, 0x0202 to 34, 0x0177 to 17, 0x0516 to 4, 0x0521 to 6, 0x0318 to 5,
        )
        assertThat(parse(body)).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `0x144 明文与官方一致`() {
        val t144 = tlvOf(body, 0x144)
        val plain = Tea.decrypt(t144, profile.tgtgtKey)
        val digest = MessageDigest.getInstance("SHA-256").digest(plain)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        // 366 字节：5 个子 TLV，含 protobuf 设备信息、GUID、机型、品牌
        assertThat(plain.size).isEqualTo(366)
        assertThat(digest).isEqualTo("38a664ac84b56a825ade9bd290db9b4273be6d1b98884b8a3c1c93964480851b")
    }

    @Test
    fun `0x144 用的是传入的 tgtgtKey，不是本地派生那把`() {
        // 扫码流程里真正的 key 由服务端 0x1e 下发。若这里继续用 profile 派生的那把，
        // 0x144 能加密出去、0x119 却解不开 —— 表现为「登录成功但拿不到 D2」。
        val key = ByteArray(16) { 7 }
        val other = WtLogin.body(
            device = device,
            profile = profile,
            uin = 878_461_730,
            tmpPwd = "TMPPWD".toByteArray(),
            noPicSig = "NOPICSIG".toByteArray(),
            tgtQr = "TGTQR".toByteArray(),
            seq = 4321,
            random = 0,
            tgtgtKey = key,
        )
        val plain = Tea.decrypt(tlvOf(other, 0x144), key)
        assertThat(plain).isEqualTo(Tea.decrypt(tlvOf(body, 0x144), profile.tgtgtKey))
        // 用本地派生那把解同一段密文：TEA 尾部校验不过，直接抛 —— 这就是线上
        // 「登录包发出去了、0x119 解不开」的样子
        assertThat(runCatching { Tea.decrypt(tlvOf(other, 0x144), profile.tgtgtKey) }.isFailure)
            .isTrue()
    }

    @Test
    fun `设备画像由 guid 派生且稳定`() {
        val a = DeviceProfile(device)
        val b = DeviceProfile(WatchDevice(device.guid, device.imei))
        assertThat(a.tgtgtKey).isEqualTo(b.tgtgtKey)
        assertThat(a.androidId).isEqualTo(b.androidId)
        assertThat(String(a.androidId)).hasLength(16)
        // 换一副 guid 就必须换一套画像，否则成了「同一台设备两个指纹」
        val other = DeviceProfile(WatchDevice(ByteArray(16) { (it + 1).toByte() }, device.imei))
        assertThat(other.tgtgtKey).isNotEqualTo(a.tgtgtKey)
    }

    @Test
    fun `设备信息 protobuf 的字段号与长度前缀正确`() {
        val proto = profile.deviceInfoProto()
        // 第一个字段是 bootloader："unknown" → 0x0A 0x07 'unknown'
        assertThat(proto[0].toInt()).isEqualTo(0x0A)
        assertThat(proto[1].toInt()).isEqualTo(7)
        assertThat(String(proto, 2, 7, Charsets.UTF_8)).isEqualTo("unknown")
    }

    // ---------------- 工具 ----------------

    private fun parse(raw: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 4 // 跳过 [2 命令][2 个数]
        while (i + 4 <= raw.size) {
            val tag = ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
            val len = ((raw[i + 2].toInt() and 0xFF) shl 8) or (raw[i + 3].toInt() and 0xFF)
            out += tag to len
            i += 4 + len
        }
        return out
    }

    private fun tlvOf(raw: ByteArray, want: Int): ByteArray {
        var i = 4
        while (i + 4 <= raw.size) {
            val tag = ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
            val len = ((raw[i + 2].toInt() and 0xFF) shl 8) or (raw[i + 3].toInt() and 0xFF)
            if (tag == want) return raw.copyOfRange(i + 4, i + 4 + len)
            i += 4 + len
        }
        throw IllegalArgumentException("没有 0x${want.toString(16)}")
    }
}
