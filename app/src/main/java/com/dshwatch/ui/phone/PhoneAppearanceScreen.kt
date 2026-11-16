package com.dshwatch.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.ui.i18n.AppLanguage
import com.dshwatch.ui.theme.SeedCache
import com.dshwatch.ui.theme.SeedSource
import com.dshwatch.ui.theme.ThemeSeed
import com.dshwatch.ui.theme.ThemeSeeds
import kotlinx.coroutines.launch

/**
 * 手机版的外观页。
 *
 * ## 为什么单独一页，而不是复用手表那张
 *
 * 手表的外观页（`SettingsGroupScreen` 里的 `AppearanceGroup`）里有七组控件，
 * 手机上只有两组是**有意义的**：
 *
 * | 手表项 | 手机上 |
 * |---|---|
 * | 明暗 | 有效 |
 * | 动态取色 / 种子色 | 有效 |
 * | 界面体系（经典 / M3E） | 无效：手机只有一套骨架 |
 * | 外观风格（纯色 / 玻璃） | 无效：手机没有 glassSurface |
 * | 屏幕形状（圆 / 方） | 无效：手机永远是矩形 |
 * | 界面大小（0.6×~1.4×） | 无效：手机固定 1.0 |
 * | 屏幕圆角安全边距 / 对话布局 / 看板娘 | 无效：都是圆屏推导出来的 |
 *
 * 这些开关在手机上不是「效果差一点」，是**按下去什么都不动** —— 用户会得出
 * 两个结论之一：这个 App 坏了，或者设置是装饰。所以手机版只留真能生效的那几项，
 * 每一项点完都能立刻在屏幕上看见变化。
 *
 * ## 配色规则写在页面上
 *
 * 种子色的说明里点明「同一个色相生成整套明暗层级」，因为手机上最容易产生的
 * 疑问是「为什么我选的不是这个颜色」—— 莫奈色板取的是色相，明度是算出来的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneAppearanceScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: PhoneNavigator,
) {
    val scope = rememberCoroutineScope()

    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { container.settingsRepo.update(block) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("外观", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = { nav.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------------- 明暗 ----------------
            AppearanceCard {
                Text(
                    text = "主题",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ThemeMode.SYSTEM to "跟随系统",
                        ThemeMode.LIGHT to "浅色",
                        ThemeMode.DARK to "深色",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { update { it.copy(themeMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // ---------------- 取色 ----------------
            AppearanceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "跟随壁纸取色",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = seedSourceText(settings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { v -> update { it.copy(dynamicColor = v) } },
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "种子色",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (settings.dynamicColor) {
                        "取色开着时以壁纸为准。点一个颜色会关掉取色，改用这套。"
                    } else {
                        "点一个颜色，按它的色相生成整套明暗层级 —— 主色不会分毫不差。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val picked = ThemeSeeds.parseHex(settings.seedColor)
                ThemeSeeds.all.chunked(5).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { seed ->
                            SeedDot(
                                seed = seed,
                                selected = !settings.dynamicColor && picked == seed.argb,
                                onClick = {
                                    // 点种子 = 明确要自己选，顺手关掉取色。
                                    // 不关的话自动的种子优先级更高，点了没反应是最糟的反馈
                                    update { it.copy(seedColor = seed.hex, dynamicColor = false) }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            // ---------------- 语言 ----------------
            AppearanceCard {
                Text(
                    text = "界面语言",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppLanguage.SYSTEM to "跟随系统",
                        AppLanguage.ZH to "中文",
                        AppLanguage.EN to "English",
                    ).forEach { (lang, label) ->
                        FilterChip(
                            selected = settings.appLanguage == lang,
                            onClick = { update { it.copy(appLanguage = lang) } },
                            label = { Text(label) },
                        )
                    }
                }
            }

            Text(
                text = "手机界面按 Material 3 标准搭建：色板来自上面的种子，" +
                    "字号、圆角、页面骨架独立于手表那套。切回手表布局时这些颜色照样生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * 取色状态要诚实。
 *
 * 「开着但取不到壁纸」和「关着用自己挑的色」看起来一模一样，用户只能理解成
 * 「开了没反应」。所以三种来源各给一句话，取自缓存里上一次真正用到的那个。
 */
@Composable
private fun seedSourceText(settings: AppSettings): String {
    if (!settings.dynamicColor) {
        return if (settings.seedColor.isBlank()) "已关闭 · 正在用 Material 3 默认色板"
        else "已关闭 · 正在用你挑的种子色"
    }
    return when (SeedCache.lastSource) {
        SeedSource.Source.BACKGROUND -> "已开启 · 配色跟随自定义背景"
        SeedSource.Source.WALLPAPER -> "已开启 · 配色跟随壁纸主色"
        SeedSource.Source.NONE -> "已开启 · 这台设备取不到壁纸，暂用默认色相"
    }
}

@Composable
private fun AppearanceCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

/**
 * 一个种子色圆点。
 *
 * 选中的那个**同时**加环与勾：只加环在深浅两色板里都可能和圆点本身糊在一起，
 * 只加勾则离得稍远就看不出选的是哪个。
 */
@Composable
private fun SeedDot(seed: ThemeSeed, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) 40.dp else 36.dp)
                .background(Color(seed.argb), CircleShape)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) scheme.onSurface else Color.Transparent,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
