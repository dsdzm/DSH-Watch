package com.dshwatch.domain.agent.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * MCP 工具名的生成规则。
 *
 * OpenAI 的 function name 只接受 `[a-zA-Z0-9_-]` 且最长 64 字符。
 * 违反时服务端返回的是笼统的 400，**不会告诉你"是某个工具名不合法"** ——
 * 表现就是"接上 MCP 之后整轮都报错"，而问题出在一台 server 的某个工具上。
 */
class McpToolAdapterTest {

    @Test
    fun `name is prefixed with server slug`() {
        assertThat(McpToolAdapter.buildToolName("GitHub", "search_repos"))
            .isEqualTo("mcp_GitHub_search_repos")
    }

    @Test
    fun `illegal characters become underscores`() {
        assertThat(McpToolAdapter.buildToolName("My Server!", "search/repos"))
            .isEqualTo("mcp_My_Server_search_repos")
    }

    @Test
    fun `long names are truncated to the api limit`() {
        val name = McpToolAdapter.buildToolName("very-long-server-name", "x".repeat(80))
        assertThat(name.length).isAtMost(64)
        assertThat(name).startsWith("mcp_very-long-server-name_")
    }

    @Test
    fun `blank slug falls back instead of producing a broken name`() {
        // server 名字是中文或全符号时，slug 可能是空的 —— 那样会得到 "mcp__tool"，
        // 虽然合法但很难看，也不好排查
        assertThat(McpToolAdapter.slug("!!!")).isEqualTo("server")
        assertThat(McpToolAdapter.buildToolName("!!!", "t")).isEqualTo("mcp_server_t")
    }

    @Test
    fun `cjk server name is kept`() {
        // 中文是字母，合法的
        assertThat(McpToolAdapter.buildToolName("企业微信", "send"))
            .isEqualTo("mcp_企业微信_send")
    }

    @Test
    fun `adapter exposes the original name for calls`() {
        val info = McpToolInfo(
            name = "search/repos",
            description = "搜索仓库",
            inputSchema = JsonRpc.EMPTY_SCHEMA,
        )
        val adapter = McpToolAdapter(
            client = McpClient("s1", "GitHub", "http://localhost/mcp", null),
            serverSlug = "GitHub",
            info = info,
        )

        // 调用时必须用原名 —— 从带前缀的名字反推是有损的（原名里本来就有斜杠）
        assertThat(adapter.originalName).isEqualTo("search/repos")
        assertThat(adapter.name).isEqualTo("mcp_GitHub_search_repos")
        // 描述里要标出来源：模型据此知道"这个工具会把数据发出去"
        assertThat(adapter.description).contains("GitHub")
    }

    @Test
    fun `mcp tools are treated as write operations`() {
        // 远程工具能干什么客户端判断不了，一律按写操作对待 ——
        // 「只读自动」策略下每次都会要求确认
        val adapter = McpToolAdapter(
            client = McpClient("s1", "X", "http://localhost/mcp", null),
            serverSlug = "X",
            info = McpToolInfo("t", "", JsonRpc.EMPTY_SCHEMA),
        )
        assertThat(adapter.safety).isEqualTo(com.dshwatch.domain.agent.ToolSafety.WRITE)
    }
}
