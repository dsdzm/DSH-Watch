package com.dshwatch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme as W3
import com.dshwatch.domain.ContextMeter
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.m3e.M3eStaticSurface
import com.dshwatch.ui.theme.isM3eUi
import com.dshwatch.util.Fmt

/**
 * 上下文构成面板。
 *
 * ## 为什么从用量页搬到对话页的顶栏
 *
 * 这块内容原来只在「设置 → 模型 → 用量」里，用户的反馈很直接：
 * **「我在设置里没有找到你说的具体用量」**。他说得对 —— 要判断
 * 「这一轮为什么贵」，人正看着对话；跳到设置里翻二级页，看到的是
 * 一分钟前那一刻的快照，回来还得再找一遍位置。
 *
 * 所以面板本身不变，入口搬到**正在发生这件事的地方**：对话页顶部
 * 那颗「上下文」按钮。用量页保留同一张卡片（历史统计仍在那里），
 * 两个入口读同一份数据、同一套组件，不会出现「两处数字不一样」。
 *
 * ## 面板为什么是这个形状
 *
 * 手表上一屏十来行、没有 hover，桌面插件的多列柱状图照搬只会读不懂。
 * 这里只留四件事，且每件都能直接指导动作：
 *
 * 1. **一行大数字**（已用 / 预算 + 百分比），颜色随档位变；
 * 2. **一根分段条**，工具输出用 error 色 —— 它是最该被折叠的那段；
 * 3. **可折叠省下的量**，唯一能直接指导动作的数字；
 * 4. **超限 / 接近满** 时一句结论（开新会话）。
 *
 * 分母用 [ContextMeter.Insight.usable]（窗口减预留输出）而不是窗口本身：
 * 模型真实能用的就是这一段，拿窗口当分母会让人在 90% 时还以为"才用了一半"。
 */
@Composable
fun ContextUsageCard(insight: ContextMeter.Insight, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val ui = isM3eUi

    val styleBig = if (ui) {
        W3.typography.titleLarge ?: MaterialTheme.typography.headlineSmall
    } else {
        MaterialTheme.typography.headlineSmall
    }
    val stylePercent = if (ui) {
        W3.typography.titleSmall ?: MaterialTheme.typography.titleSmall
    } else {
        MaterialTheme.typography.titleSmall
    }
    val styleRow = if (ui) {
        W3.typography.labelMedium ?: MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.labelMedium
    }
    val styleNote = if (ui) {
        W3.typography.labelSmall ?: MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.labelSmall
    }
    // M3E 的行更松：Wear 的触摸目标从 48dp 起步，行距贴太紧会让整块看起来是一团
    val rowGap = if (ui) 3.dp else 1.dp

    val levelColor = when (insight.level) {
        ContextMeter.Level.OK -> colors.primary
        ContextMeter.Level.WARN -> colors.tertiary
        ContextMeter.Level.HOT, ContextMeter.Level.OVER -> colors.error
    }

    val body: @Composable ColumnScope.() -> Unit = {
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = Fmt.tokens(insight.total), style = styleBig, color = levelColor)
            Text(
                text = " / ${Fmt.tokens(insight.usable)}",
                style = styleRow,
                color = colors.outline,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = "${(insight.ratio * 100).toInt()}%",
                style = stylePercent,
                color = levelColor,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(6.dp))
        ContextMeterBar(insight)
        Spacer(Modifier.height(8.dp))

        for (part in insight.parts) {
            if (part.tokens <= 0) continue
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = rowGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(contextSectionColor(part.section)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = contextSectionLabel(part.section),
                    style = styleRow,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${Fmt.tokens(part.tokens)} · ${(insight.share(part.section) * 100).toInt()}%",
                    style = styleRow,
                    color = colors.onSurface,
                    maxLines = 1,
                )
            }
        }

        // 可折叠的量：只有真能省出一截时才值得占一行
        if (insight.reclaimable > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = rowGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = s.ctxReclaimable,
                    style = styleRow,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Fmt.tokens(insight.reclaimable),
                    style = styleRow,
                    color = colors.tertiary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = rowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = s.ctxTurns,
                style = styleRow,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(text = insight.turns.toString(), style = styleRow, color = colors.onSurface)
        }

        val warn = when (insight.level) {
            ContextMeter.Level.OVER -> s.ctxOver
            ContextMeter.Level.HOT -> s.ctxHot
            else -> ""
        }
        if (warn.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(text = warn, style = styleNote, color = colors.error)
        }
        Spacer(Modifier.height(6.dp))
    }

    if (ui) {
        M3eStaticSurface {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                content = body,
            )
        }
    } else {
        SettingCard(modifier = modifier, content = body)
    }
}

