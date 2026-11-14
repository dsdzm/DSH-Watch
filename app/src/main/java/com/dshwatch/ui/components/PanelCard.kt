package com.dshwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.Panels

/**
 * 状态面板：标题 + 若干条目 + 每条可展开的详情。
 *
 * ## 形制从哪来
 *
 * 一眼要看完的是「谁、有什么、多少」，要细看的才点开 —— 手表一屏只有
 * 200dp 出头，把详情全铺开会把对话顶走。所以条目默认收起，右侧一个小方块
 * 写着「阅」，点开在这一行下面展开，不跳页、不弹窗。
 *
 * 数值写成 `48/100` 时画成进度条：数字本身能读，但一眼扫过去判断"剩多少"
 * 靠的是条子的长短，不是心算。
 */
@Composable
fun PanelCard(
    panel: Panels.Panel,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val expanded = remember(panel) { mutableStateMapOf<Int, Boolean>() }

    // M3E：面板卡片换成更高一层的容器色、更大的圆角；玻璃材质在 M3E 下不参与
    val m3e = com.dshwatch.ui.theme.isM3eUi
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (m3e) {
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(scheme.surfaceContainerHigh)
                } else {
                    Modifier.glassSurface(
                        shape = RoundedCornerShape(14.dp),
                        cornerRadius = 14.dp,
                    )
                },
            )
            .padding(horizontal = 9.dp, vertical = 9.dp),
    ) {
        // ---- 标题 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.primary.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = panel.title.take(1),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = panel.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (panel.subtitle.isNotBlank()) {
                    Text(
                        text = panel.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(7.dp))

        // ---- 条目 ----
        panel.rows.forEachIndexed { index, row ->
            val open = expanded[index] == true
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .then(
                            if (row.expandable) {
                                Modifier.clickable { expanded[index] = !open }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.expandable) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier
                                .size(11.dp)
                                .graphicsLayer { rotationZ = if (open) 90f else 0f },
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.onSurfaceVariant.copy(alpha = 0.5f)),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.tag.isNotBlank()) {
                        Spacer(Modifier.width(5.dp))
                        TagChip(row.tag)
                    }
                    Spacer(Modifier.weight(1f))
                    if (row.value.isNotBlank()) {
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (row.expandable) {
                        Spacer(Modifier.width(6.dp))
                        MarkBtn(open)
                    }
                }

                if (open) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 17.dp, top = 1.dp, bottom = 5.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(scheme.surfaceContainerHighest.copy(alpha = 0.62f))
                            .border(
                                width = 1.dp,
                                color = scheme.outlineVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(9.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        row.details.forEach { d ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                if (d.key.isNotBlank()) {
                                    Text(
                                        text = d.key,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.primary.copy(alpha = 0.85f),
                                        modifier = Modifier.width(26.dp),
                                    )
                                }
                                Text(
                                    text = d.text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurface.copy(alpha = 0.88f),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                // 数值是 48/100 这种形状时补一条进度
                Panels.ratioOf(row.value)?.let { (now, total) ->
                    val p = (now / total).coerceIn(0.001f, 1f)
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.onSurface.copy(alpha = 0.12f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(p)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.primary.copy(alpha = 0.75f)),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** 条目右边的「阅」方块：收起时是实心，展开后变描边，视觉上退到后面 */
@Composable
private fun MarkBtn(open: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(17.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (open) {
                    scheme.primary.copy(alpha = 0.12f)
                } else {
                    scheme.primary.copy(alpha = 0.88f)
                },
            )
            .border(
                width = if (open) 1.dp else 0.dp,
                color = if (open) scheme.primary.copy(alpha = 0.5f) else scheme.primary,
                shape = RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "阅",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = if (open) scheme.primary else scheme.onPrimary,
        )
    }
}

@Composable
private fun TagChip(text: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(scheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.primary,
            maxLines = 1,
        )
    }
}
