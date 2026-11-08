package com.dshwatch.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.ScreenShapes
import com.dshwatch.ui.components.PageDots
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.wear.LocalCircle
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 引导页之后的「新手教学」。
 *
 * ## 为什么单独一页，而不是塞进引导页
 *
 * 引导页（4 步）解决的是「长什么样」：形状、场景、布局、主题 —— 全是点一下就能看到结果的选择。
 * 这里解决的是「怎么用」：API Key、地址、模型、上下文、温度 —— 全是概念，
 * 混在一起会让第一次启动变成一场考试。
 *
 * ## 为什么老用户也会看到
 *
 * 判定用独立的 [AppSettings.tutorialDone]，不复用 `onboardingDone`。
 * 老用户升级后同样会走一遍 —— 他们恰恰是最需要知道「内置模型」和「温度怎么调」的人，
 * 而这两件事在旧版本里根本不存在。
 */
@Composable
fun TutorialScreen(container: AppContainer, settings: AppSettings) {
    val circle = LocalCircle.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }

    val zh = isChinese(settings)
    val pages = remember(zh) { tutorialPages(zh) }
    val last = pages.lastIndex

    fun finish() {
        scope.launch { container.settingsRepo.update { it.copy(tutorialDone = true) } }
    }

    BackHandler {
        if (step > 0) step-- else finish()
    }

    // 与引导页同一套弦长边距：三段各按自己那一行收，全部落在圆内
    val d = circle?.diameter?.value ?: 0f
    val topY = circle?.top?.value ?: 0f
    val barY = topY + d * 0.08f
    val barInset = circle?.let { (it.insetAt(barY) + 2f).dp } ?: 0.dp
    val bodyInset = circle?.let { (it.insetAt(topY + d * 0.72f) + 2f).dp }
        ?: if (circle != null) 20.dp else 10.dp
    val footInset = circle?.let { (it.insetAt(topY + d * 0.80f) + 2f).dp }
        ?: if (circle != null) 20.dp else 10.dp
    val bottomPad = circle?.let { it.height - it.top - it.diameter * 0.80f } ?: 8.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = if (circle != null) bottomPad else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(if (circle != null) barY.dp else 6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (circle != null) barInset else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (zh) "上手 · ${step + 1} / ${pages.size}" else "Guide · ${step + 1} / ${pages.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(4.dp))
        PageDots(
            count = pages.size,
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
            val page = pages[step]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .glassSurface(
                            shape = CircleShape,
                            cornerRadius = 13.dp,
                            tint = MaterialTheme.colorScheme.primary,
                            solid = true,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            page.body.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            // 小贴士：把「上手之后马上会踩的坑」单独拎出来，正文只讲概念
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(
                        shape = RoundedCornerShape(12.dp),
                        cornerRadius = 12.dp,
                        tint = MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = page.tip,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = footInset, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0 && circle == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .glassSurface(
                            shape = RoundedCornerShape(12.dp),
                            cornerRadius = 12.dp,
                            tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                            onClick = { step-- },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (zh) "上一步" else "Back",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(6.dp))
            }
            Box(
                modifier = Modifier
                    .weight(if (step > 0 && circle == null) 1.4f else 1f)
                    .height(38.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(12.dp),
                        cornerRadius = 12.dp,
                        tint = MaterialTheme.colorScheme.primary,
                        solid = true,
                        onClick = { if (step >= last) finish() else step++ },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (step >= last) {
                        if (zh) "开始使用" else "Start"
                    } else {
                        if (zh) "下一步" else "Next"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class TutorialPage(
    val icon: ImageVector,
    val title: String,
    val body: List<String>,
    val tip: String,
)

private fun isChinese(settings: AppSettings): Boolean = when (settings.appLanguage) {
    "zh" -> true
    "en" -> false
    else -> Locale.getDefault().language.startsWith("zh")
}

private fun tutorialPages(zh: Boolean): List<TutorialPage> = if (zh) {
    listOf(
        TutorialPage(
            icon = Icons.Outlined.Key,
            title = "API Key 是什么",
            body = listOf(
                "它是一串很长的字符，相当于你在模型服务商那里的钥匙。",
                "每发一条消息，服务商都凭它认出「这是谁在用」，并从你的额度里扣费。所以它等同于密码：别发群里，别贴进公开仓库。",
            ),
            tip = "填一次就够，之后存在本机加密区，不会进日志、也不会随备份上传。",
        ),
        TutorialPage(
            icon = Icons.Outlined.Tune,
            title = "已经给你接好了",
            body = listOf(
                "内置了两条通道：Agnes 装了 5 个免费模型（两个文本、两个出图、一个出视频），智谱 GLM 装了 3 个（文本、视觉、出图）。",
                "不用填任何东西，回到对话页就能发第一条消息。按量收费的模型都没有放进来。",
            ),
            tip = "内置项只让你调参数（温度、上下文等），Key 与地址由应用托管、不显示也不能改；想用自己的账号就新建一个供应商。",
        ),
        TutorialPage(
            icon = Icons.Outlined.Memory,
            title = "接自己的供应商",
            body = listOf(
                "地址填到 /v1 为止，不要带 /chat/completions —— 后者由 App 自己拼。",
                "填完点「测试连接」，再点「拉取模型」，最后选一个默认模型。",
            ),
            tip = "本地跑 Ollama / LM Studio 时，地址填 http://电脑IP:11434/v1，两台设备要在同一个 Wi-Fi。",
        ),
        TutorialPage(
            icon = Icons.Outlined.VideoLibrary,
            title = "模型怎么选",
            body = listOf(
                "名字带 image 的负责出图，带 video 的负责出视频，其余是聊天模型。",
                "选中出图 / 出视频模型后，你发的那句话就是提示词，回复里直接是一张图或一段视频。",
            ),
            tip = "视频比图片慢得多（常见 1–2 分钟），队列满时会提示稍后再试。",
        ),
        TutorialPage(
            icon = Icons.Outlined.Memory,
            title = "上下文窗口",
            body = listOf(
                "指模型一次能记住多少 token。一个汉字大约 1 个 token。",
                "超出这个数，最早的消息会被丢掉 —— 表现就是「它忘了前面说过什么」。",
            ),
            tip = "日常 64k 够用；长文档翻译、整份代码一起看，再调到 128k 以上。",
        ),
        TutorialPage(
            icon = Icons.Outlined.Thermostat,
            title = "温度",
            body = listOf(
                "越低越稳：0.2 适合改代码、算数、照着格式输出。",
                "0.7 是日常聊天的平衡点；1.2 更发散，适合写文案、想点子。",
            ),
            tip = "温度只影响下一轮生成，改完立刻生效，不用重开对话。",
        ),
    )
} else {
    listOf(
        TutorialPage(
            icon = Icons.Outlined.Key,
            title = "What an API key is",
            body = listOf(
                "A long string that works like a key at your model provider.",
                "Every request is billed to it, so treat it as a password: never paste it in a group chat or a public repo.",
            ),
            tip = "Enter it once. It is stored in the encrypted local store, not in logs or backups.",
        ),
        TutorialPage(
            icon = Icons.Outlined.Tune,
            title = "Already wired up",
            body = listOf(
                "Two channels ship built in: Agnes with 5 free models (2 text, 2 image, 1 video) " +
                    "and Zhipu GLM with 3 (text, vision, image).",
                "No setup needed — just start chatting. Paid models are left out on purpose.",
            ),
            tip = "Built-in entries only expose model parameters. Keys and URLs are managed by the app; " +
                "create your own provider if you want to use your account.",
        ),
        TutorialPage(
            icon = Icons.Outlined.Memory,
            title = "Add your own provider",
            body = listOf(
                "Enter the base URL up to /v1, without /chat/completions.",
                "Tap Test, then Fetch models, then pick a default model.",
            ),
            tip = "Local Ollama / LM Studio: http://<pc-ip>:11434/v1, both devices on the same Wi-Fi.",
        ),
        TutorialPage(
            icon = Icons.Outlined.VideoLibrary,
            title = "Picking a model",
            body = listOf(
                "Names containing image generate pictures, video generates clips, the rest chat.",
                "With an image or video model selected, your message becomes the prompt.",
            ),
            tip = "Video takes 1–2 minutes and may queue up; you will be told to retry later.",
        ),
        TutorialPage(
            icon = Icons.Outlined.Memory,
            title = "Context window",
            body = listOf(
                "How many tokens the model can remember at once.",
                "Beyond that, the oldest messages are dropped — which looks like forgetting.",
            ),
            tip = "64k is fine for daily use; raise it for long documents or whole files.",
        ),
        TutorialPage(
            icon = Icons.Outlined.Thermostat,
            title = "Temperature",
            body = listOf(
                "Lower is steadier: 0.2 for code, maths and strict formats.",
                "0.7 balances daily chat; 1.2 is more creative for writing and ideas.",
            ),
            tip = "It applies to the next generation only — no need to restart the chat.",
        ),
    )
}
