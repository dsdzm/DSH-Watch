package com.dshwatch.ui.screens.pair

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.BuildConfig
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.net.lan.PairingPortal
import com.dshwatch.net.remote.RemotePairing
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 用手表开的小网站完成 DSH Remote 登录。
 *
 * 手表屏幕上只有两样东西：一个二维码（手机扫它打开页面）和一句话状态。
 * 输地址、点登录、扫授权码全部发生在手机上 —— 手表负责「提供服务」和「等待」。
 *
 * 这条页面成立的前提是手机与手表在同一个 Wi-Fi。不在同一个网也能看，
 * 但扫出来的地址打不开；所以连接失败时的第一句提示就是查这个。
 */
@Composable
fun RemotePairScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()

    val provider = remember(settings.providers) {
        settings.providers.firstOrNull { it.type == ProviderType.DSH_SERVER }
    }

    val pairing = remember {
        RemotePairing(
            context = container.appContext,
            keyStore = container.keyStore,
            scope = scope,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            clientVersion = BuildConfig.VERSION_NAME,
            json = container.json,
        )
    }

    val portalState by pairing.state.collectAsStateWithLifecycle()
    val link by pairing.link.collectAsStateWithLifecycle()
    var savedHosts by remember { mutableStateOf(0) }

    DisposableEffect(pairing) {
        pairing.onPaired = { result ->
            scope.launch {
                container.settingsRepo.update { st ->
                    val list = st.providers.toMutableList()
                    val idx = list.indexOfFirst { it.type == ProviderType.DSH_SERVER }
                    val base = if (idx >= 0) {
                        list[idx]
                    } else {
                        Provider(
                            id = "dsh-remote",
                            name = "DSH Remote",
                            type = ProviderType.DSH_SERVER,
                        )
                    }
                    val first = result.hosts.firstOrNull()
                    val updated = base.copy(
                        remoteServer = result.server,
                        remoteDeviceId = result.deviceId,
                        remoteHostId = first?.deviceId ?: base.remoteHostId,
                    )
                    if (idx >= 0) list[idx] = updated else list.add(updated)
                    st.copy(providers = list)
                }
                savedHosts = result.hosts.size
            }
        }
        pairing.open(provider)
        onDispose { pairing.close() }
    }

    WatchScaffold(
        title = "手机配对",
        onBack = { nav.pop() },
        bottom = {
            if (portalState.phase == PairingPortal.Snapshot.Phase.DONE) {
                PrimaryButton(text = "完成", onClick = { nav.pop() })
            } else {
                GhostButton(text = "取消", onClick = { nav.pop() })
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val url = link
            if (url != null) {
                // 手表屏幕装不下整行地址，二维码才是主要通道，文字只作兜底
                QrCode(content = url, size = 176.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = url.substringAfter("//").substringBefore("?k="),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            } else {
                Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "没有 Wi-Fi 地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = portalState.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (portalState.detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = portalState.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                )
            }

            if (portalState.phase == PairingPortal.Snapshot.Phase.DONE) {
                Spacer(Modifier.height(6.dp))
                val hosts = portalState.hosts
                if (hosts.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        hosts.take(3).forEach { (_, name) ->
                            Text(
                                text = "· $name",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (savedHosts > 3) {
                            Text(
                                text = "还有 ${savedHosts - 3} 台",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (portalState.phase == PairingPortal.Snapshot.Phase.FAILED) {
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    text = "重试",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        pairing.close()
                        pairing.open(provider)
                    },
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "手机和手表要在同一个 Wi-Fi",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 画二维码。
 *
 * 白底黑块是硬要求：深色主题下直接把模块画成浅色，很多相机解不出来。
 * 留 2 格静区（quiet zone）—— 少了这一圈，边缘模块会贴着屏幕黑边，同样扫不动。
 */
@Composable
private fun QrCode(content: String, size: Dp) {
    val matrix = remember(content) { RemotePairing.matrix(content) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix.isEmpty()) return@Box
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val n = matrix.size
            if (n == 0) return@Canvas
            val cell = this.size.minDimension / n
            for (y in 0 until n) {
                val row = matrix[y]
                for (x in 0 until n) {
                    if (row[x] == '1') {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }
    }
}
