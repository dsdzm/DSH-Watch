package com.dshwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 截图风格的「胶囊条目」—— 深灰胶囊、左侧线性图标、文字紧随其后。
 *
 * 尺寸取自参考图（410×502 截图 ≈ 2x 的手表屏）：
 * 胶囊高约 48dp、圆角全圆、左右内边距 20dp、图标 18dp、图标与文字间距 14dp。
 * 宽度**随内容**（wrapContentWidth），不做满宽 —— 长短不一的胶囊正是这种
 * 菜单的呼吸感来源。
 */
@Composable
fun PillItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .wrapContentWidth(Alignment.Start)
            .widthIn(min = 132.dp)
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .tap(enabled = enabled, onClick = withHaptic(onClick))
            .padding(start = 20.dp, end = if (trailing != null) 16.dp else 20.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 底部页面指示点：当前页为白色实心、其余为暗灰小点。
 * 参考图里 3 个点表示分页，用户左右滑动切页。
 */
@Composable
fun PageDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color(0xFF4A4A4A),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .size(if (i == current) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (i == current) activeColor else inactiveColor),
            )
        }
    }
}

/**
 * 一页胶囊菜单：垂直居中、条目左对齐、间距 12dp。
 * 顶部可选一行小标题（参考图没有标题，这里只给分页语义留一点锚点）。
 */
@Composable
fun PillMenuPage(
    modifier: Modifier = Modifier,
    caption: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!caption.isNullOrBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
                maxLines = 1,
            )
        }
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}
