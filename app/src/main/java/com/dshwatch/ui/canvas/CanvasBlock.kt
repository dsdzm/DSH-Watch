package com.dshwatch.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dshwatch.ui.components.tap

/**
 * 画布类内容的统一外壳：公式 / 思维导图 / 几何图。
 *
 * 三者共用一套「卡片 + 点开全屏」的行为，原因很实际：
 * 手表的圆屏可视区域只有二百来 dp，任何一张图在消息流里都只能显示个大概。
 * 所以卡片态只负责「让用户认出这是什么」，真正的阅读发生在全屏。
 */
enum class CanvasKind(val id: String, val title: String) {
    MATH("math", "公式"),
    MINDMAP("mindmap", "思维导图"),
    GEOMETRY("geom", "几何图"),
    ;

    companion object {
        /**
         * 从代码块语言标记判断是不是画布。
         *
         * 收得比较宽：模型写 ```latex / ```katex 的都有，写 ```mermaid 的还要看首行
         * 是不是 mindmap。宁可多认几个别名，也不要让一段公式退化成等宽代码。
         */
        fun of(language: String, body: String): CanvasKind? {
            val lang = language.lowercase().trim()
            return when {
                lang in setOf("math", "latex", "katex", "tex", "formula") -> MATH
                lang in setOf("mindmap", "mind-map", "mind_map", "脑图", "思维导图") -> MINDMAP
                lang in setOf("geom", "geometry", "figure", "几何") -> GEOMETRY
                lang == "mermaid" && body.trimStart().startsWith("mindmap", ignoreCase = true) -> MINDMAP
                else -> null
            }
        }
    }
}

/**
 * 消息流里的画布卡片。
 *
 * @param compact 给聊天流用（矮一点）；全屏时传 false
 */
@Composable
fun CanvasCard(
    kind: CanvasKind,
    source: String,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    expandable: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = when (kind) {
        CanvasKind.MATH -> Color(0xFF8FB4E8)
        CanvasKind.MINDMAP -> Color(0xFF7FD1AE)
        CanvasKind.GEOMETRY -> Color(0xFFE0B04A)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = kind.title,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            if (expandable) {
                Text(
                    text = if (compact) "全屏 ›" else "收起",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        // 点击挂在文字上，不挂整张卡片：卡片内部有缩放/拖拽手势，
                        // 外层再拦点击会让「想拖一下」变成「打开全屏」
                        .tap(onClick = { expanded = !expanded }),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        when (kind) {
            CanvasKind.MATH -> MathView(latex = source, compact = compact)
            CanvasKind.MINDMAP -> MindMapView(source = source, compact = compact)
            CanvasKind.GEOMETRY -> GeometryView(source = source, compact = compact)
        }
        if (expandable && compact) {
            Spacer(Modifier.height(4.dp))
        }
    }

    if (expanded) {
        CanvasFullscreen(kind = kind, source = source, onClose = { expanded = false })
    }
}

/** 全屏画布。用 Dialog 而不是新开路由：画布没有自己的状态，返回键就该就地消失 */
@Composable
fun CanvasFullscreen(
    kind: CanvasKind,
    source: String,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF00B0D12))
                .padding(10.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = kind.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF8FB4E8),
                    )
                    Text(
                        text = "关闭 ✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF9AA7BD),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .tap(onClick = onClose)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    when (kind) {
                        CanvasKind.MATH -> MathView(latex = source, fontSize = 26f)
                        CanvasKind.MINDMAP -> MindMapView(source = source, modifier = Modifier.fillMaxSize())
                        CanvasKind.GEOMETRY -> GeometryView(source = source, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            // 关闭热区铺满整屏底部：圆屏边缘的「关闭」文字不好点
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth()
                    .height(48.dp),
            )
        }
    }
}
