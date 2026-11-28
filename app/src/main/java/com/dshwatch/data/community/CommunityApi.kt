package com.dshwatch.data.community

import com.dshwatch.core.Logx
import com.dshwatch.domain.roleplay.CardField
import com.dshwatch.domain.roleplay.CardPlugin
import com.dshwatch.domain.roleplay.LoreEntry
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

    suspend fun register(username: String, password: String): Auth =
        auth("register", username, password)

    suspend fun login(username: String, password: String): Auth =
        auth("login", username, password)

    private suspend fun auth(action: String, username: String, password: String): Auth {
        val body = buildJsonObject {
            put("username", username.trim())
            put("password", password)
        }
        val obj = post(action, body, token = null)
        val token = obj["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isBlank()) throw CommunityException("服务器没有返回登录凭证")
        val user = obj["user"]?.jsonObject
        return Auth(
            token = token,
            username = user?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty(),
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
    suspend fun detail(id: Long, download: Boolean = false): CommunityDetail {
        val url = "?a=card&id=$id" + if (download) "&download=1" else ""
        val obj = get(url, token = null)
        val item = obj["item"]?.jsonObject ?: throw CommunityException("这张卡已经不在了")
        val meta = readMeta(item)
        val payload = item["card"]?.jsonObject ?: JsonObject(emptyMap())
        return CommunityDetail(meta = meta, card = toRoleCard(payload, meta))
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
         * 现在还是 http：主机的 SSL 证书没装好（面板自带那张是
         * aspanel.starzv.com 的，且已过期）。装好之后把这里换成 https 即可，
         * App 的 network_security_config 两种都允许。
         */
        const val BASE_URL = "http://dshwatch.vxni.ink/api/"

        /** `?a=list` 后面那串固定部分，和 [list] 里拼参数的地方对应 */
        private const val BASE_TAIL_LIST = "?a=list"

        const val SORT_NEW = "new"
        const val SORT_HOT = "hot"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** 注册 / 登录成功后的凭证 */
data class Auth(val token: String, val username: String)

/** 一页社区列表 */
data class Page(val items: List<CommunityCard>, val hasMore: Boolean)

/** 详情：元信息 + 可直接入库的本地卡 */
data class CommunityDetail(val meta: CommunityCard, val card: RoleCard)

/**
 * 社区列表里的一行。
 *
 * [status] / [reason] 只有 `?a=mine` 才有值，浏览列表时固定是 approved。
 * 用同一个结构而不是两个类：列表页和「我的投稿」长得几乎一样，
 * 分开写等于把状态渲染复制一遍。
 */
data class CommunityCard(
    val id: Long,
    val kind: String,
    val name: String,
    val tagline: String,
    val author: String,
    val downloads: Int,
    val likes: Int,
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
