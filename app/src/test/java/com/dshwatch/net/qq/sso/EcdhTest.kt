package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 密钥交换的形状检查。
 *
 * 这里不验具体数值（每次都是新密钥），只验格式：曲线选错、公钥写成压缩格式、
 * 共享密钥忘了 MD5 —— 这几种都会让服务端解不开正文，而且不会报错，
 * 只会一直等着，最后表现为读超时。
 */
class EcdhTest {

    @Test
    fun `公钥是未压缩格式的 65 字节`() {
        val s = Ecdh.session()
        assertThat(s.publicKey).hasLength(65)
        assertThat(s.publicKey[0].toInt()).isEqualTo(0x04)
    }

    @Test
    fun `共享密钥是 16 字节`() {
        assertThat(Ecdh.session().shareKey).hasLength(16)
    }

    @Test
    fun `每次协商结果都不同`() {
        val a = Ecdh.session()
        val b = Ecdh.session()
        assertThat(a.publicKey).isNotEqualTo(b.publicKey)
        assertThat(a.shareKey).isNotEqualTo(b.shareKey)
    }

    @Test
    fun `服务端公钥能在同一条曲线上还原`() {
        // 曲线参数不匹配时这一步会抛异常，而不是等到服务端超时才暴露
        repeat(3) { assertThat(Ecdh.session().shareKey).hasLength(16) }
    }
}
