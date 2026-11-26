package com.dshwatch.ui.screens.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.IconTap
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.FlatList
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    // activeProviderId 为空时 settings.activeProvider 会退化成第一个，这里保持一致
    val activeId = settings.activeProvider?.id.orEmpty()

    WatchScaffold(
        title = s.providersTitle,
        onBack = { nav.pop() },
        actions = {
            IconTap(Icons.Filled.Add, s.addProvider) {
                nav.push(Screen.ProviderEdit(null))
            }
        },
    ) {
        if (settings.providers.isEmpty()) {
            EmptyState(title = s.addProvider, hint = s.typeOpenAiHint)
            return@WatchScaffold
        }
        FlatList(
            state = com.dshwatch.ui.wear.rememberKeptLazyState("providers"),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "__hint__") {
                Text(
                    text = "聊天固定使用「${settings.activeProvider?.name ?: "未配置"}」，点下面的「${s.setActive}」可切换",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
            items(settings.providers.size, key = { settings.providers[it].id }) { index ->
                val provider = settings.providers[index]
                ProviderRow(
                    provider = provider,
                    isActive = provider.id == activeId,
                    hasKey = hasCredential(container, provider),
                    onEdit = { nav.push(Screen.ProviderEdit(provider.id)) },
                    onActivate = {
                        scope.launch {
                            container.settingsRepo.update { it.copy(activeProviderId = provider.id) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: Provider,
    isActive: Boolean,
    hasKey: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .tap(onClick = withHaptic(onEdit))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Spacer(Modifier.width(4.dp))
                    Chip(text = s.activeProvider, selected = true)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    if (provider.isBuiltin) {
                        // 内置通道对用户不暴露地址与 key，这里也不显示「Key ✓」——
                        // 它永远是 ✓，写出来只会让人以为可以点进去改
                        append("内置通道 · ")
                        append(provider.defaultModel.ifBlank { "未选模型" })
                        /*
                         * 但「这份包压根没编进 Key」要明说。
                         *
                         * 那种包内置通道一用就鉴权失败，而用户看到的只是一行
                         * 正常的「内置通道 · agnes-3.0-flash」—— 他不会想到是包里
                         * 少了东西。标出来，他才会点进去填自己的 Key。
                         */
                        if (!com.dshwatch.domain.model.BuiltinProviders.isConfigured(provider.id)) {
                            append(" · ")
                            append(if (s.isChinese) "未配置 Key" else "no key")
                        }
                    } else if (provider.accountAuth) {
                        /*
                         * 账号模式没有 key 可显示，展示「登录没登录」——
                         * 这一行是用户判断「为什么发不出消息」的第一现场。
                         */
                        append(if (s.isChinese) "DeepSeek 账号" else "DeepSeek account")
                        append(" · ")
                        append(provider.defaultModel.ifBlank { "未选模型" })
                        append(" · ")
                        append(
                            when {
                                hasKey -> if (s.isChinese) "已登录" else "signed in"
                                else -> if (s.isChinese) "未登录" else "signed out"
                            },
                        )
                    } else {
                        append(if (provider.type == ProviderType.DSH_SERVER) s.typeDsh else s.typeOpenAi)
                        append(" · ")
                        append(provider.defaultModel.ifBlank { "未选模型" })
                        append(" · Key ")
                        append(if (hasKey) "✓" else "✗")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (
                    provider.isBuiltin &&
                    !com.dshwatch.domain.model.BuiltinProviders.isConfigured(provider.id)
                ) {
                    // 这不是「提示」，是「现在发不出消息」—— 用错误色
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!isActive) {
                Spacer(Modifier.height(4.dp))
                // 直接在这里切换「聊天走哪个」，不用进编辑页。
                // 之前只能在编辑页里点「设为当前」，而且那个按钮不落盘，很容易配了 B 却一直在用 A。
                Chip(text = s.setActive, onClick = onActivate)
            }
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

internal fun hasCredential(container: AppContainer, provider: Provider): Boolean = when {
    provider.type == ProviderType.DSH_SERVER -> !container.keyStore.getServerToken(provider.id).isNullOrBlank()
    // 账号模式：凭据是账号 token，不是这一条供应商自己的 API Key
    provider.accountAuth -> !container.keyStore.getDeepSeekToken().isNullOrBlank()
    else -> !container.keyStore.getApiKey(provider.id).isNullOrBlank()
}
