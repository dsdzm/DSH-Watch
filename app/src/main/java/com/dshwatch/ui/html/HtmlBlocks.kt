package com.dshwatch.ui.html

/**
 * 从消息正文里找出「可以当界面渲染的 HTML」。
 *
 * ## 为什么是这两种形态
 *
 * 模型或用户给的 HTML 只有两种自然写法：
 *
 *  1. 包在 ` ```html ` 围栏里 —— 模型最常用的方式，也最不容易和正文混淆；
 *  2. 整段就是一个文档（以 `<!DOCTYPE html>` 或 `<html` 开头）。
 *
 * 不去猜「半段 HTML 片段能不能渲染」：正文里出现一个 `<br>`、一句 `<span>`
 * 是常事，把它们当界面渲染只会让聊天气泡莫名其妙地空掉。
 * 需要的门槛是「这是一份完整的界面」，而不是「这里有标签」。
 */
object HtmlBlocks {

    /** 一份被识别出来的界面文档 */
    data class Block(
        /** 要交给 WebView 的 HTML */
        val html: String,
        /** 原文里这一段的完整文本（含围栏），用于从正文里剔除 */
        val raw: String,
    )

    private val FENCE = Regex("```(?:html|HTML)[ \\t]*\\r?\\n([\\s\\S]*?)```")
    private val DOC = Regex("(?is)<!DOCTYPE html.*?</html\\s*>|<html[\\s>][\\s\\S]*?</html\\s*>")

    /** 找第一份界面文档；没有就返回 null */
    fun find(text: String): Block? {
        if (text.isBlank()) return null
        FENCE.find(text)?.let { m ->
            val body = m.groupValues.getOrNull(1).orEmpty().trim()
            if (body.isNotBlank()) return Block(body, m.value)
        }
        DOC.find(text)?.let { m ->
            val body = m.value.trim()
            if (body.isNotBlank()) return Block(body, m.value)
        }
        return null
    }

    /** 把界面文档从正文里去掉，剩下的文字仍然按 Markdown 渲染 */
    fun strip(text: String, block: Block): String =
        text.replace(block.raw, "").trim()
}
