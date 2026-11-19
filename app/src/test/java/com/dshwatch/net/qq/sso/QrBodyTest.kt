package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 取码请求的正文结构回归。
 *
 * 这一段是整条链路上唯一「错了也不报错」的位置：字段少一个字节，服务端照样回包，
 * 只是内容不对。设备名尤其隐蔽 —— 留空时码能出、能扫、能到「已确认」，
 * 偏偏在手机点确认那一下被拦，客户端只能看到一个 45。
 */
class QrBodyTest {

    private val device = WatchDevice(
        guid = ByteArray(16) { it.toByte() },
        imei = "861234567890123",
    )

    private val profile = DeviceProfile(device)

    private val body = fetchQrBody(device, profile, qrSize = 1, qrMargin = 2, qrEc = 1)

    @Test
    fun `设备名非空且与机型一致`() {
        val name = deviceName()
        assertThat(name).isNotEmpty()
        // 手机 QQ 确认页显示的就是这串；和 0x144 里报的机型对不上等于「查无此机」
        assertThat(name).isEqualTo("${profile.brand} ${profile.model}")
    }

    @Test
    fun `设备名用 2 字节长度前缀，后面紧跟 TLV 个数`() {
        val r = header()
        val n = r.short()
        assertThat(String(r.bytes(n), Charsets.UTF_8)).isEqualTo(profile.deviceName)
        assertThat(r.short()).isEqualTo(6)
    }

    @Test
    fun `设备名之后 6 条 TLV 能逐条解完且不剩尾巴`() {
        val r = header()
        r.skip(r.short()) // 设备名
        assertThat(r.short()).isEqualTo(6)
        val tlvs = TlvMap.decode(r, strict = true)
        assertThat(tlvs.keys).containsExactly(0x16, 0x1b, 0x1d, 0x1f, 0x33, 0x35)
        assertThat(r.remaining).isEqualTo(0)
    }

    @Test
    fun `0x16 里带的是手表客户端的包名与版本`() {
        val r = header()
        r.skip(r.short())
        r.short()
        val t16 = TlvMap.decode(r, strict = true).getValue(0x16)
        val b = ByteReader(t16)
        assertThat(b.int()).isEqualTo(device.ssoVersion)
        assertThat(b.int()).isEqualTo(16)
        assertThat(b.int()).isEqualTo(device.subAppId.toInt())
        assertThat(b.bytes(16)).isEqualTo(device.guid)
        assertThat(String(b.lv16(), Charsets.UTF_8)).isEqualTo("com.tencent.qqlite")
        assertThat(String(b.lv16(), Charsets.UTF_8)).isEqualTo(WatchDevice.APK_VERSION)
        assertThat(b.lv16()).isEqualTo(device.apkSignMd5)
    }

    /** 跳过定长头：2 保留 + 4 appId + 8 uin + 1 标记 */
    private fun header() = ByteReader(body).also { it.skip(2 + 4 + 8 + 1) }

    private fun deviceName(): String {
        val r = header()
        return String(r.bytes(r.short()), Charsets.UTF_8)
    }
}
