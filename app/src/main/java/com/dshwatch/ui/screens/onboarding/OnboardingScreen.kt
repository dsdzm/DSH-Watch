package com.dshwatch.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatLayouts
import com.dshwatch.domain.model.ScreenShapes
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PageDots
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.theme.Accents
import com.dshwatch.ui.theme.ThemePresets
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 首次启动的三步引导：屏幕形状 → 对话布局 → 主题。
 *
 * 每一步都**立即写入设置**（而不是攒到最后一并提交）：
 *  - 主题可以边选边预览，因为整个 App 的主题就是这个设置渲染的；
 *  - 用户中途被杀进程，回来还停在他选过的那一页上，不用从头再来。
 * 只有最后一步才把 `onboardingDone` 置 true —— 那一下才真正离开引导。
 */
@Composable
fun OnboardingScreen(container: AppContainer, settings: AppSettings) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }

    val configuration = LocalConfiguration.current
    val detectedRound = runCatching { configuration.isScreenRound }.getOrDefault(false)
    val round = ScreenShapes.resolve(settings.screenShape, detectedRound)
    val layouts = ChatLayouts.available(round)
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val update: ((AppSettings) -> AppSettings) -> Unit = { block ->
        scope.launch { container.settingsRepo.update(block) }
    }

    // 返回键视作「就用当前选择」：不把用户困在引导里，也不直接退出 App
    BackHandler {
        update { it.copy(onboardingDone = true) }
    }

    fun goNext() {
        if (step >= 2) {
            update {
                it.copy(
                    onboardingDone = true,
                    // 环形布局只在圆屏上有意义；从圆屏切到方屏时收回气泡
                    chatLayout = if (it.chatLayout == ChatLayouts.RING &&
                        !ScreenShapes.resolve(it.screenShape, detectedRound)
                    ) ChatLayouts.BUBBLE else it.chatLayout
                )
            }
            return
        }
        if (step == 0 && settings.screenShape == ScreenShapes.AUTO) {
            // 用户什么都没点就直接下一步：把他看到的这个形状落成显式选择，
            // 否则进到布局页时「环形」可能因为系统检测值而消失。
            update { it.copy(screenShape = if (detectedRound) ScreenShapes.ROUND else ScreenShapes.SQUARE) }
        }
        step++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = if (round) 20.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = s.onboardingTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${step + 1} / 3",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
        PageDots(
            count = 3,
            current = step,
            activeColor = MaterialTheme.colorScheme.primary,
            inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .wearVerticalScroll(rememberScrollState()),
        ) {
            when (step) {
                0 -> ShapeStep(
                    round = round,
                    detectedRound = detectedRound,
                    selected = settings.screenShape,
                    onPick = { choice -> update { it.copy(screenShape = choice) } },
                )
                1 -> LayoutStep(
                    layouts = layouts,
                    selected = ChatLayouts.of(settings.chatLayout),
                    onPick = { id -> update { it.copy(chatLayout = id) } },
                )
                else -> ThemeStep(
                    settings = settings,
                    dark = dark,
                    onPickPreset = { id ->
                        update { it.copy(themePreset = id, dynamicColor = false) }
                    },
                    onPickAccent = { id ->
                        update { it.copy(accentColor = id, dynamicColor = false) }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                GhostButton(
                    text = s.onboardingBack,
                    modifier = Modifier.weight(1f),
                    onClick = { step-- },
                )
            }
            PrimaryButton(
                text = if (step >= 2) s.onboardingStart else s.onboardingNext,
                modifier = Modifier.weight(if (step > 0) 1.4f else 1f),
                onClick = ::goNext,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ShapeStep(
    round: Boolean,
    detectedRound: Boolean,
    selected: String,
    onPick: (String) -> Unit,
) {
    val s = LocalStrings.current
    StepHeader(s.onboardingShapeTitle, s.onboardingShapeHint)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ShapeCard(
            modifier = Modifier.weight(1f),
            round = true,
            label = s.shapeRound,
            active = round,
            onClick = { onPick(ScreenShapes.ROUND) },
        )
        ShapeCard(
            modifier = Modifier.weight(1f),
            round = false,
            label = s.shapeSquare,
            active = !round,
            onClick = { onPick(ScreenShapes.SQUARE) },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = if (detectedRound) s.onboardingDetectedRound else s.onboardingDetectedSquare,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun ShapeCard(
    modifier: Modifier,
    round: Boolean,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val border = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val bg = if (active) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column(
        modifier = modifier
            .tap(onClick = withHaptic(onClick))
            .background(bg, RoundedCornerShape(18.dp))
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.size(54.dp)) {
            val stroke = Stroke(width = 2.5f)
            val strokeColor = if (active) {
                Color(0xFF9EC1FF)
            } else {
                Color(0xFF8A8A92)
            }
            val inner = Color(0xFFB9C6D8)
            if (round) {
                drawCircle(color = strokeColor, radius = size.minDimension / 2f, style = stroke)
                // 圆内三行示意文字：越靠边越短，正是圆形可用宽度收窄的样子
                listOf(0.34f to 0.62f, 0.5f to 0.72f, 0.66f to 0.54f).forEach { (y, w) ->
                    drawRoundRect(
                        color = inner,
                        topLeft = Offset(size.width * (0.5f - w / 2f), size.height * y),
                        size = Size(size.width * w, size.height * 0.06f),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
            } else {
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(size.width * 0.2f),
                    style = stroke,
                )
                listOf(0.34f, 0.5f, 0.66f).forEachIndexed { i, y ->
                    drawRoundRect(
                        color = inner,
                        topLeft = Offset(size.width * 0.18f, size.height * y),
                        size = Size(size.width * if (i == 2) 0.4f else 0.64f, size.height * 0.06f),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LayoutStep(
    layouts: List<String>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val s = LocalStrings.current
    StepHeader(s.onboardingLayoutTitle, s.onboardingLayoutHint)
    layouts.forEach { id ->
        val active = ChatLayouts.of(selected) == id
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(16.dp),
                )
                .tap(onClick = withHaptic { onPick(id) })
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LayoutThumb(layout = id, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.chatLayoutLabel(id),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = s.chatLayoutHint(id),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LayoutThumb(layout: String, tint: Color) {
    Canvas(modifier = Modifier.size(36.dp)) {
        val stroke = Stroke(width = 1.5f)
        when (layout) {
            ChatLayouts.BUBBLE -> {
                drawRoundRect(tint.copy(alpha = 0.75f), Offset(0f, size.height * 0.12f), Size(size.width * 0.58f, size.height * 0.3f), CornerRadius(8f))
                drawRoundRect(tint.copy(alpha = 0.35f), Offset(size.width * 0.3f, size.height * 0.56f), Size(size.width * 0.7f, size.height * 0.3f), CornerRadius(8f))
            }
            ChatLayouts.CARD -> {
                drawRoundRect(tint.copy(alpha = 0.3f), Offset.Zero, size, CornerRadius(7f))
                drawRect(tint, Offset(2f, 4f), Size(2.5f, size.height - 8f))
                drawRoundRect(tint.copy(alpha = 0.6f), Offset(size.width * 0.2f, size.height * 0.3f), Size(size.width * 0.6f, size.height * 0.1f), CornerRadius(4f))
                drawRoundRect(tint.copy(alpha = 0.4f), Offset(size.width * 0.2f, size.height * 0.55f), Size(size.width * 0.45f, size.height * 0.1f), CornerRadius(4f))
            }
            ChatLayouts.MINIMAL -> {
                drawCircle(tint, 3f, Offset(4f, size.height * 0.2f))
                listOf(0.2f, 0.45f, 0.7f).forEachIndexed { i, y ->
                    drawRoundRect(
                        tint.copy(alpha = if (i == 0) 0.8f else 0.45f),
                        Offset(size.width * 0.2f, size.height * y),
                        Size(size.width * (if (i == 2) 0.45f else 0.72f), size.height * 0.09f),
                        CornerRadius(3f),
                    )
                }
            }
            else -> {
                drawCircle(tint.copy(alpha = 0.7f), size.minDimension / 2f, style = stroke)
                listOf(0.36f to 0.5f, 0.52f to 0.6f, 0.68f to 0.4f).forEach { (y, w) ->
                    drawRoundRect(
                        tint.copy(alpha = 0.55f),
                        Offset(size.width * (0.5f - w / 2f), size.height * y),
                        Size(size.width * w, size.height * 0.08f),
                        CornerRadius(4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeStep(
    settings: AppSettings,
    dark: Boolean,
    onPickPreset: (String) -> Unit,
    onPickAccent: (String) -> Unit,
) {
    val s = LocalStrings.current
    StepHeader(s.onboardingThemeTitle, s.onboardingThemeHint)

    ThemePresets.all.forEach { preset ->
        val active = preset.id == settings.themePreset
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(14.dp),
                )
                .tap(onClick = withHaptic { onPickPreset(preset.id) })
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ThemePresets.swatch(preset.id, settings.accentColor, dark).forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(c, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = s.themePresetLabel(preset.id),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (active) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = s.accentColor,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(5.dp))
    Accents.all.chunked(3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { accent ->
                val active = accent.id == settings.accentColor
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .tap(onClick = withHaptic { onPickAccent(accent.id) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(if (dark) accent.darkPrimary else accent.lightPrimary, CircleShape),
                        )
                        Text(
                            text = s.accentLabel(accent.id),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(title: String, hint: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

