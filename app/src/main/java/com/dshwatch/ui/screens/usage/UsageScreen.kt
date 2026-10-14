package com.dshwatch.ui.screens.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.usage.DayUsage
import com.dshwatch.data.usage.UsageRepository
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
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
