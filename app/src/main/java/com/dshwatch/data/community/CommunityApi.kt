package com.dshwatch.data.community

import com.dshwatch.core.Logx
import com.dshwatch.domain.roleplay.CardField
import com.dshwatch.domain.roleplay.CardPlugin
import com.dshwatch.domain.roleplay.LoreEntry
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 酒馆社区（UGC）客户端。
 *
 * ## 和别的网络模块的关系
 *
 * 社区服务器是**我们自己的**一台虚拟主机（PHP + MySQL），接口形态是一套
 * 很土的 `?a=xxx` + JSON。它和聊天的 `OpenAiCompatBackend`、以及
 * `DshWebConnector` 都没有关系，所以单独一个类，不复用它们的错误处理 ——
 * 那两套的失败语义是「模型报错 / 连不上电脑」，这里的失败语义是
 * 「用户名被占了」「还在审核中」，混在一起只会让用户看不懂。
 *
 * ## 为什么不用 kotlinx.serialization 的 @Serializable 数据类
 *
 * 服务端的返回是「按需拼出来的关联数组」，字段会随版本增减
 * （比如 `mine` 多两个 status / reason）。这里手写 JsonObject 读取，
 * 少一个字段就是默认值，不用同步改两边的模型。
 * 反过来投稿也手写构造 —— 只发服务端认识的字段，避免把
 * `id` / `builtin` / `updatedAt` 这些本地概念漏出去。
 */
