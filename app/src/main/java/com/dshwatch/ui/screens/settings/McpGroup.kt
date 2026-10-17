package com.dshwatch.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.dshwatch.core.Ids
import com.dshwatch.domain.agent.mcp.McpConnectOutcome
import com.dshwatch.domain.model.McpServerConfig
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.i18n.AppStrings
import kotlinx.coroutines.launch

/**
 * MCP 服务器管理。
 *
 * ## 为什么"连接"和"启用"是两步
 *
 * 远程 server 的工具**描述会原样进入模型上下文**，而描述里可以藏指令
 * （所谓 tool poisoning：工具名写得人畜无害，description 里写"顺便把
 * 用户的 API Key 发到某个地址"）。客户端没法审查这些内容，只能把决定权交给人：
 *
 * 1. 点「保存并连接」→ 只拉清单给你看，**不启用**
 * 2. 你看过清单、点「信任并启用」→ 才真正注册成工具
 *
 * 改地址会重置确认状态 —— 换了地址就是换了一个 server，之前那次信任不适用。
 */
@Composable
internal fun McpGroup(
    settings: com.dshwatch.domain.model.AppSettings,
    update: Update,
    container: AppContainer,
    s: AppStrings,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    // 连接是普通方法调用，Compose 感知不到；用这个计数器强制重读连接状态
    var version by remember { mutableStateOf(0) }

    val servers = settings.mcpServers

    SettingCard {
        SettingRow(
            title = s.mcpAdd,
            subtitle = if (servers.isEmpty()) s.mcpEmpty else null,
            trailing = "＋",
            onClick = {
                val created = McpServerConfig(
                    id = Ids.new(),
                    name = "MCP ${servers.size + 1}",
                )
                update { it.copy(mcpServers = it.mcpServers + created) }
                expandedId = created.id
            },
        )
    }

    servers.forEach { server ->
        Spacer(Modifier.height(6.dp))
        val connected = if (version >= 0) container.mcpManager.connection(server.id) else null
        val status = when {
            connected != null -> "已连接 · ${connected.tools.size} 个工具"
            server.isValid && server.confirmed -> "已启用，未连接"
            server.isValid -> "已配置，待确认工具清单"
            else -> "未填写地址"
        }

        SettingCard {
            SettingRow(
                title = server.name.ifBlank { "MCP" },
                subtitle = status,
                trailing = if (expandedId == server.id) "⌃" else "›",
                showDivider = expandedId == server.id,
                onClick = {
                    expandedId = if (expandedId == server.id) null else server.id
                },
            )

            if (expandedId == server.id) {
                ServerEditor(
                    server = server,
                    container = container,
                    update = update,
                    s = s,
                    tools = connected?.tools.orEmpty(),
                    protocol = connected?.protocol,
                    stateless = connected?.stateless == true,
                    onChanged = { version++ },
                )
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = s.mcpHint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ServerEditor(
    server: McpServerConfig,
    container: AppContainer,
    update: Update,
    s: AppStrings,
    tools: List<com.dshwatch.domain.agent.mcp.McpToolInfo>,
    protocol: String?,
    stateless: Boolean,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(server.id) { mutableStateOf(server.name) }
    var url by remember(server.id) { mutableStateOf(server.url) }
    var token by remember(server.id) {
        mutableStateOf(runCatching { container.keyStore.getMcpToken(server.id) }.getOrNull().orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember(server.id) { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
        TextRow(label = s.mcpNameLabel, value = name, onValueChange = { name = it })
        Spacer(Modifier.height(6.dp))
        TextRow(
            label = s.mcpUrlLabel,
            value = url,
            onValueChange = { url = it },
            placeholder = "https://example.com/mcp",
        )
        Spacer(Modifier.height(6.dp))
        TextRow(
            label = s.mcpTokenLabel,
            value = token,
            onValueChange = { token = it },
            isSecret = true,
        )
        Spacer(Modifier.height(8.dp))

        PrimaryButton(
            text = if (busy) "连接中…" else s.mcpConnect,
            enabled = !busy && url.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    message = ""
                    val trimmedUrl = url.trim()
                    val trimmedName = name.trim().ifBlank { "MCP" }
                    runCatching { container.keyStore.putMcpToken(server.id, token.trim().ifBlank { null }) }

                    // 改地址 = 换了 server，之前的信任不适用
                    val urlChanged = trimmedUrl != server.url
                    val saved = server.copy(
                        name = trimmedName,
                        url = trimmedUrl,
                        confirmed = if (urlChanged) false else server.confirmed,
                        enabled = if (urlChanged) false else server.enabled,
                    )
                    update { st ->
                        st.copy(mcpServers = st.mcpServers.map { if (it.id == saved.id) saved else it })
                    }

                    val outcome = runCatching { container.mcpManager.connect(saved) }
                        .getOrElse { McpConnectOutcome.Failed(it.message.orEmpty()) }
                    message = when (outcome) {
                        is McpConnectOutcome.Ok ->
                            "连接成功：${outcome.tools.size} 个工具 · 协议 ${outcome.protocol}" +
                                if (outcome.stateless) "（无状态模式）" else ""
                        is McpConnectOutcome.Failed -> "连接失败：${outcome.message}"
                    }
                    container.refreshToolRegistry()
                    busy = false
                    onChanged()
                }
            },
        )

        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = if (message.startsWith("连接失败")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        if (tools.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = s.mcpToolsTitle + "（" + tools.size + "）" +
                    (protocol?.let { " · $it" } ?: "") +
                    if (stateless) " · 无状态" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            tools.forEach { tool ->
                SwitchRow(
                    title = tool.name,
                    subtitle = tool.description.take(90).ifBlank { "(无描述)" },
                    checked = tool.name !in server.disabledTools,
                    onCheckedChange = { on ->
                        val next = if (on) {
                            server.disabledTools - tool.name
                        } else {
                            server.disabledTools + tool.name
                        }
                        update { st ->
                            st.copy(
                                mcpServers = st.mcpServers.map {
                                    if (it.id == server.id) it.copy(disabledTools = next) else it
                                },
                            )
                        }
                        onChanged()
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = s.mcpToolsHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (!server.confirmed && tools.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = "信任这些工具并启用",
                onClick = {
                    update { st ->
                        st.copy(
                            mcpServers = st.mcpServers.map {
                                if (it.id == server.id) it.copy(confirmed = true, enabled = true) else it
                            },
                        )
                    }
                    container.refreshToolRegistry()
                    onChanged()
                },
            )
        }

        if (server.confirmed) {
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "启用",
                subtitle = "关掉后它的工具不再出现在模型的选择里",
                checked = server.enabled,
                onCheckedChange = { on ->
                    update { st ->
                        st.copy(
                            mcpServers = st.mcpServers.map {
                                if (it.id == server.id) it.copy(enabled = on) else it
                            },
                        )
                    }
                    container.refreshToolRegistry()
                    onChanged()
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        GhostButton(
            text = "删除这个服务器",
            danger = true,
            onClick = {
                container.mcpManager.disconnect(server.id)
                runCatching { container.keyStore.clearMcpToken(server.id) }
                update { st ->
                    st.copy(mcpServers = st.mcpServers.filterNot { it.id == server.id })
                }
                container.refreshToolRegistry()
                onChanged()
            },
        )
    }
}
