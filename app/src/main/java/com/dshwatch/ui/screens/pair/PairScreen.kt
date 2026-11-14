package com.dshwatch.ui.screens.pair

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 连接电脑上的 DSH。
 *
 * 设置页从这里的入口进来时，编辑的是「当前那台 DSH 服务器供应商」；
 * 新建供应商时同样的表单也能在编辑页里用到（[DshConnectSection] 是共用的）。
 *
 * ## 这里原来不叫这个名字
 *
 * 旧版是「填地址 + 填六位配对码」，而那套接口在 `dsh web` 上不存在，
 * 所以无论怎么填都连不上。现在改成 SSH：手表连上电脑，在那边起 `dsh web`，
 * 再把端口转发回来。用户只需记住主机、用户名、密码。
 */
@Composable
fun PairScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()

    val provider = remember(settings.providers) {
        settings.providers.firstOrNull { it.type == ProviderType.DSH_SERVER }
    }

    if (provider == null) {
        WatchScaffold(title = "连接电脑", onBack = { nav.pop() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .wearVerticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "还没有「DSH 服务器」类型的供应商。先新建一个，再回到这里连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    text = "新建供应商",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { nav.push(Screen.ProviderEdit(null)) },
                )
            }
        }
        return
    }

    var draft by remember(provider.id) { mutableStateOf(provider) }
    var password by remember(provider.id) {
        mutableStateOf(container.keyStore.getSshPassword(provider.id).orEmpty())
    }

    fun save() {
        scope.launch {
            container.settingsRepo.update { st ->
                val list = st.providers.toMutableList()
                val idx = list.indexOfFirst { it.id == draft.id }
                if (idx >= 0) list[idx] = draft else list.add(draft)
                st.copy(providers = list)
            }
            container.keyStore.putSshPassword(draft.id, password.ifBlank { null })
            nav.pop()
        }
    }

    WatchScaffold(
        title = "连接电脑",
        onBack = { nav.pop() },
        bottom = {
            PrimaryButton(text = "保存", onClick = { save() })
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            SectionHeader(draft.name.ifBlank { "DSH 服务器" })
            SettingCard {
                DshConnectSection(
                    container = container,
                    draft = draft,
                    onDraftChange = { draft = it },
                    password = password,
                    onPasswordChange = { password = it },
                )
            }
        }
    }
}
