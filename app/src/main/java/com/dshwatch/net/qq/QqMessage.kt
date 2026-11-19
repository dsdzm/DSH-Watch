package com.dshwatch.net.qq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OneBot 11 的一条消息段。
 *
 * 字段是「所有类型共用一张表」而不是 sealed class：解析端不想在每次
 * 遇到新类型（`mface` / `forward` / `json`）时都改一次 when 分支，
 * 未知类型保留 type 与原始 data 就能安静地忽略掉。
 *
 * 各字段归属：
 *  - `text`：text 段的正文
 *  - `url` / `file`：image 段的远端地址与文件名（NapCat 两个都给，旧实现只给 file）
 *  - `qq`：at 段的目标，reply 段这里是空（见 [id]）
 *  - `id`：reply 段引用的消息 id
 */
data class QqSegment(
    val type: String,
    val text: String = "",
    val url: String = "",
    val file: String = "",
    val qq: String = "",
    val id: String = "",
)

/**
 * 从 OneBot 拉回来的一条消息。
 *
 * [peerId] 是「这间屋子」的 id：私聊是对方 QQ 号，群聊是群号。
 * 群消息里 [senderId] 才是发话的人 —— 两者在群聊里不同，
 * 用错一个就会把回复发到陌生人私聊里。
 */
data class QqMessage(
    val id: String,
    val peerId: String,
    val isGroup: Boolean,
    val senderId: String,
    val senderName: String,
    val timeSec: Long,
    val segments: List<QqSegment>,
) {
    val text: String
        get() = segments.filter { it.type == "text" }.joinToString("") { it.text }

    val imageUrls: List<String>
        get() = segments.filter { it.type == "image" }
            .map { it.url.ifBlank { it.file } }
            .filter { it.isNotBlank() }

    val atTargets: List<String>
        get() = segments.filter { it.type == "at" }.map { it.qq }.filter { it.isNotBlank() }

    /** 引用回复的目标消息 id（CQ 码里的 `[CQ:reply,id=...]`） */
    val replyId: String
        get() = segments.firstOrNull { it.type == "reply" }?.id.orEmpty()

    /** 是否 @ 了某个号 */
    fun mentions(qq: String): Boolean = qq.isNotBlank() && atTargets.contains(qq)

    /** 去掉 @ 与回复引用后的正文，用于判断前缀 / 交给模型 */
    fun cleanText(selfId: String): String =
        segments.filter { it.type == "text" }.joinToString("") { it.text }
            .trim()
}

/**
 * OneBot 消息解析。
 *
 * 纯函数，不碰网络 —— 这一层是整个桥接里最容易出错的地方（字段名在
 * NapCat / go-cqhttp / SnowLuma 之间并不完全一致），单独拆出来才能
 * 用固定样本反复回归。
 */
object QqParser {

    /**
     * 解析一条历史消息。
     *
     * 兼容两种 `message` 形态：
     *  - 数组（推荐，NapCat 默认）：`[{type:"text",data:{text:"hi"}}]`
     *  - 字符串（CQ 码）：`"hi[CQ:image,file=a.jpg]"`
     */
    fun message(obj: JsonObject, isGroup: Boolean, fallbackPeer: String = ""): QqMessage? {
        val id = obj.str("message_id").ifBlank { obj.str("msg_id") }
        if (id.isBlank()) return null

        val sender = runCatching { obj["sender"]?.jsonObject }.getOrNull()
        val senderId = sender?.str("user_id").orEmpty().ifBlank { obj.str("user_id") }
        val senderName = sender?.str("card").orEmpty()
            .ifBlank { sender?.str("nickname").orEmpty() }
            .ifBlank { senderId }

        val peer = when {
            isGroup -> obj.str("group_id").ifBlank { fallbackPeer }
            else -> obj.str("user_id").ifBlank { senderId }.ifBlank { fallbackPeer }
        }

        val segs = segments(obj["message"]) ?: segments(obj["raw_message"]) ?: emptyList()

        return QqMessage(
            id = id,
            peerId = peer,
            isGroup = isGroup,
            senderId = senderId,
            senderName = senderName,
            timeSec = obj.str("time").toLongOrNull() ?: 0L,
            segments = segs,
        )
    }

