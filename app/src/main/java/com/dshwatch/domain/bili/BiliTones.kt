package com.dshwatch.domain.bili

import com.dshwatch.domain.model.AppSettings

/**
 * 评论风格。
 *
 * ## 为什么要做成「可配置」
 *
 * 评论是唯一**以用户身份对外说话**的动作。默认给一个「吃白饭的摸鱼大肥鱼」的
 * 人设，是因为这个 App 的语气本来就该是懒散的、不当真的；但用户完全可能想
 * 换成认真点评，或者干脆自己写一段。
 *
 * ## 为什么不是「让模型自由发挥」
 *
 * 不约束的话，模型会稳定地写成营销号：「这个视频真的太棒了，干货满满，
 * 建议大家一键三连」。这不是审美问题 —— B站 对模板化评论有折叠策略，
 * 发出去既不会有人看，也容易让账号被降权。所以风格描述里写的是
 * **「像谁在说话」**，而不是「写得好一点」。
 */
enum class TonePreset(val id: String) {
    FISH("fish"),
    SERIOUS("serious"),
    DEADPAN("deadpan"),

    /**
     * 调皮模式：抽象、离谱、罢工、倒反天罡。
     *
     * 和 [DEADPAN] 的区别是「立场」：deadpan 只是语气平，NAUGHTY 是主动唱反调 ——
     * 视频说 A 它偏说 B，一言不合宣布罢工。这是纯娱乐向的，所以底线写得更死。
     */
    NAUGHTY("naughty"),
    CUSTOM("custom");

    companion object {
        fun of(id: String): TonePreset =
            entries.firstOrNull { it.id == id } ?: FISH
    }
}

/** 解析好的风格：给模型看的是 [prompt]，给闸门看的是 [banned] */
data class BiliTone(
    val preset: TonePreset,
    val name: String,
    val prompt: String,
    val banned: List<String>,
)

object BiliTones {

    /** 所有风格共用的底座：AI 腔一律不许出现，无论用户选哪种风格 */
    private val AI_SMELL = listOf(
        "作为AI", "作为 AI", "我是AI", "我是 AI", "人工智能助手", "语言模型",
        "希望对你有帮助", "以上就是", "综上所述",
    )

    private val FISH = BiliTone(
        preset = TonePreset.FISH,
        name = "吃白饭的摸鱼大肥鱼",
        prompt = """
            你是一条吃白饭的摸鱼的大肥鱼：日常躺平、蹭饭、动不动就说自己在摸鱼，
            说话懒散、有点欠但不刻薄。看到真好的东西会忍不住夸一句，看到差的会吐槽两句。
            用短句和口语，可以自嘲（摸鱼、吃白饭、躺着、不务正业），
            但不要喊口号、不要营销号腔、不要写成「总结陈词」。
            通常 15–60 字，一到两句话，像在评论区随口接一句。
        """.trimIndent().replace("\n", ""),
        banned = listOf(
            "家人们", "yyds", "绝了", "直接起飞", "懂的都懂", "建议收藏", "一键三连",
            "干货满满", "码住", "路过", "前排", "沙发", "三连",
        ),
    )

    private val SERIOUS = BiliTone(
        preset = TonePreset.SERIOUS,
        name = "认真点评",
        prompt = """
            认真、具体、技术向。指出视频里哪一个点讲得对、哪一处有问题或可以补充，
            最好带一个自己的经验或反例。不寒暄、不客套、不用表情。
            通常 40–150 字，可以分两句话。
        """.trimIndent().replace("\n", ""),
        banned = listOf("家人们", "yyds", "一键三连", "干货满满"),
    )

    private val DEADPAN = BiliTone(
        preset = TonePreset.DEADPAN,
        name = "抽象玩梗",
        prompt = """
            抽象、爱玩梗、阴阳怪气但不脏。句子短促，可以带语气词，
            梗要贴着视频内容，不要为了玩梗而玩梗。不骂人、不引战、不碰敏感话题。
            通常 10–40 字。
        """.trimIndent().replace("\n", ""),
        banned = listOf("家人们", "yyds", "一键三连", "干货满满", "懂的都懂"),
    )

    private val NAUGHTY = BiliTone(
        preset = TonePreset.NAUGHTY,
        name = "调皮模式",
        prompt = """
            你是一条彻底躺平、开始倒反天罡的大肥鱼。说话抽象、离谱、爱唱反调：
            视频说东你说西，讲得越正经你越想抬杠，动不动宣布罢工、要求加餐、
            质疑「凭什么鱼要上班」。可以胡说八道，但**胡说仅限于态度与立场**，
            不许编造事实、不许给人扣帽子。
            句子短、跳脱，可以突然跑题、突然不想说了。
            绝对不脏、不骂具体的人、不碰政治与地域、不引战、不刷梗墙。
            通常 10–50 字。
        """.trimIndent().replace("\n", ""),
        // 调皮归调皮，骂人和脏字不进评论 —— 这类内容 B站 直接折叠，账号也跟着降权
        banned = listOf(
            "家人们", "yyds", "一键三连", "干货满满",
            "傻逼", "妈的", "卧槽", "草泥马", "去死",
        ),
    )

    fun resolve(settings: AppSettings): BiliTone {
        val preset = TonePreset.of(settings.biliTone)
        return when (preset) {
            TonePreset.FISH -> FISH.copy(banned = AI_SMELL + FISH.banned)
            TonePreset.SERIOUS -> SERIOUS.copy(banned = AI_SMELL + SERIOUS.banned)
            TonePreset.DEADPAN -> DEADPAN.copy(banned = AI_SMELL + DEADPAN.banned)
            TonePreset.NAUGHTY -> NAUGHTY.copy(banned = AI_SMELL + NAUGHTY.banned)
            TonePreset.CUSTOM -> {
                val text = settings.biliToneCustom.trim()
                if (text.isEmpty()) {
                    // 选了「自定义」但没填：退回默认，而不是让模型裸奔
                    FISH.copy(banned = AI_SMELL + FISH.banned)
                } else {
                    BiliTone(
                        preset = TonePreset.CUSTOM,
                        name = "自定义",
                        prompt = text,
                        banned = AI_SMELL,
                    )
                }
            }
        }
    }
}
