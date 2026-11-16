package com.dshwatch.data.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * DeepSeek 官方历史会话归档（`deepseek_data-日期.zip`）的格式常量与读取工具。
 *
 * ## 为什么照着官方的格式做
 *
 * 用户在官方 App 里聊了半年，换到这边来最痛的一步是「历史没了」。
 * 官方 demo 已经把这套格式定义清楚：`manifest.json` + 每个会话一个 JSON
 * （消息是一棵树，靠 `parent_id` 分叉）+ 图片原件 + 一份索引。
 * 按它读，用户导出一次就能整包搬过来；按它写，这边的东西也能搬回去 ——
 * 数据不被锁在任何一个客户端里，比多做一个导出按钮重要得多。
 *
 * ## 宽松读取的理由
 *
 * 归档来自别的版本、别的平台，字段缺失是常态（早期导出没有 `status`，
 * 也没有 `site_icon`）。严格模式会让一份九成可用的文件整体失败，
 * 这里改成「容器字段缺了才失败，内容片段不认识就丢」。
 */
internal object DeepSeekFormat {

    /** 当前支持的格式版本。官方 README 写明 `export_format_version: 3` */
    const val VERSION = 3

    const val MANIFEST = "manifest.json"
    const val INDEX = "chat_session_index.json"
    const val SESSION_DIR = "chat_sessions/"

    /** 单条 JSON 上限。正常会话几十 KB，给到 32MB 是防「zip 里塞个巨型文件」 */
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /** 图片总量上限。超过就只留元数据 —— 手表/手机的空间比历史记录重要 */
    const val MAX_IMAGE_BYTES = 512L * 1024 * 1024

    /** 单张图片上限，与附件导入那条路保持一致 */
    const val MAX_ONE_IMAGE = 32L * 1024 * 1024

    /**
     * 归档里的时间是「UTC RFC 3339、Z 结尾、小数秒精度不固定」。
     * `Instant.parse` 两者都吃，解析失败按 0 处理（时间戳只是排序用的）。
     */
    fun time(text: String?): Long =
        text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    /**
     * 反过来：`Instant.toString()` 在毫秒为 0 时不带小数位，
     * 恰好落在官方正则 `(?:\.(\d+))?Z` 允许的范围里，不需要手拼格式。
     */
    fun stamp(millis: Long): String =
        Instant.ofEpochMilli(if (millis > 0) millis else System.currentTimeMillis()).toString()
}

// ---------------- JsonObject 的宽容取值 ----------------

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.num(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.flag(key: String, fallback: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** 导入失败时给用户看的一句话。带 code 是为了让 UI 决定要不要提示「去重新导出」 */
internal class ArchiveException(val code: Code, message: String) : Exception(message) {
    enum class Code {
        /** 不是 zip / 打不开 */
        NOT_ARCHIVE,

        /** 第一个条目不是 manifest.json，多半拿错了文件 */
        NOT_DEEPSEEK,

        /** 版本比我们新 */
        TOO_NEW,

        /** 版本对不上（旧版或损坏） */
        BAD_VERSION,

        /** 一个会话都读不出来 */
        EMPTY,
    }
}
