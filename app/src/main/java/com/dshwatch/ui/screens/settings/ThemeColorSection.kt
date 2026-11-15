package com.dshwatch.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme as W3
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.m3e.M3eStaticSurface
import com.dshwatch.ui.theme.SeedCache
import com.dshwatch.ui.theme.SeedSource
import com.dshwatch.ui.theme.ThemeSeed
import com.dshwatch.ui.theme.ThemeSeeds
import com.dshwatch.ui.theme.isM3eUi

/**
 * 主题色：一个开关 + 一盘种子色。
 *
 * ## 为什么是这个形状
 *
 * 原来的两行（「强调色」+「动态取色」）是两种心智模型叠在一起：
 * 强调色是 6 套手调色板里挑一套，动态取色是"系统说了算"，用户没法从界面上
 * 看出它们的关系，也不知道当前到底谁在生效 —— 只有"我选了蓝的，怎么没变"。
 *
 * 现在改成同一张卡片上的两层：
 *
 *  · **跟随壁纸取色** —— 开：种子从壁纸/背景图现场算；关：用下面的种子；
 *  · **种子色** —— 任意一个颜色都能生成整套色板（莫奈取色），
 *    9 个点只是"顺手能选的几个"，不是全部。
 *
 * 关掉开关时网格才是可点的，开着时整盘压暗、写明"已由上方接管"。
 * 这不是禁用用户，而是把**因果关系摆在眼前**：想自己挑，就先关掉自动。
 *
 * ## 为什么网格是 4/4/1
 *
 * 圆屏卡片的可用宽度约 170dp，4 个 34dp 的点加 3 道 6dp 缝正好 154dp。
 * 9 个点排 3 行，最后一行单独一个 —— 手表上"多出来的那一个"比"3×3 空格"
 * 更好读：前者一眼能数清，后者容易被看成 3×3 缺了两个。
 */
@Composable
fun ThemeColorSection(
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    dark: Boolean,
) {
    val s = LocalStrings.current
    val m3e = isM3eUi

    val auto = settings.dynamicColor
    val picked = ThemeSeeds.parseHex(settings.seedColor)
    val pickedSeed = picked?.let { ThemeSeeds.byHex(settings.seedColor) }

    /*
     * 状态文案要诚实。
     *
     * 手表上 WallpaperManager 经常是空的（见 SeedSource），如果开关开着
     * 却取不到图，界面必须说出来 —— 否则用户看到的是"开了但颜色没变"，
     * 只能理解成功能坏了。三种来源各一句话，取自缓存里上一次真正用到的那个。
     */
    val subtitle = when {
        !auto && pickedSeed != null -> "已关闭 · 正在用「${pickedSeed.labelZh}」生成的配色"
        !auto -> "已关闭 · 正在用主题预设的颜色"
        SeedCache.lastSource == SeedSource.Source.BACKGROUND -> "已开启 · 配色跟随自定义背景"
        SeedCache.lastSource == SeedSource.Source.WALLPAPER -> "已开启 · 配色跟随壁纸主色"
        else -> s.seedNoPaper
    }

    if (m3e) {
        SwitchRow(
            title = s.dynamicColor,
            subtitle = subtitle,
            checked = auto,
            onCheckedChange = { v ->
                // 开：不必清掉种子 —— 关掉时用户还想回到自己挑的那个颜色
                update { it.copy(dynamicColor = v) }
            },
        )
    } else {
        SettingCard {
            SwitchRow(
                title = s.dynamicColor,
                subtitle = subtitle,
                checked = auto,
                onCheckedChange = { v -> update { it.copy(dynamicColor = v) } },
            )
        }
    }

    Spacer(Modifier.height(6.dp))

    SeedCard(
        auto = auto,
        picked = picked,
        dark = dark,
        onPick = { seed ->
            // 点种子 = 明确要自己选，顺手把自动取色关掉：
            // 不关的话点了没反应（自动的种子优先级更高），那是最糟的反馈
            update { it.copy(seedColor = seed.hex, dynamicColor = false) }
        },
    )
}

@Composable
private fun SeedCard(
    auto: Boolean,
    picked: Int?,
    dark: Boolean,
    onPick: (ThemeSeed) -> Unit,
) {
    val s = LocalStrings.current
    val body: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = s.seedColors,
                style = titleStyle(),
                color = onSurfaceColor(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (auto) s.seedAutoHint else s.seedPickHint,
                style = hintStyle(),
                color = onSurfaceVariantColor(),
            )
            Spacer(Modifier.height(10.dp))
            SeedGrid(
                seeds = ThemeSeeds.all,
                picked = picked,
                enabled = !auto,
                dark = dark,
                onPick = onPick,
            )
            Spacer(Modifier.height(2.dp))
        }
    }

    if (isM3eUi) {
        M3eStaticSurface { Column(content = body) }
    } else {
        SettingCard(content = body)
    }
}

@Composable
private fun SeedGrid(
    seeds: List<ThemeSeed>,
    picked: Int?,
    enabled: Boolean,
    dark: Boolean,
    onPick: (ThemeSeed) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        seeds.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { seed ->
                    SeedDot(
                        seed = seed,
                        selected = picked != null && picked == seed.argb,
                        enabled = enabled,
                        dark = dark,
                        onClick = { onPick(seed) },
                    )
                }
            }
        }
    }
}

/**
 * 一颗种子。
 *
 * 选中态用**外圈**表达（圆环 + 中间对勾），不改圆点本身的颜色 ——
 * 圆点显示的就是"选了会变成什么样"，涂改它等于把预览也一起改了。
 */
@Composable
private fun SeedDot(
    seed: ThemeSeed,
    selected: Boolean,
    enabled: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    val ringColor = if (dark) Color.White else Color.Black

    Box(
        modifier = Modifier
            .size(38.dp)
            .tap(enabled = enabled, onClick = withHaptic(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(seed.argb).copy(alpha = alpha))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, ringColor.copy(alpha = 0.85f), CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    // 点上的对勾要跟种子色有对比：亮色种子用黑勾、暗色用白勾。
                    // 不按种子亮度选的话，浅黄底上的白勾基本看不见
                    tint = if (isLight(seed.argb)) Color.Black else Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 相对亮度。只用来决定对勾的颜色，不需要精确的 Lab */
private fun isLight(argb: Int): Boolean {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.299f * r + 0.587f * g + 0.114f * b > 0.62f
}

// 两套界面读的是同一份色板（M3E 主题由同一 ColorScheme 映射），
// 这里只差字号：Wear 的 label 比手机大一号
@Composable
private fun titleStyle() = if (isM3eUi) {
    W3.typography.bodyMedium ?: MaterialTheme.typography.bodyMedium
} else {
    MaterialTheme.typography.bodyMedium
}

@Composable
private fun hintStyle() = if (isM3eUi) {
    W3.typography.labelSmall ?: MaterialTheme.typography.labelSmall
} else {
    MaterialTheme.typography.labelSmall
}

@Composable
private fun onSurfaceColor(): Color =
    if (isM3eUi) W3.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface

@Composable
private fun onSurfaceVariantColor(): Color =
    if (isM3eUi) W3.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant
