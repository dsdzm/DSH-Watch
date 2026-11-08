package com.dshwatch.net.media

import com.dshwatch.net.Http
import com.dshwatch.net.backend.DshError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 图片 / 视频生成通道。
 *
 * 和聊天不是同一条链路，差异有三处，所以单独放一个类而不是塞进 [OpenAiCompatBackend]：
 *  1. **端点不同**：出图走 `/images/generations`，出视频走 `/videos` 建任务 + `/agnesapi` 轮询；
 *     `/chat/completions` 对着这两个模型发只会得到一句「我是 image 模型」。
 *  2. **耗时不同**：出图十几秒，出视频几十秒到几分钟，`readTimeout` 不能沿用聊天的 180s 语义。
 *  3. **产物是文件**：必须落盘，否则地址一过期这条记录就废了。
 */
class MediaApi(private val json: Json) {

    /** 生成类请求用独立 client：长任务不能被聊天的连接池挤掉 */
    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // ---------------- 图片 ----------------

    /**
     * 文生图。
     *
     * @return 远端地址；服务端也可能直接回 `b64_json`，那种情况先落盘再返回本地路径。
     */
    suspend fun generateImage(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        size: String? = null,
        destDir: File,
        fileName: String,
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            size?.takeIf { it.isNotBlank() }?.let { put("size", it) }
        }
        val body = post("${baseUrl.trimEnd('/')}/images/generations", apiKey, payload)
        val root = json.parseToJsonElement(body).jsonObject

        // 错误有两种外壳：{error:{message}} 与 {code,message}
        errorOf(root)?.let { throw DshError.Server(it) }

        val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw DshError.Parse("响应里没有 data[0]")
        val url = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.isNotBlank()) return@withContext url

        // 某些中转站只回 base64，直接写文件，后续流程统一按「本地路径」处理
        val b64 = item["b64_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (b64.isBlank()) throw DshError.Parse("响应里既没有 url 也没有 b64_json")

        destDir.mkdirs()
        val file = File(destDir, fileName)
        file.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        file.absolutePath
    }

    // ---------------- 视频 ----------------

    /**
     * 文生视频：建任务 → 轮询。
     *
     * 参数按 Agnes Video 2.5 的约定传字符串（`seconds` 是 `"4"`–`"12"`，
     * 传数字会被判成非法字段，这一点在真机联调时才暴露得出来）。
     *
     * @param onProgress 每次轮询回一次（百分比 + 一句人话），界面据此更新「生成中」
     */
    suspend fun generateVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        seconds: Int = 5,
        size: String = "720P",
        aspectRatio: String = "16:9",
        onProgress: suspend (Int, String) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        // 轮询接口挂在站点根上，不在 /v1 下面
        val origin = root.removeSuffix("/v1").removeSuffix("/")

        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("mode", "text")
            put("seconds", seconds.coerceIn(4, 12).toString())
            put("size", size)
            put("aspect_ratio", aspectRatio)
        }
        val created = runCatching { post("$root/videos", apiKey, payload) }
            .getOrElse { t ->
                // 队列满是最常见的失败，单独给一句能指导操作的话
                val msg = t.message.orEmpty()
                if (msg.contains("queue", true) || msg.contains("队列")) {
                    throw DshError.Server("视频队列已满，稍等一会儿再试")
                }
                throw t
            }
        val createdObj = json.parseToJsonElement(created).jsonObject
        errorOf(createdObj)?.let { throw DshError.Server(it) }

        val videoId = firstString(createdObj, "video_id", "task_id", "id")
            ?: throw DshError.Parse("建任务没返回 video_id")
        onProgress(3, "任务已提交")

        // 轮询：1.5s 一次，最多 8 分钟。超时不算错误，如实告诉用户去稍后重试。
        val deadline = System.currentTimeMillis() + VIDEO_TIMEOUT_MS
        var tick = 0
        while (System.currentTimeMillis() < deadline) {
            delay(1_500)
            tick++
            val url = "$origin/agnesapi?video_id=$videoId&model_name=$model"
            val text = runCatching { get(url, apiKey) }.getOrNull() ?: continue
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue

            errorOf(obj)?.let { throw DshError.Server(it) }

            val status = (obj["status"]?.jsonPrimitive?.contentOrNull
                ?: obj["state"]?.jsonPrimitive?.contentOrNull).orEmpty().lowercase()
            val progress = obj["progress"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: obj["percent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: (10 + tick * 6).coerceAtMost(92)
            val out = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: obj["video_url"]?.jsonPrimitive?.contentOrNull
                ?: obj["data"]?.let { d ->
                    runCatching {
                        d.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                            ?: d.jsonArray.first().jsonObject["url"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                }

            when {
                status == "completed" || status == "succeeded" || status == "success" ->
                    return@withContext out?.takeIf { it.isNotBlank() }
                        ?: throw DshError.Parse("任务完成但没有返回视频地址")

                status == "failed" || status == "error" ->
                    throw DshError.Server(
                        firstString(obj, "message", "error", "detail") ?: "视频生成失败"
                    )

                else -> onProgress(progress.coerceIn(3, 97), progressLabel(status))
            }
        }
        throw DshError.Server("视频生成超时（>${VIDEO_TIMEOUT_MS / 60_000} 分钟），可以稍后重试")
    }

    // ---------------- 下载 ----------------

    /**
     * 把远端媒体下载到本地。
     *
     * 图片生成的地址是带签名的 COS 链接，几小时后就会失效；
     * 不落盘的话，用户第二天翻回这条会话只能看到一张裂图。
     */
    suspend fun download(
        url: String,
        dest: File,
        apiKey: String = "",
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val req = Request.Builder().url(url).get().apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DshError.Http(resp.code, resp.body?.string().orEmpty())
            val total = resp.body?.contentLength() ?: -1L
            val tmp = File(dest.absolutePath + ".part")
            resp.body?.byteStream()?.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
        dest
    }

    // ---------------- 底层 ----------------

    private fun post(url: String, apiKey: String, payload: JsonObject): String {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(Http.JSON_MEDIA))
            .build()
        return execute(req)
    }

    private fun get(url: String, apiKey: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(req)
    }

    private fun execute(req: Request): String {
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return text
                // 4xx 的服务端原话比 "HTTP 400" 有用得多：它通常直接说明哪个参数错了
                val friendly = runCatching {
                    val o = json.parseToJsonElement(text).jsonObject
                    errorOf(o)
                }.getOrNull()
                if (friendly != null) throw DshError.Server("${friendly}")
                throw DshError.Http(resp.code, text)
            }
        } catch (e: DshError) {
            throw e
        } catch (t: Throwable) {
            throw DshError.from(t)
        }
    }

    /** 兼容 `{error:{message}}`、`{error:"..."}`、`{code,message}` 三种错误外壳 */
    private fun errorOf(obj: JsonObject): String? {
        obj["error"]?.let { e ->
            runCatching {
                if (e is JsonObject) {
                    e["message"]?.jsonPrimitive?.contentOrNull
                        ?: e["code"]?.jsonPrimitive?.contentOrNull
                } else {
                    e.jsonPrimitive.contentOrNull
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val code = obj["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (code.isNotBlank() && code != "success" && obj["data"] == null && obj["message"] != null) {
            return obj["message"]?.jsonPrimitive?.contentOrNull
        }
        return null
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            // 字段可能不是字符串（`error` 经常是对象），jsonPrimitive 会直接抛，必须兜住
            val v = runCatching { obj[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun progressLabel(status: String): String = when (status) {
        "queued", "pending", "waiting" -> "排队中"
        "running", "processing", "generating", "in_progress" -> "生成中"
        else -> "生成中"
    }

    companion object {
        private const val VIDEO_TIMEOUT_MS = 8 * 60 * 1000L
    }
}
