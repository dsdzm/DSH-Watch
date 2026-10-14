package com.dshwatch

import com.dshwatch.domain.agent.tools.BuiltinTools
import com.dshwatch.ui.i18n.En
import com.dshwatch.ui.i18n.Zh
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 工具名必须「说人话」。
 *
 * 模型用的是 `write_file` 这种 snake_case 英文名，直接摆到界面上太工程化 ——
 * 用户看到的是「创建文件」。
 *
 * 这个测试的价值在于：**以后新增工具时，只要忘了加映射就会红**，
 * 不需要靠人肉记得去补翻译。
 */
class ToolTitleTest {

    @Test
    fun `every builtin tool has a human readable title in both languages`() {
        val tools = BuiltinTools.all()
        assertThat(tools).isNotEmpty()

        tools.forEach { tool ->
            val zh = Zh.toolTitle(tool.name)
            val en = En.toolTitle(tool.name)
            assertThat(zh).isNotEqualTo(tool.name)
            assertThat(en).isNotEqualTo(tool.name)
            assertThat(zh).isNotEmpty()
            assertThat(en).isNotEmpty()
        }
    }

    @Test
    fun `unknown tools fall back to the raw name instead of crashing`() {
        assertThat(Zh.toolTitle("some_future_tool")).isEqualTo("some_future_tool")
    }

    @Test
    fun `titles are short enough for one watch line`() {
        BuiltinTools.all().forEach { tool ->
            // 工具卡片标题只有一行，超过就会被截成「…」
            assertThat(Zh.toolTitle(tool.name).length).isAtMost(8)
        }
    }

    @Test
    fun `titles are unique so the cards stay distinguishable`() {
        val titles = BuiltinTools.all().map { Zh.toolTitle(it.name) }
        assertThat(titles.toSet()).hasSize(titles.size)
    }
}
