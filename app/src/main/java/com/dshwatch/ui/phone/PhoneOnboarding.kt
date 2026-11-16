package com.dshwatch.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.DeviceKinds
import com.dshwatch.domain.model.ThemeMode
import kotlinx.coroutines.launch

/**
 * 手机版引导。
 *
 * 三步：**设备类型 → 明暗 → 完成**。
 *
 * ## 为什么第一步问设备类型
 *
 * 检测靠 `uiMode`，绝大多数设备是对的；但方形表、以及在平板上装这个 App 的人
 * 会被判错 —— 判错的代价是「整个界面不是他要的那套」，比任何配色问题都严重。
 * 所以第一屏就把当前判断摆出来，默认选中检测结果，想改的人一步就能改。
 *
 * 放在第一步还有一个好处：它是**唯一会切换整套界面**的选项。放在最后，
 * 用户刚看完手机版的引导，点一下「手表」整屏换成另一个样子，会以为点坏了。
 */
@Composable
fun PhoneOnboarding(
    container: AppContainer,
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }

    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { container.settingsRepo.update(block) }
    }

    BackHandler {
        if (step > 0) step-- else update { it.copy(onboardingDone = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(64.dp))
        Text(
            text = "${step + 1} / 3",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))

        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                0 -> DeviceStep(
                    current = DeviceKinds.of(settings.deviceKind),
                    onPick = { kind ->
                        update { it.copy(deviceKind = kind, deviceKindChosen = true) }
                    },
                )
                1 -> ThemeStep(
                    current = settings.themeMode,
                    onPick = { mode -> update { it.copy(themeMode = mode) } },
                )
                else -> DoneStep()
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (step > 0) {
                TextButton(onClick = { step-- }) { Text("上一步") }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (step >= 2) update { it.copy(onboardingDone = true) } else step++
                },
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (step >= 2) "开始使用" else "下一步")
            }
        }
    }
}

@Composable
private fun DeviceStep(current: String, onPick: (String) -> Unit) {
    Text(
        text = "你在什么设备上用？",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "手表是一套圆屏界面，手机是底部导航界面。选错了随时能在设置里改回来。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    DeviceOption(
        icon = Icons.Outlined.Watch,
        title = "手表",
        desc = "圆屏 / 小屏，内容按圆周安全区排布",
        selected = current == DeviceKinds.WATCH,
        onClick = { onPick(DeviceKinds.WATCH) },
    )
    Spacer(Modifier.height(12.dp))
    DeviceOption(
        icon = Icons.Outlined.PhoneAndroid,
        title = "手机",
        desc = "底部三个入口，对话、记录、设置各占一页",
        selected = current == DeviceKinds.PHONE,
        onClick = { onPick(DeviceKinds.PHONE) },
    )
}

@Composable
private fun DeviceOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ThemeStep(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    Text(
        text = "界面明暗",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "手机版只跟随这一个开关。手表那套主题预设、强调色、玻璃材质不会影响这里。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            ThemeMode.SYSTEM to "跟随系统",
            ThemeMode.LIGHT to "浅色",
            ThemeMode.DARK to "深色",
        ).forEach { (mode, label) ->
            FilterChip(
                selected = current == mode,
                onClick = { onPick(mode) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun DoneStep() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "可以开始了",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "内置模型通道已经准备好，直接说话就行。\n想换成自己的 API Key，去「设置 → 供应商与模型」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
