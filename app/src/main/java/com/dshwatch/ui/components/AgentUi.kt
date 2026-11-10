package com.dshwatch.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.ApprovalDecision
import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.PendingApproval
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.ToolCallRecord
import com.dshwatch.domain.model.ToolStatus
import com.dshwatch.ui.i18n.AppStrings
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.theme.LocalPerf
import com.dshwatch.ui.theme.MonoSmall
import com.dshwatch.util.Fmt
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.theme.LocalGlass

@Composable
fun toolStatusLabel(status: ToolStatus): String {
    val s = LocalStrings.current
    return when (status) {
        ToolStatus.PENDING -> s.toolStatusPending
        ToolStatus.AWAITING_APPROVAL -> s.toolStatusAwaiting
        ToolStatus.RUNNING -> s.toolStatusRunning
        ToolStatus.SUCCESS -> s.toolStatusSuccess
        ToolStatus.FAILED -> s.toolStatusFailed
        ToolStatus.DENIED -> s.toolStatusDenied
        ToolStatus.SKIPPED -> s.toolStatusSkipped
    }
}

@Composable
private fun toolStatusColor(status: ToolStatus): Color = when (status) {
    ToolStatus.SUCCESS -> MaterialTheme.colorScheme.secondary
    ToolStatus.FAILED -> MaterialTheme.colorScheme.error
    ToolStatus.DENIED, ToolStatus.SKIPPED -> MaterialTheme.colorScheme.outline
    ToolStatus.RUNNING, ToolStatus.AWAITING_APPROVAL -> MaterialTheme.colorScheme.primary
    ToolStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 工具调用卡片。
 *
 * 三条设计约定：
 *  1. **说人话** —— 标题走 `AppStrings.toolTitle`，不把 `write_file` 这种
 *     工程化名字直接摆给用户看；
 *  2. **默认只占两行** —— 手表屏幕窄，一眼能看到「做了什么、成没成」就够了，
 *     参数原文与原始输出折叠起来；
 *  3. **产物要能直接拿走** —— build_project 成功后渲染成文件卡片，
 *     点开即预览、右侧导出，不用再去「工作空间」页翻一遍。
 */
@Composable
fun ToolCallCard(
    call: ToolCallRecord,
    modifier: Modifier = Modifier,
    sizeBytes: Long = 0L,
    onOpenArtifact: ((String) -> Unit)? = null,
    onExportArtifact: ((String) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val perf = LocalPerf.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    val tint = toolStatusColor(call.status)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (LocalGlass.current) {
                    Modifier.glassSurface(shape = RoundedCornerShape(12.dp), cornerRadius = 12.dp)
                } else {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f))
                },
            )
            .padding(horizontal = 7.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tap(onClick = withHaptic { expanded = !expanded }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标 + 状态色底：扫一眼就知道是什么工具、成没成
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ToolVisuals.icon(call.name),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = s.toolTitle(call.name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (call.elapsedMs > 0) {
                Text(
                    text = Fmt.duration(call.elapsedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = toolStatusLabel(call.status),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(start = 3.dp)
                    .size(16.dp),
            )
        }

        // 云端构建要几分钟，卡片上先把预期写清楚 —— 否则用户只会看到"一直在跑"
        if (call.name == "cloud_build") {
            Text(
                text = s.cloudBuildEta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp, top = 1.dp),
            )
        }

        // 参数摘要常显：用户不用展开就知道它动了什么
        if (call.summary.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = call.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp),
            )
        }

        // 产物比「详情」重要得多，所以不藏在折叠里
        if (call.status == ToolStatus.SUCCESS && call.artifact.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            ArtifactCard(
                path = call.artifact,
                sizeBytes = sizeBytes,
                onOpen = onOpenArtifact,
                onExport = onExportArtifact,
            )
        }

        if (expanded) {
            if (call.arguments.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = s.toolArgs,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = prettyJson(call.arguments).take(600),
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (call.result.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = s.toolResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = s.copy,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .tap(onClick = withHaptic {
                                clipboard.setText(AnnotatedString(call.result))
                            })
                            .padding(horizontal = 4.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (perf.tier == DeviceTier.LOW) 90.dp else 160.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = call.result,
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 产物卡片 —— 「agent 真的给我做出来了一个东西」这件事要看得见、拿得走。
 *
 * 结构参考最常见的文件卡片：左侧类型角标，中间文件名 + 「产物 · 大小」，
 * 右侧导出箭头。整卡点击 = 打开预览。
 *
 * 大小由调用方从工作空间读真实字节数传进来（工具返回文本里那个虚数不值得信），
 * 读不到就为空，这一项自动省略。
 */
@Composable
fun ArtifactCard(
    path: String,
    sizeBytes: Long = 0L,
    onOpen: ((String) -> Unit)? = null,
    onExport: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val typeText = remember(path) {
        extensionOf(path).ifBlank { "file" }.uppercase().take(4)
    }
    val sizeText = remember(sizeBytes) { Fmt.bytes(sizeBytes) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .tap(onClick = withHaptic { onOpen?.invoke(path) })
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = typeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = path.substringAfterLast('/'),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (sizeText.isBlank()) s.artifact else s.artifact + " · " + sizeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onExport != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .tap(onClick = withHaptic { onExport.invoke(path) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = s.exportArtifact,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 把模型给的 JSON 参数压成一行展示，避免原始字符串里的转义把界面搞乱 */
private fun prettyJson(raw: String): String = raw
    .replace("\\n", " ")
    .replace('\n', ' ')      // 参数里也可能是真的换行（模型没转义），一并压平
    .replace('\r', ' ')
    .replace("\\\"", "\"")
    .trim()

/**
 * 工具审批弹窗。
 * 三个动作做成一列整宽按钮——手表上并排三个按钮会小到点不准。
 */
@Composable
fun ToolApprovalDialog(
    pending: PendingApproval,
    onDecision: (ApprovalDecision) -> Unit,
) {
    val s = LocalStrings.current
    // 危险命令分两屏：第一屏只负责「让你看清」，第二屏才是真正的同意。
    // key 用 callId：换一条命令必须从头确认，不能沿用上一条的第二屏状态。
    var stage by remember(pending.callId) { mutableStateOf(0) }
    val danger = pending.danger
    val hasCommand = pending.command.isNotBlank()

    GlassDialog(
        onDismissRequest = { onDecision(ApprovalDecision.DENY) },
        title = when {
            danger && stage == 0 -> "⚠ 高风险操作"
            danger -> "再确认一次"
            else -> pending.toolName
        },
        maxContentHeight = 230,
        content = {
            if (danger && stage == 1) {
                Text(
                    text = "上一次点击没有执行任何操作。确认之后命令会立即运行，无法撤销。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }

            // 模型自己写的用途说明放最上面：用户判断「该不该允许」靠的就是这一行
            // 和下面那条命令能不能对上。
            if (pending.purpose.isNotBlank()) {
                Text(
                    text = "AI 的用途说明",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = pending.purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
            }

            if (hasCommand) {
                Text(
                    text = "要执行的命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                CommandBlock(pending.command)
                Spacer(Modifier.height(6.dp))
            } else {
                Text(
                    text = pending.summary.ifBlank { pending.arguments.take(200) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }

            when {
                danger -> Text(
                    text = "这类命令会删除或改写系统文件，可能导致应用崩溃、数据丢失，" +
                        "甚至无法开机。只有你完全明白它在做什么时才继续。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )

                hasCommand -> Text(
                    text = "授权范围：${pending.categoryLabel.ifBlank { "其他命令" }}。" +
                        "选「始终允许」后这一类不再询问，危险命令除外。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                else -> Text(
                    text = "副作用：${pending.safety.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        // 按钮做成一列整宽 —— 手表上并排三个会小到点不准
        buttons = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                when {
                    // 第一屏：只有「继续看第二屏」和「取消」，没有任何执行入口
                    danger && stage == 0 -> {
                        ApprovalButton("我了解风险，继续", danger = true) { stage = 1 }
                        ApprovalButton(s.deny) { onDecision(ApprovalDecision.DENY) }
                    }

                    // 第二屏：真正执行。没有「始终允许」—— 危险命令每次都要问
                    danger -> {
                        ApprovalButton("确认执行", danger = true) {
                            onDecision(ApprovalDecision.ALLOW_ONCE)
                        }
                        ApprovalButton(s.deny) { onDecision(ApprovalDecision.DENY) }
                    }

                    // run_script：不给永久放行，只给「本会话」
                    pending.sessionScoped -> {
                        ApprovalButton(s.approveSession, primary = true) {
                            onDecision(ApprovalDecision.ALLOW_SESSION)
                        }
                        ApprovalButton(s.approveOnce) { onDecision(ApprovalDecision.ALLOW_ONCE) }
                        ApprovalButton(s.deny, danger = true) { onDecision(ApprovalDecision.DENY) }
                    }

                    // adb_shell：按类别永久授权，按钮上写清楚是哪一类
                    hasCommand -> {
                        ApprovalButton(
                            "始终允许「${pending.categoryLabel.ifBlank { "这类" }}」",
                            primary = true,
                        ) { onDecision(ApprovalDecision.ALLOW_ALWAYS) }
                        ApprovalButton(s.approveOnce) { onDecision(ApprovalDecision.ALLOW_ONCE) }
                        ApprovalButton(s.deny, danger = true) { onDecision(ApprovalDecision.DENY) }
                    }

                    else -> {
                        ApprovalButton(s.approveAlways, primary = true) {
                            onDecision(ApprovalDecision.ALLOW_ALWAYS)
                        }
                        ApprovalButton(s.approveOnce) { onDecision(ApprovalDecision.ALLOW_ONCE) }
                        ApprovalButton(s.deny, danger = true) { onDecision(ApprovalDecision.DENY) }
                    }
                }
            }
        },
    )
}

/**
 * 命令原文。
 *
 * 等宽 + 独立底色的原因：用户要判断「purpose 里说的」和「命令实际干的」是不是
 * 同一件事，混在正文里读不出来。限高 72dp 并允许滚动，长命令不会把按钮顶出圆屏。
 */
@Composable
private fun CommandBlock(command: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = command,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ApprovalButton(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        danger -> MaterialTheme.colorScheme.error
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .glassSurface(
                shape = RoundedCornerShape(12.dp),
                cornerRadius = 12.dp,
                tint = if (primary || danger) color else androidx.compose.ui.graphics.Color.Unspecified,
                onClick = withHaptic(onClick),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * Agent 模式的快捷设置弹窗（聊天页和设置页都能改）。
 *
 * 这里**不再**提供「步数上限」：现在不按轮数限制，改成检测停滞
 * （见 `ProgressTracker`）。留一个调不动的旋钮只会让人以为还有上限。
 */
@Composable
fun AgentModeDialog(
    current: AgentMode,
    onPickMode: (AgentMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val s: AppStrings = LocalStrings.current
    GlassDialog(
        onDismissRequest = onDismiss,
        title = s.groupAgent,
        maxContentHeight = 250,
        content = {
            AgentMode.entries.forEach { mode ->
                GlassDialogRow(
                    title = mode.label,
                    subtitle = mode.desc,
                    selected = mode == current,
                    onClick = { onPickMode(mode) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.entryAgentStepsDesc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        buttons = { GlassDialogButton(s.done, primary = true, onClick = onDismiss) },
    )
}
