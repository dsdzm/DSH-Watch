package com.dshwatch.ui.screens.pair

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Ids
import com.dshwatch.domain.PromptPresets
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.util.Fmt
import kotlinx.coroutines.launch

/**
 * DSH 服务器配对页。
 *
 * 配对流程（对齐 dsh-workspace 的远程访问）：
 *  1) 电脑端 DSH → dsh-workspace → 远程访问 → 启用并创建配对；
 *  2) 手机上填入 服务器地址 + 一次性配对码；
 *  3) 换取设备令牌（只保存在本机加密存储里），之后所有请求带 Bearer token。
 */
@Composable
fun PairScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val dshProvider = remember(settings.providers) {
        settings.providers.firstOrNull { it.type == ProviderType.DSH_SERVER }
    }

    var address by remember(dshProvider?.id) {
        mutableStateOf(dshProvider?.baseUrl.orEmpty())
    }
    var code by remember(dshProvider?.id) {
        mutableStateOf(dshProvider?.id?.let { container.keyStore.getPairingCode(it) }.orEmpty())
    }
    var deviceName by remember(dshProvider?.id) {
        mutableStateOf(dshProvider?.deviceName.orEmpty().ifBlank { android.os.Build.MODEL })
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val token = dshProvider?.id?.let { container.keyStore.getServerToken(it) }
    val paired = !token.isNullOrBlank()

    fun pair() {
        val addr = address.trim()
        if (addr.isBlank()) {
            message = "请填写服务器地址"
            isError = true
            return
        }
        if (code.isBlank()) {
            message = "请填写配对码"
            isError = true
            return
        }
        scope.launch {
            busy = true
            message = null
            runCatching {
                container.backends.dshServer.pair(addr, code, deviceName)
            }.onSuccess { response ->
                val provider = dshProvider ?: Provider(
                    id = Ids.new(),
                    name = "DSH 服务器",
                    type = ProviderType.DSH_SERVER,
                    presetId = PromptPresets.VIBE_CODING,
                )
                val models = runCatching {
                    container.backends.dshServer.listModels(provider, "", response.token)
                }.getOrDefault(emptyList())

                val saved = provider.copy(
                    type = ProviderType.DSH_SERVER,
                    baseUrl = addr,
                    models = models,
                    defaultModel = provider.defaultModel.ifBlank { models.firstOrNull().orEmpty() },
                    deviceName = deviceName,
                    pairedAt = System.currentTimeMillis(),
                )
                container.settingsRepo.update { st ->
                    val list = st.providers.toMutableList()
                    val idx = list.indexOfFirst { it.id == saved.id }
                    if (idx >= 0) list[idx] = saved else list.add(saved)
                    st.copy(
                        providers = list,
                        activeProviderId = st.activeProviderId.ifBlank { saved.id },
                    )
                }
                container.keyStore.putServerToken(saved.id, response.token)
                container.keyStore.putPairingCode(saved.id, null)
                code = ""
                message = buildString {
                    append(s.pairSuccess)
                    if (models.isNotEmpty()) append(" · 模型 ${models.size} 个")
                    if (response.expiresAt > 0) append(" · 有效期至 ${Fmt.dateTime(response.expiresAt)}")
                }
                isError = false
            }.onFailure {
                message = "${s.pairFailed}：${it.message ?: it.javaClass.simpleName}"
                isError = true
            }
            busy = false
        }
    }

    WatchScaffold(title = s.pairTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.pairHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            message?.let { text ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(8.dp),
                )
            }

            if (paired) {
                SectionHeader(s.pairedDevice)
                SettingCard {
                    SettingRow(
                        title = dshProvider?.name ?: "DSH 服务器",
                        subtitle = dshProvider?.baseUrl.orEmpty(),
                        trailing = "✓",
                        showDivider = true,
                    )
                    SettingRow(
                        title = s.deviceName,
                        trailing = dshProvider?.deviceName.orEmpty().ifBlank { "—" },
                        showDivider = true,
                    )
                    SettingRow(
                        title = "令牌",
                        subtitle = "••••${token!!.takeLast(4)}",
                    )
                    Spacer(Modifier.height(4.dp))
                    GhostButton(text = s.unpair, danger = true, onClick = {
                        scope.launch {
                            dshProvider?.let { container.keyStore.clear(it.id) }
                            message = "已解除配对"
                            isError = false
                        }
                    })
                    Spacer(Modifier.height(4.dp))
                }
            }

            SectionHeader("填写配对信息")
            SettingCard {
                TextRow(
                    label = s.serverAddress,
                    value = address,
                    placeholder = "http://192.168.1.20:3090",
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { address = it },
                )
                Spacer(Modifier.height(6.dp))
                TextRow(
                    label = s.pairingCode,
                    value = code,
                    placeholder = s.pairingCodeHint,
                    onValueChange = { code = it },
                )
                Spacer(Modifier.height(6.dp))
                TextRow(
                    label = s.deviceName,
                    value = deviceName,
                    placeholder = "DSH Watch",
                    onValueChange = { deviceName = it },
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryButton(
                        text = if (busy) s.pairing else s.pairNow,
                        enabled = !busy,
                        onClick = { pair() },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "提示：HTTP 明文只适合可信局域网；跨公网请用 HTTPS 或 Tailscale。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
