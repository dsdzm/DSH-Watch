package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.intOf
import com.dshwatch.domain.agent.strOf
import com.dshwatch.domain.bili.BiliApi
import com.dshwatch.domain.bili.BiliException
import com.dshwatch.domain.bili.BiliImages
import com.dshwatch.domain.bili.BiliMode
import com.dshwatch.domain.bili.BiliModes
import com.dshwatch.domain.bili.BiliTone
import com.dshwatch.domain.bili.BiliTones
import com.dshwatch.domain.bili.BiliTopics
import com.dshwatch.domain.bili.BiliUser
import com.dshwatch.domain.bili.ReplyNotice
import com.dshwatch.domain.bili.UploadedImage
import com.dshwatch.domain.bili.VideoDetail
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.util.Fmt
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「刷 B站」工具组。
 *
 * 设计参考开源插件 dsh-bili-scroll 的流水线（发现 → 读正文 → 判断 → 写日报 → 可选评论），
 * 但**把 Python + MCP server 那一层整个去掉**：手表上没有常驻的 Python 环境，
 * 也不该为了刷 B站要求用户在旁边开一台电脑。接口调用、WBI 签名、日报落盘
 * 全部用 Kotlin 原生做，装上就能用。
 *
 * ## 为什么「判断」不是工具
 *
 * 参考实现里 `bili_judge` 会再调一次 DeepSeek 打分。这里不需要 ——
 * **模型自己就在场**，让它读字幕、自己下判断，既省一次 API 往返，
 * 也避免出现「两个模型互相看不懂对方结论」的情况。
 * 所以这里只提供 `bili_report`：把模型已经形成的判断落到日报里，
 * 顺带做配额计数与判重。
 *
 * ## 两条流水线
 *
 *  - **看**：search → subtitle → 判断 → report
 *  - **回**：inbox（谁回了我）→ thread（这栋楼在聊什么）→ reply（精准回一条）
 *
 * 两条都受同一组开关约束，也都遵守同一套评论风格。
 */
object BiliTools {

    fun all(): List<AgentTool> = listOf(
        StatusTool,
        SearchTool,
        VideoTool,
        SubtitleTool,
        CommentsTool,
        InboxTool,
        ThreadTool,
        ReportTool,
        ReplyTool,
        DmTool,
        DynTool,
        LikeTool,
        FavFoldersTool,
        FavListTool,
        CoinTool,
        FavTool,
    )

    /** 日报放在工作空间根的 `bili/` 下：跨会话共享，换会话不会丢配额与判重记录 */
    private const val DIR = "bili"

    private fun api(ctx: ToolContext) = BiliApi { ctx.biliCookie() }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun digestPath(): String = "$DIR/${today()}.md"

    /** 今天已经写了几条：数 `### BV...` 行数，和参考实现一致，不另存计数器 */
    private suspend fun countToday(ctx: ToolContext): Int =
        ctx.workspace.read(digestPath(), maxChars = 400_000).orEmpty()
            .lineSequence()
            .count { it.startsWith("### BV") }

    /**
     * 判重键。
     *
     * 从 1.9 起是 `bvid|root|parent` —— 同一个视频下可以回复不同的人，
     * 只按 bvid 判重会把「回了 A 就不能再回 B」这种正常需求挡掉。
     * 老版本只记了 `bvid` 的行仍然能匹配顶层评论（root/parent 都是 0）。
     */
    private fun replyKey(bvid: String, root: Long, parent: Long): String =
        if (root <= 0 && parent <= 0) bvid else "$bvid|$root|$parent"

    private suspend fun repliedBefore(ctx: ToolContext, key: String): Boolean =
        ctx.workspace.read("$DIR/replied.md", maxChars = 100_000).orEmpty()
            .lineSequence()
            .any { it.trim() == key }

    private suspend fun markReplied(ctx: ToolContext, key: String) {
        ctx.workspace.append("$DIR/replied.md", "$key\n")
    }

    // ------------------------------------------------------------------
    // 对外发言的三件套：配额、@ 目标、配图
    // ------------------------------------------------------------------

    /** 每天发过的话记在这里，一行一条：`HH:mm kind target` */
    private fun sentPath(): String = "$DIR/sent/${today()}.md"

    private suspend fun countSentToday(ctx: ToolContext, kind: String? = null): Int =
        ctx.workspace.read(sentPath(), maxChars = 200_000).orEmpty()
            .lineSequence()
            .count { it.isNotBlank() && (kind == null || it.contains(" $kind ")) }

    private suspend fun markSent(ctx: ToolContext, kind: String, target: String) {
        val line = SimpleDateFormat("HH:mm", Locale.US).format(Date()) + " $kind $target\n"
        ctx.workspace.append(sentPath(), line)
    }

    /**
     * 今天还能不能对外说话。
     *
     * `biliCommentQuota` 是评论与私信的**合计**上限：两者都是「以用户身份对外发言」，
     * 分开计数等于把上限翻倍。0 表示不限，但那种情况下用户得自己盯着。
     *
     * `kind` 非空时只数这一类。动态用独立上限（[AppSettings.biliDynQuota]），
     * 但它同时也被计入总发言数 —— 否则「3 条评论 + 1 条动态」就成了四次对外发言。
     */
    private suspend fun remainingQuota(ctx: ToolContext, quota: Int, kind: String? = null): Int {
        if (quota <= 0) return Int.MAX_VALUE
        return (quota - countSentToday(ctx, kind)).coerceAtLeast(0)
    }

    /**
     * 解析要 @ 的人。
     *
     * 拿不到昵称就返回 null 并把原因说清楚 —— 只发 `@123456` 这种纯数字，
     * 在 B站 里既不会变色也不会通知，等于白写。
     */
    private suspend fun atTarget(ctx: ToolContext): Pair<BiliUser?, String> {
        val uid = ctx.settings().biliAtUid.trim().toLongOrNull() ?: return null to ""
        if (uid <= 0) return null to ""
        val user = runCatching { api(ctx).userCard(uid) }.getOrNull()
            ?: return null to "（想 @ UID $uid，但查不到这个人的昵称，这条先不 @）"
        return user to ""
    }

