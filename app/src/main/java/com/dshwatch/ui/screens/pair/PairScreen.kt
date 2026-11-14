package com.dshwatch.ui.screens.pair

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Ids
import com.dshwatch.domain.PromptPresets
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.net.dshweb.DshWeb
import com.dshwatch.net.dshweb.DshWebError
import com.dshwatch.net.dshweb.DshWebHost
import com.dshwatch.net.dshweb.DshWebStep
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 连接电脑上的 `dsh web`。
 *
 * ## 这一页曾经连不上，原因不是网络
 *
 * 旧版这里填的是「服务器地址 + 一次性配对码」，请求打在
 * `POST /api/v1/devices/pair` 上 —— 那个接口是照着「远程访问插件」想象的，
 * `dsh web` 上根本不存在。地址填得再对，得到的也只有 404 或一句解析失败。
 *
 * 现在按 dshgo 的实际做法：电脑上 `dsh web` 一启动会打印
 *
 * ```
 * dsh web: http://192.168.1.20:3080/?token=xxxxxxxx
 * ```
 *
 * 把**这一整行**粘进来，客户端用 token 换一枚会话 cookie，之后所有请求带它。
 *
 * ## 为什么要把「哪一步失败」显示出来
 *
 * 这条链上有四个独立环节：解析地址、换 cookie、服务可达、会话列表。
 * 它们失败的样子都是「连不上」，但处理方式完全不同 —— 地址错要重抄，
 * cookie 失效要去电脑上重启 `dsh web`，网络不通要查 Wi-Fi。
 * 只报一句「配对失败」，用户能做的只有反复试。
 */
@Composable
fun PairScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val provider = remember(settings.providers) {
        settings.providers.firstOrNull { it.type == ProviderType.DSH_SERVER }
    }
    // cookie 头存在「设备凭证」字段里：它是这台手表在这台电脑上的身份，
    // 换电脑就作废，与 API key 不是一回事，但对本地存储的要求完全相同
    val storedCookie = provider?.id?.let { container.keyStore.getServerToken(it) }
    val paired = !storedCookie.isNullOrBlank()

    var address by remember(provider?.id) {
        mutableStateOf(provider?.baseUrl.orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<DshWebStep>>(emptyList()) }

    // 局部 suspend：它要写 settings（异步落盘），只能在协程里调用
    suspend fun remember(newHost: DshWebHost, providerId: String) {
        container.settingsRepo.update { st ->
            val existing = st.providers.firstOrNull { it.id == providerId }
            val base = existing ?: Provider(
                id = providerId,
                name = "DSH 电脑",
                type = ProviderType.DSH_SERVER,
                presetId = PromptPresets.VIBE_CODING,
            )
            val updated = base.copy(
                type = ProviderType.DSH_SERVER,
                baseUrl = newHost.baseUrl,
                deviceName = android.os.Build.MODEL,
                pairedAt = System.currentTimeMillis(),
            )
            val list = st.providers.toMutableList()
            val idx = list.indexOfFirst { it.id == providerId }
            if (idx >= 0) list[idx] = updated else list.add(updated)
            st.copy(
                providers = list,
                activeProviderId = st.activeProviderId.ifBlank { providerId },
            )
        }
        newHost.cookie?.let { container.keyStore.putServerToken(providerId, it.header) }
    }

    fun connect() {
        val text = address.trim()
        if (text.isBlank()) {
            message = "请粘贴电脑上 dsh web 打印的地址"
            isError = true
            return
        }
        val launch = DshWeb.parseLaunchUrl(text)
        if (launch == null) {
            message = "这段文字里没有启动地址。要复制电脑上以 http:// 开头、带 ?token= 的那一整行"
            isError = true
            return
        }
        scope.launch {
            busy = true
            message = null
            steps = emptyList()
            val id = provider?.id ?: Ids.new()
            runCatching { container.dshWeb.connect(launch) }
                .onSuccess { host ->
                    remember(host, id)
                    address = host.baseUrl
                    steps = container.dshWeb.diagnose(host)
                    val bad = steps.firstOrNull { !it.ok }
                    if (bad == null) {
                        message = "已连接 ${host.baseUrl}"
                        isError = false
                    } else {
                        message = "${bad.name}：${bad.detail}"
                        isError = true
                    }
                }
                .onFailure {
                    val e = DshWebError.from(it)
                    message = e.describeForUser()
                    isError = true
                }
            busy = false
        }
    }

    fun recheck() {
        val host = DshWeb.hostFrom(provider?.baseUrl.orEmpty(), storedCookie) ?: return
        scope.launch {
            busy = true
            message = null
            steps = container.dshWeb.diagnose(host)
            val bad = steps.firstOrNull { !it.ok }
            message = bad?.let { "${it.name}：${it.detail}" } ?: "连接正常"
            isError = bad != null
            busy = false
        }
    }

    WatchScaffold(title = s.pairTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "电脑上先运行 dsh web，然后复制它打印的那一行地址（带 ?token=）。" +
                    "手表和电脑要在同一个 Wi-Fi 下。",
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
                SectionHeader("已连接")
                SettingCard {
                    SettingRow(
                        title = provider?.name ?: "DSH 电脑",
                        subtitle = provider?.baseUrl.orEmpty(),
                        trailing = "✓",
                        showDivider = true,
                    )
                    SettingRow(
                        title = "登录",
                        subtitle = "cookie 保存在本机加密存储",
                        showDivider = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    PrimaryButton(
                        text = if (busy) "检查中…" else "检查连接",
                        enabled = !busy,
                        onClick = { recheck() },
                    )
                    Spacer(Modifier.height(4.dp))
                    GhostButton(text = s.unpair, danger = true, onClick = {
                        scope.launch {
                            provider?.let { container.keyStore.clear(it.id) }
                            steps = emptyList()
                            message = "已解除连接"
                            isError = false
                        }
                    })
                    Spacer(Modifier.height(4.dp))
                }
            }

            SectionHeader("启动地址")
            SettingCard {
                TextRow(
                    label = "粘贴地址",
                    value = address,
                    placeholder = "http://192.168.1.20:3080/?token=…",
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { address = it },
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = if (busy) "连接中…" else "连接",
                    enabled = !busy,
                    onClick = { connect() },
                )
                Spacer(Modifier.height(4.dp))
            }

            if (steps.isNotEmpty()) {
                SectionHeader("检查结果")
                SettingCard {
                    steps.forEachIndexed { i, step ->
                        SettingRow(
                            title = "${if (step.ok) "✓" else "✗"} ${step.name}",
                            subtitle = step.detail,
                            trailing = "${step.millis} ms",
                            showDivider = i != steps.lastIndex,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "提示：HTTP 明文只适合可信局域网。跨公网请用 Tailscale 之类的隧道，" +
                    "不要直接把端口暴露在公网上。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
