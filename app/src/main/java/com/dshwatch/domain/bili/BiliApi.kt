package com.dshwatch.domain.bili

import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID

/**
 * B站网页接口的最小封装。
 *
 * ## 为什么自己实现 WBI 签名
 *
 * 2023 年之后 B站的搜索/播放器接口都要求 `w_rid` + `wts` 两个参数，
 * 而 `w_rid = md5(排序后的 query + mixinKey)`，mixinKey 又由
 * `nav` 接口返回的 img_key / sub_key 按一张固定的 64 位置换表重排得到。
 * 这件事没有官方 SDK，社区实现（含本项目的参考对象 dsh-bili-scroll）都是
 * 同一套算法，所以这里按同一套写死，只在内存里缓存 mixinKey 六小时。
 *
 * ## 为什么不用第三方库
 *
 * 手表 APK 每多一个依赖就多几 MB，而这里真正需要的只是「取 JSON + 算 md5」，
 * OkHttp 与 kotlinx.serialization 项目里本来就有。
 *
 * ## 登录态
 *
 * 全部接口都能匿名调；只有「发评论」需要 Cookie 里的 SESSDATA + bili_jct。
 * Cookie 由外部以 lambda 传入（从加密存储现取），这个类不碰持久化，
 * 也就不会有人把 SESSDATA 写进日志或者导出文件。
 */
class BiliApi(private val cookie: () -> String) {

    // ------------------------------------------------------------------
    // 缓存：mixinKey 六小时、buvid3 一次进程
    // ------------------------------------------------------------------

    @Volatile
    private var mixinKey: String = ""

    @Volatile
    private var mixinKeyAt: Long = 0L

    @Volatile
    private var buvid: String = ""

    @Volatile
    private var buvid4: String = ""

    /** `b_nut` = 首次访问时间戳，B站用它判断「这个 buvid 是不是刚生成的机器人」 */
    @Volatile
    private var bNut: Long = 0L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ------------------------------------------------------------------
    // 对外能力
    // ------------------------------------------------------------------

    /**
     * 诊断串（不含任何凭证内容）。
     *
     * 出问题时「到底是没拿到设备指纹，还是密钥过期，还是被风控」三种情况的
     * 处理方式完全不同，而界面上的表现都是「搜不到东西」。
     */
    fun health(): String = buildString {
        append("buvid3=").append(if (buvid.isEmpty()) "无" else "有")
        append(" · buvid4=").append(if (buvid4.isEmpty()) "无" else "有")
        append(" · wbiKey=").append(if (mixinKey.isEmpty()) "未取" else "已缓存")
        append(" · 登录=").append(if (cookie().isBlank()) "匿名" else "已配置")
    }

