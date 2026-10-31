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

    private suspend fun countSentToday(ctx: ToolContext): Int =
        ctx.workspace.read(sentPath(), maxChars = 200_000).orEmpty()
            .lineSequence()
            .count { it.isNotBlank() }

    private suspend fun markSent(ctx: ToolContext, kind: String, target: String) {
        val line = SimpleDateFormat("HH:mm", Locale.US).format(Date()) + " $kind $target\n"
        ctx.workspace.append(sentPath(), line)
    }

    /**
     * 今天还能不能对外说话。
     *
     * `biliCommentQuota` 是评论与私信的**合计**上限：两者都是「以用户身份对外发言」，
     * 分开计数等于把上限翻倍。0 表示不限，但那种情况下用户得自己盯着。
     */
    private suspend fun remainingQuota(ctx: ToolContext, quota: Int): Int {
        if (quota <= 0) return Int.MAX_VALUE
        return (quota - countSentToday(ctx)).coerceAtLeast(0)
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
     * 返回空列表 = 不带图。上传失败**不阻断**评论：一张配图不值得让整条评论发不出去，
     * 但要把失败原因带回给模型，让它别再反复重试。
     */
    private suspend fun preparePictures(
        ctx: ToolContext,
        message: String,
    ): Pair<List<UploadedImage>, String> {
        val s = ctx.settings()
        val kind = s.biliImage
        if (kind == "none" || kind.isBlank()) return emptyList<UploadedImage>() to ""

        val bytes = when (kind) {
            "card" -> {
                val text = s.biliImageText.trim()
                    .ifBlank { BiliImages.pickFallback(today().hashCode()) }
                val foot = if (s.biliUserName.isNotBlank()) s.biliUserName else "bilibili.com"
                BiliImages.card(text, foot)
            }
            "local" -> {
                val c = ctx.appContext ?: return emptyList<UploadedImage>() to "（没有 Android 上下文，跳过配图）"
                BiliImages.loadLocal(c)
                    ?: return emptyList<UploadedImage>() to
                        "（设置里选了相册图，但还没选过。去设置 → 刷B站 → 评论配图 选一张）"
            }
            else -> return emptyList<UploadedImage>() to ""
        }
        return runCatching { listOf(api(ctx).uploadImage(bytes)) }
            .fold(
                onSuccess = { it to "" },
                onFailure = { emptyList<UploadedImage>() to "（配图上传失败：${errText(it)}；评论照发）" },
            )
    }

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
                "（发评论 / 发私信 / 只总结）、@ 目标、配图设置、评论风格，以及本轮标准流程。"
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
                    if (s.biliAtUid.isNotBlank()) {
                        append("评论要 @ 的 UID：").append(s.biliAtUid.trim()).append('\n')
                    }
                    append("评论配图：").append(
                        when (s.biliImage) {
                            "card" -> "摸鱼卡（程序生成，卡上文字「" +
                                s.biliImageText.ifBlank { "按日期轮换" } + "」）"
                            "local" -> "用户自选的相册图"
                            else -> "不带"
                        }
                    ).append('\n')
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
            "发评论，或回复某条评论。**直接发送，不需要用户确认** —— 但只有设置里模式为" +
                "「发评论」时才能用，否则会被拒绝。" +
                "配图（摸鱼卡 / 相册图）与 @ 用户由设置自动附加，你不用管。" +
                "要回复某个人：传 root（这栋楼的根评论 rpid）+ parent（对方那条的 rpid），" +
                "两个 id 由 bili_inbox / bili_comments 给出。" +
                "语气必须遵守 bili_status 返回的「评论风格」。"
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
            val (pics, picNote) = preparePictures(ctx, message)

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
                            if (picNote.isNotBlank()) append('\n').append(picNote)
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
