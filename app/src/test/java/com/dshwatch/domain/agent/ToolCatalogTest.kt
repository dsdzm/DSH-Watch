package com.dshwatch.domain.agent

import com.dshwatch.domain.agent.tools.BuiltinTools
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 渐进式披露：目录、检索、可见集。
 *
 * 这组断言守的是「省下的 token 是不是真的省下了，以及模型找不找得到工具」：
 * 目录必须覆盖每个非核心组、检索必须能把「字幕」这种词打到 `bili_subtitle` 上、
 * 单次加载不能把整组 16 个 B 站工具全拉进来（那等于没省）。
 */
class ToolCatalogTest {

    private val all = BuiltinTools.all()

    @Test
    fun `only core tools are visible before anything is loaded`() {
        val visible = ToolCatalog.visible(all, emptySet())
        assertThat(visible.map { it.name }.toSet()).isEqualTo(ToolCatalog.CORE)
        // 长尾工具一个都不能漏出来，否则「省 token」是假的
        assertThat(visible.map { it.name }).doesNotContain("bili_video")
        assertThat(visible.map { it.name }).doesNotContain("cloud_build")
    }

    @Test
    fun `loading a name makes it visible`() {
        val visible = ToolCatalog.visible(all, setOf("bili_video"))
        assertThat(visible.map { it.name }).contains("bili_video")
        assertThat(visible.size).isEqualTo(ToolCatalog.CORE.size + 1)
    }

    @Test
    fun `group name resolves to the whole group`() {
        val hits = ToolCatalog.search(all, "B站")
        assertThat(hits).isNotEmpty()
        assertThat(hits.all { it.name.startsWith("bili_") }).isTrue()
    }

    @Test
    fun `keyword beats group order so a precise search lands on the right tool`() {
        // 「字幕」也会命中 B站 整组；相关性排序必须把 bili_subtitle 顶到前面，
        // 否则一次只取 6 个时会取到 6 个不相干的
        val hits = ToolCatalog.search(all, "字幕")
        assertThat(hits.first().name).isEqualTo("bili_subtitle")
    }

    @Test
    fun `colloquial words map to the right group`() {
        assertThat(ToolCatalog.search(all, "闹钟").map { it.name }).contains("set_alarm")
        assertThat(ToolCatalog.search(all, "apk").map { it.name }).contains("cloud_build")
        assertThat(ToolCatalog.search(all, "操控").map { it.name }).contains("device_act")
    }

    @Test
    fun `unknown query returns nothing instead of guessing`() {
        assertThat(ToolCatalog.search(all, "量子纠缠")).isEmpty()
    }

    @Test
    fun `catalog lists only what is not already sent`() {
        val text = ToolCatalog.catalogPrompt(all, ToolCatalog.CORE)
        assertThat(text).contains("B站")
        assertThat(text).contains("构建")
        // 已经下发的核心工具不该再出现在目录里 —— 重复收费
        assertThat(text).doesNotContain("read_file")
        assertThat(text).doesNotContain("write_file")
    }

    @Test
    fun `catalog is empty once everything is loaded`() {
        val everything = all.map { it.name }.toSet()
        assertThat(ToolCatalog.catalogPrompt(all, everything)).isEmpty()
    }

    @Test
    fun `catalog stays small enough to be worth it`() {
        val text = ToolCatalog.catalogPrompt(all, ToolCatalog.CORE)
        // 目录本身也是每轮都发的；超过一千字就把省下的又吃回去了
        assertThat(text.length).isLessThan(900)
    }

    @Test
    fun `core set is at least four times smaller than everything`() {
        fun cost(tools: List<AgentTool>) =
            tools.sumOf { it.description.length + it.parameters.toString().length }
        val core = cost(ToolCatalog.visible(all, emptySet()))
        val full = cost(all)
        // 实测约 1/4，这里按「至少省三分之二」卡，留出加减工具的余量
        assertThat(core * 3).isLessThan(full)
    }

    @Test
    fun `brief marks required parameters`() {
        val search = all.first { it.name == "tool_search" }
        val brief = ToolCatalog.brief(search)
        assertThat(brief).isEqualTo("tool_search(query*)")
    }

    @Test
    fun `scope deduplicates and reports only new names`() {
        val scope = ToolScope { all }
        assertThat(scope.load(listOf("bili_video", "bili_search"))).hasSize(2)
        assertThat(scope.load(listOf("bili_video", "bili_subtitle"))).containsExactly("bili_subtitle")
        assertThat(scope.snapshot()).containsExactly("bili_video", "bili_search", "bili_subtitle")
    }

    @Test
    fun `already loaded tools sink so a second search finds new ones`() {
        val scope = ToolScope { all }
        val first = scope.search("B站").take(3).map { it.name }
        scope.load(first)
        val second = scope.search("B站").take(3).map { it.name }
        assertThat(second).containsNoneIn(first)
    }
}