    /** 按关键词搜视频。返回的是「能用来判断值不值得看」的最少字段，不返回一堆用不上的统计 */
    suspend fun search(keyword: String, num: Int): List<VideoBrief> {
        val n = num.coerceIn(1, 20)
        val data = call(
            url = "$API/x/web-interface/wbi/search/type",
            params = mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to "1",
                "page_size" to n.toString(),
            ),
            signed = true,
        )
        // `search/type` 返回的是 `data.result` = 视频数组；
        // 而 `search/all/v2` 是 `data.result` = 分组数组（每组里再套 result）。
        // 两种形态都认，否则会出现「接口 code 0、解析出来 0 条」这种假空结果。
        val items: List<JsonObject> = when (val el = data["result"]) {
            is kotlinx.serialization.json.JsonArray ->
                el.mapNotNull { it as? JsonObject }
            is JsonObject ->
                (el["result"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        return items.mapNotNull { o ->
            val bvid = o.str("bvid").ifBlank { return@mapNotNull null }
            VideoBrief(
                bvid = bvid,
                title = clean(o.str("title")),
                author = o.str("author"),
                play = o.long("play"),
                danmaku = o.long("video_review"),
                duration = o.str("duration"),
                pubdate = o.long("pubdate"),
                desc = clean(o.str("description")),
            )
        }
    }

    /** 视频元信息（含分P 的 cid —— 取字幕必须先有 cid） */
    suspend fun video(bvid: String): VideoDetail {
        val data = call(
            url = "$API/x/web-interface/view",
            params = mapOf("bvid" to bvid),
            signed = false,
        )
        val pages = (data["pages"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { p ->
                Page(
                    cid = p.long("cid"),
                    page = p.int("page"),
                    part = p.str("part"),
                    durationSec = p.int("duration"),
                )
            }
        val owner = data["owner"] as? JsonObject
        val stat = data["stat"] as? JsonObject
        return VideoDetail(
            bvid = data.str("bvid").ifBlank { bvid },
            aid = data.long("aid"),
            cid = data.long("cid"),
            title = data.str("title"),
            owner = owner?.str("name").orEmpty(),
            ownerMid = owner?.long("mid") ?: 0L,
            desc = data.str("desc"),
            durationSec = data.int("duration"),
            view = stat?.long("view") ?: 0,
            like = stat?.long("like") ?: 0,
            coin = stat?.long("coin") ?: 0,
            favorite = stat?.long("favorite") ?: 0,
            reply = stat?.long("reply") ?: 0,
            danmaku = stat?.long("danmaku") ?: 0,
            pubdate = data.long("pubdate"),
            pages = pages,
        )
    }

    /**
     * 字幕原文。
     *
     * 这是整个流程里最关键的一步：**判断值不值得看只能靠正文**，
     * 靠标题和简介猜出来的结论没有意义。所以拿不到字幕时明确报「没有字幕」，
     * 而不是退回标题让模型硬猜。
     */
    suspend fun subtitle(bvid: String, page: Int, maxChars: Int): SubtitleResult {
        val detail = video(bvid)
        val p = detail.pages.firstOrNull { it.page == page } ?: detail.pages.firstOrNull()
            ?: throw BiliException("这个视频没有分P 信息，取不到 cid")
        val cid = if (p.cid > 0) p.cid else detail.cid

        val data = call(
            url = "$API/x/player/wbi/v2",
            params = mapOf("bvid" to detail.bvid, "cid" to cid.toString()),
            signed = true,
        )
        val subtitle = data["subtitle"] as? JsonObject ?: JsonObject(emptyMap())
        val list = (subtitle["subtitles"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
        if (list.isEmpty()) {
            val needLogin = subtitle["need_login_subtitle"]?.let {
                (it as? JsonPrimitive)?.contentOrNull == "true"
            } ?: false
            return SubtitleResult(
                text = "",
                lang = "",
                available = false,
                hint = if (needLogin) {
                    "该视频的字幕需要登录才能取。在设置里填 B站 Cookie（SESSDATA）再试一次。"
                } else {
                    "这个视频没有可读的字幕。B站现在普遍要求**登录后**才能看 AI 字幕，" +
                        "匿名多半是空的 —— 想让判断更准可以填 Cookie；" +
                        "不填的话用 bili_video 的简介 + bili_comments 的评论判断，或者换一条。"
                },
            )
        }
        // 优先中文，其次第一条
        val pick = list.firstOrNull { it.str("lan").startsWith("zh") } ?: list.first()
        val url = pick.str("subtitle_url").let { if (it.startsWith("//")) "https:$it" else it }
        if (url.isBlank()) return SubtitleResult("", "", false, "字幕地址为空，取不到正文。")

        val body = fetchText(url)
        val cues = runCatching {
            val root = json.parseToJsonElement(body) as JsonObject
            (root["body"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .map { it.str("content").trim() }
                .filter { it.isNotEmpty() }
        }.getOrElse { throw BiliException("字幕文件解析失败：${it.message}") }

        // ASR 字幕经常整句重复，按行去重能省掉一半 token
        val deduped = ArrayList<String>(cues.size)
        var prev = ""
        for (line in cues) {
            if (line != prev) deduped += line
            prev = line
        }
        val text = deduped.joinToString("\n")
        return SubtitleResult(
            text = text.take(maxChars.coerceIn(500, 20_000)),
            lang = pick.str("lan_doc").ifBlank { pick.str("lan") },
            available = true,
            hint = "",
        )
    }

    /** 热门评论（按热度排序）。发评论前用它做撞车检测，判断前用来补充正文没说的信息 */
    suspend fun comments(bvid: String, num: Int): List<Comment> {
        val detail = video(bvid)
        val data = call(
            url = "$API/x/v2/reply",
            params = mapOf(
                "type" to "1",
                "oid" to detail.aid.toString(),
                "sort" to "2",
                "ps" to num.coerceIn(1, 20).toString(),
                "pn" to "1",
            ),
            signed = false,
        )
        return (data["replies"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { parseComment(it) }
    }

    /** 评论对象 → 领域模型。`rpid` 必须留着：回复楼中楼全靠它定位 */
    private fun parseComment(o: JsonObject): Comment? {
        val member = o["member"] as? JsonObject ?: return null
        val content = o["content"] as? JsonObject ?: return null
        return Comment(
            rpid = o.long("rpid"),
            user = member.str("uname"),
            mid = member.long("mid"),
            message = content.str("message"),
            like = o.int("like"),
            replies = o.int("rcount"),
            rootId = o.long("root"),
            parentId = o.long("parent"),
            ctime = o.long("ctime"),
        )
    }

    /**
     * 发一条评论。
     *
     * 需要 Cookie（SESSDATA + bili_jct，后者就是 csrf 参数）。
     * 没登录态时直接抛异常 —— 绝不做「匿名试一下」这种会触发风控的事。
     */
    suspend fun reply(
        bvid: String,
        message: String,
        root: Long = 0L,
        parent: Long = 0L,
        at: BiliUser? = null,
        pictures: List<UploadedImage> = emptyList(),
    ): String {
        ensureBuvid()
        val raw = cookie().trim()
        if (raw.isBlank()) throw BiliException("没有配置 B站 Cookie，无法发评论")
        val csrf = cookieValue(raw, "bili_jct")
            ?: throw BiliException("Cookie 里缺少 bili_jct（csrf 令牌），无法发评论")
        val detail = video(bvid)

        // 楼中楼回复的口径：`root` 是整栋楼的根评论，`parent` 是**被回复的那一条**。
        // 只填 parent 不填 root，B站会把回复挂到评论区顶层而不是对方那条下面 ——
        // 表现就是「明明回复了，对方收不到通知」。
        if (parent > 0 && root <= 0) throw BiliException("回复某条评论时 root 不能为 0")

        // @ 人和带图都必须同时给「正文里的 @昵称」和「`at_name_to_mid` 映射」：
        // 只给映射正文里看不到，只给正文不会通知到对方。两个都给了才算真 @。
        val text = if (at != null && at.name.isNotBlank() && !message.contains("@${at.name}")) {
            "$message @${at.name}"
        } else {
            message
        }

        val builder = okhttp3.FormBody.Builder()
            .add("oid", detail.aid.toString())
            .add("type", "1")
            .add("message", text)
            .add("csrf", csrf)
            .add("root", root.coerceAtLeast(0L).toString())
            .add("parent", parent.coerceAtLeast(0L).toString())

        if (at != null && at.name.isNotBlank()) {
            // 昵称里可能有引号，交给 JSON 编码器，别自己拼字符串
            val map = kotlinx.serialization.json.buildJsonObject {
                put(at.name, JsonPrimitive(at.mid))
            }
            builder.add("at_name_to_mid", map.toString())
        }
        if (pictures.isNotEmpty()) {
            val arr = kotlinx.serialization.json.buildJsonArray {
                pictures.forEach { pic ->
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("img_src", JsonPrimitive(pic.url))
                            put("img_width", JsonPrimitive(pic.width))
                            put("img_height", JsonPrimitive(pic.height))
                            // UploadedImage.size 是字节，这个字段要 KB
                            put("img_size", JsonPrimitive((pic.size / 1024.0).coerceAtLeast(0.1)))
                        }
                    )
                }
            }
            builder.add("pictures", arr.toString())
        }
        if (at != null || pictures.isNotEmpty()) {
            // web 端带图/带 at 的评论都带这几个标记；缺了会被当成旧版客户端
            builder.add("plat", "1")
            builder.add("gaia_source", "main_web")
            builder.add("has_vote_option", "true")
        }
        val form = builder.build()
        val request = req("$API/x/v2/reply/add")
            .header("Referer", "https://www.bilibili.com/video/$bvid")
            .header("Cookie", cookieHeader(raw))
            .post(form)
            .build()

        val body = withContext(Dispatchers.IO) {
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw BiliException("发送失败：${it.message.orEmpty().take(120)}") }
        }
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw BiliException("返回不是 JSON，可能被风控拦了：${body.take(120)}")
        val code = obj.int("code")
        if (code != 0) {
            throw BiliException("B站拒绝了这条评论：${code} ${obj.str("message")}（${friendlyCode(code)}）")
        }
        return "已发布"
    }

    // ------------------------------------------------------------------
    // 账号信息 / 图片 / 私信
    // ------------------------------------------------------------------

    /** 当前登录账号的 mid。取不到说明 Cookie 里没有 DedeUserID，也就发不了私信 */
    fun selfMid(): Long = cookieValue(cookie().trim(), "DedeUserID")?.toLongOrNull() ?: 0L

    /**
     * 查一个人的昵称。
     *
     * 评论 @ 人需要「昵称 → mid」的映射，光有 UID 拼不出能点开的 @。
     * 这个接口匿名可用（实测 mid=2 直接回 code 0），所以不要求登录。
     */
    suspend fun userCard(mid: Long): BiliUser? {
        if (mid <= 0) return null
        val obj = runCatching {
            call("$API/x/web-interface/card", mapOf("mid" to mid.toString()), signed = false)
        }.getOrNull() ?: return null
        val card = obj["card"] as? JsonObject ?: return null
        val name = card.str("name")
        if (name.isBlank()) return null
        return BiliUser(mid = mid, name = name, face = card.str("face"))
    }

    /**
     * 上传一张图到 B站图床，返回评论 `pictures` 字段要用的结构。
     *
     * 走的是动态用的 `upload_bfs`：评论与动态共用同一个图床，参数 `biz=new_dyn`
     * 就是「按动态图存」。实测未登录会回 `-101`，所以必须先有 Cookie。
     */
    suspend fun uploadImage(bytes: ByteArray, fileName: String = "dsh.jpg"): UploadedImage {
        ensureBuvid()
        val raw = cookie().trim()
        if (raw.isBlank()) throw BiliException("没有配置 B站 Cookie，无法上传图片")
        val csrf = cookieValue(raw, "bili_jct")
            ?: throw BiliException("Cookie 里缺少 bili_jct，无法上传图片")
        if (bytes.isEmpty()) throw BiliException("图片是空的")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("csrf", csrf)
            .addFormDataPart("category", "daily")
            .addFormDataPart("biz", "new_dyn")
            .addFormDataPart("file_up", fileName, bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = req("$API/x/dynamic/feed/draw/upload_bfs")
            .header("Referer", "https://www.bilibili.com/")
            .post(body)
            .build()

        val text = withContext(Dispatchers.IO) {
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw BiliException("上传图片失败：${it.message.orEmpty().take(120)}") }
        }
        val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: throw BiliException("上传图片返回不是 JSON：${text.take(120)}")
        val code = obj.int("code")
        if (code != 0) {
            throw BiliException("上传图片被拒：$code ${obj.str("message")}（${friendlyCode(code)}）")
        }
        val data = obj["data"] as? JsonObject ?: throw BiliException("上传图片没有返回 data")
        val rawUrl = data.str("image_url").ifBlank { data.str("img_src") }
        if (rawUrl.isBlank()) throw BiliException("上传图片没有返回地址")

        // 返回的地址偶尔是协议相对形式（`//i0.hdslb.com/...`）。
        // 评论接口原样接受，但动态接口会把它当成非法 URL 直接吞掉图片 —— 补齐。
        val url = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("http") -> rawUrl
            rawUrl.startsWith("/") -> "https://i0.hdslb.com$rawUrl"
            else -> "https://i0.hdslb.com/bfs/new_dyn/$rawUrl"
        }

        /*
         * `img_size` 的单位是 **KB**，而且常带小数（88.5）。
         *
         * 这里统一折算成字节存进 UploadedImage，让「字节」成为唯一口径：
         * 评论接口要 KB、动态接口也要 KB，各自除 1024 就行。
         * 之前直接把接口返回的 KB 当字节存，评论那边原样传出去是 88（碰巧对），
         * 而走 bytes.size 兜底时传的是 97000 —— 被 B站 判成非法参数，
         * 图片被静默丢弃，表现就是「评论发出去了但没图」。
         */
        val kb = data.str("img_size").trim().toDoubleOrNull()?.takeIf { it > 0 }
            ?: data.int("img_size").takeIf { it > 0 }?.toDouble()
        return UploadedImage(
            url = url,
            width = data.int("image_width").takeIf { it > 0 } ?: 720,
            height = data.int("image_height").takeIf { it > 0 } ?: 720,
            size = if (kb != null) (kb * 1024).toInt() else bytes.size,
        )
    }

    /**
     * 发一条私信。
     *
     * 走的是 IM 的 `send_msg`（不是评论接口），因此**不可撤回、对方一定会看到**。
     * 需要的 `msg[sender_uid]` 从 Cookie 的 DedeUserID 取 —— 少了就直接报错，
     * 而不是猜一个 mid 发出去。
     */
    suspend fun dm(receiver: Long, text: String): String {
        ensureBuvid()
        if (receiver <= 0) throw BiliException("收件人 UID 不合法")
        val raw = cookie().trim()
        if (raw.isBlank()) throw BiliException("没有配置 B站 Cookie，无法发私信")
        val csrf = cookieValue(raw, "bili_jct") ?: throw BiliException("Cookie 里缺少 bili_jct")
        val self = selfMid()
        if (self <= 0) throw BiliException("Cookie 里没有 DedeUserID，无法确定发送者；请重新扫码登录")

        // dev_id 用已有的 buvid3：同一个设备反复换 dev_id 更容易被判定成脚本
        val devId = buvid.ifBlank { UUID.randomUUID().toString().uppercase() }
        val content = kotlinx.serialization.json.buildJsonObject {
            put("content", JsonPrimitive(text))
        }.toString()

        val form = okhttp3.FormBody.Builder()
            .add("msg[sender_uid]", self.toString())
            .add("msg[receiver_id]", receiver.toString())
            .add("msg[receiver_type]", "1")
            .add("msg[msg_type]", "1")
            .add("msg[msg_status]", "0")
            .add("msg[content]", content)
            .add("msg[dev_id]", devId)
            .add("msg[timestamp]", (System.currentTimeMillis() / 1000).toString())
            .add("msg[new_face_version]", "1")
            .add("csrf", csrf)
            .build()

        val request = req(VC_SEND_MSG)
            .header("Referer", "https://message.bilibili.com/")
            .header("Origin", "https://message.bilibili.com")
            .post(form)
            .build()

        val body = withContext(Dispatchers.IO) {
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw BiliException("发送私信失败：${it.message.orEmpty().take(120)}") }
        }
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw BiliException("私信接口返回不是 JSON：${body.take(120)}")
        val code = obj.int("code")
        if (code != 0) {
            throw BiliException("B站拒绝了这条私信：$code ${obj.str("message").ifBlank { obj.str("msg") }}")
        }
        return "已发送"
    }

    // ------------------------------------------------------------------
    // 点赞
    // ------------------------------------------------------------------

    /**
     * 给视频点个赞。
     *
     * 接口是幂等的：重复 `like=1` 不会连点两次，所以判重只是为了省请求。
     * 传 `aid` 而不是 `bvid`：bvid 分支在不同版本里时好时坏，aid 是从没变过的那个。
     *
     * 这里**不加 WBI 签名**：`archive/like` 是网页端最老的一批接口之一，
     * 只认「Cookie + form 表单 + csrf」。多塞 `wts` / `w_rid` 它也不看，
     * 反而因为参数集合跟网页端对不上，更容易被当成脚本。
     */
    suspend fun like(bvid: String, on: Boolean = true): String {
        val raw = cookie().trim()
        if (raw.isBlank()) throw BiliException("没有配置 B站 Cookie，无法点赞")
        val csrf = cookieValue(raw, "bili_jct") ?: throw BiliException("Cookie 里缺少 bili_jct，无法点赞")
        val aid = video(bvid).aid
        if (aid <= 0) throw BiliException("查不到 $bvid 的 aid，点赞失败")

        val (code, message) = postAction(
            path = "/x/web-interface/archive/like",
            bvid = bvid,
            params = linkedMapOf(
                "aid" to aid.toString(),
                "like" to if (on) "1" else "0",
                "csrf" to csrf,
            ),
        )
        if (code != 0) {
            throw BiliException("B站拒绝了这次点赞：$code $message（${friendlyCode(code)}）")
        }
        return if (on) "已点赞" else "已取消点赞"
    }

    /**
     * 点赞的请求骨架：**参数只放 form 表单**，不签名、不往 query 里塞第二份。
     *
     * 之前那版为了让签名覆盖到参数，把同一组键值同时写进 query 和 body，
     * 服务端于是能解析出两个 `csrf`、两个 `aid`，取到哪个全看框架实现 ——
     * 表现就是「别的接口都正常，只有这个动作回 -401 非法访问」。
     * 网页端发的本来就只有一份表单，照着发最不容易出事。
     */
    private suspend fun postAction(
        path: String,
        bvid: String,
        params: Map<String, String>,
    ): Pair<Int, String> {
        val form = okhttp3.FormBody.Builder()
        params.forEach { (k, v) -> form.add(k, stripForbidden(v)) }

        val request = req("$API$path")
            .header("Referer", "https://www.bilibili.com/video/$bvid")
            .post(form.build())
            .build()

        val body = withContext(Dispatchers.IO) {
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw BiliException("点赞失败：${it.message.orEmpty().take(120)}") }
        }
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw BiliException("点赞接口返回不是 JSON（可能被风控）：${body.take(120)}")
        return obj.int("code") to obj.str("message")
    }

    /**
     * 发一条动态。
     *
     * 和评论不一样，动态是**独立广播**：它进粉丝时间线，不依附任何视频，
     * 也没有「楼」可以回。接口是 `x/dynamic/feed/create/dyn`，body 是 JSON，
     * `csrf` 放在 query —— 这个接口**不需要 WBI 签名**（匿名实测只回 `-101`，
     * 说明参数层是通的，卡在登录校验）。
     *
     * 图片直接复用 [uploadImage] 的 `biz=new_dyn` 图床，返回地址填进 `pics`。
     * 注意 `img_size` 的单位是 **KB**，而 [UploadedImage.size] 是字节数。
     *
     * `scene` 只能 1（纯文本）或 2（带图）。传错不会报错，只会得到一条没有图的
     * 动态 —— 所以这里按 pics 是否为空自己定，不交给调用方。
     */
    suspend fun createDyn(text: String, pics: List<UploadedImage> = emptyList()): Long {
        ensureBuvid()
        if (text.isBlank()) throw BiliException("动态内容是空的")
        val raw = cookie().trim()
        if (raw.isBlank()) throw BiliException("没有配置 B站 Cookie，发不了动态")
        val csrf = cookieValue(raw, "bili_jct") ?: throw BiliException("Cookie 里缺少 bili_jct")
        val self = selfMid()
        if (self <= 0) throw BiliException("Cookie 里没有 DedeUserID，无法确定发布者；请重新扫码登录")

        // upload_id 是可选的客户端追踪号，格式 mid_秒级时间戳_四位随机。
        // 不传也能发，但带上更接近网页端行为，风控上少一个可疑点。
        val uploadId = "${self}_${System.currentTimeMillis() / 1000}_${(1000..9999).random()}"

        val payload = kotlinx.serialization.json.buildJsonObject {
            putJsonObject("dyn_req") {
                putJsonObject("content") {
                    putJsonArray("contents") {
                        addJsonObject {
                            put("raw_text", text)
                            put("type", 1)
                        }
                    }
                    if (pics.isNotEmpty()) {
                        putJsonArray("pics") {
                            pics.forEach { p ->
                                addJsonObject {
                                    put("img_src", p.url)
                                    put("img_width", p.width)
                                    put("img_height", p.height)
                                    // 接口要 KB，UploadedImage 存的是字节
                                    put("img_size", (p.size / 1024.0).coerceAtLeast(0.1))
                                }
                            }
                        }
                    }
                }
                put("scene", if (pics.isEmpty()) 1 else 2)
                put("upload_id", uploadId)
                putJsonObject("meta") {
                    putJsonObject("app_meta") {
                        put("from", "create.dynamic.web")
                        put("mobi_app", "web")
                    }
                }
            }
        }.toString()

        val url = "$API/x/dynamic/feed/create/dyn?platform=web&csrf=${csrf}"
        val request = req(url)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val body = withContext(Dispatchers.IO) {
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw BiliException("发动态失败：${it.message.orEmpty().take(120)}") }
        }
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw BiliException("动态接口返回不是 JSON（可能被风控）：${body.take(120)}")
        val code = obj.int("code")
        if (code != 0) {
            throw BiliException("B站拒绝了这条动态：$code ${obj.str("message").ifBlank { obj.str("msg") }}（${friendlyCode(code)}）")
        }
        val data = obj["data"] as? JsonObject
        // dyn_id_str 是字符串形式的 id（超过 2^53 时网页端也是用它），优先取它
        return data?.str("dyn_id_str")?.toLongOrNull()?.takeIf { it > 0 }
            ?: data?.long("dyn_id")?.takeIf { it > 0 }
            ?: 0L
    }

    // ------------------------------------------------------------------
    // 扫码登录
    // ------------------------------------------------------------------

    /**
     * 申请一个登录二维码。
     *
     * 走的是 passport 域名（不是 api.bilibili.com），返回的 `url` 就是二维码里
     * 该编码的内容，`qrcode_key` 用来轮询结果。两者都必须原样用，
     * 自己拼 `https://bilibili.com` 是扫不出来的。
     */
    suspend fun qrGenerate(): QrLogin {
        val body = withContext(Dispatchers.IO) {
            val request = req(QR_GENERATE, withCookie = false).get().build()
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw BiliException("生成二维码失败：${it.message.orEmpty().take(100)}") }
        }
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw BiliException("生成二维码失败：返回不是 JSON")
        val code = obj.int("code")
        if (code != 0) throw BiliException("生成二维码失败：$code ${obj.str("message")}")
        val data = obj["data"] as? JsonObject ?: throw BiliException("生成二维码失败：返回没有 data")
        val key = data.str("qrcode_key")
        val url = data.str("url")
        if (key.isBlank() || url.isBlank()) throw BiliException("生成二维码失败：缺少 qrcode_key")
        return QrLogin(key = key, url = url)
    }

    /**
     * 轮询扫码结果。
     *
     * 内层 `code`：0 成功 / 86101 未扫码 / 86090 已扫码待确认 / 86038 已过期。
     * 外层 code 永远是 0，别拿外层判断状态 —— 这是最容易写错的一处。
     */
    suspend fun qrPoll(key: String): QrPoll {
        val pair = withContext(Dispatchers.IO) {
            val request = req("$QR_POLL?qrcode_key=$key", withCookie = false).get().build()
            runCatching {
                Http.client.newCall(request).execute().use { resp ->
                    (resp.body?.string().orEmpty()) to resp.headers.values("Set-Cookie")
                }
            }.getOrElse { throw BiliException("查询扫码状态失败：${it.message.orEmpty().take(100)}") }
        }
        val obj = runCatching { json.parseToJsonElement(pair.first) as JsonObject }.getOrNull()
            ?: throw BiliException("查询扫码状态失败：返回不是 JSON")
        if (obj.int("code") != 0) {
            throw BiliException("查询扫码状态失败：${obj.int("code")} ${obj.str("message")}")
        }
        val data = obj["data"] as? JsonObject ?: JsonObject(emptyMap())
        val inner = data.int("code")
        // 成功时凭证在两个地方都有：Set-Cookie 头，以及 data.url 的 query。
        // 优先用 Set-Cookie（更全），拿不到再从 url 里取 —— 有些机型/代理会吃掉响应头。
        val cookie = cookiesToHeader(pair.second).ifBlank { cookieFromUrl(data.str("url")) }
        return QrPoll(
            code = inner,
            message = data.str("message"),
            cookie = if (inner == 0) cookie else "",
        )
    }

    /** 当前登录账号（未登录时 loggedIn = false）。用来在设置页显示「已登录：昵称」 */
    suspend fun me(): BiliMe {
        val body = withContext(Dispatchers.IO) {
            val request = req("$API/x/web-interface/nav").get().build()
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrDefault("")
        }
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: return BiliMe(0L, "", false)
        val data = obj["data"] as? JsonObject
        val logged = data?.get("isLogin")?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: false
        return BiliMe(
            mid = data?.long("mid") ?: 0L,
            name = data?.str("uname").orEmpty(),
            loggedIn = logged,
        )
    }

    // ------------------------------------------------------------------
    // 「回复我的」与楼中楼
    // ------------------------------------------------------------------

    /**
     * 最近收到的回复（B站消息中心的「回复我的」）。
     *
     * 每一项里有两段关键正文：
     *  - `source_content`：**对方**回复了什么
     *  - `target_reply_content`：**我**原来那条评论
     * 再加上 `root_id` / `source_id` 两个 id，就能直接接一条精准的楼中楼回复。
     */
    suspend fun inbox(num: Int): List<ReplyNotice> {
        val data = call(
            url = "$API/x/msgfeed/reply",
            params = mapOf(
                "platform" to "web",
                "id" to "",
                "reply_time" to "",
            ),
            signed = false,
        )
        val items = (data["items"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .take(num.coerceIn(1, 20))
        return items.mapNotNull { o ->
            val user = o["user"] as? JsonObject ?: JsonObject(emptyMap())
            val item = o["item"] as? JsonObject ?: JsonObject(emptyMap())
            val uri = item.str("uri").ifBlank { item.str("native_uri") }
            val bvid = BVID_REGEX.find(uri)?.value.orEmpty()
            ReplyNotice(
                id = o.long("id"),
                from = user.str("nickname"),
                fromMid = user.long("mid"),
                timeSec = o.long("reply_time"),
                theirReply = item.str("source_content").ifBlank { item.str("desc") },
                myComment = item.str("target_reply_content").ifBlank { item.str("root_reply_content") },
                videoTitle = item.str("title"),
                bvid = bvid,
                aid = item.long("subject_id"),
                rootId = item.long("root_id"),
                sourceId = item.long("source_id"),
                targetId = item.long("target_id"),
                businessId = item.int("business_id"),
            )
        }
    }

    /** 某一栋评论楼的全部回复（含根评论）。用来在回复前看清上下文 */
    suspend fun thread(aid: Long, root: Long, num: Int): ThreadView {
        val data = call(
            url = "$API/x/v2/reply/reply",
            params = mapOf(
                "type" to "1",
                "oid" to aid.toString(),
                "root" to root.toString(),
                "ps" to num.coerceIn(1, 20).toString(),
                "pn" to "1",
            ),
            signed = false,
        )
        val rootObj = data["root"] as? JsonObject
        return ThreadView(
            rootComment = rootObj?.let { parseComment(it) },
            replies = (data["replies"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { parseComment(it) },
        )
    }

    // ------------------------------------------------------------------
    // 传输
    // ------------------------------------------------------------------

    /**
     * 调一个接口并返回 `data` 字段。
     *
     * 签名用的是**最终发出去的 query 字符串**（[HttpUrl.encodedQuery]），
     * 而不是自己拼一遍再发一遍 —— 手工编码与 OkHttp 的规范化只要差一个
     * `%20` / `+`，w_rid 就对不上，而这种错误表现为「所有接口都 -403」，
     * 极难定位。
     */
    private suspend fun call(url: String, params: Map<String, String>, signed: Boolean): JsonObject {
        val body = withContext(Dispatchers.IO) { execute(url, params, signed) }
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }
            .getOrElse { throw BiliException("返回不是 JSON（可能被风控或网络劫持）：${body.take(120)}") }
        val code = obj.int("code")
        if (code != 0) {
            throw BiliException("B站接口返回 $code：${obj.str("message").ifBlank { friendlyCode(code) }}")
        }
        return obj["data"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun execute(url: String, params: Map<String, String>, signed: Boolean): String {
        val base = url.toHttpUrlOrNull() ?: throw BiliException("接口地址不合法：$url")
        ensureBuvid()
        var target: HttpUrl = base
        var rid = ""

        if (signed) {
            val key = mixinKey()
            val builder = base.newBuilder()
            // 排序后再插入：HttpUrl 保持插入顺序，encodedQuery 才是已排序的
            (params + ("wts" to (System.currentTimeMillis() / 1000).toString()))
                .toSortedMap()
                .forEach { (k, v) -> builder.addQueryParameter(k, stripForbidden(v)) }
            target = builder.build()
            rid = md5(target.encodedQuery.orEmpty() + key)
        } else {
            val builder = base.newBuilder()
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            target = builder.build()
        }

        val finalUrl = if (signed) {
            (target.toString() + "&w_rid=" + rid).toHttpUrlOrNull() ?: target
        } else {
            target
        }

        val request = req(finalUrl.toString()).get().build()

        return runCatching {
            Http.client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw BiliException("HTTP ${resp.code}：${text.take(100)}")
                }
                text
            }
        }.getOrElse { t ->
            if (t is BiliException) throw t
            throw BiliException(
                "网络请求失败：${t.javaClass.simpleName} ${t.message.orEmpty().take(100)}。" +
                    "如果是 DNS / 连接错误，说明这台设备访问不了 api.bilibili.com。"
            )
        }
    }

    /** 字幕 JSON 在 CDN 上，不需要签名，但要带 Referer 否则 403 */
    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = req(url, withCookie = false).get().build()
        runCatching {
            Http.client.newCall(request).execute().use { it.body?.string().orEmpty() }
        }.getOrElse { throw BiliException("下载字幕失败：${it.message.orEmpty().take(100)}") }
    }

    // ------------------------------------------------------------------
    // 设备指纹
    // ------------------------------------------------------------------

    /**
     * 取一个匿名 buvid3。
     *
     * 这是**能不能刷起来的关键**：不带 buvid3 直接打 `view` / `wbi/search`，
     * 大概率拿到 -352 / 空响应（实测同一台机器、同一个 IP，加了这一行才稳定 code 0）。
     * 这个接口本身不需要登录，返回的也只是设备随机标识，不涉及账号。
     */
    private suspend fun ensureBuvid() {
        if (buvid.isNotEmpty()) return
        val body = withContext(Dispatchers.IO) {
            val request = req("$API/x/frontend/finger/spi").get().build()
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrDefault("")
        }
        if (body.isBlank()) return
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return
        val data = obj["data"] as? JsonObject ?: return
        val b3 = (data["b_3"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val b4 = (data["b_4"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (b3.isNotBlank()) {
            buvid = b3
            buvid4 = b4
            bNut = System.currentTimeMillis() / 1000
        }
    }

    // ------------------------------------------------------------------
    // WBI 签名
    // ------------------------------------------------------------------

    private suspend fun mixinKey(): String {
        val cached = mixinKey
        if (cached.isNotEmpty() && System.currentTimeMillis() - mixinKeyAt < KEY_TTL_MS) return cached
        ensureBuvid()

        val body = withContext(Dispatchers.IO) {
            val request = req("$API/x/web-interface/nav").get().build()
            runCatching { Http.client.newCall(request).execute().use { it.body?.string().orEmpty() } }
                .getOrElse { throw BiliException("取 WBI 密钥失败：${it.message.orEmpty().take(100)}") }
        }
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw BiliException("取 WBI 密钥失败：返回不是 JSON")
        val wbi = (obj["data"] as? JsonObject)?.get("wbi_img") as? JsonObject
            ?: throw BiliException("取 WBI 密钥失败：nav 接口没有返回 wbi_img")

        val imgKey = keyOf(wbi.str("img_url"))
        val subKey = keyOf(wbi.str("sub_url"))
        if (imgKey.isBlank() || subKey.isBlank()) throw BiliException("WBI 密钥为空")

        val raw = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) sb.append(raw[MIXIN_KEY_ENC_TAB[i]])
        val key = sb.toString()
        mixinKey = key
        mixinKeyAt = System.currentTimeMillis()
        return key
    }

    /** `https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png` → 文件名（去扩展名） */
    private fun keyOf(url: String): String =
        url.substringAfterLast('/', "").substringBeforeLast('.')

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 统一构造请求。
     *
     * `Origin` 与 `Accept-Language` 看着无关紧要，但 B站的风控会把
     * 「有 Referer 没 Origin」当成脚本特征 —— 缺这两个头时搜索接口更容易
     * 返回空结果。这类问题在真机上表现为「有时候能刷、有时候刷不出来」，
     * 所以把它固定在这里，而不是散落在各个调用点。
     */
    private fun req(url: String, withCookie: Boolean = true): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "application/json, text/plain, */*")
        if (withCookie) b.header("Cookie", cookieHeader(cookie()))
        return b
    }

    /**
     * Cookie 头。
     *
     * 匿名请求也带一个 buvid3：B站对没有设备指纹的请求更容易直接 -412（风控），
     * 而拿 buvid3 本身不需要登录。
     *
     * **但两套指纹绝不能混用**：buvid3 / buvid4 / b_nut 是一组，B站会校验
     * 三者的签发关系。登录 Cookie 里已经有服务端下发的 buvid3 时，如果再拼一个
     * 匿名 b_nut 进去，两者对不上 —— 表现就是大部分接口正常，只有点赞这种
     * 带写操作的动作返回 -401 非法访问。所以只有在我们自己注入 buvid3 的那条
     * 分支里，才顺带注入配套的 buvid4 与 b_nut。
     */
    private fun cookieHeader(raw: String): String =
        composeCookieHeader(raw, buvid, buvid4, bNut)

    /**
     * 从 Set-Cookie 头里挑出登录凭证，拼成一条 Cookie 头。
     *
     * 只保留白名单里的键：响应里还有一堆 `buvid_fp`、`CURRENT_FNVAL` 之类，
     * 全塞进去既没必要，也会把 Cookie 撑长到被某些网关拦掉。
     */
    private fun cookiesToHeader(list: List<String>): String {
        val out = LinkedHashMap<String, String>()
        list.forEach { raw ->
            val first = raw.substringBefore(';')
            val name = first.substringBefore('=').trim()
            val value = first.substringAfter('=', "").trim()
            if (name in LOGIN_COOKIE_KEYS && value.isNotEmpty() && !value.equals("deleted", true)) {
                out[name] = value
            }
        }
        return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 从 `data.url` 的 query 里取凭证。
     *
     * 刻意**不解码**：SESSDATA 的值本身是 URL 编码过的（含 `%2C`），
     * 浏览器与本尊 B站 都是拿编码值当 Cookie 发的；解码后再拼回去会让签名对不上。
     */
    private fun cookieFromUrl(url: String): String {
        if (url.isBlank()) return ""
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return ""
        val out = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            val name = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (name in LOGIN_COOKIE_KEYS && value.isNotEmpty()) out[name] = value
        }
        return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 从 Cookie 串里取某个键的值 */
    private fun cookieValue(raw: String, key: String): String? =
        raw.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** B站签名要求先剔除 `!'()*` 这五个字符 */
    private fun stripForbidden(v: String): String = v.filter { it !in "!'()*" }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun JsonObject.str(key: String): String =
        ((this[key] as? JsonPrimitive)?.contentOrNull ?: "").let { if (it == "null") "" else it }

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0

    /** 标题里带 `<em class="keyword">` 高亮标签，直接回灌给模型只是噪声 */
    private fun clean(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .trim()

    private fun friendlyCode(code: Int): String = when (code) {
        -101 -> "未登录"
        -400 -> "参数不合法"
        -401 -> "签名校验失败（这个接口要 WBI 签名，或 Cookie 不完整）"
        -352 -> "被风控拦截（需要 buvid 或换个网络）"
        -403 -> "签名校验失败或权限不足"
        -404 -> "视频不存在或已删除"
        -412 -> "请求过于频繁，被风控限流"
        62002 -> "该视频不可见（可能是私密/已删）"
        62004 -> "稿件审核中"
        12061 -> "评论被折叠或需要验证"
        12015 -> "需要先完成手机号绑定才能评论"
        12002 -> "评论区已关闭"
        12003 -> "该视频禁止回复"
        12016 -> "内容含敏感词，换一种说法"
        12025 -> "评论字数超出限制"
        12035 -> "被 UP 主拉黑，发不了评论"
        12051 -> "重复评论，被判定刷屏"
        else -> "未知错误"
    }

    companion object {
        private const val API = "https://api.bilibili.com"
        private const val KEY_TTL_MS = 6 * 3600_000L

        /** 私信走 IM 域名，不是 api.bilibili.com */
        private const val VC_SEND_MSG = "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg"

        private const val QR_GENERATE =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        private const val QR_POLL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"

        /** 登录必须保留的 Cookie 键。少了 bili_jct 就发不了评论，少了 SESSDATA 就等于没登录 */
        private val LOGIN_COOKIE_KEYS = setOf(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid",
            // buvid3 / buvid4 / b_nut 必须成组保存：带写操作的接口会校验
            // 三者的签发关系，只留 buvid3 而丢掉 b_nut 会被判成「指纹不完整」
            "buvid3", "buvid4", "b_nut",
        )

        private val BVID_REGEX = Regex("BV[0-9A-Za-z]{10}")

        /**
         * 用**桌面** Chrome 的 UA。
         *
         * 这一条是实测出来的，不是随手写的：同一个 buvid3、同一台机器，
         * 移动 UA 的搜索接口经常返回 `code 0` 但 `result` 为空（软风控），
         * 换成桌面 UA 立刻稳定返回结果。反正请求是 App 代发的，
         * 用哪个 UA 与「用户拿的是手表还是手机」没有关系。
         */
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** WBI 的 mixinKey 重排表，社区通用实现，顺序不能动 */
        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )
    }
}

// ----------------------------------------------------------------------
// 数据模型
// ----------------------------------------------------------------------

class BiliException(message: String) : Exception(message)

data class VideoBrief(
    val bvid: String,
    val title: String,
    val author: String,
    val play: Long,
    val danmaku: Long,
    val duration: String,
    val pubdate: Long,
    val desc: String,
)

data class VideoDetail(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val owner: String,
    /** UP 主 mid。私信模式要发给它 */
    val ownerMid: Long,
    val desc: String,
    val durationSec: Int,
    val view: Long,
    val like: Long,
    val coin: Long,
    val favorite: Long,
    val reply: Long,
    val danmaku: Long,
    val pubdate: Long,
    val pages: List<Page>,
)

data class Page(val cid: Long, val page: Int, val part: String, val durationSec: Int)

data class Comment(
    /** 评论 id。回复楼中楼时必须用它当 `parent` */
    val rpid: Long,
    val user: String,
    val mid: Long,
    val message: String,
    val like: Int,
    val replies: Int,
    /** 所在楼的根评论 id（顶层评论自身为 0） */
    val rootId: Long,
    val parentId: Long,
    val ctime: Long,
)

data class BiliUser(val mid: Long, val name: String, val face: String = "")

/** 上传到 B站图床后的结果，字段名与评论接口的 `pictures` 一一对应 */
data class UploadedImage(
    val url: String,
    val width: Int,
    val height: Int,
    val size: Int,
)

data class QrLogin(val key: String, val url: String)

data class QrPoll(val code: Int, val message: String, val cookie: String) {
    val ok: Boolean get() = code == 0 && cookie.isNotBlank()
}

data class BiliMe(val mid: Long, val name: String, val loggedIn: Boolean)

/** 一条「回复我的」通知 */
data class ReplyNotice(
    val id: Long,
    val from: String,
    val fromMid: Long,
    val timeSec: Long,
    /** 对方说的话 */
    val theirReply: String,
    /** 我原来那条评论 */
    val myComment: String,
    val videoTitle: String,
    val bvid: String,
    val aid: Long,
    val rootId: Long,
    /** 对方那条评论的 id —— 回复时当 `parent` */
    val sourceId: Long,
    /** 被回复的评论 id（也就是我的评论） */
    val targetId: Long,
    val businessId: Int,
)

data class ThreadView(val rootComment: Comment?, val replies: List<Comment>)

data class SubtitleResult(
    val text: String,
    val lang: String,
    val available: Boolean,
    val hint: String,
)

/**
 * 拼 Cookie 头。抽成顶层函数只为一个原因：**这条逻辑值得单测**。
 *
 * 规则是「设备指纹不许混用」——
 * - 登录 Cookie 里已经有 buvid3：它是服务端下发的，与 SESSDATA 绑定。
 *   此时**什么都不补**，尤其是不能塞匿名 buvid4 / b_nut；
 * - 没有 buvid3：三件套（buvid3 / buvid4 / b_nut）整体注入，它们是一组。
 *
 * 混用的后果很隐蔽：搜索、看视频、发评论全都正常，只有点赞这种带写操作的
 * 接口回 `-401 非法访问`，看上去像是「接口要签名」，实际是设备指纹对不上。
 */
internal fun composeCookieHeader(
    raw: String,
    buvid: String,
    buvid4: String,
    bNut: Long,
): String {
    val parts = ArrayList<String>(4)
    if (raw.isNotBlank()) parts += raw.trim().trimEnd(';')
    // 只在完全没有 buvid3 时才整套注入：给一个服务端下发的 buvid3 配一个
    // 别处换来的 buvid4，两者签发关系对不上，比不带还要糟。
    if (!raw.contains("buvid3=") && buvid.isNotEmpty()) {
        parts += "buvid3=$buvid"
        if (buvid4.isNotEmpty()) parts += "buvid4=$buvid4"
        if (bNut > 0) parts += "b_nut=$bNut"
    }
    return parts.joinToString("; ")
}
