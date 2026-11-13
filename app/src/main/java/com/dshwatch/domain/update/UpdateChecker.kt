package com.dshwatch.domain.update

import android.content.Context
import com.dshwatch.BuildConfig
import com.dshwatch.core.Jsonx
import com.dshwatch.core.Logx
import com.dshwatch.net.Http
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 更新来源：GitHub Releases。
 *
 * 一次 `GET /repos/{owner}/{repo}/releases/latest` 就拿到全部信息 ——
 * 版本号、说明、安装包地址都在同一个响应里，不需要额外的清单文件，
 * 也就不存在「清单更新了但 CDN 还缓存着旧的」这类问题。
 *
 * ## 依赖的两条约定
 *
 *  1. tag 固定是 `v<versionName>`，例如 `v1.15.19`；
 *  2. 安装包资产固定叫 `dshwatch-<versionCode>.apk`。
 *
 * 第二条是刻意的：GitHub 的 API 不提供 versionCode，只给 tag。把 code 写进
 * 文件名，客户端就能用一个正则稳定取到，不必去解析发布说明里那句自然语言 ——
 * 那种解析在说明改一个字之后就悄悄失效，表现为「永远提示已是最新」。
 *
 * ## 为什么没有 `?t=` 破缓存参数了
 *
 * 七牛那套要时间戳，是因为清单是静态文件、CDN 会缓存。GitHub 的 API 响应
 * 走自己的 CDN，缓存窗口只有一分钟量级 —— 发完版一分钟后必然是新数据，
 * 不值得为此让每次请求都绕开缓存。
 */
@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GhAsset> = emptyList(),
    @SerialName("published_at") val publishedAt: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

@Serializable
private data class GhAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
    /** 形如 `sha256:ab12…`；老仓库/老资产可能没有这个字段 */
    val digest: String = "",
)

/**
 * 更新清单。
 *
 * 字段刻意保持和上一版（对象存储方案）完全一致 —— 界面、安装器、缓存恢复
 * 全部按这个结构写好了，换数据源不该波及它们。
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val size: Long = 0,
    val sha256: String = "",
    val notes: List<String> = emptyList(),
    val publishedAt: String = "",
)

/**
 * 更新通道。
 *
 * ## 为什么要两个通道
 *
 * 发版节奏改成「攒一批再发」之后，中间那些改动总得有人先试 —— 否则等攒够
 * 才发现方向错了，返工的成本比早试一次高得多。但把半成品推给所有人是不负责任的：
 * 用户装 App 是为了用，不是为了当测试机。
 *
 * 所以拆成两条：
 *
 * | 通道 | 看到什么 | 适合谁 |
 * |---|---|---|
 * | [STABLE] | `/releases/latest`，GitHub 上「最新正式版」 | 默认，所有人 |
 * | [BETA] | `/releases` 列表里 versionCode 最大的那条，**含预发布** | 愿意先试的人 |
 *
 * 两条通道用的都是**同一个安装包格式、同一套签名**，区别只在「查哪一次发布」。
 * 用户在设置里随时能切回来 —— 切回稳定版不会降级，只是不再提示测试版。
 */
enum class UpdateChannel(val id: String) {
    STABLE("stable"),
    BETA("beta"),
    ;

    companion object {
        /** 认不出的值一律当稳定版：这是「什么都不做」的那一侧，出错时最安全 */
        fun of(id: String?): UpdateChannel = entries.firstOrNull { it.id == id } ?: STABLE
    }
}

sealed interface UpdateResult {
    data object UpToDate : UpdateResult

    data class Available(val manifest: UpdateManifest) : UpdateResult

    data class Failed(val reason: String) : UpdateResult
}

sealed interface DownloadResult {
    data class Done(val file: File) : DownloadResult

    data class Failed(val reason: String) : DownloadResult
}

object UpdateChecker {

    const val OWNER = "dsdzm"
    const val REPO = "DSH-Watch"

    /** 只认 `dshwatch-42.apk` 这一种命名，别把源码包之类的东西当安装包 */
    private val ASSET = Regex("""^dshwatch-(\d+)\.apk$""")

    /** 断线后最多续几次。8MB 的包在弱网下断两三次是常态，不续就只能一直重来 */
    private const val MAX_ATTEMPTS = 2

