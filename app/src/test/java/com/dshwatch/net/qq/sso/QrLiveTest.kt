package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 实网取码。
 *
 * 默认跳过（CI 和沙箱不一定能连 QQ 的登录服，也免得每次跑测试都去骚扰服务端）。
 * 需要验证协议时手动开：
 * ```
 * QQ_LIVE=1 ./gradlew testDebugUnitTest --tests 'com.dshwatch.net.qq.sso.QrLiveTest'
 * ```
 *
 * 这个测试能一次覆盖整条链路：外层封装 → TEA → ECDH → oicq → TransEmp → 响应解码。
 * 任何一层写错都拿不到 PNG。
 */
class QrLiveTest {

    @Test
    fun `手表身份能取到二维码`() {
        assumeTrue(System.getenv("QQ_LIVE") == "1")

        QrLoginFlow(WatchDevice.create()).use { flow ->
            val qr = flow.requestQr()

            // PNG magic：拿到的必须是服务端画的图，不是别的什么 200 响应
            assertThat(qr.png.copyOfRange(0, 4))
                .isEqualTo(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
            assertThat(qr.png.size).isGreaterThan(100)
            // sig 是后续轮询的凭据，服务端固定给 24 字节
            assertThat(qr.sig).hasLength(24)
        }
    }

    @Test
    fun `取到码之后能查到未扫状态`() {
        assumeTrue(System.getenv("QQ_LIVE") == "1")

        QrLoginFlow(WatchDevice.create()).use { flow ->
            val qr = flow.requestQr()
            assertThat(flow.poll(qr.sig)).isEqualTo(QrStatus.Waiting)
        }
    }
}
