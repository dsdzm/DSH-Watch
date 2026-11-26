package com.dshwatch.domain.fish

import kotlinx.serialization.Serializable

/**
 * 「养鱼」这一页的外观。
 *
 * ## 为什么要独立一份，而不是改全局主题
 *
 * 用户原话是「页面和普通对话页分离，只在养鱼页生效」。改全局主题会把编程页、
 * 酒馆、写作一起染上颜色 —— 那是另一件事，而且会让人在写代码时被一片粉色包围。
 *
 * 所以这里存的是**这一页自己的**一组值：她叫什么、头像是什么、背景图是哪个、
 * 气泡什么色。页面上每一处需要这些值的地方都从这里取，取不到就退回默认，
 * 不影响任何别的界面。
 *
 * ## 图片为什么存路径而不是位图
 *
 * 存路径（相对工作空间）。图片本身在 `fish/` 下，一张几百 KB 的壁纸塞进
 * JSON 会让设置读写变成几十毫秒的阻塞，而这一页要频繁重读。
 *
 * ## 给模型看的那一层
 *
 * [SLOTS] 是「骨架」的说明书：哪些槽位存在、各自管什么、取值范围。
 * 工具 `fish_skin` 的描述就是照它生成的 —— 用户说「背景换成深蓝、名字叫阿鲤」，
 * 模型得先知道"背景"对应哪个字段，才不会瞎猜参数名。
 */
@Serializable
data class FishSkin(
    /** 她的名字。空 = 用角色卡上的名字 */
    val name: String = "",
    /** 头像图片，相对工作空间的路径（`fish/avatar.png`）。空 = 用默认那条鱼的渐变圆 */
    val avatar: String = "",
    /** 聊天背景图，同上。空 = 纯色 */
    val background: String = "",
    /** 主色（ARGB）：我这侧的气泡、强调线。0 = 跟随默认 */
    val accent: Long = 0L,
    /** 她那侧的气泡底色。0 = 跟随主题的 surfaceContainerHighest */
    val bubbleTheirs: Long = 0L,
    /** 背景图压暗程度 0f..0.85f，保证字还看得清 */
    val backgroundDim: Float = 0.35f,
    /** 头像直径（dp）。手表的屏也能塞下 36，别允许太夸张的值 */
    val avatarSize: Int = 36,
    /** 气泡字号（sp） */
    val textSize: Int = 15,
    /** 是否显示头像。关掉就是纯文字列表，有人喜欢那种干净 */
    val showAvatar: Boolean = true,
    /** 开场白覆盖。空 = 用角色卡上的 */
    val greeting: String = "",
) {

    /** 颜色值转 Int（Compose 要 Int） */
    fun accentColorInt(): Int? = accent.takeIf { it != 0L }?.toInt()
    fun theirsColorInt(): Int? = bubbleTheirs.takeIf { it != 0L }?.toInt()

    /** 只保留真正生效的字段，比较"改没改过"用 */
    fun isEmpty(): Boolean = this == DEFAULT

    companion object {
        val DEFAULT = FishSkin()

        /** 值的合法范围。工具与界面共用，避免模型写出 200dp 的头像 */
        val AVATAR_SIZE_RANGE = 24..64
        val TEXT_SIZE_RANGE = 12..22
        val DIM_RANGE = 0f..0.85f
        const val NAME_MAX = 12
        const val GREETING_MAX = 80

        /**
         * 骨架说明书：给模型的「页面长什么样、哪一格能改」。
         *
         * 不写这个，模型只会照用户的原话猜字段名（"背景" → `bg`？`wallpaper`？），
         * 然后一路试错到步数用完。写清楚之后一次就能改对。
         */
        val SLOTS: String = """
            |这一页从上到下的骨架（每一项都可以单独改，没提到的保持原样）：
            |  1. 顶栏：左边菜单键，中间「头像 + 名字 + 状态」，右边模型名
            |     └ name（名字，最长 ${NAME_MAX} 字）、avatar（头像图）
            |  2. 消息区：铺满整块，气泡左右分列
            |     └ background（背景图）、background_dim（背景压暗 0~0.85）、
            |       accent（我这侧气泡色）、bubble_theirs（她那侧气泡色）、
            |       text_size（${TEXT_SIZE_RANGE.first}~${TEXT_SIZE_RANGE.last}）、
            |       avatar_size（${AVATAR_SIZE_RANGE.first}~${AVATAR_SIZE_RANGE.last}）、show_avatar（要不要头像）
            |  3. 开场白：还没说话时她那句
            |     └ greeting（最长 ${GREETING_MAX} 字）
        """.trimMargin()
    }
}
