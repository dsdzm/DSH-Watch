package com.dshwatch.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Provider
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.screens.provider.hasCredential
import kotlinx.coroutines.launch

/**
 * 手机版的模型列表。
 *
 * 手表上这一页是「一张张卡片、点进去才能看到有没有填 Key」；手机上空间够，
 * 直接把**三件关键信息**摆在行内：名字、Key 有没有填、用的是哪个模型。
 * 首次配置的人最常卡在「我觉得填了但其实没保存」，这一行能直接看出来。
 *
 * 编辑页仍复用 `ProviderEditScreen`（[ScreenHost]）：那里是一张几十项的表单，
 * 重写一遍的收益远小于维护两份逻辑的风险。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneProvidersScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val activeId = settings.activeProvider?.id.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s.providersTitle, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = { nav.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                }
            },
            actions = {
                IconButton(onClick = { nav.open(Screen.ProviderEdit(null)) }) {
                    Icon(Icons.Filled.Add, contentDescription = s.addProvider)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (settings.providers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.addProvider, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = s.typeOpenAiHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = { nav.open(Screen.ProviderEdit(null)) }) {
                        Text("添加一个")
                    }
                }
            }
            return@Column
        }

        Text(
            text = "聊天固定使用「${settings.activeProvider?.name ?: "未配置"}」，点右侧「使用」可切换",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(settings.providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    isActive = provider.id == activeId,
                    hasKey = hasCredential(container, provider),
                    onEdit = { nav.open(Screen.ProviderEdit(provider.id)) },
                    onActivate = {
                        scope.launch {
                            container.settingsRepo.update { it.copy(activeProviderId = provider.id) }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    isActive: Boolean,
    hasKey: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
) {
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name.ifBlank { "未命名" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isActive) {
                        Spacer(Modifier.size(5.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!hasKey) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(3.dp))
                    }
                    Text(
                        text = buildString {
                            append(if (hasKey) "已填 Key" else "没填 Key")
                            append(" · ")
                            append(provider.defaultModel.ifBlank { "默认模型" })
                            val host = provider.normalizedBaseUrl()
                                .removePrefix("https://")
                                .removePrefix("http://")
                                .trimEnd('/')
                            if (host.isNotBlank()) append(" · $host")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasKey) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!isActive) {
                TextButton(onClick = onActivate) { Text("使用") }
            }
        }
    }
}
