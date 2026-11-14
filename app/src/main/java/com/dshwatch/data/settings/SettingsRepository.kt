package com.dshwatch.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dshwatch.core.Logx
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.BuiltinProviders
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        /*
         * **必须兜住 DataStore 自己的异常。**
         *
         * DataStore 的 `data` 流不是"读成功了才发"，它会在读盘失败时
         * **把异常直接 emit 进流里**（典型：文件损坏 / 权限异常 /
         * 某些兼容层的文件系统语义不完整）。上游 `stateIn(..., Eagerly)`
         * 所在的就是 AppContainer 的 appScope —— 异常一路传上去会把
         * 整个 scope 干掉，之后所有后台任务一起停摆，界面上表现为
         * 「启动一下就没了」。
         *
         * 这里退回默认设置：用户会看到初始设置，但 App 至少进得去。
         * 设置本来就是可以重设的东西，比打不开强。
         */
        .catch { e ->
            Logx.e("Settings", "读取设置失败，回退默认值", e)
            emit(AppSettings())
        }
        .distinctUntilChanged()
        .conflate()

    override suspend fun current(): AppSettings = runCatching {
        decode(context.settingsDataStore.data.first()[prefsKey])
    }.getOrElse {
        Logx.e("Settings", "读取设置失败（current），回退默认值", it)
        AppSettings()
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var updated = AppSettings()
        runCatching {
            /*
             * **写入必须跑完，哪怕调用方的协程已经死了。**
             *
             * 界面层是用 `rememberCoroutineScope()` 调进来的，手表上这个作用域
             * 死得非常频繁：设置页停着不动、屏幕一黑，系统就把 Activity 回收了，
             * composition 一散，scope 连同里面正在跑的 DataStore.edit 一起取消。
             * 用户的原话是「改了设置，过一会儿又变回去了」—— 日志里就是连着四条
             * `写入设置失败 · JobCancellationException: ... left the composition`。
             *
             * `NonCancellable` 把这段挂到不可取消的上下文里：界面可以没，
             * 用户已经做出的这个决定不能被一起带走。DataStore 的 edit 本身是
             * 事务性的，跑完就是原子落盘，不存在写一半。
             */
            withContext(NonCancellable) {
                context.settingsDataStore.edit { prefs ->
                    val current = decode(prefs[prefsKey])
                    updated = transform(current)
                    prefs[prefsKey] = json.encodeToString(AppSettings.serializer(), updated)
                }
            }
        }.onFailure {
            // 写不进去（磁盘满 / 兼容层文件系统异常）不该让调用方的协程崩掉。
            // 界面上的改动能生效到下次重启前，总比点一下就闪退好。
            Logx.e("Settings", "写入设置失败", it)
        }
        return updated
    }

    private fun decode(raw: String?): AppSettings {
        // 空存档也要走一遍 migrate：内置供应商是在迁移里注入的，
        // 直接 return AppSettings() 会让全新安装的用户看不到它。
        if (raw.isNullOrBlank()) return migrate(AppSettings())
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

        // 归一化界面体系：未知取值一律落回经典（默认值，也是老用户升级后的样子）
        val uiStyle = com.dshwatch.domain.model.UiStyles.of(out.uiStyle)
        if (uiStyle != out.uiStyle) out = out.copy(uiStyle = uiStyle)

        /*
         * v1.15.22：字号并入界面大小。
         *
         * 旧版有两个滑块 —— 「界面大小」（缩 density）和「字号」（缩 fontScale）。
         * 两者在文字上**相乘**：1.15 × 1.30 让字号相对卡片放大到 1.5 倍，
         * 于是字撑出卡片、内容顶到圆周外被切。用户的原话是「先调缩放再调字号
         * 会出问题，先调字号再调缩放也会出问题」——单独调任一个都正常。
         *
         * 合并后只剩 uiScale 一个轴，这里把旧字号值折进去：
         *  - 原本手动调过界面大小：直接相乘，保留用户当时的观感；
         *  - 原本跟随本机自动：**不动**。自动值是按屏幕宽度算的安全值，
         *    再乘 1.3 就必然溢出 —— 这正是要修的病，不能在迁移里重新犯一遍。
         *
         * 幂等：fontScale 归 1.0 之后条件不再成立，重复执行不会连乘。
         */
        if (out.fontScale != 1f) {
            val merged = if (out.uiScaleAuto) {
                out.uiScale
            } else {
                (out.uiScale * out.fontScale).coerceIn(
                    com.dshwatch.domain.UiScale.MIN,
                    com.dshwatch.domain.UiScale.MAX,
                )
            }
            out = out.copy(uiScale = merged, fontScale = 1f)
        }

        /*
         * 注入内置供应商。
         *
         * 目的很明确：新用户装完就能发第一条消息，不必先去搞一个 API Key。
         * 这是「手表上配置一个供应商」的门槛决定的 —— 在小屏幕上敲 baseUrl、
         * 敲 key、再点一次拉模型列表，多数人会在第二步放弃。
         *
         * 三个约束：
         *  - 幂等：已经存在就什么都不做；
         *  - 用户删得掉：删掉时记进 builtinDismissedIds，之后不再补回来
         *    （否则会变成「删了又出现」的鬼打墙）；
         *  - 不抢占选择：activeProviderId 已有值时不改，只在新装时兜底。
         *
         * 1.17 起有多家内置通道，必须**逐家**判断是否被删过：
         * 用一个全局布尔的话，用户删掉 Agnes 会连带让智谱也不再注入，
         * 而他其实从没碰过智谱。旧存档里的 [AppSettings.builtinDismissed]
         * 只代表「Agnes 被删过」，所以只对 Agnes 生效。
         */
        /*
         * 归一化已存在的内置项。
         *
         * v1.17.0 的 `Provider.isBuiltin` 只认 Agnes，智谱那一项在设置页被当成
         * 普通供应商：用户可以点「拉取模型」把官方全量（含付费档）灌进列表，
         * 也能改地址。这些值一旦存档就会一直留着 —— 界面隐藏解决不了历史数据。
         *
         * 所以每次加载都把「界面上本来就不可编辑」的三项拉回注册表：
         * 地址、模型名单、失效的默认模型。温度 / maxTokens / 上下文窗口
         * 本来就应该让用户调，保持原样。
         */
        val normalized = out.providers.map { p ->
            val spec = BuiltinProviders.of(p.id) ?: return@map p
            val model = p.defaultModel.takeIf { it in spec.models } ?: spec.defaultModel
            if (p.baseUrl == spec.baseUrl && p.models == spec.models && p.defaultModel == model) {
                p
            } else {
                p.copy(baseUrl = spec.baseUrl, models = spec.models, defaultModel = model)
            }
        }
        if (normalized != out.providers) out = out.copy(providers = normalized)

        val missingBuiltins = BuiltinProviders.all.filter { spec ->
            val dismissed = spec.id in out.builtinDismissedIds ||
                (spec.id == BuiltinProviders.AGNES_ID && out.builtinDismissed)
            !dismissed && out.providers.none { it.id == spec.id }
        }
        if (missingBuiltins.isNotEmpty()) {
            val injected = missingBuiltins.map { BuiltinProviders.make(it) }
            out = out.copy(
                providers = injected + out.providers,
                activeProviderId = out.activeProviderId.ifBlank { injected.first().id },
            )
        }
        return out
    }
}
