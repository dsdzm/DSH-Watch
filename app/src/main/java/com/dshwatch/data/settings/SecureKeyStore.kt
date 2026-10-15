package com.dshwatch.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dshwatch.core.Logx

/**
 * API Key / DSH 设备令牌的加密存储。
 *
 * 优先使用 Android Keystore 支持的 EncryptedSharedPreferences。
 * 部分定制 ROM（尤其国产 Android 9 手表）会缺少 Tink 依赖的 provider，
 * 或者在系统升级 / 恢复出厂后让 Keystore 里的主密钥失效，
 * 此时**读写会直接抛异常**——旧实现把这些异常吞掉了，
 * 表现出来就是「Key 明明填过，却提示 Key 为空」，非常难查。
 *
 * 现在的策略：
 *  1) 读写全部包 runCatching，异常记录到 [lastError] 并在诊断页展示；
 *  2) 加密存储读失败时自动降级到普通存储，保证 App 还能用（代价是本机旧密钥需要重填）；
 *  3) [health] 供诊断页调用，明确告诉用户当前用的是哪种存储。
 */
class SecureKeyStore(context: Context) : com.dshwatch.domain.CredentialSource {

    private val appContext = context.applicationContext

    /** 最近一次读写异常，供诊断页展示；为空表示一切正常 */
    @Volatile
    var lastError: String? = null
        private set

    /** 是否成功启用了加密存储 */
    @Volatile
    var encrypted: Boolean = false
        private set

    private var prefs: SharedPreferences = openEncrypted() ?: openFallback()

    private fun openEncrypted(): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val p = EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
        // create() 内部可能不触发真正的解密，这里做一次读探测，尽早暴露问题
        p.getString(PROBE_KEY, null)
        encrypted = true
        p
    }.getOrElse { t ->
        Logx.w(TAG, "加密存储不可用，降级为普通存储：${t.javaClass.simpleName} ${t.message}")
        lastError = "加密存储不可用（${t.javaClass.simpleName}），已降级为普通存储"
        null
    }

    private fun openFallback(): SharedPreferences {
        encrypted = false
        return appContext.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    /** 遇到解密失败时切换存储，避免整个 App 因为读不到 Key 而不可用 */
    private fun recover(reason: Throwable) {
        Logx.e(TAG, "读取加密存储失败，切换到普通存储", reason)
        lastError = "加密存储读取失败（${reason.javaClass.simpleName}），已切换为普通存储，请重新填写 API Key"
        prefs = runCatching { openFallback() }.getOrElse { prefs }
    }

    // ---------------- 读写 ----------------

    override fun getApiKey(providerId: String): String? = get(k(providerId, FIELD_API_KEY))

    fun putApiKey(providerId: String, value: String?) = put(k(providerId, FIELD_API_KEY), value)

    override fun getServerToken(providerId: String): String? = get(k(providerId, FIELD_TOKEN))

    fun putServerToken(providerId: String, value: String?) = put(k(providerId, FIELD_TOKEN), value)

    fun getPairingCode(providerId: String): String? = get(k(providerId, FIELD_PAIRING))

    fun putPairingCode(providerId: String, value: String?) = put(k(providerId, FIELD_PAIRING), value)

    /**
     * 云端编译用的 GitHub 令牌。
     *
     * 不挂在某个 provider 下面（它不属于任何模型供应商），用一个合成的 id 复用同一套加密存储。
     * 与 API Key 一样：只加密存在本机，不进日志、不进仓库、不给脚本。
     */
    fun getGithubToken(): String? = get(k(CLOUD_SCOPE, FIELD_GITHUB_TOKEN))

    fun putGithubToken(value: String?) = put(k(CLOUD_SCOPE, FIELD_GITHUB_TOKEN), value)

    fun clear(providerId: String) {
        runCatching {
            prefs.edit()
                .remove(k(providerId, FIELD_API_KEY))
                .remove(k(providerId, FIELD_TOKEN))
                .remove(k(providerId, FIELD_PAIRING))
                .apply()
        }.onFailure { t ->
            lastError = "清除凭据失败：${t.javaClass.simpleName}"
            Logx.e(TAG, "清除凭据失败", t)
        }
    }

    /** 清除云端编译的令牌（用户在设置里撤销时调用） */
    fun clearGithubToken() {
        runCatching {
            prefs.edit().remove(k(CLOUD_SCOPE, FIELD_GITHUB_TOKEN)).apply()
        }.onFailure { t ->
            lastError = "清除 GitHub 令牌失败：${t.javaClass.simpleName}"
            Logx.e(TAG, "清除 GitHub 令牌失败", t)
        }
    }

    /** 只回显尾部 4 位，用于界面展示 */
    fun masked(providerId: String, type: KeyType): String {
        val raw = when (type) {
            KeyType.API_KEY -> getApiKey(providerId)
            KeyType.SERVER_TOKEN -> getServerToken(providerId)
        } ?: return ""
        if (raw.length <= 6) return "••••"
        return "••••" + raw.takeLast(4)
    }

    /** 诊断页展示用 */
    fun health(): String = buildString {
        append(if (encrypted) "加密存储正常（Android Keystore）" else "普通存储（未加密）")
        lastError?.let { append(" · ").append(it) }
    }

    private fun get(key: String): String? = runCatching {
        prefs.getString(key, null)?.ifBlank { null }
    }.getOrElse { t ->
        recover(t)
        null
    }

    private fun put(key: String, value: String?) {
        runCatching {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(key) else putString(key, value)
            }.apply()
        }.onFailure { t ->
            // 写失败再试一次（可能是加密存储坏了），并记录
            Logx.e(TAG, "写入凭据失败：${t.javaClass.simpleName}", t)
            lastError = "写入凭据失败：${t.javaClass.simpleName}"
            runCatching { openFallback().edit().putString(key, value).apply() }
        }
    }

    private fun k(providerId: String, field: String) = "p_${providerId}_$field"

    enum class KeyType { API_KEY, SERVER_TOKEN }

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val FILE_NAME = "dsh_secure_keys"
        private const val FALLBACK_FILE_NAME = "dsh_secure_keys_fallback"
        private const val FIELD_API_KEY = "apiKey"
        private const val FIELD_TOKEN = "token"
        private const val FIELD_PAIRING = "pairing"

        /** GitHub 令牌不属于任何 provider，用一个合成作用域复用同一套存储 */
        private const val CLOUD_SCOPE = "cloud"
        private const val FIELD_GITHUB_TOKEN = "githubToken"
        private const val PROBE_KEY = "__probe__"
    }
}
