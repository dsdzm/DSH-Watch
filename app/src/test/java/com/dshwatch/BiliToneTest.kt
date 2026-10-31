package com.dshwatch

import com.dshwatch.domain.agent.tools.BiliTools
import com.dshwatch.domain.bili.BiliTones
import com.dshwatch.domain.bili.TonePreset
import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 评论风格与风格闸门。
 *
 * 这两块最容易在「改文案」时被无声破坏：风格描述写坏了模型不会报错，
 * 只会照着写得更像营销号；闸门写松了也不会报错，只会把套话真的发出去。
 * 所以这里把**可验证的约束**钉住。
 */
class BiliToneTest {

    @Test
    fun `default settings use the freeloading fish tone`() {
        val tone = BiliTones.resolve(AppSettings())
        assertThat(tone.preset).isEqualTo(TonePreset.FISH)
        assertThat(tone.name).isEqualTo("吃白饭的摸鱼大肥鱼")
        // 提示词必须真的说了「摸鱼 / 吃白饭」，否则默认人设只是换了个名字
        assertThat(tone.prompt).contains("摸鱼")
        assertThat(tone.prompt).contains("吃白饭")
    }

    @Test
    fun `custom tone without text falls back to the default instead of an empty prompt`() {
        val tone = BiliTones.resolve(AppSettings(biliTone = "custom", biliToneCustom = "   "))
        assertThat(tone.prompt).isNotEmpty()
        assertThat(tone.preset).isEqualTo(TonePreset.FISH)
    }

    @Test
    fun `custom tone text is passed through verbatim`() {
        val text = "像一个刚下班的运维，先说结论再吐槽一句"
        val tone = BiliTones.resolve(AppSettings(biliTone = "custom", biliToneCustom = text))
        assertThat(tone.prompt).isEqualTo(text)
        assertThat(tone.preset).isEqualTo(TonePreset.CUSTOM)
    }

    @Test
    fun `unknown preset id does not crash and resolves to the default`() {
        val tone = BiliTones.resolve(AppSettings(biliTone = "some_future_tone"))
        assertThat(tone.preset).isEqualTo(TonePreset.FISH)
    }

    @Test
    fun `style gate blocks marketing boilerplate but allows self deprecation`() {
        val fish = BiliTones.resolve(AppSettings())

        // 鱼味自嘲：必须放过，不然默认风格发不出话
        assertThat(BiliTools.StyleGate.check("摸鱼看完的，前半段讲得还行，后面有点水", fish)).isNull()

        // 营销号套话：拦
        assertThat(BiliTools.StyleGate.check("这期真的干货满满，建议大家一键三连", fish)).isNotNull()
        // AI 腔：任何风格都拦
        assertThat(BiliTools.StyleGate.check("作为AI我无法评价这个视频", fish)).isNotNull()
        // 链接
        assertThat(BiliTools.StyleGate.check("看这个 https://example.com 更详细", fish)).isNotNull()
        // 太短
        assertThat(BiliTools.StyleGate.check("好", fish)).isNotNull()
    }

    @Test
    fun `custom tone only enforces the hard rules`() {
        val custom = BiliTones.resolve(
            AppSettings(biliTone = "custom", biliToneCustom = "用一个老工程师的口吻，简短、直接")
        )
        // 用户自己的口味里可能就说「家人们」，不该被别人的黑名单拦
        assertThat(BiliTools.StyleGate.check("家人们，这个方案我试过，确实能跑通", custom)).isNull()
        // 但 AI 腔与链接是硬规则，任何风格都不放
        assertThat(BiliTools.StyleGate.check("作为 AI 助手我建议你多看几遍", custom)).isNotNull()
        assertThat(BiliTools.StyleGate.check("细节在这里 https://a.b 自己看", custom)).isNotNull()
    }
}