    /**
     * 按设置准备配图。
     *
     * **失败就失败，不静默降级成纯文字。** 之前这里上传失败只回一句提示、
     * 评论照发，用户看到「已发布」却发现没图 —— 表现和「功能没做」一模一样，
     * 也没法判断到底是哪一步坏了。配图是用户显式选的，那就该让失败可见。
     */
    private suspend fun preparePictures(
        ctx: ToolContext,
        seed: Long,
    ): PicturePack {
        val s = ctx.settings()
        val kind = s.biliImage
        if (kind == "none" || kind.isBlank()) return PicturePack()

        val bytes = when (kind) {
            // 内置表情包：四张里随机一张。取不到（assets 被裁掉）才退回摸鱼卡，
            // 这是唯一一处允许降级的地方 —— 图源缺失不是用户的错。
            "pack" -> {
                val c = ctx.appContext
                val raw = c?.let { BiliImages.pack(it, seed) }
                if (raw != null) {
                    raw
                } else {
                    val text = s.biliImageText.trim()
                        .ifBlank { BiliImages.pickFallback(seed.toInt()) }
                    BiliImages.card(text, coverFooter(s))
                }
            }
            "card" -> {
                val text = s.biliImageText.trim()
                    .ifBlank { BiliImages.pickFallback(seed.toInt()) }
                BiliImages.card(text, coverFooter(s))
            }
            "local" -> {
                val c = ctx.appContext ?: return PicturePack(error = "没有 Android 上下文，取不到相册图")
                BiliImages.loadLocal(c)
                    ?: return PicturePack(
                        error = "设置里选了相册图，但还没选过。去 设置 → 刷B站 → 配图 选一张，" +
                            "或者改回「大肥鱼表情包」。"
                    )
            }
            else -> return PicturePack()
        }

        return runCatching { listOf(api(ctx).uploadImage(bytes)) }.fold(
            onSuccess = { PicturePack(pics = it) },
            onFailure = {
                PicturePack(
                    error = "配图上传失败：${errText(it)}\n" +
                        "图片没传上去就不会带图，这条先不发 —— 免得你以为带图了其实没有。" +
                        "可以改设置里的配图来源，或稍后重试。"
                )
            },
        )
    }

    private fun coverFooter(s: com.dshwatch.domain.model.AppSettings): String =
        if (s.biliUserName.isNotBlank()) s.biliUserName else "bilibili.com"

    /** 配图准备结果。`error` 非空表示「用户要图但没拿到」，调用方必须中止 */
    private data class PicturePack(
        val pics: List<UploadedImage> = emptyList(),
        val error: String? = null,
    )

    private fun durationText(sec: Int): String =
        if (sec <= 0) "?" else "%d:%02d".format(sec / 60, sec % 60)

