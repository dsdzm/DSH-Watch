package com.dshwatch.domain.bili

import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.security.MessageDigest

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
            .mapNotNull { o ->
                val member = o["member"] as? JsonObject ?: return@mapNotNull null
                val content = o["content"] as? JsonObject ?: return@mapNotNull null
                Comment(
                    user = member.str("uname"),
                    message = content.str("message"),
                    like = o.int("like"),
                    replies = o.int("rcount"),
                )
            }
    }

    /**
     * 发一条评论。
     *
     * 需要 Cookie（SESSDATA + bili_jct，后者就是 csrf 参数）。
     * 没登录态时直接抛异常 —— 绝不做「匿名试一下」这种会触发风控的事。
     */
    suspend fun reply(bvid: String, message: String): String {
        ensureBuvid()
        val raw = cookie().trim()
        if (raw.isBlank()) throw BiliException("没有配置 B站 Cookie，无法发评论")
        val csrf = cookieValue(raw, "bili_jct")
            ?: throw BiliException("Cookie 里缺少 bili_jct（csrf 令牌），无法发评论")
        val detail = video(bvid)

        val form = okhttp3.FormBody.Builder()
            .add("oid", detail.aid.toString())
            .add("type", "1")
            .add("message", message)
            .add("csrf", csrf)
            .add("root", "0")
            .add("parent", "0")
            .build()
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
     */
    private fun cookieHeader(raw: String): String {
        val parts = ArrayList<String>(4)
        if (raw.isNotBlank()) parts += raw.trim().trimEnd(';')
        if (buvid.isNotEmpty() && !raw.contains("buvid3=")) parts += "buvid3=$buvid"
        if (buvid4.isNotEmpty() && !raw.contains("buvid4=")) parts += "buvid4=$buvid4"
        if (bNut > 0 && !raw.contains("b_nut=")) parts += "b_nut=$bNut"
        return parts.joinToString("; ")
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
        -352 -> "被风控拦截（需要 buvid 或换个网络）"
        -403 -> "签名校验失败或权限不足"
        -404 -> "视频不存在或已删除"
        -412 -> "请求过于频繁，被风控限流"
        62002 -> "该视频不可见（可能是私密/已删）"
        62004 -> "稿件审核中"
        12061 -> "评论被折叠或需要验证"
        12015 -> "需要先完成手机号绑定才能评论"
        else -> "未知错误"
    }

    companion object {
        private const val API = "https://api.bilibili.com"
        private const val KEY_TTL_MS = 6 * 3600_000L

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

data class Comment(val user: String, val message: String, val like: Int, val replies: Int)

data class SubtitleResult(
    val text: String,
    val lang: String,
    val available: Boolean,
    val hint: String,
)
