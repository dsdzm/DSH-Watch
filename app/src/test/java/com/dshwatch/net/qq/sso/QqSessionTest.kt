package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 登录态的编码 / 解码。
 *
 * 这段代码只在「换票成功」和「App 重启」两端各跑一次，写错了不会立刻报错，
 * 而是表现为「明明登录过，重启却要求重扫」——所以把边界都钉住。
 */
class QqSessionTest {

    private val session = QqSession(
        uin = 2332184782L,
        nick = "东山的字幕",
        a2 = ByteArray(64) { it.toByte() },
        d2 = ByteArray(32) { (it + 1).toByte() },
        d2Key = ByteArray(16) { (0xF0 + it).toByte() },
        ksid = ByteArray(20) { (it * 3).toByte() },
        tgt = ByteArray(48) { (it + 7).toByte() },
        savedAt = 1_764_000_000_000L,
    )

    @Test
    fun `往返编码保真`() {
        val back = QqSession.decode(session.encode())!!
        assertThat(back.uin).isEqualTo(session.uin)
        assertThat(back.nick).isEqualTo(session.nick)
        assertThat(back.a2).isEqualTo(session.a2)
        assertThat(back.d2).isEqualTo(session.d2)
        assertThat(back.d2Key).isEqualTo(session.d2Key)
        assertThat(back.ksid).isEqualTo(session.ksid)
        assertThat(back.tgt).isEqualTo(session.tgt)
        assertThat(back.savedAt).isEqualTo(session.savedAt)
        assertThat(back.usable).isTrue()
    }

    @Test
    fun `昵称里的竖线不会破坏分段`() {
        val weird = QqSession(
            uin = 1L,
            nick = "a|b|c",
            a2 = ByteArray(1) { 1 },
            d2 = ByteArray(1) { 2 },
            d2Key = ByteArray(16),
            ksid = ByteArray(0),
            tgt = ByteArray(0),
        )
        assertThat(QqSession.decode(weird.encode())!!.nick).isEqualTo("a|b|c")
    }

    @Test
    fun `换版本或截断一律当没有`() {
        assertThat(QqSession.decode(null)).isNull()
        assertThat(QqSession.decode("")).isNull()
        assertThat(QqSession.decode("v2|1|x")).isNull()
        // 少一段（不是少字符：末尾是时间戳，截断两个字符照样能解析）
        val parts = session.encode().split('|')
        assertThat(QqSession.decode(parts.dropLast(1).joinToString("|"))).isNull()
        // 长度对但内容坏
        assertThat(QqSession.decode("v1|abc|x|x|x|x|x|x")).isNull()
    }

    @Test
    fun `缺 tgt 不能断线重连`() {
        val noTgt = QqSession(
            uin = 1L,
            nick = "",
            a2 = ByteArray(8) { 1 },
            d2 = ByteArray(8) { 2 },
            d2Key = ByteArray(16),
            ksid = ByteArray(0),
        )
        assertThat(noTgt.usable).isTrue()
        assertThat(noTgt.resumable).isFalse()
        assertThat(session.resumable).isTrue()
    }

    @Test
    fun `缺 d2Key 的票据不可用`() {
        val broken = QqSession(
            uin = 1L,
            nick = "",
            a2 = ByteArray(8),
            d2 = ByteArray(8),
            d2Key = ByteArray(0),
            ksid = ByteArray(0),
            tgt = ByteArray(0),
        )
        assertThat(broken.usable).isFalse()
        // 但编码本身仍然能存能取，界面才好提示「不完整」
        assertThat(QqSession.decode(broken.encode())!!.usable).isFalse()
    }
}