    private fun countText(n: Long): String = when {
        n >= 100_000_000 -> String.format(Locale.US, "%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> String.format(Locale.US, "%.1f万", n / 10_000.0)
        else -> n.toString()
    }

    private fun ago(sec: Long): String {
        if (sec <= 0) return ""
        val d = System.currentTimeMillis() / 1000 - sec
        return when {
            d < 3600 -> "${(d / 60).coerceAtLeast(1)} 分钟前"
            d < 86_400 -> "${d / 3600} 小时前"
            d < 86_400 * 30 -> "${d / 86_400} 天前"
            else -> Fmt.date(sec * 1000)
        }
    }

    // ------------------------------------------------------------------
    // 1. 状态 / 工作流
    // ------------------------------------------------------------------

    /**
     * 开工第一步。
     *
     * 参考实现里对应 `bili_duty_guide`：把「这一轮该怎么做」直接写进工具结果，
     * 而不是塞进系统提示 —— 系统提示每一轮都要付 token，而这段只在真要用时才读。
     */
    object StatusTool : AgentTool {
        override val name = "bili_status"
        override val description =
            "刷B站 / 回评论前的第一步：返回今日配额、关注关键词、登录状态、当前模式" +
                "（发评论 / 发私信 / 只总结）、@ 目标、配图设置、动态选题、点赞开关、评论风格，" +
                "以及本轮标准流程。"
        override val safety = ToolSafety.READ_ONLY
        override val parameters = Schema.obj(emptyMap())
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val s = ctx.settings()
            val used = countToday(ctx)
            val cookie = ctx.biliCookie().isNotBlank()
            val tone = BiliTones.resolve(s)
            val mode = BiliModes.of(s)
            val sent = countSentToday(ctx)
            val quotaText = if (s.biliCommentQuota <= 0) "不限" else "${s.biliCommentQuota} 条"
            return ToolResult.ok(
                buildString {
                    append("今日已记：").append(used)
                        .append(" / ").append(if (s.biliDailyQuota <= 0) "不限" else "${s.biliDailyQuota} 条")
                        .append("\n")
                    append("关注关键词：").append(s.biliKeywords.ifBlank { "（未设置，由你按用户这次的话决定）" })
                        .append('\n')
                    append("登录态：").append(
                        when {
                            !cookie -> "匿名（能搜索/读字幕，不能发评论、私信、看不到回复）"
                            s.biliUserName.isNotBlank() -> "已登录（${s.biliUserName}）"
                            else -> "已配置 Cookie"
                        }
                    ).append('\n')

                    append("模式【").append(
                        when (mode) {
                            BiliMode.SUMMARY -> "只总结 —— 只写日报，不对外发任何东西"
                            BiliMode.COMMENT -> "发评论 —— 可以在视频下发评论 / 回复别人"
                            BiliMode.DM -> "发私信 —— 给 UP 主或指定 UID 发一条私信"
                        }
                    ).append("】\n")
                    if (mode != BiliMode.SUMMARY) {
                        append("今日已对外发：").append(sent).append(" / ").append(quotaText).append('\n')
                    }
                    if (s.biliDynEnabled) {
                        val dynUsed = countSentToday(ctx, "dyn")
                        append("发动态：**开**，今日已发 ").append(dynUsed).append(" / ")
                            .append(if (s.biliDynQuota <= 0) "不限" else "${s.biliDynQuota} 条")
                            .append("（独立上限，同时也计入上面的总发言数）\n")
                        append("动态可写的内容：「").append(dynTopicsText(s)).append("」\n")
                    } else {
                        append("发动态：关（不要调用 bili_dyn）\n")
                    }
                    if (s.biliAtUid.isNotBlank()) {
                        append("评论要 @ 的 UID：").append(s.biliAtUid.trim()).append('\n')
                    }
                    append("配图（评论与动态共用）：").append(
                        when (s.biliImage) {
                            "pack" -> "大肥鱼表情包（内置 ${BiliImages.packSize} 张，每次随机一张）"
                            "card" -> "摸鱼卡（程序生成，卡上文字「" +
                                s.biliImageText.ifBlank { "按日期轮换" } + "」）"
                            "local" -> "用户自选的相册图"
                            else -> "不带"
                        }
                    ).append('\n')
                    if (s.biliLikeEnabled) {
                        append("点赞：**开**（同一条视频当天只点一次，不消耗任何资源）\n")
                    } else {
                        append("点赞：关（不要调用 bili_like）\n")
                    }
                    append("评论风格【").append(tone.name).append("】：").append(tone.prompt).append('\n')
                    append("日报：").append(digestPath()).append('\n')

                    append("\n流程 A · 刷视频：\n")
                    append("1. bili_search 按关键词找候选（或用户直接给了 BV 号）\n")
                    append("2. bili_subtitle 读**字幕正文** —— 判断依据只能是正文，不许只看标题\n")
                    append("3. 自己下判断：值得看 / 可以看 / 不值当，并写一句话理由\n")
                    append("4. bili_report 把挑中的写进日报（自动扣配额、自动判重）\n")
                    append("5. 配额用完就停，把今天挑出来的列给用户\n")

                    when (mode) {
                        BiliMode.COMMENT -> {
                            append("\n流程 B · 发评论 / 接话：\n")
                            append("1. bili_inbox 看谁回复了我（未登录时不可用）\n")
                            append("2. 想弄清上下文就 bili_thread；不想就直接回\n")
                            append("3. bili_reply 直接发（**不需要用户确认**）：带 root + parent 是楼中楼回复，\n")
                            append("   不带就是顶层评论；配图与 @ 由设置自动处理，你不用手动传\n")
                            append("4. 每日发言有上限，超了 bili_reply 会拒绝 —— 那时就停下，别再试\n")
                        }
                        BiliMode.DM -> {
                            append("\n流程 B · 发私信：\n")
                            append("1. 私信**不可撤回、对方一定看得到**，只在真有话说时用\n")
                            append("2. bili_dm：给 bvid 的 UP 主发（不填 to_uid），或指定 to_uid\n")
                            append("3. 内容仍然遵守上面的评论风格，但比评论更克制：不催更、不索要、不刷存在感\n")
                        }
                        BiliMode.SUMMARY -> {
                            append("\n模式是「只总结」：**不要**调用 bili_reply / bili_dm，\n")
                            append("把想说的话写进日报即可。用户要发评论得先去设置里换模式。\n")
                        }
                    }

                    if (s.biliDynEnabled) {
                        append("\n流程 C · 发动态（可选，不是每轮都发）：\n")
                        append("1. 动态是**独立广播**，会进粉丝时间线 —— 只有真有话想说时才发，\n")
                        append("   不要为了「用一下工具」而发，也不要把它写成视频摘要\n")
                        append("2. 从上面「动态可写的内容」里挑一个角度写，三种都是一个人会发的东西：\n")
                        append("   生活日常像碎碎念，视频吐槽要有具体的点，说「主人」用第一人称、别喊口号\n")
                        append("3. bili_dyn 直接发（**不需要确认**）；正文 20–200 字，观点或情绪要具体\n")
                        append("4. 配图跟随设置；纯文字更利落时传 with_image=false\n")
                        append("5. 有独立每日上限，超了会拒绝；相同内容当天不会重复发\n")
                    }

                    if (s.biliLikeEnabled) {
                        append("\n流程 D · 点赞（可选，不是每条都点）：\n")
                        append("1. 只在你真觉得这条值得的时候用 bili_like，不要每看完一条就点\n")
                        append("2. 同一条视频当天只点一次，重复调用会被跳过\n")
                    }

                    append("\n字幕取不到时用 bili_comments 看评论区，仍取不到就跳过这条并说明原因。")
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. 搜索
    // ------------------------------------------------------------------

    object SearchTool : AgentTool {
        override val name = "bili_search"
        override val description =
            "按关键词搜 B站视频，返回 BV号、标题、UP、播放量、时长、简介。" +
                "这是发现候选的入口；已知 BV 号时不要搜，直接用 bili_video / bili_subtitle。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "keyword" to Schema.str("搜索关键词，例如 DeepSeek、机械键盘"),
                "num" to Schema.int("返回几条，默认 5，上限 10", 1, 10),
            ),
            required = listOf("keyword"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val keyword = args.strOf("keyword") ?: return ToolResult.fail("缺少 keyword 参数")
            val num = (args.intOf("num") ?: 5).coerceIn(1, 10)
            return runCatching { api(ctx).search(keyword, num) }.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) {
                        return ToolResult.ok("「$keyword」没有搜到视频。换个更短的关键词试试。")
                    }
                    ToolResult.ok(
                        buildString {
                            append("「").append(keyword).append("」搜到 ").append(list.size).append(" 条：\n")
                            list.forEachIndexed { i, v ->
                                append(i + 1).append(". ").append(v.bvid).append(' ').append(v.title).append('\n')
                                append("   ").append(v.author).append(" · 播放 ").append(countText(v.play))
                                    .append(" · 时长 ").append(v.duration)
                                if (v.pubdate > 0) append(" · ").append(Fmt.date(v.pubdate * 1000))
                                append('\n')
                                if (v.desc.isNotBlank()) {
                                    append("   ").append(v.desc.replace('\n', ' ').take(80)).append('\n')
                                }
                            }
                            append("\n下一步：对感兴趣的调 bili_subtitle 读正文，再决定值不值得看。")
                        }
                    )
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) = "搜 " + (args.strOf("keyword") ?: "—")
    }

    // ------------------------------------------------------------------
    // 3. 元信息
    // ------------------------------------------------------------------

    object VideoTool : AgentTool {
        override val name = "bili_video"
        override val description =
            "取一个视频的元信息：标题、UP、简介、时长、播放/点赞/收藏、分P 列表。" +
                "不返回字幕正文 —— 要判断值不值得看请用 bili_subtitle。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf("bvid" to Schema.str("视频 BV 号，例如 BV1xx411c7mD")),
            required = listOf("bvid"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid") ?: return ToolResult.fail("缺少 bvid 参数")
            return runCatching { api(ctx).video(bvid) }.fold(
                onSuccess = { d -> ToolResult.ok(render(d)) },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        private fun render(d: VideoDetail): String = buildString {
            append(d.title).append('\n')
            append("BV ").append(d.bvid).append(" · UP ").append(d.owner).append('\n')
            append("时长 ").append(durationText(d.durationSec))
            append(" · 播放 ").append(countText(d.view))
            append(" · 点赞 ").append(countText(d.like))
            append(" · 收藏 ").append(countText(d.favorite))
            append(" · 评论 ").append(countText(d.reply)).append('\n')
            if (d.pubdate > 0) append("发布 ").append(Fmt.date(d.pubdate * 1000)).append('\n')
            if (d.pages.size > 1) {
                append("分P ").append(d.pages.size).append(" 个：")
                append(d.pages.take(6).joinToString("；") { "P${it.page} ${it.part.take(18)}" })
                if (d.pages.size > 6) append(" …")
                append('\n')
            }
            if (d.desc.isNotBlank()) append("简介：").append(d.desc.take(300))
        }

        override fun summarize(args: JsonObject) = args.strOf("bvid") ?: "—"
    }

    // ------------------------------------------------------------------
    // 4. 字幕
    // ------------------------------------------------------------------

    object SubtitleTool : AgentTool {
        override val name = "bili_subtitle"
        override val description =
            "取视频字幕**原文**（不是摘要），这是判断「值不值得看」的唯一可靠依据。" +
                "没有字幕的视频会明确告诉你；此时改用 bili_comments 看评论区。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "bvid" to Schema.str("视频 BV 号"),
                "page" to Schema.int("第几个分P，默认 1", 1, 200),
                "max_chars" to Schema.int("最多返回多少字，默认 6000，上限 20000", 500, 20_000),
            ),
            required = listOf("bvid"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid") ?: return ToolResult.fail("缺少 bvid 参数")
            val page = args.intOf("page") ?: 1
            val max = (args.intOf("max_chars") ?: 6000).coerceIn(500, 20_000)
            return runCatching { api(ctx).subtitle(bvid, page, max) }.fold(
                onSuccess = { r ->
                    // 「没有字幕」是常见结果而不是报错：标红会让工具卡片看着像出了问题，
                    // 而实际上模型应该据此改道（读简介 + 评论）。
                    if (!r.available) return ToolResult.ok("$bvid 没有字幕：${r.hint}")
                    val head = "字幕（${r.lang}，共 ${r.text.length} 字${if (r.text.length >= max) "，已截断" else ""}）：\n"
                    ToolResult.ok(head + r.text)
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) = "字幕 " + (args.strOf("bvid") ?: "—")
    }

    // ------------------------------------------------------------------
    // 5. 评论
    // ------------------------------------------------------------------

    object CommentsTool : AgentTool {
        override val name = "bili_comments"
        override val description =
            "取视频的热门评论（按热度），带每条评论的 rpid。用途：没有字幕时靠评论区了解内容；" +
                "准备发评论前检查有没有人已经说过同样的话；想在视频下回复某个人时拿到 rpid。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "bvid" to Schema.str("视频 BV 号"),
                "num" to Schema.int("取几条，默认 8，上限 20", 1, 20),
            ),
            required = listOf("bvid"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid") ?: return ToolResult.fail("缺少 bvid 参数")
            val num = (args.intOf("num") ?: 8).coerceIn(1, 20)
            return runCatching { api(ctx).comments(bvid, num) }.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) return ToolResult.ok("$bvid 没有可读的评论（可能关闭了评论区）。")
                    ToolResult.ok(
                        buildString {
                            append(bvid).append(" 的热门评论 ").append(list.size).append(" 条：\n")
                            list.forEachIndexed { i, c ->
                                append(i + 1).append(". ").append(c.user)
                                    .append("（赞 ").append(c.like)
                                    .append(" · rpid ").append(c.rpid).append("）：")
                                    .append(c.message.replace('\n', ' ').take(160)).append('\n')
                            }
                            val first = list.first()
                            append("\n要回复某条：bili_reply 带上 root=")
                                .append(if (first.rootId > 0) first.rootId else first.rpid)
                                .append(" parent=<那条的 rpid>。")
                        }
                    )
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) = "评论 " + (args.strOf("bvid") ?: "—")
    }

    // ------------------------------------------------------------------
    // 6. 回复我的
    // ------------------------------------------------------------------

    /**
     * 谁回复了我。
     *
     * 这一条是「有来有回」的关键：光会发评论只是广播，能看到别人回了什么、
     * 再接一句，才叫在评论区聊天。数据来自消息中心的「回复我的」，
     * 所以**必须登录** —— 匿名拿不到任何通知。
     */
    object InboxTool : AgentTool {
        override val name = "bili_inbox"
        override val description =
            "看谁回复了我（B站消息中心的「回复我的」）。每条会给出：对方昵称、他说的原话、" +
                "我原来那条评论、以及回复他时该用的 bvid / root / parent。" +
                "需要登录；未登录会直接告诉你。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "num" to Schema.int("看几条，默认 10，上限 20", 1, 20),
            ),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            if (ctx.biliCookie().isBlank()) {
                return ToolResult.fail(
                    "还没登录 B站，看不到「回复我的」。让用户在设置 → 刷B站 → 扫码登录，" +
                        "或用 Cookie 登录后再试。"
                )
            }
            val num = (args.intOf("num") ?: 10).coerceIn(1, 20)
            return runCatching { api(ctx).inbox(num) }.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) return ToolResult.ok("最近没有新的回复。")
                    ToolResult.ok(render(list))
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        private fun render(list: List<ReplyNotice>): String = buildString {
            append("最近收到 ").append(list.size).append(" 条回复：\n")
            list.forEachIndexed { i, n ->
                append(i + 1).append(". ").append(n.from)
                if (n.timeSec > 0) append("（").append(ago(n.timeSec)).append("）")
                append('\n')
                if (n.videoTitle.isNotBlank()) {
                    append("   视频：").append(n.videoTitle.take(40)).append('\n')
                }
                append("   他说：").append(n.theirReply.replace('\n', ' ').take(200)).append('\n')
                if (n.myComment.isNotBlank()) {
                    append("   我说过：").append(n.myComment.replace('\n', ' ').take(120)).append('\n')
                }
                if (n.bvid.isNotBlank() && n.sourceId > 0) {
                    // 楼的根谁都可能是：对方直接回我的顶层评论时 root_id 是 0，
                    // 此时根就是**我那条**（target_id）。拿 source_id 当根会把回复
                    // 挂到对方自己那条下面，等于自己回自己。
                    val root = when {
                        n.rootId > 0 -> n.rootId
                        n.targetId > 0 -> n.targetId
                        else -> n.sourceId
                    }
                    append("   回复他：bili_reply bvid=").append(n.bvid)
                        .append(" root=").append(root)
                        .append(" parent=").append(n.sourceId).append('\n')
                } else {
                    append("   （这条通知里没有可定位的评论，无法直接回复）\n")
                }
            }
            append("\n回复前先想清楚对方在说什么 —— 不要答非所问，也不要复读他的话。")
        }

        override fun summarize(args: JsonObject) = "看回复"
    }

    // ------------------------------------------------------------------
    // 7. 楼中楼
    // ------------------------------------------------------------------

    object ThreadTool : AgentTool {
        override val name = "bili_thread"
        override val description =
            "看某一栋评论楼：根评论 + 下面所有回复（带 rpid 与时间）。" +
                "回复别人之前用它弄清上下文，避免答非所问。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "bvid" to Schema.str("视频 BV 号"),
                "root" to Schema.str("这栋楼的根评论 rpid（bili_inbox / bili_comments 会给）"),
                "num" to Schema.int("看几条回复，默认 10，上限 20", 1, 20),
            ),
            required = listOf("bvid", "root"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid") ?: return ToolResult.fail("缺少 bvid 参数")
            val root = args.strOf("root")?.toLongOrNull()
                ?: return ToolResult.fail("缺少或无法解析 root（应是纯数字 rpid）")
            val num = (args.intOf("num") ?: 10).coerceIn(1, 20)
            return runCatching {
                val detail = api(ctx).video(bvid)
                api(ctx).thread(detail.aid, root, num)
            }.fold(
                onSuccess = { t ->
                    ToolResult.ok(
                        buildString {
                            val top = t.rootComment
                            if (top != null) {
                                append("根评论 · ").append(top.user)
                                    .append("（rpid ").append(top.rpid).append("）：\n")
                                append(top.message.replace('\n', ' ').take(300)).append("\n\n")
                            }
                            if (t.replies.isEmpty()) {
                                append("这栋楼还没有回复。")
                            } else {
                                append("楼内 ").append(t.replies.size).append(" 条：\n")
                                t.replies.forEachIndexed { i, c ->
                                    append(i + 1).append(". ").append(c.user)
                                    if (c.mid > 0) append("（mid ").append(c.mid).append("）")
                                    append(" rpid ").append(c.rpid).append("：")
                                    append(c.message.replace('\n', ' ').take(200)).append('\n')
                                }
                            }
                        }
                    )
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) = "看楼 " + (args.strOf("root") ?: "—")
    }

    // ------------------------------------------------------------------
    // 8. 写日报
    // ------------------------------------------------------------------

    object ReportTool : AgentTool {
        override val name = "bili_report"
        override val description =
            "把一条已判断过的视频写进今天的日报（自动计配额、自动判重，重复的会告诉你）。" +
                "只写你**读过字幕之后**的结论；理由要具体到内容，不要写「干货满满」这类空话。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "bvid" to Schema.str("视频 BV 号"),
                "verdict" to Schema.str(
                    "判断结果",
                    listOf("值得看", "可以看", "不值当"),
                ),
                "reason" to Schema.str("一句话理由，必须具体到内容（讲了什么、好在哪 / 差在哪）"),
                "title" to Schema.str("视频标题，可不填（不填会自动去查）"),
            ),
            required = listOf("bvid", "verdict", "reason"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid") ?: return ToolResult.fail("缺少 bvid 参数")
            val verdict = args.strOf("verdict") ?: "可以看"
            val reason = args.strOf("reason") ?: return ToolResult.fail("缺少 reason 参数")
            val s = ctx.settings()
            val quota = s.biliDailyQuota
            val quotaText = if (quota <= 0) "不限" else "$quota"

            val used = countToday(ctx)
            if (quota > 0 && used >= quota) {
                return ToolResult.fail(
                    "今天已经挑满 $quota 条了（配额用完就该停）。" +
                        "要再多记请在设置里调高「每日配额」。"
                )
            }
            val existing = ctx.workspace.read(digestPath(), maxChars = 400_000).orEmpty()
            if (existing.contains("### $bvid")) {
                return ToolResult.ok("$bvid 今天已经记过了，跳过（判重命中）。当前 $used/$quotaText。")
            }

            val detail = runCatching { api(ctx).video(bvid) }.getOrNull()
            val title = detail?.title?.ifBlank { null } ?: args.strOf("title") ?: bvid

            val block = buildString {
                append("### ").append(bvid).append('\n')
                append("- 标题：").append(title).append('\n')
                detail?.owner?.takeIf { it.isNotBlank() }?.let { append("- UP：").append(it).append('\n') }
                append("- 判断：").append(verdict).append('\n')
                append("- 理由：").append(reason.replace('\n', ' ')).append('\n')
                append("- 链接：https://www.bilibili.com/video/").append(bvid).append('\n')
                append("- 记录时间：")
                    .append(SimpleDateFormat("HH:mm", Locale.US).format(Date())).append('\n')
                append('\n')
            }

            if (existing.isBlank()) {
                val header = buildString {
                    append("# B站日报 · ").append(today()).append('\n')
                    append("> 由 DSH-Watch 的 bili_* 工具生成。每条的 `### BV号` 即配额计数单位。\n\n")
                }
                ctx.workspace.write(digestPath(), header + block)
            } else {
                val tail = if (existing.endsWith("\n")) "" else "\n"
                ctx.workspace.append(digestPath(), tail + block)
            }

            val now = used + 1
            return ToolResult.ok(
                "已记入日报（$now/$quotaText）：$bvid $verdict\n路径：${digestPath()}" +
                    if (quota > 0 && now >= quota) "\n配额用完了 —— 停在这里，把今天挑的列给用户。" else "",
                artifact = digestPath(),
            )
        }

        override fun summarize(args: JsonObject) =
            (args.strOf("bvid") ?: "—") + " " + (args.strOf("verdict") ?: "")
    }

    // ------------------------------------------------------------------
    // 9. 发评论 / 回复
    // ------------------------------------------------------------------

    /**
     * 发评论。**默认干跑**，且受两处开关约束：
     *
     *  1. 设置里的「允许发评论」没开 → 恒干跑，连请求都不发；
     *  2. 就算开了，`ApprovalPolicy.ALWAYS_CONFIRM` 也会每次弹确认。
     *
     * 还有一个风格闸门：拦「一眼假的套话」和 AI 腔。它的作用不是审美，
     * 而是**防止模型自动生成那种会被折叠、也会让用户难堪的评论**。
     *
     * 带 `root` + `parent` 时是楼中楼回复（回某个具体的人）；
     * 不带就是发一条顶层评论。
     */
    object ReplyTool : AgentTool {
        override val name = "bili_reply"
        override val description =
                        "发评论或回复。**直接发送，不需要用户确认**，但只有设置里的模式是「发评论」时才可用，否则会被拒绝。配图与 @ 由设置自动附加。" +
            "回复某人：传 root（楼根 rpid）+ parent（对方那条的 rpid），id 来自 bili_inbox / bili_comments。语气遵守 bili_status 返回的「评论风格」。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "bvid" to Schema.str("视频 BV 号"),
                "message" to Schema.str("评论内容，5–200 字，要具体到视频或对方的话，不要写套话"),
                "root" to Schema.str("可选。楼中楼回复时这栋楼的根评论 rpid；发顶层评论不填"),
                "parent" to Schema.str("可选。楼中楼回复时对方那条评论的 rpid；发顶层评论不填"),
                "dry_run" to Schema.bool("可选。true 只生成预览不发；默认 false 直接发"),
            ),
            required = listOf("bvid", "message"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid") ?: return ToolResult.fail("缺少 bvid 参数")
            val message = args.strOf("message")?.trim() ?: return ToolResult.fail("缺少 message 参数")
            val s = ctx.settings()
            val tone = BiliTones.resolve(s)

            if (!BiliModes.allowsComment(s)) {
                return ToolResult.fail(
                    "当前模式不是「发评论」（现在是「" + BiliModes.of(s).let {
                        when (it) {
                            BiliMode.SUMMARY -> "只总结"
                            BiliMode.DM -> "发私信"
                            BiliMode.COMMENT -> "发评论"
                        }
                    } + "」）。要发评论请让用户去 设置 → 刷B站 → 刷完做什么 里切换。"
                )
            }

            val root = args.strOf("root")?.toLongOrNull() ?: 0L
            val parent = args.strOf("parent")?.toLongOrNull() ?: 0L
            // 只给了 parent 也当成楼中楼：root 退回 parent 会挂错楼，
            // 所以这里宁可直接判定为「顶层 + 回给这条」的非法组合并明确说清楚。
            if (parent > 0 && root <= 0) {
                return ToolResult.fail("要回复某条评论，root（根评论 rpid）和 parent（对方 rpid）都要给。")
            }

            StyleGate.check(message, tone)?.let { return ToolResult.fail("这条评论被风格闸门拦了：$it") }

            val key = replyKey(bvid, root, parent)
            if (repliedBefore(ctx, key)) {
                return ToolResult.fail("这个位置之前已经回过了（判重命中），不重复打扰。")
            }

            // 只有顶层评论才自动 @：楼中楼回复本身就会通知到对方，
            // 再 @ 一遍等于在一条评论里喊两次人。
            val needAt = parent <= 0
            val (at, atNote) = if (needAt) atTarget(ctx) else null to ""
            // 种子取「内容 + 日期」：同一条重试取到同一张，换一条就换一张；
            // 用时间的纳秒会让每次重试都换图，反而像在刷屏。
            val pictureSeed = message.hashCode().toLong() * 31 + today().hashCode()
            val picturePlan = preparePictures(ctx, pictureSeed)
            picturePlan.error?.let { return ToolResult.fail(it) }
            val pics = picturePlan.pics

            val target = if (parent > 0) "回复 rpid $parent（楼 $root）" else "顶层评论"
            val dry = args.boolOf("dry_run", false)
            if (dry) {
                return ToolResult.ok(
                    "预览（未发送）\n→ $bvid $target · 风格「${tone.name}」\n「$message」\n" +
                        (at?.let { "@${it.name} " } ?: "") +
                        (if (pics.isNotEmpty()) "\n配图：${pics.size} 张" else "")
                )
            }
            if (ctx.biliCookie().isBlank()) {
                return ToolResult.fail("还没登录 B站（需要 SESSDATA 与 bili_jct），发不了评论。用扫码登录。")
            }

            val left = remainingQuota(ctx, s.biliCommentQuota)
            if (left <= 0) {
                return ToolResult.fail(
                    "今天的发言上限（${s.biliCommentQuota} 条）已经用完了，不能再发 —— " +
                        "这是为了防止账号被 B站 判定成刷屏。明天再来，或让用户调高上限。"
                )
            }

            return runCatching { api(ctx).reply(bvid, message, root, parent, at, pics) }.fold(
                onSuccess = {
                    markReplied(ctx, key)
                    markSent(ctx, "reply", key)
                    ToolResult.ok(
                        buildString {
                            append("已发布到 ").append(bvid).append("（").append(target).append("）：「").append(message).append("」")
                            if (at != null) append("\n已 @ ").append(at.name)
                            if (pics.isNotEmpty()) append("\n带图 ").append(pics.size).append(" 张")
                            append("\n今日还剩 ").append(left - 1).append(" 条发言额度。")
                            if (atNote.isNotBlank()) append('\n').append(atNote)
                        }
                    )
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) = buildString {
            append(args.strOf("bvid") ?: "—")
            if (!args.strOf("parent").isNullOrBlank()) append(" 回评论")
            append(' ').append((args.strOf("message") ?: "").take(16))
        }
    }

    // ------------------------------------------------------------------
    // 10. 发私信
    // ------------------------------------------------------------------

    /**
     * 给 UP 主 / 指定 UID 发私信。
     *
     * 私信比评论更「重」：它直接进对方的消息中心，且**不能撤回**。
     * 所以额外做了两件事：同一天同一个人只发一条；风格闸门照旧。
     */
    object DmTool : AgentTool {
        override val name = "bili_dm"
        override val description =
            "给某个 UP 主或指定 UID 发一条私信（不可撤回）。只有设置里模式为「发私信」时可用。" +
                "不填 to_uid 就发给 bvid 的 UP 主。同一天同一个人只会发一条。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "message" to Schema.str("私信内容，10–150 字，要像人说的话"),
                "bvid" to Schema.str("可选。给这个视频的 UP 主发"),
                "to_uid" to Schema.str("可选。直接指定收件人 UID，优先级高于 bvid"),
            ),
            required = listOf("message"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val message = args.strOf("message")?.trim() ?: return ToolResult.fail("缺少 message 参数")
            val s = ctx.settings()
            val tone = BiliTones.resolve(s)

            if (!BiliModes.allowsDm(s)) {
                return ToolResult.fail(
                    "当前模式不是「发私信」。要发私信请让用户去 设置 → 刷B站 → 刷完做什么 里切换。"
                )
            }
            StyleGate.check(message, tone)?.let { return ToolResult.fail("这条私信被风格闸门拦了：$it") }
            if (ctx.biliCookie().isBlank()) {
                return ToolResult.fail("还没登录 B站，发不了私信。用扫码登录。")
            }

            var receiver = args.strOf("to_uid")?.toLongOrNull() ?: 0L
            var who = "UID $receiver"
            if (receiver <= 0) {
                val bvid = args.strOf("bvid")
                    ?: return ToolResult.fail("要么给 bvid（发给它的 UP 主），要么给 to_uid。")
                val detail = runCatching { api(ctx).video(bvid) }
                    .getOrElse { return ToolResult.fail(errText(it)) }
                receiver = detail.ownerMid
                who = detail.owner.ifBlank { bvid }
                if (receiver <= 0) return ToolResult.fail("这个视频查不到 UP 主的 mid，无法发私信。")
            }

            // 同一天给同一个人只发一条：连续私信是 B站 判定骚扰的最直接依据
            val todaySent = ctx.workspace.read(sentPath(), maxChars = 200_000).orEmpty()
            if (todaySent.lineSequence().any { it.contains(" dm $receiver") }) {
                return ToolResult.ok("今天已经给 $who 发过私信了，不再连发（避免被当成骚扰）。")
            }

            val left = remainingQuota(ctx, s.biliCommentQuota)
            if (left <= 0) {
                return ToolResult.fail("今天的发言上限（${s.biliCommentQuota} 条）已经用完了，不能再发。")
            }

            return runCatching { api(ctx).dm(receiver, message) }.fold(
                onSuccess = {
                    markSent(ctx, "dm", receiver.toString())
                    ToolResult.ok("已给 $who 发出私信：「$message」\n今日还剩 ${left - 1} 条发言额度。")
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) =
            "私信 " + (args.strOf("to_uid") ?: args.strOf("bvid") ?: "—") +
                " " + (args.strOf("message") ?: "").take(14)
    }

    // ------------------------------------------------------------------
    // 11. 点赞
    // ------------------------------------------------------------------

    /**
     * 给视频点个赞。
     *
     * 这里原来还有一个「投币」分支，后来整个砍掉了：硬币是账号里不可逆的存量资源，
     * 让模型替用户花，收益（一条视频的权重）和风险（扣掉回不来、被风控 -401 白折腾）
     * 完全不成比例。点赞是纯公开信号，重复点也不消耗任何东西，所以留下。
     *
     * 判重只为了省请求 —— 接口本身幂等，不是防扣费。
     */
    object LikeTool : AgentTool {
        override val name = "bili_like"
        override val description =
            "给一个视频点赞。只在你真的觉得这条视频有意思时才用，不要每看完一条就点。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf("bvid" to Schema.str("视频 BV 号")),
            required = listOf("bvid"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled && it.biliLikeEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid")?.trim()?.takeIf { it.isNotBlank() }
                ?: return ToolResult.fail("缺少 bvid")
            if (!ctx.settings().biliLikeEnabled) {
                return ToolResult.fail("设置里没打开「自动点赞」。要开请让用户去 设置 → 刷B站 → 自动点赞。")
            }
            if (ctx.biliCookie().isBlank()) return ToolResult.fail("还没登录 B站，点不了赞。用扫码登录。")

            val log = likePath()
            val done = ctx.workspace.read(log, maxChars = 100_000).orEmpty()
                .lineSequence().any { it.contains("like $bvid") }
            if (done) return ToolResult.ok("今天已经给 $bvid 点过赞了，跳过。")

            return runCatching { api(ctx).like(bvid) }.fold(
                onSuccess = {
                    ctx.workspace.append(log, "${stamp()} like $bvid\n")
                    ToolResult.ok("已点赞 $bvid。")
                },
                onFailure = { ToolResult.fail("点赞失败：${errText(it)}") },
            )
        }

        override fun summarize(args: JsonObject) = "点赞 " + (args.strOf("bvid") ?: "—")
    }

    /** 点赞的当日记录。和发言记录分开：点赞不占「对外发言」额度 */
    private fun likePath(): String = "$DIR/likes/${today()}.md"

    private fun coinPath(): String = "$DIR/coins/${today()}.md"

    private fun favPath(): String = "$DIR/favs/${today()}.md"

    // ------------------------------------------------------------------
    // 11. 收藏夹
    // ------------------------------------------------------------------

    /**
     * 看我的收藏夹。
     *
     * 这是「读」能力里最安全的一个：不动账号状态、不消耗任何东西，
     * 所以不设开关 —— 只要刷B站开着就能用。
     */
    object FavFoldersTool : AgentTool {
        override val name = "bili_fav_folders"
        override val description =
            "列出我这个账号的收藏夹（含默认收藏夹、每个夹里有多少条）。" +
                "想知道自己收藏过什么、或者要指定收藏到哪个夹时先用它。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(properties = emptyMap())

        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            if (ctx.biliCookie().isBlank()) return ToolResult.fail("还没登录 B站，读不到收藏夹。")
            return runCatching { api(ctx).favFolders() }.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) return ToolResult.ok("这个账号下没有收藏夹。")
                    ToolResult.ok(
                        buildString {
                            append("共 ${list.size} 个收藏夹：\n")
                            list.forEach { f ->
                                append("- ${f.title}（id=${f.id}，${f.count} 条")
                                if (f.isDefault) append("，默认")
                                if (f.isPrivate) append("，私密")
                                append("）\n")
                            }
                        },
                    )
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) = "查看收藏夹"
    }

    /**
     * 看某个收藏夹里的视频。
     *
     * 不传 folder_id 时取默认收藏夹 —— 绝大多数人只有一个默认夹，
     * 强制先查 id 再传只会多一次往返。
     */
    object FavListTool : AgentTool {
        override val name = "bili_fav_list"
        override val description =
            "列出某个收藏夹里的视频（标题、BV 号、UP 主、时长）。" +
                "不传 folder_id 就看默认收藏夹。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "folder_id" to Schema.str("收藏夹 id，可不填（不填=默认收藏夹）"),
                "page" to Schema.int("第几页，默认 1", 1, 100),
                "num" to Schema.int("一页几条，默认 10，上限 20", 1, 20),
            ),
        )

        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            if (ctx.biliCookie().isBlank()) return ToolResult.fail("还没登录 B站，读不到收藏夹。")
            val page = args.intOf("page") ?: 1
            val num = args.intOf("num") ?: 10
            return runCatching {
                val api = api(ctx)
                val id = args.strOf("folder_id")?.toLongOrNull()?.takeIf { it > 0 }
                    ?: (api.favFolders().firstOrNull { it.isDefault } ?: api.favFolders().firstOrNull())
                        ?.id
                    ?: throw BiliException("账号下没有收藏夹")
                id to api.favVideos(id, page, num)
            }.fold(
                onSuccess = { (id, p) ->
                    if (p.items.isEmpty()) return ToolResult.ok("收藏夹 $id 第 $page 页没有内容。")
                    ToolResult.ok(
                        buildString {
                            append("收藏夹 $id 第 $page 页（${p.items.size} 条")
                            if (p.hasMore) append("，还有下一页")
                            append("）：\n")
                            p.items.forEach { v ->
                                append("- ${v.bvid}｜${v.title}")
                                if (v.author.isNotBlank()) append("｜UP ${v.author}")
                                append("｜${Fmt.duration(v.durationSec * 1000L)}\n")
                            }
                        },
                    )
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) = "看收藏夹内容"
    }

