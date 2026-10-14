package com.dshwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.DeepSeekPricing
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.util.Fmt
import kotlinx.coroutines.delay

/**
 * DeepSeek 峰谷时段提示。
 *
 * 放在输入框上方，是因为**这是用户唯一还来得及改变主意的时刻** ——
 * 消息一发出去，钱就花了。工作日的 9-12 点、14-18 点恰好是高峰，
 * 而这两个区间正是大多数人写代码顺手的时候，很容易不知不觉按双倍价格用。
 *
 * 提示要同时回答两件事：「现在贵不贵」和「还有多久变」——
 * 只给前者的话，用户没法决定是现在发还是过一会儿再发。
 */
@Composable
fun DeepSeekPriceBar(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var window by remember { mutableStateOf(DeepSeekPricing.windowAt(System.currentTimeMillis())) }

    // 每分钟刷一次就够：切点是整点，而倒计时以分钟为单位展示。
    // 用更短的周期只会白白唤醒手表。
    LaunchedEffect(Unit) {
        while (true) {
            window = DeepSeekPricing.windowAt(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val peak = window.peak
    // 高峰用暖色（提醒「贵」）、空闲用主色（中性信息）——
    // 这里刻意不用 error 红：它不是错误，只是一种计价状态，用红会显得像出问题了。
    val tint = if (peak) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (peak) "⚡" else "🌙",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (peak) s.peakNow else s.offPeakNow,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = Fmt.longDuration(window.untilChangeMs) + if (peak) s.toOffPeak else s.toPeak,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.75f),
            maxLines = 1,
        )
    }
}
