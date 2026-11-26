package com.dshwatch.domain.adb

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ADB 客户端用的 RSA 密钥。
 *
 * ## 为什么不用 AndroidKeyStore
 *
 * 系统密钥库的 RSA 签名默认走 PKCS#1 v1.5，签名结果与 adb 期望一致，
 * 但 KeyStore 不保证能导出「模数、指数」之外的编码细节，而 adb 的公钥格式
 * 不是标准 X.509 —— 它要的是 AOSP 自定义的 524 字节结构（模数、n0inv、rr）。
 * 从 KeyStore 拿不到私钥参与这个结构的构造，所以这里自己生成、自己存。
 *
 * ## 存哪、怎么存
 *
 * `filesDir/adb/adbkey.pk8`，仅本进程可读。密钥是这对设备的身份：
 * 丢了就得在手机上重新点一次「允许调试」，但不会影响任何用户数据，
 * 所以不做额外加密 —— 加密密钥本身又要存一份，收益为零。
 */
object AdbKey {

    /** adb 公钥结构里模数固定 2048 位 = 256 字节 */
    const val MODULUS_BYTES = 256

    /** AOSP 结构总长：len + n0inv + modulus + rr + exponent */
    const val ENCODED_BYTES = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

    private const val BITS = 2048
    private const val PRIVATE_FILE = "adbkey.pk8"
    private const val PUBLIC_FILE = "adbkey.pub"

    /** adb 公钥尾部的标识，出现在手机的「已授权的调试密钥」列表里 */
    private const val LABEL = "dsh@watch"

    @Volatile
    private var cached: KeyPair? = null

    fun loadOrCreate(dir: File): KeyPair {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = read(dir)
            val pair = loaded ?: generate()
            if (loaded == null) write(dir, pair)
            cached = pair
            return pair
        }
    }

    /** 丢掉内存缓存，下次重新读盘。密钥文件被删或换过时用。 */
    fun forget() {
        cached = null
    }

    private fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(BITS)
    }.generateKeyPair()

    private fun read(dir: File): KeyPair? = runCatching {
        val priv = File(dir, PRIVATE_FILE)
        val pub = File(dir, PUBLIC_FILE)
        if (!priv.isFile || priv.length() == 0L) return null
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))
        val publicKey = if (pub.isFile && pub.length() > 0) {
            factory.generatePublic(X509EncodedKeySpec(pub.readBytes()))
        } else {
            null
        }
        if (publicKey == null) return null
        KeyPair(publicKey, privateKey)
    }.getOrNull()

    private fun write(dir: File, pair: KeyPair) {
        runCatching {
            dir.mkdirs()
            val priv = File(dir, PRIVATE_FILE)
            val pub = File(dir, PUBLIC_FILE)
            val tmp = File(dir, "$PRIVATE_FILE.tmp")
            tmp.writeBytes(pair.private.encoded)
            if (!tmp.renameTo(priv)) {
                priv.writeBytes(pair.private.encoded)
                tmp.delete()
            }
            pub.writeBytes(pair.public.encoded)
            listOf(priv, pub).forEach { f ->
                f.setReadable(false, false)
                f.setReadable(true, true)
                f.setWritable(false, false)
                f.setWritable(true, true)
            }
        }
    }

    /**
     * 发给手机的公钥文本：`base64(524 字节结构) 标签`。
     *
     * 不要在这里加换行 —— adb 的 `auth_publickey` 载荷以字符串形式传输，
     * 换行会让手机解析出一个截断的 base64。
     */
    fun publicKeyText(pair: KeyPair): String {
        val pub = pair.public as RSAPublicKey
        return Base64.getEncoder().encodeToString(encode(pub)) + " " + LABEL
    }

    /** 对 AUTH 的 20 字节 token 签名。算法与 adb 一致：SHA-1 + PKCS#1 v1.5。 */
    fun sign(pair: KeyPair, token: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA1withRSA")
        s.initSign(pair.private)
        s.update(token)
        return s.sign()
    }

    /**
     * AOSP 的 `RSAPublicKey` 结构。
     *
     * ```
     * uint32 len        模数字节数（256）
     * uint32 n0inv      -1 / n[0] mod 2^32
     * uint8  modulus[]  模数，小端
     * uint8  rr[]       R^2 mod n，R = 2^2048，小端
     * uint32 exponent   公钥指数（65537）
     * ```
     *
     * 这个结构不是 X.509，也不是 PKCS#1 —— 少一个字段、字节序反了，
     * 手机上都会显示成「已授权」但连接立刻被拒，而且没有任何错误提示。
     */
    fun encode(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val out = ByteArray(ENCODED_BYTES)
        putIntLe(out, 0, MODULUS_BYTES)
        putIntLe(out, 4, n0inv(n))
        littleEndian(n, MODULUS_BYTES).copyInto(out, 8)
        littleEndian(rr(n), MODULUS_BYTES).copyInto(out, 8 + MODULUS_BYTES)
        putIntLe(out, 8 + 2 * MODULUS_BYTES, pub.publicExponent.toInt())
        return out
    }

    private fun n0inv(n: BigInteger): Int {
        val base = BigInteger.ONE.shiftLeft(32)
        val low = n.mod(base)
        val inverse = low.modInverse(base)
        // -n^-1 mod 2^32，写成 32 位无符号
        return base.subtract(inverse).mod(base).toInt()
    }

    private fun rr(n: BigInteger): BigInteger {
        val r = BigInteger.ONE.shiftLeft(BITS)
        return r.multiply(r).mod(n)
    }

    private fun littleEndian(value: BigInteger, size: Int): ByteArray {
        val big = value.toByteArray()
        val out = ByteArray(size)
        // toByteArray 是带符号的大端：多出的前导 0 在反转后变成末尾，正好丢掉
        val usable = minOf(big.size, size)
        for (i in 0 until usable) {
            out[i] = big[big.size - 1 - i]
        }
        return out
    }

    private fun putIntLe(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
