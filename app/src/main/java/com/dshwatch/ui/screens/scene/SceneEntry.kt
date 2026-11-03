package com.dshwatch.ui.screens.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.components.ChoiceRow
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.GlassDialogRow
import com.dshwatch.ui.components.glassSurface

/**
 * 场景切换入口。
 *
 * ## 为什么放在顶栏而不是底部导航
 *
 * Wear 上底部是「输入条 + 发送」的地盘，圆屏底部两侧还要留给切角，塞不下三个 tab。
 * 顶栏放一个**当前场景胶囊**（图标 + 名字），点开是一个弹出选择 ——
 * 一次操作、不占空间，也符合手表"单页单事"的习惯。
 *
 * ## 切换场景 = 换界面，不是换设置
 *
 * 点「酒馆」不是把当前会话变成酒馆，而是**跳到酒馆模式的根界面**
 * （角色列表）。会话自己的场景在创建时就定死了，见 `Conversation.scene`。
 */
@Composable
fun ScenePill(
    scene: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .glassSurface(shape = CircleShape, onClick = withHaptic(onClick))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = sceneIcon(scene),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = Scenes.label(scene),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 场景选择弹窗：三个选项，各自一行说明 */
@Composable
fun ScenePickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "切换模式",
        maxContentHeight = 280,
        content = {
            Scenes.all.forEach { id ->
                GlassDialogRow(
                    title = Scenes.label(id),
                    subtitle = Scenes.desc(id),
                    selected = id == Scenes.of(current),
                    onClick = { onPick(id) },
                )
            }
        },
        buttons = { GlassDialogButton("取消", onClick = onDismiss) },
    )
}

fun sceneIcon(scene: String): ImageVector = when (Scenes.of(scene)) {
    Scenes.TAVERN -> Icons.Outlined.TheaterComedy
    Scenes.WRITING -> Icons.Outlined.AutoStories
    else -> Icons.Outlined.Code
}

/** 圆屏底栏用的横向三格：只在需要"平铺三个入口"时用（如场景根页面） */
@Composable
fun SceneRow(
    modifier: Modifier = Modifier,
    current: String,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Scenes.all.forEach { id ->
            val active = id == Scenes.of(current)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .tap(onClick = withHaptic { onPick(id) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = sceneIcon(id),
                    contentDescription = null,
                    tint = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = Scenes.label(id),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