    /**
     * 下载通道兜底链。
     *
     * 实测（北京，2026-11-06）：GitHub 直链到
     * `release-assets.githubusercontent.com` 只有 9~25 KB/s，8MB 要 6~15 分钟，
     * 而且连接经常直接超时。同样的包走下面两个反代是 1.2~1.8 MB/s，4~6 秒下完 ——
     * 差两个数量级。这不是代码能优化出来的，只能换通道。
     *
     * 反代是第三方，理论上能看到明文（TLS 在它那里终结）。所以**校验不能省**：
     * 清单里的 sha256 来自 GitHub 的资产 digest，包被换掉一定对不上。
     * 直链排在最后兜底 —— 反代挂了的时候仍然能下，只是慢。
     */
    private val PROXIES = listOf("https://ghfast.top/", "https://gh-proxy.com/")

    /** 镜像在前、直链在后；已经带过镜像前缀的 URL 不重复加 */
    internal fun downloadSources(url: String): List<String> {
        val mirrors = PROXIES
            .filterNot { url.startsWith(it) }
            .map { it + url }
        return mirrors + url
    }

    fun releaseApiUrl(channel: UpdateChannel = UpdateChannel.STABLE): String = when (channel) {
        // 单条：GitHub 自己保证「最新且非预发布」，不用我们再排一遍
        UpdateChannel.STABLE -> "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        // 列表：一次拿 30 条，预发布也在里面，由 parseReleases 挑 versionCode 最大的
        UpdateChannel.BETA -> "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"
    }

    /**
     * 有没有新版本。
     *
     * 只看 versionCode：它能保证严格递增，而版本名在 `1.9` / `1.10` 这种地方
     * 按字符串比会得出反的结论（项目里 AppRoot.compareVersion 就是为那个坑写的）。
     */
    fun isNewer(remoteCode: Int, currentCode: Int): Boolean = remoteCode > currentCode

