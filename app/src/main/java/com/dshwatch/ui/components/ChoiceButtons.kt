package com.dshwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.Choices

/**
 * 把回复末尾的 A / B / C 渲染成一排可点的按钮。
 *
 * ## 为什么不做成「气泡里的一行字」
 *
 * 手表上一行字只有 17 个汉字的宽度，四个选项折成四段换行，看起来像正文，
 * 用户根本意识不到那是能点的。做成一整条按钮之后，左边缘对齐、有稳定高度、
 * 有一枚字母徽标 —— 这三件事合起来才让它读起来是「一个控件」。
 *
 * ## 点击发什么
 *
 * 发 [Choices.Choice.raw]，也就是「A. 走过去看看」整行。
 * 只发正文（"走过去看看"）会让模型失去「这是在四个选项里挑了一个」的语境，
 * 有时会反问你"走过去还是留下？"；带上序号，它立刻明白这是一次选择。
 *
 * @param enabled 只有最新一条消息可点。历史消息里的选项点下去会往对话里
 *                插一条过期的回复，所以那些一律置灰。
 */
@Composable
fun ChoiceButtons(
    choices: List<Choices.Choice>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPick: (Choices.Choice) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (com.dshwatch.ui.theme.isM3eUi) {
        M3eChoiceButtons(choices, modifier, enabled, onPick, scheme)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        choices.forEach { c ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(
                        shape = RoundedCornerShape(12.dp),
                        cornerRadius = 12.dp,
                        enabled = enabled,
                        onClick = if (enabled) withHaptic { onPick(c) } else null,
                    )
                    .padding(start = 8.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (c.custom) {
                                scheme.onSurfaceVariant.copy(alpha = 0.16f)
                            } else {
                                scheme.primary.copy(alpha = 0.20f)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = c.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.custom) scheme.onSurfaceVariant else scheme.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = c.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        scheme.onSurface
                    } else {
                        scheme.onSurface.copy(alpha = 0.45f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (c.custom) Icons.Outlined.Edit else Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant.copy(alpha = if (enabled) 0.9f else 0.35f),
                    modifier = Modifier
                        .widthIn(max = 16.dp)
                        .size(15.dp),
                )
            }
        }
    }
}


/**
 * M3E 版的选项按钮。
 *
 * 和经典版结构一样（序号徽标 + 文本 + 箭头），差别只是外壳：
 * 每一条是一张独立 [androidx.wear.compose.material3.Card]，
 * 按下时整张卡片有形变与涟漪 —— 这是 M3E 里"可点的东西"该有的反馈。
 */
@Composable
private fun M3eChoiceButtons(
    choices: List<Choices.Choice>,
    modifier: Modifier,
    enabled: Boolean,
    onPick: (Choices.Choice) -> Unit,
    scheme: androidx.compose.material3.ColorScheme,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        choices.forEach { c ->
            androidx.wear.compose.material3.Card(
                onClick = { onPick(c) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(
                                if (c.custom) {
                                    scheme.onSurfaceVariant.copy(alpha = 0.16f)
                                } else {
                                    scheme.primary.copy(alpha = 0.20f)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = c.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (c.custom) scheme.onSurfaceVariant else scheme.primary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = c.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (c.custom) Icons.Outlined.Edit else Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
