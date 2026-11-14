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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.Appearances
import androidx.compose.foundation.layout.heightIn
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ChatLayouts
import com.dshwatch.domain.model.ScreenShapes
import com.dshwatch.domain.model.ThemeMode
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PageDots
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SegmentedRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.WatchSlider
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.theme.Accents
import com.dshwatch.ui.theme.ThemePresets
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.screens.scene.sceneIcon
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.theme.LocalUiScale
import com.dshwatch.ui.theme.LocalGlass
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember

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
    // 当前生效的缩放（含自动值）。关掉「跟随本机」时要用它作为手动起点
    val currentScale = LocalUiScale.current

    // 返回键：圆屏上它承担「上一步」—— 圆屏底栏只放得下一个按钮，
    // 两个并排会把「下一步」压到 60dp。所以回退动作交给系统返回键，
    // 退到第一步之后再按一次才「就用当前选择」离开引导。
    // 方屏维持原行为（底栏两个按钮都在，返回键直接完成引导）。
    val circle = com.dshwatch.ui.wear.LocalCircle.current
    BackHandler {
        if (circle != null && step > 0) {
            step--
        } else {
            update { it.copy(onboardingDone = true) }
        }
    }

    fun goNext() {
        if (step >= 3) {
            update { it.copy(onboardingDone = true) }
            return
        }
        if (step == 0 && settings.screenShape == ScreenShapes.AUTO) {
            // 用户什么都没点就直接下一步：把他看到的这个形状落成显式选择，
            // 否则进到布局页时，可用布局列表可能因为系统检测值而变。
            update { it.copy(screenShape = if (detectedRound) ScreenShapes.ROUND else ScreenShapes.SQUARE) }
        }
        step++
    }

    /*
     * 圆屏的三段边距，各按自己那一行的弦长算。
     *
     * 基准是**圆的直径**，不是屏幕高：这台机器 412×502（329.6×401.6dp），
     * 圆只占中间 329.6dp，上下各有 36dp 黑边。拿屏幕高算，整页会整体下沉、
     * 底部按钮掉到圆周外面 —— 看着在，按下去没反应。
     *
     * 三段分开之后：指示行 / 内容 / 按钮各按自己那一行的弦宽收，
     * 全部落在圆内。
     */
    val d = circle?.diameter?.value ?: 0f
    val topY = circle?.top?.value ?: 0f
    val barY = topY + d * 0.08f
    val barInset = circle?.let { (it.insetAt(barY) + 2f).dp } ?: 0.dp
    val bodyInset = circle?.let { (it.insetAt(topY + d * 0.72f) + 2f).dp }
        ?: if (round) 20.dp else 10.dp
    val footInset = circle?.let { (it.insetAt(topY + d * 0.80f) + 2f).dp }
        ?: if (round) 20.dp else 10.dp
    // 圆底到屏幕底那段不能用：那里弦宽趋近 0，按钮放上去就是个装饰品
    val bottomPad = circle?.let { it.height - it.top - it.diameter * 0.80f }
        ?: 8.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = if (circle != null) bottomPad else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        /*
         * 圆屏不画主标题。
         *
         * 那一行贴着圆顶，弦宽只有直径的零头 —— 任何一句「欢迎」都会被切成两半。
         * 文本留白在这里不是风格问题，是几何问题。
         * 步骤指示（"2 / 4" + 圆点）足够让用户知道进度。
         */
        Spacer(Modifier.height(if (circle != null) barY.dp else 6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (circle != null) barInset else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (circle == null) {
                Text(
                    text = s.onboardingTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${step + 1} / 4",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(4.dp))
        PageDots(
            count = 4,
            current = step,
            activeColor = MaterialTheme.colorScheme.primary,
            inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = bodyInset)
                .wearVerticalScroll(rememberScrollState()),
        ) {
            when (step) {
                0 -> ShapeStep(
                    round = round,
                    detectedRound = detectedRound,
                    selected = settings.screenShape,
                    onPick = { choice -> update { it.copy(screenShape = choice) } },
                )
                1 -> SceneStep(
                    selected = settings.useScene,
                    onPick = { id ->
                        // 场景顺便影响"进 App 看到什么"，所以立刻落盘；
                        // 布局保持用户已在第一步之外的选择，不在这里改。
                        update { it.copy(useScene = id) }
                    },
                )
                2 -> LayoutStep(
                    layouts = layouts,
                    selected = ChatLayouts.of(settings.chatLayout),
                    onPick = { id -> update { it.copy(chatLayout = id) } },
                )
                else -> ThemeStep(
                    settings = settings,
                    dark = dark,
                    onPickUiStyle = { id -> update { it.copy(uiStyle = id) } },
                    onPickUiScale = { v -> update { it.copy(uiScale = v, uiScaleAuto = false) } },
                    onToggleUiScaleAuto = { v ->
                        update {
                            it.copy(
                                uiScaleAuto = v,
                                // 关掉自动时落到当前生效值，避免界面突然跳回 1.0
                                uiScale = if (v) it.uiScale else currentScale,
                            )
                        }
                    },
                    onPickAppearance = { id ->
                        // 选风格时顺带关掉动态取色：动态取色会整块替换色板，
                        // 玻璃的底色晕就没了依据（见 DshWatchTheme 的说明）
                        update {
                            it.copy(
                                appearance = id,
                                dynamicColor = if (id == Appearances.GLASS) false else it.dynamicColor,
                            )
                        }
                    },
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (circle != null) footInset else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 圆屏底栏只有约 120dp 可用：两个按钮各 57dp，"上一步"挤成一条缝。
            // 所以圆屏只留一个主按钮，回退交给系统返回键（见上面的 BackHandler）。
            if (step > 0 && circle == null) {
                GhostButton(
                    text = s.onboardingBack,
                    modifier = Modifier.weight(1f),
                    onClick = { step-- },
                )
            }
            PrimaryButton(
                text = if (step >= 3) s.onboardingStart else s.onboardingNext,
                modifier = Modifier.weight(if (step > 0 && circle == null) 1.4f else 1f),
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
    val glass = LocalGlass.current
    val src = remember { MutableInteractionSource() }
    val border = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val bg = if (active) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column(
        modifier = modifier
            .then(
                if (glass) {
                    // 玻璃主题：选中卡染色玻璃，未选中的就是一块中性玻璃
                    Modifier
                        .glassSurface(
                            shape = RoundedCornerShape(18.dp),
                            cornerRadius = 18.dp,
                            tint = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            interaction = src,
                        )
                        .tap(quiet = true, interaction = src, onClick = withHaptic(onClick))
                } else {
                    Modifier
                        .tap(onClick = withHaptic(onClick))
                        .background(bg, RoundedCornerShape(18.dp))
                        .border(1.dp, border, RoundedCornerShape(18.dp))
                },
            )
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

/**
 * 第二步：主要用这台手表做什么。
 *
 * 为什么放在**布局之前**：用户选了"角色扮演"，第三步的布局就有了默认答案
 * （简化气泡 + 不开鱼眼），不必让他自己再想一遍。这也是这一步存在的唯一理由 ——
 * 如果它不影响后面任何选择，那它就只是多按一次。
 */
@Composable
private fun SceneStep(selected: String, onPick: (String) -> Unit) {
    StepHeader("主要用来做什么？", "决定进 App 先进哪个界面，之后随时能在顶栏切换")
    Column(modifier = Modifier.fillMaxWidth()) {
        SceneChoices.forEach { (id, label, hint) ->
            val active = Scenes.of(selected) == id
            val src = remember(id) { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .then(
                        if (LocalGlass.current) {
                            Modifier
                                .glassSurface(
                                    shape = RoundedCornerShape(16.dp),
                                    cornerRadius = 16.dp,
                                    tint = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    interaction = src,
                                )
                                .tap(quiet = true, interaction = src, onClick = withHaptic { onPick(id) })
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .tap(onClick = withHaptic { onPick(id) })
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = sceneIcon(id),
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private val SceneChoices = listOf(
    Triple(Scenes.CODING, "编程 / 干活", "写代码、跑脚本、查资料、做任务"),
    Triple(Scenes.TAVERN, "角色扮演", "和角色聊天，看剧情怎么走"),
    Triple(Scenes.WRITING, "写作", "写小说、续写、整理设定"),
)

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
        }
    }
}

@Composable
private fun ThemeStep(
    settings: AppSettings,
    dark: Boolean,
    onPickUiStyle: (String) -> Unit,
    onPickAppearance: (String) -> Unit,
    onPickPreset: (String) -> Unit,
    onPickAccent: (String) -> Unit,
    onPickUiScale: (Float) -> Unit,
    onToggleUiScaleAuto: (Boolean) -> Unit,
) {
    val s = LocalStrings.current
    StepHeader(s.onboardingThemeTitle, s.onboardingThemeHint)

    /*
     * 界面大小放在最前面。
     *
     * 高 dpi 小屏上，剩下的主题、预设、强调色是在一个已经溢出的界面上铺开的，
     * 选项本身就可能被裁掉一半。先让用户把界面收进屏幕，后面才好选。
     * 拖动即时生效（密度变化会整体重组），所见即所得，不需要「预览」按钮。
     */
    val effectiveScale = LocalUiScale.current
    val scaleText = String.format(java.util.Locale.US, "%.2f×", effectiveScale)
    WatchSlider(
        value = (if (settings.uiScaleAuto) effectiveScale else settings.uiScale)
            .coerceIn(com.dshwatch.domain.UiScale.MIN, com.dshwatch.domain.UiScale.MAX),
        onChange = onPickUiScale,
        valueRange = com.dshwatch.domain.UiScale.MIN..com.dshwatch.domain.UiScale.MAX,
        steps = 10,
        label = s.uiScale,
        valueText = if (settings.uiScaleAuto) "自动 $scaleText" else scaleText,
    )
    SwitchRow(
        title = s.uiScaleAuto,
        subtitle = s.uiScaleHint,
        checked = settings.uiScaleAuto,
        onCheckedChange = onToggleUiScaleAuto,
    )
    Spacer(Modifier.height(6.dp))

    /*
     * 界面体系：在材质之前。
     *
     * 这是唯一一个「决定页面长什么样」的开关 —— 骨架、卡片、按钮、开关全换。
     * 放在引导页里而不是只藏在设置深处，是因为绝大多数人不会去翻「外观」，
     * 而这一项的效果比换颜色明显得多。
     */
    Text(
        text = "界面体系",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(3.dp))
    SegmentedRow(
        options = listOf("经典", "M3E"),
        selectedIndex = if (com.dshwatch.domain.model.UiStyles.isM3e(settings.uiStyle)) 1 else 0,
        onSelect = { index ->
            onPickUiStyle(
                if (index == 1) {
                    com.dshwatch.domain.model.UiStyles.M3E
                } else {
                    com.dshwatch.domain.model.UiStyles.CLASSIC
                },
            )
        },
    )
    Text(
        text = "经典：自绘骨架，每屏多放一行。M3E：Wear 原生组件，观感更接近系统。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 2.dp),
    )
    Spacer(Modifier.height(8.dp))

    // ---- 第一步：先选材质，再选颜色 ----
    //
    // 顺序是有讲究的：材质决定了「卡片长什么样」，颜色只是在它上面换一套色。
    // 反过来问（先选颜色）会出现「挑完樱花，下一屏突然问你要不要玻璃」——
    // 用户会以为是把刚选的颜色弄丢了。
    AppearanceRow(
        selected = Appearances.of(settings.appearance),
        onPick = onPickAppearance,
    )
    Spacer(Modifier.height(8.dp))

    Text(
        text = s.themePreset,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))

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


/**
 * 两种外观风格并排。
 *
 * 左边的卡片**当场用该风格画**：M3E 是不透明的容器色，玻璃是半透明 + 描边。
 * 只给文字说明「质感玻璃」是不够的 —— 用户没法想象，一定会随手选第一个。
 * 让他看见差别，选择才有意义。
 */
@Composable
private fun AppearanceRow(selected: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppearanceCard(
            modifier = Modifier.weight(1f),
            id = Appearances.M3E,
            title = "M3E",
            hint = "纯色卡片",
            active = selected == Appearances.M3E,
            onClick = { onPick(Appearances.M3E) },
        )
        AppearanceCard(
            modifier = Modifier.weight(1f),
            id = Appearances.GLASS,
            title = "质感玻璃",
            hint = "半透明 · 透出底色",
            active = selected == Appearances.GLASS,
            onClick = { onPick(Appearances.GLASS) },
        )
    }
}

@Composable
private fun AppearanceCard(
    modifier: Modifier = Modifier,
    id: String,
    title: String,
    hint: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val isGlass = id == Appearances.GLASS
    Column(
        modifier = modifier
            .heightIn(min = 62.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp),
            )
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary
                else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp),
            )
            .tap(onClick = withHaptic(onClick))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 小样：两种风格各画一次自己的「卡片」
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .then(
                    if (isGlass) {
                        Modifier
                            .background(Color.White.copy(alpha = 0.30f), RoundedCornerShape(7.dp))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.55f),
                                RoundedCornerShape(7.dp),
                            )
                    } else {
                        Modifier.background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(7.dp),
                        )
                    },
                ),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