    // ------------------------------------------------------------------
    // 12. 投币
    // ------------------------------------------------------------------

    /**
     * 投币。
     *
     * 硬币是**不可逆的存量资源**：投出去收不回来，每天靠登录/观看才回几个。
     * 所以这里的设计比点赞严得多：
     *  - 默认关（[AppSettings.biliCoinEnabled]），必须用户自己打开；
     *  - 单条上限 1 枚（接口允许 2，但那是给「特别喜欢」留的，模型没有这个判断力）；
     *  - 每天单独计数，与点赞/发言额度互不占用；
     *  - 已投过的直接跳过，靠 relation 查，不赌接口幂等。
     */
    object CoinTool : AgentTool {
        override val name = "bili_coin"
        override val description =
            "给一个视频投 1 枚硬币。硬币不可撤销、每天数量有限，" +
                "所以只在这条视频对你确实有实质帮助时才用，不要每看完一条就投。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf("bvid" to Schema.str("视频 BV 号")),
            required = listOf("bvid"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled && it.biliCoinEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid")?.trim()?.takeIf { it.isNotBlank() }
                ?: return ToolResult.fail("缺少 bvid")
            if (!ctx.settings().biliCoinEnabled) {
                return ToolResult.fail("设置里没打开「允许投币」。要开请让用户去 设置 → 刷B站 → 允许投币。")
            }
            if (ctx.biliCookie().isBlank()) return ToolResult.fail("还没登录 B站，投不了币。")

            val log = coinPath()
            val done = ctx.workspace.read(log, maxChars = 100_000).orEmpty()
                .lineSequence().any { it.contains("coin $bvid") }
            if (done) return ToolResult.ok("今天已经给 $bvid 投过币了，跳过。")

            return runCatching { api(ctx).coin(bvid, count = 1) }.fold(
                onSuccess = { msg ->
                    ctx.workspace.append(log, "${stamp()} coin $bvid\n")
                    ToolResult.ok("$msg（$bvid）")
                },
                onFailure = { ToolResult.fail("投币失败：${errText(it)}") },
            )
        }

