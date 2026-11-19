package com.dshwatch.net.qq.sso

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * 登录握手用的 P-256 密钥交换。
 *
 * 二维码命令（`wtlogin.trans_emp`）的正文不是明文直发，而是先用一次临时协商出来的
 * 密钥加密：客户端带一个临时公钥过去，服务端用自己的私钥算出同一个共享密钥。
 * 每一包都换一份新密钥，所以这里不缓存，用完即弃。
 *
 * 算法是标准 NIST P-256（`secp256r1`），不是国密也不是 secp192k1 ——
 * 曲线认错的直接后果是服务端解不开正文、于是既不回包也不断开，表现为读超时。
 */
internal object Ecdh {

    /** 服务端固定公钥，未压缩格式（0x04 + X + Y） */
    private val SERVER_PUB = hex(
        "04EBCA94D733E399B2DB96EACDD3F69A8BB0F74224E2B44E3357812211D2E62EFBC91BB553098E25E33A799ADC7F76FEB2" +
            "08DA7C6522CDB0719A305180CC54A82E",
    )

    /** 一次协商的结果 */
    class Session(val publicKey: ByteArray, val shareKey: ByteArray)

    /**
     * 现生成一对临时密钥并与服务端公钥协商。
     *
     * 共享密钥取协商结果的 X 坐标前 16 字节做 MD5 —— 这一步不能省，
     * 直接拿坐标当密钥会让服务端算出来的值对不上。
     */
    fun session(): Session {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        return negotiate(pair.public as ECPublicKey, pair.private as ECPrivateKey)
    }

    private fun negotiate(pub: ECPublicKey, priv: ECPrivateKey): Session {
        val myPub = ByteArray(65)
        myPub[0] = 0x04
        writeFixed(myPub, 1, pub.w.affineX, 32)
        writeFixed(myPub, 33, pub.w.affineY, 32)

        val serverPoint = ECPoint(
            BigInteger(1, SERVER_PUB.copyOfRange(1, 33)),
            BigInteger(1, SERVER_PUB.copyOfRange(33, 65)),
        )
        val serverKey = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(serverPoint, pub.params))

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(serverKey, true)
        val secret = ka.generateSecret()

        val share = MessageDigest.getInstance("MD5").digest(secret.copyOfRange(0, 16))
        return Session(myPub, share)
    }

    // ---------------- 工具 ----------------

    private fun writeFixed(dst: ByteArray, at: Int, v: BigInteger, width: Int) {
        val raw = v.toByteArray()
        // BigInteger 会带一个多余的前导 0 符号字节，也可能不足 width 位
        val src = if (raw.size > width) raw.copyOfRange(raw.size - width, raw.size) else raw
        val pad = width - src.size
        src.copyInto(dst, at + pad)
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
