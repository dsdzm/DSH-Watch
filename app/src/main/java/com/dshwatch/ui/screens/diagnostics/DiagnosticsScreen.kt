package com.dshwatch.ui.screens.diagnostics

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dshwatch.BuildConfig
import com.dshwatch.core.AppContainer
import com.dshwatch.data.settings.SecureKeyStore
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.net.backend.DiagStep
import com.dshwatch.net.backend.DshError
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.KeyValueRow
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import kotlinx.coroutines.launch

/**
 * 连接诊断。
 *
 * 手表上没法连电脑看 logcat，所以界面上必须能自证问题出在哪一层。
 * 这里复用后端真正使用的代码路径（同一个 OkHttpClient、同一个请求体构造器），
 * 保证「测的就是发的」，而不是另写一套测试用的假请求。
 */
@Composable
fun DiagnosticsScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val provider = settings.activeProvider
    val apiKey = remember(provider?.id) {
        provider?.id?.let { container.keyStore.getApiKey(it) }.orEmpty()
    }
    val serverToken = remember(provider?.id) {
        provider?.id?.let { container.keyStore.getServerToken(it) }.orEmpty()
    }
    val keyMask = remember(provider?.id) {
        provider?.id?.let {
            container.keyStore.masked(
                it,
                if (provider.type == ProviderType.DSH_SERVER) SecureKeyStore.KeyType.SERVER_TOKEN
                else SecureKeyStore.KeyType.API_KEY,
            )
        }.orEmpty()
    }

    var running by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<DiagStep>>(emptyList()) }
    var copied by remember { mutableStateOf(false) }

    fun runDiagnostics() {
        val p = provider ?: return
        scope.launch {
            running = true
            copied = false
            steps = emptyList()
            steps = runCatching {
                container.backends.of(p.type).diagnose(p, apiKey, serverToken)
            }.getOrElse {
                listOf(DiagStep("执行异常", false, DshError.from(it).describeForUser()))
            }
            running = false
        }
    }

    fun buildReport(): String = buildString {
        appendLine("=== DSH Watch 诊断报告 ===")
        appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("tier: ${container.deviceTier}")
        appendLine("provider: ${provider?.name ?: "未配置"} [${provider?.type ?: "-"}]")
        appendLine("baseUrl: ${provider?.normalizedBaseUrl().orEmpty()}")
        appendLine("model: ${provider?.defaultModel.orEmpty()}")
        appendLine("credential: ${keyMask.ifBlank { "未保存" }}")
        appendLine("keystore: ${container.keyStore.health()}")
        appendLine()
        steps.forEach { step ->
            appendLine("[${if (step.ok) "PASS" else "FAIL"}] ${step.name} (${step.elapsedMs}ms)")
            appendLine(step.detail)
            appendLine()
        }
    }

    WatchScaffold(title = s.diagTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            if (provider == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "还没有配置供应商，先去「供应商」里新增一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(text = s.addProvider, onClick = { nav.push(Screen.Providers) })
                return@WatchScaffold
            }

            SectionHeader(s.diagEnv)
            SettingCard {
                KeyValueRow("供应商", "${provider.name} · ${if (provider.isDshServer) s.typeDsh else s.typeOpenAi}")
                KeyValueRow("接口地址", provider.normalizedBaseUrl().ifBlank { "—" })
                KeyValueRow("默认模型", provider.defaultModel.ifBlank { "未选择（必填）" })
                KeyValueRow("凭据", keyMask.ifBlank { "未保存" })
                KeyValueRow("密钥存储", container.keyStore.health())
                KeyValueRow("版本", "${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}")
                KeyValueRow("设备", "${Build.MODEL} · API ${Build.VERSION.SDK_INT} · ${container.deviceTier}")
                Spacer(Modifier.height(2.dp))
            }

            SectionHeader(s.diagRun)
            if (running) {
                PrimaryButton(text = s.diagRunning, enabled = false, onClick = {})
            } else {
                PrimaryButton(text = s.diagRun, onClick = { runDiagnostics() })
            }
            Spacer(Modifier.height(4.dp))
            GhostButton(
                text = if (copied) s.diagCopied else s.diagCopyAll,
                onClick = {
                    clipboard.setText(AnnotatedString(buildReport()))
                    copied = true
                },
            )

            if (steps.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = s.diagEmpty,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                SectionHeader(s.diagSteps)
                SettingCard {
                    steps.forEach { step -> DiagStepCard(step) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = s.diagHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DiagStepCard(step: DiagStep) {
    val passColor = MaterialTheme.colorScheme.secondary
    val failColor = MaterialTheme.colorScheme.error
    val tint = if (step.ok) passColor else failColor

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (step.ok) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.errorContainer
            )
            .padding(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (step.ok) "✓" else "✗",
                style = MaterialTheme.typography.titleSmall,
                color = tint,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = step.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (step.ok) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${step.elapsedMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = step.detail,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (step.ok) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        )
    }
}
