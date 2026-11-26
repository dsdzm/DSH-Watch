package com.dshwatch.ui.screens.fish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 第一次进养鱼时的那一问。
 *
 * ## 为什么要有这一页，而不是"进来就算开启"
 *
 * 养鱼是唯一会在**用户不操作时联网**的模式：每天到点主动开口、每天写一条动态。
 * 以前的实现是"有默认安排就排闹钟"，于是用户只是点进来看看（甚至一句话没说），
 * 之后每天四次请求照发，而他从没同意过 —— 用的是他的额度，替他做了决定。
 *
 * 所以开启这件事必须是一次明确的点击，并且在这一页上把"会花什么"说清楚：
 * 会联网、会用你自己的模型、每天四点、随时可以关。
 *
 * ## 为什么关掉之后还留着这一页
 *
 * 关掉不是删除。用户回来时看到的应该是"要不要再开"，而不是一片空白 ——
 * 空白会让人以为历史也没了。这里只写一句「关掉期间她不会说话」，不动数据。
 */
@Composable
fun FishEnableCard(
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
    /** 「去别的模式」：手机关闭态顶栏没有模式图标，得在这里留一个出口 */
    onSwitchScene: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "养鱼",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "她会记得你说过的事，也会在每天的固定时间自己找你说话。",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "开启之后：",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "她会在每天 7:00 / 12:00 / 18:00 / 22:00 主动发消息，时间可以改；",
                "每天写一条只有你能看到的动态，内容是本机生成的；",
                "上面两件事都会联网，用你在养鱼设置里选的那家模型，按次计费；",
                "关掉这个开关，两件事都会立刻停下，聊过的内容一条都不会删。",
            ).forEach { line ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "· ",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onEnable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("开启养鱼", fontSize = 15.sp)
        }
        if (onSwitchScene != null) {
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onSwitchScene) {
                Text("去别的模式", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "以后可以在「养鱼设置」里随时关掉",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 手表上的同一问，用 M3E 的排版尺寸。
 *
 * 圆表盘一行只有十几个字，长段落会变成一坨；这里把细则压成三行，
 * 主句仍然是"她会在你不在的时候联网"。
 */
@Composable
fun FishEnablePanel(
    onEnable: () -> Unit,
    onBack: (() -> Unit)? = null,
    /**
     * 「先不开了，换个模式」。
     *
     * 关闭态曾经只有「开启」一个按钮 —— 不打算开的人就停在原地，
     * 顶栏也没有模式图标（那是正常态才有的），整屏无处可去。
     */
    onSwitchScene: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "要开始养鱼吗",
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "她会自己找你说话，也会每天联网写一条动态。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "都按次计费，随时能关。",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onEnable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("开启", fontSize = 14.sp)
        }
        if (onBack != null) {
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onBack) {
                Text("先不用", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (onSwitchScene != null) {
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onSwitchScene) {
                Text("去别的模式", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
