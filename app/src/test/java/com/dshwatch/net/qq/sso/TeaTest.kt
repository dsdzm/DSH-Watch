package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * TEA 的往返测试。
 *
 * 这里只能验证「加密再解密能还原」，验证不了「服务端会不会认」——
 * 后者需要真机扫码。但至少能挡住填充、链式异或这类自己跟自己对不上的错误：
 * 这类错误一旦带上线，表现是服务端**静默丢弃**请求，超时且没有任何提示。
 */
class TeaTest {

    private val key = WatchDevice.APK_SIGN

    @Test
    fun `空数据也能往返`() {
        val enc = Tea.encrypt(ByteArray(0), key)
        assertThat(enc.size % 8).isEqualTo(0)
        assertThat(Tea.decrypt(enc, key)).isEmpty()
    }

    @Test
    fun `各种长度都能往返`() {
        // 覆盖 0..64：包含 8 的整数倍、余 1、余 7 这些会让填充计算走不同分支的情况
        for (len in 0..64) {
            val plain = ByteArray(len) { (it * 31 + 7).toByte() }
            val enc = Tea.encrypt(plain, key)
            assertThat(enc.size % 8).isEqualTo(0)
            val dec = Tea.decrypt(enc, key)
            assertThat(dec).isEqualTo(plain)
        }
    }

    @Test
    fun `同一份明文两次加密结果不同`() {
        // 填充里带随机数，结果相同说明随机没生效 —— 那样包会呈现固定特征
        val plain = "hello qq watch protocol".toByteArray()
        val a = Tea.encrypt(plain, key)
        val b = Tea.encrypt(plain, key)
        assertThat(a).isNotEqualTo(b)
        assertThat(Tea.decrypt(a, key)).isEqualTo(plain)
        assertThat(Tea.decrypt(b, key)).isEqualTo(plain)
    }

    @Test
    fun `换密钥解不出来`() {
        val plain = "secret".toByteArray()
        val enc = Tea.encrypt(plain, key)
        val other = ByteArray(16) { (it + 1).toByte() }
        val failed = runCatching { Tea.decrypt(enc, other) }.isFailure
        // 要么填充位校验失败抛异常，要么解出一堆乱码 —— 两种都算「解不出来」
        assertThat(failed || !Tea.decrypt(enc, other).contentEquals(plain)).isTrue()
    }

    @Test
    fun `密文长度非法时直接报错`() {
        assertThat(runCatching { Tea.decrypt(ByteArray(7), key) }.isFailure).isTrue()
        assertThat(runCatching { Tea.decrypt(ByteArray(9), key) }.isFailure).isTrue()
    }
}