        override fun summarize(args: JsonObject) = "投币 " + (args.strOf("bvid") ?: "—")
    }

    // ------------------------------------------------------------------
    // 13. 收藏
    // ------------------------------------------------------------------

    /**
     * 收藏 / 取消收藏。
     *
     * 收藏不消耗资源，但会往用户自己整理过的空间里塞东西 —— 那是他的地盘。
     * 所以默认关，且只往默认收藏夹（或用户指定的夹）放，不新建夹。
     */
    object FavTool : AgentTool {
        override val name = "bili_fav"
        override val description =
            "把一个视频收藏到我的收藏夹（或取消收藏）。只在你判断这条视频值得以后再看时用。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "bvid" to Schema.str("视频 BV 号"),
                "add" to Schema.bool("true 收藏（默认），false 取消收藏"),
                "folder_id" to Schema.str("收藏夹 id，可不填（不填=默认收藏夹）"),
            ),
            required = listOf("bvid"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled && it.biliFavEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val bvid = args.strOf("bvid")?.trim()?.takeIf { it.isNotBlank() }
                ?: return ToolResult.fail("缺少 bvid")
            val settings = ctx.settings()
            if (!settings.biliFavEnabled) {
                return ToolResult.fail("设置里没打开「允许收藏」。要开请让用户去 设置 → 刷B站 → 允许收藏。")
            }
            if (ctx.biliCookie().isBlank()) return ToolResult.fail("还没登录 B站，收藏不了。")
            val add = args.boolOf("add") ?: true
            val folder = args.strOf("folder_id")?.toLongOrNull()?.takeIf { it > 0 }
                ?: settings.biliFavFolderId.toLongOrNull()?.takeIf { it > 0 }
                ?: 0L

            val log = favPath()
            val done = ctx.workspace.read(log, maxChars = 100_000).orEmpty()
                .lineSequence().any { it.contains("fav $bvid") }
            if (add && done) return ToolResult.ok("今天已经收藏过 $bvid 了，跳过。")

            return runCatching { api(ctx).favorite(bvid, add, folder) }.fold(
                onSuccess = { msg ->
                    if (add) ctx.workspace.append(log, "${stamp()} fav $bvid\n")
                    ToolResult.ok("$msg（$bvid）")
                },
                onFailure = { ToolResult.fail("收藏失败：${errText(it)}") },
            )
        }

