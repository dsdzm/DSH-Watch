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
        val parsed = runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrElse {
                Logx.w("Settings", "设置解析失败，回退默认值: ${it.message}")
                AppSettings()
            }
        return migrate(parsed)
    }

    /**
     * 老存档的默认值修正。
     *
     * 配图来源在 1.11 之前只有 `none / card / local`，默认 `none`；现在多了
     * 「内置表情包」并把它设成默认。老用户升级后如果静默保持 `none`，
     * 这个新功能对他们等于不存在。
     *
     * 但**不能无条件覆盖**：有用户是主动选的不带图。所以看
     * [AppSettings.biliImageTouched] —— 没动过才改，动过就完全尊重。
     * 迁移是幂等的，每次读设置跑一遍也不会出问题。
     */
    private fun migrate(s: AppSettings): AppSettings {
        var out = s
        // 1.11 的配图默认值修正
        if (!out.biliImageTouched && out.biliImage == "none") {
            out = out.copy(biliImage = "pack")
        }
        // v1.15：玻璃从「主题预设」里拆成独立的「外观风格」。
        //
        // 注意这里**不再改写 themePreset**。玻璃预设本身仍然存在、仍然可选，
        // 它只是不再等于「材质」。之前的版本把它强行改成 "oled"，
        // 结果用户辛辛苦苦选的玻璃底色被换成纯黑 —— 玻璃要的就是能透出底色，
        // 底换成黑，玻璃就只剩一块灰板。
        //
        // 唯一要补的是：老数据里 themePreset = "glass" 意味着当时用的就是玻璃材质，
        // 所以 appearance 要一并置成玻璃，否则升级后会变成「玻璃预设 + 不透明卡片」，
        // 那套底色配实心卡片是最难看的一种组合。
        if (out.themePreset == com.dshwatch.ui.theme.ThemePresets.GLASS_ID &&
            out.appearance != com.dshwatch.domain.model.Appearances.GLASS
        ) {
            out = out.copy(appearance = com.dshwatch.domain.model.Appearances.GLASS)
        }
        // 归一化 appearance：未知取值（含老存档的空串）一律落回 M3E
        val appearance = com.dshwatch.domain.model.Appearances.of(out.appearance)
        if (appearance != out.appearance) out = out.copy(appearance = appearance)
        return out
    }
}
