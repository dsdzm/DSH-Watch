package com.dshwatch.ui.screens.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.domain.help.HelpBlock
import com.dshwatch.domain.help.HelpContent
import com.dshwatch.domain.help.HelpKind
import com.dshwatch.domain.help.HelpTopic
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.WearCardRow
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.i18n.Zh
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.WearList
import com.dshwatch.ui.wear.rememberKeptListState
import com.dshwatch.ui.wear.wearVerticalScroll

/**
 * 帮助目录。
 *
 * 为什么是「列表 + 详情」而不是一个长页面：手表一屏十几行，十一条教程连排
 * 会变成一条滚不到底的带子；而且用户来找的往往是**某一个**格式，
 * 目录能让他三秒定位，长页面只能一路滚。
 */
@Composable
fun HelpListScreen(nav: WatchNavigator) {
    val s = LocalStrings.current
    val chinese = s === Zh

    WatchScaffold(title = s.helpTitle, onBack = { nav.pop() }) {
        WearList(
            state = rememberKeptListState("help"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(HelpContent.topics.size) { index ->
                val topic = HelpContent.topics[index]
                WearCardRow(
                    icon = iconOf(topic.id),
                    title = topic.title.of(chinese),
                    subtitle = topic.summary.of(chinese),
                    trailing = "›",
                    onClick = { nav.push(Screen.HelpTopic(topic.id)) },
                )
            }
        }
    }
}

/**
 * 单条教程。
 *
 * 全部内容一次性铺在一个可滚动列里：教程本身不长（每条 5~8 段），
 * 分页反而会让"照着做"变成反复翻页。代码样例单独用等宽字体 + 深色底，
 * 和正文拉开层次 —— 面板格式里全是竖线和缩进，混在正文里根本数不清。
 */
@Composable
fun HelpTopicScreen(topicId: String, nav: WatchNavigator) {
    val s = LocalStrings.current
    val chinese = s === Zh
    val clipboard = LocalClipboardManager.current
    val topic = HelpContent.find(topicId)
    /*
     * 教程 id 变了（比如降级安装后恢复的导航栈）就直接退回目录。
     * 退栈放进 LaunchedEffect：在组合过程中改导航会触发「重组中修改状态」，
     * 轻则一帧空白，重则被 Compose 判成死循环。
     */
    LaunchedEffect(topicId) {
        if (topic == null) nav.replaceTop(Screen.Help)
    }
    if (topic == null) return

    WatchScaffold(title = topic.title.of(chinese), onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            topic.blocks.forEach { block -> HelpBlockView(block, chinese) }
            Spacer(Modifier.height(10.dp))
            WatchGhostButton(
                text = s.helpCopyAll,
                onClick = { clipboard.setText(AnnotatedString(topic.plainText(chinese))) },
            )
        }
    }
}

@Composable
private fun HelpBlockView(block: HelpBlock, chinese: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val body = block.text.of(chinese)
    when (block.kind) {
        HelpKind.TEXT -> Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
            color = scheme.onSurface,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        HelpKind.BULLET -> Text(
            text = "· $body",
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 3.dp),
        )

        HelpKind.CODE -> Text(
            text = body,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            ),
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(scheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )

        HelpKind.NOTE -> Text(
            text = body,
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 15.sp),
            color = scheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(scheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

/** 目录图标：按主题 id 定，不按顺序，加一条教程不会让所有图标错位 */
private fun iconOf(id: String): ImageVector = when (id) {
    "start" -> Icons.Outlined.PlayCircleOutline
    "scene" -> Icons.Outlined.Theaters
    "panel" -> Icons.Outlined.Dashboard
    "choice" -> Icons.Outlined.ListAlt
    "html" -> Icons.Outlined.Code
    "transcript" -> Icons.Outlined.SwapVert
    "memory" -> Icons.Outlined.Memory
    "card" -> Icons.Outlined.Style
    "model" -> Icons.Outlined.SmartToy
    "update" -> Icons.Outlined.SystemUpdate
    else -> Icons.Outlined.BugReport
}
