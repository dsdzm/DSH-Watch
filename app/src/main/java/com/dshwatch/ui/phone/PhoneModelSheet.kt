package com.dshwatch.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ReasoningEffort

/**
 * 模型选择弹窗。
 *
 * 照参考图的三层：**供应商** → **模型** → **思考强度**，最后一颗「管理模型」。
 *
 * ## 为什么不复用 `PhoneModelPicker`
 *
 * 旧的只是一个 `AlertDialog` + 一串模型名。那是「选一个字符串」，
 * 而用户真正在做的是三件相互牵连的事：
 *
 *  - 换供应商（同一家也可能有两条通道：官方 Key / 中转站）；
 *  - 换模型（同一个供应商下有四五个）；
 *  - 调思考强度（同一个模型，快和深是两档用法）。
 *
 * 三件事挤在一个下拉列表里，等于让用户先记住供应商再找模型。
 * 现在按层摆开，每层都显示「现在选的是哪个」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneModelSheet(
    providers: List<Provider>,
    activeProviderId: String,
    currentModel: String,
    onPickProvider: (Provider) -> Unit,
    onPickModel: (String) -> Unit,
    onPickEffort: (ReasoningEffort) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val active = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
    val models = active?.models.orEmpty()
    val effort = active?.reasoningEffort ?: ReasoningEffort.DEFAULT

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = scheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 14.dp),
            )

            // ---------------- 供应商 ----------------
            if (providers.size > 1) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { p ->
                        FilterChip(
                            selected = p.id == active?.id,
                            onClick = { onPickProvider(p) },
                            label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            shape = RoundedCornerShape(12.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = scheme.surfaceContainerLowest,
                                selectedLabelColor = scheme.onSurface,
                                containerColor = Color.Transparent,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = p.id == active?.id,
                                borderColor = Color.Transparent,
                                selectedBorderColor = scheme.outlineVariant,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ---------------- 模型 ----------------
            if (models.isEmpty()) {
                Text(
                    text = "这个供应商还没填模型名。去「管理模型」里加一个。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            } else {
                models.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { model ->
                            ModelCard(
                                model = model,
                                selected = model == currentModel,
                                onClick = { onPickModel(model) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 单数个时补一个空位，避免最后一张被拉成整行宽
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---------------- 思考强度 ----------------
            Text(
                text = "思考深度",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            EffortBar(
                current = effort,
                onPick = onPickEffort,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onManage) {
                    Text("管理模型", color = scheme.primary)
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 一张模型卡：选中时描边 + 右下角勾，没选中时只有一层浅底 */
@Composable
private fun ModelCard(
    model: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surfaceContainerLowest,
        modifier = modifier
            .heightIn(min = 74.dp)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, scheme.primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (selected) "使用中" else "点一下切过去",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        if (selected) scheme.primary else Color.Transparent,
                        CircleShape,
                    )
                    .then(
                        if (selected) Modifier else Modifier.border(1.5.dp, scheme.outlineVariant, CircleShape)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * 思考强度分段条。
 *
 * 不直接用 `SegmentedRow`：那是手表组件，读 `LocalGlass` 并按 44dp 高度排，
 * 在手机上会显得又小又薄。这里照参考图的「凹槽 + 白色选中块」重画一遍，
 * 高度 44dp、圆角 22dp。
 */
@Composable
private fun EffortBar(
    current: ReasoningEffort,
    onPick: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val options = listOf(
        ReasoningEffort.DEFAULT to "跟随",
        ReasoningEffort.LOW to "低",
        ReasoningEffort.MEDIUM to "中",
        ReasoningEffort.HIGH to "高",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = value == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        if (selected) scheme.surfaceContainerLowest else Color.Transparent,
                        RoundedCornerShape(20.dp),
                    )
                    .clickable { onPick(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }
        }
    }
}
