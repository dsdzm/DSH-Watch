package com.dshwatch.ui.screens.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme as W3
import com.dshwatch.core.AppContainer
import com.dshwatch.data.usage.DayUsage
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.domain.ContextMeter
import com.dshwatch.ui.m3e.M3eStaticSurface
import com.dshwatch.ui.theme.isM3eUi
import com.dshwatch.ui.components.ConfirmDialog
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.KeyValueRow
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SegmentedRow
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.util.Fmt
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

@Composable
fun UsageScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val records by container.usageRepo.records.collectAsStateWithLifecycle()
    var period by remember { mutableStateOf(0) }
    var showClear by remember { mutableStateOf(false) }

    /*
     * 上下文构成：只显示「属于当前这个会话」的快照。
     *
     * ChatEngine 里切换会话的赋值点有十来处，逐个清空快照既容易漏、
     * 又会和「正在发送」的时序打架。带上会话 id 比一下，切过去自然是空的 ——
     * 空着只是没数字，挂着别的会话的数字才是误导。
     */
    val snapshot by container.engine.context.collectAsStateWithLifecycle()
    val conversation by container.engine.conversation.collectAsStateWithLifecycle()
    val insight = snapshot?.takeIf { it.conversationId == conversation?.id }?.insight

    LaunchedEffect(Unit) { container.usageRepo.ensureLoaded() }

    val now = remember(records) { System.currentTimeMillis() }
    val (from, to) = when (period) {
        0 -> Fmt.startOfDay(now) to (now + 1)
        1 -> (now - 7 * 86_400_000L) to (now + 1)
        2 -> Fmt.startOfMonth(now) to (now + 1)
        else -> 0L to Long.MAX_VALUE
    }
    val aggregate = remember(records, period) {
        UsageRepository.aggregate(records, from, to)
    }

    WatchScaffold(title = s.usageTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            if (insight != null) {
                SectionHeader(s.ctxTitle)
                ContextCard(insight)
            }

            SectionHeader("统计区间")
            SegmentedRow(
                options = listOf(s.periodToday, s.periodWeek, s.periodMonth, s.periodAll),
                selectedIndex = period,
                onSelect = { period = it },
            )

            if (aggregate.isEmpty) {
                EmptyState(title = s.noUsage, hint = "发几条消息后这里会出现统计")
                return@WatchScaffold
            }

            SectionHeader(s.totalTokens)
            SettingCard {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(
                        text = Fmt.tokens(aggregate.total),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = Fmt.money(aggregate.cost),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                KeyValueRow(s.promptTokens, Fmt.tokens(aggregate.prompt))
                KeyValueRow(s.completionTokens, Fmt.tokens(aggregate.completion))
                if (aggregate.cached > 0) {
                    KeyValueRow(s.cachedTokens, Fmt.tokens(aggregate.cached))
                }
                KeyValueRow(s.requestsLabel, aggregate.requests.toString())
                KeyValueRow(s.costLabel, Fmt.money(aggregate.cost))
                Spacer(Modifier.height(2.dp))
            }

            if (aggregate.byDay.isNotEmpty()) {
                SectionHeader(s.byDay)
                SettingCard {
                    Spacer(Modifier.height(6.dp))
                    DayBars(days = aggregate.byDay.takeLast(14))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${aggregate.byDay.first().day} → ${aggregate.byDay.last().day}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            SectionHeader(s.byModel)
            SettingCard {
                Spacer(Modifier.height(4.dp))
                aggregate.byModel.take(8).forEach { model ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = model.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                text = model.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "↑${Fmt.tokens(model.prompt)} ↓${Fmt.tokens(model.completion)}" +
                                    " · ${Fmt.tokens(model.total)} " + s.tokensLabel +
                                    if (model.cost > 0) " · ${Fmt.money(model.cost)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            SectionHeader("维护")
            SettingCard {
                GhostButton(
                    text = s.clearUsage,
                    danger = true,
                    onClick = { showClear = true },
                )
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "标有「估算」的记录来自本地 token 估算器（服务端未返回 usage）。花费按供应商里配置的单价折算。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    if (showClear) {
        ConfirmDialog(
            title = s.clearUsage,
            text = s.clearUsageConfirm,
            onConfirm = {
                showClear = false
                scope.launch { container.usageRepo.clear() }
            },
            onDismiss = { showClear = false },
        )
    }
}

@Composable
private fun DayBars(days: List<DayUsage>) {
    if (days.isEmpty()) return
    val max = days.maxOf { it.total }.coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        val n = days.size
        val slot = size.width / n
        val barWidth = (slot * 0.62f).coerceAtLeast(2f)
        days.forEachIndexed { index, day ->
            val ratio = day.total.toFloat() / max
            val h = (size.height * ratio).coerceAtLeast(2f)
            val left = index * slot + (slot - barWidth) / 2f
            drawRoundRect(
                color = if (day.total > 0) barColor else emptyColor,
                topLeft = Offset(left, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(2f, 2f),
            )
            // 基线
            drawRect(
                color = Color.Transparent,
                topLeft = Offset(left, size.height - 1f),
                size = Size(barWidth, 1f),
            )
        }
    }
}

/**
 * 上下文构成卡片 —— 对应 DSH 生态里 `dsh-context` / `billion-context` 的「看清」那一半。
 *
 * ## 为什么是三件东西，不是一张图
 *
 * 桌面插件的做法是柱状图 + hover 明细。手表上没有 hover，一屏也只放得下十来行，
 * 照抄会得到一张读不出结论的图。这里按「先结论、再构成、最后能做什么」排：
 *
 * 1. **一行大数字**（已用 / 预算 + 百分比），颜色随档位变；够不够用一眼就够；
 * 2. **一根分段条**，五段同宽比例，工具输出用 error 色 —— 它是最该被折叠的那段，
 *    在颜色上就该比别的刺眼；
 * 3. **可折叠省下的量**。这是唯一能直接指导动作的数字：看到工具输出占一半、
 *    可省 2.9k，就知道该让模型别再整段读文件，而不是急着重开会话。
 *
 * 分母用 [ContextMeter.Insight.usable]（窗口减去预留输出）而不是窗口本身：
 * 模型真实能用的就是这一段，拿窗口当分母会让人在 90% 时还以为"才用了一半"。
 */
/**
 * 上下文构成卡片 —— 对应 DSH 生态里 `dsh-context` / `billion-context` 的「看清」那一半。
 *
 * ## 两套界面各走一条路
 *
 * 经典界面：`SettingCard` 是一张玻璃卡片，内容直接排在里面。
 * M3E：`SettingCard` 只是 `Column(spacedBy(6dp))`，卡片感来自每一行自己的底色 ——
 * 所以这里显式包一层 [M3eStaticSurface]，否则五行数字会直接浮在页面上，
 * 看起来像"没做完的卡片"。
 *
 * 字阶也必须分开取：Wear M3 的 `labelMedium` 是 15sp，手机的是 12sp，
 * 用同一套字号会让 M3E 下的明细行比同页按钮小一档。
 *
 * ## 为什么是这三件东西
 *
 * 桌面插件的做法是柱状图 + hover 明细。手表上没有 hover、一屏十来行，
 * 照抄只会得到一张读不懂的图。这里按「先结论、再构成、最后能做什么」排：
 *
 * 1. **一行大数字**（已用 / 预算 + 百分比），颜色随档位变；
 * 2. **一根分段条**，工具输出用 error 色 —— 它是最该被折叠的那段；
 * 3. **可折叠省下的量**，唯一能直接指导动作的数字。
 *
 * 分母用 [ContextMeter.Insight.usable]（窗口减预留输出）而不是窗口本身：
 * 模型真实能用的就是这一段，拿窗口当分母会让人在 90% 时还以为"才用了一半"。
 */
@Composable
private fun ContextCard(insight: ContextMeter.Insight) {
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
        ContextBar(insight)
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
                        .background(sectionColor(part.section)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = sectionLabel(part.section),
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
        SettingCard(content = body)
    }
}

/**
 * 分段占比条。
 *
 * 段宽按 `tokens / usable` 算，总和不到 100% 时右侧留出灰底 ——
 * 那条灰就是"还剩多少"，比在数字上做减法直观。超预算时各段按比例压到整条内。
 */
@Composable
private fun ContextBar(insight: ContextMeter.Insight) {
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
private fun sectionColor(section: ContextMeter.Section): Color {
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
private fun sectionLabel(section: ContextMeter.Section): String {
    val s = LocalStrings.current
    return when (section) {
        ContextMeter.Section.SYSTEM -> s.ctxSystem
        ContextMeter.Section.USER -> s.ctxUser
        ContextMeter.Section.ASSISTANT -> s.ctxAssistant
        ContextMeter.Section.TOOL_ARGS -> s.ctxToolArgs
        ContextMeter.Section.TOOL_RESULTS -> s.ctxToolResults
    }
}
