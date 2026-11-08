package com.dshwatch.domain.model

import kotlinx.serialization.Serializable
import java.io.File

/**
 * 对话里可以承载的媒体类型。
 *
 * 目前只区分图片与视频：这两类在链路上的差别是实质性的 ——
 * 图片一次请求就能拿到结果，视频要「建任务 → 轮询」两步，
 * 而且体积大一个数量级，不能走同一套超时与重试。
 */
@Serializable
enum class MediaKind {
    IMAGE,
    VIDEO,
}

@Serializable
enum class MediaStatus {
    /** 已提交，等模型接单 */
    PENDING,

    /** 生成中（视频会有 progress） */
    RUNNING,

    /** 已生成并落盘 */
    DONE,

    FAILED,

    /** 用户点了停止 */
    CANCELED,
}

/**
 * 一条消息里附带的媒体。
 *
 * 为什么不直接存成 markdown 里的 `![](url)`：
 *  - 模型返回的地址会过期（COS 签名链接），下次打开会话就加载不出来了；
 *  - 手表上大概率没网、或者是弱网，每次滚动都去拉远端等于卡死。
 * 所以生成完立刻下载到应用私有目录，[path] 才是权威来源，[url] 只作溯源。
 */
@Serializable
data class MediaRef(
    val id: String,
    val kind: MediaKind = MediaKind.IMAGE,
    /** 生成结果的远端地址，仅用于「来源」展示与重新下载 */
    val url: String = "",
    /** 本机绝对路径；空串表示还没落盘 */
    val path: String = "",
    val mime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    /** 生成这条媒体的提示词，方便「再画一张」时复用 */
    val prompt: String = "",
    val model: String = "",
    val status: MediaStatus = MediaStatus.DONE,
    /** 0–100，只有视频轮询会更新 */
    val progress: Int = 0,
    val error: String = "",
) {
    val isReady: Boolean get() = status == MediaStatus.DONE
    val isBusy: Boolean get() = status == MediaStatus.PENDING || status == MediaStatus.RUNNING

    /** 落盘后的本地文件；没落盘或文件被清理过都返回 null */
    fun localFile(): File? = path.takeIf { it.isNotBlank() }?.let { f ->
        File(f).takeIf { it.isFile && it.length() > 0 }
    }

    /** 优先本地，其次远端 —— 界面拿它决定「画缩略图」还是「显示占位」 */
    val displaySource: String get() = if (localFile() != null) path else url
}

/**
 * 判断某个模型是「文本模型」还是「出图 / 出视频的模型」。
 *
 * 为什么靠名字猜：
 * OpenAI 兼容协议里没有能力声明字段，`/v1/models` 只给 id。
 * 而这条链路上的命名是稳定的（`agnes-image-*`、`agnes-video-*`、
 * `dall-e-*`、`flux-*`、`*-vl`），按名字判断比让用户逐项勾选更省事，
 * 也不会出现「选了出图模型却按聊天发出去，得到一句『我是文本模型』」。
 */
object MediaModels {

    private val VIDEO_HINTS = listOf("video", "veo", "sora", "kling", "wan-")
    private val IMAGE_HINTS = listOf(
        "image", "dall-e", "dalle", "flux", "imagen", "sd-", "stable-diffusion",
        // 智谱：cogview 是出图模型，名字里没有 image，靠 hints 猜会漏成「文本模型」，
        // 然后对着它发 /chat/completions 拿回 400
        "cogview",
    )

    fun kindOf(model: String): MediaKind? {
        val m = model.lowercase()
        if (m.isBlank()) return null
        // 先判视频：`agnes-video-2.5` 里没有 image，但某些命名会同时带两个词
        if (VIDEO_HINTS.any { m.contains(it) }) return MediaKind.VIDEO
        if (IMAGE_HINTS.any { m.contains(it) }) return MediaKind.IMAGE
        return null
    }

    fun supportsImageInput(model: String): Boolean {
        val m = model.lowercase()
        if (kindOf(m) != null) return false
        // 常见视觉模型命名：*-vl / vision / gpt-4o / claude / gemini / pixtral / llava
        return listOf("vl", "vision", "4o", "claude", "gemini", "pixtral", "llava", "omni")
            .any { m.contains(it) }
    }
}
