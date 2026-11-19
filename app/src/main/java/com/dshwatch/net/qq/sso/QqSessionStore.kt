package com.dshwatch.net.qq.sso

import com.dshwatch.data.settings.SecureKeyStore

/**
 * 登录态的加密落盘。
 *
 * 走的是和 API Key 同一套存储（Android Keystore 加密）。理由一样：
 * d2 / d2Key 等价于这个号的登录凭证，拿到就能以本人身份收发消息，
 * 明文写进普通 SharedPreferences，等于任何能读应用目录的东西都能接管账号。
 */
internal class QqSessionStore(private val keys: SecureKeyStore) {

    fun load(): QqSession? = QqSession.decode(keys.getSecret(SCOPE, FIELD_SESSION))

    fun save(session: QqSession) {
        keys.putSecret(SCOPE, FIELD_SESSION, session.encode())
    }

    fun clear() {
        keys.putSecret(SCOPE, FIELD_SESSION, null)
    }

    private companion object {
        const val SCOPE = "qq"
        const val FIELD_SESSION = "session"
    }
}
