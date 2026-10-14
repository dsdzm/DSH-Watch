package com.dshwatch.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dshwatch.core.Logx
import com.dshwatch.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "dsh_settings")

/**
 * 全部非敏感设置序列化成一个 JSON 存在 DataStore 里。
 * 好处：加字段不用改 schema、不用写 migration；缺点：整块读写。
 * 设置体积只有几 KB，对 DataStore 来说完全无压力。
 *
 * 注意：API Key / 设备令牌**不在这里**，见 [SecureKeyStore]。
 */
class SettingsRepository(
    private val context: Context,
    private val json: Json,
) : com.dshwatch.domain.SettingsSource {

    private val prefsKey = stringPreferencesKey("app_settings_v1")

    val flow: Flow<AppSettings> = context.settingsDataStore.data
        .map { decode(it[prefsKey]) }
        .distinctUntilChanged()
        .conflate()

    override suspend fun current(): AppSettings = decode(context.settingsDataStore.data.first()[prefsKey])

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var updated = AppSettings()
        context.settingsDataStore.edit { prefs ->
            val current = decode(prefs[prefsKey])
            updated = transform(current)
            prefs[prefsKey] = json.encodeToString(AppSettings.serializer(), updated)
        }
        return updated
    }

    private fun decode(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrElse {
                Logx.w("Settings", "设置解析失败，回退默认值: ${it.message}")
                AppSettings()
            }
    }
}