/**
 * 对话页顶栏按钮打开的弹窗。
 *
 * 内容自己滚：圆屏上这一块大约 250dp，加上标题和按钮会顶到圆周外。
 * 所以这里把 `scrollable` 关掉、自己给 `heightIn` —— 两层滚动容器会让
 * Compose 直接抛 `infinity maximum height constraints`（见 GlassDialog 注释）。
 */
@Composable
fun ContextUsageDialog(
    insight: ContextMeter.Insight,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    GlassDialog(
        onDismissRequest = onDismiss,
        title = s.ctxTitle,
        maxContentHeight = 300,
        scrollable = false,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 250.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ContextUsageCard(insight)
            }
        },
        buttons = { GlassDialogButton(s.close, onClick = onDismiss) },
    )
}

/**
 * 分段占比条。
 *
 * 段宽按 `tokens / usable` 算，总和不到 100% 时右侧留出灰底 ——
 * 那条灰就是"还剩多少"，比在数字上做减法直观。超预算时各段按比例压到整条内。
 */
@Composable
private fun ContextMeterBar(insight: ContextMeter.Insight) {
    val colors = MaterialTheme.colorScheme
    val emptyColor = colors.surfaceContainerHighest
    val denom = insight.usable.coerceAtLeast(1)

    // 颜色必须在进入绘制 lambda 之前取好：DrawScope 里不能调 @Composable
    val colorOf = mapOf(
        ContextMeter.Section.SYSTEM to colors.outline,
        ContextMeter.Section.USER to colors.primary,
        ContextMeter.Section.ASSISTANT to colors.secondary,
        ContextMeter.Section.TOOL_ARGS to colors.tertiary,
        ContextMeter.Section.TOOL_RESULTS to colors.error,
    )

    Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = emptyColor, size = size, cornerRadius = radius)

        val track = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    cornerRadius = radius,
                ),
            )
        }
        clipPath(track) {
            var x = 0f
            for (part in insight.parts) {
                if (part.tokens <= 0) continue
                val remaining = size.width - x
                if (remaining <= 0f) break
                val width = (size.width * part.tokens / denom).coerceAtMost(remaining)
                if (width <= 0f) continue
                drawRect(
                    color = colorOf[part.section] ?: emptyColor,
                    topLeft = Offset(x, 0f),
                    size = Size(width, size.height),
                )
                x += width
            }
        }
    }
}

/**
 * 五段的配色。
 *
 * 工具输出用 error 是有意的：它不是"错误"，而是唯一一段**可以立刻收回来**的
 * 占用，用户看到红色第一反应就是"这块能砍"，这正是想要的行为。
 *
 * 两套界面读的是同一份色板（M3E 主题由同一 ColorScheme 映射而来），
 * 所以这里不需要分支。
 */
@Composable
private fun contextSectionColor(section: ContextMeter.Section): Color {
    val c = MaterialTheme.colorScheme
    return when (section) {
        ContextMeter.Section.SYSTEM -> c.outline
        ContextMeter.Section.USER -> c.primary
        ContextMeter.Section.ASSISTANT -> c.secondary
        ContextMeter.Section.TOOL_ARGS -> c.tertiary
        ContextMeter.Section.TOOL_RESULTS -> c.error
    }
}

@Composable
private fun contextSectionLabel(section: ContextMeter.Section): String {
    val s = LocalStrings.current
    return when (section) {
        ContextMeter.Section.SYSTEM -> s.ctxSystem
        ContextMeter.Section.USER -> s.ctxUser
        ContextMeter.Section.ASSISTANT -> s.ctxAssistant
        ContextMeter.Section.TOOL_ARGS -> s.ctxToolArgs
        ContextMeter.Section.TOOL_RESULTS -> s.ctxToolResults
    }
}
