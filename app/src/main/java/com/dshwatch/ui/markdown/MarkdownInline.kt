package com.dshwatch.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

data class InlineColors(
    val text: Color,
    val muted: Color,
    val codeText: Color,
    val codeBackground: Color,
    val link: Color,
    val bold: Color,
)

/**
 * 行内样式解析。
 *
 * 支持的标记：`code`、**粗体**、__粗体__、*斜体*、_斜体_、~~删除线~~、[文字](链接)、裸链接。
 * 不支持嵌套（例如 **加粗里再放斜体**）——手表上出现的概率极低，
 * 而支持嵌套要多一轮递归扫描，对低端机是实打实的开销。
 */
object MarkdownInline {

    private val tokenRegex = Regex(
        """(`[^`\n]+`)""" + "|" +
            """(\*\*[^*\n]+\*\*)""" + "|" +
            """(__[^_\n]+__)""" + "|" +
            """(\*[^*\n]+\*)""" + "|" +
            """(_[^_\n]+_)""" + "|" +
            """(~~[^~\n]+~~)""" + "|" +
            """(\[[^\]\n]+\]\([^)\n]+\))""" + "|" +
            """(https?://[^\s<>)\]]+)"""
    )

    fun render(text: String, colors: InlineColors): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        for (m in tokenRegex.findAll(text)) {
            if (m.range.first > cursor) {
                append(text.substring(cursor, m.range.first))
            }
            val token = m.value
            when {
                token.startsWith("`") -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = colors.codeText,
                        background = colors.codeBackground,
                    )
                ) { append(token.trim('`')) }

                token.startsWith("**") -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = colors.bold)
                ) { append(token.removeSurrounding("**")) }

                token.startsWith("__") -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = colors.bold)
                ) { append(token.removeSurrounding("__")) }

                token.startsWith("~~") -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.muted)
                ) { append(token.removeSurrounding("~~")) }

                token.startsWith("[") -> {
                    val close = token.indexOf("](")
                    val label = token.substring(1, close)
                    val url = token.substring(close + 2, token.length - 1)
                    withStyle(
                        SpanStyle(
                            color = colors.link,
                            textDecoration = TextDecoration.Underline,
                        )
                    ) { append(label.ifBlank { url }) }
                }

                token.startsWith("http") -> withStyle(
                    SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
                ) { append(token) }

                token.startsWith("*") -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic)
                ) { append(token.removeSurrounding("*")) }

                token.startsWith("_") -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic)
                ) { append(token.removeSurrounding("_")) }

                else -> append(token)
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
