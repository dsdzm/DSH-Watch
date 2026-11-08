package com.dshwatch.domain.model

import kotlinx.serialization.Serializable

/**
 * 用户随消息上传的文件。
 *
 * ## 为什么只存路径，不存正文
 *
 * 会话 JSON 是要整包读写的（`ConversationStore` 一次 load 全部消息）。
 * 把一本书的正文塞进每条消息，打开会话时就要解几十 MB 的 JSON ——
 * 手表上这是致命的。所以正文提取一次，落成 `extracted.txt`，
 * 发送时按需读、按需截断；消息里只留元信息（名字、大小、字符数）。
 *
 * ## 为什么不做「文件 API」
 *
 * OpenAI / 智谱 / DeepSeek 的文件接口都是给 batch、微调、检索用的，
 * 聊天接口本身仍然只吃文本（智谱的 `file` 参数绑定的是知识库里的 file_id）。
 * 想让「上传一个文件然后提问」在所有兼容服务上都成立，唯一稳的做法是
 * 本地提取文本、拼进消息。这条路对任何 OpenAI 兼容端点都有效，
 * 也不需要多一个上传往返和一份会在 24 小时后失效的远端副本。
 */
@Serializable
data class Attachment(
    val id: String,
    val name: String,
    /** 原文件 mime，可能为空（很多文件管理器不给） */
    val mime: String = "",
    val sizeBytes: Long = 0,
    /** 见 [AttachmentKinds] */
    val kind: String = AttachmentKinds.TEXT,
    /** 原始文件的本地副本；图片会走多模态输入，文档只留作「用户能看到自己传了什么」 */
    val path: String = "",
    /** 提取出的纯文本文件路径；空表示提取失败或本来就不是文本 */
    val textPath: String = "",
    /** 正文字符数（提取后） */
    val chars: Int = 0,
    /** 提取失败的原因。非空时这条附件不参与请求 */
    val error: String = "",
) {
    val ok: Boolean get() = error.isBlank()

    /** 给界面用的一句话摘要 */
    fun summary(): String = when {
        error.isNotBlank() -> error
        kind == AttachmentKinds.IMAGE -> "图片"
        chars > 0 -> "$chars 字"
        else -> "空文件"
    }
}

object AttachmentKinds {
    const val TEXT = "text"
    const val DOCX = "docx"
    const val PDF = "pdf"
    const val IMAGE = "image"
    const val OTHER = "other"

    /** 单个文件最多提取这么多字符：再长也只是把上下文烧光，模型读不完 */
    const val MAX_CHARS = 60_000

    /** 单次最多带几个附件 */
    const val MAX_FILES = 3
}
