package com.dshwatch.domain.agent.tools

import com.dshwatch.data.store.sanitized
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.doubleOf
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.rawStrOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.fish.FishSkin
import com.dshwatch.domain.roleplay.Scenes
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 让她改自己那一页的样子。
 *
 * ## 为什么这是个工具，而不是只做设置界面
 *
 * 设置界面能改，但用户想改的时候往往正在聊天：「背景换成海边的图吧」
 * 「名字叫阿鲤好不好」。这类要求出现在对话里，让他退出去找设置、
 * 翻到外观、再选一遍图 —— 那句话就白说了。
 *
 * ## 「骨架」是怎么开放的
 *
 * 开放的是一组**具名槽位**（[FishSkin.SLOTS]），不是任意布局 DSL。
 * 理由很实际：放任模型写布局，它能写出来，但用户没法改回来、
 * 换个机型就错位、出错时也没法说清错在哪。具名槽位是那种
 * 「模型容易写对、用户能在界面里看见并微调、坏了能一键还原」的中间形态。
 *
 * ## 图片从哪来
 *
 * 用户可能说「用我刚发的那张」。图已经在会话目录里，模型把它当路径传进来
 * 就行（`list_files` / `attach_file` 之后它看得见路径）。工具负责复制到
 * `fish/`：直接引用会话目录里的文件是不行的，会话被删、缓存被清，
 * 背景就变黑。
 */
object FishSkinTool : AgentTool {

    override val name = "fish_skin"

    override val description =
        "改你自己那一页的样子（只在养鱼页生效，别的页面不受影响）。" +
            "只传要改的项，没传的保持原样。\n" +
            FishSkin.SLOTS + "\n" +
            "颜色写 #RRGGBB。avatar / background 传图片路径（工作空间相对路径或 /sdcard 下的绝对路径），" +
            "传空串表示去掉这张图。用户说「改回原来的样子」就用 reset。"

    override val safety = ToolSafety.READ_ONLY

    override val onlyInScene = Scenes.FISH

    override val parameters = Schema.obj(
        properties = mapOf(
            "name" to Schema.str("她的名字，最长 ${FishSkin.NAME_MAX} 字。用户说「你叫阿鲤」就传阿鲤"),
            "avatar" to Schema.str("头像图片路径。空串 = 去掉头像，回到默认"),
            "background" to Schema.str("聊天背景图片路径。空串 = 去掉背景图"),
            "accent" to Schema.str("主色 #RRGGBB：我这侧的气泡、强调线"),
            "bubble_theirs" to Schema.str("她那侧气泡的底色 #RRGGBB"),
            "background_dim" to Schema.num(
                "背景图压暗程度 ${FishSkin.DIM_RANGE.start}~${FishSkin.DIM_RANGE.endInclusive}。" +
                    "图太亮看不清字时调大，默认 0.35",
            ),
            "avatar_size" to Schema.int(
                "头像直径 dp",
                min = FishSkin.AVATAR_SIZE_RANGE.first,
                max = FishSkin.AVATAR_SIZE_RANGE.last,
            ),
            "text_size" to Schema.int(
                "气泡字号 sp",
                min = FishSkin.TEXT_SIZE_RANGE.first,
                max = FishSkin.TEXT_SIZE_RANGE.last,
            ),
            "show_avatar" to Schema.bool("要不要显示头像。关掉是纯文字列表"),
            "greeting" to Schema.str("还没说话时她的开场白，最长 ${FishSkin.GREETING_MAX} 字"),
            "reset" to Schema.bool("true = 全部恢复默认（名字、头像、背景、颜色一起还原）"),
        ),
    )

