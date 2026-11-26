package com.dshwatch.domain.sessions

import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.roleplay.Scenes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 记录页的分组规则。
 *
 * 界面上「分组」看起来只是加一行标题，实际上坏起来很难发现：
 * 组序会随数据顺序浮动、空组会顶出一个孤零零的标题、
 * 组内顺序被打乱导致置顶失效 —— 三种都不会崩，只是"用着怪"。
 *
 * 所以规则写成纯函数，在这里钉死。
 */
class SessionGroupsTest {

    private fun conv(
        id: String,
        scene: String,
        card: String = "",
        at: Long = 0L,
        pinned: Boolean = false,
    ) = Conversation(
        id = id,
        title = id,
        scene = scene,
        roleCardId = card,
        updatedAt = at,
        messageCount = 2,
        pinned = pinned,
    )

    @Test
    fun `按模式分组，顺序固定为编程酒馆写作养鱼`() {
        val groups = SessionGroups.build(
            listOf(
                conv("f1", Scenes.FISH),
                conv("w1", Scenes.WRITING, "wc"),
                conv("t1", Scenes.TAVERN, "tc"),
                conv("c1", Scenes.CODING),
            ),
        )
        assertThat(groups.map { it.scene })
            .containsExactly(Scenes.CODING, Scenes.TAVERN, Scenes.WRITING, Scenes.FISH)
            .inOrder()
    }

    @Test
    fun `酒馆与写作按角色卡再分，编程养鱼不细分`() {
        val groups = SessionGroups.build(
            listOf(
                conv("t1", Scenes.TAVERN, "a", at = 100),
                conv("t2", Scenes.TAVERN, "b", at = 900),
                conv("t3", Scenes.TAVERN, "a", at = 200),
                conv("c1", Scenes.CODING, "", at = 1),
                conv("c2", Scenes.CODING, "", at = 2),
            ),
        ) { id -> "卡$id" }

        val tavern = groups.filter { it.scene == Scenes.TAVERN }
        assertThat(tavern).hasSize(2)
        // 组间按「组内最近一条」倒序：b 组最近是 900，排前面
        assertThat(tavern.map { it.cardId }).containsExactly("b", "a").inOrder()
        assertThat(tavern[0].title).isEqualTo("酒馆 · 卡b")
        // 同卡的两条挨在一起，且保持传入顺序
        assertThat(tavern[1].items.map { it.id }).containsExactly("t1", "t3").inOrder()

        val coding = groups.single { it.scene == Scenes.CODING }
        assertThat(coding.cardId).isEmpty()
        assertThat(coding.title).isEqualTo("编程")
        assertThat(coding.count).isEqualTo(2)
    }

    @Test
    fun `空组不出现`() {
        val groups = SessionGroups.build(listOf(conv("c1", Scenes.CODING)))
        assertThat(groups).hasSize(1)
        assertThat(groups[0].scene).isEqualTo(Scenes.CODING)
    }

    @Test
    fun `卡被删了也不能把 id 印给用户`() {
        val groups = SessionGroups.build(listOf(conv("t1", Scenes.TAVERN, "ghost"))) { "" }
        assertThat(groups[0].title).isEqualTo("酒馆 · 未命名角色")
    }

    @Test
    fun `老会话的空 scene 按编程处理`() {
        val groups = SessionGroups.build(listOf(conv("old", "")))
        assertThat(groups.single().scene).isEqualTo(Scenes.CODING)
    }

    @Test
    fun `养鱼永远只有一组，不按卡拆`() {
        val groups = SessionGroups.build(
            listOf(
                conv("f1", Scenes.FISH, "builtin-tavern-feiyu", at = 1),
                conv("f2", Scenes.FISH, "other", at = 2),
            ),
        )
        assertThat(groups).hasSize(1)
        assertThat(groups[0].count).isEqualTo(2)
        assertThat(groups[0].cardId).isEmpty()
    }
}