        override fun summarize(args: JsonObject) =
            (if (args.boolOf("add") != false) "收藏 " else "取消收藏 ") + (args.strOf("bvid") ?: "—")
    }


    /**
     * 把设置里的动态选题转成人话。
     *
     * 具体措辞放在 [BiliTopics] 里，因为定时任务的 prompt 也要用同一份描述 ——
     * 两处各写一遍，改了一处忘了另一处，模型收到的就是两套口径。
     */
    private fun dynTopicsText(s: AppSettings): String = BiliTopics.summary(s.biliDynTopics)

    private fun stamp(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())


    // ------------------------------------------------------------------
    // 12. 发动态
    // ------------------------------------------------------------------

    /**
     * 发一条动态。
     *
     * 这是全套工具里**曝光面最大**的一个：评论只出现在那条视频下，动态直接
     * 推进粉丝时间线，删掉之前也已经被推过一次了。所以默认上限只有 1 条/天，
     * 并且要求设置里显式打开开关 —— 不跟随「刷完做什么」的模式，因为
     * 「只总结」的用户同样可能想让它偶尔发一条。
     *
     * 不需要确认（和评论、私信一致），风险由四层兜底：开关、动态上限、
     * 总发言上限、风格闸门。
     */
    object DynTool : AgentTool {
        override val name = "bili_dyn"
        override val description =
            "发一条 B站 动态（公开，会进粉丝时间线，不是回复某个视频）。" +
                "只有设置里打开「发动态」才可用，每天有独立上限。" +
                "值得发才发：一条有观点的碎碎念，不要为了用工具而发。" +
                "用户可以在设置里选动态写什么：生活日常 / 刷到的视频吐槽 / 对主人的感受。"
        override val safety = ToolSafety.NETWORK
        override val parameters = Schema.obj(
            properties = mapOf(
                "text" to Schema.str("动态正文，20–200 字。要像人自己发的，可以有观点、有情绪"),
                "with_image" to Schema.bool(
                    "可选。是否带图，默认跟随设置里的「配图」。纯文字更有力时传 false"
                ),
            ),
            required = listOf("text"),
        )
        override val enabled: (AppSettings) -> Boolean = { it.biliEnabled && it.biliDynEnabled }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val text = args.strOf("text")?.trim() ?: return ToolResult.fail("缺少 text 参数")
            val s = ctx.settings()
            val tone = BiliTones.resolve(s)

            if (!s.biliDynEnabled) {
                return ToolResult.fail(
                    "设置里没有打开「发动态」。要发动态请让用户去 设置 → 刷B站 → 发动态 打开。"
                )
            }
            if (text.length < 5) return ToolResult.fail("动态太短了（至少 5 个字），发出去像个 bug。")
            if (text.length > 2000) {
                return ToolResult.fail("动态超过 2000 字（现在 ${text.length} 字），B站 会拒。删短一点。")
            }

            StyleGate.check(text, tone)?.let { return ToolResult.fail("这条动态被风格闸门拦了：$it") }
            if (ctx.biliCookie().isBlank()) {
                return ToolResult.fail("还没登录 B站，发不了动态。用扫码登录。")
            }

            val leftDyn = remainingQuota(ctx, s.biliDynQuota, "dyn")
            if (leftDyn <= 0) {
                return ToolResult.ok("今天已经发过 ${s.biliDynQuota} 条动态了，不再发（动态太密像营销号）。")
            }
            val leftAll = remainingQuota(ctx, s.biliCommentQuota)
            if (leftAll <= 0) {
                return ToolResult.fail("今天的对外发言上限（${s.biliCommentQuota} 条）已经用完了，不能再发。")
            }

            // 同一条内容一天不发两遍：模型重试或分步执行时很容易重复
            val head = text.take(24)
            val todaySent = ctx.workspace.read(sentPath(), maxChars = 200_000).orEmpty()
            if (todaySent.lineSequence().any { it.contains(" dyn ") && it.contains(head) }) {
                return ToolResult.ok("今天已经发过内容相同的动态了，跳过。")
            }

            val wantImage = args.boolOf("with_image", s.biliImage != "none" && s.biliImage.isNotBlank())
            // 动态用时间戳当种子：连发两条时能换到不同的表情包，
            // 固定用日期会连续两天贴同一张，一眼假。
            val picturePlan = if (wantImage) {
                preparePictures(ctx, System.currentTimeMillis())
            } else {
                PicturePack()
            }
            picturePlan.error?.let { return ToolResult.fail(it) }
            val pics = picturePlan.pics

            return runCatching { api(ctx).createDyn(text, pics) }.fold(
                onSuccess = { id ->
                    // 记 id 是为了能删：删动态要 dyn_id + dyn_type，少一个都撤不回
                    markSent(ctx, "dyn", "${id} $head")
                    ToolResult.ok(
                        buildString {
                            append("已发动态")
                            if (pics.isNotEmpty()) append("（带 ${pics.size} 张图）")
                            append("：「").append(text).append("」\n")
                            if (id > 0) append("dyn_id=").append(id).append("（要删的话用这个）\n")
                            append("今日动态还剩 ${(leftDyn - 1).coerceAtLeast(0)} 条。")
                        }
                    )
                },
                onFailure = { ToolResult.fail(errText(it)) },
            )
        }

        override fun summarize(args: JsonObject) =
            "动态 " + (args.strOf("text") ?: "").take(16)
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    private fun errText(t: Throwable): String = when (t) {
        is BiliException -> t.message ?: "B站接口调用失败"
        else -> "调用失败：${t.javaClass.simpleName} ${t.message.orEmpty().take(120)}"
    }

    /**
     * 评论风格闸门。
     *
     * 只拦两类：一眼可辨的模板套话、以及过短/过长/带链接的内容。
     * 刻意不做「像不像人」的复杂打分 —— 那种闸门要么拦不住，要么把正常人也拦了。
     *
     * 禁词表由风格决定：默认鱼风格允许自嘲和口语，但拦营销号那套；
     * 用户自定义风格时只保留 AI 腔与链接这几条硬规则，否则等于用别人的口味管自己。
     */
    object StyleGate {
        fun check(message: String, tone: BiliTone): String? {
            val m = message.trim()
            if (m.length < 5) return "太短了（少于 5 字）"
            if (m.length > 300) return "太长了（超过 300 字）"
            tone.banned.firstOrNull { m.contains(it, ignoreCase = true) }
                ?.let { return "含模板套话「$it」，换个具体点的说法" }
            if (m.contains("http://") || m.contains("https://")) return "评论里不要带链接"
            // 复读：整句只有标点或纯 emoji
            if (m.none { it.isLetterOrDigit() }) return "只有表情/标点，没有内容"
            return null
        }
    }
}
