package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.roleplay.Scenes
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * `schedule_proactive` 的可见范围。
 *
 * ## 这条测试是为什么写的
 *
 * beta.39 上线后用户反馈「AI 好像没法自己决定什么时候主动发消息」。
 * 原因不是模型不会调，而是这个工具**根本不在请求里**：
 * 它当时用 `enabled = { Scenes.of(settings.useScene) == FISH }` 判断场景，
 * 而 `useScene` 是全局模式 —— 从记录页打开一条养鱼会话时，它可能还停在编程，
 * 于是工具被静默过滤掉了。
 *
 * 现在场景由会话决定（`onlyInScene`），`enabled` 只管"这个能力开没开"。
 * 所以这里钉两件事：它不依赖 `useScene`；它的场景是养鱼。
 */
class FishScheduleToolTest {

    private val tool = BuiltinTools.ScheduleProactiveTool

    @Test
    fun `stays available no matter which scene the app is in`() {
        Scenes.all.forEach { scene ->
            val settings = AppSettings(useScene = scene)
            assertThat(tool.enabled(settings)).isTrue()
        }
    }

    @Test
    fun `is marked as fish only so coding never sees it`() {
        assertThat(tool.onlyInScene).isEqualTo(Scenes.FISH)
    }

    @Test
    fun `schema asks for a note and accepts either relative or absolute time`() {
        val props = tool.parameters["properties"]!!.jsonObject
        assertThat(props.keys).containsAtLeast("after_minutes", "at", "note")
        assertThat(tool.parameters["required"].toString()).contains("note")
    }
}
