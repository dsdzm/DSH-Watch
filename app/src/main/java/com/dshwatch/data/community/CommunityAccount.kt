package com.dshwatch.data.community

import com.dshwatch.core.Logx
import com.dshwatch.data.settings.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 社区账号态。
 *
 * 三件事：token 存在哪、当前是谁、以及「本机这张卡投过哪一条」。
 *
 * ## token 为什么放 [SecureKeyStore] 而不是设置里
 *
 * `SettingsRepository` 是整包 JSON 明文写在 DataStore 里的，而 token 等价于
 * 密码（拿到就能投稿、删稿）。这个项目里所有凭证都走 Keystore 加密存的那一套
 * （API Key、GitHub token、B站 Cookie），社区 token 没有理由例外。
 *
 * ## 为什么还要记一张「投稿映射」
 *
 * 服务端靠「带不带 id」区分新建与改稿。如果不记，用户改完卡再点一次分享，
 * 社区里就会出现两张同名卡 —— 而且第三张之后会被「还有 3 张在排队」卡住，
 * 看起来像 bug。映射表放本地就行：它只是"上次投的是哪条"的备忘，
 * 丢了最坏结果就是多投一张。
 */
class CommunityAccount(
    private val keyStore: SecureKeyStore,
    private val dir: File,
    private val json: Json,
) {

    data class State(
        val signedIn: Boolean = false,
        val username: String = "",
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    val isSignedIn: Boolean get() = _state.value.signedIn

    /** 当前 token；未登录时是空串。每次现取，避免用户在别处清掉后还拿着旧的。 */
    val token: String get() = keyStore.getSecret(SCOPE, FIELD_TOKEN).orEmpty()

    fun signIn(auth: Auth) {
        keyStore.putSecret(SCOPE, FIELD_TOKEN, auth.token)
        keyStore.putSecret(SCOPE, FIELD_USER, auth.username)
        _state.value = State(signedIn = true, username = auth.username)
    }

    /**
     * 退出登录。
     *
     * 只清本地：远端那条 token 记在服务器上也没关系（它没有任何用处，
     * 除非有人同时拿到设备和这串字符）。真正的理由是不能让"退出"变成
     * 一个会失败的操作 —— 网络不通时用户仍然要能退出。
     */
    fun signOut() {
        keyStore.putSecret(SCOPE, FIELD_TOKEN, null)
        keyStore.putSecret(SCOPE, FIELD_USER, null)
        _state.value = State()
    }

    private fun read(): State {
        val token = keyStore.getSecret(SCOPE, FIELD_TOKEN).orEmpty()
        val user = keyStore.getSecret(SCOPE, FIELD_USER).orEmpty()
        return State(signedIn = token.isNotBlank(), username = user)
    }

    // ---------------- 投稿映射 ----------------

    private val mapFile = File(dir, "submitted.json")
    private val mutex = Mutex()
    private var cache: MutableMap<String, Long>? = null

    /** 本机某张卡上次投稿得到的远端 id；没投过返回 0 */
    suspend fun remoteIdOf(cardId: String): Long = withContext(Dispatchers.IO) {
        if (cardId.isBlank()) return@withContext 0L
        mutex.withLock { readMapLocked()[cardId] ?: 0L }
    }

    suspend fun rememberSubmission(cardId: String, remoteId: Long) = withContext(Dispatchers.IO) {
        if (cardId.isBlank() || remoteId <= 0) return@withContext
        mutex.withLock {
            val map = readMapLocked()
            map[cardId] = remoteId
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                mapFile.writeText(json.encodeToString(MAP_SERIALIZER, map.toMap()))
            }.onFailure { Logx.w(TAG, "投稿映射写入失败", it) }
        }
    }

    private fun readMapLocked(): MutableMap<String, Long> {
        cache?.let { return it }
        val loaded = if (mapFile.exists()) {
            runCatching {
                json.decodeFromString(MAP_SERIALIZER, mapFile.readText()).toMutableMap()
            }.getOrElse {
                Logx.w(TAG, "投稿映射解析失败，按空处理", it)
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }
        cache = loaded
        return loaded
    }

    private companion object {
        const val TAG = "CommunityAccount"
        const val SCOPE = "community"
        const val FIELD_TOKEN = "token"
        const val FIELD_USER = "username"

        val MAP_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
    }
}
