package com.dshwatch.net.qq.sso

import java.util.Base64

/**
 * 一次成功扫码换来的登录态。
 *
 * 这几个字段是后续所有请求的通行证：
 *  - [a2]      HTTP 接口（qun.qq.com 之类）的 cookie 值
 *  - [d2]      SSO 包体里标识「这个号的这条会话」的令牌
 *  - [d2Key]   长连接上每个包的 TEA 密钥，解开/封上服务端的推包都靠它
 *  - [ksid]    会话 id，部分接口要求跟 d2 一起出现
 *  - [tgt]     「换票凭证」。TCP 断开重连后，服务端不认旧连接，
 *              要用它在 `wtlogin.exchange_emp` 里重新认证一次；
 *              没有它就只剩「重新扫码」一条路
 *
 * 它们只在换票那一刻由服务端下发，客户端算不出来；丢了就只能重新扫码。
 * 所以登录成功后必须立刻落盘，否则进程一退就白扫。
 */
internal class QqSession(
    val uin: Long,
    val nick: String,
    val a2: ByteArray,
    val d2: ByteArray,
    val d2Key: ByteArray,
    val ksid: ByteArray,
    val tgt: ByteArray = ByteArray(0),
    val savedAt: Long = System.currentTimeMillis(),
) {

    /** 能不能拿它去建长连接：缺 d2Key 就解不开推包，等于一条死连接 */
    val usable: Boolean get() = a2.isNotEmpty() && d2.isNotEmpty() && d2Key.size == 16

    /** 能不能在没有新扫码的情况下重连：缺 tgt 只能重扫 */
    val resumable: Boolean get() = usable && tgt.isNotEmpty()

    /** 票据过期时间不好判断，只能看年龄：超过 30 天基本要重扫 */
    fun ageDays(now: Long = System.currentTimeMillis()): Long =
        ((now - savedAt) / 86_400_000L).coerceAtLeast(0)

    fun encode(): String = listOf(
        FORMAT,
        uin.toString(),
        b64(nick.toByteArray(Charsets.UTF_8)),
        b64(a2),
        b64(d2),
        b64(d2Key),
        b64(ksid),
        b64(tgt),
        savedAt.toString(),
    ).joinToString("|")

    companion object {
        /** 格式版本。字段顺序一变就换这个号，旧数据直接当没有 */
        private const val FORMAT = "v2"

        fun decode(raw: String?): QqSession? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 9 || parts[0] != FORMAT) return null
            return runCatching {
                QqSession(
                    uin = parts[1].toLong(),
                    nick = String(unb64(parts[2]), Charsets.UTF_8),
                    a2 = unb64(parts[3]),
                    d2 = unb64(parts[4]),
                    d2Key = unb64(parts[5]),
                    ksid = unb64(parts[6]),
                    tgt = unb64(parts[7]),
                    savedAt = parts[8].toLong(),
                )
            }.getOrNull()
        }

        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}
