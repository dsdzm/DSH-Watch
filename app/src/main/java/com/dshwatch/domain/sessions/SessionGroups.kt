package com.dshwatch.domain.sessions

import com.dshwatch.domain.model.Conversation
import com.dshwatch.domain.roleplay.Scenes

/**
 * 记录页的分组。
 *
 * ## 为什么要在数据层做
 *
 * 分组是**纯函数**：给一串会话，返回一串组。它不含任何界面代码，因此
 * 手机和手表可以共用同一份切法 —— 两边各写一遍 `groupBy`，最后一定
 * 在「组内怎么排」「空组怎么处理」这些细节上分叉，用户看到的就是
 * 「手表上酒馆排第一、手机上写作排第一」。
 *
 * ## 规则
 *
 * 1. **先按模式分**，顺序固定为 编程 / 酒馆 / 写作 / 养鱼 —— 和场景列表一致，
 *    不按「哪组最近有更新」浮动。组的位置每次都一样，翻两次就能记住。
 * 2. **酒馆与写作再按角色卡分**。这两种模式下「和谁聊」比「什么时候聊」重要：
 *    同一张卡的十条记录应该挨在一起，否则找「上次和这个角色说到哪」要翻十个组。
 *    组内仍保持「置顶在前、再按时间倒序」——那是 [Conversation] 列表本来的顺序。
 * 3. 编程与养鱼不再细分：编程会话没有卡，养鱼只有一个角色（而且只留一段对话）。
 * 4. **空组不出现**。一个没用过写作的用户不该在记录里看到一行「写作」。
 *
 * ## 组与组之间怎么排
 *
 * 同级组（酒馆下的一组角色卡）按**组内最近一条**倒序：刚聊过的角色排前面。
 * 这比按角色名字排更符合直觉 —— 用户找的是「最近那个人」，不是「按拼音第几个」。
 */
object SessionGroups {

    /**
     * 一组会话。
     *
     * [key] 用来当列表的稳定 key（分组头是列表项之一，不能没有 key）。
     * [cardId] 为空表示这一组不分卡；界面拿它决定要不要显示卡名。
     */
    data class Group(
        val key: String,
        val scene: String,
        val cardId: String,
        val title: String,
        val items: List<Conversation>,
    ) {
        val count: Int get() = items.size
    }

    /**
     * 切分。
     *
     * @param cardName 角色卡 id → 名字。拿不到（卡被删了）时返回空串，
     *   这里会退化成「未命名角色」，而不是把 id 直接印给用户看。
     */
    fun build(
        all: List<Conversation>,
        cardName: (String) -> String = { "" },
    ): List<Group> {
        val out = ArrayList<Group>(Scenes.all.size)
        for (scene in Scenes.all) {
            // Scenes.of() 兜底：老会话的 scene 是空串，按编程算，和历史行为一致
            val bucket = all.filter { Scenes.of(it.scene) == scene }
            if (bucket.isEmpty()) continue

            if (!usesCard(scene)) {
                out += Group(
                    key = scene,
                    scene = scene,
                    cardId = "",
                    title = Scenes.label(scene),
                    items = bucket,
                )
                continue
            }

            /*
             * 按卡分组，但保持「第一次出现」的顺序先建出来 ——
             * 后面再按组内最近时间排序。用 LinkedHashMap 是为了让
             * 「时间完全相同的两条」也有确定顺序，不会每次重组都跳。
             */
            val byCard = LinkedHashMap<String, MutableList<Conversation>>()
            bucket.forEach { conv ->
                byCard.getOrPut(conv.roleCardId) { mutableListOf() } += conv
            }
            byCard.entries
                .sortedByDescending { (_, list) -> list.maxOfOrNull { it.updatedAt } ?: 0L }
                .forEach { (cardId, list) ->
                    out += Group(
                        key = "$scene:${cardId.ifBlank { "-" }}",
                        scene = scene,
                        cardId = cardId,
                        title = Scenes.label(scene) + " · " + cardName(cardId).ifBlank { "未命名角色" },
                        items = list,
                    )
                }
        }
        return out
    }

    /** 只有酒馆与写作需要按卡细分 */
    fun usesCard(scene: String): Boolean =
        Scenes.of(scene) == Scenes.TAVERN || Scenes.of(scene) == Scenes.WRITING
}
