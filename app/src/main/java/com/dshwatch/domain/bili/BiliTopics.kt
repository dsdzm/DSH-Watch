package com.dshwatch.domain.bili

/**
 * 动态选题。
 *
 * 三种不是「三种模板」，而是三种**视角**：一个人发朋友圈时，往往是
 * 今天干了啥 + 顺手吐槽 + 想到某个人，三者混着来。所以这里给模型的是
 * 可选角度，不是必须逐条覆盖的清单。
 *
 * 用字符串常量而不是枚举，理由和 [BiliMode] 一样：设置整包 JSON 落盘，
 * 枚举改名会让老用户读不回自己的选择。区别是这里允许多选，所以存的是
 * 逗号分隔的字符串。
 */
object BiliTopics {

    const val LIFE = "life"
    const val ROAST = "roast"
    const val MASTER = "master"

    /** 顺序即设置页的显示顺序，也用来把用户的选择重新排列成固定顺序 */
    val all = listOf(LIFE, ROAST, MASTER)

    fun of(raw: String): List<String> {
        val picked = raw.split(',').map { it.trim() }.filter { it in all }
        return all.filter { it in picked }
    }

    fun label(key: String): String = when (key) {
        ROAST -> "刷到的视频吐槽"
        MASTER -> "对主人（用户）的感受"
        else -> "生活日常"
    }

    /**
     * 给模型看的完整描述。
     *
     * 写这么细是因为「对主人的感受」这种选题特别容易被写成口号；
     * 点明「第一人称、具体到某件事」比只说四个字有用得多。
     */
    fun describe(key: String): String = when (key) {
        ROAST -> "刷到的视频吐槽（刚看完什么，哪里离谱 / 哪里真不错，要具体到点）"
        MASTER -> "对主人的感受（跟着他、被他使唤、嫌他烦又离不开，用第一人称说具体的事）"
        else -> "生活日常（今天在干嘛、吃了什么、犯困、摸鱼、白吃白喝）"
    }

    /** 一段话概括当前选择，直接拼进 prompt */
    fun summary(raw: String): String {
        val on = of(raw)
        if (on.isEmpty()) return describe(LIFE)
        return on.joinToString("；") { describe(it) }
    }
}
