package com.dshwatch.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.theme.LocalPerf
import com.dshwatch.ui.theme.MonoSmall

/**
 * Markdown 渲染组件。
 *
 * 性能约定：
 *  - 解析结果用 remember(text) 缓存，流式刷新时只有文本真的变了才重解析；
 *  - 块数超过 perf.markdownMaxBlocks 时截断，避免超长回答让一帧里出现几百个 Text；
 *  - 代码块默认只渲染前 maxCodeBlockLines 行，可点开；
 *  - 低端机（perf.enableCodeHighlight = false）跳过语法着色，直接用等宽单色。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onCopyCode: ((String) -> Unit)? = null,
) {
    val perf = LocalPerf.current
    val blocks = remember(text, perf.markdownMaxBlocks) {
        MarkdownParser.parse(text, perf.markdownMaxBlocks)
    }
    if (blocks.isEmpty()) return

    val colors = markdownInlineColors()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((index, block) in blocks.withIndex()) {
            when (block) {
                is MdBlock.Heading -> Text(
                    text = MarkdownInline.render(block.text, colors),
                    style = headingStyle(block.level),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )

                is MdBlock.Paragraph -> Text(
                    text = MarkdownInline.render(block.text, colors),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MdBlock.Bullet -> ListItemRow(
                    marker = block.marker.toString(),
                    level = block.level,
                    content = MarkdownInline.render(block.text, colors),
                )

                is MdBlock.Ordered -> ListItemRow(
                    marker = block.number + ".",
                    level = block.level,
                    content = MarkdownInline.render(block.text, colors),
                )

                is MdBlock.Quote -> QuoteBlock(block, colors)
                MdBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 3.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                is MdBlock.Code -> CodeBlock(key = index, block = block, onCopyCode = onCopyCode)
                is MdBlock.Table -> TableBlock(block, colors)
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    3 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.labelLarge
}

@Composable
private fun ListItemRow(marker: String, level: Int, content: AnnotatedString) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (level * 10).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun QuoteBlock(block: MdBlock.Quote, colors: InlineColors) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .background(
                    MaterialTheme.colorScheme.secondary,
                    RoundedCornerShape(2.dp),
                )
                .height(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = MarkdownInline.render(block.text, colors),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 代码块。
 *
 * 两级折叠，对应两种不同的诉求：
 *  1. **整体折叠** —— 点标题条收起整段代码。回答里贴了三四个大代码块时，
 *     这是唯一能快速扫完全文的办法；
 *  2. **行数限制** —— 展开后仍只渲染前 `perf.maxCodeBlockLines` 行，
 *     点「显示全部」才补上剩下的。避免一段 300 行的输出把整条消息撑成几屏。
 *
 * 另外，语言标签旁边直接给出行数 —— 用户判断"要不要看"靠的就是这个数字。
 */
@Composable
private fun CodeBlock(key: Int, block: MdBlock.Code, onCopyCode: ((String) -> Unit)?) {
    val perf = LocalPerf.current
    val clip = LocalClipboardManager.current
    val s = LocalStrings.current
    var collapsed by remember(key) { mutableStateOf(false) }
    var showAllLines by remember(key) { mutableStateOf(false) }

    val lines = remember(block.code) { block.code.split('\n') }
    val tooLong = lines.size > perf.maxCodeBlockLines
    val limited = tooLong && !showAllLines
    val visibleCode = remember(block.code, limited, perf.maxCodeBlockLines) {
        if (limited) lines.take(perf.maxCodeBlockLines).joinToString("\n") else block.code
    }

    val codeColors = CodeColors(
        plain = MaterialTheme.colorScheme.onSurface,
        keyword = MaterialTheme.colorScheme.tertiary,
        string = MaterialTheme.colorScheme.secondary,
        comment = MaterialTheme.colorScheme.outline,
        number = MaterialTheme.colorScheme.primary,
        function = MaterialTheme.colorScheme.onSurface,
    )

    val body = remember(visibleCode, perf.enableCodeHighlight, codeColors) {
        if (perf.enableCodeHighlight && visibleCode.length <= 8_000) {
            CodeHighlighter.highlight(visibleCode, block.language, codeColors)
        } else {
            AnnotatedString(visibleCode)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tap(onClick = withHaptic { collapsed = !collapsed }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(block.language.ifBlank { if (block.closed) "code" else "…" })
                    append(" · ")
                    append(lines.size)
                    append(" 行")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ActionChip(s.copy) {
                clip.setText(AnnotatedString(block.code))
                onCopyCode?.invoke(block.code)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (collapsed) {
                    Icons.Filled.KeyboardArrowDown
                } else {
                    Icons.Filled.KeyboardArrowUp
                },
                contentDescription = if (collapsed) s.expandReasoning else s.collapseReasoning,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }

        if (!collapsed) {
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = body,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                )
            }
            if (tooLong) {
                Spacer(Modifier.height(3.dp))
                ActionChip(
                    if (showAllLines) {
                        "收起（只看前 ${perf.maxCodeBlockLines} 行）"
                    } else {
                        "显示全部 ${lines.size} 行"
                    }
                ) { showAllLines = !showAllLines }
            }
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, colors: InlineColors) {
    val columnCount = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp),
        ) {
            if (block.header.isNotEmpty()) {
                TableRow(block.header, columnCount, colors, header = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            block.rows.take(30).forEach { row ->
                TableRow(row, columnCount, colors, header = false)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    columnCount: Int,
    colors: InlineColors,
    header: Boolean,
) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        for (i in 0 until columnCount) {
            val cell = cells.getOrNull(i).orEmpty()
            Text(
                text = MarkdownInline.render(cell, colors),
                style = if (header) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.bodySmall
                },
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(86.dp)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .tap(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
fun markdownInlineColors(): InlineColors = InlineColors(
    text = MaterialTheme.colorScheme.onSurface,
    muted = MaterialTheme.colorScheme.onSurfaceVariant,
    codeText = MaterialTheme.colorScheme.tertiary,
    codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
    link = MaterialTheme.colorScheme.primary,
    bold = MaterialTheme.colorScheme.onSurface,
)
