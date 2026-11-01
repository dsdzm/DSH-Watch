package com.dshwatch

import com.dshwatch.domain.agent.ApprovalPolicy
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.tools.BiliTools
import com.dshwatch.domain.bili.BiliClock
import com.dshwatch.domain.bili.BiliMode
import com.dshwatch.domain.bili.BiliModes
import com.dshwatch.domain.bili.BiliTone
import com.dshwatch.domain.bili.BiliTones
import com.dshwatch.domain.bili.TonePreset
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.util.Calendar

/**
 * 1.10 加的四件事都踩在「默认值」上，所以每一条都钉一下：
 * 模式迁移（老存档不能突然失效）、定时时钟（不能被时区/当天已过坑到）、
 * 自动发送（不能顺手把云端编译的确认也放开）、调皮模式（不能变成骂人）。
 */
class BiliModePlanTest {

    // ---------------- 模式迁移 ----------------

    @Test
    fun `old archive with comment enabled migrates to comment mode`() {
        val old = AppSettings(biliMode = "", biliCommentEnabled = true)
        assertThat(BiliModes.of(old)).isEqualTo(BiliMode.COMMENT)
    }

    @Test
    fun `old archive with comment disabled migrates to summary`() {
        val old = AppSettings(biliMode = "", biliCommentEnabled = false)
        assertThat(BiliModes.of(old)).isEqualTo(BiliMode.SUMMARY)
    }

    @Test
    fun `explicit mode wins over the legacy flag`() {
        // 用户在新版里选了「只总结」，但旧布尔还是 true —— 必须以新字段为准，
        // 否则关掉评论后一重启又自己开了
        val s = AppSettings(biliMode = "summary", biliCommentEnabled = true)
        assertThat(BiliModes.of(s)).isEqualTo(BiliMode.SUMMARY)
        assertThat(BiliModes.allowsComment(s)).isFalse()
    }

    @Test
    fun `dm mode only allows dm`() {
        val s = AppSettings(biliMode = "dm")
        assertThat(BiliModes.allowsComment(s)).isFalse()
        assertThat(BiliModes.allowsDm(s)).isTrue()
    }

    @Test
    fun `unknown mode id falls back instead of crashing`() {
        val s = AppSettings(biliMode = "whatever", biliCommentEnabled = false)
        assertThat(BiliModes.of(s)).isEqualTo(BiliMode.SUMMARY)
    }

    // ---------------- 定时时钟 ----------------

    @Test
    fun `next trigger is today when the time has not passed`() {
        val now = at(2026, 10, 30, 1, 0)
        val next = BiliClock.nextTrigger(now, 3, 30)
        assertThat(next).isEqualTo(at(2026, 10, 30, 3, 30))
    }

    @Test
    fun `next trigger rolls to tomorrow when the time already passed`() {
        val now = at(2026, 10, 30, 5, 0)
        val next = BiliClock.nextTrigger(now, 3, 30)
        assertThat(next).isEqualTo(at(2026, 10, 31, 3, 30))
    }

    @Test
    fun `next trigger rolls over month end`() {
        val now = at(2026, 10, 31, 23, 50)
        val next = BiliClock.nextTrigger(now, 3, 30)
        assertThat(next).isEqualTo(at(2026, 11, 1, 3, 30))
    }

    @Test
    fun `clock text is zero padded`() {
        assertThat(BiliClock.hhmm(3, 5)).isEqualTo("03:05")
        assertThat(BiliClock.hhmm(23, 45)).isEqualTo("23:45")
    }

    // ---------------- 审批策略 ----------------

    @Test
    fun `bilibili posting never asks for confirmation`() {
        // 逐次确认模式下也不例外：用户明确要求发评论不要弹窗，
        // 护栏改成模式 + 配额 + 判重 + 风格闸门
        val s = AppSettings(agentMode = AgentMode.ASK_EVERY_STEP)
        listOf("bili_reply", "bili_dm", "bili_dyn", "bili_like").forEach { name ->
            assertThat(
                ApprovalPolicy.needsApproval(s, name, ToolSafety.NETWORK, JsonObject(emptyMap()), emptySet())
            ).isFalse()
        }
    }

    // ---------------- 发动态 ----------------

    @Test
    fun `dynamic publishing is off by default`() {
        val s = AppSettings()
        assertThat(s.biliDynEnabled).isFalse()
        assertThat(s.biliDynQuota).isEqualTo(1)
        assertThat(BiliTools.DynTool.enabled(s)).isFalse()
    }

    @Test
    fun `dynamic publishing needs both the master switch and its own toggle`() {
        // 总开关关掉时，单独开动态也不该生效 —— 否则「关了刷B站」还能发动态
        assertThat(
            BiliTools.DynTool.enabled(AppSettings(biliEnabled = false, biliDynEnabled = true))
        ).isFalse()
        assertThat(
            BiliTools.DynTool.enabled(AppSettings(biliEnabled = true, biliDynEnabled = true))
        ).isTrue()
    }

    @Test
    fun `dynamic publishing is independent of the three way mode`() {
        // 「只总结」的用户同样可能想让鱼偶尔发一条，所以动态不挂在模式上。
        // 这也是当初没把 dynamic 塞进 BiliMode 的原因。
        val s = AppSettings(biliEnabled = true, biliDynEnabled = true, biliMode = "summary")
        assertThat(BiliModes.of(s)).isEqualTo(BiliMode.SUMMARY)
        assertThat(BiliTools.DynTool.enabled(s)).isTrue()
    }

    @Test
    fun `dynamic tool is registered and network bound`() {
        assertThat(BiliTools.all().map { it.name }).contains("bili_dyn")
        assertThat(BiliTools.all().map { it.name }).contains("bili_like")
        assertThat(BiliTools.DynTool.safety).isEqualTo(ToolSafety.NETWORK)
    }

    @Test
    fun `cloud build still asks every time`() {
        val s = AppSettings(agentMode = AgentMode.AUTO_ALL)
        assertThat(
            ApprovalPolicy.needsApproval(
                s, "cloud_build", ToolSafety.NETWORK, JsonObject(emptyMap()), emptySet()
            )
        ).isTrue()
    }

    // ---------------- 调皮模式 ----------------

    @Test
    fun `naughty tone is selectable and keeps its guardrails`() {
        val tone = BiliTones.resolve(AppSettings(biliTone = "naughty"))
        assertThat(tone.preset).isEqualTo(TonePreset.NAUGHTY)
        assertThat(tone.prompt).contains("倒反天罡")
        // 抽象归抽象，骂人不行
        assertThat(tone.banned).contains("傻逼")
        // AI 腔是全局底座，任何风格都要拦
        assertThat(tone.banned.any { it.contains("AI") }).isTrue()
    }

    @Test
    fun `style gate blocks a slur even in naughty mode`() {
        val tone: BiliTone = BiliTones.resolve(AppSettings(biliTone = "naughty"))
        val blocked = BiliTools.StyleGate.check("这视频拍得跟傻逼一样", tone)
        assertThat(blocked).isNotNull()
    }

    @Test
    fun `style gate lets an absurd but harmless line through`() {
        val tone = BiliTones.resolve(AppSettings(biliTone = "naughty"))
        val ok = BiliTools.StyleGate.check("我宣布今天罢工，谁爱看谁看，鱼不伺候了", tone)
        assertThat(ok).isNull()
    }

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis
}
