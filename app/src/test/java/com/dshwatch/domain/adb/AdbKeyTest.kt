package com.dshwatch.domain.adb

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * 公钥编码与签名。
 *
 * 这两样任一错了，手机上的表现都是「连接被拒」或「已授权但握手失败」，
 * 没有任何提示能指向真正的原因。所以把结构的每个字段单独钉住。
 */
class AdbKeyTest {

    private fun tempDir(): File = Files.createTempDirectory("adbkey-test").toFile()

    @Test
    fun `公钥编码长度与字段正确`() {
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(tempDir())
        val encoded = AdbKey.encode(pair.public as RSAPublicKey)

        assertThat(encoded).hasLength(AdbKey.ENCODED_BYTES)
        assertThat(intLe(encoded, 0)).isEqualTo(256)
        // 指数 65537，小端
        assertThat(intLe(encoded, 8 + 2 * AdbKey.MODULUS_BYTES)).isEqualTo(65537)
    }

    @Test
    fun `n0inv 满足 n 乘它等于负一`() {
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(tempDir())
        val pub = pair.public as RSAPublicKey
        val encoded = AdbKey.encode(pub)

        val base = BigInteger.ONE.shiftLeft(32)
        val n0inv = java.lang.Long.parseLong(
            Integer.toUnsignedString(intLe(encoded, 4)),
        )
        val product = pub.modulus.mod(base).multiply(BigInteger.valueOf(n0inv)).mod(base)
        // -1 mod 2^32
        assertThat(product).isEqualTo(base.subtract(BigInteger.ONE))
    }

    @Test
    fun `rr 等于 R 平方取模`() {
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(tempDir())
        val pub = pair.public as RSAPublicKey
        val encoded = AdbKey.encode(pub)

        val start = 8 + AdbKey.MODULUS_BYTES
        val rrBytes = ByteArray(AdbKey.MODULUS_BYTES)
        // 小端转回大端
        for (i in rrBytes.indices) rrBytes[i] = encoded[start + AdbKey.MODULUS_BYTES - 1 - i]
        val rr = BigInteger(1, rrBytes)
        val r = BigInteger.ONE.shiftLeft(2048)
        assertThat(rr).isEqualTo(r.multiply(r).mod(pub.modulus))
    }

    @Test
    fun `公钥文本是 base64 加标签`() {
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(tempDir())
        val text = AdbKey.publicKeyText(pair)

        assertThat(text).endsWith(" dsh@watch")
        assertThat(text).doesNotContain("\n")
        val decoded = Base64.getDecoder().decode(text.substringBefore(' '))
        assertThat(decoded).hasLength(AdbKey.ENCODED_BYTES)
    }

    @Test
    fun `签名可被公钥验证`() {
        AdbKey.forget()
        val pair = AdbKey.loadOrCreate(tempDir())
        val token = ByteArray(20) { (it * 7 + 3).toByte() }
        val signature = AdbKey.sign(pair, token)

        assertThat(signature).hasLength(256)
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(pair.public)
        verifier.update(token)
        assertThat(verifier.verify(signature)).isTrue()
    }

    @Test
    fun `密钥落盘后重读一致`() {
        val dir = tempDir()
        AdbKey.forget()
        val first = AdbKey.loadOrCreate(dir)
        val firstText = AdbKey.publicKeyText(first)

        AdbKey.forget()
        val second = AdbKey.loadOrCreate(dir)
        assertThat(AdbKey.publicKeyText(second)).isEqualTo(firstText)
    }

    @Test
    fun `损坏的密钥文件会重新生成`() {
        val dir = tempDir()
        dir.mkdirs()
        File(dir, "adbkey.pk8").writeText("not a key")
        AdbKey.forget()

        val pair = AdbKey.loadOrCreate(dir)
        assertThat(AdbKey.publicKeyText(pair)).endsWith(" dsh@watch")
        // 重新生成的密钥应当覆盖坏文件，下一次还能读出来
        AdbKey.forget()
        assertThat(AdbKey.publicKeyText(AdbKey.loadOrCreate(dir))).isEqualTo(AdbKey.publicKeyText(pair))
    }

    private fun intLe(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or
            ((src[offset + 1].toInt() and 0xFF) shl 8) or
            ((src[offset + 2].toInt() and 0xFF) shl 16) or
            ((src[offset + 3].toInt() and 0xFF) shl 24)
}