    /**
     * 查一次并和本机版本比较。
     */
    suspend fun check(
        channel: UpdateChannel = UpdateChannel.STABLE,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
    ): UpdateResult = withContext(Dispatchers.IO) {
        val client = Http.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        runCatching {
            client.newCall(
                Request.Builder()
                    .url(releaseApiUrl(channel))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build(),
            ).execute().use { resp ->
                // 分别给一句人话：限流和「没有发布」看起来都是失败，但用户该做的事完全不同
                when {
                    resp.code == 404 -> return@use UpdateResult.Failed("发布页不存在")
                    resp.code == 403 || resp.code == 429 ->
                        return@use UpdateResult.Failed("GitHub 限流，稍后再试")

                    !resp.isSuccessful -> return@use UpdateResult.Failed("GitHub 返回 ${resp.code}")
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use UpdateResult.Failed("回应是空的")
                when (channel) {
                    UpdateChannel.STABLE -> parseRelease(body, currentVersionCode)
                    UpdateChannel.BETA -> parseReleases(body, currentVersionCode)
                }
            }
        }.getOrElse {
            Logx.w(TAG, "检查更新失败：${it.message}")
            UpdateResult.Failed(it.message?.take(60) ?: "网络不可用")
        }
    }

    /**
     * 把 GitHub 的 release 响应翻译成 [UpdateResult]。
     *
     * 单独抽出来是为了能离线测：这段逻辑依赖的全是外部格式（tag 前缀、
     * 资产命名、digest 字段），一旦 GitHub 改了字段或者发版脚本改了命名，
     * 症状是「永远提示已是最新」—— 没有异常、没有日志，极难从现象反推。
     */
    internal fun parseRelease(json: String, currentVersionCode: Int): UpdateResult {
        val release = Jsonx.lenient.decodeFromString<GhRelease>(json)
        val asset = release.assets.firstOrNull { ASSET.matches(it.name) }
            ?: return UpdateResult.Failed("这次发布里没有安装包")
        val manifest = manifestOf(release, asset)
            ?: return UpdateResult.Failed("安装包名里没有版本号")
        return decide(manifest, currentVersionCode)
    }

    /**
     * 测试通道：一次给你一串发布，挑 versionCode 最大的那个。
     *
     * ## 为什么不是「取第一条」
     *
     * GitHub 的列表按**发布时间**排，而不是按 versionCode。两者绝大多数时候一致，
     * 但有两种情况会岔开：
     *
     *  · 补发一个旧版本（比如 1.22.3 的修复包在 1.23.0 之后才发）；
     *  · 先打了 tag、过一会儿才上传资产。
     *
     * 按时间取第一条，遇到这两种就会「提示更新，装完还是旧的」，用户得反复点。
     * 按 versionCode 取最大，语义和「该装哪个包」完全一致。
     *
     * 草稿（draft）要排除：它只有仓库管理员看得见，普通 token 请求本来也拿不到，
     * 但万一以后换成带权限的 token，草稿不该出现在用户的更新提示里。
     */
    internal fun parseReleases(json: String, currentVersionCode: Int): UpdateResult {
        val releases = runCatching {
            Jsonx.lenient.decodeFromString<List<GhRelease>>(json)
        }.getOrNull() ?: return UpdateResult.Failed("发布列表读不出来")

        val best = releases.asSequence()
            .filterNot { it.draft }
            .mapNotNull { release ->
                release.assets.firstOrNull { ASSET.matches(it.name) }
                    ?.let { manifestOf(release, it) }
            }
            // 只接受 https：别被中间人换成一个 http 地址
            .filter { it.url.startsWith("https://") }
            .maxByOrNull { it.versionCode }
            ?: return UpdateResult.Failed("发布列表里没有可用的安装包")

        return decide(best, currentVersionCode)
    }

    /** 一条发布 → 更新清单。缺安装包或者名字里没有版本号时返回 null */
    private fun manifestOf(release: GhRelease, asset: GhAsset): UpdateManifest? {
        val code = ASSET.find(asset.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (code <= 0) return null
        return UpdateManifest(
            versionCode = code,
            versionName = release.tagName.removePrefix("v"),
            url = asset.url,
            size = if (asset.size > 0) asset.size else 0,
            sha256 = asset.digest.removePrefix("sha256:"),
            notes = notesOf(release.body),
            publishedAt = release.publishedAt,
        )
    }

    /** 清单 → 结论。校验与比较只写这一处，两个通道共用 */
    private fun decide(manifest: UpdateManifest, currentVersionCode: Int): UpdateResult = when {
        manifest.url.isBlank() -> UpdateResult.Failed("安装包没有下载地址")
        // 只接受 https：别被中间人换成一个 http 地址
        !manifest.url.startsWith("https://") -> UpdateResult.Failed("下载地址不是 HTTPS")
        isNewer(manifest.versionCode, currentVersionCode) -> UpdateResult.Available(manifest)
        else -> UpdateResult.UpToDate
    }

    /**
     * 把 release 正文拆成几条说明。
     *
     * 正文是 Markdown，里面会混着标题、分隔线、图片和空行；全部塞进界面的
     * 那几行小字里会变成一团。这里只取有实际内容的句子，并去掉列表符号。
     */
    private fun notesOf(body: String): List<String> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("#") || it.startsWith("---") || it.startsWith("![") || it.startsWith("|") }
            .map { it.removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotEmpty() }
            .take(12)
            .toList()

    /**
     * 下载并校验：**镜像优先、逐源兜底、同源内续传**。
     *
     * GitHub 直链在国内经常只有几十 KB/s（实测 9~25 KB/s），一次 8MB 要六到
     * 十五分钟，中途断掉的概率相当高。所以真正干活的通常是前面的反代，
     * 直链只在反代全挂时兜底。
     *
     * 单源内仍然做断点续传：先看 `.part` 已经有多少字节，请求时带
     * `Range: bytes=<已下载>-`：
     *
     *  * 服务器回 `206` → 接着往后写；
     *  * 服务器回 `200` → 说明不支持 Range（或者断点已经失效），删掉重下；
     *  * 服务器回 `416` → 断点越界，同样删掉重下。
     *
     * 显式带 `Accept-Encoding: identity`：OkHttp 默认会协商 gzip 并透明解压，
     * 那个模式下 `Content-Length` 会变成 -1、Range 也不再按字节对齐，续传会错位。
     *
     * 换源时**必须**把 `.part` 删掉 —— 两个源给出的半截字节拼在一起，
     * 长度可能恰好对得上，内容却是错的，最后只能靠 sha256 报「文件损坏」，
     * 那时已经白下了一整轮。
     *
     * 校验放在最后整体做（size + sha256）：分段写盘没法边写边算摘要。
     */
    suspend fun download(
        context: Context,
        manifest: UpdateManifest,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val target = File(dir, "dshwatch-${manifest.versionCode}.apk")
        val part = File(dir, "dshwatch-${manifest.versionCode}.apk.part")

        // 已经是完整的旧文件就直接用（用户下载完没来得及装，下次点开不必重下）
        if (target.isFile && (manifest.size <= 0 || target.length() == manifest.size)) {
            if (manifest.sha256.isBlank() || sha256(target).equals(manifest.sha256, ignoreCase = true)) {
                onProgress(1f)
                return@withContext DownloadResult.Done(target)
            }
            target.delete()
        }

        val client = Http.client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        /*
         * 每次进来都从头下。
         *
         * 断点只在本轮本次调用内有效：不同源的半截字节不能混着续（长度对、内容错），
         * 而有了镜像之后一个包只要几秒，跨进程续传的收益抵不上这个错位风险。
         */
        part.delete()

        var lastError = "网络中断"
        var finished = false

        for ((index, source) in downloadSources(manifest.url).withIndex()) {
            if (index > 0) {
                part.delete()
                Logx.w(TAG, "换下一个下载源：${source.take(48)}…")
            }
            lastError = "网络中断"

            for (attempt in 1..MAX_ATTEMPTS) {
                // 每次重试都重新读长度：上一轮可能已经写进去一部分
                val have = if (part.isFile) part.length() else 0L
                if (manifest.size > 0 && have > manifest.size) part.delete()

                /*
                 * 已经齐了就直接去校验。
                 *
                 * 这种情况出现在「上一轮下完但改名失败」：如果还发 `Range: bytes=<size>-`，
                 * 服务器会回 416，而我们把它当成「断点失效」删掉重下 —— 8MB 白下。
                 */
                if (manifest.size > 0 && have == manifest.size) {
                    lastError = ""
                    finished = true
                    break
                }

                val outcome = runCatching {
                    fetchInto(client, source, manifest, part, onProgress)
                }
                if (outcome.isSuccess) {
                    lastError = ""
                    finished = true
                    break
                }
                lastError = outcome.exceptionOrNull()?.message?.take(40) ?: "网络中断"
                Logx.w(TAG, "第 $index 个源第 $attempt 次中断：$lastError")
                if (attempt < MAX_ATTEMPTS) delay(1500L * attempt)
            }
            if (finished) break
        }

        if (!finished) {
            return@withContext DownloadResult.Failed("下载失败：$lastError")
        }

        val written = part.length()
        val actual = sha256(part)
        when {
            manifest.size > 0 && written != manifest.size -> {
                part.delete()
                DownloadResult.Failed("包不完整（$written/${manifest.size} 字节）")
            }

            manifest.sha256.isNotBlank() && !actual.equals(manifest.sha256, true) -> {
                // 内容坏了就必须删，否则下一轮会拿坏数据当断点接着写，永远修不好
                part.delete()
                DownloadResult.Failed("校验失败，文件可能损坏")
            }

            !part.renameTo(target) -> {
                DownloadResult.Failed("无法写入缓存目录")
            }

            else -> {
                onProgress(1f)
                DownloadResult.Done(target)
            }
        }
    }

    /**
     * 跑一次「从断点下到结束」。失败就抛异常，由上层决定要不要再来一轮。
     */
    private fun fetchInto(
        client: OkHttpClient,
        source: String,
        manifest: UpdateManifest,
        part: File,
        onProgress: (Float) -> Unit,
    ) {
        val have = if (part.isFile) part.length() else 0L
        val builder = Request.Builder()
            .url(source)
            .header("Accept-Encoding", "identity")
        if (have > 0) builder.header("Range", "bytes=$have-")

        client.newCall(builder.build()).execute().use { resp ->
            val resumed = when (resp.code) {
                206 -> true
                416 -> {
                    part.delete()
                    throw IOException("断点已失效")
                }

                else -> if (resp.isSuccessful) false else throw IOException("HTTP ${resp.code}")
            }

            // 服务器不认 Range 却回了 200：之前的字节作废，从 0 重写
            if (!resumed) part.delete()

            val body = resp.body ?: throw IOException("没有响应内容")
            var done = if (resumed) have else 0L
            val total = if (manifest.size > 0) {
                manifest.size
            } else {
                val len = body.contentLength()
                if (len > 0) len + done else -1L
            }
            if (total <= 0) onProgress(-1f)

            body.byteStream().use { input ->
                FileOutputStream(part, true).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    /** 清掉缓存里的安装包（装完 / 版本已是最新时调用，手表空间紧张） */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, "apk").deleteRecursively() }
    }

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private const val TAG = "Update"
}
