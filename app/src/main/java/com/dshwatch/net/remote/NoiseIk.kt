package com.dshwatch.net.remote

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Noise_IK_25519_ChaChaPoly_SHA256，Noise 协议框架 revision 34。
 *
 * 与 ds-harness-remote 的 `noise_ik.dart` 逐字节对齐：IK 模式、
 * 客户端是发起方、两个握手载荷在生产环境里都为空。
 *
 * ```text
 * <- s
 * ...
 * -> e, es, s, ss
 * <- e, ee, se
 * ```
 *
 * 这里只做客户端（initiator）需要的那一半；responder 那一半只存在于测试里，
 * 用来在没有真服务器的情况下证明实现是自洽的 —— 两端都能推导出同一把会话密钥，
 * 是这套握手唯一能自证的东西。
 */
class NoiseIk private constructor(
    private val role: Role,
    private val localStatic: ByteArray,
    private val remoteStatic: ByteArray,
    private val ephemeralSeed: ByteArray?,
    prologue: ByteArray,
) {

    enum class Role { INITIATOR, RESPONDER }

    private val symmetric = Symmetric(protocolName)
    private var ephemeral: ByteArray? = null
    private var remoteEphemeral: ByteArray? = null
    private var messageIndex = 0
    private var send: CipherState? = null
    private var receive: CipherState? = null

    val complete: Boolean get() = send != null
    val canWrite: Boolean get() = !complete && (messageIndex == 0) == (role == Role.INITIATOR)

    init {
        symmetric.mixHash(prologue)
        // 预消息 `<- s`：响应方的静态公钥两边事先都知道
        symmetric.mixHash(if (role == Role.INITIATOR) remoteStatic else publicOf(localStatic))
    }

    fun writeHandshake(payload: ByteArray = ByteArray(0)): ByteArray {
        check(!complete) { "握手已经完成" }
        val e = ephemeralSeed?.copyOf() ?: ByteArray(32).also { X25519.generatePrivateKey(random, it) }
        ephemeral = e
        val ePub = publicOf(e)
        symmetric.mixHash(ePub)

        val out = ArrayList<Byte>(128)
        out += ePub.toList()
        if (role == Role.INITIATOR) {
            // -> e, es, s, ss
            symmetric.mixKey(agree(e, remoteStatic))
            out += symmetric.encryptAndHash(publicOf(localStatic)).toList()
            symmetric.mixKey(agree(localStatic, remoteStatic))
        } else {
            // <- e, ee, se
            val re = requireNotNull(remoteEphemeral) { "还没读到对方的临时公钥" }
            symmetric.mixKey(agree(e, re))
            symmetric.mixKey(agree(e, remoteStatic))
        }
        out += symmetric.encryptAndHash(payload).toList()
        messageIndex++
        finishIfReady()
        return out.toByteArray()
    }

    fun readHandshake(message: ByteArray): ByteArray {
        check(!complete) { "握手已经完成" }
        require(message.size >= 32) { "握手消息太短" }
        val re = message.copyOfRange(0, 32)
        remoteEphemeral = re
        symmetric.mixHash(re)

        var offset = 32
        if (role == Role.RESPONDER) {
            // -> e, es, s, ss
            symmetric.mixKey(agree(localStatic, re))
            require(message.size >= offset + 48) { "握手消息太短" }
            val encryptedStatic = message.copyOfRange(offset, offset + 48)
            offset += 48
            val learned = symmetric.decryptAndHash(encryptedStatic)
            if (!MessageDigest.isEqual(learned, remoteStatic)) {
                throw NoiseError("对端身份与预期不符")
            }
            symmetric.mixKey(agree(localStatic, learned))
        } else {
            symmetric.mixKey(agree(requireNotNull(ephemeral), re))
            symmetric.mixKey(agree(localStatic, re))
        }
        val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
        messageIndex++
        finishIfReady()
        return payload
    }

    // 传输消息不带关联数据：Noise 只在握手阶段用 h 作为 AD
    fun encrypt(plaintext: ByteArray): ByteArray =
        checkNotNull(send) { "握手还没完成" }.encrypt(EMPTY_AD, plaintext)

    fun decrypt(ciphertext: ByteArray): ByteArray =
        checkNotNull(receive) { "握手还没完成" }.decrypt(EMPTY_AD, ciphertext)

    /** 下一条要发送的 nonce。Relay 帧要带上它，服务端按同一个计数器校验顺序 */
    val sendingNonce: Long get() = send?.nonce ?: 0L

    /** 期望收到的下一个 nonce。对不上就是重放、丢帧或乱序 */
    val receivingNonce: Long get() = receive?.nonce ?: 0L

    /** 用于把对端身份固定下来之后做比对提示 */
    fun remoteStaticFingerprint(): String = fingerprintOf(remoteStatic)

    private fun finishIfReady() {
        if (messageIndex < 2 || complete) return
        val (k1, k2) = symmetric.split()
        if (role == Role.INITIATOR) {
            send = CipherState(k1)
            receive = CipherState(k2)
        } else {
            send = CipherState(k2)
            receive = CipherState(k1)
        }
    }

    // -------------------------------------------------------------- 内部件

    private class CipherState(private val key: ByteArray?) {
        var nonce = 0L
            private set

        fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
            val k = key ?: return plaintext.copyOf()
            check(nonce < MAX_NONCE) { "nonce 用尽" }
            val cipher = ChaCha20Poly1305()
            cipher.init(true, AEADParameters(KeyParameter(k), 128, nonceBytes(nonce), ad))
            val out = ByteArray(cipher.getOutputSize(plaintext.size))
            var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
            len += cipher.doFinal(out, len)
            nonce++
            return out.copyOf(len)
        }

        fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
            val k = key ?: return ciphertext.copyOf()
            require(ciphertext.size >= 16) { "密文比 tag 还短" }
            check(nonce < MAX_NONCE) { "nonce 用尽" }
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(k), 128, nonceBytes(nonce), ad))
            return try {
                val out = ByteArray(cipher.getOutputSize(ciphertext.size))
                var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
                len += cipher.doFinal(out, len)
                nonce++
                out.copyOf(len)
            } catch (e: InvalidCipherTextException) {
                // 认证失败就失败：重放、乱序、伪造都走这条路
                throw NoiseError("密文认证失败")
            }
        }
    }

    private class Symmetric(protocolName: String) {
        private var h: ByteArray
        private var ck: ByteArray

        /**
         * 每次 mixKey 换一把新钥匙，nonce 随之归零 —— 这是 Noise 的规定，
         * 但同一个密钥下的多次加解密必须共用同一个计数器。之前每个
         * encryptAndHash 都新建 CipherState，等于把 nonce 一直重置成 0，
         * 两次用同一把钥匙时第二次会得到一模一样的密钥流。
         */
        private var cipher: CipherState? = null

        init {
            val name = protocolName.toByteArray(Charsets.UTF_8)
            h = if (name.size <= 32) name.copyOf(32) else sha256(name)
            ck = h.copyOf()
        }

        fun mixHash(data: ByteArray) {
            h = sha256(h + data)
        }

        /** Noise §4.3：`HKDF(ck, ikm)` 取前两段 —— ck 与临时密钥 */
        fun mixKey(ikm: ByteArray) {
            val (nextCk, k) = hkdf2(ck, ikm)
            ck = nextCk
            cipher = CipherState(k)
        }

        /**
         * Noise §5.2：密文的关联数据是**当前的 h**，不是空。
         *
         * 这一条写错，握手在字节层面看起来完全正常，但和任何按协议实现的
         * 对端都对不上 —— 连 prologue 不一致这种本该失败的情况都会"成功"。
         */
        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val ct = cipher?.encrypt(h, plaintext) ?: plaintext.copyOf()
            h = sha256(h + ct)
            return ct
        }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val pt = cipher?.decrypt(h, ciphertext) ?: ciphertext.copyOf()
            h = sha256(h + ciphertext)
            return pt
        }

        fun split(): Pair<ByteArray, ByteArray> = hkdf2(ck, ByteArray(0))
    }

    companion object {
        const val protocolName = "Noise_IK_25519_ChaChaPoly_SHA256"

        /** 生产环境两边都发空载荷；留着是为了测试能塞点东西进去 */
        val prologue: ByteArray = ByteArray(0)

        private const val MAX_NONCE = 0x3FFFFFFFFFFFFFFFL
        private val EMPTY_AD = ByteArray(0)
        private val random = SecureRandom()

        fun initiator(
            localPrivate: ByteArray,
            remotePublic: ByteArray,
            ephemeralSeed: ByteArray? = null,
            prologue: ByteArray = ByteArray(0),
        ): NoiseIk {
            require(localPrivate.size == 32 && remotePublic.size == 32) { "密钥必须是 32 字节" }
            return NoiseIk(Role.INITIATOR, localPrivate, remotePublic, ephemeralSeed, prologue)
        }

        fun responder(
            localPrivate: ByteArray,
            remotePublic: ByteArray,
            ephemeralSeed: ByteArray? = null,
            prologue: ByteArray = ByteArray(0),
        ): NoiseIk {
            require(localPrivate.size == 32 && remotePublic.size == 32) { "密钥必须是 32 字节" }
            return NoiseIk(Role.RESPONDER, localPrivate, remotePublic, ephemeralSeed, prologue)
        }

        fun newPrivateKey(): ByteArray = ByteArray(32).also { X25519.generatePrivateKey(random, it) }

        fun publicOf(privateKey: ByteArray): ByteArray = ByteArray(32).also {
            X25519.generatePublicKey(privateKey, 0, it, 0)
        }

        /** 全零结果意味着对端给了低阶点，必须拒绝 */
        fun agree(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
            val out = ByteArray(32)
            if (!X25519.calculateAgreement(privateKey, 0, peerPublic, 0, out, 0)) {
                throw NoiseError("X25519 协商失败（对端公钥是低阶点）")
            }
            if (out.all { it == 0.toByte() }) throw NoiseError("X25519 协商出了全零密钥")
            return out
        }

        fun fingerprintOf(rawPublic: ByteArray): String =
            sha256(rawPublic).toBase64Url().take(16)

        internal fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        internal fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        /** Noise §4.3 的 `HKDF(ck, ikm, 2)` */
        internal fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
            val temp = hmac(chainingKey, ikm)
            val out1 = hmac(temp, byteArrayOf(0x01))
            val out2 = hmac(temp, out1 + byteArrayOf(0x02))
            return out1 to out2
        }

        private fun nonceBytes(n: Long): ByteArray {
            val out = ByteArray(12)
            for (i in 0 until 8) out[4 + i] = ((n shr (8 * i)) and 0xFF).toByte()
            return out
        }
    }
}

class NoiseError(message: String) : Exception(message)
