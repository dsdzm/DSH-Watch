package com.dshwatch.ui.m3e.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme as W3
import androidx.wear.compose.material3.Text
import com.dshwatch.core.AppContainer
import com.dshwatch.data.usage.DayUsage
import com.dshwatch.data.usage.UsageAggregate
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.ui.components.ContextUsageCard
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.m3e.M3eChip
import com.dshwatch.ui.m3e.M3eConfirmDialog
import com.dshwatch.ui.m3e.M3eEmptyState
import com.dshwatch.ui.m3e.M3eList
import com.dshwatch.ui.m3e.M3eGhostButton
import com.dshwatch.ui.m3e.M3eScaffold
import com.dshwatch.ui.m3e.M3eSectionHeader
import com.dshwatch.ui.m3e.M3eStaticSurface
import com.dshwatch.ui.m3e.rememberM3eListState
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.util.Fmt
import kotlinx.coroutines.launch

/**
 * M3E 的用量页 —— 独立实现。
 *
 * ## 为什么整页重写而不是换控件
 *
 * 经典页的骨架是「一张大卡 + 卡片内分隔线」，一屏只能放一个指标。
 * 用量页有三组彼此独立的数字（区间汇总 / 每天 / 按模型），
 * 塞进一张卡里必须滚动，而手表上一屏就 200dp —— 用户滚到第二组时
 * 已经不记得第一组是多少了。
 *
 * M3E 这版把它拆成**一叠独立卡片**，每张只说一件事，靠列表的变换效果
 * （越靠边越小越淡）提示"上面还有内容"。这是 Wear 官方推荐的形态：
 * 一张卡 = 一个信息块，不是"一张卡 = 一整页"。
 *
 * ## 区间选择用官方 Chip 横排，不用分段控件
 *
 * 手机上的 `SegmentedButton` 在手表上缩到 4 段后，每段只有 45dp，
 * 中文字「本月」刚好放不下。四颗 Chip 横排是 Wear 的标准做法 ——
 * 它们本来就有选中态（`selected`），语义与分段控件完全一致。
 */
@Composable
fun M3eUsageScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val listState = rememberM3eListState()

    val records by container.usageRepo.records.collectAsStateWithLifecycle()
    var period by remember { mutableStateOf(0) }
    var showClear by remember { mutableStateOf(false) }

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
    val aggregate = remember(records, period) { UsageRepository.aggregate(records, from, to) }

    M3eScaffold(title = s.usageTitle, onBack = { nav.pop() }) {
        M3eList(state = listState, modifier = Modifier.weight(1f)) {
            /*
             * 区间放在第一项 —— 它是这一页唯一的显式状态。
             *
             * 顺序上它必须排在所有数字前面：先选"看哪段时间"，再看数字；
             * 反过来用户会先读到一整屏数字，然后才发现下面有个筛选器，
             * 于是不得不往回滚去确认"我刚才看的是哪一段"。
             */
            item(key = "__period__") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(s.periodToday, s.periodWeek, s.periodMonth, s.periodAll)
                        .forEachIndexed { index, label ->
                            M3eChip(
                                text = label,
                                selected = period == index,
                                onClick = { period = index },
                            )
                        }
                }
            }

            if (insight != null) {
                item(key = "__hdr_ctx__") { M3eSectionHeader(s.ctxTitle) }
                item(key = "__ctx__") { ContextUsageCard(insight) }
            }

            if (aggregate.isEmpty) {
                item(key = "__empty__") {
                    M3eEmptyState(title = s.noUsage, hint = "发几条消息后这里会出现统计")
                }
                return@M3eList
            }

            item(key = "__hdr_total__") { M3eSectionHeader(s.totalTokens) }
            item(key = "__total__") { TotalCard(aggregate) }

            if (aggregate.byDay.isNotEmpty()) {
                item(key = "__hdr_day__") { M3eSectionHeader(s.byDay) }
                item(key = "__day__") {
                    M3eStaticSurface {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            DayBars(days = aggregate.byDay.takeLast(14))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${aggregate.byDay.first().day} → ${aggregate.byDay.last().day}",
                                style = W3.typography.labelSmall,
                                color = W3.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "__hdr_model__") { M3eSectionHeader(s.byModel) }
            items(aggregate.byModel.take(8).size, key = { "m" + it }) { index ->
                val model = aggregate.byModel[index]
                M3eStaticSurface {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            text = model.model,
                            style = W3.typography.bodySmall,
                            color = W3.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = model.providerName,
                                style = W3.typography.labelSmall,
                                color = W3.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "↑${Fmt.tokens(model.prompt)} ↓${Fmt.tokens(model.completion)}",
                                style = W3.typography.labelSmall,
                                color = W3.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            item(key = "__hdr_maint__") { M3eSectionHeader("维护") }
            item(key = "__clear__") {
                M3eGhostButton(text = s.clearUsage, icon = Icons.Outlined.Delete) { showClear = true }
            }

            item(key = "__note__") {
                Text(
                    text = "标有「估算」的记录来自本地 token 估算器（服务端未返回 usage）。花费按供应商里配置的单价折算。",
                    style = W3.typography.labelSmall,
                    color = W3.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }

            item(key = "__tail_gap__") { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showClear) {
        M3eConfirmDialog(
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

/**
 * 区间汇总卡。
 *
 * 大数字用 `titleMedium`（Wear 的 `displaySmall` 在 1.4 英寸上会占满一整屏），
 * 右侧是花费。两者并排而不是上下堆 —— 用户看这一屏就是为了对这两个数。
 */
@Composable
private fun TotalCard(aggregate: UsageAggregate) {
    val s = LocalStrings.current
    Column(modifier = Modifier.fillMaxWidth()) {
        M3eStaticSurface {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = Fmt.tokens(aggregate.total),
                        style = W3.typography.titleMedium,
                        color = W3.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = Fmt.money(aggregate.cost),
                        style = W3.typography.labelMedium,
                        color = W3.colorScheme.secondary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                KvRow(s.promptTokens, Fmt.tokens(aggregate.prompt))
                KvRow(s.completionTokens, Fmt.tokens(aggregate.completion))
                if (aggregate.cached > 0) KvRow(s.cachedTokens, Fmt.tokens(aggregate.cached))
                KvRow(s.requestsLabel, aggregate.requests.toString())
            }
        }
    }
}

@Composable
private fun KvRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = W3.typography.labelSmall,
            color = W3.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = value, style = W3.typography.labelMedium, color = W3.colorScheme.onSurface)
    }
}

/**
 * 每日柱状图。
 *
 * 这里是**唯一保留自绘的地方**：Wear 没有柱状图组件。
 * 用量页要读的是「这周哪几天忙」，一张 14 根的柱子图比 14 行数字快得多，
 * 而官方组件里最近的东西是 `SegmentedCircularProgressIndicator` ——
 * 那是「完成度」的语言，不是「逐日对比」的语言，用上去会更难读。
 */
@Composable
private fun DayBars(days: List<DayUsage>) {
    if (days.isEmpty()) return
    val max = days.maxOf { it.total }.coerceAtLeast(1)
    val barColor = W3.colorScheme.primary
    val emptyColor = W3.colorScheme.surfaceContainerHigh

    Canvas(modifier = Modifier.fillMaxWidth().height(52.dp)) {
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
        }
    }
}