    override fun summarize(args: JsonObject): String {
        if (args.boolOf("reset")) return "恢复默认外观"
        val bits = buildList {
            args.strOf("name")?.let { add("名字→$it") }
            if (args.containsKey("avatar")) add("头像")
            if (args.containsKey("background")) add("背景")
            args.strOf("accent")?.let { add("主色 $it") }
        }
        return if (bits.isEmpty()) "改外观" else "改外观：${bits.joinToString("，")}"
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val store = ctx.fishSkin
            ?: return ToolResult.fail("当前不在养鱼页，没有可改的外观")

        if (args.boolOf("reset")) {
            store.reset()
            return ToolResult.ok("已恢复默认样子。头像、背景、名字都回到原来那样了")
        }

        var skin = store.skin.value
        val touched = mutableListOf<String>()

        // ---- 文字类 ----
        // 用 rawStrOf：空串在这里是"去掉"，不是"没给"
        args.rawStrOf("name")?.let {
            skin = skin.copy(name = it.trim().take(FishSkin.NAME_MAX))
            touched += if (it.isBlank()) "名字改回默认" else "名字叫「${it.trim()}」"
        }
        args.rawStrOf("greeting")?.let {
            skin = skin.copy(greeting = it.trim().take(FishSkin.GREETING_MAX))
            touched += "换了开场白"
        }

        // ---- 图片类 ----
        for ((key, slot) in listOf("avatar" to "avatar", "background" to "background")) {
            val raw = args.rawStrOf(key) ?: continue
            if (raw.isBlank()) {
                skin = if (slot == "avatar") skin.copy(avatar = "") else skin.copy(background = "")
                touched += if (slot == "avatar") "去掉头像" else "去掉背景图"
                continue
            }
            val src = resolveImage(raw, ctx)
                ?: return ToolResult.fail(
                    "找不到这张图：$raw。先把图放进工作空间，或给一个能读的绝对路径（/sdcard/…）",
                )
            val saved = store.importImage(src, slot)
                .getOrElse { e ->
                    return ToolResult.fail("这张图用不了：${e.message ?: "读不出来"}")
                }
            skin = if (slot == "avatar") skin.copy(avatar = saved) else skin.copy(background = saved)
            touched += if (slot == "avatar") "换了头像" else "换了背景"
        }

        // ---- 颜色 ----
        args.strOf("accent")?.let { hex ->
            val c = parseColor(hex) ?: return ToolResult.fail("颜色写法不对：$hex（要 #RRGGBB 或 #AARRGGBB）")
            skin = skin.copy(accent = c)
            touched += "主色 $hex"
        }
        args.strOf("bubble_theirs")?.let { hex ->
            val c = parseColor(hex) ?: return ToolResult.fail("颜色写法不对：$hex（要 #RRGGBB 或 #AARRGGBB）")
            skin = skin.copy(bubbleTheirs = c)
            touched += "她那侧气泡 $hex"
        }

        // ---- 数值 ----
        args.doubleOf("background_dim")?.let { v ->
            skin = skin.copy(backgroundDim = v.toFloat())
            touched += "背景压暗 ${"%.2f".format(skin.sanitized().backgroundDim)}"
        }
        args.intOf("avatar_size")?.let { v ->
            skin = skin.copy(avatarSize = v)
            touched += "头像大小 ${skin.sanitized().avatarSize}dp"
        }
        args.intOf("text_size")?.let { v ->
            skin = skin.copy(textSize = v)
            touched += "字号 ${skin.sanitized().textSize}sp"
        }
        if (args.containsKey("show_avatar")) {
            skin = skin.copy(showAvatar = args.boolOf("show_avatar", true))
            touched += if (skin.showAvatar) "显示头像" else "隐藏头像"
        }

        if (touched.isEmpty()) {
            return ToolResult.fail("没有要改的内容。可以改：${FishSkin.SLOTS.lines().size} 个槽位里的任意一个")
        }

        val saved = runCatching { store.saveOrThrow(skin) }
            .getOrElse { e ->
                /*
                 * 存不下来就**必须说存不下来**。
                 *
                 * 这条以前是静默的：写盘失败只打一行 release 里会被删掉的日志，
                 * 工具照样回「改好了」。用户退出重进发现变回原样，而她已经答应过。
                 */
                return ToolResult.fail(
                    "样式改好了但存不下来（${e.message ?: "写盘失败"}）。" +
                        "让她下次再试，或者去「她的样子」里手动改",
                )
            }
        return ToolResult.ok(
            "改好了，你自己那一页已经变成这样：${touched.joinToString("，")}。" +
                "（${if (saved.name.isBlank()) "名字沿用原来的" else "现在叫${saved.name}"}）",
        )
    }

    /** 图片路径 → 文件。先按绝对路径找，再按工作空间相对路径找 */
    private fun resolveImage(raw: String, ctx: ToolContext): File? {
        val candidates = buildList {
            add(File(raw))
            add(File(ctx.workspaceRoot(), raw))
            if (ctx.sessionDir.isNotBlank()) add(File(File(ctx.workspaceRoot(), ctx.sessionDir), raw))
        }
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * `#RRGGBB` / `#AARRGGBB` / 纯十六进制 → ARGB。
     *
     * 模型很爱写 `#3A7BD5`，也很爱写 `3A7BD5`，两种都收。
     * 不带 alpha 时补成 FF —— 少了这一步会得到全透明色，界面上"改了但看不见"。
     */
    internal fun parseColor(text: String): Long? {
        val hex = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        val v = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> 0xFF000000L or v
            8 -> v
            else -> null
        }
    }
}
