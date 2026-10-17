package com.dshwatch.domain.agent.mcp

import com.dshwatch.core.Logx
import com.dshwatch.data.settings.SecureKeyStore
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolRegistry
import com.dshwatch.domain.model.McpServerConfig

/**
 * MCP server 的连接池。
 *
 * 只做三件事：连、断、把连接着的工具汇成一个列表交给 [ToolRegistry]。
 * **不做缓存持久化**：工具清单存在磁盘上会让"server 已经换了工具"
 * 和"本地还记得旧工具"之间出现偏差，而手表上的连接代价并不高（一次 HTTPS）。
 */
class McpManager(
    private val baseContext: ToolContext,
    private val keyStore: SecureKeyStore,
) {

    data class Connection(
        val server: McpServerConfig,
        val client: McpClient,
        val tools: List<McpToolInfo>,
        val protocol: String,
        val stateless: Boolean,
    )

    private val connections = mutableMapOf<String, Connection>()

    /**
     * 连接一个 server 并拉取工具清单。
     *
     * 会先断开同 id 的旧连接 —— 用户改了地址之后重新连接，
     * 如果还留着旧 client，工具调用会发到旧地址上。
     */
    suspend fun connect(server: McpServerConfig): McpConnectOutcome {
        connections.remove(server.id)
        val client = McpClient(
            serverId = server.id,
            serverName = server.name.ifBlank { "MCP" },
            endpoint = server.url.trim(),
            token = keyStore.getMcpToken(server.id),
        )
        val outcome = client.connect()
        if (outcome is McpConnectOutcome.Ok) {
            connections[server.id] = Connection(
                server = server,
                client = client,
                tools = outcome.tools,
                protocol = outcome.protocol,
                stateless = outcome.stateless,
            )
        }
        return outcome
    }

    /**
     * 启动时自动连接"已启用且已确认"的 server。
     *
     * 串行而不是并行：手表只有 4 核，且 MCP server 通常有自己的速率限制，
     * 同时连三台只会让三台都变慢。失败静默 —— 用户没在等这个结果，
     * 需要看原因时去设置页手动点「连接」。
     */
    suspend fun autoConnect(servers: List<McpServerConfig>): Int {
        var ok = 0
        for (server in servers) {
            if (!server.enabled || !server.confirmed || !server.isValid) continue
            val result = runCatching { connect(server) }.getOrElse { t ->
                Logx.w(TAG, "自动连接 ${server.name} 失败", t)
                McpConnectOutcome.Failed(t.message.orEmpty())
            }
            if (result is McpConnectOutcome.Ok) ok++
        }
        return ok
    }

    fun disconnect(serverId: String) {
        connections.remove(serverId)
    }

    fun disconnectAll() {
        connections.clear()
    }

    fun connection(serverId: String): Connection? = connections[serverId]

    fun connectedIds(): Set<String> = connections.keys.toSet()

    /** 当前可注册的 MCP 工具（已过滤用户禁用的单项） */
    fun activeTools(servers: List<McpServerConfig>): List<AgentTool> {
        val disabledByServer = servers.associate { it.id to it.disabledTools }
        return connections.values.flatMap { connection ->
            val disabled = disabledByServer[connection.server.id].orEmpty()
            connection.tools
                .filter { it.name !in disabled }
                .map { McpToolAdapter(connection.client, connection.server.name, it) }
        }
    }

    /**
     * 用「内置工具 + 当前 MCP 工具」重建注册表。
     *
     * 每次重建都基于**内置工具的原始列表**，而不是上一次的注册表 ——
     * 否则断开一台 server 之后，它的工具会一直留在表里（越积越多）。
     */
    fun buildRegistry(builtin: List<AgentTool>, servers: List<McpServerConfig>): ToolRegistry =
        ToolRegistry(baseContext, builtin + activeTools(servers))

    companion object {
        private const val TAG = "McpManager"
    }
}