    /** `message` 字段可能是数组、字符串、或缺失（这时看 `raw_message`） */
    fun segments(el: JsonElement?): List<QqSegment>? {
        if (el == null) return null
        return when (el) {
            is JsonArray -> el.mapNotNull { item ->
                val o = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                val type = o.str("type")
                val d = runCatching { o["data"]?.jsonObject }.getOrNull()
                QqSegment(
                    type = type,
                    text = d?.str("text").orEmpty(),
                    url = d?.str("url").orEmpty(),
                    file = d?.str("file").orEmpty(),
                    qq = d?.str("qq").orEmpty(),
                    id = d?.str("id").orEmpty(),
                )
            }
            is JsonPrimitive -> parseCq(el.contentOrNull.orEmpty())
            else -> null
        }
    }

    /**
     * CQ 码解析。
     *
     * 只认 `[CQ:type,k=v,k=v]` 这一种写法。转义（`&#91;` 之类）按
     * OneBot 规范反转回来，否则消息里出现方括号时会被整段吞掉。
     */
    fun parseCq(raw: String): List<QqSegment> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<QqSegment>()
        var i = 0
        val buf = StringBuilder()

        fun flushText() {
            if (buf.isNotEmpty()) {
                out += QqSegment(type = "text", text = unescape(buf.toString()))
                buf.setLength(0)
            }
        }

        while (i < raw.length) {
            val start = raw.indexOf("[CQ:", i)
            if (start < 0) {
                buf.append(raw, i, raw.length)
                break
            }
            val end = raw.indexOf(']', start)
            if (end < 0) {
                buf.append(raw, i, raw.length)
                break
            }
            if (start > i) buf.append(raw, i, start)
            flushText()

            val body = raw.substring(start + 4, end)
            val comma = body.indexOf(',')
            val type = if (comma < 0) body else body.substring(0, comma)
            val kv = HashMap<String, String>()
            if (comma >= 0) {
                body.substring(comma + 1).split(',').forEach { pair ->
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        kv[pair.substring(0, eq).trim()] = unescape(pair.substring(eq + 1).trim())
                    }
                }
            }
            out += QqSegment(
                type = type,
                text = kv["text"].orEmpty(),
                url = kv["url"].orEmpty(),
                file = kv["file"].orEmpty(),
                qq = kv["qq"].orEmpty(),
                id = kv["id"].orEmpty(),
            )
            i = end + 1
        }
        flushText()
        return out
    }

    private fun unescape(s: String): String = s
        .replace("&#91;", "[")
        .replace("&#93;", "]")
        .replace("&#44;", ",")
        .replace("&amp;", "&")

}

/**
 * 取一个字符串字段。
 *
 * OneBot 的字段类型不统一：`user_id` 在部分实现里是数字、部分里是字符串，
 * `message_id` 甚至可能超过 Int。统一走 `contentOrNull` 读成字符串，
 * 不做数值解析 —— 这些值从来不参与运算。
 */
fun JsonObject.str(key: String): String {
    val p = this[key] as? JsonPrimitive ?: return ""
    return p.contentOrNull.orEmpty()
}

/** 把要发出去的内容转成 OneBot 的数组消息 */
object QqOut {
    fun text(t: String): List<QqSegment> =
        if (t.isBlank()) emptyList() else listOf(QqSegment("text", text = t))

    fun imageBase64(b64: String): QqSegment =
        QqSegment("image", file = "base64://$b64")

    fun of(text: String, images: List<String> = emptyList()): List<QqSegment> =
        buildList {
            if (text.isNotBlank()) add(QqSegment("text", text = text))
            images.forEach { b64 -> add(imageBase64(b64)) }
        }
}
