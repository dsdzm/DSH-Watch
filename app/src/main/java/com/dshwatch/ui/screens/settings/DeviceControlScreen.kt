package com.dshwatch.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.device.BatteryKeepAlive
import com.dshwatch.domain.device.DeviceControlHub
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.service.DeviceA11yService
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「设备操控」设置页。
 *
 * 这一页要同时回答三个问题，顺序就是用户实际的疑问：
 *  1. 它到底能干什么、会看到什么（说明放在最上面，不是折叠起来）；
 *  2. 现在能不能用（开关 + 系统无障碍状态，两者是**与**的关系）；
 *  3. 怎么立刻停下来（运行中时给一个显眼的停止按钮）。
 *
 * 开关与系统权限刻意分成两层：应用内开关只决定「工具要不要出现在工具表里」，
 * 真正的能力来自系统无障碍授权。两层都开才会工作 —— 这是故意让用户多走一步。
 */
@Composable
fun DeviceControlScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val zh = s.isChinese
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hubState by DeviceControlHub.state.collectAsState()

    // 系统无障碍状态没有回调可订阅，只能轮询。1 秒一次、只在页面可见时跑，
    // 代价可以忽略，但用户从系统设置切回来时能看到状态立刻变。
    var enabled by remember { mutableStateOf(DeviceA11yService.isEnabled(context)) }
    var connected by remember { mutableStateOf(DeviceA11yService.isConnected()) }
    var exempt by remember { mutableStateOf(BatteryKeepAlive.isExempt(context)) }
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            enabled = DeviceA11yService.isEnabled(context)
            connected = DeviceA11yService.isConnected()
            // 电池白名单的变化没那么快，每 3 秒查一次就够（它要走系统服务）
            if (tick++ % 3 == 0) exempt = BatteryKeepAlive.isExempt(context)
            delay(1000)
        }
    }

    val status = when {
        connected -> if (zh) "已连接，可以使用" else "Connected"
        enabled -> if (zh) "已授权，等待系统连接" else "Granted, waiting for system"
        else -> if (zh) "未授权" else "Not granted"
    }

    DisposableEffect(Unit) { onDispose { } }

    WatchScaffold(title = if (zh) "设备操控" else "Device control", onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
        ) {
            SectionHeader(if (zh) "它能做什么" else "What it does")
            SettingCard {
                Text(
                    text = if (zh) {
                        "打开后，AI 可以读取当前屏幕上的按钮和文字，并按你的指示点击、输入、翻页。" +
                            "比如「帮我在微信里给张三发一句我晚点到」。\n\n" +
                            "操作期间 DSH 会退到后台，屏幕底部出现一条提示，写着正在做什么，" +
                            "右边就是停止按钮。点一下立刻停。"
                    } else {
                        "When on, the AI can read the buttons and text on screen and tap, type or " +
                            "scroll as you instruct. DSH moves to the background while it works; a bar " +
                            "at the bottom shows what it is doing, with a stop button."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }

            SectionHeader(if (zh) "开关" else "Switch")
            SettingCard {
                SwitchRow(
                    title = if (zh) "允许 AI 操作设备" else "Allow device control",
                    subtitle = if (zh) {
                        "关闭后，这四个工具连描述都不会发给模型"
                    } else {
                        "When off, these tools are not even sent to the model"
                    },
                    checked = settings.deviceControlEnabled,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.settingsRepo.update { it.copy(deviceControlEnabled = on) }
                            if (!on) DeviceControlHub.end()
                        }
                    },
                )
            }

            SectionHeader(if (zh) "系统权限" else "System permission")
            SettingCard {
                SettingRow(
                    title = if (zh) "无障碍服务" else "Accessibility service",
                    subtitle = status,
                    trailing = if (connected) "✓" else "›",
                    showDivider = !connected,
                    onClick = if (connected) null else {
                        { DeviceA11yService.openSettings(context) }
                    },
                )
                if (!connected) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        HintText(
                            if (zh) {
                                "在系统设置的「无障碍 → 已下载的应用」里找到 DSH 肥鱼 并打开。" +
                                    "部分手表把这一项藏在「辅助功能」或「更多设置」里。"
                            } else {
                                "Open Settings → Accessibility → Downloaded apps and enable DSH. " +
                                    "On some watches it is under Additional settings."
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        GhostButton(
                            text = if (zh) "去系统设置" else "Open settings",
                            onClick = { DeviceA11yService.openSettings(context) },
                        )
                    }
                }
            }

            SectionHeader(if (zh) "后台保活" else "Keep alive")
            SettingCard {
                SettingRow(
                    title = if (zh) "不受电池优化限制" else "Ignore battery optimization",
                    subtitle = if (exempt) {
                        if (zh) "已加入白名单" else "Allowed"
                    } else {
                        if (zh) "未加入，操作可能被系统中断" else "Not allowed — may be killed"
                    },
                    trailing = if (exempt) "✓" else "›",
                    onClick = if (exempt) null else {
                        { BatteryKeepAlive.request(context) }
                    },
                )
                if (!exempt) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        HintText(
                            if (zh) {
                                "操作期间 DSH 在后台，手表的低内存回收可能把进程清掉，" +
                                    "表现为「操作到一半，悬浮条和任务一起消失」。" +
                                    "把它加入白名单能大幅降低概率，但不能保证绝对不被杀。"
                            } else {
                                "DSH runs in the background while operating. Low-memory killers may " +
                                    "remove the process and the task with it. Whitelisting makes that " +
                                    "much less likely, but cannot guarantee it."
                            },
                        )
                    }
                }
            }

            if (hubState.running) {
                SectionHeader(if (zh) "正在操作" else "In progress")
                SettingCard {
                    SettingRow(
                        title = hubState.intent.ifBlank { if (zh) "执行中" else "Working" },
                        subtitle = hubState.appLabel,
                    )
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        GhostButton(
                            text = if (zh) "停止" else "Stop",
                            onClick = { DeviceControlHub.requestStop("用户在设置页点了停止") },
                        )
                    }
                }
            }

            SectionHeader(if (zh) "边界" else "Limits")
            SettingCard {
                Column(modifier = Modifier.padding(10.dp)) {
                    listOf(
                        if (zh) "密码、验证码、支付确认框：不读内容，也不代填代点"
                        else "Password, OTP and payment confirmations: never read or filled",
                        if (zh) "读取只在调用工具时发生一次，不投屏、不录屏、不落盘"
                        else "Screen is read once per tool call; no recording, nothing stored",
                        if (zh) "屏幕内容会随请求发给当前模型 —— 这是它能看懂界面的前提"
                        else "Screen text is sent to the current model — that is how it understands",
                        if (zh) "权限管理、应用安装、系统界面：只读不点，避免 AI 给自己加权限"
                        else "Permission pages, installers and system UI: read-only",
                    ).forEach {
                        Text(
                            text = "· $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            HintText(
                if (zh) {
                    "提示：不需要这个功能时请把它关掉。开关关着时，无障碍服务虽然还在系统列表里，" +
                        "但应用不会读任何内容。"
                } else {
                    "Tip: turn it off when you do not need it. The service may stay enabled in " +
                        "system settings, but nothing is read unless a task calls it."
                },
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}
