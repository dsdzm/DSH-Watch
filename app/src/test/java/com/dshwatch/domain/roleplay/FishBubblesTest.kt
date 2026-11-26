package com.dshwatch.domain.roleplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 拆气泡。
 *
 * 这几条都是真机上见过的形态：模型偶尔会用「- 」列点、会加粗几个词、
 * 会把三句话挤成一行。拆不干净的直接后果是聊天框里出现 Markdown 或者
 * 一块占满四行的文字墙。
 */
class FishBubblesTest {

    @Test
    fun `按换行拆成多条`() {
        assertThat(FishBubbles.split("几点了都\n吃没吃")).containsExactly("几点了都", "吃没吃").inOrder()
    }

    @Test
    fun `空行和纯空白被丢掉`() {
        assertThat(FishBubbles.split("在吗\n\n\n嗯  \n")).containsExactly("在吗", "嗯").inOrder()
    }

    @Test
    fun `去掉列表符号`() {
        val out = FishBubbles.split("- 先说好\n1. 不许熬夜\n2、记得吃饭")
        assertThat(out).containsExactly("先说好", "不许熬夜", "记得吃饭").inOrder()
    }

    @Test
    fun `去掉 Markdown 星号`() {
        assertThat(FishBubbles.split("**真的**假的")).containsExactly("真的假的")
    }

    @Test
    fun `一整段长句按句末标点切开`() {
        val long = "今天下班的时候下了好大的雨，我在楼下站了十分钟才等到车。回来的时候鞋全湿了，" +
            "袜子踩在水里一路走回来的。你说气不气人，天气预报明明说今天没雨的。" +
            "我到家第一件事就是把鞋脱了晾在阳台上，结果发现阳台也漏水了。"
        val out = FishBubbles.split(long)
        assertThat(out.size).isAtLeast(2)
        out.forEach { assertThat(it).doesNotContain("\n") }
    }

    @Test
    fun `超过上限时合并尾巴而不是整段丢掉`() {
        val many = (1..9).joinToString("\n") { "第${it}句" }
        val out = FishBubbles.split(many)
        assertThat(out).hasSize(FishBubbles.MAX)
        // 最后一条必须还带着结尾的信息
        assertThat(out.last()).contains("第9句")
    }

    @Test
    fun `空回复不产生气泡`() {
        assertThat(FishBubbles.split("")).isEmpty()
        assertThat(FishBubbles.split("   \n \n")).isEmpty()
    }

    @Test
    fun `沉默标记不变成气泡`() {
        // 她选择不说话时只给已读回执，不能在聊天框里冒出「[已读]」这三个字
        assertThat(FishBubbles.split("[已读]")).isEmpty()
        assertThat(FishBubbles.split("（已读）")).isEmpty()
    }

    @Test
    fun `已读标记后面跟着正文时只留正文`() {
        assertThat(FishBubbles.split("[已读] 不想说")).containsExactly("不想说")
    }

    /*
     * 思考泄漏。
     *
     * 真机截图：气泡里出现了「不过我应该先关心一下现在几点、他还剩多少时间。
     * 简单问一句？不，别问太多。就这。」和「太多了。压到三条，每条短。」
     *
     * 提示词已经禁止，但中转站与部分模型会把思考包在标签里塞进正文。
     * 标签是明确边界，剥掉它不会误伤台词 —— 而漏过去，用户就会以为
     * 她在把日记本当消息发。
     */
    @Test
    fun `剥掉标签包住的思考`() {
        val raw = "<thought>要不要问他吃没吃</thought>吃了吗"
        assertThat(FishBubbles.split(raw)).containsExactly("吃了吗")
    }

    @Test
    fun `剥掉跨行的思考块`() {
        val raw = "<thinking>\n他今天考语文\n我问一句会不会太烦\n</thinking>\n考得怎么样"
        assertThat(FishBubbles.split(raw)).containsExactly("考得怎么样")
    }

    @Test
    fun `只有开标签时从标签起全部丢掉`() {
        // 被 token 上限截断时很常见：闭标签根本没生成出来
        val raw = "在吗\n<thought>先问他在干嘛"
        assertThat(FishBubbles.split(raw)).containsExactly("在吗")
    }

    @Test
    fun `剥掉整行的思考标注`() {
        val raw = "思考：先问一句\n吃了吗"
        assertThat(FishBubbles.split(raw)).containsExactly("吃了吗")
    }

    @Test
    fun `正常台词不会被当成思考剥掉`() {
        // 她在说话时完全可能提到"想"，不能因此吞掉整句
        val out = FishBubbles.split("我想了想\n还是算了吧\n心里想你")
        assertThat(out).containsExactly("我想了想", "还是算了吧", "心里想你").inOrder()
    }

    @Test
    fun `只有思考、没有正文时一个气泡都不发`() {
        assertThat(FishBubbles.split("<thought>算了不说了</thought>")).isEmpty()
    }
}
