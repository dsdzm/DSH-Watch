package com.dshwatch.ui.screens.about

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.dshwatch.BuildConfig
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.AppChangelog
import com.dshwatch.domain.DeviceTier
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.KeyValueRow
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator

/** 作者信息是专有名词，不参与多语言切换 */
private const val AUTHOR_NAME = "东山の字幕"
private const val AUTHOR_EMAIL = "2332184782@qq.com"

/**
 * 关于页。
 *
 * 四张卡片：作者、应用介绍（技术栈 / 借鉴项目 / 功能）、更新日志、设备信息。
 *
 * 更新日志来自 [AppChangelog]，**发版时只需往那个列表最前面加一条** ——
 * 这里会自动渲染出来，不用维护两份文档。
 */
@Composable
fun AboutScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    val info = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        listOf(
            "型号" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "屏幕" to run {
                val dm = context.resources.displayMetrics
                "${dm.widthPixels}×${dm.heightPixels} @ ${dm.densityDpi}dpi"
            },
            "内存档" to "${am?.memoryClass ?: 0} MB ${if (am?.isLowRamDevice == true) "(低内存设备)" else ""}",
            "设备分级" to when (container.deviceTier) {
                DeviceTier.LOW -> s.tierLow
                DeviceTier.MID -> s.tierMid
                DeviceTier.HIGH -> s.tierHigh
            },
            "CPU 核心" to Runtime.getRuntime().availableProcessors().toString(),
        )
    }

    WatchScaffold(title = s.aboutTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = s.appName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = s.aboutDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------------- 关于作者 ----------------
            SectionHeader(s.aboutAuthor)
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = AUTHOR_NAME,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = AUTHOR_EMAIL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 手表上没法方便地长按选中文字，所以给一个一键复制
                    Chip(
                        text = if (copied) s.copied else s.copy,
                        onClick = {
                            clipboard.setText(AnnotatedString(AUTHOR_EMAIL))
                            copied = true
                        },
                    )
                }
                Spacer(Modifier.height(2.dp))
            }

            // ---------------- 应用介绍 ----------------
            SectionHeader(s.aboutIntro)
            SettingCard {
                InfoBlock(title = s.techStack, body = s.techStackText)
                InfoBlock(title = s.inspiredBy, body = s.inspiredByText)
                InfoBlock(title = s.appFeatures, body = s.appFeaturesText)
            }

            // ---------------- 更新日志 ----------------
            SectionHeader(s.aboutChangelog)
            AppChangelog.entries.forEach { entry ->
                SettingCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "v${entry.version}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = entry.date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    entry.highlights.forEach { line ->
                        Text(
                            text = "· $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                Spacer(Modifier.height(4.dp))
            }

            // ---------------- 设备信息 ----------------
            SectionHeader(s.deviceInfo)
            SettingCard {
                KeyValueRow(s.version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                info.forEach { (k, v) -> KeyValueRow(k, v) }
                Spacer(Modifier.height(2.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = s.sourceHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun InfoBlock(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
