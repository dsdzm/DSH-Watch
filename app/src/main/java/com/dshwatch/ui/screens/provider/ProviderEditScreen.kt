package com.dshwatch.ui.screens.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Ids
import com.dshwatch.domain.PromptPresets
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.domain.model.ReasoningEffort
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.ConfirmDialog
import com.dshwatch.ui.components.cornerSafe
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SegmentedRow
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.StepperRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.util.Fmt
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderEditScreen(
    container: AppContainer,
    settings: AppSettings,
    providerId: String?,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val existing = remember(providerId) {
        providerId?.let { id -> settings.providers.firstOrNull { it.id == id } }
    }
    val newId = remember { Ids.new() }

    var draft by remember(providerId) {
        mutableStateOf(
            existing ?: Provider(
                id = newId,
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                models = listOf("deepseek-chat", "deepseek-reasoner"),
                defaultModel = "deepseek-chat",
                presetId = PromptPresets.VIBE_CODING,
            )
        )
    }
    var apiKey by remember(providerId) {
        mutableStateOf(existing?.let { container.keyStore.getApiKey(it.id) }.orEmpty())
    }
    var serverToken by remember(providerId) {
        mutableStateOf(existing?.let { container.keyStore.getServerToken(it.id) }.orEmpty())
    }

    var busy by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var resultIsError by remember { mutableStateOf(false) }
    var manualModel by remember { mutableStateOf("") }
    var showPresetPicker by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showPromptEditor by remember { mutableStateOf(false) }

    fun persist() {
        scope.launch {
            val toSave = draft.copy(baseUrl = draft.baseUrl.trim())
            container.settingsRepo.update { st ->
                val list = st.providers.toMutableList()
                val idx = list.indexOfFirst { it.id == toSave.id }
                if (idx >= 0) list[idx] = toSave else list.add(toSave)
                val active = st.activeProviderId.ifBlank { toSave.id }
                st.copy(providers = list, activeProviderId = active)
            }
            if (toSave.type == ProviderType.DSH_SERVER) {
                container.keyStore.putServerToken(toSave.id, serverToken.trim().ifBlank { null })
            } else {
                container.keyStore.putApiKey(toSave.id, apiKey.trim().ifBlank { null })
            }
            nav.pop()
        }
    }

    fun test() {
        scope.launch {
            busy = "test"
            result = null
            resultIsError = false
            val outcome = runCatching {
                val backend = container.backends.of(draft.type)
                backend.testConnection(
                    provider = draft.copy(baseUrl = draft.baseUrl.trim()),
                    apiKey = apiKey.trim(),
                    serverToken = serverToken.trim(),
                )
            }
            outcome.onSuccess {
                result = it
                resultIsError = false
            }.onFailure {
                result = "${s.connectionFailed}：${it.message ?: it.javaClass.simpleName}"
                resultIsError = true
            }
            busy = ""
        }
    }

    fun fetchModels() {
        scope.launch {
            busy = "models"
            result = null
            resultIsError = false
            runCatching {
                container.backends.of(draft.type).listModels(
                    provider = draft.copy(baseUrl = draft.baseUrl.trim()),
                    apiKey = apiKey.trim(),
                    serverToken = serverToken.trim(),
                )
            }.onSuccess { models ->
                if (models.isEmpty()) {
                    result = "服务端返回了空列表"
                    resultIsError = true
                } else {
                    draft = draft.copy(
                        models = models,
                        defaultModel = draft.defaultModel.ifBlank { models.first() },
                    )
                    result = "拉到 ${models.size} 个模型"
                    resultIsError = false
                }
            }.onFailure {
                result = "拉取失败：${it.message ?: it.javaClass.simpleName}"
                resultIsError = true
            }
            busy = ""
        }
    }

    WatchScaffold(
        title = if (existing == null) s.newProvider else s.editProvider,
        onBack = { nav.pop() },
        bottom = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .cornerSafe()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),

            ) {
                PrimaryButton(text = s.save, onClick = { persist() })
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            result?.let { text ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (resultIsError) {
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

            SectionHeader("基础")
            SettingCard {
                TextRow(
                    label = s.providerName,
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = s.providerType,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                SegmentedRow(
                    options = listOf(s.typeOpenAi, s.typeDsh),
                    selectedIndex = if (draft.type == ProviderType.OPENAI_COMPAT) 0 else 1,
                    onSelect = { index ->
                        draft = draft.copy(
                            type = if (index == 0) ProviderType.OPENAI_COMPAT else ProviderType.DSH_SERVER,
                        )
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (draft.type == ProviderType.OPENAI_COMPAT) s.typeOpenAiHint else s.typeDshHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(6.dp))
            }

            SectionHeader("连接")
            SettingCard {
                TextRow(
                    label = s.baseUrl,
                    value = draft.baseUrl,
                    placeholder = s.baseUrlHint,
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                )
                Spacer(Modifier.height(6.dp))
                if (draft.type == ProviderType.OPENAI_COMPAT) {
                    TextRow(
                        label = s.apiKey,
                        value = apiKey,
                        placeholder = s.apiKeyHint,
                        isSecret = true,
                        onValueChange = { apiKey = it },
                    )
                } else {
                    TextRow(
                        label = s.pairedDevice,
                        value = serverToken,
                        placeholder = "配对后自动填入",
                        isSecret = true,
                        onValueChange = { serverToken = it },
                    )
                    Spacer(Modifier.height(6.dp))
                    GhostButton(text = s.pairTitle, onClick = { nav.push(Screen.Pair) })
                }
                Spacer(Modifier.height(6.dp))
                if (busy == "test") {
                    GhostButton(
                        text = s.testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {},
                    )
                } else {
                    GhostButton(
                        text = s.testConnection,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { test() },
                    )
                }
                Spacer(Modifier.height(5.dp))
            }

            SectionHeader(s.modelsTitle)
            SettingCard {
                TextRow(
                    label = s.defaultModel,
                    value = draft.defaultModel,
                    placeholder = "deepseek-chat",
                    onValueChange = { draft = draft.copy(defaultModel = it.trim()) },
                )
                Spacer(Modifier.height(6.dp))
                TextRow(
                    label = s.addModelManually,
                    value = manualModel,
                    placeholder = s.modelNameInput,
                    onValueChange = { manualModel = it },
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(text = s.add, onClick = {
                        val m = manualModel.trim()
                        if (m.isNotBlank() && m !in draft.models) {
                            draft = draft.copy(
                                models = draft.models + m,
                                defaultModel = draft.defaultModel.ifBlank { m },
                            )
                        }
                        manualModel = ""
                    })
                    Chip(
                        text = if (busy == "models") s.fetchingModels else s.fetchModels,
                        onClick = { if (busy.isEmpty()) fetchModels() },
                    )
                }
                if (draft.models.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        draft.models.forEach { model ->
                            val selected = model == draft.defaultModel
                            Chip(
                                text = if (selected) "✓ $model" else model,
                                selected = selected,
                                onClick = { draft = draft.copy(defaultModel = model) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            SectionHeader("生成参数")
            SettingCard {
                StepperRow(
                    title = s.temperature,
                    value = String.format(Locale.US, "%.1f", draft.temperature),
                    onDecrease = {
                        draft = draft.copy(temperature = (draft.temperature - 0.1f).coerceAtLeast(0f))
                    },
                    onIncrease = {
                        draft = draft.copy(temperature = (draft.temperature + 0.1f).coerceAtMost(2f))
                    },
                )
                StepperRow(
                    title = s.maxTokens,
                    value = Fmt.tokens(draft.maxTokens),
                    onDecrease = {
                        draft = draft.copy(maxTokens = (draft.maxTokens - 256).coerceAtLeast(128))
                    },
                    onIncrease = {
                        draft = draft.copy(maxTokens = (draft.maxTokens + 256).coerceAtMost(32_768))
                    },
                )
                StepperRow(
                    title = s.contextWindow,
                    value = Fmt.tokens(draft.contextWindow),
                    onDecrease = {
                        draft = draft.copy(contextWindow = (draft.contextWindow - 16_384).coerceAtLeast(8_192))
                    },
                    onIncrease = {
                        draft = draft.copy(contextWindow = (draft.contextWindow + 16_384).coerceAtMost(2_000_000))
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = s.reasoningEffort,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                SegmentedRow(
                    options = listOf("默认", "低", "中", "高"),
                    selectedIndex = when (draft.reasoningEffort) {
                        ReasoningEffort.DEFAULT -> 0
                        ReasoningEffort.LOW -> 1
                        ReasoningEffort.MEDIUM -> 2
                        ReasoningEffort.HIGH -> 3
                    },
                    onSelect = { index ->
                        draft = draft.copy(
                            reasoningEffort = when (index) {
                                1 -> ReasoningEffort.LOW
                                2 -> ReasoningEffort.MEDIUM
                                3 -> ReasoningEffort.HIGH
                                else -> ReasoningEffort.DEFAULT
                            }
                        )
                    },
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    title = s.streamEnabled,
                    checked = draft.stream,
                    onCheckedChange = { draft = draft.copy(stream = it) },
                )
                SwitchRow(
                    title = s.requestUsage,
                    subtitle = "让服务端在流式响应末尾带上 usage",
                    checked = draft.requestUsageInStream,
                    onCheckedChange = { draft = draft.copy(requestUsageInStream = it) },
                )
            }

            SectionHeader("提示词")
            SettingCard {
                SettingRow(
                    title = s.preset,
                    subtitle = PromptPresets.byId(draft.presetId).description,
                    trailing = PromptPresets.byId(draft.presetId).name,
                    showDivider = true,
                    onClick = { showPresetPicker = true },
                )
                SettingRow(
                    title = s.customPrompt,
                    subtitle = Fmt.preview(draft.customPrompt.ifBlank { s.customPromptHint }, 30),
                    trailing = "›",
                    onClick = { showPromptEditor = !showPromptEditor },
                )
                if (showPromptEditor) {
                    Spacer(Modifier.height(6.dp))
                    TextRow(
                        label = s.customPrompt,
                        value = draft.customPrompt,
                        placeholder = s.customPromptHint,
                        singleLine = false,
                        onValueChange = { draft = draft.copy(customPrompt = it) },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            SectionHeader("计价（用于估算花费）")
            SettingCard {
                TextRow(
                    label = "${s.priceIn}（${s.pricePerMillion}）",
                    value = draft.priceInPerMillion.toString(),
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = {
                        draft = draft.copy(priceInPerMillion = it.toDoubleOrNull() ?: 0.0)
                    },
                )
                Spacer(Modifier.height(6.dp))
                TextRow(
                    label = "${s.priceOut}（${s.pricePerMillion}）",
                    value = draft.priceOutPerMillion.toString(),
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = {
                        draft = draft.copy(priceOutPerMillion = it.toDoubleOrNull() ?: 0.0)
                    },
                )
                Spacer(Modifier.height(4.dp))
            }

            SectionHeader("操作")
            SettingCard {
                GhostButton(text = s.setActive, onClick = {
                    scope.launch {
                        // 必须先把草稿落盘再切当前，否则 activeProviderId 会指向一个
                        // 还不存在的 id，chat 侧会被 fallback 到「第一个供应商」，看起来像切换无效。
                        val toSave = draft.copy(baseUrl = draft.baseUrl.trim())
                        container.settingsRepo.update { st ->
                            val list = st.providers.toMutableList()
                            val idx = list.indexOfFirst { it.id == toSave.id }
                            if (idx >= 0) list[idx] = toSave else list.add(toSave)
                            st.copy(providers = list, activeProviderId = toSave.id)
                        }
                        if (toSave.type == ProviderType.DSH_SERVER) {
                            container.keyStore.putServerToken(toSave.id, serverToken.trim().ifBlank { null })
                        } else {
                            container.keyStore.putApiKey(toSave.id, apiKey.trim().ifBlank { null })
                        }
                        nav.pop()
                    }
                })
                if (existing != null) {
                    Spacer(Modifier.height(6.dp))
                    GhostButton(
                        text = s.deleteProvider,
                        danger = true,
                        onClick = { showDelete = true },
                    )
                }
                Spacer(Modifier.height(2.dp))
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPresetPicker) {
        AlertDialog(
            onDismissRequest = { showPresetPicker = false },
            title = { Text(s.preset, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    PromptPresets.all.forEach { preset ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tap(onClick = withHaptic {
                                    draft = draft.copy(presetId = preset.id)
                                    showPresetPicker = false
                                })
                                .padding(vertical = 7.dp),
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (preset.id == draft.presetId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPresetPicker = false }) { Text(s.cancel) } },
        )
    }

    if (showDelete && existing != null) {
        ConfirmDialog(
            title = s.deleteProvider,
            text = s.deleteProviderConfirm,
            onConfirm = {
                showDelete = false
                scope.launch {
                    container.keyStore.clear(existing.id)
                    container.settingsRepo.update { st ->
                        val list = st.providers.filterNot { it.id == existing.id }
                        st.copy(
                            providers = list,
                            activeProviderId = if (st.activeProviderId == existing.id) {
                                list.firstOrNull()?.id.orEmpty()
                            } else {
                                st.activeProviderId
                            },
                        )
                    }
                    nav.pop()
                }
            },
            onDismiss = { showDelete = false },
        )
    }
}
