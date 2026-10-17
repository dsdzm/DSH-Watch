package com.dshwatch.domain.model

import kotlinx.serialization.Serializable

/**
 * 一条远程 MCP server 的配置。
 *
 * 全字段带默认值 —— 项目约定：设置类是整份 JSON 存 DataStore，
 * 加字段不需要写迁移（老 JSON 缺字段时用默认值补上）。
 *
 * [confirmed] 是这里最要紧的一个字段，见 [McpServerConfig] 的类注释说明。
 */
@Serializable
data class McpServerConfig(
    val id: String = "",
    val name: String = "",
    /** MCP endpoint 完整地址，例如 https://api.githubcopilot.com/mcp/ */
    val url: String = "",
    val enabled: Boolean = false,
    /**
     * 用户逐个禁用的工具（存 MCP 里的**原名**，不是加前缀后的名字）。
     * 存原名是因为前缀会随 server 重命名变化，而禁用意图不该因此丢失。
     */
    val disabledTools: Set<String> = emptySet(),
    /**
     * 用户是否已经看过这个 server 的工具清单并确认。
     *
     * 为什么需要它：远程 server 的工具**描述会直接进模型上下文**，
     * 而描述里可以藏指令（tool poisoning）。所以第一次连接时把清单摊开给用户看，
     * 确认后才注册成工具。这是整套 MCP 功能里唯一一次明确的信任决策。
     *
     * 关掉 server 或改地址时会被重置（`McpServerConfig` 没有自动化逻辑，
     * 由设置页在改 url 时手动置 false）。
     */
    val confirmed: Boolean = false,
) {
    val isValid: Boolean get() = url.trim().startsWith("http") && name.isNotBlank()
}
