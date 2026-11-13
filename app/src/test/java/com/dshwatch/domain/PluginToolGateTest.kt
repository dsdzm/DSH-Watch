package com.dshwatch.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 插件白名单落到「真实工具表」上的那一步。
 *
 * [ChatEngine.blockedTools] 是插件与工具注册表唯一的交汇点：
 * 插件只认识字符串，注册表才知道这些字符串背后有没有工具。
 * 两边的偏差全在这里收口，所以单独测。
 */
class PluginToolGateTest {

    private val known = listOf("read_file", "write_file", "run_script", "web_search")

    @Test
    fun `without a whitelist only the denied tools are blocked`() {
        val (blocked, ignored) = ChatEngine.blockedTools(
            denied = setOf("web_search"),
            only = null,
            known = known,
        )
        assertThat(blocked).containsExactly("web_search")
        assertThat(ignored).isFalse()
    }

    @Test
    fun `a whitelist blocks everything outside it`() {
        val (blocked, ignored) = ChatEngine.blockedTools(
            denied = emptySet(),
            only = setOf("read_file", "run_script"),
            known = known,
        )
        assertThat(blocked).containsExactly("write_file", "web_search")
        assertThat(ignored).isFalse()
    }

    /**
     * 白名单里一个都对不上时按「没写」处理。
     *
     * 照字面执行会把**所有**工具拦掉：模型突然什么都不会做，
     * 而用户完全看不出是插件里少打了一个字母。
     */
    @Test
    fun `a whitelist that matches nothing is ignored instead of blocking everything`() {
        val (blocked, ignored) = ChatEngine.blockedTools(
            denied = emptySet(),
            only = setOf("webs_search", "reed_file"),
            known = known,
        )
        assertThat(blocked).isEmpty()
        assertThat(ignored).isTrue()
    }

    /** 部分命中就按命中处理 —— 用户只是多写了一两个不存在的名字，不该整体作废 */
    @Test
    fun `a partially matching whitelist still applies`() {
        val (blocked, ignored) = ChatEngine.blockedTools(
            denied = emptySet(),
            only = setOf("read_file", "webs_search"),
            known = known,
        )
        assertThat(blocked).containsExactly("write_file", "run_script", "web_search")
        assertThat(ignored).isFalse()
    }

    @Test
    fun `denied and whitelist stack instead of cancelling out`() {
        val (blocked, _) = ChatEngine.blockedTools(
            denied = setOf("read_file"),
            only = setOf("read_file", "write_file"),
            known = known,
        )
        // 白名单留下 read_file / write_file，但 read_file 又被禁用 —— 禁用优先
        assertThat(blocked).containsExactly("read_file", "run_script", "web_search")
    }

    /** 注册表为空（比如首次启动还没装工具）时，白名单没有可参照的东西 */
    @Test
    fun `an empty registry makes the whitelist a no-op`() {
        val (blocked, ignored) = ChatEngine.blockedTools(
            denied = setOf("web_search"),
            only = setOf("read_file"),
            known = emptyList(),
        )
        assertThat(blocked).containsExactly("web_search")
        assertThat(ignored).isTrue()
    }
}