class CommunityApi(
    private val json: Json,
    /** 允许测试替身换成 MockWebServer 的地址 */
    private val baseUrl: String = BASE_URL,
) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // 社区接口都是一次性小请求，必须有硬超时：
            // 主机在境外，证书没装好时连接会一直吊着，界面就卡在"加载中"
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ---------------- 账号 ----------------

    /**
     * 注册。邮箱是必填的（服务端会校验格式与重复），但**没有验证码** ——
     * 目前没有发信通道，加一步验证只会让注册流程断掉。
     */
    suspend fun register(username: String, password: String, email: String): Auth =
        auth("register", username, password, email)

    suspend fun login(username: String, password: String): Auth =
        auth("login", username, password)

    private suspend fun auth(
        action: String,
        username: String,
        password: String,
        email: String = "",
    ): Auth {
        val body = buildJsonObject {
            put("username", username.trim())
            put("password", password)
            if (email.isNotBlank()) put("email", email.trim())
        }
        val obj = post(action, body, token = null)
        val token = obj["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isBlank()) throw CommunityException("服务器没有返回登录凭证")
        val user = obj["user"]?.jsonObject
        return Auth(
            token = token,
            username = user?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty(),
            email = user?.get("email")?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /** 退出登录。失败无所谓 —— 本地 token 一定要清掉，不然下次登录还是这个人 */
    suspend fun logout(token: String) {
        runCatching { post("logout", buildJsonObject { }, token) }
    }

    // ---------------- 浏览 ----------------

    /**
     * 已通过审核的卡。
     *
     * [page] 从 0 开始，[size] 服务端上限 30。手表一屏放不下 20 条，
     * 但网络请求来回一趟比多拉几条贵，所以默认 20 条 + 手动「加载更多」。
     */
    suspend fun list(
        page: Int = 0,
        size: Int = 20,
        kind: String? = null,
        query: String = "",
        sort: String = SORT_NEW,
    ): Page {
        val url = buildString {
            append(BASE_TAIL_LIST)
            append("&page=").append(page.coerceAtLeast(0))
            append("&size=").append(size.coerceIn(1, 30))
            append("&sort=").append(if (sort == SORT_HOT) SORT_HOT else SORT_NEW)
            if (kind == Scenes.TAVERN || kind == Scenes.WRITING) {
                append("&kind=").append(kind)
            }
            if (query.isNotBlank()) {
                append("&q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
            }
        }
        val obj = get(url, token = null)
        val items = obj["items"]?.jsonArray ?: JsonArray(emptyList())
        return Page(
            items = items.mapNotNull { it as? JsonObject }.map(::readMeta),
            hasMore = obj["hasMore"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /**
     * 卡详情（含完整正文）。
     *
     * [download] 为 true 时服务端顺手给下载数 +1 —— 只在**真的导入**时传，
     * 光点开看一眼不该算一次下载。
     */
    suspend fun detail(id: Long, download: Boolean = false, token: String? = null): CommunityDetail {
        val url = "?a=card&id=$id" + if (download) "&download=1" else ""
        // 不带 token 也能看；带了才知道「我打了几分」「这张卡是不是我投的」
        val obj = get(url, token?.takeIf { it.isNotBlank() })
        val item = obj["item"]?.jsonObject ?: throw CommunityException("这张卡已经不在了")
        val meta = readMeta(item)
        val payload = item["card"]?.jsonObject ?: JsonObject(emptyMap())
        return CommunityDetail(
            meta = meta,
            card = toRoleCard(payload, meta),
            myScore = item["myScore"]?.jsonPrimitive?.intOrNull ?: 0,
            liked = item["liked"]?.jsonPrimitive?.booleanOrNull == true,
            mine = item["mine"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    suspend fun mine(token: String): List<CommunityCard> {
        val obj = get("?a=mine", token)
        val items = obj["items"]?.jsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { it as? JsonObject }.map(::readMeta)
    }

    suspend fun like(token: String, id: Long): Int {
        val obj = post("like", buildJsonObject { put("id", id) }, token)
        return obj["likes"]?.jsonPrimitive?.intOrNull ?: 0
    }

    // ---------------- 评论 / 评分 / 举报 ----------------

    /** 一张卡的评论，最新在前。主评论下面带一层回复（见 [CommunityComment.replies]） */
    suspend fun comments(
        cardId: Long,
        page: Int = 0,
        size: Int = 10,
        token: String? = null,
    ): CommentPage {
        val obj = get("?a=comments&id=$cardId&page=$page&size=$size", token?.takeIf { it.isNotBlank() })
        val raw = obj["items"]?.jsonArray ?: JsonArray(emptyList())
        return CommentPage(
            items = raw.mapNotNull { it as? JsonObject }.map(::readComment),
            hasMore = obj["hasMore"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** 发评论；[parentId] > 0 表示回复某条主评论 */
    suspend fun postComment(
        token: String,
        cardId: Long,
        body: String,
        parentId: Long = 0L,
    ): CommunityComment {
        val obj = post(
            "comment",
            buildJsonObject {
                put("id", cardId)
                put("body", body.trim())
                if (parentId > 0) put("parent", parentId)
            },
            token,
        )
        val c = obj["comment"]?.jsonObject ?: throw CommunityException("评论没发出去，再试一次")
        return readComment(c)
    }

    /** 给评论点赞。一次性：重复点返回当前数字，不取消 */
    suspend fun likeComment(token: String, commentId: Long): Int {
        val obj = post("clike", buildJsonObject { put("id", commentId) }, token)
        return obj["likes"]?.jsonPrimitive?.intOrNull ?: 0
    }

    /** 站内消息（目前是「你的评论被赞」），[unread] 是未读数 */
    suspend fun notices(token: String): NoticePage {
        val obj = get("?a=notices", token)
        val items = obj["items"]?.jsonArray ?: JsonArray(emptyList())
        return NoticePage(
            items = items.mapNotNull { it as? JsonObject }.map(::readNotice),
            unread = obj["unread"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /** 标记已读；[id] 传 0 表示全部 */
    suspend fun readNotices(token: String, id: Long = 0L): Int {
        val obj = post("notice_read", buildJsonObject { if (id > 0) put("id", id) }, token)
        return obj["unread"]?.jsonPrimitive?.intOrNull ?: 0
    }

    /**
     * 打分。一人一卡一分，**打过就不能改** —— 服务端返回 409 时抛
     * `CommunityException("你已经打过分了，评分不能修改")`，界面原样展示。
     */
    suspend fun rate(token: String, cardId: Long, score: Int): RatingResult {
        val obj = post(
            "rate",
            buildJsonObject {
                put("id", cardId)
                put("score", score.coerceIn(1, 5))
            },
            token,
        )
        return RatingResult(
            rating = obj["rating"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            count = obj["ratingCount"]?.jsonPrimitive?.intOrNull ?: 0,
            myScore = obj["myScore"]?.jsonPrimitive?.intOrNull ?: score,
        )
    }

    /** 作者撤回自己的投稿。服务端会校验 user_id，别人删不动 */
    suspend fun deleteOwn(token: String, cardId: Long) {
        post("delete", buildJsonObject { put("id", cardId) }, token)
    }

    suspend fun report(token: String, cardId: Long, reason: String, detail: String = "") {
        post(
            "report",
            buildJsonObject {
                put("id", cardId)
                put("reason", reason)
                if (detail.isNotBlank()) put("detail", detail)
            },
            token,
        )
    }

    private fun readComment(o: JsonObject): CommunityComment = CommunityComment(
        id = o["id"]?.jsonPrimitive?.longOrNull ?: 0L,
        author = o["author"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        likes = o["likes"]?.jsonPrimitive?.intOrNull ?: 0,
        liked = o["liked"]?.jsonPrimitive?.booleanOrNull == true,
        mine = o["mine"]?.jsonPrimitive?.booleanOrNull == true,
        parentId = o["parentId"]?.jsonPrimitive?.longOrNull ?: 0L,
        replies = o["replies"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.map(::readComment)
            .orEmpty(),
    )

    private fun readNotice(o: JsonObject): CommunityNotice = CommunityNotice(
        id = o["id"]?.jsonPrimitive?.longOrNull ?: 0L,
        kind = o["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        cardId = o["cardId"]?.jsonPrimitive?.longOrNull ?: 0L,
        commentId = o["commentId"]?.jsonPrimitive?.longOrNull ?: 0L,
        actor = o["actor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        read = o["read"]?.jsonPrimitive?.booleanOrNull == true,
    )

    // ---------------- 投稿 ----------------

    /**
     * 投稿 / 改稿。
     *
     * 服务端用「带不带 id」区分新建与修改：带了就覆盖自己那条并重新进审核队列。
     * 因此 [remoteId] 只在本机这张卡曾经投过时才传 —— 传错了会把别人的稿覆盖掉
     * （服务端有 user_id 校验，实际覆盖不了，但会得到一个 404 而不是新建）。
     */
    suspend fun submit(token: String, card: RoleCard, remoteId: Long = 0L): Long {
        val body = buildJsonObject {
            if (remoteId > 0) put("id", remoteId)
            put("card", toPayload(card))
        }
        val obj = post("submit", body, token)
        return obj["id"]?.jsonPrimitive?.longOrNull ?: 0L
    }

    // ---------------- 编解码 ----------------

    private fun readMeta(o: JsonObject): CommunityCard = CommunityCard(
        id = o["id"]?.jsonPrimitive?.longOrNull ?: 0L,
        kind = o["kind"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { Scenes.TAVERN },
        name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        tagline = o["tagline"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        author = o["author"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        downloads = o["downloads"]?.jsonPrimitive?.intOrNull ?: 0,
        likes = o["likes"]?.jsonPrimitive?.intOrNull ?: 0,
        rating = o["rating"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        ratingCount = o["ratingCount"]?.jsonPrimitive?.intOrNull ?: 0,
        commentCount = o["commentCount"]?.jsonPrimitive?.intOrNull ?: 0,
        createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        status = o["status"]?.jsonPrimitive?.contentOrNull
            ?: CommunityCard.STATUS_APPROVED,
        reason = o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    /**
     * 本地卡 → 投稿体。
     *
     * 字段名刻意和 [RoleCard] 对齐（服务端就是照它写的），所以只有三处要处理：
     *  - `id` / `builtin` / 时间戳：本机概念，服务端不认；
     *  - 世界书的 `priority` / `enabled`：不开放（一张卡不该靠优先级挤掉别人的设定）；
     *  - 提示插件：服务端整段不收（插件会改系统提示，审核看不懂它的作用）。
     */
    private fun toPayload(card: RoleCard): JsonObject = buildJsonObject {
        put("kind", if (card.kind == Scenes.WRITING) Scenes.WRITING else Scenes.TAVERN)
        put("name", card.name.trim())
        put("tagline", card.tagline.trim())
        put("persona", card.persona)
        put("userPersona", card.userPersona)
        put("greeting", card.greeting)
        put("style", card.style)
        putJsonArray("fields") {
            card.fields.forEach { f ->
                if (f.key.isNotBlank() || f.value.isNotBlank()) {
                    addJsonObject {
                        put("key", f.key)
                        put("value", f.value)
                    }
                }
            }
        }
        putJsonArray("entries") {
            card.entries.forEach { e ->
                addJsonObject {
                    put("title", e.title)
                    put("content", e.content)
                    put("keywords", buildJsonArray { e.keywords.forEach { add(it) } })
                }
            }
        }
        put("longTermMemory", card.longTermMemory)
        put("autoContinue", card.autoContinue)
    }

    /**
     * 详情正文 → 本地卡。
     *
     * 不沿用远端的 id：本地 rolecards 目录是按 id 命名的文件，
     * 用 `u12` 这种远端 id 会和以后别的东西撞。交给 `RoleCardStore.save`
     * 生成新 id，同一张卡可以导入两次（用户想改一版留一版时很常见）。
     */
    private fun toRoleCard(o: JsonObject, meta: CommunityCard): RoleCard {
        val kind = (o["kind"]?.jsonPrimitive?.contentOrNull
            ?: meta.kind).let { if (it == Scenes.WRITING) Scenes.WRITING else Scenes.TAVERN }
        val fields = o["fields"]?.jsonArray?.mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            val k = f["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val v = f["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (k.isBlank() && v.isBlank()) null else CardField(key = k, value = v)
        }.orEmpty()
        val entries = o["entries"]?.jsonArray?.mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            LoreEntry(
                title = f["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                content = f["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                keywords = f["keywords"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
            )
        }.orEmpty()
        return RoleCard(
            kind = kind,
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { meta.name },
            tagline = o["tagline"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { meta.tagline },
            persona = o["persona"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            userPersona = o["userPersona"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            greeting = o["greeting"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            style = o["style"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            fields = fields,
            entries = entries,
            plugins = emptyList<CardPlugin>(),
            longTermMemory = o["longTermMemory"]?.jsonPrimitive?.booleanOrNull == true,
            autoContinue = o["autoContinue"]?.jsonPrimitive?.booleanOrNull == true,
            builtin = false,
        )
    }

    // ---------------- 传输 ----------------

    private suspend fun get(path: String, token: String?): JsonObject =
        call(Request.Builder().url(url(path)).get(), token)

    private suspend fun post(path: String, body: JsonObject, token: String?): JsonObject =
        call(
            Request.Builder()
                .url(url("?a=$path"))
                .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA)),
            token,
        )

    private fun url(path: String) = (baseUrl + path).toHttpUrl()

    private suspend fun call(builder: Request.Builder, token: String?): JsonObject =
        withContext(Dispatchers.IO) {
            if (token != null) builder.header("Authorization", "Bearer $token")
            builder.header("Accept", "application/json")
            val request = builder.build()
            val text = try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) {
                        throw CommunityException(
                            if (resp.code == 404) "社区接口不存在（404），服务器可能还没部署好"
                            else "服务器返回了空响应（HTTP ${resp.code}）"
                        )
                    }
                    body
                }
            } catch (e: CommunityException) {
                throw e
            } catch (e: IOException) {
                Logx.w(TAG, "社区请求失败 ${request.url}", e)
                throw CommunityException(describe(e))
            }
            parse(text)
        }

    /**
     * 统一解析：服务端的约定是 `{"ok":true,...}` / `{"ok":false,"error":"..."}`。
     *
     * 出错时**只留服务端那句中文**：它本来就是写给用户看的
     * （「这个名字已经被用了」「你还有 3 张卡在排队」），再包一层
     * 「请求失败：」只会把话变长。
     */
    private fun parse(text: String): JsonObject {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            // 主机出错时常常直接吐一页 HTML 错误页，这时候把前 120 字带上，
            // 至少能看出是 502 还是 PHP 报错
            throw CommunityException("服务器返回异常：" + text.take(120).replace("\n", " "))
        }
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw CommunityException(
                obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "请求被拒绝了，稍后再试"
            )
        }
        return obj
    }

    /** 把 OkHttp 的英文异常翻成一句用户能懂的话（手表上没地方看堆栈） */
    private fun describe(e: IOException): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Failed to connect", true) || m.contains("ConnectException", true) ->
                "连不上社区服务器，检查一下网络"
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "社区服务器没响应，稍后再试"
            m.contains("SSL", true) || m.contains("certificate", true) ->
                "证书校验失败（服务器还没装好 HTTPS 证书）"
            m.contains("Unable to resolve host", true) ->
                "解析不了社区域名，检查网络"
            else -> "网络错误：" + m.take(60)
        }
    }

    companion object {
        private const val TAG = "CommunityApi"

        /**
         * 社区服务器地址。
         *
         * 走 https：主机上装的是 ZeroSSL 签的 `dshwatch.vxni.ink` 单域名证书。
         * 证书链是 叶子 <- ZeroSSL RSA DV SSL CA 2 <- Sectigo R46 —— R46 是新根，
         * 老手表/老手机的系统 CA 库里没有，会报 Trust anchor not found；
         * 所以包里内置了 R46（res/raw/sectigo_r46.crt），在
         * network_security_config.xml 里作为附加信任锚，见该文件注释。
         * 登录和投稿都会带 token，明文 HTTP 等于把 token 写在明信片上寄出去。
         */
        const val BASE_URL = "https://dshwatch.vxni.ink/api/"

        /** `?a=list` 后面那串固定部分，和 [list] 里拼参数的地方对应 */
        private const val BASE_TAIL_LIST = "?a=list"

        const val SORT_NEW = "new"
        const val SORT_HOT = "hot"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** 注册 / 登录成功后的凭证 */
data class Auth(val token: String, val username: String, val email: String = "")

/** 一页社区列表 */
data class Page(val items: List<CommunityCard>, val hasMore: Boolean)

/**
 * 详情：元信息 + 可直接入库的本地卡。
 *
 * [myScore] / [liked] / [mine] 只有带着 token 请求时才有意义 —— 没登录时
 * 是 0 / false，界面上就是不显示"我的评分"、点赞不可点、删除按钮不出现，正好。
 */
data class CommunityDetail(
    val meta: CommunityCard,
    val card: RoleCard,
    val myScore: Int = 0,
    val liked: Boolean = false,
    val mine: Boolean = false,
)

/** 评论列表的一页 */
data class CommentPage(val items: List<CommunityComment>, val hasMore: Boolean)

/**
 * 一条评论。
 *
 * [replies] 只有主评论才有：服务端把回复跟着主评论一起下发，
 * 前端不用为了几条回复再发一次请求。[liked] 是"我赞过没有"。
 */
data class CommunityComment(
    val id: Long,
    val author: String,
    val body: String,
    val createdAt: Long,
    val likes: Int = 0,
    val liked: Boolean = false,
    val mine: Boolean = false,
    val parentId: Long = 0L,
    val replies: List<CommunityComment> = emptyList(),
)

/** 一页站内消息 */
data class NoticePage(val items: List<CommunityNotice>, val unread: Int)

/** 站内消息：目前只有「你的评论被赞」（kind = clike） */
data class CommunityNotice(
    val id: Long,
    val kind: String,
    val cardId: Long,
    val commentId: Long,
    val actor: String,
    val body: String,
    val createdAt: Long,
    val read: Boolean,
)

/** 打分后的回执：新的均分、评分人数、我打了几分 */
data class RatingResult(val rating: Double, val count: Int, val myScore: Int)

/**
 * 社区列表里的一行。
 *
 * [status] / [reason] 只有 `?a=mine` 才有值，浏览列表时固定是 approved。
 * 用同一个结构而不是两个类：列表页和「我的投稿」长得几乎一样，
 * 分开写等于把状态渲染复制一遍。
 */
@Serializable
data class CommunityCard(
    val id: Long,
    val kind: String,
    val name: String,
    val tagline: String,
    val author: String,
    val downloads: Int,
    val likes: Int,
    /** 平均分，一位小数；没人评分时是 0 */
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val commentCount: Int = 0,
    val createdAt: Long,
    val status: String = STATUS_APPROVED,
    val reason: String = "",
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_HIDDEN = "hidden"

        fun statusLabel(status: String): String = when (status) {
            STATUS_PENDING -> "审核中"
            STATUS_APPROVED -> "已上线"
            STATUS_REJECTED -> "未通过"
            STATUS_HIDDEN -> "已下架"
            else -> status
        }
    }
}

/**
 * 社区相关的失败。
 *
 * 单独一个类型而不是复用 IOException：界面要区分「网络没通，可以重试」
 * 和「这个名字被占了，重试没用」—— 两者都是 Exception，但提示语完全不同。
 */
class CommunityException(message: String) : Exception(message)
