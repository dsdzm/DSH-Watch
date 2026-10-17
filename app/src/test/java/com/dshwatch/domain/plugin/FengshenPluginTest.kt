package com.dshwatch.domain.plugin

import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 风神首轮路由。
 *
 * 判定用关键词打分，所以测试的重点是**两类典型表达各自落在哪一边** ——
 * 判错的代价不是错误，而是"模型一开始就走错方向"：
 * 判成 SPEC 会在一堆"先确认"里空转，判成 REACT 则会直接动手改一个还没看懂的东西。
 */
class FengshenPluginTest {

    private fun ctx(message: String, step: Int = 1) = TurnContext(
        step = step,
        firstUserMessage = message,
        settings = AppSettings(),
    )

    @Test
    fun `bug report is classified as spec`() {
        val samples = listOf(
            "应用一打开就崩溃，日志里是 NullPointerException",
            "为什么这个函数返回 null？帮我看看",
            "构建报错了：Unresolved reference 'foo'",
            "同步功能不工作，点了没反应",
            "fix the crash when opening settings",
            "there is a regression in the login flow, traceback attached",
        )
        samples.forEach { message ->
            assertThat(FengshenPlugin.classify(message))
                .isEqualTo(FengshenPlugin.TaskKind.SPEC)
        }
    }

    @Test
    fun `feature request is classified as react`() {
        val samples = listOf(
            "帮我新增一个导出功能",
            "实现一个本地缓存，支持离线读取",
            "做一个设置页，把选项分类",
            "implement dark mode support",
            "create a new screen for token usage",
        )
        samples.forEach { message ->
            assertThat(FengshenPlugin.classify(message))
                .isEqualTo(FengshenPlugin.TaskKind.REACT)
        }
    }

    @Test
    fun `ambiguous message defaults to react`() {
        // "建东西"是更常见的诉求：误判成 REACT 只是更积极动手，
        // 误判成 SPEC 会让它在"先确认再确认"里空转
        assertThat(FengshenPlugin.classify("帮我看看这个"))
            .isEqualTo(FengshenPlugin.TaskKind.REACT)
        assertThat(FengshenPlugin.classify(""))
            .isEqualTo(FengshenPlugin.TaskKind.REACT)
    }

    @Test
    fun `first turn narrows the tool set`() {
        val overlay = FengshenPlugin.overlay(ctx("修复登录崩溃"))

        assertThat(overlay).isNotNull()
        assertThat(overlay!!.allowedTools).containsExactly(
            "read_file", "write_file", "edit_file", "run_script",
        )
        assertThat(overlay.promptSuffix).contains("修复")
    }

    @Test
    fun `first turn persona differs by task kind`() {
        val spec = FengshenPlugin.overlay(ctx("修复崩溃"))!!.promptSuffix
        val react = FengshenPlugin.overlay(ctx("新增导出功能"))!!.promptSuffix

        assertThat(spec).contains("先定位")
        assertThat(react).contains("直接动手")
        assertThat(spec).isNotEqualTo(react)
    }

    @Test
    fun `does nothing from the second step on`() {
        // 收窄只能发生在首轮。真实任务里第 2 步经常就要用 grep 或 build_project，
        // 一直收窄会让模型"想用的工具不在清单里"，转而用 run_script 绕 —— 更容易出错
        assertThat(FengshenPlugin.overlay(ctx("修复崩溃", step = 2))).isNull()
        assertThat(FengshenPlugin.overlay(ctx("新增功能", step = 5))).isNull()
    }

    @Test
    fun `narrowed tools are all real tool names`() {
        // 名单里写错一个名字的后果：那一轮模型看不到它，而报错不会出现 ——
        // 只会表现为"模型突然不知道要读文件"
        val known = com.dshwatch.domain.agent.tools.BuiltinTools.all().map { it.name }.toSet()
        assertThat(known).containsAtLeastElementsIn(FengshenPlugin.FIRST_TURN_TOOLS)
    }
}
